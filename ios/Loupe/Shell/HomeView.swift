import LoupeKit
import SwiftUI

/// Home (spec 2026-09-28 §3, step 1): where Loupe opens. Today's Now and Guard content in the new order: Needs
/// attention (only when something does), Quick check, Money, Documents, Protected. Each card leads to today's screen
/// for it, pushed on Home's stack.
struct HomeView: View {
    @Binding var path: NavigationPath
    /// Me → What Loupe reads (the empty cards' button).
    let openReads: () -> Void
    /// Me → Mail (mail phishing in Needs attention).
    let openMail: () -> Void
    @ObservedObject var watchers: WatchersService = .shared
    @ObservedObject var sources: SourcesService = .shared
    @ObservedObject var review: ReviewService = .shared
    @ObservedObject var privacy: PrivacyService = .shared
    @ObservedObject var mail: MailTriageService = .shared
    @ObservedObject private var protection = ProtectionStore.shared
    @ObservedObject private var safari = SafariSetup.shared
    @ObservedObject private var keyboard = KeyboardSetup.shared
    @ObservedObject private var clipboard = ClipboardMonitor.shared
    @ObservedObject private var online = OnlineChecksService.shared
    /// Findings name their items through `ItemIndex`, built off the main thread: repaint when it lands.
    @ObservedObject private var itemIndex = ItemIndex.Store.shared
    @State private var openItem: SourceItem?
    /// The scans the panel shows: the live ones, kept after they settle out of `liveScans` so the panel can
    /// collapse to their summary line; replaced when a new scan starts.
    @State private var panelScans: [LiveScan] = []

