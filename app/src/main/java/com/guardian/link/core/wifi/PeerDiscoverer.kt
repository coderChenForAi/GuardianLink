package com.guardian.link.core.wifi

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/**
 * UDP 广播发现同一局域网内已开启监护的对端。
 * 注意：要求对端 App 的心跳服务正在运行（即对端已点"开始监护"），且路由器未开 AP 隔离。
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
                    val targets = listOf(
                        "255.255.255.255",
                        "192.168.0.255",
                        "192.168.1.255",
                        "192.168.2.255",
                        "192.168.31.255",
                        "192.168.43.255",
                        "192.168.50.255",
                    )
                    val deadline = System.currentTimeMillis() + timeoutMs
                    var round = 0
                    while (System.currentTimeMillis() < deadline) {
                        if (round % 4 == 0) {
                            for (t in targets) {
                                runCatching {
                                    s.send(DatagramPacket(data, data.size, InetAddress.getByName(t), peerPort))
                                }
                            }
                        }
                        round++
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
}
