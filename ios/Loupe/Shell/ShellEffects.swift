import LoupeKit
import SwiftUI

/// App-wide work that used to hang off the Now tab, now on the root so it runs whichever place a launch opens on.
///
/// Nothing heavy starts when the app opens (owner rulings O-4 and A, 2026-09-28): every run of the checks goes through
/// `RunCoordinator` (the first check after onboarding, Run now, Scan again, a source switched on or off through the
/// sources' `userScan` / `itemsChanged` hooks, and the nightly run). The watchers', the privacy check's and mail
/// triage's saved results load in their services' init, off the main thread. So at launch this only loads what is
/// saved (the judgments and the ledger, read off the main thread). It also collects Review proposals when a check has
/// run, and handles the DEBUG demos and `-LoupeOpen queue|review`.
struct ShellEffects: ViewModifier {
    let router: AppRouter
    private var sources: SourcesService { .shared }
    @State private var opened = false
    private var watchers: WatchersService { .shared }
    private var privacy: PrivacyService { .shared }
    private var mail: MailTriageService { .shared }
    private var review: ReviewService { .shared }
    private var judgments: JudgmentsService { .shared }

    func body(content: Content) -> some View {
        content
            // Whatever a check proposes goes to Review as soon as it has run (never run until approved).
            // (@Published fires before the value is stored: hop once so collect reads the new one.)
            .onReceive(privacy.$summary.receive(on: DispatchQueue.main)) { _ in review.collect() }
            .onReceive(mail.$summary.receive(on: DispatchQueue.main)) { _ in review.collect() }
            .onReceive(watchers.$summary.receive(on: DispatchQueue.main)) { _ in review.collect() }
            .task {
                // The judgments and the ledger, read off the main thread; the Unsure queue is drawn in the
                // background, so the Needs you count lands later (2026-09-28 launch hang).
                await judgments.loadInBackground()
                #if DEBUG
                if LaunchOptions.current.bigLedger > 0 { await judgments.seedLargeLedger(count: LaunchOptions.current.bigLedger) }
                if LaunchOptions.current.queueDemo { await seedWhenScanned() }
                if LaunchOptions.current.reviewDemo { await sources.seedReviewDemo() }
                #endif
                guard !opened else { return }
                opened = true
                if LaunchOptions.current.openScreen == "queue" { router.openAsk(queue: true) }
                if LaunchOptions.current.openScreen == "review" { router.openHome(.review) }
            }
    }

    #if DEBUG
    /// Waits (briefly) for the sample scan, then seeds the fixture queue.
    private func seedWhenScanned() async {
        for _ in 0..<100 where judgments.sampleItems().isEmpty { try? await Task.sleep(nanoseconds: 100_000_000) }
        if judgments.rows.isEmpty { await judgments.seedFixtureQueue() }
    }
    #endif
}
