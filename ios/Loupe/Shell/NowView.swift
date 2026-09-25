import SwiftUI
import LoupeKit

struct NowView: View {
    @EnvironmentObject private var launcher: GameLauncher
    @ObservedObject var service: JudgmentsService = .shared
    @ObservedObject var watchers: WatchersService = .shared
    @ObservedObject var sources: SourcesService = .shared
    @ObservedObject var sort: SortService = .shared
    @ObservedObject var privacy: PrivacyService = .shared
    @State private var showPrivacy = false
    @ObservedObject var mail: MailTriageService = .shared
    @State private var showMail = false
    @ObservedObject var review: ReviewService = .shared
    @State private var showReview = false
    @State private var openItem: SourceItem?
    @State private var showQueue = false
    @State private var opened = false
    @ObservedObject private var readiness = ModelReadiness.shared

    var body: some View {
        NavigationStack {
            content
                .navigationDestination(isPresented: $showQueue) { UnsureQueueView(service: service) }
                .navigationDestination(isPresented: $showPrivacy) { PrivacyView(privacy: privacy) }
                .navigationDestination(isPresented: $showMail) { MailTriageView(mail: mail) }
                .navigationDestination(isPresented: $showReview) { ReviewView(review: review) }
                .toolbar(.hidden, for: .navigationBar)
                .sheet(item: $openItem) { ItemTextView(item: $0) }
        }
        // Re-run the watchers whenever the scanned items change (a scan finishes, a source is
        // switched on or off). The first value arrives on subscribe, so this also runs on appear.
        .onReceive(sources.$sampleScan.combineLatest(sources.$sampleEnabled, sources.$revision)) { _ in
            guard !sources.scanning else { return }
            Task { await watchers.run() }
            Task { await privacy.run() }
            Task { await mail.run() }
        }
        // Whatever a check proposes goes to Review as soon as it has run (never run until approved).
        // (@Published fires before the value is stored: hop once so collect reads the new one.)
        // Laya arrived (download finished, files copied in): the watchers' model half can run now.
        .onChange(of: readiness.isReady) { _, ready in
            if ready, !sources.scanning { Task { await watchers.run() } }
        }
        .onReceive(privacy.$summary.receive(on: DispatchQueue.main)) { _ in review.collect() }
        .onReceive(mail.$summary.receive(on: DispatchQueue.main)) { _ in review.collect() }
        .onReceive(watchers.$summary.receive(on: DispatchQueue.main)) { _ in review.collect() }
        .task {
            service.load()
            service.refreshLedger()
            #if DEBUG
            if LaunchOptions.current.queueDemo { await seedWhenScanned() }
            if LaunchOptions.current.reviewDemo { await sources.seedReviewDemo() }
            #endif
            if !opened, LaunchOptions.current.openScreen == "queue" { opened = true; showQueue = true }
            if !opened, LaunchOptions.current.openScreen == "review" { opened = true; showReview = true }
        }
    }

    #if DEBUG
    /// Waits (briefly) for the sample scan, then seeds the fixture queue.
    private func seedWhenScanned() async {
        for _ in 0..<100 where service.sampleItems().isEmpty { try? await Task.sleep(nanoseconds: 100_000_000) }
        if service.rows.isEmpty { await service.seedFixtureQueue() }
    }
    #endif

    private var hasDecisions: Bool { !service.rows.isEmpty }

    private var content: some View {
        ScrollView {
            VStack(spacing: 20) {
                HeroBand {
                    VStack(alignment: .leading, spacing: 14) {
                        HStack(alignment: .bottom) {
                            Image("LogoLight")
                                .resizable().scaledToFit()
                                .frame(height: 34)
                                .accessibilityLabel("Loupe")
                            Spacer()
                            MascotView(state: mascotState, size: 92)
                        }
                        .padding(.top, 16)
                        HStack {
                            Circle().fill(Palette.mint).frame(width: 8, height: 8)
                            Text("On this phone. Nothing leaves it.")
                                .font(.subheadline)
                                .foregroundStyle(Palette.ink)
                            Spacer()
                            Text("0 bytes out")
                                .font(Typeface.mono(12))
                                .foregroundStyle(Palette.ink)
                        }
                        Divider().overlay(Palette.hairline)
                        HStack(spacing: 0) {
                            stat("Needs you", hasDecisions ? "\(service.needsYou)" : nil)
                            stat("Findings", watchers.summary.map { "\($0.findings.count)" })
                            stat("Sources", sources.enabledCount > 0 ? "\(sources.enabledCount)" : nil)
                        }
                        if let top = watchers.top {
                            HeroFindingCard(finding: top, more: watchers.findings.count - 1)
                        }
                    }
                }
                // The game sits high on Now so it is found at once (owner, 2026-09-25).
                PlayCard { launcher.open($0) }
                // Runs that belong to Now, live and in place: the passive sort and the watchers.
                LiveRunSection(view: "now", whileRunning: true).padding(.horizontal, 16)
                LiveRunSection(view: "watchers", whileRunning: true).padding(.horizontal, 16)
                if hasDecisions {
                    Button { showQueue = true } label: { NeedsYouCard(count: service.needsYou) }
                        .buttonStyle(.plain)
                        .padding(.horizontal, 16)
                        .accessibilityIdentifier("now.needsYou")
                }
                if let last = sort.last {
                    SortedCard(record: last)
                        .padding(.horizontal, 16)
                        .accessibilityIdentifier("now.sorted")
                }
                Button { showReview = true } label: { ReviewCard(review: review) }
                    .buttonStyle(.plain)
                    .padding(.horizontal, 16)
                    .accessibilityIdentifier("now.review")
                Button { showPrivacy = true } label: { PrivacyCard(privacy: privacy) }
                    .buttonStyle(.plain)
                    .padding(.horizontal, 16)
                    .accessibilityIdentifier("now.privacy")
                Button { showMail = true } label: { MailTriageCard(mail: mail) }
                    .buttonStyle(.plain)
                    .padding(.horizontal, 16)
                    .accessibilityIdentifier("now.mail")
                findingsSection
            }
            .padding(.bottom, 24)
        }
        .neonGround()
        .scrollBounceBehavior(.basedOnSize)
    }

