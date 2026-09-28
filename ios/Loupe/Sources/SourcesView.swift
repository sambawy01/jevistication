import LoupeKit
import SwiftUI

/// The Sources screen (visual pass 2026-09-25): a header with every item read, which sources are on, the last scan
/// and what leaves the phone; then one card per phone source (epic #7 child 7) with its glyph, badge, switch,
/// permission state and either its resting numbers or, while it is read, the live scan display in place, and any
/// error with what to do about it. No sample data (owner decision 2026-09-28): the app reads only the phone's own
/// sources and what is imported. What Loupe reads, without a stack of its own: Me pushes it.
struct SourcesScreen: View {
    @ObservedObject var sources: SourcesService
    @Environment(\.accessibilityReduceMotion) private var systemReduceMotion

    var body: some View {
            ScrollViewReader { proxy in
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    SourcesHeader(sources: sources)
                    #if DEBUG
                    // DEBUG fixture launches: the hidden fixture sample's count, for the UI tests (never in the app).
                    if sources.hasFixtureSample {
                        Text("\(Int(sources.sampleScan?.itemCount ?? 0)) items")
                            .font(.system(size: 6)).opacity(0.02)
                            .accessibilityIdentifier("debug.fixture.count")
                    }
                    #endif

                    SourcesSectionTitle(title: "Phone sources")
                    ForEach(PhoneSource.allCases.filter { $0 != .mail }) { source in
                        PhoneSourceRow(sources: sources, source: source).id(source.id)
                    }
                    // Mail is one place (spec D10): connect, sync, what was found and the actions, on its own screen.
                    NavigationLink {
                        MailScreen(mail: MailTriageService.shared)
                    } label: {
                        MailEntryCard(sources: sources, mail: MailTriageService.shared)
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("sources.mail")
                    SourcesFootnote(text: "On-device sources (Photos, Files, Calendar and Contacts) are on by default, and you can turn any of them off. Off means its items leave every judgment and watcher. Mail waits until you add a mailbox.")

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
            .navigationTitle("What Loupe reads")
            .navigationBarTitleDisplayMode(.inline)
            // The scans' live runs are drawn in place here, so the Activity dock leaves them out on this screen.
            .onAppear { ActivityCenter.shared.show("sources") }
            .onDisappear { ActivityCenter.shared.hide("sources") }
    }
}
