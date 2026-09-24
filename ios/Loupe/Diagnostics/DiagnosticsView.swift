#if DEBUG || LOUPE_DIAGNOSTICS
import SwiftUI
import UIKit
import LoupeKit

/// What the phone reports about itself during a run. No network, no private API.
enum DeviceProbe {
    /// `task_vm_info.phys_footprint`: the number iOS's memory limit (jetsam) is enforced against.
    static func physFootprint() -> UInt64 {
        var info = task_vm_info_data_t()
        var count = mach_msg_type_number_t(MemoryLayout<task_vm_info_data_t>.size / MemoryLayout<natural_t>.size)
        let kr = withUnsafeMutablePointer(to: &info) {
            $0.withMemoryRebound(to: integer_t.self, capacity: Int(count)) {
                task_info(mach_task_self_, task_flavor_t(TASK_VM_INFO), $0, &count)
            }
        }
        return kr == KERN_SUCCESS ? info.phys_footprint : 0
    }

    static func thermal() -> String {
        switch ProcessInfo.processInfo.thermalState {
        case .nominal: return "nominal"
        case .fair: return "fair"
        case .serious: return "serious"
        case .critical: return "critical"
        @unknown default: return "unknown"
        }
    }

    /// e.g. "iPhone16,1"; "arm64" on the simulator (with SIMULATOR_MODEL_IDENTIFIER when set).
    static func model() -> String {
        var u = utsname()
        uname(&u)
        let machine = withUnsafeBytes(of: &u.machine) { String(decoding: $0.prefix { $0 != 0 }, as: UTF8.self) }
        if let sim = ProcessInfo.processInfo.environment["SIMULATOR_MODEL_IDENTIFIER"] { return "\(sim) (simulator)" }
        return machine
    }

    @MainActor static func battery() -> (level: Float, state: String, charging: Bool) {
        let d = UIDevice.current
        d.isBatteryMonitoringEnabled = true
        let state: String
        switch d.batteryState {
        case .unplugged: state = "unplugged"
        case .charging: state = "charging"
        case .full: state = "full"
        default: state = "unknown"
        }
        return (d.batteryLevel, state, d.batteryState == .charging || d.batteryState == .full)
    }
}

/// Runs the pinned parity questions through the real on-device Laya and measures the phone.
@MainActor
final class DiagnosticsRunner: ObservableObject {
    enum Phase: Equatable {
        case idle
        case opening
        case running(done: Int, total: Int)
        case finished
        case failed(String)
    }

    @Published private(set) var phase: Phase = .idle
    @Published private(set) var report: DiagnosticsReport?
    @Published private(set) var peakFootprint: UInt64 = 0
    @Published private(set) var thermalNow = DeviceProbe.thermal()

    let fixture: DiagnosticsFixture?
    private var cancelled = false
    private var sampler: Timer?

    init(fixture: DiagnosticsFixture? = .bundled()) { self.fixture = fixture }

    var isRunning: Bool {
        switch phase {
        case .opening, .running: return true
        default: return false
        }
    }

    func cancel() { cancelled = true }

