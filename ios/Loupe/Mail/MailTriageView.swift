import LoupeKit
import SwiftUI

/// Now's card and Sources' link: "Mail triage: N possible phishing · M need a reply".
struct MailTriageCard: View {
    @ObservedObject var mail: MailTriageService

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: "envelope.badge.shield.half.filled").font(.title2).foregroundStyle(Palette.blue)
            VStack(alignment: .leading, spacing: 2) {
                Text(line).font(.headline).foregroundStyle(Palette.ink)
                Text("Categories, phishing evidence and link checks · on this phone")
                    .font(.caption).foregroundStyle(Palette.inkSoft)
            }
            Spacer()
            Image(systemName: "chevron.right").foregroundStyle(Palette.inkSoft)
        }
        .card()
        .accessibilityElement(children: .combine)
    }

    private var line: String {
        guard let s = mail.summary else { return mail.running ? "Mail triage: reading…" : "Mail triage" }
        if s.rows.isEmpty { return "Mail triage: no mail" }
        var parts: [String] = []
        let p = Int(s.phishingCount)
        if p > 0 { parts.append("\(p) possible phishing") }
        let r = Int(s.needsReplyCount)
        if r > 0 { parts.append("\(r) need\(r == 1 ? "s" : "") a reply") }
        if parts.isEmpty { parts.append("\(s.rows.count) email\(s.rows.count == 1 ? "" : "s")") }
        return "Mail triage: " + parts.joined(separator: " · ")
    }
}

