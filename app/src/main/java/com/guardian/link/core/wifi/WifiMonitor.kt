package com.guardian.link.core.wifi

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** 监听 WiFi 连接状态；断开后经过宽限期仍未恢复则发出 lostConfirmed（防路由抖动/漫游误报）。 */
class WifiMonitor(private val scope: CoroutineScope) {

    private val _connected = MutableStateFlow<Boolean?>(null)
    val connected: StateFlow<Boolean?> = _connected

    private val _lostConfirmed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val lostConfirmed: SharedFlow<Unit> = _lostConfirmed

    private var cm: ConnectivityManager? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var graceJob: Job? = null
    private var graceMs = 5_000L

    fun start(context: Context, graceMs: Long) {
        this.graceMs = graceMs
        stop()
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm = manager
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = markConnected()
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = markConnected()
            override fun onLost(network: Network) = markLost()
        }
        callback = cb
        manager.registerNetworkCallback(
            NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
            cb
        )
    }

    fun stop() {
        runCatching { callback?.let { cm?.unregisterNetworkCallback(it) } }
        callback = null
        cm = null
        graceJob?.cancel()
        graceJob = null
        _connected.value = null
    }

    private fun markConnected() {
        graceJob?.cancel()
        graceJob = null
        if (_connected.value != true) _connected.value = true
    }

    private fun markLost() {
        if (_connected.value == false) return
        Log.i(TAG, "wifi lost, grace ${graceMs}ms")
        _connected.value = false
        graceJob?.cancel()
        graceJob = scope.launch {
            delay(graceMs)
            _lostConfirmed.tryEmit(Unit)
        }
    }

    companion object {
        private const val TAG = "WifiMonitor"
    }
}
