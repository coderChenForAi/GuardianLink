package com.guardian.link.core.sync

import android.util.Log
import com.guardian.link.data.ElderEntry
import com.guardian.link.data.EventStore
import com.guardian.link.data.GuardianEntry
import com.guardian.link.data.GuardianEvent
import com.guardian.link.data.PairingStore
import com.guardian.link.data.Role
import com.guardian.link.data.eventFromJson
import com.guardian.link.data.eventToJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap

/**
 * 局域网多监护人同步引擎（UDP 47910，与旧心跳共用端口，同一时间只启动其一）。
 *
 * 消息（JSON，均带 v2/fromId/fromName/ts）：
 *  discover/announce            —— 设备发现（与旧 PeerDiscoverer 兼容）
 *  pair_req{code}               —— 监护人发起配对（需老人机当前展示的 6 位配对码）
 *  pair_ack{groupId,token,...}  —— 老人机验证通过，签发该监护人独立 token
 *  ping{token} / pong           —— 监护人保活，刷新老人机上记录的地址
 *  event_push{groupId,token,event} —— 老人机向已授权监护人推送事件（未确认会在 ping 时有限补发）
 *  event_ack{token,eventId}     —— 监护人独立确认
 *
 * 安全边界（如实说明）：UDP 明文 + token 鉴权，可防"随手加入"，不能抵御同一局域网内的抓包重放；
 * 配对码是配对时刻的一次性人工校验，不是端到端加密。
 */
