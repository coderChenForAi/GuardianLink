import Foundation

// MARK: - 角色与模式

enum DeviceRole: String, Codable, CaseIterable {
    case elder = "ELDER_DEVICE"
    case guardian = "GUARDIAN_DEVICE"

    var label: String {
        switch self {
        case .elder: return "老人设备"
        case .guardian: return "监护人设备"
        }
    }
}

// MARK: - 坐标系与定位

/// 定位坐标系。iOS CoreLocation 返回 WGS-84；高德链接需要 GCJ-02，发送告警时转换。
enum CoordSystem: String, Codable {
    case gcj02 = "GCJ02"
    case wgs84 = "WGS84"
}

struct Loc: Equatable {
    var lat: Double
    var lng: Double
    var accuracyM: Float
    /// epoch 毫秒（与 Android 端协议一致）
    var time: Int64
    var coord: CoordSystem = .wgs84
    var source: String = "corelocation"

    static func invalid() -> Loc { Loc(lat: 0, lng: 0, accuracyM: -1, time: 0) }
}

// MARK: - 围栏配置

struct FenceConfig: Codable, Equatable {
    static let minRadiusM = 50
    static let maxRadiusM = 1000

    var centerLat: Double
    var centerLng: Double
    /// 米
    var radiusM: Int
    /// 固定 "GCJ-02"（发送给高德链接时使用）
    var coordSystem: String = "GCJ-02"
    var updatedAt: Int64 = 0
    var version: Int = 0
    var enabled: Bool = false

    var configured: Bool { centerLat != 0 && centerLng != 0 && radiusM > 0 }

    static func coerceRadius(_ m: Int) -> Int { min(max(m, minRadiusM), maxRadiusM) }

    /// 中国境内粗校验（与 Android 端一致）
    static func isValidCenter(lat: Double, lng: Double) -> Bool {
        (0.8293...55.8271).contains(lat) && (72.004...137.8347).contains(lng)
    }
}

// MARK: - 运行设置

struct AppSettings: Codable, Equatable {
    var role: DeviceRole = .elder
    var deviceLabel: String = "老人手机"
    var feishuWebhook: String = ""
    var feishuSecret: String = ""
    /// 离家期间位置更新间隔（分钟）
    var awayReportMinutes: Int = 10
    /// 定位失效判定（分钟）
    var staleAfterMinutes: Int = 10
    var lanSyncEnabled: Bool = true
}

// MARK: - 事件

enum EventType: String, Codable, CaseIterable {
    case exitHome = "EXIT_HOME"
    case returnHome = "RETURN_HOME"
    case locationUpdate = "LOCATION_UPDATE"
    case locationStale = "LOCATION_STALE"
    case deviceAlert = "DEVICE_ALERT"
}

enum SendStatus: String, Codable {
    case none = "NONE"
    case pending = "PENDING"
    case sent = "SENT"
    case failedRetriable = "FAILED_RETRIABLE"
    case failedPermanent = "FAILED_PERMANENT"
}

/// 与 Android 端 LAN 协议同构的事件模型（epoch 毫秒、同字段名）。
struct GuardianEvent: Codable, Equatable, Identifiable {
    var id: String
    var type: EventType
    var occurredAt: Int64
    var locatedAt: Int64?
    var lat: Double?
    var lng: Double?
    var accuracyM: Float?
    var coord: String?
    var sourceDeviceId: String
    var awayEpisodeId: String?
    var note: String?
    var sendStatus: SendStatus
    var retryCount: Int
    var lastError: String?
    var sentAt: Int64?
    /// 已确认收到该事件的监护人设备 ID（Android 端字段，iOS 老人端同样维护）
    var ackedBy: [String]

    init(
        id: String,
        type: EventType,
        occurredAt: Int64,
        locatedAt: Int64? = nil,
        lat: Double? = nil,
        lng: Double? = nil,
        accuracyM: Float? = nil,
        coord: String? = nil,
        sourceDeviceId: String,
        awayEpisodeId: String? = nil,
        note: String? = nil,
        sendStatus: SendStatus = .none,
        retryCount: Int = 0,
        lastError: String? = nil,
        sentAt: Int64? = nil,
        ackedBy: [String] = []
    ) {
        self.id = id
        self.type = type
        self.occurredAt = occurredAt
        self.locatedAt = locatedAt
        self.lat = lat
        self.lng = lng
        self.accuracyM = accuracyM
        self.coord = coord
        self.sourceDeviceId = sourceDeviceId
        self.awayEpisodeId = awayEpisodeId
        self.note = note
        self.sendStatus = sendStatus
        self.retryCount = retryCount
        self.lastError = lastError
        self.sentAt = sentAt
        self.ackedBy = ackedBy
    }
}

// MARK: - 告警发送配置

struct AlertConfig: Equatable {
    var webhook: String
    var secret: String
    var deviceLabel: String
    var deviceId: String
    var fenceRadiusM: Int
    var awayReportIntervalMs: Int64
}
