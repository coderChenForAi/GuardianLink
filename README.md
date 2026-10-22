# GuardianLink

**老人离家安全监护**：老人随身 Android 手机在本地监测"家庭安全区域"地理围栏，确认离家后**本机最大音量响铃**，并通过**飞书群自定义机器人**向家人发送包含定位时间、精度、坐标与高德地图链接的告警；同一 Wi-Fi 下支持**多台监护人设备**显式配对、事件推送与独立确认。**不依赖 Google Play services，不依赖自建服务端。**

> v2.0 重构说明：本项目从"两台手机 Wi-Fi/蓝牙断连告警"改造为以地理围栏为核心的老人离家监护；原连接监护（Wi-Fi UDP 心跳 / 蓝牙 RFCOMM）保留为**辅助信号**，不再作为离家判定的依据。

## 功能总览

| 模块 | 说明 |
|---|---|
| 家庭围栏配置 | 高德地图选点（长按/拖动 Marker）、圆形安全区覆盖层、半径滑块（50–1000 米，默认 200）、保存/恢复、状态展示 |
| 地理围栏监测 | 老人手机本地运行：高德定位 SDK（GCJ-02）连续定位 + 自研距离判定与去抖（连续 3 次有效定位确认进出、精度>100m 丢弃），监护状态机：HOME_SAFE / EXIT_SUSPECTED / AWAY_ALERTED / AWAY_TRACKING / RETURN_SUSPECTED / LOCATION_STALE |
| 飞书告警 | 离家告警 / 返家通知 / 离家期间定时位置更新（默认 10 分钟，可配）/ 定位异常提醒；支持可选签名校验；先落盘后发送，网络失败自动重试（WorkManager 指数退避，最多 6 次），事件 ID 幂等去重，服务重启不重复告警同一离家事件 |
| 多监护人 | 同一 Wi-Fi 局域网显式配对（6 位配对码，每人独立 token），离家/返家事件推送 + 各自独立确认（event_ack），未确认事件在心跳时有限补发；支持撤销授权 |
| 设备健康 | 监护服务状态、定位权限/服务、最近有效定位、最近飞书成功、最近同步、网络、电量——明确区分"设备在线"与"人在家" |
| 辅助连接监护（遗留） | Wi-Fi 断开 + UDP 心跳 / 蓝牙 RFCOMM 断链告警。仅表示**设备连接**状态，与离家判定解耦 |
| 可靠性 | 前台服务（location/dataSync 类型）、常驻唤醒锁、电池优化白名单引导、开机自恢复（若此前处于开启状态）、事件本地保留最近 200 条并自动淘汰 |

## Android 环境要求

- Android 8.1（API 27）及以上；targetSdk 35。
- 精确定位权限（运行时申请）；后台围栏监测依赖前台服务，锁屏取位无需额外权限即可工作，如遇厂商限制可授予"始终允许"定位（`ACCESS_BACKGROUND_LOCATION` 已声明，按需申请）。
- 通知权限（Android 13+ 运行时申请）。

## 高德地图 / 定位 SDK 配置（必须，否则地图空白）

项目已通过 Maven Central 集成（版本已核实存在）：

```kotlin
implementation("com.amap.api:3dmap:9.8.3")
implementation("com.amap.api:location:6.5.1")
```

申请步骤：

