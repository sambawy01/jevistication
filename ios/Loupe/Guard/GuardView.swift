import LoupeKit
import SwiftUI

/// The Guard tab (owner decision 2026-09-26): everything that watches over you, in one place. The five watchers'
/// latest run (`WatchersService`, LoupeKit's shared `WatcherRun`) laid out by what they protect: a status header
/// (what is watched, the last run, what leaves the phone, Run now, the live per-watcher progress), Subscriptions
/// (recurring money), Expiring soon (the expiry radar), a section for each other watcher, and Protection (link and
/// site checks). Findings still surface on Now (the newest one or two), which links here.
///
/// Performance: the header's progress strip observes its own feed, so a progress tick redraws the strip only; the
/// sections take plain values and redraw only when the run's summary changes.
struct GuardView: View {
    @ObservedObject var watchers: WatchersService = .shared
    @ObservedObject var sources: SourcesService = .shared
    @ObservedObject private var readiness = ModelReadiness.shared
    @ObservedObject private var settings = ModelSettingsService.shared
    @EnvironmentObject private var router: AppRouter
    @State private var path = NavigationPath()

    var body: some View {
        NavigationStack(path: $path) {
            ScrollViewReader { proxy in
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    GuardHeader(watchers: watchers, sources: sources, coverage: coverage)
                    notice
                    if let summary = watchers.summary {
                        content(summary)
                    } else {
                        firstRun
                    }
                    GuardSectionTitle(title: "Protection", detail: "Links and sites").id("protection")
                    GuardProtectionSection()
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 32)
            }
            #if DEBUG
            // -LoupeGuardSection expiry|subscriptions|protection: bring that section up once the run is in (screenshots).
            .onChange(of: watchers.summary != nil) { _, ready in
                guard ready, let i = ProcessInfo.processInfo.arguments.firstIndex(of: "-LoupeGuardSection"),
                      i + 1 < ProcessInfo.processInfo.arguments.count else { return }
                let id = ProcessInfo.processInfo.arguments[i + 1]
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { proxy.scrollTo(id, anchor: .top) }
            }
            #endif
            }
            .neonGround()
            .navigationTitle("Guard")
            .navigationBarTitleDisplayMode(.inline)
            .navigationDestination(for: GuardRoute.self) { route in
                switch route {
                case .subscription(let merchant): SubscriptionDetailView(watchers: watchers, merchant: merchant)
                case .expiry(let itemId): ExpiryDetailView(watchers: watchers, itemId: itemId)
                case .finding(let key): FindingDetailView(watchers: watchers, key: key)
                case .mail: MailTriageView(mail: MailTriageService.shared)
                case .onlineChecks: OnlineChecksView(online: OnlineChecksService.shared)
                }
            }
        }
        // Now's "Loupe spotted …" card: push the Spotted list (its destination is registered by the Protection section).
        .onChange(of: router.guardPush) { _, _ in takePush() }
        // The run's live progress is drawn here (the strip), so the Activity dock leaves the watchers out on this tab.
        .onAppear { ActivityCenter.shared.show("watchers"); takePush() }
        .onDisappear { ActivityCenter.shared.hide("watchers") }
    }

    private var coverage: GuardCoverage { GuardCoverage.from(sources) }

    private func takePush() {
        guard let route = router.guardPush else { return }
        router.guardPush = nil
        path = NavigationPath()
        path.append(route)
    }

    private var modelHalf: GuardModel.ModelHalf {
        GuardModel.modelHalf(modelRan: watchers.summary?.modelRan ?? false, modelReady: readiness.isReady,
                             turnedOff: !settings.useLaya(Features.shared.WATCHERS))
    }

    @ViewBuilder private func content(_ summary: WatcherSummary) -> some View {
        let findings = summary.findings
        GuardSectionTitle(title: "Subscriptions", detail: WatcherKind.recurring.title).id("subscriptions")
        SubscriptionsSection(census: summary.census, findings: findings, coverage: coverage,
                             openSources: { router.open(.sources) })
        GuardSectionTitle(title: "Expiring soon", detail: WatcherKind.expiry.title).id("expiry")
        ExpirySection(rows: summary.expiries, ruleName: summary.ruleName, itemsChecked: Int(summary.itemsChecked),
                      half: modelHalf, coverage: coverage, openSources: { router.open(.sources) })
        ForEach(GuardModel.otherWatchers, id: \.self) { kind in
            GuardSectionTitle(title: kind.guardTitle, detail: kind.title)
            WatcherSection(kind: kind, findings: GuardModel.findings(kind, in: findings), summary: summary,
                           coverage: coverage, openSources: { router.open(.sources) })
        }
        Text("Warnings only. \(summary.itemsChecked) item(s), \(summary.emailsChecked) email(s) and \(summary.linksChecked) link(s) checked on \(GuardModel.day(summary.todayIso)). Anything with nothing raised is not cleared: each watcher covers only what it looks for.")
            .font(.caption).foregroundStyle(Palette.inkSoft)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.horizontal, 4)
            .accessibilityIdentifier("guard.footnote")
    }

    /// Before the first run has finished: the strip in the header shows the run; this says what is coming.
    private var firstRun: some View {
        HStack(spacing: 12) {
            NeonIcon(name: "shield.lefthalf.filled", color: Palette.cyan, size: 22, active: true)
            VStack(alignment: .leading, spacing: 2) {
                Text(sources.scanning ? "Reading your sources first" : "The five watchers are starting")
                    .font(.subheadline.weight(.semibold)).foregroundStyle(Palette.ink)
                Text("Subscriptions, expiry dates, impersonation, site fraud and term changes appear here as each watcher finishes.")
                    .font(.footnote).foregroundStyle(Palette.inkSoft)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 0)
        }
        .card(active: true)
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("guard.loading")
    }

    @ViewBuilder private var notice: some View {
        if let notice = watchers.notice {
            HStack(spacing: 10) {
                Text(notice).font(.footnote).foregroundStyle(Palette.ink)
                    .fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: 8)
                if watchers.lastSetAside != nil || watchers.lastSetAsideRow != nil {
                    Button("Undo") { watchers.undoSetAside() }
                        .font(.footnote.weight(.semibold))
                        .frame(minWidth: 44, minHeight: 44)
                        .accessibilityIdentifier("guard.undo")
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .frame(minHeight: 44)
            .background(Palette.accentSoft, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("guard.notice")
        }
    }
}

/// Where a Guard row leads.
enum GuardRoute: Hashable {
    case subscription(String)
    case expiry(String)
    case finding(String)
    case mail
    case onlineChecks
}

/// Which kinds of item the sources that are on can give the watchers: it decides what an empty section asks you to
/// turn on.
struct GuardCoverage: Equatable {
    /// Mail-like items: the sample's mail, the Mail source, imported mail and statements (Inbox).
    var mail = false
    /// Documents: the sample's documents, Files, Photos (their text), imports.
    var documents = false
    /// Contacts (the address book the impersonation watcher knows people by).
    var contacts = false
    var anyOn = false

    @MainActor static func from(_ s: SourcesService) -> GuardCoverage {
        let inbox = s.inboxEnabled && !s.inboxBatches.isEmpty
        return GuardCoverage(mail: s.sampleEnabled || s.isPhoneEnabled(.mail) || inbox,
                             documents: s.sampleEnabled || s.isPhoneEnabled(.files) || s.isPhoneEnabled(.photos) || inbox,
                             contacts: s.isPhoneEnabled(.contacts),
                             anyOn: s.enabledCount > 0)
    }
}

extension WatcherKind {
    /// The order `WatcherRun` runs them in (its progress reports follow it).
    static let runOrder: [WatcherKind] = [.expiry, .recurring, .termChange, .impersonation, .siteFraud]

    /// The Guard section each watcher fills.
    var guardTitle: String {
        switch self {
        case .impersonation: return "Impersonation"
        case .siteFraud: return "Site fraud"
        case .expiry: return "Expiring soon"
        case .termChange: return "Term changes"
        default: return "Subscriptions"
        }
    }

    /// One word under its tile in the strip.
    var shortTitle: String {
        switch self {
        case .impersonation: return "People"
        case .siteFraud: return "Sites"
        case .expiry: return "Expiry"
        case .termChange: return "Terms"
        default: return "Money"
        }
    }
}

// MARK: - Header

/// The Guard's status header: the mascot, how many findings are open, which sources are watched, the five watchers
/// (live while they run), the last run, what leaves the phone, and Run now.
struct GuardHeader: View {
    @ObservedObject var watchers: WatchersService
    @ObservedObject var sources: SourcesService
    let coverage: GuardCoverage
    @ObservedObject private var online = OnlineChecksService.shared
    @ObservedObject private var mail = MailTriageService.shared

    private struct Entry: Identifiable { let id: String; let title: String; let on: Bool }

    private var entries: [Entry] {
        [Entry(id: "sample", title: "Sample", on: sources.sampleEnabled)]
            + PhoneSource.allCases.map { Entry(id: $0.id, title: $0.title, on: sources.isPhoneEnabled($0)) }
            + [Entry(id: "inbox", title: "Inbox", on: sources.inboxEnabled && !sources.inboxBatches.isEmpty)]
    }

    private var running: Bool { watchers.running || sources.scanning }

    private var mascot: MascotState {
        if running { return .scanning }
        if !coverage.anyOn { return .empty }
        if watchers.newCount > 0 && !watchers.findings.isEmpty { return .found }
        return watchers.findings.isEmpty ? .happy : .watching
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            topRow
            glyphRow
            WatcherStrip(feed: watchers.progress, counts: counts, ran: watchers.summary != nil)
            statusRow
            onlineRow
        }
        .padding(16)
        .background(background.clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous)))
        .overlay {
            if running {
                WorkingBorder(radius: 20)
            } else {
                RoundedRectangle(cornerRadius: 20, style: .continuous).stroke(Palette.border, lineWidth: 1)
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("guard.header")
    }

    /// Findings per watcher id, for the strip's resting counts (subscriptions count the census).
    private var counts: [String: Int] {
        var out = Dictionary(grouping: watchers.findings, by: { $0.watcherId }).mapValues(\.count)
        out[WatcherKind.recurring.id] = watchers.summary.map { $0.census.rows.count }
        out[WatcherKind.expiry.id] = watchers.summary.map { $0.expiries.count }
        return out
    }

    private var open: Int { watchers.findings.filter { $0.verdict != .confirmed }.count }

    private var topRow: some View {
        HStack(alignment: .center, spacing: 14) {
            MascotView(state: mascot, size: 76)
            VStack(alignment: .leading, spacing: 0) {
                Caption(text: running ? "Watching now" : "Watching over you")
                if watchers.summary != nil {
                    RollingNumber(value: open, font: Typeface.display(44), color: open > 0 ? Palette.warnText : Palette.ink)
                    Text(open == 1 ? "warning to look at" : "warnings to look at")
                        .font(.caption).foregroundStyle(Palette.inkSoft)
                } else {
                    Text("—").font(Typeface.display(44)).foregroundStyle(Palette.inkSoft)
                    Text("first run on its way").font(.caption).foregroundStyle(Palette.inkSoft)
                }
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(watchers.summary == nil ? "Guard: the first watcher run has not finished"
                                : "Guard: \(open) warning\(open == 1 ? "" : "s") to look at")
            .accessibilityIdentifier("guard.header.open")
            Spacer(minLength: 0)
        }
    }

    private var glyphRow: some View {
        let e = entries
        let on = e.filter(\.on)
        return HStack(spacing: 6) {
            ForEach(e) { x in SourceGlyph(id: x.id, on: x.on, size: 26) }
            Spacer(minLength: 4)
            Text("\(on.count) of \(e.count) watched")
                .font(Typeface.mono(12, weight: .semibold)).foregroundStyle(Palette.ink)
                .lineLimit(1).fixedSize()
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(on.count) of \(e.count) sources watched: \(on.map(\.title).joined(separator: ", "))")
        .accessibilityIdentifier("guard.header.sources")
    }

    private var statusRow: some View {
        HStack(spacing: 10) {
            Image(systemName: "clock.arrow.circlepath").font(.caption).foregroundStyle(Palette.inkSoft).accessibilityHidden(true)
            Text(lastRunLine)
                .font(Typeface.mono(11)).foregroundStyle(Palette.inkSoft)
                .lineLimit(2).minimumScaleFactor(0.85)
                .accessibilityIdentifier("guard.header.lastRun")
            Spacer(minLength: 4)
            Button {
                Task { await watchers.run() }
                Task { await mail.run() }
            } label: {
                Label(watchers.running ? "Running" : "Run now", systemImage: "arrow.clockwise")
                    .font(.footnote.weight(.semibold))
                    .padding(.horizontal, 14)
                    .frame(minHeight: 44)
            }
            .buttonStyle(.neonPrimaryCompact)
            .disabled(running || !coverage.anyOn)
            .accessibilityHint("Runs the five watchers over every source that is on")
            .accessibilityIdentifier("guard.runNow")
        }
    }

    private var lastRunLine: String {
        if sources.scanning { return "Reading your sources" }
        if watchers.running { return "The watchers are reading \(sources.enabledCount) source\(sources.enabledCount == 1 ? "" : "s")" }
        guard let s = watchers.summary else { return "Not run yet" }
        let when: String
        if let d = watchers.lastRun {
            when = Date().timeIntervalSince(d) < 60 ? "just now" : d.formatted(.relative(presentation: .named))
        } else {
            when = GuardModel.day(s.todayIso)
        }
        return "Last run \(when) · \(s.itemsChecked) item\(s.itemsChecked == 1 ? "" : "s")"
    }

    /// What leaves the phone: the watchers never send anything; the online phishing checks (Protection) do, and
    /// then this says what, and to whom.
    @ViewBuilder private var onlineRow: some View {
        if online.settings.anyOn {
            VStack(alignment: .leading, spacing: 4) {
                Pill(text: "Online checks on", color: Palette.blue, symbol: "globe")
                Text(GuardHeader.onlineLine(online.settings))
                    .font(.caption).foregroundStyle(Palette.inkSoft)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .accessibilityElement(children: .combine)
            .accessibilityIdentifier("guard.header.online")
        } else {
            HStack(spacing: 8) {
                Pill(text: "0 bytes out", color: Palette.okText, symbol: "lock.fill")
                Text("The watchers read on this iPhone; online checks are off.")
                    .font(.caption).foregroundStyle(Palette.inkSoft)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .accessibilityElement(children: .combine)
            .accessibilityLabel("0 bytes out. The watchers read on this iPhone; online checks are off.")
            .accessibilityIdentifier("guard.header.bytesOut")
        }
    }

    /// "The watchers send nothing. Online checks send: …" from the switches in Me → Online phishing checks.
    static func onlineLine(_ s: OnlinePhishingSettings) -> String {
        var parts: [String] = []
        if s.domainFacts { parts.append("one link domain at a time to Loupe's web helper") }
        if s.feeds { parts.append("nothing about your links (public phishing lists are downloaded)") }
        if s.dnsFacts || s.dnsbl { parts.append("link domains through this iPhone's DNS resolver") }
        if s.safeBrowsing { parts.append("4-byte hash prefixes to Google, only when a link matches") }
        return "The watchers send nothing. Online checks send " + parts.joined(separator: "; ") + "."
    }

    private var background: some View {
        ZStack {
            LinearGradient(colors: [Palette.groundHigh.opacity(0.9), Palette.card], startPoint: .topLeading, endPoint: .bottomTrailing)
            Canvas { g, size in
                var lines = Path()
                var y: CGFloat = 0
                while y < size.height { lines.addRect(CGRect(x: 0, y: y, width: size.width, height: 1)); y += 4 }
                g.fill(lines, with: .color(Palette.cyan.opacity(0.035)))
            }
        }
    }
}

// MARK: - The five watchers, live

/// One tile per watcher in run order. At rest each shows what it holds; while the watchers run, the done ones light
/// up, the running one draws its real progress on a ring (the expiry radar's model half, document by document), and
/// the line under says which is running. Observes only the progress feed.
struct WatcherStrip: View {
    @ObservedObject var feed: WatcherProgressFeed
    let counts: [String: Int]
    let ran: Bool

    var body: some View {
        let state = feed.state
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 0) {
                ForEach(WatcherKind.runOrder, id: \.self) { kind in
                    WatcherTile(kind: kind, phase: phase(kind, state), count: counts[kind.id])
                        .frame(maxWidth: .infinity)
                }
            }
            if let state {
                SweepBar(fraction: GuardProgress.fraction(state), color: Palette.cyan, height: 5, live: true)
                    .accessibilityHidden(true)
                Text(GuardProgress.line(state))
                    .font(Typeface.mono(11)).monospacedDigit().foregroundStyle(Palette.ink)
                    .lineLimit(1).minimumScaleFactor(0.8)
                    .accessibilityIdentifier("guard.strip.status")
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(accessibilityLine(state))
        .accessibilityIdentifier("guard.strip")
    }

    private func phase(_ kind: WatcherKind, _ s: WatcherProgressFeed.State?) -> WatcherTile.Phase {
        guard let s else { return ran ? .rest : .waiting }
        if s.done.contains(kind.id) { return .done }
        if s.current == kind.id { return .running(Double(s.unitDone) / Double(max(1, s.unitTotal))) }
        return .waiting
    }

    private func accessibilityLine(_ s: WatcherProgressFeed.State?) -> String {
        if let s { return GuardProgress.line(s) }
        guard ran else { return "The five watchers have not run yet" }
        return WatcherKind.runOrder.map { k in "\(k.title): \(counts[k.id] ?? 0)" }.joined(separator: ", ")
    }
}

/// The strip's words and overall fraction, from the feed's state (unit-tested).
enum GuardProgress {
    static func fraction(_ s: WatcherProgressFeed.State) -> Double {
        let n = Double(WatcherKind.runOrder.count)
        let running = s.current.flatMap { s.done.contains($0) ? nil : $0 } != nil
            ? Double(s.unitDone) / Double(max(1, s.unitTotal)) : 0
        return min(1, (Double(s.done.count) + running) / n)
    }

    static func line(_ s: WatcherProgressFeed.State) -> String {
        let n = WatcherKind.runOrder.count
        guard let id = s.current, let kind = WatcherKind.runOrder.first(where: { $0.id == id }) else {
            return "Starting the five watchers"
        }
        if kind == .expiry && s.unitTotal > 1 && !s.done.contains(id) {
            return "\(s.done.count + 1) of \(n) · \(kind.title) · the decision model: document \(min(s.unitDone + 1, s.unitTotal)) of \(s.unitTotal)"
        }
        return "\(min(s.done.count + (s.done.contains(id) ? 0 : 1), n)) of \(n) · \(kind.title)"
    }
}

/// A watcher's tile: its glyph in its hue, lit when done or at rest, a progress ring while it runs.
struct WatcherTile: View {
    enum Phase: Equatable {
        case rest, waiting, done
        case running(Double)
    }

    let kind: WatcherKind
    let phase: Phase
    let count: Int?
    var size: CGFloat = 44

    var body: some View {
        let hue = kind.tint
        let lit = phase == .done || phase == .rest || { if case .running = phase { return true } else { return false } }()
        let shape = RoundedRectangle(cornerRadius: size * 0.28, style: .continuous)
        VStack(spacing: 4) {
            ZStack {
                shape.fill(lit ? AnyShapeStyle(LinearGradient(colors: [hue.opacity(0.30), hue.opacity(0.06)], startPoint: .topLeading, endPoint: .bottomTrailing))
                               : AnyShapeStyle(Palette.groundMid))
                shape.stroke(lit ? hue.opacity(0.6) : Palette.border, lineWidth: 1)
                if case .running(let f) = phase {
                    shape.inset(by: -3)
                        .trim(from: 0, to: max(0.04, min(1, f)))
                        .stroke(Palette.cyan, style: StrokeStyle(lineWidth: 2.5, lineCap: .round))
                        .neonGlow(Palette.cyan, radius: 3)
                }
                Image(systemName: kind.symbol)
                    .symbolRenderingMode(.hierarchical)
                    .font(.system(size: size * 0.40, weight: .semibold))
                    .foregroundStyle(lit ? hue : Palette.inkSoft)
                if phase == .done {
                    Image(systemName: "checkmark.circle.fill")
                        .font(.system(size: 13, weight: .bold))
                        .foregroundStyle(Palette.okText)
                        .background(Circle().fill(Palette.card))
                        .offset(x: size * 0.42, y: -size * 0.42)
                }
            }
            .frame(width: size, height: size)
            Text(kind.shortTitle)
                .font(Typeface.mono(10, weight: .medium)).foregroundStyle(Palette.inkSoft)
                .lineLimit(1).minimumScaleFactor(0.7)
            Text(phase == .rest ? (count.map { "\($0)" } ?? "—") : " ")
                .font(Typeface.mono(13, weight: .semibold)).monospacedDigit()
                .foregroundStyle((count ?? 0) > 0 ? Palette.ink : Palette.inkSoft)
        }
        .accessibilityHidden(true)
    }
}

/// A section's caption on Guard, with the watcher behind it on the right.
struct GuardSectionTitle: View {
    let title: String
    var detail: String? = nil

    var body: some View {
        HStack(alignment: .firstTextBaseline) {
            Text(title).font(Typeface.display(24)).foregroundStyle(Palette.ink)
                .accessibilityAddTraits(.isHeader)
            Spacer(minLength: 8)
            if let detail {
                Text(detail.uppercased())
                    .font(Typeface.mono(10, weight: .medium)).tracking(0.8)
                    .foregroundStyle(Palette.inkSoft)
                    .lineLimit(1)
                    .accessibilityHidden(true)
            }
        }
        .padding(.top, 10)
        .padding(.horizontal, 4)
    }
}

/// The primary style at a smaller size, for a button inside a row (Run now).
struct NeonPrimaryCompactButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var enabled
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .foregroundStyle(Palette.onAccent)
            .background(
                LinearGradient(colors: [Palette.cyan, Palette.blue], startPoint: .leading, endPoint: .trailing)
                    .opacity(enabled ? 1 : 0.4),
                in: Capsule())
            .neonGlow(Palette.cyan, radius: configuration.isPressed ? 2 : 4, on: enabled)
            .scaleEffect(configuration.isPressed ? 0.97 : 1)
    }
}

extension ButtonStyle where Self == NeonPrimaryCompactButtonStyle {
    static var neonPrimaryCompact: NeonPrimaryCompactButtonStyle { NeonPrimaryCompactButtonStyle() }
}

/// A row's small verdict or action button: 44 pt tall, tinted.
struct GuardActionButton: View {
    let title: String
    let symbol: String
    var tint: Color = Palette.inkSoft
    let id: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Label(title, systemImage: symbol)
                .font(.footnote.weight(.semibold))
                .foregroundStyle(tint)
                .padding(.horizontal, 12)
                .frame(minHeight: 44)
                .frame(maxWidth: .infinity)
                .background(tint.opacity(0.1), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).stroke(tint.opacity(0.35), lineWidth: 1))
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier(id)
    }
}