/// Mail, one place (spec D10): the mailbox (connect, the switch, Scan again, settings and Remove), what was found in
/// the mail, then the triage rows by section (phishing suspected first), each with its category, the concrete signals
/// behind a phishing verdict, link checks, and Open / Mark safe / Confirm / Draft a reply.
struct MailScreen: View {
    /// Item headers come from `ItemIndex`, built off the main thread: repaint when it lands.
    @ObservedObject private var itemIndex = ItemIndex.Store.shared
    @ObservedObject var mail: MailTriageService
    @ObservedObject var sources: SourcesService = .shared
    @ObservedObject private var watchers = WatchersService.shared
    @ObservedObject private var assist = AssistService.shared
    @State private var openItem: SourceItem?
    @State private var replyTo: SourceItem?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                PhoneSourceRow(sources: sources, source: .mail)
                MailFoundCard(found: found)
                LiveRunSection(view: "email")
                Text("Loupe Station's mail rules, read on this iPhone: a category from keyword rules, and a phishing verdict only from evidence a scam cannot hide — the sender's domain, the name it shows, where replies go, the mail server's checks and where links really go. The wording alone never flags an email.")
                    .font(.caption).foregroundStyle(Palette.inkSoft)
                LayaOffBanner(feature: Features.shared.EMAIL)
                if let notice = mail.notice {
                    HStack {
                        Text(notice).font(.caption).foregroundStyle(Palette.inkSoft)
                        Spacer()
                        if mail.lastVerdict != nil {
                            Button { mail.undo() } label: { Text("Undo").tapTarget() }.font(.caption.weight(.semibold))
                                .accessibilityIdentifier("mail.undo")
                        }
                    }
                }
                if let status = mail.onlineStatus {
                    Label { Text(status).font(.caption).foregroundStyle(Palette.ink) } icon: {
                        Image(systemName: "globe").foregroundStyle(Palette.blue)
                    }
                    .card()
                    .accessibilityIdentifier("mail.onlineStatus")
                }
                if let s = mail.summary {
                    if s.rows.isEmpty {
                        Text("No mail yet. Add a mailbox above.")
                            .font(.subheadline).foregroundStyle(Palette.inkSoft).card()
                            .accessibilityIdentifier("mail.none")
                    }
                    ForEach(s.sections, id: \.name) { section in
                        sectionView(section, s.inSection(section: section))
                    }
                    if !s.webLinks.isEmpty { webLinks(s.webLinks) }
                    Text("Warnings only. A row with no sign is not cleared: these checks cover only what they look for.")
                        .font(.caption).foregroundStyle(Palette.inkSoft)
                } else {
                    HStack { ProgressView(); Text("Reading your mail…").foregroundStyle(Palette.inkSoft) }.card()
                }
            }
            .padding(16)
        }
        .neonGround()
        .navigationTitle("Mail")
        .navigationBarTitleDisplayMode(.inline)
        .task { if mail.summary == nil { await mail.run() } }
        .refreshable { await mail.run() }
        // Re-run here too (audit P2-11), like the Privacy check's.
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button { Task { await mail.run() } } label: {
                    Image(systemName: "arrow.clockwise").frame(minWidth: 44, minHeight: 44)
                }
                .disabled(mail.running)
                .accessibilityLabel("Check again")
                .accessibilityIdentifier("mail.rerun")
            }
        }
        .sheet(item: $openItem) { ItemTextView(item: $0) }
        .sheet(item: $replyTo) { ReplyDraftSheet(item: $0, assist: assist, review: ReviewService.shared) }
    }

    private var found: MailFound {
        MailFound.of(phishing: Int(mail.summary?.phishingCount ?? 0), needsReply: Int(mail.summary?.needsReplyCount ?? 0),
                     mailItemIds: Set(mail.summary?.rows.map(\.itemId) ?? []),
                     census: watchers.summary?.census.rows ?? [])
    }

    private func sectionView(_ section: MailSection, _ rows: [MailRow]) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .firstTextBaseline) {
                Text(section.title).font(Typeface.display(20)).foregroundStyle(Palette.ink)
                Spacer()
                Text("\(rows.count)").font(Typeface.mono(13)).foregroundStyle(Palette.inkSoft)
            }
            .accessibilityElement(children: .combine)
            .accessibilityIdentifier("mail.section.\(section.name.lowercased())")
            ForEach(rows, id: \.itemId) { row($0) }
        }
    }

    private func row(_ r: MailRow) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 6) {
                if r.phishing {
                    Pill(text: "PHISHING?", color: Palette.dangerText, symbol: "exclamationmark.triangle.fill")
                        .accessibilityIdentifier("mail.flag.phishing")
                }
                Pill(text: r.categoryTitle, color: Palette.blue)
                if let p = r.providerCategory { Pill(text: p.name + " · " + (MailClassify.shared.CATEGORY_TITLES[p.key] ?? p.key), color: Palette.inkSoft) }
                if r.personVerdict != nil { Pill(text: "YOUR ANSWER", color: Palette.okText) }
                Spacer()
            }
            Text(r.subject.isEmpty ? "(no subject)" : r.subject).font(.headline).foregroundStyle(Palette.ink)
            Text(r.sender).font(Typeface.mono(11)).foregroundStyle(Palette.inkSoft)
            if let item = ItemIndex.item(r.itemId) { ItemRefHeader(item: item); ItemActions(item: item) }
            let labels = r.labels.filter { $0 != MailClassify.shared.PHISHING_LABEL }
            if !labels.isEmpty {
                Text(labels.map { $0.replacingOccurrences(of: MailClassify.shared.LABEL_PREFIX, with: "") + (r.weakLabels.contains($0) ? " (weak rule)" : "") }.joined(separator: " · "))
                    .font(.caption).foregroundStyle(Palette.inkSoft)
            }
            let signals = r.signals
            if !signals.isEmpty {
                VStack(alignment: .leading, spacing: 4) {
                    Text(r.phishing ? "Why it may be phishing" : "Sender and link checks")
                        .font(.caption.weight(.semibold)).foregroundStyle(r.phishing ? Palette.dangerText : Palette.ink)
                    ForEach(Array(signals.enumerated()), id: \.offset) { _, s in
                        Text("• " + s.text).font(.caption).foregroundStyle(Palette.ink)
                            .fixedSize(horizontal: false, vertical: true)
                            .accessibilityIdentifier("mail.signal.\(s.code)")
                    }
                    if r.verdict.score > 0 {
                        Text(r.scoreLine).font(.caption2).foregroundStyle(Palette.inkSoft)
                    }
                }
                .padding(10)
                .background(r.phishing ? Palette.dangerSoft : Palette.track, in: RoundedRectangle(cornerRadius: 10))
            }
            HStack(spacing: 16) {
                Button { openItem = mail.item(r.itemId) } label: { Text("Open").tapTarget() }
                    .accessibilityIdentifier("mail.open")
                if r.phishing {
                    Button { mail.markSafe(r) } label: { Text("Mark safe").tapTarget() }
                        .accessibilityIdentifier("mail.markSafe")
                } else {
                    Button { mail.confirmPhishing(r) } label: { Text("Confirm phishing").tapTarget() }
                        .foregroundStyle(Palette.dangerText)
                        .accessibilityIdentifier("mail.confirmPhishing")
                    // Only with an assistant configured; never offered for suspected phishing.
                    if assist.isReady {
                        Button { replyTo = mail.item(r.itemId) } label: { Text("Draft a reply").tapTarget() }
                            .accessibilityIdentifier("mail.draftReply")
                    }
                }
            }
            .font(.caption.weight(.semibold))
            .buttonStyle(.borderless)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .card()
    }

    private func webLinks(_ links: [WebLinkCheck]) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Links in your items").font(Typeface.display(20)).foregroundStyle(Palette.ink)
                .accessibilityIdentifier("mail.section.links")
            LayaOffBanner(feature: Features.shared.BROWSER)
            Text("Site checks on web links found outside mail: one verdict from the phishing formula Loupe shares with Loupe Station, with the signals behind it.")
                .font(.caption).foregroundStyle(Palette.inkSoft)
            ForEach(Array(links.enumerated()), id: \.offset) { _, l in
                VStack(alignment: .leading, spacing: 4) {
                    HStack {
                        Pill(text: l.check.levelTitle.uppercased(), color: l.check.warn ? Palette.warnText : Palette.inkSoft)
                        Text(l.itemName).font(Typeface.mono(11)).foregroundStyle(Palette.inkSoft).lineLimit(1)
                    }
                    Text(l.check.url).font(Typeface.mono(12)).foregroundStyle(Palette.ink).lineLimit(2)
                    if let item = ItemIndex.item(l.itemId) { ItemRefHeader(item: item) }
                    SiteVerdictSections(check: l.check)
                    Button("Open") { openItem = mail.item(l.itemId) }.font(.caption.weight(.semibold)).buttonStyle(.borderless)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .card()
            }
        }
    }
}

