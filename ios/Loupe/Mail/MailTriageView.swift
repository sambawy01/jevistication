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

/// The Mail triage screen: rows by section (phishing suspected first), each with its category,
/// the concrete signals behind a phishing verdict, link checks, and Open / Mark safe / Confirm.
struct MailTriageView: View {
    @ObservedObject var mail: MailTriageService
    @ObservedObject private var assist = AssistService.shared
    @State private var openItem: SourceItem?
    @State private var replyTo: SourceItem?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                LiveRunSection(view: "email")
                Text("Loupe Station's mail rules, read on this iPhone: a category from keyword rules, and a phishing verdict only from evidence a scam cannot hide — the sender's domain, the name it shows, where replies go, the mail server's checks and where links really go. The wording alone never flags an email.")
                    .font(.caption).foregroundStyle(Palette.inkSoft)
                LayaOffBanner(feature: Features.shared.EMAIL)
                if let notice = mail.notice {
                    HStack {
                        Text(notice).font(.caption).foregroundStyle(Palette.inkSoft)
                        Spacer()
                        if mail.lastVerdict != nil {
                            Button("Undo") { mail.undo() }.font(.caption.weight(.semibold))
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
                        Text("No mail to triage. Turn on the sample or Mail in Sources.")
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
        .navigationTitle("Mail triage")
        .navigationBarTitleDisplayMode(.inline)
        .task { if mail.summary == nil { await mail.run() } }
        .refreshable { await mail.run() }
        .sheet(item: $openItem) { ItemTextView(item: $0) }
        .sheet(item: $replyTo) { ReplyDraftSheet(item: $0, assist: assist, review: ReviewService.shared) }
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
                Text(labels.map { $0.replacingOccurrences(of: "Laya/", with: "") + (r.weakLabels.contains($0) ? " (weak rule)" : "") }.joined(separator: " · "))
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
                Button("Open") { openItem = mail.item(r.itemId) }
                    .accessibilityIdentifier("mail.open")
                if r.phishing {
                    Button("Mark safe") { mail.markSafe(r) }
                        .accessibilityIdentifier("mail.markSafe")
                } else {
                    Button("Confirm phishing") { mail.confirmPhishing(r) }
                        .foregroundStyle(Palette.dangerText)
                        .accessibilityIdentifier("mail.confirmPhishing")
                    // Only with an assistant configured; never offered for suspected phishing.
                    if assist.isReady {
                        Button("Draft a reply") { replyTo = mail.item(r.itemId) }
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
