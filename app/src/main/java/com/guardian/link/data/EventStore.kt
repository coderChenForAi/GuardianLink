package com.guardian.link.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 基于文件的事件存储：先持久化、后发送；原子写（临时文件+改名）。
 * 纯 JVM 实现（org.json + java.io），可单测。不做 Android 日志输出。
 */
class EventStore(private val file: File) {

    private val lock = Any()

    init {
        file.parentFile?.mkdirs()
    }

    @Synchronized
    fun all(): List<GuardianEvent> = load()

    @Synchronized
    fun find(id: String): GuardianEvent? = load().firstOrNull { it.id == id }

    @Synchronized
    fun upsert(event: GuardianEvent) {
        val list = load().toMutableList()
        val idx = list.indexOfFirst { it.id == event.id }
        if (idx >= 0) list[idx] = event else list.add(event)
        save(trim(list))
    }

    /** 已确认离家且未成功发送（待发送或可重试失败）的事件。 */
    @Synchronized
    fun pending(): List<GuardianEvent> = load().filter {
        it.sendStatus == SendStatus.PENDING || it.sendStatus == SendStatus.FAILED_RETRIABLE
    }

    @Synchronized
    fun markSent(id: String, at: Long): GuardianEvent? = mutate(id) { it.copy(sendStatus = SendStatus.SENT, sentAt = at, lastError = null) }

    @Synchronized
    fun markFailed(id: String, retryable: Boolean, error: String): GuardianEvent? = mutate(id) {
        it.copy(
            sendStatus = if (retryable) SendStatus.FAILED_RETRIABLE else SendStatus.FAILED_PERMANENT,
            retryCount = it.retryCount + 1,
            lastError = error.take(300),
        )
    }

    @Synchronized
    fun markAcked(id: String, guardianId: String): GuardianEvent? = mutate(id) {
        if (guardianId in it.ackedBy) it else it.copy(ackedBy = it.ackedBy + guardianId)
    }

    @Synchronized
    fun recent(limit: Int): List<GuardianEvent> = load().sortedByDescending { it.occurredAt }.take(limit)

    @Synchronized
    fun exists(id: String): Boolean = load().any { it.id == id }

    @Synchronized
    fun clear() {
        save(emptyList())
    }

    private fun mutate(id: String, f: (GuardianEvent) -> GuardianEvent): GuardianEvent? {
        val list = load().toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return null
        val updated = f(list[idx])
        list[idx] = updated
        save(trim(list))
        return updated
    }

    /** 本地最多保留 [MAX_EVENTS] 条，超出淘汰最旧事件（位置数据属于隐私数据，不做无限累积）。 */
    private fun trim(list: List<GuardianEvent>): List<GuardianEvent> =
        if (list.size <= MAX_EVENTS) list else list.sortedByDescending { it.occurredAt }.take(MAX_EVENTS)

    private fun load(): List<GuardianEvent> = runCatching {
        if (!file.exists()) return emptyList()
        val text = file.readText()
        if (text.isBlank()) return emptyList()
        val arr = JSONArray(text)
        (0 until arr.length()).mapNotNull { i ->
            runCatching { GuardianEvent.fromJson(arr.getJSONObject(i)) }.getOrNull()
        }
    }.getOrDefault(emptyList())

    private fun save(list: List<GuardianEvent>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(arr.toString())
        if (!tmp.renameTo(file)) {
            file.writeText(arr.toString())
            tmp.delete()
        }
    }

    companion object {
        const val MAX_EVENTS = 200
    }
}