class LanSyncEngine(
    private val scope: CoroutineScope,
    private val pairing: PairingStore,
    private val selfRole: Role,
    private val selfName: String,
    private val elderEventStore: EventStore?,
    private val guardianEventStore: EventStore?,
    private val port: Int = 47910,
    private val onGuardianPaired: ((GuardianEntry) -> Unit)? = null,
    private val onEventReceived: ((GuardianEvent) -> Unit)? = null,
    private val onEventAcked: ((eventId: String, guardianId: String) -> Unit)? = null,
    private val onGuardianSeen: ((GuardianEntry) -> Unit)? = null,
) {

    @Volatile private var running = false
    private var socket: DatagramSocket? = null
    private var pingJob: Job? = null

    /** 老人机侧：各监护人最后出现的地址（由 ping/event_ack 刷新）。 */
    private val guardianAddrs = ConcurrentHashMap<String, InetSocketAddress>()

    fun start() {
        if (running) return
        running = true
        socket = try {
            DatagramSocket(port).apply {
                broadcast = true
                soTimeout = 1000
            }
        } catch (t: Throwable) {
            Log.w(TAG, "bind udp $port failed, fallback ephemeral", t)
            DatagramSocket().apply {
                broadcast = true
                soTimeout = 1000
            }
        }
        scope.launch(Dispatchers.IO) { receiveLoop() }
        if (selfRole == Role.GUARDIAN_DEVICE) {
            pingJob = scope.launch(Dispatchers.IO) { pingLoop() }
        }
    }

    fun stop() {
        running = false
        pingJob?.cancel()
        pingJob = null
        runCatching { socket?.close() }
        socket = null
    }

    // ------------------------------------------------------------ 老人机：推送事件

    fun pushEvent(event: GuardianEvent) {
        if (selfRole != Role.ELDER_DEVICE) return
        val root = pairing.snapshot()
        val s = socket ?: return
        for (g in root.guardians) {
            val addr = guardianAddrs[g.id] ?: continue
            val msg = base("event_push")
                .put("groupId", root.groupId)
                .put("token", g.token)
                .put("event", eventToJson(event))
            sendTo(msg, addr)
        }
    }

    /** 监护人 ping 进来时，把对方尚未确认且 24h 内的重要事件补发一次（有上限，防风暴）。 */
    private fun resendUnacked(g: GuardianEntry, addr: InetSocketAddress) {
        val store = elderEventStore ?: return
        val root = pairing.snapshot()
        val s = socket ?: return
        val cutoff = System.currentTimeMillis() - 24 * 3600_000L
        val targets = store.all()
            .filter {
                it.occurredAt >= cutoff && g.id !in it.ackedBy &&
                    (it.type == com.guardian.link.data.EventType.EXIT_HOME ||
                        it.type == com.guardian.link.data.EventType.RETURN_HOME)
            }
            .sortedByDescending { it.occurredAt }
            .take(10)
        for (e in targets) {
            val msg = base("event_push")
                .put("groupId", root.groupId)
                .put("token", g.token)
                .put("event", eventToJson(e))
            sendTo(msg, addr)
        }
    }

    // ------------------------------------------------------------ 监护人：发起配对

    /** 向指定 IP 的老人机发起配对；成功返回并持久化 ElderEntry。 */
    suspend fun pair(ip: String, code: String, timeoutMs: Long = 8_000): ElderEntry? =
        withContext(Dispatchers.IO) {
            runCatching {
                DatagramSocket().use { s ->
                    s.broadcast = true
                    s.soTimeout = 500
                    val req = base("pair_req").put("code", code.trim())
                    val data = req.toString().toByteArray(Charsets.UTF_8)
                    val deadline = System.currentTimeMillis() + timeoutMs
                    var result: ElderEntry? = null
                    while (System.currentTimeMillis() < deadline && result == null) {
                        runCatching {
                            s.send(DatagramPacket(data, data.size, InetAddress.getByName(ip), port))
                        }
                        val buf = ByteArray(65535)
                        val p = DatagramPacket(buf, buf.size)
                        try {
                            s.receive(p)
                        } catch (e: SocketTimeoutException) {
                            continue
                        }
                        val json = parsePacket(p) ?: continue
                        if (json.optString("type") == "pair_ack") {
                            val entry = ElderEntry(
                                id = json.optString("elderId"),
                                name = json.optString("elderName"),
                                token = json.getString("token"),
                                groupId = json.optString("groupId"),
                                host = p.address?.hostAddress ?: ip,
                                port = json.optInt("port", this@LanSyncEngine.port),
                                lastSeen = System.currentTimeMillis(),
                            )
                            pairing.setElder(entry)
                            result = entry
                        }
                    }
                    result
                }
            }.getOrNull()
        }

    // ------------------------------------------------------------ 收包循环

    private suspend fun receiveLoop() {
        val buf = ByteArray(65535)
        while (running) {
            val s = socket ?: break
            val p = DatagramPacket(buf, buf.size)
            try {
                s.receive(p)
            } catch (e: SocketTimeoutException) {
                continue
            } catch (t: Throwable) {
                break
            }
            val json = parsePacket(p) ?: continue
            val from = InetSocketAddress(p.address, p.port)
            when (json.optString("type")) {
                "discover" -> if (selfRole == Role.ELDER_DEVICE) {
                    sendTo(
                        base("announce")
                            .put("role", "elder")
                            .put("name", selfName)
                            .put("port", socket?.localPort ?: port),
                        from
                    )
                }
                "pair_req" -> handlePairReq(json, from)
                "ping" -> handlePing(json, from)
                "pong" -> {
                    pairing.touchElder(System.currentTimeMillis())
                }
                "event_push" -> handleEventPush(json, from)
                "event_ack" -> handleEventAck(json, from)
            }
        }
    }

    private fun handlePairReq(json: JSONObject, from: InetSocketAddress) {
        if (selfRole != Role.ELDER_DEVICE) return
        val code = json.optString("code")
        if (code != pairing.pairingCode()) return
        val fromId = json.optString("fromId").ifBlank { "unknown-" + from.address?.hostAddress }
        val entry = pairing.authorizeGuardian(fromId, json.optString("fromName").ifBlank { "监护人" })
        guardianAddrs[entry.id] = from
        val ack = base("pair_ack")
            .put("groupId", pairing.groupId())
            .put("token", entry.token)
            .put("elderId", pairing.deviceId())
            .put("elderName", selfName)
            .put("port", socket?.localPort ?: port)
        sendTo(ack, from)
        Log.i(TAG, "guardian paired: ${entry.name}")
        onGuardianPaired?.invoke(entry)
    }

    private fun handlePing(json: JSONObject, from: InetSocketAddress) {
        if (selfRole != Role.ELDER_DEVICE) return
        val g = pairing.findByToken(json.optString("token")) ?: return
        guardianAddrs[g.id] = from
        pairing.touch(g.id, System.currentTimeMillis())
        sendTo(base("pong"), from)
        onGuardianSeen?.invoke(g)
        resendUnacked(g, from)
    }

    private fun handleEventPush(json: JSONObject, from: InetSocketAddress) {
        if (selfRole != Role.GUARDIAN_DEVICE) return
        val elder = pairing.elder() ?: return
        if (json.optString("token") != elder.token) return
        val eventJson = json.optJSONObject("event") ?: return
        val event = runCatching { eventFromJson(eventJson) }.getOrNull() ?: return
        guardianEventStore?.upsert(event)
        sendTo(
            base("event_ack").put("token", elder.token).put("eventId", event.id),
            from
        )
        onEventReceived?.invoke(event)
    }

    private fun handleEventAck(json: JSONObject, from: InetSocketAddress) {
        if (selfRole != Role.ELDER_DEVICE) return
        val g = pairing.findByToken(json.optString("token")) ?: return
        guardianAddrs[g.id] = from
        val eventId = json.optString("eventId")
        if (eventId.isNotBlank()) {
            elderEventStore?.markAcked(eventId, g.id)
            onEventAcked?.invoke(eventId, g.id)
        }
    }

    private suspend fun pingLoop() {
        while (running) {
            val elder = pairing.elder()
            if (elder != null && elder.host.isNotBlank()) {
                val msg = base("ping").put("token", elder.token)
                runCatching {
                    socket?.send(
                        DatagramPacket(
                            msg.toString().toByteArray(Charsets.UTF_8),
                            msg.toString().toByteArray(Charsets.UTF_8).size,
                            InetAddress.getByName(elder.host),
                            elder.port,
                        )
                    )
                }
            }
            delay(20_000)
        }
    }

    // ------------------------------------------------------------ 基础

    private fun base(type: String): JSONObject = JSONObject()
        .put("v", 2)
        .put("type", type)
        .put("fromId", pairing.deviceId())
        .put("fromName", selfName)
        .put("ts", System.currentTimeMillis())

    private fun parsePacket(p: DatagramPacket): JSONObject? = runCatching {
        JSONObject(String(p.data, p.offset, p.length, Charsets.UTF_8))
    }.getOrNull()

    private fun sendTo(json: JSONObject, addr: InetSocketAddress) {
        runCatching {
            val s = socket ?: return
            val data = json.toString().toByteArray(Charsets.UTF_8)
            s.send(DatagramPacket(data, data.size, addr.address, addr.port))
        }.onFailure { Log.w(TAG, "udp send failed: ${it.message}") }
    }

    companion object {
        private const val TAG = "LanSync"
    }
}
