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

    var body: some View {
        TabView(selection: $selection) {
            NowView()
                .tabItem { Label("Now", systemImage: "scope") }
                .tag(AppTab.now)
            PlaceholderTab(title: "Judgments",
                           message: "Questions you write in plain language, run on everything you own. They need sources to run on.",
                           symbol: "list.bullet.rectangle")
                .tabItem { Label("Judgments", systemImage: "list.bullet.rectangle") }
                .tag(AppTab.judgments)
            WebTabView()
                .tabItem { Label("Web", systemImage: "globe") }
                .tag(AppTab.web)
            SourcesView(sources: sources)
                .tabItem { Label("Sources", systemImage: "externaldrive") }
                .tag(AppTab.sources)
            MeView()
                .tabItem { Label("Me", systemImage: "person") }
                .tag(AppTab.me)
        }
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
        .onAppear {
            selection = initialTab
            sources.start()
            let launch = LaunchOptions.current
            launcher.seed = launch.gameSeed
            if let game = launch.game {
                launcher.open(game)
            } else if !onboardingSeen && !launch.skipOnboarding {
                showOnboarding = true
            }
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
            .background(Palette.ground.ignoresSafeArea())
            .navigationTitle(title)
        }
    }
}
