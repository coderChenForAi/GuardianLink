package com.guardian.link.core.wifi

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/**
 * UDP 广播发现同一局域网内已开启监护的对端。
 *
 * 发现策略（按可靠性递进）：
 * 1. 255.255.255.255 全网广播 + 本机所在子网的定向广播（从网卡实际地址计算，不猜网段）+ 常见网段兜底；
 * 2. 广播无应答时，自动降级为对本机所在 /24 网段 1..254 逐 IP 单播探测
 *    （多数路由器/AP 会丢弃或隔离广播帧，但单播通常可达）。
 *
 * 注意：要求对端 App 的监护服务正在运行（对端已点"开始监护"）。
 */
object PeerDiscoverer {

    data class Candidate(val ip: String, val name: String)

    suspend fun discover(timeoutMs: Long = 5_000, peerPort: Int = 47910): List<Candidate> =
        withContext(Dispatchers.IO) {
            val found = LinkedHashMap<String, Candidate>()
            runCatching {
                DatagramSocket().use { s ->
                    s.broadcast = true
                    s.soTimeout = 400
                    val req = JSONObject()
                        .put("v", 1)
                        .put("type", "discover")
                        .put("ts", System.currentTimeMillis())
                    val data = req.toString().toByteArray(Charsets.UTF_8)
                    val locals = localNetworks()
                    val targets = LinkedHashSet<String>().apply {
                        add("255.255.255.255")
                        locals.forEach { add(it.broadcast) }
                        addAll(DEFAULT_TARGETS)
                    }
                    val deadline = System.currentTimeMillis() + timeoutMs
                    // 前 2/3 时间发广播，最后 1/3 仍无应答则单播扫描 /24 网段兜底
                    val sweepAt = deadline - timeoutMs / 3
                    var swept = false
                    var lastBroadcast = 0L
                    while (System.currentTimeMillis() < deadline) {
                        val nowMs = System.currentTimeMillis()
                        if (nowMs - lastBroadcast >= 1_200) {
                            for (t in targets) {
                                runCatching {
                                    s.send(DatagramPacket(data, data.size, InetAddress.getByName(t), peerPort))
                                }
                            }
                            lastBroadcast = nowMs
                        }
                        if (!swept && nowMs >= sweepAt && found.isEmpty() && locals.isNotEmpty()) {
                            swept = true
                            sweepSubnet(s, locals, data, peerPort)
                        }
                        val buf = ByteArray(2048)
                        val p = DatagramPacket(buf, buf.size)
                        try {
                            s.receive(p)
                        } catch (e: SocketTimeoutException) {
                            continue
                        }
                        val json = runCatching {
                            JSONObject(String(p.data, p.offset, p.length, Charsets.UTF_8))
                        }.getOrNull() ?: continue
                        if (json.optString("type") == "announce") {
                            val ip = p.address?.hostAddress ?: continue
                            if (!found.containsKey(ip)) {
                                found[ip] = Candidate(ip, json.optString("name", "未知设备"))
                            }
                        }
                    }
                }
            }
            found.values.toList()
        }

    private data class LocalNet(val baseIp: Int, val broadcast: String)

    /** 本机所有 IPv4 网段：网络基址（int）+ 定向广播地址，跳过回环/未启用接口。 */
    private fun localNetworks(): List<LocalNet> {
        val nets = ArrayList<LocalNet>()
        try {
            val e = NetworkInterface.getNetworkInterfaces()
            while (e.hasMoreElements()) {
                val nif = e.nextElement()
                if (!nif.isUp || nif.isLoopback) continue
                for (ia in nif.interfaceAddresses) {
                    val addr = ia.address as? Inet4Address ?: continue
                    val bc: InetAddress = ia.broadcast ?: continue
                    nets.add(LocalNet(ipInt(addr), bc.hostAddress))
                }
            }
        } catch (_: Throwable) {
        }
        return nets.distinctBy { it.baseIp }
    }

    /** 对本机所在 /24 网段 1..254 逐 IP 单播 discover，跳过本机自身地址。 */
    private fun sweepSubnet(s: DatagramSocket, nets: List<LocalNet>, data: ByteArray, port: Int) {
        val self = HashSet<Int>(nets.map { it.baseIp })
        val bases = nets.map { it.baseIp and 0xFFFFFF00.toInt() }.distinct()
        for (base in bases) {
            for (h in 1..254) {
                val ip = base or h
                if (ip in self) continue
                runCatching {
                    val bytes = byteArrayOf(
                        ((ip ushr 24) and 0xFF).toByte(),
                        ((ip ushr 16) and 0xFF).toByte(),
                        ((ip ushr 8) and 0xFF).toByte(),
                        (ip and 0xFF).toByte(),
                    )
                    s.send(DatagramPacket(data, data.size, InetAddress.getByAddress(bytes), port))
                }
            }
        }
    }

    private fun ipInt(a: Inet4Address): Int {
        val b = a.address
        return ((b[0].toInt() and 0xFF) shl 24) or
            ((b[1].toInt() and 0xFF) shl 16) or
            ((b[2].toInt() and 0xFF) shl 8) or
            (b[3].toInt() and 0xFF)
    }

    private val DEFAULT_TARGETS = listOf(
        "192.168.0.255",
        "192.168.1.255",
        "192.168.2.255",
        "192.168.31.255",
        "192.168.43.255",
        "192.168.50.255",
    )
}
