import SwiftUI

/// Me (spec 2026-09-28 §3, mockup #6): everything that is not Home or Ask, one level down, in the spec's groups:
/// What Loupe reads (the sources; Mail as one place), Personal Assistant, Decision model, Your data, See Loupe think,
/// Advanced and About.
struct MeView: View {
    @EnvironmentObject private var launcher: GameLauncher
    @EnvironmentObject private var router: AppRouter
    @ObservedObject private var laya = LayaModel.shared
    @ObservedObject private var ledger = LedgerService.shared
    @ObservedObject private var judgments = JudgmentsService.shared
    @ObservedObject private var assist = AssistService.shared
    @ObservedObject private var sources = SourcesService.shared
    @ObservedObject private var review = ReviewService.shared
    @State private var exporting = false
    @State private var exportError: String?
    @State private var shared: SharedFile?
    @State private var confirmErase = false
    @State private var showErase = false
    private let engine = EngineInfo.load()

    var body: some View {
        NavigationStack(path: $router.mePath) {
            List {
                NeonSection("What Loupe reads") {
                    NavigationLink(value: MeRoute.reads) { row("Sources", MeModel.readsLine(on: sources.enabledCount)) }
                        .accessibilityIdentifier("me.reads")
                    NavigationLink(value: MeRoute.mail) {
                        row("Mail", MeModel.mailLine(account: sources.mailAccount?.username, on: sources.isPhoneEnabled(.mail)))
                    }
                    .accessibilityIdentifier("me.mail")
                }
                NeonSection("Personal Assistant") {
                    NavigationLink { AssistSettingsView(assist: assist) } label: {
                        row("Personal Assistant", MeModel.assistantLine(enabled: assist.config.enabled, ready: assist.isReady))
                    }
                    .accessibilityIdentifier("me.assistant")
                }
                NeonSection("Decision model") {
                    NavigationLink { LayaModelView() } label: { row("Decision model", layaStatus) }
                        .accessibilityIdentifier("me.model")
                }
                SortSection()
                NeonSection("Your data") {
                    Text(ledgerLine)
                        .font(.footnote).foregroundStyle(Palette.inkSoft)
                        .accessibilityIdentifier("me.ledger.count")
                    Text(judgments.overall().line)
                        .font(.footnote).foregroundStyle(Palette.ink)
                        .accessibilityIdentifier("me.agreement")
                    NavigationLink(value: MeRoute.review) { row("To review", "\(review.toReview)") }
                        .accessibilityIdentifier("me.review")
                    Button {
                        Task { await export() }
                    } label: {
                        HStack {
                            Text("Export my data")
                            Spacer()
                            if exporting { ProgressView() }
                        }
                        .frame(minHeight: 44)
                    }
                    .disabled(exporting || ledger.problem != nil)
                    .accessibilityIdentifier("me.export")
                    if let exportError {
                        Text(exportError).font(.footnote).foregroundStyle(Palette.inkSoft)
                    }
                    // Two steps (audit P1-4): this asks, then the sheet wants DELETE typed.
                    Button(role: .destructive) { confirmErase = true } label: {
                        Text("Delete all my Loupe data…").frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                    }
                    .accessibilityIdentifier("me.erase")
                }
                Section {
                    PlayCard { launcher.open($0) }
                        .listRowInsets(EdgeInsets())
                        .listRowBackground(Color.clear)
                } header: {
                    Text("See Loupe think")
                }
                NeonSection("Advanced") {
                    NavigationLink(value: MeRoute.advanced) { row("Advanced", "model settings, online checks") }
                        .accessibilityIdentifier("me.advanced")
                }
                NeonSection("About") {
                    row("Version", Self.version)
                        .accessibilityElement(children: .combine)
                        .accessibilityIdentifier("me.version")
                    row("LoupeKit", engine.linked ? "linked" : "missing")
                    row("Built-in judgments", "\(engine.builtInJudgments)")
                    row("Public Suffix List", engine.pslVersion)
                    Link(destination: Self.privacyURL) { linkRow("Privacy policy") }
                        .accessibilityIdentifier("me.privacy")
                    Link(destination: Self.termsURL) { linkRow("Terms of use") }
                        .accessibilityIdentifier("me.terms")
                    NavigationLink("Licences") { LicencesView() }
                        .accessibilityIdentifier("me.licences")
                }
            }
            .scrollContentBackground(.hidden)
            .neonGround()
            .navigationTitle("Me")
            // C-19 (owner ruling): no main-thread ledger work in a view body. loadInBackground() is async and reads
            // the ledger off the main thread; .load()/.refreshLedger() do not.
            .task { await judgments.loadInBackground() }
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
                case .advanced: AdvancedView()
                case .review: ReviewView(review: ReviewService.shared)
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
            Image(systemName: "arrow.up.forward.square").foregroundStyle(Palette.inkSoft).accessibilityHidden(true)
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
            .frame(minHeight: 44)
    }
}
