import SwiftUI

@main
struct LoupeApp: App {
    /// Background-session wake-ups for the model download (Loupe/Laya/ModelDownloader.swift).
    @UIApplicationDelegateAdaptor(LoupeAppDelegate.self) private var appDelegate
    @StateObject private var web = WebModel.make(launch: LaunchOptions.current)

    init() {
        #if DEBUG
        // -LoupeResetMascot: forget the mascot choice (UI tests start from the default robot).
        if ProcessInfo.processInfo.arguments.contains("-LoupeResetMascot") {
            UserDefaults.standard.removeObject(forKey: MascotKind.storageKey)
        }
        // -LoupeResetModelConsent: forget the download consent, so a Download tap in a UI test
        // opens the consent sheet and can never start a real download.
        if ProcessInfo.processInfo.arguments.contains("-LoupeResetModelConsent") {
            UserDefaults.standard.removeObject(forKey: ModelConsent.key)
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
                RootView(initialTab: LaunchOptions.current.initialTab, initialSection: LaunchOptions.current.initialSection)
                    .environmentObject(web)
                    .tint(Palette.blue)
                    .preferredColorScheme(.dark)
            }
            #else
            RootView(initialTab: LaunchOptions.current.initialTab, initialSection: LaunchOptions.current.initialSection)
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
    var initialTab: AppTab = .now
    /// `-LoupeTab web` (the old Web tab): Judgments on Web questions.
    var initialSection: JudgmentsView.Section?
    var skipOnboarding = false   // -LoupeSkipOnboarding: never show the first-launch sheet
    var game: GameMode?          // -LoupeGame human|watch: open the game at launch
    var noModel = false          // -LoupeNoModel: Laya reads as not installed (tests own their model state)
    /// -LoupeModelState ready|missing: `ModelReadiness` reads as this instead of the files, so UI tests
    /// drive Get Laya and the locked states without the 400 MB model (-LoupeNoModel implies missing).
    var modelState: ModelReadiness.State?
    var gameSeed: Int64 = 1      // -LoupeSeed n
    var judgmentDemo: String?    // -LoupeJudgmentDemo <template id>: add it, open its results, run
    var openLibrary = false      // -LoupeLibrary: open Judgments on the Library
    var queueDemo = false        // -LoupeQueueDemo (DEBUG, with -LoupeFixtures): seed the queue with a stand-in scorer
    var openScreen: String?      // -LoupeOpen queue|measure: open Now's queue, or the first judgment's Measure
    var sortDemo = false         // -LoupeSortDemo (DEBUG, with -LoupeFixtures): passive sort with a stand-in scorer
    var inboxDemo = false        // -LoupeInboxDemo (DEBUG, with -LoupeFixtures): import a statement CSV into the Inbox
    var fakeAssistant = false    // -LoupeFakeAssistant (DEBUG): a configured writing assistant whose provider is an in-app fake (no network)
    var privacyPhotoDemo = false // -LoupePrivacyPhotoDemo (DEBUG, with -LoupeFixtures): a rendered test-card photo as an OCR'd item
    var reviewDemo = false       // -LoupeReviewDemo (DEBUG, with -LoupeFixtures): a duplicate pair in the (throwaway) inbox, Files on

    static let current: LaunchOptions = {
        var o = LaunchOptions()
        #if DEBUG
        let args = ProcessInfo.processInfo.arguments
        o.fixtureMode = args.contains("-LoupeFixtures")
        o.autoSearch = args.contains("-LoupeAutoSearch")
        o.ephemeralKey = args.contains("-LoupeEphemeralKeychain") || o.fixtureMode
        if let i = args.firstIndex(of: "-LoupeTab"), i + 1 < args.count, let route = AppTab.route(args[i + 1]) {
            o.initialTab = route.tab
            o.initialSection = route.judgments
        }
        o.skipOnboarding = args.contains("-LoupeSkipOnboarding")
        if let i = args.firstIndex(of: "-LoupeGame"), i + 1 < args.count { o.game = GameMode(rawValue: args[i + 1]) }
        o.noModel = args.contains("-LoupeNoModel")
        if let i = args.firstIndex(of: "-LoupeModelState"), i + 1 < args.count { o.modelState = ModelReadiness.State(launchValue: args[i + 1]) }
        if o.noModel { o.modelState = .missing }
        if let i = args.firstIndex(of: "-LoupeSeed"), i + 1 < args.count, let n = Int64(args[i + 1]) { o.gameSeed = n }
        if let i = args.firstIndex(of: "-LoupeJudgmentDemo"), i + 1 < args.count { o.judgmentDemo = args[i + 1] }
        o.openLibrary = args.contains("-LoupeLibrary")
        o.queueDemo = args.contains("-LoupeQueueDemo") && o.fixtureMode
        o.sortDemo = args.contains("-LoupeSortDemo") && o.fixtureMode
        o.reviewDemo = args.contains("-LoupeReviewDemo") && o.fixtureMode
        o.privacyPhotoDemo = args.contains("-LoupePrivacyPhotoDemo") && o.fixtureMode
        o.fakeAssistant = args.contains("-LoupeFakeAssistant")
        o.inboxDemo = args.contains("-LoupeInboxDemo") && o.fixtureMode
        if let i = args.firstIndex(of: "-LoupeOpen"), i + 1 < args.count { o.openScreen = args[i + 1] }
        #endif
        return o
    }()
}
