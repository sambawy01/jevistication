import SwiftUI

struct RootView: View {
    let initialPlace: LaunchPlace
    @StateObject private var router = AppRouter()
    /// Browsing protection's Spotted log: its unseen entries badge Home (where the alerts are).
    @ObservedObject private var protection = ProtectionStore.shared
    /// The Unsure count badges Ask (spec D6).
    @ObservedObject private var judgments = JudgmentsService.shared
    @StateObject private var launcher = GameLauncher()
    @ObservedObject private var sources = SourcesService.shared
    @State private var showOnboarding = false
    @State private var watchAfterOnboarding = false
    /// What shows before the places (`LaunchFlow`): Get the Loupe Decision Model while the model is not ready
    /// (every launch until it is installed), then the permissions and protection steps once each. Decided from
    /// the model's state at launch, which `ModelReadiness` has right when it is created (the relaunch bug of
    /// 2026-09-26 was this reading "missing" for a model that was installed).
    @State private var step = LaunchFlow.first(ready: ModelReadiness.shared.isReady, launch: .current, record: OnboardingRecord())
    @State private var started = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    /// The Get Laya step shows at launch when the model is not ready, unless a test skips
    /// onboarding or the launch opens the game directly.
    static func getLayaAtLaunch(ready: Bool, launch: LaunchOptions) -> Bool {
        LaunchFlow.first(ready: ready, launch: launch, record: OnboardingRecord()) == .getModel
    }

    var body: some View {
        Group {
            switch step {
            case .getModel:
                GetLayaView(context: .onboarding) { leave(.getModel) }
                    .transition(.opacity)
            case .permissions:
                PermissionsStepView(model: PermissionsStepModel(asker: Self.permissionAsker(sources),
                                                                sourceOff: { [sources] s in !sources.isPhoneEnabled(s) },
                                                                onAnswered: { [sources] in sources.permissionsChanged() })) {
                    leave(.permissions)
                }
                .transition(.opacity)
            case .protect:
                ProtectStepView { leave(.protect) }
                    .transition(.opacity)
            case .tabs:
                places
            }
        }
        .animation(Motion.reduced(reduceMotion) ? nil : .easeOut(duration: 0.25), value: step)
        .onAppear(perform: start)
        // "Delete all my Loupe data" (Me): back to the start, as a fresh install.
        .onReceive(NotificationCenter.default.publisher(for: .loupeDataErased)) { _ in restartAfterErase() }
        .sheet(isPresented: $showOnboarding, onDismiss: {
            // Open the game only once the sheet is gone: two presentations cannot overlap.
            if watchAfterOnboarding { watchAfterOnboarding = false; launcher.open(.watch) }
        }) {
            OnboardingView(onWatch: {
                OnboardingRecord().introSeen = true
                watchAfterOnboarding = true
                showOnboarding = false
            }, onSkip: {
                OnboardingRecord().introSeen = true
                showOnboarding = false
            })
        }
        // "Open in Loupe" from the share sheet or Files: a preset pack goes to Ask's preview (packs need no model, so
        // this leaves the onboarding steps for the places; the undone ones come back on the next launch).
        .onOpenURL { url in
            guard url.isFileURL else { return }
            step = .tabs
            router.openAsk(.mine)
            PacksService.shared.open(url)
        }
    }

    /// Leaves an onboarding step: records it as done (Get the model is not recorded: it comes back until the
    /// model is here), moves on, and once at the places shows the one-time intro.
    private func leave(_ current: LaunchStep) {
        let record = OnboardingRecord()
        switch current {
        case .permissions: record.permissionsDone = true
        case .protect: record.protectDone = true
        case .getModel, .tabs: break
        }
        step = LaunchFlow.after(current, record: record)
        // The intro is a sheet on the places: it is presented once they are on screen (see `places`' task), since a
        // sheet asked for in the same update that creates its host is dropped.
    }

    /// iOS's prompts, or in DEBUG with `-LoupePermissions granted|denied` a stand-in that answers without them.
    static func permissionAsker(_ sources: SourcesService) -> PermissionAsking {
        #if DEBUG
        if let answer = LaunchOptions.current.fakePermissions { return FakePermissionAsker(answer: answer) }
        #endif
        return SystemPermissionAsker(deps: sources.deps)
    }