/// A site verdict's groups (formula v1.2): "Warning signs" (or "Small things noticed" when the level
/// does not warn), "Reassuring facts", other facts, and the collapsed "Also noticed (not counted)".
struct SiteVerdictSections: View {
    let check: SiteCheckResult

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            if !check.lines.isEmpty {
                Text(check.linesTitle).font(.caption.weight(.semibold)).foregroundStyle(check.warn ? Palette.warnText : Palette.inkSoft)
                    .accessibilityIdentifier("site.warnings")
                ForEach(Array(check.lines.enumerated()), id: \.offset) { _, line in
                    Text("• " + line).font(.caption).foregroundStyle(Palette.ink)
                }
            }
            if !check.reassuringFacts.isEmpty {
                Text("Reassuring facts").font(.caption.weight(.semibold)).foregroundStyle(Palette.okText)
                    .accessibilityIdentifier("site.reassuring")
                ForEach(Array(check.reassuringFacts.enumerated()), id: \.offset) { _, line in
                    Text("• " + line).font(.caption).foregroundStyle(Palette.ink)
                }
            }
            if !check.otherFacts.isEmpty {
                Text("Other facts").font(.caption.weight(.semibold)).foregroundStyle(Palette.inkSoft)
                ForEach(Array(check.otherFacts.enumerated()), id: \.offset) { _, line in
                    Text("• " + line).font(.caption).foregroundStyle(Palette.inkSoft)
                }
            }
            if !check.notCountedLines.isEmpty {
                DisclosureGroup("Also noticed (not counted)") {
                    ForEach(Array(check.notCountedLines.enumerated()), id: \.offset) { _, line in
                        Text("• " + line).font(.caption).foregroundStyle(Palette.inkSoft)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
                .font(.caption.weight(.semibold))
                .accessibilityIdentifier("site.notCounted")
            }
        }
    }
}

