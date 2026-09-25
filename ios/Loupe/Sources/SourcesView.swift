import LoupeKit
import SwiftUI

/// The Sources tab: every source with its item count and last scan. The sample is on by default and
/// labelled as sample data at every mention. The phone's own sources (epic #7 child 7) follow, each
/// with its switch, permission state, count, last scan and any error with what to do about it.
struct SourcesView: View {
    @ObservedObject var sources: SourcesService
    @ObservedObject private var center = ActivityCenter.shared

    var body: some View {
        NavigationStack {
            List {
                // The live run of the source being read, in place at the top of the screen (docs/LIVE-RUN-VIEW.md).
                if center.latest("sources")?.running == true {
                    NeonSection { LiveRunSection(view: "sources", whileRunning: true).listRowInsets(EdgeInsets()) }
                        .accessibilityIdentifier("sources.liverun")
                }
                NeonSection {
                    sampleRow
                } header: {
                    Text("On this iPhone")
                } footer: {
                    Text("Synthetic receipts, SPECIMEN documents, subscription mail and a phishing example, shipped inside the app so Loupe can be tried without your data. Read on this iPhone; nothing leaves it.")
                }
                NeonSection {
                    ForEach(PhoneSource.allCases) { source in
                        PhoneSourceRow(sources: sources, source: source)
                    }
                } header: {
                    Text("Phone sources")
                } footer: {
                    Text("Each source is off until you turn it on. Turning one on is the only time Loupe asks iOS for its permission. Off means its items leave every judgment and watcher.")
                }
                NeonSection {
                    NavigationLink {
                        InboxView(sources: sources)
                    } label: {
                        InboxCard(sources: sources)
                    }
                    .accessibilityIdentifier("sources.inbox")
                } header: {
                    Text("Inbox")
                } footer: {
                    Text("Imported CSVs, mail files, ZIP archives and text shared from other apps. Each import is listed with its counts and can be removed.")
                }
                NeonSection {
                    NavigationLink {
                        PrivacyView(privacy: PrivacyService.shared)
                    } label: {
                        PrivacyCard(privacy: PrivacyService.shared)
                    }
                    .accessibilityIdentifier("sources.privacy")
                } footer: {
                    Text("Checks every source that is on for ID and card numbers, IBANs, contact lists, keys and tokens, and duplicate files. Mechanical, on this iPhone.")
                }
                NeonSection {
                    NavigationLink {
                        MailTriageView(mail: MailTriageService.shared)
                    } label: {
                        MailTriageCard(mail: MailTriageService.shared)
                    }
                    .accessibilityIdentifier("sources.mail")
                } header: {
                    Text("Mail")
                } footer: {
                    Text("Sorts every email from the sources that are on (the sample now, your IMAP mailbox when it is on) into Loupe Station's categories and checks it for phishing: sender, reply address, mail-server checks and links. Mechanical, on this iPhone.")
                }
                if let problem = sources.problem {
                    NeonSection { Text(problem).font(.footnote).foregroundStyle(Palette.red) }
                }
            }
            .scrollContentBackground(.hidden)
            .neonGround()
            .navigationTitle("Sources")
        }
    }

    @ViewBuilder private var sampleRow: some View {
        VStack(alignment: .leading, spacing: 8) {
            Toggle(isOn: Binding(get: { sources.sampleEnabled }, set: { sources.setSampleEnabled($0) })) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Sample data").font(.headline)
                    Text(SourcesService.sampleLabel)
                        .font(.footnote.weight(.semibold)).foregroundStyle(Palette.amber)
                        .accessibilityIdentifier("sources.sample.label")
                }
            }
            .accessibilityIdentifier("sources.sample.toggle")
            if let p = sources.progress {
                ProgressView(value: p.fraction) {
                    Text(p.total == 0 ? "Preparing…" : "Reading \(p.seen) of \(p.total)")
                        .font(.footnote).foregroundStyle(Palette.inkSoft)
                }
                .accessibilityIdentifier("sources.sample.progress")
            } else if let scan = sources.sampleScan {
                Text(countLine(scan))
                    .font(.subheadline)
                    .accessibilityIdentifier("sources.sample.count")
                Text(detailLine(scan)).font(.footnote).foregroundStyle(Palette.inkSoft)
                HStack {
                    Text("Last scan \(Date(timeIntervalSince1970: Double(scan.scannedAtEpochMillis) / 1000).formatted(date: .abbreviated, time: .shortened))")
                        .font(.footnote).foregroundStyle(Palette.inkSoft)
                    Spacer()
                    Button("Scan again") { sources.scanSample() }
                        .font(.footnote).buttonStyle(.borderless)
                        .accessibilityIdentifier("sources.sample.rescan")
                }
            } else if sources.sampleEnabled {
                Text("Not scanned yet").font(.footnote).foregroundStyle(Palette.inkSoft)
            }
            if !sources.sampleEnabled {
                Text("Off: judgments and watchers ignore the sample.").font(.footnote).foregroundStyle(Palette.inkSoft)
            }
        }
        .padding(.vertical, 4)
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