    /// After "Delete all my Loupe data": the onboarding steps again (Get the model first when it went too), Home with
    /// every stack at its first screen, and the sources started again from their fresh-install defaults.
    private func restartAfterErase() {
        showOnboarding = false
        router.reset()
        sources.start()
        // The Delete sheet is still up (on Me) when the erase finishes: let it go before the places are swapped for
        // the onboarding steps. Torn down while presenting, it left UIKit's presentation stale, so the one-time game
        // intro could not present after the steps and came up at the next launch instead (scenario test 2026-09-27).
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 700_000_000)
            step = LaunchFlow.first(ready: ModelReadiness.shared.isReady, launch: .current, record: OnboardingRecord())
        }
    }

    private func start() {
        guard !started else { return }
        started = true
        router.go(initialPlace)
        sources.start()
        #if DEBUG
        DeviceDiag.run(sources)
        #endif
        let launch = LaunchOptions.current
        launcher.seed = launch.gameSeed
        if let game = launch.game { launcher.open(game) }
    }

    /// A tab tap goes through the router, so tapping the place already showing returns to its first screen.
    private var selection: Binding<Place> {
        Binding(get: { router.place }, set: { router.select($0) })
    }

    private var places: some View {
        TabView(selection: selection) {
            NowView()
                .tabItem { Label(Place.home.title, systemImage: Place.home.symbol) }
                .badge(protection.unseenCount)
                .tag(Place.home)
                .environment(\.mascotTabSelected, router.place == .home)
            JudgmentsView(service: JudgmentsService.shared)
                .tabItem { Label(Place.ask.title, systemImage: Place.ask.symbol) }
                .badge(judgments.needsYou ?? 0)
                .tag(Place.ask)
                .environment(\.mascotTabSelected, router.place == .ask)
            MeView()
                .tabItem { Label(Place.me.title, systemImage: Place.me.symbol) }
                .tag(Place.me)
                .environment(\.mascotTabSelected, router.place == .me)
        }
        // Jobs running off-screen, and the model-load banner (the live run views are in place on each screen).
        .overlay(alignment: .bottom) { ActivityDock() }
        #if DEBUG
        .overlay(alignment: .topLeading) {
            if LaunchOptions.current.mainWatchdog { MainThreadWatchdogLabel() }
        }
        #endif
        .task {
            // Arriving at the places from the onboarding steps: the one-time intro follows, once the step's fade
            // has finished (a sheet asked for mid-transition, or in the update that creates its host, is dropped).
            guard LaunchOptions.current.game == nil,
                  LaunchFlow.showsIntro(launch: .current, record: OnboardingRecord()) else { return }
            try? await Task.sleep(nanoseconds: 450_000_000)
            if !showOnboarding { showOnboarding = true }
        }
        .environmentObject(launcher)
        .environmentObject(router)
        .fullScreenCover(item: $launcher.mode) { mode in
            GameView(mode: mode, seed: launcher.seed)
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

#if DEBUG
/// `-LoupeMainWatchdog` (DEBUG): the longest the main thread has been unable to run a block since launch,
/// measured from a background timer every 50 ms, shown in a tiny label a UI test reads
/// (`debug.mainStall`, in milliseconds). The large-ledger launch test holds it under 500 ms (2026-09-28).
final class MainThreadWatchdog: ObservableObject, @unchecked Sendable {
    static let shared = MainThreadWatchdog()
    @Published private(set) var shownMillis = 0   // written on the main queue only
    private let lock = NSLock()
    private var worst = 0.0
    private var pending: Date?
    private var timer: DispatchSourceTimer?

    func start() {
        guard timer == nil else { return }
        let t = DispatchSource.makeTimerSource(queue: DispatchQueue(label: "com.loupe-ai.ios.main-watchdog"))
        t.schedule(deadline: .now(), repeating: .milliseconds(50))
        t.setEventHandler { [self] in
            lock.lock()
            // A ping still waiting counts as a stall already, however long it waits.
            if let p = pending { worst = max(worst, Date().timeIntervalSince(p)); lock.unlock(); return }
            let sent = Date()
            pending = sent
            lock.unlock()
            DispatchQueue.main.async { [self] in
                lock.lock()
                worst = max(worst, Date().timeIntervalSince(sent))
                pending = nil
                let ms = Int(worst * 1000)
                lock.unlock()
                if ms != shownMillis { shownMillis = ms }
            }
        }
        t.resume()
        timer = t
    }
}

private struct MainThreadWatchdogLabel: View {
    @ObservedObject private var dog = MainThreadWatchdog.shared
    var body: some View {
        Text("\(dog.shownMillis)")
            .font(.system(size: 6)).opacity(0.02)
            .accessibilityIdentifier("debug.mainStall")
            .onAppear { dog.start() }
    }
}
#endif
