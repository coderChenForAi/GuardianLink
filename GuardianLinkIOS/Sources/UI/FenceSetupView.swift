import SwiftUI
import MapKit
import CoreLocation

/// 地图围栏设置：长按地图选点 + 圆形覆盖层 + 半径滑块 + 保存。
/// 使用系统 MapKit，无需任何地图 Key。
struct FenceSetupView: View {
    @ObservedObject var monitor = FenceMonitor.shared

    @State private var center: CLLocationCoordinate2D?
    @State private var radiusM: Double = 200
    @State private var locating = false
    @State private var savedHint = ""

    var body: some View {
        List {
            Section(footer: Text("在地图上长按选择家庭中心点（GCJ-02 与 WGS-84 的差异由系统定位处理，发送飞书链接时自动转换）")) {
                FenceMapView(center: $center, radiusM: radiusM)
                    .frame(height: 320)
                    .listRowInsets(EdgeInsets())
            }

            Section("围栏参数") {
                if let c = center {
                    LabeledContent("中心点", value: String(format: "%.6f, %.6f", c.latitude, c.longitude))
                } else {
                    Text("尚未选择中心点：长按地图选点，或点「使用当前位置」")
                        .foregroundColor(.secondary)
                        .font(.footnote)
                }
                LabeledContent("安全半径", value: "\(Int(radiusM)) 米")
                Slider(
                    value: $radiusM,
                    in: Double(FenceConfig.minRadiusM)...Double(FenceConfig.maxRadiusM),
                    step: 10
                )
                Button {
                    locating = true
                    SingleFixFetcher.fetch { fix in
                        locating = false
                        if let fix {
                            center = CLLocationCoordinate2D(latitude: fix.lat, longitude: fix.lng)
                        }
                    }
                } label: {
                    Text(locating ? "定位中…" : "使用当前位置")
                }
                .disabled(locating)

                Button {
                    guard let c = center else { return }
                    if let err = monitor.saveFence(centerLat: c.latitude, centerLng: c.longitude, radiusM: Int(radiusM)) {
                        savedHint = "保存失败：\(err)"
                    } else {
                        savedHint = "✅ 已保存，开启监护后生效（监护运行中则立即生效）"
                    }
                } label: {
                    Text("保存围栏配置").frame(maxWidth: .infinity)
                }
                if !savedHint.isEmpty {
                    Text(savedHint).font(.footnote).foregroundColor(.secondary)
                }
            }

            Section(footer: Text("离开/返回由连续多次定位确认（默认 3 次），精度差于 100 米的定位会被忽略，避免漂移误报。后台监测需要「始终允许」定位权限。")) {
                LabeledContent("当前监护状态", value: monitor.monitorRunning ? monitor.state.label : "未开启监护")
                LabeledContent("最近有效定位", value: formatAge(monitor.lastFix?.time))
                if let fix = monitor.lastFix {
                    LabeledContent("最近定位精度", value: "±\(Int(fix.accuracyM)) 米")
                }
            }
        }
        .navigationTitle("家庭安全区域")
        .onAppear(perform: load)
    }

    private func load() {
        let f = monitor.savedFence()
        if f.configured {
            center = CLLocationCoordinate2D(latitude: f.centerLat, longitude: f.centerLng)
            radiusM = Double(f.radiusM)
        }
    }
}

/// MapKit 地图（长按选点 + 圆形覆盖层）
struct FenceMapView: UIViewRepresentable {
    @Binding var center: CLLocationCoordinate2D?
    var radiusM: Double

    func makeUIView(context: Context) -> MKMapView {
        let mv = MKMapView()
        mv.delegate = context.coordinator
        mv.isRotateEnabled = false
        mv.isPitchEnabled = false
        let longPress = UILongPressGestureRecognizer(
            target: context.coordinator,
            action: #selector(Coordinator.longPress(_:))
        )
        mv.addGestureRecognizer(longPress)
        return mv
    }

