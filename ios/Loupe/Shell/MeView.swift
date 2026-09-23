import SwiftUI

struct MeView: View {
    @EnvironmentObject private var web: WebModel
    @EnvironmentObject private var launcher: GameLauncher
    @ObservedObject private var laya = LayaModel.shared
    @ObservedObject private var ledger = LedgerService.shared
    @ObservedObject private var judgments = JudgmentsService.shared
    @State private var exporting = false
    @State private var exportError: String?
    @State private var shared: SharedFile?
    @State private var showWebSettings = false
    private let engine = EngineInfo.load()

    var body: some View {
        NavigationStack {
            List {
                Section("Engine (on device)") {
                    row("LoupeKit", engine.linked ? "linked" : "missing")
                    row("Built-in judgments", "\(engine.builtInJudgments)")
                    row("Public Suffix List", engine.pslVersion)
                    NavigationLink { LayaModelView() } label: {
                        row("Laya model", layaStatus)
                    }
                    .accessibilityIdentifier("me.model")
                    #if DEBUG || LOUPE_DIAGNOSTICS
                    if Self.showDiagnostics {
                        NavigationLink { DiagnosticsView() } label: { row("Diagnostics", "device check") }
                            .accessibilityIdentifier("me.diagnostics")
                    }
                    #endif
                }
                Section("Your data") {
                    Text(ledgerLine)
                        .font(.footnote).foregroundStyle(Palette.inkSoft)
                        .accessibilityIdentifier("me.ledger.count")
                    Text(judgments.overall().line)
                        .font(.footnote).foregroundStyle(Palette.ink)
                        .accessibilityIdentifier("me.agreement")
                    Button {
                        Task { await export() }
                    } label: {
                        HStack {
                            Text("Export my data")
                            Spacer()
                            if exporting { ProgressView() }
                        }
                    }
                    .disabled(exporting || ledger.problem != nil)
                    .accessibilityIdentifier("me.export")
                    if let exportError {
                        Text(exportError).font(.footnote).foregroundStyle(Palette.inkSoft)
                    }
                }
                SortSection()
                Section("Game") {
                    Button { launcher.open(.watch) } label: {
                        row("Riverflight", "watch Laya fly")
                    }
                    .foregroundStyle(Palette.ink)
                    .accessibilityIdentifier("me.game")
                    Button("Play it yourself") { launcher.open(.human) }
                        .accessibilityIdentifier("me.game.play")
                }
                Section("Web") {
                    Button("Web settings") { showWebSettings = true }
                }
                Section("About") {
                    NavigationLink("Licences") { LicencesView() }
                        .accessibilityIdentifier("me.licences")
                }
                Section {
                    Text("Per-judgment calibration, the baseline and the threshold are on each judgment's Measure screen.")
                        .font(.footnote).foregroundStyle(Palette.inkSoft)
                }
            }
            .scrollContentBackground(.hidden)
            .background(Palette.ground.ignoresSafeArea())
            .navigationTitle("Me")
            .onAppear { judgments.load(); judgments.refreshLedger() }
            .sheet(isPresented: $showWebSettings) { WebSettingsSheet().environmentObject(web) }
            .sheet(item: $shared) { file in ShareSheet(items: [file.url]) }
        }
    }

    /// Honest about where the history lives: nothing here is uploaded anywhere.
    private var ledgerLine: String {
        if let problem = ledger.problem { return "Decision ledger unavailable: \(problem)" }
        return "Decisions logged: \(ledger.count) · stored only on this iPhone"
    }

    /// F4: the desktop's lossless export (ledger, judgments, calibration, corrections), zipped.
    private func export() async {
        exporting = true
        exportError = nil
        defer { exporting = false }
        do {
            shared = SharedFile(url: try await ledger.export())
        } catch {
            exportError = "Export failed: \(error.localizedDescription)"
        }
    }

    #if DEBUG
    static let showDiagnostics = true
    #elseif LOUPE_DIAGNOSTICS
    /// A diagnostics Release build shows it only under TestFlight (a sandbox receipt), never from the App Store.
    static let showDiagnostics = Bundle.main.appStoreReceiptURL?.lastPathComponent == "sandboxReceipt"
    #endif

    private var layaStatus: String {
        switch laya.status {
        case .ready: return "on this phone"
        case .checking: return "checking"
        case .downloading(let p): return "downloading \(Int(p * 100))%"
        case .paused(let p): return "paused at \(Int(p * 100))%"
        case .verifying: return "checking"
        case .failed: return "needs attention"
        case .notInstalled: return "not on this phone"
        }
    }

    private func row(_ k: String, _ v: String) -> some View {
        HStack { Text(k); Spacer(); Text(v).font(Typeface.mono(13)).foregroundStyle(Palette.inkSoft).lineLimit(1) }
    }
}
