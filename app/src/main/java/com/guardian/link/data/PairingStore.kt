package com.guardian.link.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class GuardianEntry(
    val id: String,
    val name: String,
    val token: String,
    val lastSeen: Long = 0,
)

/** 监护人设备侧保存的"已配对老人设备"。 */
data class ElderEntry(
    val id: String,
    val name: String,
    val token: String,
    val groupId: String,
    val host: String = "",
    val port: Int = 47910,
    val lastSeen: Long = 0,
)

/**
 * 设备身份、守护组与已授权监护人列表的持久化。
 * - deviceId：本机唯一标识
 * - groupId：守护组标识（首次使用生成，配对双方共享）
 * - pairingCode：6 位配对码（老人机展示、监护人输入）；可重新生成
 * - guardians：已授权监护人（各自持独立 token）
 * 纯 JVM 实现。token 属敏感凭据，仅存本机，不写日志。
 */
class PairingStore(private val file: File) {

    private val lock = Any()

    init {
        file.parentFile?.mkdirs()
    }

    data class Snapshot(
        val deviceId: String,
        val groupId: String,
        val pairingCode: String,
        val guardians: List<GuardianEntry>,
    )

    @Synchronized
    fun snapshot(): Snapshot {
        val root = load()
        return Snapshot(root.deviceId, root.groupId, root.pairingCode, root.guardians)
    }

    @Synchronized
    fun deviceId(): String = load().deviceId

    @Synchronized
    fun groupId(): String = load().groupId

    @Synchronized
    fun pairingCode(): String = load().pairingCode

    @Synchronized
    fun regeneratePairingCode(): String {
        val root = load()
        val newCode = (100000..999999).random().toString()
        save(root.copy(pairingCode = newCode))
        return newCode
    }

    @Synchronized
    fun guardians(): List<GuardianEntry> = load().guardians

    /** 注册（或更新名字）一位监护人，签发其独立 token。 */
    @Synchronized
    fun authorizeGuardian(id: String, name: String): GuardianEntry {
        val root = load()
        val existing = root.guardians.firstOrNull { it.id == id }
        val entry = existing?.copy(name = name)
            ?: GuardianEntry(id = id, name = name, token = newToken())
        val others = root.guardians.filter { it.id != id }
        save(root.copy(guardians = others + entry))
        return entry
    }

    @Synchronized
    fun revokeGuardian(id: String) {
        val root = load()
        save(root.copy(guardians = root.guardians.filter { it.id != id }))
    }

    @Synchronized
    fun findByToken(token: String): GuardianEntry? = load().guardians.firstOrNull { it.token == token }

    @Synchronized
    fun touch(id: String, at: Long) {
        val root = load()
        save(root.copy(guardians = root.guardians.map { if (it.id == id) it.copy(lastSeen = at) else it }))
    }

    @Synchronized
    fun resetGroup() {
        val root = load()
        save(
            root.copy(
                groupId = "gl-" + UUID.randomUUID().toString().replace("-", "").take(10),
                pairingCode = (100000..999999).random().toString(),
                guardians = emptyList(),
            )
        )
    }

    // ---------------------------------------------------------- 监护人侧：已配对老人

    @Synchronized
    fun setElder(elder: ElderEntry) {
        val root = load()
        save(root.copy(elder = elder))
    }

    @Synchronized
    fun elder(): ElderEntry? = load().elder

    @Synchronized
    fun touchElder(at: Long) {
        val root = load()
        root.elder?.let { save(root.copy(elder = it.copy(lastSeen = at))) }
    }

    @Synchronized
    fun clearElder() {
        val root = load()
        save(root.copy(elder = null))
    }

    private fun newToken(): String = UUID.randomUUID().toString().replace("-", "")

    private data class Root(
        val deviceId: String,
        val groupId: String,
        val pairingCode: String,
        val guardians: List<GuardianEntry>,
        val elder: ElderEntry? = null,
    )

    private fun load(): Root = runCatching {
        if (!file.exists()) return newRoot()
        val text = file.readText()
        if (text.isBlank()) return newRoot()
        val j = JSONObject(text)
        val arr = j.optJSONArray("guardians")
        val guardians = arr?.let { a ->
            (0 until a.length()).mapNotNull { i ->
                runCatching {
                    val g = a.getJSONObject(i)
                    GuardianEntry(
                        id = g.getString("id"),
                        name = g.optString("name"),
                        token = g.getString("token"),
                        lastSeen = g.optLong("lastSeen", 0),
                    )
                }.getOrNull()
            }
        } ?: emptyList()
        Root(
            deviceId = j.optString("deviceId"),
            groupId = j.optString("groupId"),
            pairingCode = j.optString("pairingCode"),
            guardians = guardians,
            elder = j.optJSONObject("elder")?.let { e ->
                runCatching {
                    ElderEntry(
                        id = e.getString("id"),
                        name = e.optString("name"),
                        token = e.getString("token"),
                        groupId = e.optString("groupId"),
                        host = e.optString("host"),
                        port = e.optInt("port", 47910),
                        lastSeen = e.optLong("lastSeen", 0),
                    )
                }.getOrNull()
            },
        )
    }.getOrNull()?.takeIf { it.deviceId.isNotBlank() } ?: newRoot()

    private fun newRoot() = Root(
        deviceId = "dev-" + UUID.randomUUID().toString().replace("-", "").take(12),
        groupId = "gl-" + UUID.randomUUID().toString().replace("-", "").take(10),
        pairingCode = (100000..999999).random().toString(),
        guardians = emptyList(),
    )

    private fun save(root: Root) {
        val arr = JSONArray()
        root.guardians.forEach { g ->
            arr.put(
                JSONObject()
                    .put("id", g.id)
                    .put("name", g.name)
                    .put("token", g.token)
                    .put("lastSeen", g.lastSeen)
            )
        }
        val j = JSONObject()
            .put("deviceId", root.deviceId)
            .put("groupId", root.groupId)
            .put("pairingCode", root.pairingCode)
            .put("guardians", arr)
        root.elder?.let { e ->
            j.put(
                "elder",
                JSONObject()
                    .put("id", e.id)
                    .put("name", e.name)
                    .put("token", e.token)
                    .put("groupId", e.groupId)
                    .put("host", e.host)
                    .put("port", e.port)
                    .put("lastSeen", e.lastSeen)
            )
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(j.toString())
        if (!tmp.renameTo(file)) {
            file.writeText(j.toString())
            tmp.delete()
        }
    }
}
