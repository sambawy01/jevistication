import SwiftUI

@main
struct LoupeApp: App {
    /// Background-session wake-ups for the model download (Loupe/Laya/ModelDownloader.swift).
    @UIApplicationDelegateAdaptor(LoupeAppDelegate.self) private var appDelegate
    @StateObject private var web = WebModel.make(launch: LaunchOptions.current)

    init() {
        #if DEBUG
        // -LoupeScenarioReset (with -LoupeScenarioHome): a scenario's first launch starts from an empty fixture home.
        if let name = LaunchOptions.current.scenarioHome, ProcessInfo.processInfo.arguments.contains("-LoupeScenarioReset") {
            ScenarioHome.reset(name)
        }
        // -LoupeResetMascot: forget the mascot choice (UI tests start from the default robot).
        if ProcessInfo.processInfo.arguments.contains("-LoupeResetMascot") {
            UserDefaults.standard.removeObject(forKey: MascotKind.storageKey)
        }
        // -LoupeResetModelConsent: forget the download consent, so a Download tap in a UI test
        // opens the consent sheet and can never start a real download.
        if ProcessInfo.processInfo.arguments.contains("-LoupeResetModelConsent") {
            UserDefaults.standard.removeObject(forKey: ModelConsent.key)
        }
        // -LoupeResetOnboarding: a fresh install's onboarding and default-on switches (the relaunch UI test's
        // first launch only; every later launch goes without it, so what the test set must survive).
        if ProcessInfo.processInfo.arguments.contains("-LoupeResetOnboarding") {
            OnboardingRecord().reset()
            UserDefaults.standard.removeObject(forKey: SortService.enabledKey)
            ProtectionGroup.defaults.removeObject(forKey: ProtectionGroup.Keys.notifySuspicious)
            try? FileManager.default.removeItem(at: LedgerService.defaultHome().appendingPathComponent("sources/enabled.json"))
        }
        #endif
        // Dark neon everywhere (owner decision 2026-09-24): bars, tabs, controls.
        NeonChrome.install()
        // BGTaskScheduler wants every handler registered before launch finishes.
        BackgroundSorter.shared.register()
        BackgroundSorter.shared.schedule()
        // Browsing protection (2026-09-26): the Spotted log (Guard's badge) and Loupe for Safari's state,
        // read at launch and again whenever the app becomes active.
        _ = ProtectionStore.shared
        _ = SafariSetup.shared
    }

    var body: some Scene {
        WindowGroup {
            #if DEBUG
            if ProcessInfo.processInfo.arguments.contains("-LoupeMascotGallery") {
                MascotGallery().preferredColorScheme(.dark)
            } else {
                RootView(initialPlace: LaunchOptions.current.launchPlace)
                    .environmentObject(web)
                    .tint(Palette.blue)
                    .preferredColorScheme(.dark)
            }
            #else
            RootView(initialPlace: LaunchOptions.current.launchPlace)
                .environmentObject(web)
                .tint(Palette.blue)
                .preferredColorScheme(.dark)
            #endif
        }
    }
}

