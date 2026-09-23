import SwiftUI

@main
struct LoupeApp: App {
    @StateObject private var web = WebModel.make(launch: LaunchOptions.current)

    init() {
        // BGTaskScheduler wants every handler registered before launch finishes.
        BackgroundSorter.shared.register()
        BackgroundSorter.shared.schedule()
    }

    var body: some Scene {
        WindowGroup {
            #if DEBUG
            if ProcessInfo.processInfo.arguments.contains("-LoupeMascotGallery") {
                MascotGallery()
            } else {
                RootView(initialTab: LaunchOptions.current.initialTab)
                    .environmentObject(web)
                    .tint(Palette.blue)
            }
            #else
            RootView(initialTab: LaunchOptions.current.initialTab)
                .environmentObject(web)
                .tint(Palette.blue)
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
    var skipOnboarding = false   // -LoupeSkipOnboarding: never show the first-launch sheet
    var game: GameMode?          // -LoupeGame human|watch: open the game at launch
    var gameSeed: Int64 = 1      // -LoupeSeed n
    var judgmentDemo: String?    // -LoupeJudgmentDemo <template id>: add it, open its results, run
    var openLibrary = false      // -LoupeLibrary: open Judgments on the Library
    var queueDemo = false        // -LoupeQueueDemo (DEBUG, with -LoupeFixtures): seed the queue with a stand-in scorer
    var openScreen: String?      // -LoupeOpen queue|measure: open Now's queue, or the first judgment's Measure
    var sortDemo = false         // -LoupeSortDemo (DEBUG, with -LoupeFixtures): passive sort with a stand-in scorer

    static let current: LaunchOptions = {
        var o = LaunchOptions()
        #if DEBUG
        let args = ProcessInfo.processInfo.arguments
        o.fixtureMode = args.contains("-LoupeFixtures")
        o.autoSearch = args.contains("-LoupeAutoSearch")
        o.ephemeralKey = args.contains("-LoupeEphemeralKeychain") || o.fixtureMode
        if let i = args.firstIndex(of: "-LoupeTab"), i + 1 < args.count, let t = AppTab(rawValue: args[i + 1]) {
            o.initialTab = t
        }
        o.skipOnboarding = args.contains("-LoupeSkipOnboarding")
        if let i = args.firstIndex(of: "-LoupeGame"), i + 1 < args.count { o.game = GameMode(rawValue: args[i + 1]) }
        if let i = args.firstIndex(of: "-LoupeSeed"), i + 1 < args.count, let n = Int64(args[i + 1]) { o.gameSeed = n }
        if let i = args.firstIndex(of: "-LoupeJudgmentDemo"), i + 1 < args.count { o.judgmentDemo = args[i + 1] }
        o.openLibrary = args.contains("-LoupeLibrary")
        o.queueDemo = args.contains("-LoupeQueueDemo") && o.fixtureMode
        o.sortDemo = args.contains("-LoupeSortDemo") && o.fixtureMode
        if let i = args.firstIndex(of: "-LoupeOpen"), i + 1 < args.count { o.openScreen = args[i + 1] }
        #endif
        return o
    }()
}
