import Foundation

/// 老人监护宏观状态（与 Android 端一致）。设备连接状态不参与本状态机。
enum GuardianState: String, Codable {
    case homeSafe = "HOME_SAFE"
    case exitSuspected = "EXIT_SUSPECTED"
    case awayAlerted = "AWAY_ALERTED"
    case awayTracking = "AWAY_TRACKING"
    case returnSuspected = "RETURN_SUSPECTED"
    case locationStale = "LOCATION_STALE"

    var label: String {
        switch self {
        case .homeSafe: return "在家 · 围栏监护中"
        case .exitSuspected: return "疑似离家，确认中…"
        case .awayAlerted: return "⚠️ 已离家 · 已发出告警"
        case .awayTracking: return "已离家 · 持续监护"
        case .returnSuspected: return "疑似返家，确认中…"
        case .locationStale: return "⚠️ 定位不可用 · 监护存在盲区"
        }
    }
}

enum GuardianTransition: Equatable {
    case exitSuspected(at: Int64)
    case exitConfirmed(episodeId: String, at: Int64)
    case returnSuspected(at: Int64)
    case returnConfirmed(episodeId: String, at: Int64)
    case enteredStale(previous: GuardianState, at: Int64)
    case exitedStale(resumed: GuardianState, at: Int64)
}

/// 监护状态机：离家/返家/定位异常状态的唯一权威来源；支持快照恢复（重启后不重复告警）。
struct GuardianStateMachine {
    private(set) var state: GuardianState = .homeSafe
    private(set) var episodeId: String?
    private var stateBeforeStale: GuardianState?

    mutating func onRawOutside(now: Int64) -> [GuardianTransition] {
        guard state != .locationStale else { return [] }
        guard state == .homeSafe else { return [] }
        state = .exitSuspected
        return [.exitSuspected(at: now)]
    }

    mutating func onExitConfirmed(now: Int64) -> [GuardianTransition] {
        guard state != .locationStale else { return [] }
        let id = episodeId ?? UUID().uuidString
        episodeId = id
        state = .awayAlerted
        return [.exitConfirmed(episodeId: id, at: now)]
    }

    mutating func onRawInside(now: Int64) -> [GuardianTransition] {
        guard state != .locationStale else { return [] }
        guard state == .awayAlerted || state == .awayTracking else { return [] }
        state = .returnSuspected
        return [.returnSuspected(at: now)]
    }

    mutating func onReturnConfirmed(now: Int64) -> [GuardianTransition] {
        guard state != .locationStale else { return [] }
        if state == .returnSuspected, let ep = episodeId {
            state = .homeSafe
            episodeId = nil
            return [.returnConfirmed(episodeId: ep, at: now)]
        }
        if state == .homeSafe { episodeId = nil }
        return []
    }

    mutating func onStale(now: Int64) -> [GuardianTransition] {
        guard state != .locationStale else { return [] }
        let prev = state
        stateBeforeStale = prev
        state = .locationStale
        return [.enteredStale(previous: prev, at: now)]
    }

    mutating func onFreshFix(now: Int64) -> [GuardianTransition] {
        guard state == .locationStale else { return [] }
        let resumed = stateBeforeStale ?? .homeSafe
        stateBeforeStale = nil
        state = resumed
        return [.exitedStale(resumed: resumed, at: now)]
    }

    mutating func restore(state: GuardianState, episodeId: String?, beforeStale: GuardianState?) {
        self.state = state
        self.episodeId = episodeId
        self.stateBeforeStale = beforeStale
    }

    var snapshot: (state: GuardianState, episodeId: String?, beforeStale: GuardianState?) {
        (state, episodeId, stateBeforeStale)
    }
}
