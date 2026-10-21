package com.guardian.link.core.locate

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume

data class Loc(val lat: Double, val lng: Double, val accuracyM: Float, val time: Long)

/** 定位：优先 FusedLocationProvider（GMS）；不可用/失败时回退系统 LocationManager 的 lastKnown（兼容无 GMS 机型）。 */
class LocationProvider(private val context: Context) {

    fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    suspend fun current(timeoutMs: Long = 20_000): Loc? {
        if (!hasPermission()) return null
        fusedCurrent(timeoutMs)?.let { return it }
        return platformLastKnown()
    }

    @SuppressLint("MissingPermission")
    fun periodic(intervalMs: Long): Flow<Loc> = callbackFlow {
        if (!hasPermission()) {
            close()
            return@callbackFlow
        }
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { trySend(it.toLoc()) }
            }
        }
        var registered = false
        try {
            val client = LocationServices.getFusedLocationProviderClient(context)
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
                .setMinUpdateIntervalMillis(intervalMs / 2)
                .build()
            client.requestLocationUpdates(request, callback, ContextCompat.getMainExecutor(context))
            registered = true
        } catch (t: Throwable) {
            close(t)
        }
        awaitClose {
            if (registered) {
                runCatching {
                    LocationServices.getFusedLocationProviderClient(context).removeLocationUpdates(callback)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun fusedCurrent(timeoutMs: Long): Loc? = try {
        withTimeout(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                try {
                    val client = LocationServices.getFusedLocationProviderClient(context)
                    val executor = ContextCompat.getMainExecutor(context)
                    client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token)
                        .addOnSuccessListener(executor) { loc ->
                            if (cont.isActive) cont.resume(loc?.toLoc())
                        }
                        .addOnFailureListener(executor) {
                            if (cont.isActive) cont.resume(null)
                        }
                } catch (t: Throwable) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
    } catch (t: Throwable) {
        null
    }

    @SuppressLint("MissingPermission")
    private fun platformLastKnown(): Loc? = try {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        lm.getProviders(true)
            .mapNotNull { p -> @Suppress("DEPRECATION") lm.getLastKnownLocation(p) }
            .maxByOrNull { it.time }
            ?.toLoc()
    } catch (t: Throwable) {
        null
    }

    private fun Location.toLoc() = Loc(latitude, longitude, accuracy, time)
}
