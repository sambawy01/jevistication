import LoupeKit
import SwiftUI

/// The Sources tab (visual pass 2026-09-25): a header with every item read, which sources are on, the last scan
/// and what leaves the phone; then one card per source with its glyph, badge, switch and either its resting
/// numbers or, while it is read, the live scan display in place. The sample is on by default and labelled as
/// sample data at every mention. The phone's own sources (epic #7 child 7) follow, each with its switch,
/// permission state, count, last scan and any error with what to do about it.
struct SourcesView: View {
    @ObservedObject var sources: SourcesService
    @Environment(\.accessibilityReduceMotion) private var systemReduceMotion

    var body: some View {
        NavigationStack {
            ScrollViewReader { proxy in
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    SourcesHeader(sources: sources)

                    SourcesSectionTitle(title: "On this iPhone")
                    sampleCard
                    SourcesFootnote(text: "Synthetic receipts, SPECIMEN documents, subscription mail and a phishing example, shipped inside the app so Loupe can be tried without your data. Read on this iPhone; nothing leaves it.")

                    SourcesSectionTitle(title: "Phone sources")
                    ForEach(PhoneSource.allCases) { source in
                        PhoneSourceRow(sources: sources, source: source).id(source.id)
                    }
                    SourcesFootnote(text: "Each source is off until you turn it on. Turning one on is the only time Loupe asks iOS for its permission. Off means its items leave every judgment and watcher.")

                    SourcesSectionTitle(title: "Inbox")
                    NavigationLink {
                        InboxView(sources: sources)
                    } label: {
                        InboxCard(sources: sources)
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("sources.inbox")
                    SourcesFootnote(text: "Imported CSVs, mail files, ZIP archives and text shared from other apps. Each import is listed with its counts and can be removed.")

                    SourcesSectionTitle(title: "Checks over your sources")
                    NavigationLink {
                        PrivacyView(privacy: PrivacyService.shared)
                    } label: {
                        PrivacyCard(privacy: PrivacyService.shared)
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("sources.privacy")
                    SourcesFootnote(text: "Checks every source that is on for ID and card numbers, IBANs, contact lists, keys and tokens, and duplicate files. Mechanical, on this iPhone.")
                    NavigationLink {
                        MailTriageView(mail: MailTriageService.shared)
                    } label: {
                        MailTriageCard(mail: MailTriageService.shared)
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("sources.mail")
                    SourcesFootnote(text: "Sorts every email from the sources that are on (the sample now, your IMAP mailbox when it is on) into Loupe Station's categories and checks it for phishing: sender, reply address, mail-server checks and links. Mechanical, on this iPhone.")

                    if let problem = sources.problem {
                        Text(problem).font(.footnote).foregroundStyle(Palette.dangerText)
                            .padding(12).frame(maxWidth: .infinity, alignment: .leading)
                            .background(Palette.dangerSoft, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                    }
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 32)
            }
            #if DEBUG
            // -LoupeScanDemo <source>: bring that source's card into view for screenshots and recordings.
            .onChange(of: sources.liveScans.count) { _, _ in
                guard let demo = SourcesDemo.autoScan, sources.liveScans[demo.id] != nil, sources.liveScans.count == 1 else { return }
                withAnimation { proxy.scrollTo(demo.id, anchor: .top) }
            }
            #endif
            }
            .neonGround()
            .navigationTitle("Sources")
            .navigationBarTitleDisplayMode(.inline)
        }
        // The scans' live runs are drawn in place here, so the Activity dock leaves them out on this screen.
        .onAppear { ActivityCenter.shared.show("sources") }
        .onDisappear { ActivityCenter.shared.hide("sources") }
    }

    @ViewBuilder private var sampleCard: some View {
        let live = sources.liveScans[SourcesService.sampleId]
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .center, spacing: 12) {
                SourceGlyph(id: "sample", on: sources.sampleEnabled)
                VStack(alignment: .leading, spacing: 3) {
                    HStack(spacing: 6) {
                        Text("Sample data").font(Typeface.display(22)).foregroundStyle(Palette.ink)
                        SourceBadge(online: false)
                    }
                    Text(SourcesService.sampleLabel)
                        .font(.footnote.weight(.semibold)).foregroundStyle(Palette.amber)
                        .accessibilityIdentifier("sources.sample.label")
                }
                Spacer(minLength: 8)
                Toggle("Sample data", isOn: Binding(get: { sources.sampleEnabled }, set: { sources.setSampleEnabled($0) }))
                    .labelsHidden()
                    .accessibilityLabel("Sample data")
                    .accessibilityIdentifier("sources.sample.toggle")
            }
            if let live {
                ScanDisplay(live: live).transition(.opacity)
            } else if let scan = sources.sampleScan {
                SourceRestStats(id: "sample", count: Int(scan.itemCount), countLine: countLine(scan), countId: "sources.sample.count",
                                detail: detailLine(scan), coverage: 1,
                                lastScan: Date(timeIntervalSince1970: Double(scan.scannedAtEpochMillis) / 1000), on: sources.sampleEnabled)
                if sources.sampleEnabled {
                    CardAction(title: "Scan again", symbol: "arrow.clockwise", hue: SourceLook.hue("sample")) { sources.scanSample() }
                        .accessibilityIdentifier("sources.sample.rescan")
                }
            } else if sources.sampleEnabled {
                FirstScanInvite(id: "sample", title: "Sample data") { sources.scanSample() }
            }
            if !sources.sampleEnabled {
                Text("Off: judgments and watchers ignore the sample.").font(.footnote).foregroundStyle(Palette.inkSoft)
            }
        }
        .card(active: live.map { !$0.finished } ?? false)
        .animation(Motion.reduced(systemReduceMotion) ? .easeInOut(duration: 0.25) : .spring(response: 0.45, dampingFraction: 0.9), value: live?.id)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("sources.sample")
    }

    private func countLine(_ scan: CachedScan) -> String {
        let n = Int(scan.itemCount)
        return "\(n) \(n == 1 ? "item" : "items")"
    }

    private func detailLine(_ scan: CachedScan) -> String {
        let r = scan.result
        var parts: [String] = []
        let skipped = r.skipped.count
        if skipped > 0 { parts.append("\(skipped) skipped") }
        if r.duplicates > 0 { parts.append("\(r.duplicates) duplicate\(r.duplicates == 1 ? "" : "s")") }
        if r.withoutText > 0 { parts.append("\(r.withoutText) without text") }
        return parts.joined(separator: " · ")
    }
}
