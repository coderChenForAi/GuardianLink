package com.guardian.link.core.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.UUID

/**
 * 经典蓝牙 RFCOMM(SPP) 1 对 1 链路：被监护端做服务端、监护端做客户端（两台手机需先完成系统蓝牙配对）。
 * 连接建立后以 PING/PONG 保活；读异常或保活超时视为链路断开，发出 LinkLost 后自动回到连接循环等待重连。
 */
class BtLinkManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val peerAddress: String,
    private val isServer: Boolean,
) {

    sealed interface Event {
        data object LinkUp : Event
        data object LinkLost : Event
        data class Info(val msg: String) : Event
    }

    private val _events = MutableSharedFlow<Event>(replay = 4, extraBufferCapacity = 64)
    val events: SharedFlow<Event> = _events

    private val sppUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var linkUp = false
    @Volatile private var lostReported = true
    @Volatile private var running = false
    @Volatile private var lastPeerSeen = 0L

    fun start() {
        if (running) return
        running = true
        scope.launch(Dispatchers.IO) {
            if (isServer) serverLoop() else clientLoop()
        }
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
        socket = null
    }

    private fun adapter() = runCatching {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }.getOrNull()

    @SuppressLint("MissingPermission")
    private suspend fun serverLoop() {
        while (running) {
            val a = adapter()
            if (a == null || !a.isEnabled) {
                _events.tryEmit(Event.Info("蓝牙不可用，请开启蓝牙后重试"))
                delay(3_000)
                continue
            }
            val server = try {
                a.listenUsingRfcommWithServiceRecord("GuardianLink", sppUuid)
            } catch (t: Throwable) {
                _events.tryEmit(Event.Info("蓝牙监听失败：${t.message ?: "权限不足"}"))
                delay(3_000)
                continue
            }
            try {
                val sock = server.accept()
                serve(sock)
            } catch (t: Throwable) {
                Log.w(TAG, "accept failed: ${t.message}")
            } finally {
                runCatching { server.close() }
            }
            if (running) delay(1_500)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun clientLoop() {
        while (running) {
            val device = try {
                adapter()?.getRemoteDevice(peerAddress)
            } catch (t: Throwable) {
                null
            }
            if (device == null) {
                _events.tryEmit(Event.Info("找不到蓝牙设备，请确认已完成系统配对"))
                delay(3_000)
                continue
            }
            val sock = try {
                device.createRfcommSocketToServiceRecord(sppUuid)
            } catch (t: Throwable) {
                delay(2_000)
                continue
            }
            try {
                runCatching { adapter()?.cancelDiscovery() }
                sock.connect()
                serve(sock)
            } catch (t: Throwable) {
                runCatching { sock.close() }
                _events.tryEmit(Event.Info("等待连接：${t.message ?: "重试中"}"))
            }
            if (running) delay(2_000)
        }
    }

    private fun serve(sock: BluetoothSocket) {
        socket = sock
        lostReported = false
        lastPeerSeen = System.currentTimeMillis()
        var keepalive: Job? = null
        try {
            val reader = BufferedReader(InputStreamReader(sock.inputStream, Charsets.UTF_8))
            linkUp = true
            _events.tryEmit(Event.LinkUp)
            keepalive = scope.launch(Dispatchers.IO) {
                try {
                    while (isActive && linkUp && running) {
                        writeLine("PING")
                        if (System.currentTimeMillis() - lastPeerSeen > 15_000) throw IOException("peer stale")
                        delay(5_000)
                    }
                } catch (t: Throwable) {
                    reportLost()
                }
            }
            while (linkUp && running) {
                val line = reader.readLine() ?: throw IOException("stream closed")
                lastPeerSeen = System.currentTimeMillis()
                when (line.trim().uppercase()) {
                    "PING" -> writeLine("PONG")
                }
            }
        } catch (t: Throwable) {
            reportLost()
        } finally {
            linkUp = false
            keepalive?.cancel()
            runCatching { sock.close() }
            if (socket == sock) socket = null
        }
    }

    private fun reportLost() {
        if (running && !lostReported) {
            lostReported = true
            linkUp = false
            runCatching { socket?.close() }
            _events.tryEmit(Event.LinkLost)
        }
    }

    private fun writeLine(line: String) {
        val s = socket ?: throw IOException("no socket")
        s.outputStream.write((line + "\n").toByteArray(Charsets.UTF_8))
        s.outputStream.flush()
    }

    companion object {
        private const val TAG = "BtLink"
    }
}