/// What Mail found (spec D10): possible phishing, mail that needs a reply, and the subscriptions whose charges were
/// read from an email. Pure, for the tests.
struct MailFound: Equatable {
    let phishing: Int
    let needsReply: Int
    /// Census merchants with at least one charge read from an email, in the census's order.
    let subscriptions: [String]

    static func of(phishing: Int, needsReply: Int, mailItemIds: Set<String>, census: [CensusRow]) -> MailFound {
        MailFound(phishing: phishing, needsReply: needsReply,
                  subscriptions: census.filter { row in row.itemIds.contains { mailItemIds.contains($0) } }.map(\.merchant))
    }

    /// The card's spoken line (the scenario tests read the numbers from it).
    var line: String { "Phishing: \(phishing) · Needs a reply: \(needsReply) · Subscriptions found: \(subscriptions.count)" }
}

struct MailFoundCard: View {
    let found: MailFound

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Caption(text: "Found in your mail")
            HStack(spacing: 0) {
                stat("\(found.phishing)", "possible phishing", found.phishing > 0 ? Palette.dangerText : Palette.ink)
                stat("\(found.needsReply)", "need a reply", Palette.ink)
                stat("\(found.subscriptions.count)", "subscriptions", Palette.ink)
            }
            if !found.subscriptions.isEmpty {
                Text(found.subscriptions.joined(separator: " · "))
                    .font(.caption).foregroundStyle(Palette.inkSoft)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .card()
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(found.line)
        .accessibilityIdentifier("mail.found")
    }

    private func stat(_ n: String, _ label: String, _ color: Color) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(n).font(Typeface.mono(22, weight: .bold)).monospacedDigit().foregroundStyle(color)
            Text(label).font(.caption2).foregroundStyle(Palette.inkSoft)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// Mail's one entry in What Loupe reads: the mailbox and what it found, opening `MailScreen`.
struct MailEntryCard: View {
    @ObservedObject var sources: SourcesService
    @ObservedObject var mail: MailTriageService

    var body: some View {
        HStack(spacing: 12) {
            SourceGlyph(id: PhoneSource.mail.id, on: sources.isPhoneEnabled(.mail))
            VStack(alignment: .leading, spacing: 3) {
                Text("Mail").font(Typeface.display(22)).foregroundStyle(Palette.ink)
                Text(Self.line(account: sources.mailAccount?.username, on: sources.isPhoneEnabled(.mail),
                               phishing: Int(mail.summary?.phishingCount ?? 0), needsReply: Int(mail.summary?.needsReplyCount ?? 0)))
                    .font(.footnote).foregroundStyle(Palette.inkSoft)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 8)
            Image(systemName: "chevron.forward").font(.caption).foregroundStyle(Palette.inkSoft).accessibilityHidden(true)
        }
        .frame(minHeight: 44)
        .card()
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
        .accessibilityHint("Connect, sync, what was found and the actions")
    }

    static func line(account: String?, on: Bool, phishing: Int, needsReply: Int) -> String {
        guard let account else { return "No mailbox yet · add one to read receipts, bills and phishing" }
        guard on else { return "\(account) · off" }
        var parts = [account]
        if phishing > 0 { parts.append("\(phishing) possible phishing") }
        if needsReply > 0 { parts.append("\(needsReply) need\(needsReply == 1 ? "s" : "") a reply") }
        return parts.joined(separator: " · ")
    }
}