    func run(passes: Int, model: LayaModel = .shared) async {
        guard !isRunning else { return }
        guard let fixture else { phase = .failed("The diagnostics fixture is not in this build."); return }
        cancelled = false
        report = nil
        phase = .opening
        guard let backend = await model.backend() else {
            phase = .failed("Laya is not installed or did not open (\(model.status)). Get the model first.")
            return
        }
        UIApplication.shared.isIdleTimerDisabled = true
        defer { UIApplication.shared.isIdleTimerDisabled = false; sampler?.invalidate(); sampler = nil }

        let start = Date()
        let startFootprint = DeviceProbe.physFootprint()
        peakFootprint = startFootprint
        var thermal = [ThermalSample(t: 0, state: DeviceProbe.thermal())]
        let battery0 = DeviceProbe.battery()
        sampler = Timer.scheduledTimer(withTimeInterval: 0.25, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated {
                guard let self else { return }
                self.peakFootprint = max(self.peakFootprint, DeviceProbe.physFootprint())
                let now = DeviceProbe.thermal()
                if now != self.thermalNow || thermal.last?.state != now {
                    thermal.append(ThermalSample(t: Date().timeIntervalSince(start), state: now))
                }
                self.thermalNow = now
            }
        }

        // One untimed warm-up (first-call allocations), then the timed passes.
        if let first = fixture.cases.first { _ = await Self.score(first, backend) }
        var measured: [String: CaseMeasurement] = [:]
        let total = fixture.cases.count * passes
        var done = 0
        outer: for pass in 0..<passes {
            for c in fixture.cases {
                if cancelled { break outer }
                let (result, ms) = await Self.score(c, backend)
                let key = "\(c.set)/\(c.id)"
                var m = measured[key] ?? CaseMeasurement(probabilities: nil, error: nil, latenciesMs: [])
                switch result {
                case .success(let p):
                    if pass == 0 { m.probabilities = p }
                    m.latenciesMs.append(ms)
                case .failure(let e):
                    m.error = e.message
                }
                measured[key] = m
                done += 1
                phase = .running(done: done, total: total)
            }
        }
        peakFootprint = max(peakFootprint, DeviceProbe.physFootprint())
        thermal.append(ThermalSample(t: Date().timeIntervalSince(start), state: DeviceProbe.thermal()))
        let battery1 = DeviceProbe.battery()
        let device = DiagnosticsReport.Device(
            model: DeviceProbe.model(),
            system: "\(UIDevice.current.systemName) \(UIDevice.current.systemVersion)",
            variant: model.variantID,
            appVersion: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "?",
            lowPowerMode: ProcessInfo.processInfo.isLowPowerModeEnabled)
        report = DiagnosticsReport.build(
            cases: fixture.cases, measured: measured, device: device, passes: passes,
            durationSeconds: Date().timeIntervalSince(start), completed: !cancelled,
            memory: .init(startPhysFootprintBytes: startFootprint, peakPhysFootprintBytes: peakFootprint),
            thermal: thermal,
            battery: (battery0.level, battery1.level, battery1.state, battery0.charging || battery1.charging))
        phase = .finished
    }

    struct ScoreError: Error { let message: String }

    /// One case on the shared model queue (never the main thread), timed around the scoring call.
    private static func score(_ c: DiagnosticsCase, _ backend: Backend) async -> (Result<[Double], ScoreError>, Double) {
        await ModelWork.run(.foreground) {
            let t0 = DispatchTime.now().uptimeNanoseconds
            let r = LayaOnPhone.shared.scoreCase(backend: backend, id: c.id, question: c.question, candidates: c.candidates,
                                                 descriptions: c.descriptions, state: c.state)
            let ms = Double(DispatchTime.now().uptimeNanoseconds - t0) / 1_000_000
            if let s = r as? LayaOnPhone.CaseScoreScored {
                return (.success(s.probabilities.map { $0.doubleValue }), ms)
            }
            return (.failure(ScoreError(message: (r as? LayaOnPhone.CaseScoreFailed)?.message ?? "scoring failed")), ms)
        }
    }

    /// Writes the report to a temporary file for the share sheet.
    func exportURL() throws -> URL? {
        guard let report else { return nil }
        let stamp = report.generatedAt.replacingOccurrences(of: ":", with: "-")
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("loupe-diagnostics-\(stamp).json")
        try report.json().write(to: url, options: .atomic)
        return url
    }
}

/// Me → Diagnostics (DEBUG and diagnostics builds only): milestone 1's phone numbers.
struct DiagnosticsView: View {
    @StateObject private var runner = DiagnosticsRunner()
    @ObservedObject private var model = LayaModel.shared
    @State private var passes = 3
    @State private var shared: SharedFile?
    @State private var exportError: String?