    private var mascotState: MascotState {
        if sources.scanning || watchers.running { return .scanning }
        if watchers.newCount > 0 && !watchers.findings.isEmpty { return .found }
        return .idle
    }

    @ViewBuilder
    private var findingsSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            if let notice = watchers.notice {
                HStack {
                    Text(notice).font(.caption).foregroundStyle(Palette.inkSoft)
                    Spacer()
                    if watchers.lastSetAside != nil {
                        Button("Undo") { watchers.undoSetAside() }.font(.caption.weight(.semibold))
                            .accessibilityIdentifier("findings.undo")
                    }
                }
                .padding(.horizontal, 4)
            }
            if let summary = watchers.summary, !(sources.scanning || watchers.running) || !summary.findings.isEmpty {
                LayaOffBanner(feature: Features.shared.WATCHERS)
                if summary.findings.isEmpty {
                    if summary.itemsChecked == 0 {
                        HonestEmptyState(
                            title: "Nothing to watch yet",
                            message: "The watchers read what your sources hold. No source is on, so there is nothing to check and nothing is shown. Turn on the sample in Sources to see them work.",
                            symbol: "photo.on.rectangle")
                            .padding(.horizontal, -16)
                    } else {
                        VStack(alignment: .leading, spacing: 6) {
                            Text("Nothing raised").font(.headline).foregroundStyle(Palette.ink)
                            Text("The five watchers checked \(summary.itemsChecked) item(s) and raised nothing\(summary.setAside > 0 ? " you have not set aside (\(summary.setAside))" : ""). That is not an all-clear: these checks cover only what they look for.")
                                .font(.caption).foregroundStyle(Palette.inkSoft)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .card()
                        .accessibilityIdentifier("findings.none")
                    }
                } else {
                    HStack(alignment: .firstTextBaseline) {
                        Text("Findings").font(Typeface.display(24)).foregroundStyle(Palette.ink)
                        Spacer()
                        Text(summary.modelRan ? "Decision model + arithmetic"
                             : ModelSettingsService.shared.wasLayaOff(Features.shared.WATCHERS) ? "Mechanical only · decision model off" : "Mechanical only · model not installed")
                            .font(Typeface.mono(11)).foregroundStyle(Palette.inkSoft)
                    }
                    .padding(.horizontal, 4)
                    if !summary.modelRan, !readiness.isReady, !ModelSettingsService.shared.wasLayaOff(Features.shared.WATCHERS) {
                        GetLayaButton(title: "Get the decision model for the watchers' model half", id: "findings.getLaya")
                            .padding(.horizontal, 4)
                    }
                    ForEach(Array(summary.findings.enumerated()), id: \.element.key) { i, f in
                        FindingCard(finding: f, index: i, item: ItemIndex.item(f.itemId),
                                    onVerdict: { watchers.answer(f, $0) },
                                    onOpen: { openItem = watchers.item(f.itemId) })
                    }
                    Text("Warnings only. \(summary.itemsChecked) item(s), \(summary.emailsChecked) email(s) and \(summary.linksChecked) link(s) checked on \(summary.todayIso). An item with nothing raised is not cleared.")
                        .font(.caption).foregroundStyle(Palette.inkSoft).padding(.horizontal, 4)
                }
                if summary.itemsChecked > 0 { CensusCard(census: summary.census) }
            } else {
                HStack(spacing: 10) {
                    ProgressView()
                    Text(sources.scanning ? "Reading your sources…" : "The watchers are reading your items…")
                        .font(.subheadline).foregroundStyle(Palette.inkSoft)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .card()
                .accessibilityIdentifier("findings.loading")
            }
        }
        .padding(.horizontal, 16)
    }

    private func stat(_ label: String, _ value: String? = nil) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label).font(.caption).foregroundStyle(Palette.inkSoft)
            // An em dash, not a zero: there is no data yet, and zero would be a claim.
            Text(value ?? "—").font(Typeface.display(26)).foregroundStyle(Palette.cyan)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}
