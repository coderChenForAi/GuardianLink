package com.guardian.link.core.state

import java.util.UUID

/** 老人监护宏观状态。设备连接状态（心跳/蓝牙）不参与本状态机。 */
enum class GuardianState {
    HOME_SAFE,
    EXIT_SUSPECTED,
    AWAY_ALERTED,
    AWAY_TRACKING,
    RETURN_SUSPECTED,
    LOCATION_STALE,
}

sealed interface GuardianTransition {
    data class ExitSuspected(val at: Long) : GuardianTransition
    data class ExitConfirmed(val episodeId: String, val at: Long) : GuardianTransition
    data class ReturnSuspected(val at: Long) : GuardianTransition
    data class ReturnConfirmed(val episodeId: String, val at: Long) : GuardianTransition
    data class EnteredStale(val previous: GuardianState, val at: Long) : GuardianTransition
    data class ExitedStale(val resumed: GuardianState, val at: Long) : GuardianTransition
}

/**
 * 监护状态机：所有离家/返家/定位异常状态的唯一权威来源，避免多处维护互相冲突的状态。
 * 纯 JVM 实现，可单测。支持从持久化快照恢复（服务重启/设备重启后不重复告警同一事件）。
 */
class GuardianStateMachine {

    var state: GuardianState = GuardianState.HOME_SAFE
        private set
    var episodeId: String? = null
        private set
    private var stateBeforeStale: GuardianState? = null

    fun onRawOutside(now: Long): List<GuardianTransition> {
        if (state == GuardianState.LOCATION_STALE) return emptyList()
        return when (state) {
            GuardianState.HOME_SAFE -> {
                state = GuardianState.EXIT_SUSPECTED
                listOf(GuardianTransition.ExitSuspected(now))
            }
            else -> emptyList()
        }
    }

    fun onExitConfirmed(now: Long): List<GuardianTransition> {
        if (state == GuardianState.LOCATION_STALE) return emptyList()
        val id = episodeId ?: UUID.randomUUID().toString()
        episodeId = id
        state = GuardianState.AWAY_ALERTED
        return listOf(GuardianTransition.ExitConfirmed(id, now))
    }

    fun onRawInside(now: Long): List<GuardianTransition> {
        if (state == GuardianState.LOCATION_STALE) return emptyList()
        return when (state) {
            GuardianState.AWAY_ALERTED, GuardianState.AWAY_TRACKING -> {
                state = GuardianState.RETURN_SUSPECTED
                listOf(GuardianTransition.ReturnSuspected(now))
            }
            else -> emptyList()
        }
    }

    fun onReturnConfirmed(now: Long): List<GuardianTransition> {
        if (state == GuardianState.LOCATION_STALE) return emptyList()
        val ep = episodeId
        return if (state == GuardianState.RETURN_SUSPECTED && ep != null) {
            state = GuardianState.HOME_SAFE
            episodeId = null
            listOf(GuardianTransition.ReturnConfirmed(ep, now))
        } else {
            if (state == GuardianState.HOME_SAFE) {
                // 兜底：去抖层确认在室内但状态机本来就在家（如重启恢复错位），静默归位
                episodeId = null
            }
            emptyList()
        }
    }

    fun onStale(now: Long): List<GuardianTransition> {
        if (state == GuardianState.LOCATION_STALE) return emptyList()
        val prev = state
        stateBeforeStale = prev
        state = GuardianState.LOCATION_STALE
        return listOf(GuardianTransition.EnteredStale(prev, now))
    }

    fun onFreshFix(now: Long): List<GuardianTransition> {
        if (state != GuardianState.LOCATION_STALE) return emptyList()
        val resumed = stateBeforeStale ?: GuardianState.HOME_SAFE
        stateBeforeStale = null
        state = resumed
        return listOf(GuardianTransition.ExitedStale(resumed, now))
    }

    fun restore(state: GuardianState, episodeId: String?, beforeStale: GuardianState?) {
        this.state = state
        this.episodeId = episodeId
        this.stateBeforeStale = beforeStale
    }

    fun snapshot(): Snapshot = Snapshot(state, episodeId, stateBeforeStale)

    data class Snapshot(val state: GuardianState, val episodeId: String?, val beforeStale: GuardianState?)
}
