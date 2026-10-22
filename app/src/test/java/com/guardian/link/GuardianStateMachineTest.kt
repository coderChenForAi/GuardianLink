package com.guardian.link

import com.guardian.link.core.state.GuardianState
import com.guardian.link.core.state.GuardianStateMachine
import com.guardian.link.core.state.GuardianTransition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardianStateMachineTest {

    private fun machine() = GuardianStateMachine()

    @Test
    fun `full away and return cycle`() {
        val m = machine()
        val now = 1000L
        assertEquals(1, m.onRawOutside(now).count { it is GuardianTransition.ExitSuspected })
        assertEquals(GuardianState.EXIT_SUSPECTED, m.state)

        val exits = m.onExitConfirmed(now)
        val exit = exits.filterIsInstance<GuardianTransition.ExitConfirmed>().single()
        assertEquals(GuardianState.AWAY_ALERTED, m.state)
        assertTrue(exit.episodeId.isNotBlank())

        m.onRawInside(now)
        assertEquals(GuardianState.RETURN_SUSPECTED, m.state)

        val returns = m.onReturnConfirmed(now)
        val ret = returns.filterIsInstance<GuardianTransition.ReturnConfirmed>().single()
        assertEquals(exit.episodeId, ret.episodeId)
        assertEquals(GuardianState.HOME_SAFE, m.state)
        assertNull(m.episodeId)
    }

    @Test
    fun `raw outside in away states does not reset to suspected`() {
        val m = machine()
        m.onRawOutside(1L)
        m.onExitConfirmed(2L)
        val stateAfterExit = m.state
        m.onRawOutside(3L)
        assertEquals(stateAfterExit, m.state)
    }

    @Test
    fun `stale preserves and restores previous state`() {
        val m = machine()
        m.onRawOutside(1L)
        m.onExitConfirmed(2L)
        m.onStale(3L)
        assertEquals(GuardianState.LOCATION_STALE, m.state)
        // STALE 中不产生新的进出迁移
        assertTrue(m.onRawOutside(4L).isEmpty())
        assertTrue(m.onRawInside(4L).isEmpty())
        val resumed = m.onFreshFix(5L)
        assertEquals(1, resumed.count { it is GuardianTransition.ExitedStale })
        assertEquals(GuardianState.AWAY_ALERTED, m.state)
    }

    @Test
    fun `episode id is stable across repeated confirms and unique across episodes`() {
        val m = machine()
        m.onExitConfirmed(1L)
        val ep1 = m.episodeId
        m.onExitConfirmed(2L)
        assertEquals(ep1, m.episodeId)
        m.onRawInside(3L)
        m.onReturnConfirmed(4L)
        assertNull(m.episodeId)
        m.onRawOutside(5L)
        m.onExitConfirmed(6L)
        assertNotEquals(ep1, m.episodeId)
    }

    @Test
    fun `restore keeps away episode without re-alert`() {
        val m = machine()
        m.restore(GuardianState.AWAY_TRACKING, "episode-1", null)
        assertEquals(GuardianState.AWAY_TRACKING, m.state)
        assertEquals("episode-1", m.episodeId)
        // 恢复后直接返家流程可用
        m.onRawInside(1L)
        val returns = m.onReturnConfirmed(2L)
        assertEquals("episode-1", returns.filterIsInstance<GuardianTransition.ReturnConfirmed>().single().episodeId)
    }
}