    var body: some View {
        NavigationStack(path: $path) {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    header
                    // A scan, whatever started it (Run now, Scan again, the first check after onboarding), shows
                    // here live, then collapses to one line (owner ruling O-5).
                    scanPanel
                    // Runs that belong to Home, live and in place: the passive sort and the watchers.
                    LiveRunSection(view: "now", whileRunning: true)
                    LiveRunSection(view: "watchers", whileRunning: true)
                    // The watchers' last answer and its Undo, so a finding answered below can be taken back.
                    GuardNotice(watchers: watchers)
                    attentionSection
                    HomeSectionTitle(title: "Quick check")
                    GuardQuickActions()
                    moneyCard
                    documentsCard
                    protectedCard
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 24)
            }
            .neonGround()
            .scrollBounceBehavior(.basedOnSize)
            .toolbar(.hidden, for: .navigationBar)
            .navigationDestination(for: HomeRoute.self) { route in
                switch route {
                case .protection: GuardScreen()
                case .subscriptions: SubscriptionsScreen(openReads: openReads)
                case .expiring: ExpiringScreen(openReads: openReads)
                case .review: ReviewView(review: review)
                case .privacy: PrivacyView(privacy: privacy)
                }
            }
            .guardDestinations()
            .protectionDestinations()
            .sheet(item: $openItem) { ItemTextView(item: $0) }
        }
    }

    private var coverage: GuardCoverage { GuardCoverage.from(sources) }

    // MARK: Scan panel

    /// The live scans' keys, sample left out, in the panel's order.
    private var liveKeys: [String] { HomeScanPanelModel.scanKeys(Array(sources.liveScans.keys)) }

    @ViewBuilder private var scanPanel: some View {
        let live = liveKeys.compactMap { sources.liveScans[$0] }
        let shown = live.isEmpty ? panelScans : live
        if !shown.isEmpty {
            HomeLiveScanPanel(scans: shown, sources: sources)
                .onChange(of: live.map(\.id), initial: true) { _, _ in
                    // A new scan joins the ones shown while they run; once all had finished it starts afresh.
                    guard !live.isEmpty else { return }
                    let fresh = panelScans.allSatisfy(\.finished)
                    let kept = fresh ? [] : panelScans.filter { old in !live.contains { $0.source == old.source } }
                    panelScans = HomeScanPanelModel.scanKeys((kept + live).map(\.source))
                        .compactMap { key in live.first { $0.source == key } ?? kept.first { $0.source == key } }
                }
        }
    }

    private var header: some View {
        HStack(alignment: .center, spacing: 12) {
            Image("LogoLight")
                .resizable().scaledToFit()
                .frame(height: 30)
                .accessibilityLabel("Loupe")
            Spacer(minLength: 8)
            Pill(text: online.settings.anyOn ? "Online checks on" : "0 bytes out",
                 color: online.settings.anyOn ? Palette.blue : Palette.okText,
                 symbol: online.settings.anyOn ? "globe" : "lock.fill")
                .accessibilityIdentifier("home.bytesOut")
            MascotView(state: mascotState, size: 56)
        }
        .padding(.top, 16)
    }

    private var mascotState: MascotState {
        if sources.scanning || watchers.running { return .scanning }
        if watchers.newCount > 0 && !watchers.findings.isEmpty { return .found }
        return .idle
    }

    // MARK: Needs attention

    private var attention: [HomeModel.Attention] {
        HomeModel.attention(findingKeys: watchers.nowFindings(limit: 3).map(\.key),
                            spottedHeadline: protection.summary.headline,
                            dangerousThisWeek: protection.summary.dangerousThisWeek,
                            phishing: Int(mail.summary?.phishingCount ?? 0),
                            toReview: review.toReview,
                            privacyFindings: privacy.findings.count)
    }

    @ViewBuilder private var attentionSection: some View {
        let items = attention
        if !items.isEmpty {
            VStack(alignment: .leading, spacing: 10) {
                HomeSectionTitle(title: "Needs attention", count: items.count)
                let findingKeys = items.compactMap { item -> String? in
                    if case .finding(let key) = item { return key } else { return nil }
                }
                ForEach(items) { attentionRow($0, findingKeys: findingKeys) }
            }
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("home.attention")
        }
    }

    /// `findingKeys`: the findings in Needs attention, in order: a card's id is its place among them (finding.0 is
    /// the newest), as on Now, while its answer and Open act on the finding itself.
    @ViewBuilder private func attentionRow(_ item: HomeModel.Attention, findingKeys: [String]) -> some View {
        switch item {
        case .spotted(let headline, let dangerous):
            Button { path.append(ProtectionRoute.spotted) } label: { SpottedNowCard(headline: headline, dangerous: dangerous) }
                .buttonStyle(.plain)
                .accessibilityIdentifier("home.attention.spotted")
        case .mail:
            Button(action: openMail) { MailTriageCard(mail: mail) }
                .buttonStyle(.plain)
                .accessibilityIdentifier("home.attention.mail")
        case .finding(let key):
            if let f = watchers.findings.first(where: { $0.key == key }), let place = findingKeys.firstIndex(of: key) {
                FindingCard(finding: f, index: place, item: ItemIndex.item(f.itemId),
                            onVerdict: { watchers.answer(f, $0) },
                            onOpen: { openItem = watchers.item(f.itemId) })
            }
        case .review:
            Button { path.append(HomeRoute.review) } label: { ReviewCard(review: review) }
                .buttonStyle(.plain)
                .accessibilityIdentifier("home.attention.review")
        case .privacy:
            Button { path.append(HomeRoute.privacy) } label: { PrivacyCard(privacy: privacy) }
                .buttonStyle(.plain)
                .accessibilityIdentifier("home.attention.privacy")
        }
    }

    // MARK: Money, Documents, Protected

    private var moneyCard: some View {
        let m = HomeModel.money(watchers.summary?.census, mailCovered: coverage.mail, running: checking)
        return VStack(alignment: .leading, spacing: 10) {
            Button { path.append(HomeRoute.subscriptions) } label: {
                HomeCardLabel(caption: "Money", headline: m.headline, detail: m.detail, symbol: "creditcard")
            }
            .buttonStyle(.plain)
            .accessibilityHint("Opens your subscriptions")
            .accessibilityIdentifier("home.money")
            if m.needsSource {
                CardAction(title: "Open What Loupe reads", symbol: "externaldrive.fill.badge.plus", hue: Palette.cyan, action: openReads)
                    .accessibilityIdentifier("home.money.connect")
            }
            if m.needsRun { runNow(id: "home.money.run") }
        }
    }

    private var documentsCard: some View {
        let d = HomeModel.documents(watchers.summary?.expiries, documentsCovered: coverage.documents, running: checking)
        return VStack(alignment: .leading, spacing: 10) {
            Button { path.append(HomeRoute.expiring) } label: {
                HomeCardLabel(caption: "Documents", headline: d.headline, detail: d.detail, symbol: "doc.text.magnifyingglass")
            }
            .buttonStyle(.plain)
            .accessibilityHint("Opens your expiring documents")
            .accessibilityIdentifier("home.documents")
            if d.needsSource {
                CardAction(title: "Open What Loupe reads", symbol: "externaldrive.fill.badge.plus", hue: Palette.cyan, action: openReads)
                    .accessibilityIdentifier("home.documents.connect")
            }
            if d.needsRun { runNow(id: "home.documents.run") }
        }
    }

    /// A scan or the watchers are running now (the cards say "Reading your sources" only then).
    private var checking: Bool { watchers.running || sources.scanning }

    /// Nothing checked since Loupe opened: Run now (Protection's action), or with no source on, the way to one.
    @ViewBuilder private func runNow(id: String) -> some View {
        if coverage.anyOn {
            CardAction(title: "Run now", symbol: "arrow.clockwise", hue: Palette.cyan) { TrackingRun.runNow() }
                .accessibilityLabel("Run the check now")
                .accessibilityIdentifier(id)
        } else {
            CardAction(title: "Open What Loupe reads", symbol: "externaldrive.fill.badge.plus", hue: Palette.cyan, action: openReads)
                .accessibilityIdentifier(id.replacingOccurrences(of: ".run", with: ".connect"))
        }
    }

    private var protectedCard: some View {
        let p = HomeModel.protected(safari: safari.isOn, keyboard: keyboard.added, clipboard: clipboard.enabled,
                                    onlineChecksOn: online.settings.anyOn)
        return VStack(alignment: .leading, spacing: 10) {
            Button { path.append(HomeRoute.protection) } label: {
                HomeCardLabel(caption: p.title, headline: p.line, detail: p.bytesLine, symbol: "shield.lefthalf.filled")
            }
            .buttonStyle(.plain)
            .accessibilityHint("Opens Protection: the watchers, link checks and Spotted")
            .accessibilityIdentifier("home.protected")
            if let off = p.firstOff {
                CardAction(title: "Turn on \(off.title)", symbol: "power", hue: Palette.cyan) { turnOn(off) }
                    .accessibilityIdentifier("home.protected.turnOn")
            }
        }
    }

    /// Safari turns on from here (iOS's own settings); the keyboard and the clipboard chip are set up on Protection.
    private func turnOn(_ g: HomeModel.Guardrail) {
        switch g {
        case .safari: Task { await safari.turnOn() }
        case .keyboard, .clipboard: path.append(HomeRoute.protection)
        }
    }
}

/// A Home card: a caption, a large headline, one line, a chevron; the whole card is the button's label.
struct HomeCardLabel: View {
    let caption: String
    let headline: String
    let detail: String
    let symbol: String

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            NeonIcon(name: symbol, color: Palette.cyan, size: 22)
                .frame(width: 28)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 4) {
                Caption(text: caption)
                Text(headline).font(Typeface.display(24)).foregroundStyle(Palette.ink)
                    .fixedSize(horizontal: false, vertical: true)
                Text(detail).font(.footnote).foregroundStyle(Palette.inkSoft)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 0)
            Image(systemName: "chevron.forward").font(.caption).foregroundStyle(Palette.inkSoft).accessibilityHidden(true)
        }
        .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
        .card()
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }
}

/// A section's title on Home, with an optional count ("Needs attention · 2").
struct HomeSectionTitle: View {
    let title: String
    var count: Int? = nil

    var body: some View {
        Text(count.map { "\(title) · \($0)" } ?? title)
            .font(Typeface.display(22)).foregroundStyle(Palette.ink)
            .fixedSize(horizontal: false, vertical: true)
            .accessibilityAddTraits(.isHeader)
            .padding(.horizontal, 4)
            .padding(.top, 4)
    }
}