1. 到 [高德开放平台](https://developer.amap.com/) 注册并创建"Native 应用"，为其添加 **Key**：平台选 Android，填入包名 **`com.guardian.link`** 和签名 SHA1：
   ```
   D1:50:1C:4E:BC:0E:A2:A6:06:B3:47:08:82:EB:B2:37:79:06:F1:20
   ```
   （这是仓库内置 `app/debug.keystore` 的 SHA1，store 密码 `guardianlink2026`，alias `guardianlink`。**调试证书入库仅适用于个人/内部使用，勿用于正式发布。**）
2. 把生成的 Key 填入 `app/src/main/AndroidManifest.xml`：
   ```xml
   <meta-data android:name="com.amap.api.v2.apikey" android:value="你的Key" />
   ```
3. 未配置 Key 的表现：地图瓦片空白（App 内有明确提示条），但围栏选点（"使用当前位置"）、保存、围栏监测、飞书告警**均正常工作**。

说明：本项目**未使用**高德 GeoFenceClient 本地围栏组件，也未调用高德服务端围栏 API；进出判定由项目内置的 `GeoMath`（Haversine，含单元测试）+ `FenceDebouncer`（去抖）实现，行为透明可测。评估结论：高德 GeoFenceClient 与自研方案约束相同（需常驻服务、模糊定位下失效），且仍需自行去抖去重。

## 飞书机器人配置

1. 飞书群 → 设置 → 群机器人 → 添加**自定义机器人**；若配置了"自定义关键词"，建议包含 `GuardianLink`；
2. 复制 Webhook URL 填入 App ⚙ 设置；如开启了"签名校验"，把密钥填入下方"签名校验密钥"（App 内自动计算签名）；
3. 点"发送测试消息"验证。Webhook/密钥仅保存在老人机本地，**请勿提交到任何仓库**。APK 内不存在内置密钥；注意本机存储与 APK 均可能被持机者读取，机密性以家庭信任为前提。

告警消息包含：老人标识、离家/定位时间（过期会标注）、GCJ-02 坐标与精度、安全区半径、事件 ID、高德地图链接（`uri.amap.com/marker`，浏览器与高德 App 均可打开；坐标系为 GCJ-02，与高德定位 SDK 一致，若来源为 WGS-84 已内置转换）。

## 使用流程

### 老人手机

1. 首页角色选 **老人设备**（默认）；模式选 **家庭围栏监护**；
2. 进入 **设置家庭安全区域**：点"使用当前位置"获取候选中心点（**务必人工确认/调整后再保存**，当前定位 ≠ 家庭地址），也可长按地图选点、拖动 Marker 微调，滑块调半径 → 保存；
3. 回首页点 **开始监护**（授予定位/通知权限，点电池白名单横幅；华为：设置→电池→启动管理→手动管理三项全开）；
4. 常驻通知显示围栏监护状态；离家时手机响铃 + 飞书告警。

### 监护人手机（可多台）

1. 角色选 **监护人设备**；连到与老人机**同一路由器**（不要用开了 AP 隔离的访客网络）；
2. 点 **扫描老人设备**（或手动填老人机 IP）→ 输入老人机上的 **6 位配对码** → 配对成功后保存；
3. 点 **开始监护**：接收端常驻，收到离家事件会响铃并全屏提醒；每台监护人需各自收到推送后独立确认；
4. 老人机上可查看已授权监护人列表（撤销授权后该设备 token 立即失效）。

> 配对码与授权边界：可防止局域网内设备"随手加入"，但 UDP 明文 + token 鉴权**不能抵御**同网段抓包/重放；跨网段/公网同步与端到端加密为后续工作。

## 本地构建与测试

```bash
./gradlew testDebugUnitTest assembleDebug
```

CI：push 后 GitHub Actions 自动执行 `testDebugUnitTest assembleDebug` 并上传 APK 与测试报告（`.github/workflows/build.yml`）。

## 参数默认值（设置页可调）

| 参数 | 默认 | 说明 |
|---|---|---|
| 围栏半径 | 200 米 | 范围 50–1000 |
| 定位间隔 | 15 秒 | 围栏监测期间（代码常量 FIX_INTERVAL_MS） |
| 离家确认 | 连续 3 次围栏外有效定位 | 约 45 秒；精度>100m 的定位点丢弃 |
| 返家确认 | 连续 3 次围栏内定位 | 确认后结束离家事件 |
| 定位失效判定 | 10 分钟无有效定位 | 进入 LOCATION_STALE 并告警一次 |
| 离家位置更新 | 10 分钟/次 | 飞书与监护人推送，可配 1–120 分钟 |
| 连接监护宽限/心跳 | 5s / 15s | 遗留功能参数 |

## 测试结果

- 单元测试（CI 执行 `testDebugUnitTest`）：坐标距离/围栏判定、去抖确认与重归位、状态机全流程与重启恢复、事件存储持久化/去重/淘汰/幂等、飞书结果分类（含关键词拒绝=永久失败、429/5xx=可重试）、参数校验。
- 集成与真机项（**待真机验证清单**）：
  - [ ] 高德 Key 配置后地图瓦片加载、长按/拖动选点、圆形覆盖层渲染；
  - [ ] nova 3 等无 GMS 设备上高德定位实际回点频率与室内精度；
  - [ ] 真实走出围栏的离家/返家全链路（含飞书消息与地图链接点开位置正确性）；
  - [ ] 路由器 AP 隔离关闭前提下多监护人配对/推送/确认；
  - [ ] 华为 EMUI 后台限制下前台服务存活与开机自恢复；
  - [ ] 勿扰模式下告警铃声音量。

## 已知限制（如实说明）

1. **无法保证 100% 及时检测**：厂商省电、应用被强制停止、手机关机/没电、定位权限撤销、信号差都会造成漏报或延迟；LOCATION_STALE 会提示盲区，但无法凭空产生定位。
2. 围栏判定在室内定位漂移大时可能出现误报/延迟，已用精度门槛+连续确认缓解，参数可调但需按住处实测调优。
3. 监护人同步仅在**同一局域网**内工作；局域网不可用时老人机本地监护与飞书告警不受影响，监护人侧会显示离线。
4. UDP 同步明文传输，安全边界见上文；不支持跨网段。
5. 蓝牙/Wi-Fi 连接监护为辅助信号，断开**不代表**老人离家。
6. 定时位置更新与告警会持续消耗电量；地图 SDK 使 APK 体积显著增大。
7. 调试证书入库仅为保证高德 SHA1 绑定稳定，正式分发请更换签名。

## 隐私说明

仅收集监护必需的位置与设备状态；位置仅存于设备本地（最近 200 条，自动淘汰）并发送到**用户自己配置的**飞书群与已授权监护人设备；日志不输出完整精确坐标，不记录 Webhook/配对密钥。

## iOS 版本（GuardianLinkIOS/）

SwiftUI 实现，最低 iOS 16.0（覆盖 iPhone 12 全部可升级系统，含 iOS 26）。**在 Mac 上构建**（Windows 无法编译 iOS）：

```bash
cd GuardianLinkIOS
brew install xcodegen      # 首次
xcodegen generate          # 生成 GuardianLink.xcodeproj
open GuardianLink.xcodeproj
```

- 选中 GuardianLink target → Signing & Capabilities → 用你的 Apple ID 选 Personal Team；
- iPhone 连接 Mac → Xcode 运行即安装（免费账号 7 天有效期，到期重跑）；分发建议 TestFlight。
- CI：`.github/workflows/ios.yml` 在 macOS runner 上自动 `xcodegen + xcodebuild test/build`（模拟器），用于编译与单测验证。

iOS 端与 Android 端的差异（平台能力所致，如实说明）：

| 能力 | Android | iOS |
|---|---|---|
| 围栏监测 | 自研去抖引擎 + 前台服务持续定位 | **系统级 CLCircularRegion 围栏**（可在后台唤醒 App）+ 后台定位，去抖/状态机同一套算法 |
| 告警方式 | ALARM 流最大音量循环 | 系统通知（时间敏感级）+ 震动 + 前台弹窗，**无法**强制大音量循环 |
| 地图 | 高德 SDK（需申请 Key） | 系统 MapKit（无需 Key）；飞书链接仍为高德 URI |
| 飞书/事件/多监护人协议 | ✓ | ✓ 同一套消息格式与签名算法 |
| 后台限制 | 厂商 ROM 差异 | 权限须"始终允许"；勿强杀 App；长时间后台定位受系统调度影响 |

## 工程结构

```
app/src/main/java/com/guardian/link/
  ui/        引导/首页/设置/事件页 + 高德地图围栏设置页
  service/   GuardService(前台服务/状态编排/健康快照) + 开机恢复
  core/
    geo/     GeoMath(Haversine) + FenceDebouncer(去抖)
    state/   GuardianStateMachine(监护状态机)
    locate/  AMapLocationSource(高德定位, GCJ-02) + Loc + GeoConverter
    alert/   AlertDispatcher(事件分发/消息构造/重试调度)
    feishu/  FeishuClient(签名/业务码/结果分类)
    sync/    LanSyncEngine(发现/配对/推送/确认/保活)
    wifi|bt/ 遗留连接监护引擎
    alarm/   告警(铃声/震动/全屏页)
  data/      设置/围栏配置(DataStore) + 事件/配对/状态(文件JSON, 原子写)
  work/      FeishuRetryWorker(WorkManager 退避重试)
app/src/test/ 单元测试（无 Android 依赖的纯逻辑）
```
