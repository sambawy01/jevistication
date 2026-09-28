import SwiftUI

struct MeView: View {
    @EnvironmentObject private var launcher: GameLauncher
    @EnvironmentObject private var router: AppRouter
    @ObservedObject private var sources = SourcesService.shared
    @ObservedObject private var laya = LayaModel.shared
    @ObservedObject private var ledger = LedgerService.shared
    @ObservedObject private var judgments = JudgmentsService.shared
    @ObservedObject private var assist = AssistService.shared
    @ObservedObject private var online = OnlineChecksService.shared
    @ObservedObject private var settings = ModelSettingsService.shared
    @State private var exporting = false
    @State private var exportError: String?
    @State private var shared: SharedFile?
    @State private var confirmErase = false
    @State private var showErase = false
    @AppStorage(MascotKind.storageKey) private var mascotKind = MascotKind.default.rawValue
    private let engine = EngineInfo.load()

    var body: some View {
        NavigationStack(path: $router.mePath) {
            List {
                NeonSection("What Loupe reads") {
                    NavigationLink(value: MeRoute.reads) { row("Sources", "\(sources.enabledCount) on") }
                        .accessibilityIdentifier("me.reads")
                    NavigationLink(value: MeRoute.mail) { row("Mail", sources.isPhoneEnabled(.mail) ? "on" : "off") }
                        .accessibilityIdentifier("me.mail")
                }
                NeonSection("Engine (on device)") {
                    row("LoupeKit", engine.linked ? "linked" : "missing")
                    row("Built-in judgments", "\(engine.builtInJudgments)")
                    row("Public Suffix List", engine.pslVersion)
                    NavigationLink { LayaModelView() } label: {
                        row("Decision model", layaStatus)
                    }
                    .accessibilityIdentifier("me.model")
                    NavigationLink { ModelSettingsView() } label: {
                        row(MS.t("title"), settings.settings.changed.isEmpty ? "defaults" : "\(settings.settings.changed.count) changed")
                    }
                    .accessibilityIdentifier("me.modelSettings")
                    #if DEBUG || LOUPE_DIAGNOSTICS
                    if Self.showDiagnostics {
                        NavigationLink { DiagnosticsView() } label: { row("Diagnostics", "device check") }
                            .accessibilityIdentifier("me.diagnostics")
                    }
                    #endif
                }
                NeonSection("Your data") {
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
                    // Two steps (audit P1-4): this asks, then the sheet wants DELETE typed.
                    Button(role: .destructive) { confirmErase = true } label: {
                        Text("Delete all my Loupe data…").frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .accessibilityIdentifier("me.erase")
                }
                SortSection()
                NeonSection("Game") {
                    Button { launcher.open(.watch) } label: {
                        row("Riverflight", "watch Loupe fly")
                    }
                    .foregroundStyle(Palette.ink)
                    .accessibilityIdentifier("me.game")
                    Button("Play it yourself") { launcher.open(.human) }
                        .accessibilityIdentifier("me.game.play")
                }
                NeonSection("Writing assistant") {
                    NavigationLink { AssistSettingsView(assist: assist) } label: {
                        row("Writing assistant", assist.config.enabled ? (assist.isReady ? "On · Online" : "Needs setup") : "Off")
                    }
                    .accessibilityIdentifier("me.assistant")
                }
                NeonSection("Online phishing checks") {
                    NavigationLink { OnlineChecksView(online: online) } label: {
                        row("Online phishing checks", online.settings.anyOn ? "On · Online" : "Off")
                    }
                    .accessibilityIdentifier("me.onlineChecks")
                }
                NeonSection("Appearance") {
                    HStack {
                        Text("Mascot")
                        Spacer()
                        Picker("Mascot", selection: $mascotKind) {
                            ForEach(MascotKind.allCases) { Text($0.title).tag($0.rawValue) }
                        }
                        .pickerStyle(.segmented)
                        .fixedSize()
                        .accessibilityIdentifier("me.mascot")
                    }
                }
                // Web settings live on Judgments → Web questions (its gear); the duplicate here went (audit P2-12).
                NeonSection("About") {
                    row("Version", Self.version)
                        .accessibilityElement(children: .combine)
                        .accessibilityIdentifier("me.version")
                    Link(destination: Self.privacyURL) { linkRow("Privacy policy") }
                        .accessibilityIdentifier("me.privacy")
                    Link(destination: Self.termsURL) { linkRow("Terms of use") }
                        .accessibilityIdentifier("me.terms")
                    NavigationLink("Licences") { LicencesView() }
                        .accessibilityIdentifier("me.licences")
                }
                NeonSection {
                    Text("Per-judgment calibration, the baseline and the threshold are on each judgment's Measure screen.")
                        .font(.footnote).foregroundStyle(Palette.inkSoft)
                }
            }
            .scrollContentBackground(.hidden)
            .neonGround()
            .navigationTitle("Me")
            .onAppear { judgments.load(); judgments.refreshLedger() }
            .confirmationDialog("Delete all your Loupe data?", isPresented: $confirmErase, titleVisibility: .visible) {
                Button("Continue", role: .destructive) { showErase = true }
                    .accessibilityIdentifier("me.erase.continue")
            } message: {
                Text("Your decisions, judgments, corrections, sources' indexes, the Spotted log, settings and saved sign-ins leave this iPhone for good. You can keep the decision model.")
            }
            .sheet(isPresented: $showErase) { DeleteDataView() }
            .sheet(item: $shared) { file in ShareSheet(items: [file.url]) }
            .navigationDestination(for: MeRoute.self) { route in
                switch route {
                case .reads: SourcesScreen(sources: SourcesService.shared)
                case .mail: MailScreen(mail: MailTriageService.shared)
                }
            }
        }
    }

    static let privacyURL = URL(string: "https://loupe-ai.com/privacy/")!
    static let termsURL = URL(string: "https://loupe-ai.com/terms/")!

    /// "0.1.0 (1)": the marketing version and the build.
    static var version: String {
        let info = Bundle.main.infoDictionary ?? [:]
        let v = info["CFBundleShortVersionString"] as? String ?? "?"
        let b = info["CFBundleVersion"] as? String ?? "?"
        return "\(v) (\(b))"
    }

    private func linkRow(_ title: String) -> some View {
        HStack {
            Text(title).foregroundStyle(Palette.ink)
            Spacer()
            Image(systemName: "arrow.up.right.square").foregroundStyle(Palette.inkSoft).accessibilityHidden(true)
        }
        .frame(minHeight: 44)
        .contentShape(Rectangle())
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