/// DEBUG-only launch arguments used for UI tests and design screenshots. In Release builds
/// every flag reads as off, so none of this can change shipped behaviour.
struct LaunchOptions {
    var fixtureMode = false      // -LoupeFixtures: fixture offers, no network at all
    var autoSearch = false       // -LoupeAutoSearch: run the fixture search on appear
    var ephemeralKey = false     // -LoupeEphemeralKeychain: in-memory key store, starts empty
    /// `-LoupeTab <name>`: a place and a screen on it (`LaunchPlace.from`: home|now, guard|protection, ask|judgments,
    /// web, sources|reads, mail, me, advanced).
    var launchPlace: LaunchPlace = .start
    var skipOnboarding = false   // -LoupeSkipOnboarding: never show the first-launch sheet
    var game: GameMode?          // -LoupeGame human|watch: open the game at launch
    var noModel = false          // -LoupeNoModel: Laya reads as not installed (tests own their model state)
    /// -LoupeModelState ready|missing: `ModelReadiness` reads as this instead of the files, so UI tests
    /// drive Get Laya and the locked states without the 400 MB model (-LoupeNoModel implies missing).
    var modelState: ModelReadiness.State?
    /// -LoupeModelState installed: readiness follows a stand-in installed model through its real path (no override).
    var modelInstalledStandIn = false
    var gameSeed: Int64 = 1      // -LoupeSeed n
    var judgmentDemo: String?    // -LoupeJudgmentDemo <template id>: add it, open its results, run
    var openLibrary = false      // -LoupeLibrary: open Judgments on the Library
    var queueDemo = false        // -LoupeQueueDemo (DEBUG, with -LoupeFixtures): seed the queue with a stand-in scorer
    var mainWatchdog = false     // -LoupeMainWatchdog (DEBUG): show the worst main-thread stall for UI tests
    var bigLedger = 0            // -LoupeBigLedger n (DEBUG, with -LoupeFixtures): n long items and n model answers (launch perf)
    var openScreen: String?      // -LoupeOpen queue|review|measure|linkcheck: Ask's queue, Home's Review, a Measure, Check a link
    var sortDemo = false         // -LoupeSortDemo (DEBUG, with -LoupeFixtures): passive sort with a stand-in scorer
    var inboxDemo = false        // -LoupeInboxDemo (DEBUG, with -LoupeFixtures): import a statement CSV into the Inbox
    var fakeAssistant = false    // -LoupeFakeAssistant (DEBUG): a configured writing assistant whose provider is an in-app fake (no network)
    var privacyPhotoDemo = false // -LoupePrivacyPhotoDemo (DEBUG, with -LoupeFixtures): a rendered test-card photo as an OCR'd item
    var reviewDemo = false       // -LoupeReviewDemo (DEBUG, with -LoupeFixtures): a duplicate pair in the (throwaway) inbox, Files on
    /// -LoupePermissions granted|denied (DEBUG): onboarding's permissions step answers without iOS prompts.
    var fakePermissions: PhonePermission?
    /// -LoupeStandInModel (DEBUG, with -LoupeFixtures): judgment runs and Run now score with the sort demo's
    /// deterministic stand-in (no model files), so a judgment written in a UI test can run.
    var standInModel = false
    /// -LoupeScenarioHome <name> (DEBUG, with -LoupeFixtures): a fixture home that survives a relaunch (`ScenarioHome`).
    var scenarioHome: String?

    static let current: LaunchOptions = {
        var o = LaunchOptions()
        #if DEBUG
        let args = ProcessInfo.processInfo.arguments
        o.fixtureMode = args.contains("-LoupeFixtures")
        o.autoSearch = args.contains("-LoupeAutoSearch")
        o.ephemeralKey = args.contains("-LoupeEphemeralKeychain") || o.fixtureMode
        if let i = args.firstIndex(of: "-LoupeTab"), i + 1 < args.count, let place = LaunchPlace.from(args[i + 1]) {
            o.launchPlace = place
        }
        o.skipOnboarding = args.contains("-LoupeSkipOnboarding")
        if let i = args.firstIndex(of: "-LoupeGame"), i + 1 < args.count { o.game = GameMode(rawValue: args[i + 1]) }
        o.noModel = args.contains("-LoupeNoModel")
        if let i = args.firstIndex(of: "-LoupeModelState"), i + 1 < args.count {
            o.modelState = ModelReadiness.State(launchValue: args[i + 1])
            o.modelInstalledStandIn = args[i + 1] == "installed"
        }
        if o.noModel { o.modelState = .missing }
        if let i = args.firstIndex(of: "-LoupeSeed"), i + 1 < args.count, let n = Int64(args[i + 1]) { o.gameSeed = n }
        if let i = args.firstIndex(of: "-LoupeJudgmentDemo"), i + 1 < args.count { o.judgmentDemo = args[i + 1] }
        o.openLibrary = args.contains("-LoupeLibrary")
        o.queueDemo = args.contains("-LoupeQueueDemo") && o.fixtureMode
        o.mainWatchdog = args.contains("-LoupeMainWatchdog")
        if o.fixtureMode, let i = args.firstIndex(of: "-LoupeBigLedger"), i + 1 < args.count, let n = Int(args[i + 1]) { o.bigLedger = n }
        o.sortDemo = args.contains("-LoupeSortDemo") && o.fixtureMode
        o.reviewDemo = args.contains("-LoupeReviewDemo") && o.fixtureMode
        o.privacyPhotoDemo = args.contains("-LoupePrivacyPhotoDemo") && o.fixtureMode
        o.fakeAssistant = args.contains("-LoupeFakeAssistant")
        o.inboxDemo = args.contains("-LoupeInboxDemo") && o.fixtureMode
        if let i = args.firstIndex(of: "-LoupeOpen"), i + 1 < args.count { o.openScreen = args[i + 1] }
        o.standInModel = args.contains("-LoupeStandInModel") && o.fixtureMode
        if o.fixtureMode, let i = args.firstIndex(of: "-LoupeScenarioHome"), i + 1 < args.count { o.scenarioHome = args[i + 1] }
        if let i = args.firstIndex(of: "-LoupePermissions"), i + 1 < args.count {
            o.fakePermissions = args[i + 1] == "denied" ? .denied : .granted
        }
        #endif
        return o
    }()
}
