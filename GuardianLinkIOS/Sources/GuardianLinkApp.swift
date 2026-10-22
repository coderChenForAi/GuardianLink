import SwiftUI

@main
struct GuardianLinkApp: App {
    @StateObject private var monitor = FenceMonitor.shared
    @StateObject private var settingsStore = AppSettingsStore.shared
    @StateObject private var pairing = PairingStore.shared
    @StateObject private var lan = LanSyncService.shared

    var body: some Scene {
        WindowGroup {
            TabView {
                HomeView().tabItem { Label("监护", systemImage: "shield.fill") }
                FenceSetupView().tabItem { Label("围栏", systemImage: "mappin.and.ellipse") }
                EventsView().tabItem { Label("事件", systemImage: "list.bullet.rectangle") }
                SettingsView().tabItem { Label("设置", systemImage: "gearshape.fill") }
            }
            .onAppear {
                monitor.bootstrap()
                lan.start(role: settingsStore.settings.role)
            }
            .alert(
                monitor.presentAlertTitle ?? "",
                isPresented: Binding(
                    get: { monitor.presentAlertTitle != nil },
                    set: { if !$0 { monitor.presentAlertTitle = nil } }
                )
            ) {
                Button("知道了", role: .cancel) {}
            } message: {
                Text("检测到离家事件，已通过通知/飞书提醒家人")
            }
        }
    }
}
