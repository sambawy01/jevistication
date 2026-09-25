import SwiftUI

enum AppTab: String, CaseIterable {
    case now, judgments, web, sources, me
}

struct RootView: View {
    @State var initialTab: AppTab
    @State private var selection: AppTab = .now
    @StateObject private var launcher = GameLauncher()
    @ObservedObject private var sources = SourcesService.shared
    @AppStorage("onboarding.seen") private var onboardingSeen = false
    @State private var showOnboarding = false
    @State private var watchAfterOnboarding = false
    /// Get Laya, before the tabs: shown at launch while the model is not ready (every launch until
    /// it is installed); "Later" or "Start using Loupe" leaves it for this launch.
    @State private var showGetLaya = RootView.getLayaAtLaunch(ready: ModelReadiness.shared.isReady, launch: .current)
    @State private var started = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    /// The Get Laya step shows at launch when the model is not ready, unless a test skips
    /// onboarding or the launch opens the game directly.
    static func getLayaAtLaunch(ready: Bool, launch: LaunchOptions) -> Bool {
        !ready && !launch.skipOnboarding && launch.game == nil
    }

    var body: some View {
        Group {
            if showGetLaya {
                GetLayaView(context: .onboarding) {
                    showGetLaya = false
                    // Then the one-time intro (the game as the demo), once the tabs are up.
                    if !onboardingSeen && !LaunchOptions.current.skipOnboarding { showOnboarding = true }
                }
                .transition(.opacity)
            } else {
                tabs
            }
        }
        .animation(Motion.reduced(reduceMotion) ? nil : .easeOut(duration: 0.25), value: showGetLaya)
        .onAppear(perform: start)
        // "Open in Loupe" from the share sheet or Files: a preset pack goes to the Judgments preview
        // (packs need no model, so this leaves Get Laya for the tabs).
        .onOpenURL { url in
            guard url.isFileURL else { return }
            showGetLaya = false
            selection = .judgments
            PacksService.shared.open(url)
        }
    }

    private func start() {
        guard !started else { return }
        started = true
        selection = initialTab
        sources.start()
        #if DEBUG
        DeviceDiag.run(sources)
        #endif
        let launch = LaunchOptions.current
        launcher.seed = launch.gameSeed
        if let game = launch.game {
            launcher.open(game)
        } else if !showGetLaya && !onboardingSeen && !launch.skipOnboarding {
            showOnboarding = true
        }
    }

    private var tabs: some View {
        TabView(selection: $selection) {
            NowView()
                .tabItem { Label("Now", systemImage: "dot.radiowaves.left.and.right") }
                .tag(AppTab.now)
                .environment(\.mascotTabSelected, selection == .now)
            JudgmentsView(service: JudgmentsService.shared)
                .tabItem { Label("Judgments", systemImage: "checklist") }
                .tag(AppTab.judgments)
                .environment(\.mascotTabSelected, selection == .judgments)
            WebTabView()
                .tabItem { Label("Web", systemImage: "globe.americas.fill") }
                .tag(AppTab.web)
                .environment(\.mascotTabSelected, selection == .web)
            SourcesView(sources: sources)
                .tabItem { Label("Sources", systemImage: "externaldrive.fill.badge.checkmark") }
                .tag(AppTab.sources)
                .environment(\.mascotTabSelected, selection == .sources)
            MeView()
                .tabItem { Label("Me", systemImage: "person.crop.circle.fill") }
                .tag(AppTab.me)
                .environment(\.mascotTabSelected, selection == .me)
        }
        // Jobs running off-screen, and the model-load banner (the live run views are in place on each screen).
        .overlay(alignment: .bottom) { ActivityDock() }
        .environmentObject(launcher)
        .fullScreenCover(item: $launcher.mode) { mode in
            GameView(mode: mode, seed: launcher.seed)
        }
        .sheet(isPresented: $showOnboarding, onDismiss: {
            // Open the game only once the sheet is gone: two presentations cannot overlap.
            if watchAfterOnboarding { watchAfterOnboarding = false; launcher.open(.watch) }
        }) {
            OnboardingView(onWatch: {
                onboardingSeen = true
                watchAfterOnboarding = true
                showOnboarding = false
            }, onSkip: {
                onboardingSeen = true
                showOnboarding = false
            })
        }
    }
}

struct PlaceholderTab: View {
    let title: String
    let message: String
    let symbol: String

    var body: some View {
        NavigationStack {
            ScrollView {
                HonestEmptyState(title: "Nothing here yet", message: message, symbol: symbol)
                    .padding(.top, 24)
            }
            .neonGround()
            .navigationTitle(title)
        }
    }
}
