import SwiftUI

/// The five tabs (owner decision 2026-09-26): Now · Guard · Judgments · Sources · Me. The Web tab's template library
/// moved into Judgments as "Web questions"; "web" still opens it (`AppTab.route`).
enum AppTab: String, CaseIterable {
    case now
    case guardTab = "guard"
    case judgments, sources, me

    /// Where a tab name (the DEBUG `-LoupeTab` launch argument, a deep link) goes. The old "web" tab is Judgments →
    /// Web questions.
    static func route(_ name: String) -> (tab: AppTab, judgments: JudgmentsView.Section?)? {
        if name == "web" { return (.judgments, .web) }
        return AppTab(rawValue: name).map { ($0, nil) }
    }
}

/// Switches tabs from inside a tab ("See all in Guard" on Now, "Turn on Mail" on Guard) and asks Judgments for a
/// section (Web questions). Owned by RootView, in the environment of every tab.
@MainActor
final class AppRouter: ObservableObject {
    @Published var tab: AppTab
    /// A section Judgments should show; it clears it once shown.
    @Published var judgmentsSection: JudgmentsView.Section?
    /// A browsing-protection screen Guard should push (Now's "Loupe spotted …" card); Guard clears it once pushed.
    @Published var guardPush: ProtectionRoute?

    init(tab: AppTab = .now, judgmentsSection: JudgmentsView.Section? = nil) {
        self.tab = tab
        self.judgmentsSection = judgmentsSection
    }

    /// Guard → Protection → Spotted.
    func openSpotted() {
        guardPush = .spotted
        tab = .guardTab
    }

    func open(_ tab: AppTab, judgments section: JudgmentsView.Section? = nil) {
        if let section { judgmentsSection = section }
        self.tab = tab
    }
}

struct RootView: View {
    @State var initialTab: AppTab
    var initialSection: JudgmentsView.Section? = nil
    @StateObject private var router = AppRouter()
    /// Browsing protection's Spotted log: its unseen entries badge the Guard tab.
    @ObservedObject private var protection = ProtectionStore.shared
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
            router.open(.judgments, judgments: .mine)
            PacksService.shared.open(url)
        }
    }

    private func start() {
        guard !started else { return }
        started = true
        router.open(initialTab, judgments: initialSection)
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
        TabView(selection: $router.tab) {
            NowView()
                .tabItem { Label("Now", systemImage: "dot.radiowaves.left.and.right") }
                .tag(AppTab.now)
                .environment(\.mascotTabSelected, router.tab == .now)
            GuardView()
                .tabItem { Label("Guard", systemImage: "shield.lefthalf.filled") }
                .badge(protection.unseenCount)
                .tag(AppTab.guardTab)
                .environment(\.mascotTabSelected, router.tab == .guardTab)
            JudgmentsView(service: JudgmentsService.shared)
                .tabItem { Label("Judgments", systemImage: "checklist") }
                .tag(AppTab.judgments)
                .environment(\.mascotTabSelected, router.tab == .judgments)
            SourcesView(sources: sources)
                .tabItem { Label("Sources", systemImage: "externaldrive.fill.badge.checkmark") }
                .tag(AppTab.sources)
                .environment(\.mascotTabSelected, router.tab == .sources)
            MeView()
                .tabItem { Label("Me", systemImage: "person.crop.circle.fill") }
                .tag(AppTab.me)
                .environment(\.mascotTabSelected, router.tab == .me)
        }
        // Jobs running off-screen, and the model-load banner (the live run views are in place on each screen).
        .overlay(alignment: .bottom) { ActivityDock() }
        .environmentObject(launcher)
        .environmentObject(router)
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
