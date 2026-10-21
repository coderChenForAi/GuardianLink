package com.guardian.link.core.wifi

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/**
 * WiFi 模式下的对称心跳：两端各自向对端发 UDP 心跳并回 ACK，任一端收到对端任意包即视为对端在线。
 * 已连通后超过 lostAfterMs 未收到对端任何包 → PeerLost；之后再次收到 → PeerSeen。
 * 同时兼任 discover 报文的应答端（对端扫描时回复 announce）。
 */
class HeartbeatEngine(
    private val scope: CoroutineScope,
    private val peerIp: String,
    private val selfName: String,
    private val intervalMs: Long = 5_000,
    private val lostAfterMs: Long = 15_000,
    private val port: Int = 47910,
) {

    sealed interface Event {
        data object PeerSeen : Event
        data object PeerLost : Event
    }

    private val _events = MutableSharedFlow<Event>(replay = 4, extraBufferCapacity = 64)
    val events: SharedFlow<Event> = _events

    private var socket: DatagramSocket? = null

    @Volatile private var lastSeen = 0L
    @Volatile private var seenOnce = false
    @Volatile private var lostEmitted = false
    @Volatile private var running = false
    private var seq = 0L

    fun start() {
        if (running) return
        running = true
        socket = try {
            DatagramSocket(port).apply {
                broadcast = true
                soTimeout = 1000
            }
        } catch (t: Throwable) {
            Log.w(TAG, "bind udp $port failed, fallback to ephemeral port", t)
            DatagramSocket().apply {
                broadcast = true
                soTimeout = 1000
            }
        }
        scope.launch(Dispatchers.IO) { receiveLoop() }
        scope.launch(Dispatchers.IO) { sendLoop() }
        scope.launch(Dispatchers.IO) { watchLoop() }
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
        socket = null
    }

    private suspend fun receiveLoop() {
        val buf = ByteArray(2048)
        while (running) {
            val s = socket ?: break
            val packet = DatagramPacket(buf, buf.size)
            try {
                s.receive(packet)
            } catch (e: SocketTimeoutException) {
                continue
            } catch (t: Throwable) {
                break
            }
            val json = runCatching {
                JSONObject(String(packet.data, packet.offset, packet.length, Charsets.UTF_8))
            }.getOrNull() ?: continue
            val fromIp = packet.address?.hostAddress ?: continue
            when (json.optString("type")) {
                "hb" -> {
                    onPeerSeen()
                    val ack = JSONObject()
                        .put("v", 1)
                        .put("type", "ack")
                        .put("seq", json.optLong("seq"))
                        .put("from", selfName)
                        .put("ts", now())
                    sendText(ack.toString(), fromIp, packet.port)
                }
                "ack" -> onPeerSeen()
                "discover" -> {
                    if (running) {
                        val announce = JSONObject()
                            .put("v", 1)
                            .put("type", "announce")
                            .put("name", selfName)
                            .put("ts", now())
                        sendText(announce.toString(), fromIp, packet.port)
                    }
                }
            }
        }
    }

    private suspend fun sendLoop() {
        while (running) {
            if (peerIp.isNotBlank()) {
                seq++
                val hb = JSONObject()
                    .put("v", 1)
                    .put("type", "hb")
                    .put("seq", seq)
                    .put("from", selfName)
                    .put("ts", now())
                sendText(hb.toString(), peerIp, port)
            }
            delay(intervalMs)
        }
    }

    private suspend fun watchLoop() {
        while (running) {
            delay(2_000)
            val last = lastSeen
            if (last == 0L) continue
            if (!lostEmitted && now() - last > lostAfterMs) {
                lostEmitted = true
                Log.w(TAG, "peer lost (no packet for ${now() - last}ms)")
                _events.tryEmit(Event.PeerLost)
            }
        }
    }

    private fun onPeerSeen() {
        lastSeen = now()
        if (!seenOnce) {
            seenOnce = true
            lostEmitted = false
            _events.tryEmit(Event.PeerSeen)
        } else if (lostEmitted) {
            lostEmitted = false
            _events.tryEmit(Event.PeerSeen)
        }
    }

    private fun sendText(text: String, host: String, port: Int) {
        runCatching {
            val s = socket ?: return
            val data = text.toByteArray(Charsets.UTF_8)
            s.send(DatagramPacket(data, data.size, InetAddress.getByName(host), port))
        }.onFailure { Log.w(TAG, "udp send failed: ${it.message}") }
    }

    private fun now() = System.currentTimeMillis()

    companion object {
        private const val TAG = "Heartbeat"
    }
}
