package com.guardian.link

import com.guardian.link.data.EventStore
import com.guardian.link.data.EventType
import com.guardian.link.data.GuardianEvent
import com.guardian.link.data.SendStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EventStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store() = EventStore(tmp.newFile("events.json"))

    private fun event(id: String, type: EventType = EventType.EXIT_HOME, at: Long = 1L) = GuardianEvent(
        id = id, type = type, occurredAt = at, sourceDeviceId = "dev-test",
        lat = 30.0, lng = 120.0, accuracyM = 10f, coord = "GCJ02",
    )

    @Test
    fun `persist before send and pending reflects state`() {
        val s = store()
        s.upsert(event("e1").copy(sendStatus = SendStatus.PENDING))
        assertEquals(1, s.pending().size)
        s.markSent("e1", 99L)
        assertEquals(0, s.pending().size)
        val e = s.find("e1")
        assertNotNull(e)
        assertEquals(SendStatus.SENT, e!!.sendStatus)
        assertEquals(99L, e.sentAt)
    }

    @Test
    fun `upsert same id does not duplicate`() {
        val s = store()
        s.upsert(event("e1"))
        s.upsert(event("e1", at = 2L))
        assertEquals(1, s.all().size)
        assertEquals(2L, s.find("e1")!!.occurredAt)
    }

    @Test
    fun `retryable and permanent failures recorded`() {
        val s = store()
        s.upsert(event("e1").copy(sendStatus = SendStatus.PENDING))
        s.markFailed("e1", retryable = true, error = "HTTP 500")
        assertEquals(1, s.pending().size)
        assertEquals("HTTP 500", s.find("e1")!!.lastError)
        s.markFailed("e1", retryable = false, error = "飞书业务错误")
        assertEquals(0, s.pending().size)
        assertEquals(SendStatus.FAILED_PERMANENT, s.find("e1")!!.sendStatus)
        assertEquals(2, s.find("e1")!!.retryCount)
    }

    @Test
    fun `survives reload from disk`() {
        val file = tmp.newFile("events.json")
        val s1 = EventStore(file)
        s1.upsert(event("persist-me", EventType.RETURN_HOME, at = 42L))
        val s2 = EventStore(file)
        val loaded = s2.find("persist-me")
        assertNotNull(loaded)
        assertEquals(EventType.RETURN_HOME, loaded!!.type)
        assertEquals(42L, loaded.occurredAt)
    }

    @Test
    fun `markAcked is idempotent per guardian`() {
        val s = store()
        s.upsert(event("e1"))
        s.markAcked("e1", "g1")
        s.markAcked("e1", "g1")
        assertEquals(listOf("g1"), s.find("e1")!!.ackedBy)
    }

    @Test
    fun `recent sorted desc and trimmed`() {
        val s = store()
        for (i in 0 until EventStore.MAX_EVENTS + 5) {
            s.upsert(event("e$i", at = i.toLong()))
        }
        val all = s.all()
        assertEquals(EventStore.MAX_EVENTS, all.size)
        assertFalse(all.any { it.id == "e0" })
        val recent = s.recent(3)
        assertEquals(listOf("e${EventStore.MAX_EVENTS + 4}", "e${EventStore.MAX_EVENTS + 3}", "e${EventStore.MAX_EVENTS + 2}"), recent.map { it.id })
    }

    @Test
    fun `missing id returns null`() {
        assertNull(store().find("nope"))
    }
}
