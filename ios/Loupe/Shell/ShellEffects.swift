import Combine
import LoupeKit
import SwiftUI

/// App-wide work that used to hang off the Now tab, now on the root so it runs whichever place a launch opens on.
///
/// Nothing heavy starts when the app opens (owner ruling O-4): source scans, the privacy check, mail triage, the
/// watchers and the sort run overnight, on Run now / Scan again, and once after onboarding. So at launch this only
/// loads what is already saved (the judgments and the ledger, read off the main thread); the watchers, the privacy
/// check and mail triage re-run only when a scan actually changes the items (a scan lands, a source is switched on
/// or off). It also collects Review proposals when a check has run, and handles the DEBUG demos and
/// `-LoupeOpen queue|review`.
struct ShellEffects: ViewModifier {
    let router: AppRouter
    @ObservedObject var sources: SourcesService = .shared
    @ObservedObject private var readiness = ModelReadiness.shared
    @State private var opened = false
    private var watchers: WatchersService { .shared }
    private var privacy: PrivacyService { .shared }
    private var mail: MailTriageService { .shared }
    private var review: ReviewService { .shared }
    private var judgments: JudgmentsService { .shared }

    func body(content: Content) -> some View {
        content
            // The items changed: re-run the checks over them. The first value arrives on subscribe (at launch),
            // and launch starts nothing heavy (O-4), so it is dropped.
            .onReceive(sources.$sampleScan.combineLatest(sources.$sampleEnabled, sources.$revision).dropFirst()) { _ in
                runChecks()
            }
            // The decision model arrived (download finished, files copied in): the watchers' model half can run now.
            // (Not at launch: onChange never fires for the value the view starts with.)
            .onChange(of: readiness.isReady) { _, ready in
                if ready, !sources.scanning { Task { await watchers.run() } }
            }
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
                // UI tests over the fixtures need Home's cards filled at launch: only then do the checks run at open.
                if LaunchOptions.current.fixtureMode { runChecks() }
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

    /// The watchers, the privacy check and mail triage over the items as they are now (not while a scan is still
    /// writing them: its end bumps `revision` and brings this back).
    private func runChecks() {
        guard !sources.scanning else { return }
        Task { await watchers.run() }
        Task { await privacy.run() }
        Task { await mail.run() }
    }

    #if DEBUG
    /// Waits (briefly) for the sample scan, then seeds the fixture queue.
    private func seedWhenScanned() async {
        for _ in 0..<100 where judgments.sampleItems().isEmpty { try? await Task.sleep(nanoseconds: 100_000_000) }
        if judgments.rows.isEmpty { await judgments.seedFixtureQueue() }
    }
    #endif
}