    func updateUIView(_ mv: MKMapView, context: Context) {
        context.coordinator.parent = self
        context.coordinator.sync(mv: mv, center: center, radius: radiusM)
    }

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    final class Coordinator: NSObject, MKMapViewDelegate {
        var parent: FenceMapView
        private var circle: MKCircle?
        private let annotation = MKPointAnnotation()
        private var didCenterCamera = false

        init(_ parent: FenceMapView) {
            self.parent = parent
        }

        @objc func longPress(_ g: UILongPressGestureRecognizer) {
            guard g.state == .began, let mv = g.view as? MKMapView else { return }
            let point = g.location(in: mv)
            parent.center = mv.convert(point, toCoordinateFrom: mv)
        }

        func sync(mv: MKMapView, center: CLLocationCoordinate2D?, radius: Double) {
            guard let c = center else { return }
            annotation.title = "家庭中心"
            annotation.coordinate = c
            if mv.annotations.isEmpty {
                mv.addAnnotation(annotation)
            }
            if let old = circle { mv.removeOverlay(old) }
            let new = MKCircle(center: c, radius: radius)
            circle = new
            mv.addOverlay(new)
            if !didCenterCamera {
                let spanMeters = max(radius * 4, 800)
                mv.setRegion(
                    MKCoordinateRegion(
                        center: c,
                        latitudinalMeters: spanMeters,
                        longitudinalMeters: spanMeters
                    ),
                    animated: false
                )
                didCenterCamera = true
            }
        }

        func mapView(_ mapView: MKMapView, rendererFor overlay: MKOverlay) -> MKOverlayRenderer {
            if let circle = overlay as? MKCircle {
                let renderer = MKCircleRenderer(circle: circle)
                renderer.strokeColor = .systemBlue
                renderer.fillColor = UIColor.systemBlue.withAlphaComponent(0.15)
                renderer.lineWidth = 2
                return renderer
            }
            return MKOverlayRenderer(overlay: overlay)
        }
    }
}

/// 单次定位（"使用当前位置"）
enum SingleFixFetcher {
    static func fetch(completion: @escaping (Loc?) -> Void) {
        let fetcher = Fetcher()
        fetcher.start { fix in
            DispatchQueue.main.async { completion(fix) }
        }
    }

    private final class Fetcher: NSObject, CLLocationManagerDelegate {
        private let manager = CLLocationManager()
        private var completion: ((Loc?) -> Void)?
        private var finished = false

        func start(completion: @escaping (Loc?) -> Void) {
            self.completion = completion
            manager.delegate = self
            manager.desiredAccuracy = kCLLocationAccuracyNearestTenMeters
            switch manager.authorizationStatus {
            case .notDetermined:
                manager.requestWhenInUseAuthorization()
            case .authorizedWhenInUse, .authorizedAlways:
                manager.startUpdatingLocation()
            default:
                finish(nil)
            }
            DispatchQueue.main.asyncAfter(deadline: .now() + 20) { [weak self] in
                self?.finish(nil)
            }
        }

        private func finish(_ fix: Loc?) {
            guard !finished else { return }
            finished = true
            manager.stopUpdatingLocation()
            completion?(fix)
        }

        func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
            if manager.authorizationStatus == .authorizedWhenInUse || manager.authorizationStatus == .authorizedAlways {
                manager.startUpdatingLocation()
            } else if manager.authorizationStatus != .notDetermined {
                finish(nil)
            }
        }

        func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
            guard let last = locations.last else { return }
            guard last.horizontalAccuracy >= 0, last.horizontalAccuracy <= 100 else { return }
            finish(
                Loc(
                    lat: last.coordinate.latitude,
                    lng: last.coordinate.longitude,
                    accuracyM: Float(last.horizontalAccuracy),
                    time: Int64(last.timestamp.timeIntervalSince1970 * 1000),
                    coord: .wgs84,
                    source: "corelocation"
                )
            )
        }

        func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
            finish(nil)
        }
    }
}