    var body: some View {
        List {
            NeonSection {
                Text("Runs the \(runner.fixture?.cases.count ?? 0) pinned parity questions (34 golden + 8 criteria) through Laya on this phone and compares each answer with the desktop's INT8 answer. Measures latency by token length, peak memory, thermal state and battery. Nothing leaves the phone unless you share the report.")
                    .font(.footnote).foregroundStyle(Palette.inkSoft)
                Stepper("Timed passes: \(passes)", value: $passes, in: 1...20).disabled(runner.isRunning)
                    .accessibilityIdentifier("diag.passes")
                row("Model", "\(model.variantID) · \(statusText)")
                row("Memory now (peak)", DeliveryError.bytes(Int64(runner.peakFootprint)))
                row("Thermal", runner.thermalNow)
                if runner.isRunning {
                    Button("Stop", role: .destructive) { runner.cancel() }
                } else {
                    Button("Run diagnostics") { Task { await runner.run(passes: passes) } }
                        .disabled(!model.isInstalled)
                        .accessibilityIdentifier("diag.run")
                }
            }
            switch runner.phase {
            case .opening: NeonSection { ProgressView("Checking and opening Laya…") }
            case let .running(done, total):
                NeonSection { ProgressView(value: Double(done), total: Double(max(1, total))) { Text("\(done) / \(total)") } }
            case .failed(let m): NeonSection { Text(m).foregroundStyle(Palette.red).font(.footnote) }
            default: EmptyView()
            }
            if let r = runner.report { results(r) }
        }
        .neonList()
        .navigationTitle("Diagnostics")
        .accessibilityIdentifier("diag.screen")
        .sheet(item: $shared) { file in ShareSheet(items: [file.url]) }
    }

    private var statusText: String { model.isInstalled ? "installed" : "not installed" }

    @ViewBuilder private func results(_ r: DiagnosticsReport) -> some View {
        NeonSection("Agreement with the desktop (JVM INT8)") {
            ForEach(r.agreement.keys.sorted(), id: \.self) { k in
                let a = r.agreement[k]!
                row(k, "\(a.same)/\(a.total) · max |Δp| \(a.maxAbsDelta.map { String(format: "%.2g", $0) } ?? "–")")
                if !a.differing.isEmpty { Text("Different: \(a.differing.joined(separator: ", "))").font(.caption).foregroundStyle(Palette.red) }
            }
        }
        NeonSection("Latency by tokens (ms)") {
            ForEach(r.latency + [r.latencyAll], id: \.bucket) { l in
                row("\(l.bucket) (\(l.samples))", "p50 \(fmt(l.p50Ms)) · p95 \(fmt(l.p95Ms))")
            }
        }
        NeonSection("Phone") {
            row("Peak phys_footprint", DeliveryError.bytes(Int64(r.memory.peakPhysFootprintBytes)))
            row("Thermal start → end (worst)", "\(r.thermal.start) → \(r.thermal.end) (\(r.thermal.worst))")
            row("Battery", r.battery.drainPercentPerHour.map { String(format: "%.1f%%/h", $0) } ?? "no estimate")
            Text(r.battery.note).font(.caption).foregroundStyle(Palette.inkSoft)
            row("Duration", String(format: "%.0f s%@", r.durationSeconds, r.completed ? "" : " (stopped)"))
        }
        NeonSection {
            Button("Share report (JSON)") {
                do { shared = try runner.exportURL().map(SharedFile.init) } catch { exportError = error.localizedDescription }
            }
            .accessibilityIdentifier("diag.share")
            if let exportError { Text(exportError).font(.footnote).foregroundStyle(Palette.red) }
        }
    }

    private func fmt(_ x: Double?) -> String { x.map { String(format: "%.0f", $0) } ?? "–" }

    private func row(_ k: String, _ v: String) -> some View {
        HStack { Text(k); Spacer(); Text(v).font(Typeface.mono(12)).foregroundStyle(Palette.inkSoft).lineLimit(1).minimumScaleFactor(0.7) }
    }
}
#endif
