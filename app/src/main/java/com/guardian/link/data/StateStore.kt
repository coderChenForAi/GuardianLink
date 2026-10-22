package com.guardian.link.data

import org.json.JSONObject
import java.io.File

/** 监护状态机快照的持久化（服务/设备重启后恢复，避免重复告警同一离家事件）。 */
class StateStore(private val file: File) {

    private val lock = Any()

    init {
        file.parentFile?.mkdirs()
    }

    @Synchronized
    fun save(state: String, episodeId: String?, beforeStale: String?) {
        val j = JSONObject()
            .put("state", state)
            .put("episodeId", episodeId ?: "")
            .put("beforeStale", beforeStale ?: "")
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(j.toString())
        if (!tmp.renameTo(file)) {
            file.writeText(j.toString())
            tmp.delete()
        }
    }

    @Synchronized
    fun load(): Triple<String, String?, String?>? = runCatching {
        if (!file.exists()) return null
        val text = file.readText()
        if (text.isBlank()) return null
        val j = JSONObject(text)
        val state = j.optString("state").ifBlank { return null }
        val ep = j.optString("episodeId").ifBlank { null }
        val bs = j.optString("beforeStale").ifBlank { null }
        Triple(state, ep, bs)
    }.getOrNull()
}
