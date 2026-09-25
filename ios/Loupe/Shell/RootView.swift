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
        // "Open in Loupe" from the share sheet or Files: a preset pack goes to the Judgments preview.
        .onOpenURL { url in
            guard url.isFileURL else { return }
            selection = .judgments
            PacksService.shared.open(url)
        }
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
            #if DEBUG
            DeviceDiag.run(sources)
            #endif
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
            .neonGround()
            .navigationTitle(title)
        }
    }
}
