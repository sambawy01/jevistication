# Loupe for iPhone, Phase 1 "Shell" (Home · Ask · Me) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the five tabs (Now · Guard · Judgments · Sources · Me) with three places (Home · Ask · Me) that host today's screens, so every current feature keeps working, and the scenario journeys, relaunch checks and button audit pass in the new navigation.

**Architecture:** A native three-item `TabView` (Home, Ask with the Unsure count as its badge, Me) driven by one `AppRouter` that owns each place's `NavigationPath`. Today's screens lose their own `NavigationStack` (Guard becomes `GuardScreen`, Sources becomes `SourcesScreen`, Mail triage becomes the one `MailScreen`) and are pushed on the place's stack, whose root registers their destinations once. Home is a new card screen built from the existing services through a pure, unit-tested `HomeModel`; Ask is today's Judgments entry under an "Ask" title; Me is regrouped into the spec's groups. The `-LoupeTab` launch argument keeps working through `LaunchPlace`, which maps every old tab name to a place and a pushed screen.

**Tech Stack:** SwiftUI (iOS 17+, Swift 5 language mode), XcodeGen (`ios/project.yml`, folder sources), XCTest (`LoupeTests`) and XCUITest (`LoupeUITests`), the LoupeKit XCFramework (Kotlin Multiplatform; unchanged by this plan).

**Spec:** `docs/superpowers/specs/2026-09-28-loupe-home-ask-me-design.md` (§3 information architecture, §8 look, §9 testing, §10 step 1 "Shell"), with the mockups `docs/superpowers/specs/2026-09-28-mockups/final-screens.html` #1 (Home), #6 (Me), #7 (Mail).

## Global Constraints

- No user-facing "Laya" anywhere (labels, buttons, copy, accessibility labels); the model is "the decision model". Identifiers may keep `laya`. The scenario `audit()` enforces it on every screen it visits.
- Model prompts unchanged: no edit to any Kotlin prompt, template, judgment wording or `DecisionEngine` input in this plan.
- `use_calibration` stays OFF by default (owner decision 2026-09-27); `MeScenarios` keeps asserting it.
- `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home` for every Gradle command.
- DerivedData on `/Volumes/Sambawy/.loupe-agent-tmp/ios-shell/DerivedData` (`-derivedDataPath` on every `xcodebuild`).
- One simulator at a time: `platform=iOS Simulator,name=iPhone 17 Pro Max`. Never run two `xcodebuild test` at once.
- Targeted tests while working (`-only-testing:` the classes the task touches); the full iOS suite plus `./gradlew check` once, in the last task.
- Commit + push after the gate is green (the owner's rule): each task commits on the working branch `ios-shell` and pushes it at once (a commit never exists only locally); `main` moves only in the last task, after the full gate is green. Commit messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Stage paths explicitly (`git add <files>`), never `git add -A`.
- Kotlin test names: no commas inside backticked names (Kotlin/Native). (This plan adds no Kotlin tests.)
- Every new or moved UI works in Arabic right-to-left (use leading/trailing and `chevron.forward`, never left/right or `chevron.right` in new code), with Dynamic Type (no fixed text heights; `.fixedSize(horizontal: false, vertical: true)` on wrapping text), targets of at least 44 × 44 pt, and a VoiceOver label on every control and combined card.
- Copy: plain words, no em-dash claims of safety ("safe", "all clear"); warnings only, as today.
- Ids kept stable where a screen is only moved (`guard.*`, `sources.*`, `mail.*`, `judgments.*`, `queue.*`, `me.*`); new ids use `home.*`, `ask.*`, `me.*`.

## Review Focus

1. **Coming back to a place's first screen.** A person who opened Protection from Home (or launched with `-LoupeTab guard`) and taps Home again expects Home's cards, not the pushed screen; switching to Me and back keeps each place's own screen. Pinned by `ShellRoutingTests.testSelectingTheSamePlaceAgainGoesBackToItsFirstScreen` (Task 2) and `HomeUITests.testHomeCardsOpenTheirScreensAndHomeComesBack` (Task 3).
2. **Delete all my Loupe data from deep inside Me.** After the erase the app must be on Home's first screen with no stale pushed screen in any place (Me had Delete open). Pinned by `ShellRoutingTests.testResetClearsEveryStackAndGoesHome` (Task 2) and the Home assertion added to `DeleteDataScenarios` (Task 7).
3. **Nothing connected.** With no source on, the Money and Documents cards must say what to connect and offer a button to What Loupe reads, not show an empty total. Pinned by `HomeModelTests.testMoneyAsksForMailWhenNothingCouldHoldCharges` and `testDocumentsAskForFilesWhenNothingCouldHoldDocuments` (Task 3).
4. **Needs attention appears only when something needs you, in the spec's order** (risky sites, mail phishing, the watchers' findings, review proposals, then privacy findings), and disappears when all are answered. Pinned by `HomeModelTests.testNeedsAttentionIsEmptyWhenNothingWaits` and `testNeedsAttentionOrder` (Task 3).
5. **Arabic right-to-left and the largest text size.** Home, Ask and Me's first screens keep every action hittable and at least 44 pt under RTL and Accessibility XL. Pinned by `ShellAccessibilityUITests` (Task 9).

## Decisions this plan makes (read before Task 2)

- **Native `TabView`, three items; the Ask item carries the badge.** The badge is `.badge(JudgmentsService.shared.needsYou)` on the Ask tab item; the queue opens from the "Needs you" card at the top of Ask (`ask.needsYou`) and from `-LoupeOpen queue`. A separately tappable badge on a large custom centre button needs a custom tab bar, which would break every `app.tabBars` query in 28 UI test files and the free VoiceOver/RTL behaviour; it is left for step 5 ("calmer look"). Trade-off: one extra tap from badge to queue in phase 1.
- **Re-tapping the selected tab returns to its first screen** through a custom selection `Binding` whose setter calls `AppRouter.select(_:)`.
- **Guard is not a place any more.** Its screen stays whole, reachable from Home's Protected card (title "Protection"); Money and Documents open Guard's Subscriptions and Expiring sections on their own screens.
- **The Unseen-Spotted count badges Home** (Home now holds the alerts; it badged Guard before).
- **Mail is one screen** (`MailScreen`): the Mail source row (connect, switch, Scan again, mailbox settings and Remove), "Found in your mail", then today's triage rows and actions. Sources lists Mail as one entry that opens it.
- **The game moves to Me → See Loupe think** (today's Play card, ids renamed `me.play.*`).

## File Structure

| File | Responsibility |
|---|---|
| Create `ios/Loupe/Shell/ShellRoutes.swift` | `Place`, `HomeRoute`, `MeRoute`, `LaunchPlace` (old tab names → places), `AppRouter` (selection and the three stacks) |
| Rewrite `ios/Loupe/Shell/RootView.swift` | The onboarding steps, then the three-place `TabView` with the badges, `ShellEffects`, the game cover |
| Create `ios/Loupe/Shell/ShellEffects.swift` | App-wide work that hung off Now: re-run watchers / privacy / mail triage on scan changes, collect Review, load judgments, DEBUG demos, `-LoupeOpen` |
| Create `ios/Loupe/Shell/HomeModel.swift` | Pure: Needs attention items, Money / Documents / Protected summaries |
| Create `ios/Loupe/Shell/HomeView.swift` | Home screen, `HomeCardLabel`, `HomeSectionTitle` |
| Create `ios/Loupe/Shell/TrackingScreens.swift` | `SubscriptionsScreen`, `ExpiringScreen` (Guard's sections on their own screens) |
| Create `ios/Loupe/Shell/MeModel.swift` | Pure: Me's row values |
| Rewrite `ios/Loupe/Shell/MeView.swift` | Me in the spec's groups; Me's stack and destinations |
| Create `ios/Loupe/Shell/AdvancedView.swift` | Me → Advanced: Model settings, online checks, mascot, diagnostics |
| Delete `ios/Loupe/Shell/NowView.swift` | Replaced by Home + ShellEffects |
| Modify `ios/Loupe/Guard/GuardView.swift` | `GuardScreen` (no stack), `GuardNotice`, `guardDestinations()` |
| Modify `ios/Loupe/Protection/ProtectionSection.swift` | `protectionDestinations()` instead of a destination inside the section |
| Modify `ios/Loupe/Sources/SourcesView.swift` | `SourcesScreen` (no stack); Mail as one entry |
| Modify `ios/Loupe/Mail/MailTriageView.swift` | `MailScreen`, `MailFound`, `MailFoundCard`, `MailEntryCard` |
| Modify `ios/Loupe/Judgments/JudgmentsView.swift` | Ask title, `AskHeader`, the router's Ask stack and section |
| Modify `ios/Loupe/Judgments/JudgmentResultsView.swift` | "Open Sources" → `router.openReads()` |
| Modify `ios/Loupe/Game/GameView.swift` | Play card ids `me.play.*` |
| Modify `ios/Loupe/App/LoupeApp.swift` | `LaunchOptions.launchPlace` |
| Tests: create `ios/LoupeTests/ShellRoutingTests.swift`, `HomeModelTests.swift`, `MailScreenTests.swift`, `MeModelTests.swift`; modify `GuardModelTests.swift` | Unit tests |
| Tests: create `ios/LoupeUITests/HomeUITests.swift`, `ShellAccessibilityUITests.swift`; modify `Scenarios/*.swift` and the other UI tests; delete `NowFindingsUITests.swift` | UI tests |
| Docs: `ios/README.md`, `docs/PREDEPLOY-CHECKLIST.md` | The new places and launch arguments |

## Setup (once, before Task 1)

- [ ] **Branch and engine**

```bash
cd /Users/bistrocloud/Documents/Loupe/jevistication
git switch main && git pull --ff-only
git switch -c ios-shell
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
./gradlew :loupe-kit:assembleLoupeKitDebugXCFramework
mkdir -p /Volumes/Sambawy/.loupe-agent-tmp/ios-shell
```

Expected: `BUILD SUCCESSFUL`.

The iOS test command used below (only the `-only-testing` part changes):

```bash
cd /Users/bistrocloud/Documents/Loupe/jevistication/ios && xcodegen generate && xcodebuild test -project Loupe.xcodeproj -scheme Loupe -destination 'platform=iOS Simulator,name=iPhone 17 Pro Max' -derivedDataPath /Volumes/Sambawy/.loupe-agent-tmp/ios-shell/DerivedData -only-testing:<Target>/<Class>
```

---

### Task 1: Guard and Sources screens without their own stacks

A pure refactor: `GuardView` and `SourcesView` keep hosting the old tabs, but their content becomes `GuardScreen` / `SourcesScreen`, which any stack can push, and the Guard and Protection destinations become modifiers registered once at a stack's root.

**Files:**
- Modify: `ios/Loupe/Guard/GuardView.swift` (struct header, body tail; append `GuardView` host and `guardDestinations()`)
- Modify: `ios/Loupe/Protection/ProtectionSection.swift:1-29`
- Modify: `ios/Loupe/Sources/SourcesView.swift:1-14, 80-86`
- Test (unchanged, must stay green): `ios/LoupeUITests/Scenarios/GuardScenarios.swift`, `ProtectionScenarios.swift`, `SourcesScenarios.swift`, `ios/LoupeUITests/GuardUITests.swift`

**Interfaces:**
- Produces: `struct GuardScreen: View { init(title: String = "Protection") }` (needs `AppRouter` in the environment); `extension View { func guardDestinations() -> some View; func protectionDestinations() -> some View }`; `struct SourcesScreen: View { init(sources: SourcesService) }` titled "What Loupe reads".

- [ ] **Step 1: Baseline: run the journeys this refactor must not break**

Run: the iOS test command with `-only-testing:LoupeUITests/GuardScenarios -only-testing:LoupeUITests/ProtectionScenarios -only-testing:LoupeUITests/SourcesScenarios -only-testing:LoupeUITests/GuardUITests`
Expected: `** TEST SUCCEEDED **` (if any fails on `main` already, stop and report it; it is not this plan's regression).

- [ ] **Step 2: Split `GuardView` into `GuardScreen`**

In `ios/Loupe/Guard/GuardView.swift` replace:

```swift
struct GuardView: View {
    @ObservedObject var watchers: WatchersService = .shared
    @ObservedObject var sources: SourcesService = .shared
    @ObservedObject private var readiness = ModelReadiness.shared
    @ObservedObject private var settings = ModelSettingsService.shared
    @EnvironmentObject private var router: AppRouter
    @State private var path = NavigationPath()

    var body: some View {
        NavigationStack(path: $path) {
            ScrollViewReader { proxy in
```

with:

```swift
struct GuardScreen: View {
    /// "Protection" when Home pushes it (Task 2 on); "Guard" while it is still a tab.
    var title = "Protection"
    @ObservedObject var watchers: WatchersService = .shared
    @ObservedObject var sources: SourcesService = .shared
    @ObservedObject private var readiness = ModelReadiness.shared
    @ObservedObject private var settings = ModelSettingsService.shared
    @EnvironmentObject private var router: AppRouter

    var body: some View {
            ScrollViewReader { proxy in
```

and replace:

```swift
            .neonGround()
            .navigationTitle("Guard")
            .navigationBarTitleDisplayMode(.inline)
            .navigationDestination(for: GuardRoute.self) { route in
                switch route {
                case .subscription(let merchant): SubscriptionDetailView(watchers: watchers, merchant: merchant)
                case .expiry(let itemId): ExpiryDetailView(watchers: watchers, itemId: itemId)
                case .finding(let key): FindingDetailView(watchers: watchers, key: key)
                case .mail: MailTriageView(mail: MailTriageService.shared)
                case .onlineChecks: OnlineChecksView(online: OnlineChecksService.shared)
                }
            }
        }
        // Now's "Loupe spotted …" card: push the Spotted list (its destination is registered by the Protection section).
        .onChange(of: router.guardPush) { _, _ in takePush() }
        .onReceive(NotificationCenter.default.publisher(for: .loupeDataErased)) { _ in path = NavigationPath() }
        // The run's live progress is drawn here (the strip), so the Activity dock leaves the watchers out on this tab.
        .onAppear { ActivityCenter.shared.show("watchers"); takePush() }
        .onDisappear { ActivityCenter.shared.hide("watchers") }
    }

    private var coverage: GuardCoverage { GuardCoverage.from(sources) }

    private func takePush() {
        guard let route = router.guardPush else { return }
        router.guardPush = nil
        path = NavigationPath()
        path.append(route)
    }
```

with:

```swift
            .neonGround()
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            // The run's live progress is drawn here (the strip), so the Activity dock leaves the watchers out here.
            .onAppear { ActivityCenter.shared.show("watchers") }
            .onDisappear { ActivityCenter.shared.hide("watchers") }
    }

    private var coverage: GuardCoverage { GuardCoverage.from(sources) }
```

Append to the end of the file:

```swift
// MARK: - Hosting

/// The Guard tab's stack until the shell swap (Task 2 deletes it): Now's "Loupe spotted …" push and the reset after
/// Delete all my Loupe data.
struct GuardView: View {
    @EnvironmentObject private var router: AppRouter
    @State private var path = NavigationPath()

    var body: some View {
        NavigationStack(path: $path) {
            GuardScreen(title: "Guard")
                .guardDestinations()
                .protectionDestinations()
        }
        .onChange(of: router.guardPush) { _, _ in takePush() }
        .onReceive(NotificationCenter.default.publisher(for: .loupeDataErased)) { _ in path = NavigationPath() }
        .onAppear { takePush() }
    }

    private func takePush() {
        guard let route = router.guardPush else { return }
        router.guardPush = nil
        path = NavigationPath()
        path.append(route)
    }
}

extension View {
    /// Where Guard's rows lead (a merchant, a document, a finding, Mail, the online checks). Registered once, at the
    /// root of the stack that shows Guard's screens.
    func guardDestinations() -> some View {
        navigationDestination(for: GuardRoute.self) { route in
            switch route {
            case .subscription(let merchant): SubscriptionDetailView(watchers: WatchersService.shared, merchant: merchant)
            case .expiry(let itemId): ExpiryDetailView(watchers: WatchersService.shared, itemId: itemId)
            case .finding(let key): FindingDetailView(watchers: WatchersService.shared, key: key)
            case .mail: MailTriageView(mail: MailTriageService.shared)
            case .onlineChecks: OnlineChecksView(online: OnlineChecksService.shared)
            }
        }
    }
}
```

- [ ] **Step 3: Move the Protection destinations out of the section**

In `ios/Loupe/Protection/ProtectionSection.swift` replace the doc line `/// button while it is off), Check a link, and the Spotted log. It registers its own navigation` and the next line `/// destinations, so it only needs a NavigationStack above it.` with:

```swift
/// button while it is off), Check a link, and the Spotted log. The stack that shows it registers
/// `protectionDestinations()` once at its root (Home, the old Guard tab).
```

Delete from `ProtectionSectionContent.body`:

```swift
        .navigationDestination(for: ProtectionRoute.self) { route in
            switch route {
            case .checkLink: LinkCheckView(store: store)
            case .spotted: SpottedListView(store: store)
            case .spottedEntry(let id): SpottedDetailView(store: store, id: id)
            }
        }
```

Append to the end of the file:

```swift
extension View {
    /// Check a link, the Spotted list and a Spotted entry: registered once at the root of the stack that shows
    /// browsing protection (Home's quick checks and Protection screen push them).
    func protectionDestinations() -> some View {
        navigationDestination(for: ProtectionRoute.self) { route in
            switch route {
            case .checkLink: LinkCheckView(store: ProtectionStore.shared)
            case .spotted: SpottedListView(store: ProtectionStore.shared)
            case .spottedEntry(let id): SpottedDetailView(store: ProtectionStore.shared, id: id)
            }
        }
    }
}
```

- [ ] **Step 4: Split `SourcesView` into `SourcesScreen`**

In `ios/Loupe/Sources/SourcesView.swift` replace:

```swift
struct SourcesView: View {
    @ObservedObject var sources: SourcesService
    @Environment(\.accessibilityReduceMotion) private var systemReduceMotion

    var body: some View {
        NavigationStack {
            ScrollViewReader { proxy in
```

with:

```swift
struct SourcesView: View {
    @ObservedObject var sources: SourcesService

    var body: some View {
        NavigationStack { SourcesScreen(sources: sources) }
    }
}

/// What Loupe reads (today's Sources screen) without a stack of its own: Me pushes it from Task 2 on.
struct SourcesScreen: View {
    @ObservedObject var sources: SourcesService
    @Environment(\.accessibilityReduceMotion) private var systemReduceMotion

    var body: some View {
            ScrollViewReader { proxy in
```

and replace:

```swift
            .neonGround()
            .navigationTitle("Sources")
            .navigationBarTitleDisplayMode(.inline)
        }
        // The scans' live runs are drawn in place here, so the Activity dock leaves them out on this screen.
        .onAppear { ActivityCenter.shared.show("sources") }
        .onDisappear { ActivityCenter.shared.hide("sources") }
    }
```

with:

```swift
            .neonGround()
            .navigationTitle("What Loupe reads")
            .navigationBarTitleDisplayMode(.inline)
            // The scans' live runs are drawn in place here, so the Activity dock leaves them out on this screen.
            .onAppear { ActivityCenter.shared.show("sources") }
            .onDisappear { ActivityCenter.shared.hide("sources") }
    }
```

- [ ] **Step 5: Run the same journeys**

Run: the iOS test command with `-only-testing:LoupeUITests/GuardScenarios -only-testing:LoupeUITests/ProtectionScenarios -only-testing:LoupeUITests/SourcesScenarios -only-testing:LoupeUITests/GuardUITests`
Expected: `** TEST SUCCEEDED **`, the same tests as Step 1.

- [ ] **Step 6: Commit**

```bash
cd /Users/bistrocloud/Documents/Loupe/jevistication
git add ios/Loupe/Guard/GuardView.swift ios/Loupe/Protection/ProtectionSection.swift ios/Loupe/Sources/SourcesView.swift
git commit -m "ios: Guard and Sources screens without their own stacks (shell step 1, prep)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
git push -u origin ios-shell
```

---

### Task 2: Three places, one router, `-LoupeTab` kept

**Files:**
- Create: `ios/Loupe/Shell/ShellRoutes.swift`
- Rewrite: `ios/Loupe/Shell/RootView.swift`
- Modify: `ios/Loupe/App/LoupeApp.swift:49-58, 70-73, 107-110`
- Modify: `ios/Loupe/Shell/NowView.swift` (bound to the router; temporary until Task 3 deletes it)
- Modify: `ios/Loupe/Judgments/JudgmentsView.swift:13-21, 80, 88-104, 120-122`
- Modify: `ios/Loupe/Judgments/JudgmentResultsView.swift:343`
- Modify: `ios/Loupe/Guard/GuardView.swift` (delete the `GuardView` host; `router.open(.sources)` → `router.openReads()`)
- Modify: `ios/Loupe/Sources/SourcesView.swift` (delete the `SourcesView` host)
- Modify: `ios/Loupe/Shell/MeView.swift` (Me's stack; "What Loupe reads" rows)
- Test: create `ios/LoupeTests/ShellRoutingTests.swift`; modify `ios/LoupeTests/GuardModelTests.swift:122-130`

**Interfaces:**
- Consumes: `GuardScreen`, `SourcesScreen`, `guardDestinations()`, `protectionDestinations()` (Task 1); `JudgmentsView.Section`, `JudgmentRoute`, `ProtectionRoute`.
- Produces (exact):
  - `enum Place: String, CaseIterable { case home, ask, me; var title: String; var symbol: String }`
  - `enum HomeRoute: Hashable { case protection, review, privacy }` (Task 3 adds `subscriptions`, `expiring`)
  - `enum MeRoute: Hashable { case reads, mail }` (Task 6 adds `advanced`, `review`)
  - `struct LaunchPlace: Equatable { var place: Place; var home: HomeRoute?; var ask: JudgmentsView.Section?; var me: MeRoute?; static let start; static func from(_ name: String) -> LaunchPlace? }`
  - `@MainActor final class AppRouter: ObservableObject` with `@Published var place: Place`, `homePath`, `askPath`, `mePath: NavigationPath`, `@Published var askSection: JudgmentsView.Section?`, and `select(_:)`, `go(_:)`, `openHome(_:)`, `openSpotted()`, `openReads()`, `openMail()`, `openAsk(_:queue:)`, `reset()`.
  - `LaunchOptions.launchPlace: LaunchPlace`; `RootView(initialPlace: LaunchPlace)`.

- [ ] **Step 1: Write the failing tests**

Create `ios/LoupeTests/ShellRoutingTests.swift`:

```swift
import SwiftUI
import XCTest
@testable import Loupe

/// The three places (spec 2026-09-28 §3): every old `-LoupeTab` name still lands somewhere sensible, and the router
/// keeps one stack per place.
@MainActor
final class ShellRoutingTests: XCTestCase {
    func testThreePlacesInOrder() {
        XCTAssertEqual(Place.allCases.map(\.title), ["Home", "Ask", "Me"])
    }

    func testEveryOldTabNameHasAPlace() {
        XCTAssertEqual(LaunchPlace.from("now"), LaunchPlace(place: .home))
        XCTAssertEqual(LaunchPlace.from("home"), LaunchPlace(place: .home))
        XCTAssertEqual(LaunchPlace.from("guard"), LaunchPlace(place: .home, home: .protection))
        XCTAssertEqual(LaunchPlace.from("judgments"), LaunchPlace(place: .ask))
        XCTAssertEqual(LaunchPlace.from("ask"), LaunchPlace(place: .ask))
        XCTAssertEqual(LaunchPlace.from("web"), LaunchPlace(place: .ask, ask: .web))
        XCTAssertEqual(LaunchPlace.from("sources"), LaunchPlace(place: .me, me: .reads))
        XCTAssertEqual(LaunchPlace.from("mail"), LaunchPlace(place: .me, me: .mail))
        XCTAssertEqual(LaunchPlace.from("me"), LaunchPlace(place: .me))
        XCTAssertNil(LaunchPlace.from("nowhere"))
    }

    func testGoPushesTheOldTabsScreen() {
        let router = AppRouter()
        router.go(LaunchPlace.from("guard")!)
        XCTAssertEqual(router.place, .home)
        XCTAssertEqual(router.homePath.count, 1)
        router.go(LaunchPlace.from("sources")!)
        XCTAssertEqual(router.place, .me)
        XCTAssertEqual(router.mePath.count, 1)
        XCTAssertEqual(router.homePath.count, 0, "go starts every place from its first screen")
        router.go(LaunchPlace.from("web")!)
        XCTAssertEqual(router.place, .ask)
        XCTAssertEqual(router.askSection, .web)
    }

    func testSelectingTheSamePlaceAgainGoesBackToItsFirstScreen() {
        let router = AppRouter()
        router.openHome(.protection)
        router.select(.me)
        XCTAssertEqual(router.homePath.count, 1, "another place keeps Home's screen")
        router.select(.home)
        XCTAssertEqual(router.homePath.count, 1, "coming back shows the screen that was open")
        router.select(.home)
        XCTAssertEqual(router.homePath.count, 0, "a second tap goes back to Home's first screen")
        router.openReads()
        router.select(.me)
        XCTAssertEqual(router.mePath.count, 0)
    }

    func testOpenAskWithTheQueuePushesTheQueue() {
        let router = AppRouter()
        router.openAsk(.mine, queue: true)
        XCTAssertEqual(router.place, .ask)
        XCTAssertEqual(router.askSection, .mine)
        XCTAssertEqual(router.askPath.count, 1)
    }

    func testSpottedAndMailOpenInTheirPlaces() {
        let router = AppRouter()
        router.openSpotted()
        XCTAssertEqual(router.place, .home)
        XCTAssertEqual(router.homePath.count, 1)
        router.openMail()
        XCTAssertEqual(router.place, .me)
        XCTAssertEqual(router.mePath.count, 1)
    }

    func testResetClearsEveryStackAndGoesHome() {
        let router = AppRouter()
        router.openHome(.review)
        router.openAsk(.web, queue: true)
        router.openReads()
        router.reset()
        XCTAssertEqual(router.place, .home)
        XCTAssertEqual(router.homePath.count + router.askPath.count + router.mePath.count, 0)
        XCTAssertNil(router.askSection)
    }
}
```

In `ios/LoupeTests/GuardModelTests.swift` delete the whole function `testTheOldWebTabOpensJudgmentsWebQuestions()` (lines 122-130; its checks now live in `testEveryOldTabNameHasAPlace`).

- [ ] **Step 2: Run to verify it fails**

Run: the iOS test command with `-only-testing:LoupeTests/ShellRoutingTests`
Expected: build FAILS with `cannot find 'Place' in scope` / `cannot find 'LaunchPlace' in scope`.

- [ ] **Step 3: Write the routes and the router**

Create `ios/Loupe/Shell/ShellRoutes.swift`:

```swift
import SwiftUI

/// The three places (spec 2026-09-28 §3, D1): Home, the Ask button and Me.
enum Place: String, CaseIterable {
    case home, ask, me

    var title: String {
        switch self {
        case .home: return "Home"
        case .ask: return "Ask"
        case .me: return "Me"
        }
    }

    var symbol: String {
        switch self {
        case .home: return "house.fill"
        case .ask: return "questionmark.bubble.fill"
        case .me: return "person.crop.circle.fill"
        }
    }
}

/// Screens pushed on Home's stack.
enum HomeRoute: Hashable {
    /// Today's Guard screen whole (the watchers, Run now, Protection), behind the Protected card.
    case protection
    case review
    case privacy
}

/// Screens pushed on Me's stack.
enum MeRoute: Hashable {
    /// What Loupe reads (today's Sources screen).
    case reads
    /// Mail as one place (spec D10).
    case mail
}

/// Where a launch opens: a place, and at most one screen pushed on it. `-LoupeTab` (DEBUG) takes today's and the old
/// tab names, so every existing test and script keeps working.
struct LaunchPlace: Equatable {
    var place: Place
    var home: HomeRoute? = nil
    var ask: JudgmentsView.Section? = nil
    var me: MeRoute? = nil

    static let start = LaunchPlace(place: .home)

    static func from(_ name: String) -> LaunchPlace? {
        switch name {
        case "home", "now": return LaunchPlace(place: .home)
        case "guard", "protection": return LaunchPlace(place: .home, home: .protection)
        case "ask", "judgments": return LaunchPlace(place: .ask)
        case "web": return LaunchPlace(place: .ask, ask: .web)
        case "sources", "reads": return LaunchPlace(place: .me, me: .reads)
        case "mail": return LaunchPlace(place: .me, me: .mail)
        case "me": return LaunchPlace(place: .me)
        default: return nil
        }
    }
}

/// Selects a place and pushes screens on the places' stacks (one `NavigationPath` each). Owned by RootView and in
/// every place's environment, so a card on Home can open Me → What Loupe reads, and a pack opened from Files can land
/// on Ask.
@MainActor
final class AppRouter: ObservableObject {
    @Published var place: Place
    @Published var homePath = NavigationPath()
    @Published var askPath = NavigationPath()
    @Published var mePath = NavigationPath()
    /// A section Ask should show (Web questions, My judgments); Ask clears it once shown.
    @Published var askSection: JudgmentsView.Section?

    init(place: Place = .home) {
        self.place = place
    }

    /// A tab tapped. The place already showing goes back to its first screen, as iOS tab bars do.
    func select(_ next: Place) {
        if next == place {
            switch next {
            case .home: homePath = NavigationPath()
            case .ask: askPath = NavigationPath()
            case .me: mePath = NavigationPath()
            }
        }
        place = next
    }

    /// Every place from its first screen, then `launch`'s place with its screen pushed.
    func go(_ launch: LaunchPlace) {
        homePath = NavigationPath()
        askPath = NavigationPath()
        mePath = NavigationPath()
        if let r = launch.home { homePath.append(r) }
        if let r = launch.me { mePath.append(r) }
        askSection = launch.ask
        place = launch.place
    }

    func openHome(_ route: HomeRoute) {
        homePath = NavigationPath()
        homePath.append(route)
        place = .home
    }

    /// Home → Spotted (a "Loupe spotted …" card, the Spotted row).
    func openSpotted() {
        homePath = NavigationPath()
        homePath.append(ProtectionRoute.spotted)
        place = .home
    }

    /// Me → What Loupe reads (every "Open Sources" and "Turn on a source").
    func openReads() {
        mePath = NavigationPath()
        mePath.append(MeRoute.reads)
        place = .me
    }

    /// Me → Mail.
    func openMail() {
        mePath = NavigationPath()
        mePath.append(MeRoute.mail)
        place = .me
    }

    /// Ask, on a section, optionally with the Unsure queue pushed (the badge's door, `-LoupeOpen queue`).
    func openAsk(_ section: JudgmentsView.Section? = nil, queue: Bool = false) {
        askPath = NavigationPath()
        if let section { askSection = section }
        if queue { askPath.append(JudgmentRoute.queue) }
        place = .ask
    }

    /// After "Delete all my Loupe data": Home, every stack back to its first screen.
    func reset() {
        go(.start)
    }
}
```

- [ ] **Step 4: Rewrite `RootView.swift` around the three places**

Replace the whole of `ios/Loupe/Shell/RootView.swift` with:

```swift
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
                .badge(judgments.needsYou)
                .tag(Place.ask)
                .environment(\.mascotTabSelected, router.place == .ask)
            MeView()
                .tabItem { Label(Place.me.title, systemImage: Place.me.symbol) }
                .tag(Place.me)
                .environment(\.mascotTabSelected, router.place == .me)
        }
        // Jobs running off-screen, and the model-load banner (the live run views are in place on each screen).
        .overlay(alignment: .bottom) { ActivityDock() }
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
```

- [ ] **Step 5: `-LoupeTab` through `LaunchPlace`**

In `ios/Loupe/App/LoupeApp.swift` replace both occurrences of

```swift
RootView(initialTab: LaunchOptions.current.initialTab, initialSection: LaunchOptions.current.initialSection)
```

with

```swift
RootView(initialPlace: LaunchOptions.current.launchPlace)
```

replace

```swift
    var initialTab: AppTab = .now
    /// `-LoupeTab web` (the old Web tab): Judgments on Web questions.
    var initialSection: JudgmentsView.Section?
```

with

```swift
    /// `-LoupeTab <name>`: a place and a screen on it (`LaunchPlace.from`: home|now, guard|protection, ask|judgments,
    /// web, sources|reads, mail, me, advanced).
    var launchPlace: LaunchPlace = .start
```

and replace

```swift
        if let i = args.firstIndex(of: "-LoupeTab"), i + 1 < args.count, let route = AppTab.route(args[i + 1]) {
            o.initialTab = route.tab
            o.initialSection = route.judgments
        }
```

with

```swift
        if let i = args.firstIndex(of: "-LoupeTab"), i + 1 < args.count, let place = LaunchPlace.from(args[i + 1]) {
            o.launchPlace = place
        }
```

Also replace the comment line `    var openScreen: String?      // -LoupeOpen queue|measure: open Now's queue, or the first judgment's Measure` with `    var openScreen: String?      // -LoupeOpen queue|review|measure|linkcheck: Ask's queue, Home's Review, a Measure, Check a link`.

- [ ] **Step 6: Point the old screens at the router**

`ios/Loupe/Guard/GuardView.swift`: delete the whole `struct GuardView` host appended in Task 1 (keep `guardDestinations()`), and replace every `router.open(.sources)` (five places) with `router.openReads()`.

`ios/Loupe/Sources/SourcesView.swift`: delete

```swift
struct SourcesView: View {
    @ObservedObject var sources: SourcesService

    var body: some View {
        NavigationStack { SourcesScreen(sources: sources) }
    }
}
```

`ios/Loupe/Judgments/JudgmentResultsView.swift:343`: replace `{ router.open(.sources) }` with `{ router.openReads() }`.

`ios/Loupe/Judgments/JudgmentsView.swift`: delete `    @State private var path = NavigationPath()`; replace `NavigationStack(path: $path) {` with `NavigationStack(path: $router.askPath) {`; replace `path.append(JudgmentRoute.results(id))` with `router.askPath.append(JudgmentRoute.results(id))`; replace `            path = NavigationPath()` with `            router.askPath = NavigationPath()`; replace `if let route = demoRoute { path.append(route) }` with `if let route = demoRoute { router.askPath.append(route) }`; replace `path.append(JudgmentRoute.measure(j.id))` with `router.askPath.append(JudgmentRoute.measure(j.id))`; replace `.onChange(of: router.judgmentsSection) { _, _ in takeRequestedSection() }` with `.onChange(of: router.askSection) { _, _ in takeRequestedSection() }`; and replace

```swift
        guard let requested = router.judgmentsSection else { return }
        section = requested
        router.judgmentsSection = nil
```

with

```swift
        guard let requested = router.askSection else { return }
        section = requested
        router.askSection = nil
```

`ios/Loupe/Shell/NowView.swift` (Home until Task 3): replace `        NavigationStack {` with `        NavigationStack(path: $router.homePath) {`; after `                .sheet(item: $openItem) { ItemTextView(item: $0) }` insert

```swift
                .navigationDestination(for: HomeRoute.self) { route in
                    switch route {
                    case .protection: GuardScreen()
                    case .review: ReviewView(review: review)
                    case .privacy: PrivacyView(privacy: privacy)
                    }
                }
                .guardDestinations()
                .protectionDestinations()
```

replace both `{ router.open(.sources) }` with `{ router.openReads() }`, and replace `Button { router.open(.guardTab) } label: { GuardDoorCard(summary: summary) }` with `Button { router.openHome(.protection) } label: { GuardDoorCard(summary: summary) }`.

`ios/Loupe/Shell/MeView.swift`: add `    @EnvironmentObject private var router: AppRouter` and `    @ObservedObject private var sources = SourcesService.shared` under `@EnvironmentObject private var launcher: GameLauncher`; replace `        NavigationStack {` with `        NavigationStack(path: $router.mePath) {`; insert as the first child of `List {`:

```swift
                NeonSection("What Loupe reads") {
                    NavigationLink(value: MeRoute.reads) { row("Sources", "\(sources.enabledCount) on") }
                        .accessibilityIdentifier("me.reads")
                    NavigationLink(value: MeRoute.mail) { row("Mail", sources.isPhoneEnabled(.mail) ? "on" : "off") }
                        .accessibilityIdentifier("me.mail")
                }
```

and after `            .sheet(item: $shared) { file in ShareSheet(items: [file.url]) }` insert

```swift
            .navigationDestination(for: MeRoute.self) { route in
                switch route {
                case .reads: SourcesScreen(sources: SourcesService.shared)
                case .mail: MailTriageView(mail: MailTriageService.shared)
                }
            }
```

- [ ] **Step 7: Run the unit tests and two journeys through the old names**

Run: the iOS test command with `-only-testing:LoupeTests/ShellRoutingTests -only-testing:LoupeTests/GuardModelTests -only-testing:LoupeUITests/GuardUITests/testTheProtectionActionsAreAtTheTop -only-testing:LoupeUITests/SourcesUITests/testSourcesListsEveryRowAndSampleStillWorks`
Expected: `** TEST SUCCEEDED **` (`-LoupeTab guard` lands on Home with Protection pushed; `-LoupeTab sources` on Me with What Loupe reads pushed).

- [ ] **Step 8: Commit**

```bash
cd /Users/bistrocloud/Documents/Loupe/jevistication
git add ios/Loupe/Shell/ShellRoutes.swift ios/Loupe/Shell/RootView.swift ios/Loupe/App/LoupeApp.swift ios/Loupe/Shell/NowView.swift ios/Loupe/Judgments/JudgmentsView.swift ios/Loupe/Judgments/JudgmentResultsView.swift ios/Loupe/Guard/GuardView.swift ios/Loupe/Sources/SourcesView.swift ios/Loupe/Shell/MeView.swift ios/LoupeTests/ShellRoutingTests.swift ios/LoupeTests/GuardModelTests.swift
git commit -m "ios: three places (Home, Ask, Me) with one router; -LoupeTab mapped to places

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
git push
```

---

### Task 3: Home in the spec's card order

**Files:**
- Create: `ios/Loupe/Shell/HomeModel.swift`, `ios/Loupe/Shell/HomeView.swift`, `ios/Loupe/Shell/TrackingScreens.swift`, `ios/Loupe/Shell/ShellEffects.swift`
- Modify: `ios/Loupe/Shell/ShellRoutes.swift` (`HomeRoute` gains `subscriptions`, `expiring`)
- Modify: `ios/Loupe/Guard/GuardView.swift` (extract `GuardNotice`)
- Modify: `ios/Loupe/Shell/RootView.swift` (Home + `ShellEffects`)
- Delete: `ios/Loupe/Shell/NowView.swift`, `ios/LoupeUITests/NowFindingsUITests.swift`
- Test: create `ios/LoupeTests/HomeModelTests.swift`, `ios/LoupeUITests/HomeUITests.swift`

**Interfaces:**
- Consumes: `AppRouter` (`homePath`, `openReads()`, `openMail()`, `openAsk(_:queue:)`, `openHome(_:)`), `GuardScreen`, `guardDestinations()`, `protectionDestinations()`, `SubscriptionsSection`, `ExpirySection`, `GuardQuickActions`, `GuardCoverage.from(_:)`, `GuardModel.daysLeftLine(_:)`, `GuardModel.day(_:)`, `GuardModel.modelHalf(modelRan:modelReady:turnedOff:)`, `WatchersService.money(_:)`, `FindingCard`, `SpottedNowCard`, `MailTriageCard`, `ReviewCard`, `PrivacyCard`.
- Produces: `enum HomeModel` with `Attention`, `attention(findingKeys:spottedHeadline:dangerousThisWeek:phishing:toReview:privacyFindings:findingLimit:)`, `Summary`, `money(_:mailCovered:)`, `documents(_:documentsCovered:)`, `Guardrail`, `Protected`, `protected(safari:keyboard:clipboard:onlineChecksOn:)`; `HomeView(path:openReads:openMail:)`; `SubscriptionsScreen(openReads:)`; `ExpiringScreen(openReads:)`; `GuardNotice(watchers:)`; `ShellEffects(router:)` (a `ViewModifier`). Ids: `home.attention`, `home.attention.spotted|mail|review|privacy`, `finding.<n>` (FindingCard's own), `home.money`, `home.money.connect`, `home.documents`, `home.documents.connect`, `home.protected`, `home.protected.turnOn`, `home.bytesOut`; Quick check keeps `guard.quick.*`.

- [ ] **Step 1: Write the failing unit tests**

Create `ios/LoupeTests/HomeModelTests.swift`:

```swift
import XCTest
import LoupeKit
@testable import Loupe

/// Home's cards (spec 2026-09-28 §3): what Needs attention holds and in what order, and what Money, Documents and
/// Protected say, empty states included.
final class HomeModelTests: XCTestCase {
    private func sub(_ merchant: String, monthly: Int64?, next: String?) -> CensusRow {
        CensusRow(merchant: merchant, cadence: "monthly", occurrences: 3, typicalMinor: monthly ?? 500,
                  lastChargedIso: "2026-09-01", daysSinceLastCharge: 27, monthlyMinor: monthly.map { KotlinLong(value: $0) },
                  sample: false, itemIds: ["a", "b", "c"], nextExpectedIso: next, verdict: nil)
    }

    private func doc(_ name: String, days: Int64) -> ExpiryRow {
        ExpiryRow(itemId: "id:\(name)", itemName: name, expiryIso: "2027-01-14", daysRemaining: days, ambiguous: false,
                  breachesRule: days < 183, documentType: nil, findingKey: nil, line: nil, sample: false)
    }

    func testNeedsAttentionIsEmptyWhenNothingWaits() {
        XCTAssertEqual(HomeModel.attention(findingKeys: [], spottedHeadline: nil, dangerousThisWeek: 0, phishing: 0,
                                           toReview: 0, privacyFindings: 0), [])
    }

    func testNeedsAttentionOrder() {
        let items = HomeModel.attention(findingKeys: ["impersonation:x", "site-fraud:y", "expiry:z", "term-change:w"],
                                        spottedHeadline: "Loupe spotted 1 risky site this week", dangerousThisWeek: 1,
                                        phishing: 2, toReview: 3, privacyFindings: 4)
        XCTAssertEqual(items.map(\.id), ["spotted", "mail", "finding:impersonation:x", "finding:site-fraud:y",
                                         "finding:expiry:z", "review", "privacy"],
                       "risky sites, mail phishing, three findings at most, review proposals, then privacy")
    }

    func testMoneyShowsTheMonthlyTotalAndTheNextCharge() {
        let census = SubscriptionCensus(rows: [sub("Netflix", monthly: 16500, next: "2026-10-03"),
                                               sub("Spotify", monthly: 6999, next: "2026-10-01")],
                                        monthlyTotalMinor: 23499, chargesFound: 6, sample: false, setAside: 0)
        let m = HomeModel.money(census, mailCovered: true)
        XCTAssertEqual(m.headline, "234.99 a month")
        XCTAssertEqual(m.detail, "2 subscriptions · Spotify next on \(GuardModel.day("2026-10-01"))")
        XCTAssertFalse(m.needsSource)
    }

    func testMoneyAsksForMailWhenNothingCouldHoldCharges() {
        let empty = SubscriptionCensus(rows: [], monthlyTotalMinor: 0, chargesFound: 0, sample: false, setAside: 0)
        let m = HomeModel.money(empty, mailCovered: false)
        XCTAssertEqual(m.headline, "No subscriptions yet")
        XCTAssertTrue(m.needsSource)
        XCTAssertTrue(m.detail.contains("Mail"), m.detail)
        XCTAssertEqual(HomeModel.money(empty, mailCovered: true).needsSource, false)
        XCTAssertEqual(HomeModel.money(nil, mailCovered: true).headline, "Reading your sources")
    }

    func testDocumentsLeadWithTheSoonest() {
        let d = HomeModel.documents([doc("passport-scan.txt", days: 150), doc("car-licence.jpg", days: 12)], documentsCovered: true)
        XCTAssertEqual(d.headline, "car-licence.jpg: \(GuardModel.daysLeftLine(12))")
        XCTAssertEqual(d.detail, "+ 1 more")
        XCTAssertFalse(d.needsSource)
    }

    func testDocumentsAskForFilesWhenNothingCouldHoldDocuments() {
        let d = HomeModel.documents([], documentsCovered: false)
        XCTAssertEqual(d.headline, "No documents read yet")
        XCTAssertTrue(d.needsSource)
        XCTAssertEqual(HomeModel.documents([], documentsCovered: true).headline, "No expiry dates found")
        XCTAssertEqual(HomeModel.documents(nil, documentsCovered: true).headline, "Reading your sources")
    }

    func testProtectedNamesWhatIsOffAndOffersTheFirst() {
        let all = HomeModel.protected(safari: true, keyboard: true, clipboard: true, onlineChecksOn: false)
        XCTAssertEqual(all.title, "Protected")
        XCTAssertNil(all.firstOff)
        XCTAssertEqual(all.line, "Safari on · Keyboard on · Clipboard on")
        XCTAssertEqual(all.bytesLine, "0 bytes out")
        let some = HomeModel.protected(safari: false, keyboard: true, clipboard: false, onlineChecksOn: true)
        XCTAssertEqual(some.title, "Not fully protected")
        XCTAssertEqual(some.firstOff, .safari)
        XCTAssertEqual(some.line, "Safari off · Keyboard on · Clipboard off")
        XCTAssertEqual(some.bytesLine, "Online checks on")
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: the iOS test command with `-only-testing:LoupeTests/HomeModelTests`
Expected: build FAILS with `cannot find 'HomeModel' in scope`.

- [ ] **Step 3: Write `HomeModel`**

Create `ios/Loupe/Shell/HomeModel.swift`:

```swift
import Foundation
import LoupeKit

/// Home's cards as values (spec 2026-09-28 §3): pure, so the order, the words and the empty states are unit-tested.
enum HomeModel {
    // MARK: Needs attention

    /// One thing that needs the person, with one primary action each (the view decides the action).
    enum Attention: Equatable, Identifiable {
        case spotted(headline: String, dangerous: Int)
        case mail(phishing: Int)
        case finding(key: String)
        case review(count: Int)
        case privacy(count: Int)

        var id: String {
            switch self {
            case .spotted: return "spotted"
            case .mail: return "mail"
            case .finding(let key): return "finding:" + key
            case .review: return "review"
            case .privacy: return "privacy"
            }
        }
    }

    /// Risky sites (Safari, clipboard, shared links), mail phishing, the watchers' newest findings (impersonation,
    /// site fraud, expiry, price rises; at most `findingLimit`), review proposals, then privacy findings. Empty when
    /// nothing waits: the section is then not shown at all.
    static func attention(findingKeys: [String], spottedHeadline: String?, dangerousThisWeek: Int, phishing: Int,
                          toReview: Int, privacyFindings: Int, findingLimit: Int = 3) -> [Attention] {
        var out: [Attention] = []
        if let spottedHeadline { out.append(.spotted(headline: spottedHeadline, dangerous: dangerousThisWeek)) }
        if phishing > 0 { out.append(.mail(phishing: phishing)) }
        out += findingKeys.prefix(findingLimit).map { Attention.finding(key: $0) }
        if toReview > 0 { out.append(.review(count: toReview)) }
        if privacyFindings > 0 { out.append(.privacy(count: privacyFindings)) }
        return out
    }

    // MARK: Money and Documents

    /// A card's words: a headline, one line under it, and whether it should offer "Open What Loupe reads".
    struct Summary: Equatable {
        let headline: String
        let detail: String
        let needsSource: Bool
    }

    /// The Money card from the census; nil while the watchers have not finished their first run.
    static func money(_ census: SubscriptionCensus?, mailCovered: Bool) -> Summary {
        guard let census else {
            return Summary(headline: "Reading your sources", detail: "Subscriptions show here once the watchers have run.", needsSource: false)
        }
        let rows = census.rows
        if rows.isEmpty {
            if !mailCovered {
                return Summary(headline: "No subscriptions yet",
                               detail: "Turn on Mail, or import a bank statement, and Loupe finds what you pay for.",
                               needsSource: true)
            }
            return Summary(headline: "No subscriptions yet",
                           detail: "No merchant charged three or more times in the \(census.chargesFound) charge\(census.chargesFound == 1 ? "" : "s") read.",
                           needsSource: false)
        }
        let total = rows.reduce(Int64(0)) { $0 + ($1.monthlyMinor?.int64Value ?? 0) }
        var detail = "\(rows.count) subscription\(rows.count == 1 ? "" : "s")"
        if let next = rows.compactMap({ r in r.nextExpectedIso.map { (r.merchant, $0) } }).min(by: { $0.1 < $1.1 }) {
            detail += " · \(next.0) next on \(GuardModel.day(next.1))"
        }
        if census.sample { detail += " · sample" }
        return Summary(headline: "\(WatchersService.money(total)) a month", detail: detail, needsSource: false)
    }

    /// The Documents card from the expiry timeline; nil rows while the watchers have not finished their first run.
    static func documents(_ rows: [ExpiryRow]?, documentsCovered: Bool) -> Summary {
        guard let rows else {
            return Summary(headline: "Reading your sources", detail: "Expiry dates show here once the watchers have run.", needsSource: false)
        }
        guard let soonest = rows.min(by: { $0.daysRemaining < $1.daysRemaining }) else {
            if !documentsCovered {
                return Summary(headline: "No documents read yet",
                               detail: "Turn on Files or Photos: Loupe finds the expiry dates on IDs, licences, passports and policies.",
                               needsSource: true)
            }
            return Summary(headline: "No expiry dates found",
                           detail: "Not an all-clear: a document Loupe cannot read is not checked.", needsSource: false)
        }
        return Summary(headline: "\(soonest.itemName): \(GuardModel.daysLeftLine(soonest.daysRemaining))",
                       detail: rows.count > 1 ? "+ \(rows.count - 1) more" : "1 document", needsSource: false)
    }

    // MARK: Protected

    enum Guardrail: String, CaseIterable {
        case safari, keyboard, clipboard

        var title: String {
            switch self {
            case .safari: return "Safari"
            case .keyboard: return "Keyboard"
            case .clipboard: return "Clipboard"
            }
        }
    }

    struct Protected: Equatable {
        let on: [Guardrail: Bool]
        let onlineChecksOn: Bool

        var firstOff: Guardrail? { Guardrail.allCases.first { on[$0] != true } }
        var title: String { firstOff == nil ? "Protected" : "Not fully protected" }
        /// "Safari on · Keyboard off · Clipboard on": words, not ticks, so VoiceOver reads it as it looks.
        var line: String { Guardrail.allCases.map { "\($0.title) \(on[$0] == true ? "on" : "off")" }.joined(separator: " · ") }
        var bytesLine: String { onlineChecksOn ? "Online checks on" : "0 bytes out" }
    }

    static func protected(safari: Bool, keyboard: Bool, clipboard: Bool, onlineChecksOn: Bool) -> Protected {
        Protected(on: [.safari: safari, .keyboard: keyboard, .clipboard: clipboard], onlineChecksOn: onlineChecksOn)
    }
}
```

- [ ] **Step 4: Run to verify the model passes**

Run: the iOS test command with `-only-testing:LoupeTests/HomeModelTests`
Expected: 7 tests, `** TEST SUCCEEDED **`.

- [ ] **Step 5: Write the failing Home UI test**

Create `ios/LoupeUITests/HomeUITests.swift`:

```swift
import XCTest

/// Home over the sample (spec 2026-09-28 §3): the cards in order, each opening today's screen for it, the newest
/// findings in Needs attention, and a second tap on Home going back to its first screen.
final class HomeUITests: ScenarioCase {
    private func launchHome() {
        launch(["-LoupeFixtures", "-LoupeTab", "now", "-LoupeSkipOnboarding", "-LoupeModelState", "missing", "-LoupeLanguage", "en"])
        let money = button("home.money")
        XCTAssertTrue(money.waitForExistence(timeout: 30))
        waitFor(money, "label CONTAINS %@", "a month", timeout: 90, "the watchers ran over the sample")
    }

    func testHomeCardsOpenTheirScreensAndHomeComesBack() {
        launchHome()
        let attention = any("home.attention"), quick = button("guard.quick.checkLink")
        let money = button("home.money"), docs = button("home.documents"), protected = button("home.protected")
        XCTAssertTrue(attention.exists, "the sample has findings: Needs attention shows")
        XCTAssertLessThan(attention.frame.minY, quick.frame.minY, "Needs attention, then Quick check")
        reveal(protected)
        XCTAssertLessThan(quick.frame.minY, money.frame.minY)
        XCTAssertLessThan(money.frame.minY, docs.frame.minY)
        XCTAssertLessThan(docs.frame.minY, protected.frame.minY, "Money, Documents, then Protected")
        audit("home", ["guard.quick.checkLink", "guard.quick.checkCopied", "home.money", "home.documents", "home.protected"])

        reveal(money); money.tap()
        XCTAssertTrue(any("guard.subscriptions").waitForExistence(timeout: 10), "Money opens Subscriptions")
        back()
        reveal(docs); docs.tap()
        XCTAssertTrue(any("guard.expiry").waitForExistence(timeout: 10), "Documents opens Expiring")
        back()
        reveal(protected); protected.tap()
        XCTAssertTrue(any("guard.header").waitForExistence(timeout: 10), "Protected opens the Protection screen")
        tab("Home")
        XCTAssertTrue(button("home.money").waitForExistence(timeout: 5), "a second tap on Home goes back to its first screen")
    }

    func testNeedsAttentionShowsTheNewestFindings() {
        launchHome()
        XCTAssertTrue(any("finding.0").waitForExistence(timeout: 10))
        XCTAssertFalse(any("finding.3").exists, "three findings at most on Home; the rest are on Protection")
        let open = button("finding.open.0")
        if open.exists { XCTAssertGreaterThanOrEqual(open.frame.height, 44) }
    }
}
```

Delete `ios/LoupeUITests/NowFindingsUITests.swift` (its checks move into `HomeUITests`).

- [ ] **Step 6: Run to verify it fails**

Run: the iOS test command with `-only-testing:LoupeUITests/HomeUITests`
Expected: FAIL — `home.money` never exists (Home is still `NowView`).

- [ ] **Step 7: Add the two Home routes and extract `GuardNotice`**

In `ios/Loupe/Shell/ShellRoutes.swift` replace

```swift
    case protection
    case review
    case privacy
}
```

with

```swift
    case protection
    /// Guard's Subscriptions section on its own screen (the Money card).
    case subscriptions
    /// Guard's Expiring soon timeline on its own screen (the Documents card).
    case expiring
    case review
    case privacy
}
```

In `ios/Loupe/Guard/GuardView.swift`, in `GuardScreen.body` replace the line `                    notice` with `                    GuardNotice(watchers: watchers)`, delete the whole `@ViewBuilder private var notice: some View { … }` property, and append:

```swift
/// The watchers' last answer ("Set aside … · Undo"), on every screen a verdict can be given from (Protection,
/// Subscriptions, Expiring).
struct GuardNotice: View {
    @ObservedObject var watchers: WatchersService

    var body: some View {
        if let notice = watchers.notice {
            HStack(spacing: 10) {
                Text(notice).font(.footnote).foregroundStyle(Palette.ink)
                    .fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: 8)
                if watchers.lastSetAside != nil || watchers.lastSetAsideRow != nil {
                    // The frame is the label's: outside the Button it left only the word tappable (17 pt).
                    Button { watchers.undoSetAside() } label: {
                        Text("Undo").frame(minWidth: 44, minHeight: 44).contentShape(Rectangle())
                    }
                    .font(.footnote.weight(.semibold))
                    .accessibilityIdentifier("guard.undo")
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .frame(minHeight: 44)
            .background(Palette.accentSoft, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("guard.notice")
        }
    }
}
```

- [ ] **Step 8: Write the Subscriptions and Expiring screens**

Create `ios/Loupe/Shell/TrackingScreens.swift`:

```swift
import LoupeKit
import SwiftUI

/// Home → Money: Guard's Subscriptions section on its own screen (phase 1 hosts today's view; §6.2's redesign is
/// step 4). A merchant opens `GuardRoute.subscription`, registered at Home's root.
struct SubscriptionsScreen: View {
    let openReads: () -> Void
    @ObservedObject var watchers: WatchersService = .shared
    @ObservedObject var sources: SourcesService = .shared

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                GuardNotice(watchers: watchers)
                if let summary = watchers.summary {
                    SubscriptionsSection(census: summary.census, findings: summary.findings,
                                         coverage: GuardCoverage.from(sources), openSources: openReads)
                } else {
                    TrackingLoading(text: "The watchers are reading your sources")
                }
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 32)
        }
        .neonGround()
        .navigationTitle("Subscriptions")
        .navigationBarTitleDisplayMode(.inline)
    }
}

/// Home → Documents: Guard's Expiring soon timeline on its own screen (§6.3's redesign is step 4).
struct ExpiringScreen: View {
    let openReads: () -> Void
    @ObservedObject var watchers: WatchersService = .shared
    @ObservedObject var sources: SourcesService = .shared
    @ObservedObject private var readiness = ModelReadiness.shared
    @ObservedObject private var settings = ModelSettingsService.shared

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                GuardNotice(watchers: watchers)
                if let summary = watchers.summary {
                    ExpirySection(rows: summary.expiries, ruleName: summary.ruleName, itemsChecked: Int(summary.itemsChecked),
                                  half: GuardModel.modelHalf(modelRan: summary.modelRan, modelReady: readiness.isReady,
                                                             turnedOff: !settings.useLaya(Features.shared.WATCHERS)),
                                  coverage: GuardCoverage.from(sources), openSources: openReads)
                } else {
                    TrackingLoading(text: "The watchers are reading your sources")
                }
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 32)
        }
        .neonGround()
        .navigationTitle("Expiring")
        .navigationBarTitleDisplayMode(.inline)
    }
}

struct TrackingLoading: View {
    let text: String

    var body: some View {
        HStack(spacing: 10) {
            ProgressView()
            Text(text).font(.subheadline).foregroundStyle(Palette.inkSoft)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .card()
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("tracking.loading")
    }
}
```

- [ ] **Step 9: Write `HomeView`**

Create `ios/Loupe/Shell/HomeView.swift`:

```swift
import LoupeKit
import SwiftUI

/// Home (spec 2026-09-28 §3, step 1): where Loupe opens. Today's Now and Guard content in the new order: Needs
/// attention (only when something does), Quick check, Money, Documents, Protected. Each card leads to today's screen
/// for it, pushed on Home's stack.
struct HomeView: View {
    @Binding var path: NavigationPath
    /// Me → What Loupe reads (the empty cards' button).
    let openReads: () -> Void
    /// Me → Mail (mail phishing in Needs attention).
    let openMail: () -> Void
    @ObservedObject var watchers: WatchersService = .shared
    @ObservedObject var sources: SourcesService = .shared
    @ObservedObject var review: ReviewService = .shared
    @ObservedObject var privacy: PrivacyService = .shared
    @ObservedObject var mail: MailTriageService = .shared
    @ObservedObject private var protection = ProtectionStore.shared
    @ObservedObject private var safari = SafariSetup.shared
    @ObservedObject private var keyboard = KeyboardSetup.shared
    @ObservedObject private var clipboard = ClipboardMonitor.shared
    @ObservedObject private var online = OnlineChecksService.shared
    @State private var openItem: SourceItem?

    var body: some View {
        NavigationStack(path: $path) {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    header
                    // Runs that belong to Home, live and in place: the passive sort and the watchers.
                    LiveRunSection(view: "now", whileRunning: true)
                    LiveRunSection(view: "watchers", whileRunning: true)
                    attentionSection
                    HomeSectionTitle(title: "Quick check")
                    GuardQuickActions()
                    moneyCard
                    documentsCard
                    protectedCard
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 24)
            }
            .neonGround()
            .scrollBounceBehavior(.basedOnSize)
            .toolbar(.hidden, for: .navigationBar)
            .navigationDestination(for: HomeRoute.self) { route in
                switch route {
                case .protection: GuardScreen()
                case .subscriptions: SubscriptionsScreen(openReads: openReads)
                case .expiring: ExpiringScreen(openReads: openReads)
                case .review: ReviewView(review: review)
                case .privacy: PrivacyView(privacy: privacy)
                }
            }
            .guardDestinations()
            .protectionDestinations()
            .sheet(item: $openItem) { ItemTextView(item: $0) }
        }
    }

    private var coverage: GuardCoverage { GuardCoverage.from(sources) }

    private var header: some View {
        HStack(alignment: .center, spacing: 12) {
            Image("LogoLight")
                .resizable().scaledToFit()
                .frame(height: 30)
                .accessibilityLabel("Loupe")
            Spacer(minLength: 8)
            Pill(text: online.settings.anyOn ? "Online checks on" : "0 bytes out",
                 color: online.settings.anyOn ? Palette.blue : Palette.okText,
                 symbol: online.settings.anyOn ? "globe" : "lock.fill")
                .accessibilityIdentifier("home.bytesOut")
            MascotView(state: mascotState, size: 56)
        }
        .padding(.top, 16)
    }

    private var mascotState: MascotState {
        if sources.scanning || watchers.running { return .scanning }
        if watchers.newCount > 0 && !watchers.findings.isEmpty { return .found }
        return .idle
    }

    // MARK: Needs attention

    private var attention: [HomeModel.Attention] {
        HomeModel.attention(findingKeys: watchers.nowFindings(limit: 3).map(\.key),
                            spottedHeadline: protection.summary.headline,
                            dangerousThisWeek: protection.summary.dangerousThisWeek,
                            phishing: Int(mail.summary?.phishingCount ?? 0),
                            toReview: review.toReview,
                            privacyFindings: privacy.findings.count)
    }

    @ViewBuilder private var attentionSection: some View {
        let items = attention
        if !items.isEmpty {
            VStack(alignment: .leading, spacing: 10) {
                HomeSectionTitle(title: "Needs attention", count: items.count)
                ForEach(items) { attentionRow($0) }
            }
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("home.attention")
        }
    }

    @ViewBuilder private func attentionRow(_ item: HomeModel.Attention) -> some View {
        switch item {
        case .spotted(let headline, let dangerous):
            Button { path.append(ProtectionRoute.spotted) } label: { SpottedNowCard(headline: headline, dangerous: dangerous) }
                .buttonStyle(.plain)
                .accessibilityIdentifier("home.attention.spotted")
        case .mail:
            Button(action: openMail) { MailTriageCard(mail: mail) }
                .buttonStyle(.plain)
                .accessibilityIdentifier("home.attention.mail")
        case .finding(let key):
            if let i = watchers.findings.firstIndex(where: { $0.key == key }) {
                let f = watchers.findings[i]
                FindingCard(finding: f, index: i, item: ItemIndex.item(f.itemId),
                            onVerdict: { watchers.answer(f, $0) },
                            onOpen: { openItem = watchers.item(f.itemId) })
            }
        case .review:
            Button { path.append(HomeRoute.review) } label: { ReviewCard(review: review) }
                .buttonStyle(.plain)
                .accessibilityIdentifier("home.attention.review")
        case .privacy:
            Button { path.append(HomeRoute.privacy) } label: { PrivacyCard(privacy: privacy) }
                .buttonStyle(.plain)
                .accessibilityIdentifier("home.attention.privacy")
        }
    }

    // MARK: Money, Documents, Protected

    private var moneyCard: some View {
        let m = HomeModel.money(watchers.summary?.census, mailCovered: coverage.mail)
        return VStack(alignment: .leading, spacing: 10) {
            Button { path.append(HomeRoute.subscriptions) } label: {
                HomeCardLabel(caption: "Money", headline: m.headline, detail: m.detail, symbol: "creditcard")
            }
            .buttonStyle(.plain)
            .accessibilityHint("Opens your subscriptions")
            .accessibilityIdentifier("home.money")
            if m.needsSource {
                CardAction(title: "Open What Loupe reads", symbol: "externaldrive.fill.badge.plus", hue: Palette.cyan, action: openReads)
                    .accessibilityIdentifier("home.money.connect")
            }
        }
    }

    private var documentsCard: some View {
        let d = HomeModel.documents(watchers.summary?.expiries, documentsCovered: coverage.documents)
        return VStack(alignment: .leading, spacing: 10) {
            Button { path.append(HomeRoute.expiring) } label: {
                HomeCardLabel(caption: "Documents", headline: d.headline, detail: d.detail, symbol: "doc.text.magnifyingglass")
            }
            .buttonStyle(.plain)
            .accessibilityHint("Opens your expiring documents")
            .accessibilityIdentifier("home.documents")
            if d.needsSource {
                CardAction(title: "Open What Loupe reads", symbol: "externaldrive.fill.badge.plus", hue: Palette.cyan, action: openReads)
                    .accessibilityIdentifier("home.documents.connect")
            }
        }
    }

    private var protectedCard: some View {
        let p = HomeModel.protected(safari: safari.isOn, keyboard: keyboard.added, clipboard: clipboard.enabled,
                                    onlineChecksOn: online.settings.anyOn)
        return VStack(alignment: .leading, spacing: 10) {
            Button { path.append(HomeRoute.protection) } label: {
                HomeCardLabel(caption: p.title, headline: p.line, detail: p.bytesLine, symbol: "shield.lefthalf.filled")
            }
            .buttonStyle(.plain)
            .accessibilityHint("Opens Protection: the watchers, link checks and Spotted")
            .accessibilityIdentifier("home.protected")
            if let off = p.firstOff {
                CardAction(title: "Turn on \(off.title)", symbol: "power", hue: Palette.cyan) { turnOn(off) }
                    .accessibilityIdentifier("home.protected.turnOn")
            }
        }
    }

    /// Safari turns on from here (iOS's own settings); the keyboard and the clipboard chip are set up on Protection.
    private func turnOn(_ g: HomeModel.Guardrail) {
        switch g {
        case .safari: Task { await safari.turnOn() }
        case .keyboard, .clipboard: path.append(HomeRoute.protection)
        }
    }
}

/// A Home card: a caption, a large headline, one line, a chevron; the whole card is the button's label.
struct HomeCardLabel: View {
    let caption: String
    let headline: String
    let detail: String
    let symbol: String

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            NeonIcon(name: symbol, color: Palette.cyan, size: 22)
                .frame(width: 28)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 4) {
                Caption(text: caption)
                Text(headline).font(Typeface.display(24)).foregroundStyle(Palette.ink)
                    .fixedSize(horizontal: false, vertical: true)
                Text(detail).font(.footnote).foregroundStyle(Palette.inkSoft)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 0)
            Image(systemName: "chevron.forward").font(.caption).foregroundStyle(Palette.inkSoft).accessibilityHidden(true)
        }
        .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
        .card()
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }
}

/// A section's title on Home, with an optional count ("Needs attention · 2").
struct HomeSectionTitle: View {
    let title: String
    var count: Int? = nil

    var body: some View {
        Text(count.map { "\(title) · \($0)" } ?? title)
            .font(Typeface.display(22)).foregroundStyle(Palette.ink)
            .fixedSize(horizontal: false, vertical: true)
            .accessibilityAddTraits(.isHeader)
            .padding(.horizontal, 4)
            .padding(.top, 4)
    }
}
```

- [ ] **Step 10: Move Now's app-wide work to `ShellEffects`**

Create `ios/Loupe/Shell/ShellEffects.swift`:

```swift
import Combine
import LoupeKit
import SwiftUI

/// App-wide work that used to hang off the Now tab, now on the root so it runs whichever place a launch opens on:
/// re-run the watchers, the privacy check and mail triage whenever the scanned items change; collect Review
/// proposals when a check has run; load the judgments; the DEBUG demos and `-LoupeOpen queue|review`.
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
            // The first value arrives on subscribe, so this also runs at launch.
            .onReceive(sources.$sampleScan.combineLatest(sources.$sampleEnabled, sources.$revision)) { _ in
                guard !sources.scanning else { return }
                Task { await watchers.run() }
                Task { await privacy.run() }
                Task { await mail.run() }
            }
            // The decision model arrived (download finished, files copied in): the watchers' model half can run now.
            .onChange(of: readiness.isReady) { _, ready in
                if ready, !sources.scanning { Task { await watchers.run() } }
            }
            // Whatever a check proposes goes to Review as soon as it has run (never run until approved).
            // (@Published fires before the value is stored: hop once so collect reads the new one.)
            .onReceive(privacy.$summary.receive(on: DispatchQueue.main)) { _ in review.collect() }
            .onReceive(mail.$summary.receive(on: DispatchQueue.main)) { _ in review.collect() }
            .onReceive(watchers.$summary.receive(on: DispatchQueue.main)) { _ in review.collect() }
            .task {
                judgments.load()
                judgments.refreshLedger()
                #if DEBUG
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
```

- [ ] **Step 11: Home on the root; Now goes**

In `ios/Loupe/Shell/RootView.swift` replace

```swift
            NowView()
                .tabItem { Label(Place.home.title, systemImage: Place.home.symbol) }
```

with

```swift
            HomeView(path: $router.homePath, openReads: { router.openReads() }, openMail: { router.openMail() })
                .tabItem { Label(Place.home.title, systemImage: Place.home.symbol) }
```

and replace `        .overlay(alignment: .bottom) { ActivityDock() }` with

```swift
        .overlay(alignment: .bottom) { ActivityDock() }
        .modifier(ShellEffects(router: router))
```

Delete `ios/Loupe/Shell/NowView.swift`.

- [ ] **Step 12: Run the Home tests**

Run: the iOS test command with `-only-testing:LoupeTests/HomeModelTests -only-testing:LoupeTests/ShellRoutingTests -only-testing:LoupeUITests/HomeUITests`
Expected: `** TEST SUCCEEDED **`.

- [ ] **Step 13: Commit**

```bash
cd /Users/bistrocloud/Documents/Loupe/jevistication
git add ios/Loupe/Shell/HomeModel.swift ios/Loupe/Shell/HomeView.swift ios/Loupe/Shell/TrackingScreens.swift ios/Loupe/Shell/ShellEffects.swift ios/Loupe/Shell/ShellRoutes.swift ios/Loupe/Guard/GuardView.swift ios/Loupe/Shell/RootView.swift ios/LoupeTests/HomeModelTests.swift ios/LoupeUITests/HomeUITests.swift
git rm ios/Loupe/Shell/NowView.swift ios/LoupeUITests/NowFindingsUITests.swift
git commit -m "ios: Home: needs attention, quick check, money, documents, protected

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
git push
```

---

### Task 4: Ask hosts today's Judgments entry

**Files:**
- Modify: `ios/Loupe/Judgments/JudgmentsView.swift` (title "Ask", `AskHeader` above the section picker)
- Test: modify `ios/LoupeUITests/UnsureQueueUITests.swift`

**Interfaces:**
- Consumes: `router.askPath`, `JudgmentRoute.queue`, `NeedsYouCard(count:)`, `ShellEffects`' `-LoupeOpen queue`.
- Produces: `struct AskHeader: View { init(needsYou: Int) }`; ids `ask.header`, `ask.needsYou`.

- [ ] **Step 1: Write the failing UI tests**

Replace the body of `ios/LoupeUITests/UnsureQueueUITests.swift` with:

```swift
import XCTest

final class UnsureQueueUITests: XCTestCase {
    override func setUp() { continueAfterFailure = false }

    /// Ask → Needs you → answer the first item → the count drops by one.
    func testAnsweringAnItemDropsTheCount() {
        let app = XCUIApplication()
        // The demo's stand-in scorer plays the model: the model reads as ready (it is required, 2026-09-25).
        app.launchArguments = ["-LoupeFixtures", "-LoupeQueueDemo", "-LoupeTab", "ask", "-LoupeSkipOnboarding", "-LoupeModelState", "ready"]
        app.launch()
        XCTAssertTrue(app.navigationBars["Ask"].waitForExistence(timeout: 20), "Ask hosts today's judgments")
        let card = app.buttons["ask.needsYou"]
        XCTAssertTrue(card.waitForExistence(timeout: 30))
        card.tap()
        let count = app.staticTexts["queue.count"]
        XCTAssertTrue(count.waitForExistence(timeout: 5))
        let before = Int(count.label.replacingOccurrences(of: "Needs you: ", with: "")) ?? -1
        XCTAssertGreaterThan(before, 0)
        let option = app.buttons["queue.option.0"]
        XCTAssertTrue(option.waitForExistence(timeout: 5))
        option.tap()
        let dropped = NSPredicate(format: "label == %@", "Needs you: \(before - 1)")
        expectation(for: dropped, evaluatedWith: count)
        waitForExpectations(timeout: 5)
        XCTAssertTrue(app.buttons["queue.undo"].isEnabled)
    }

    /// `-LoupeOpen queue` opens Ask with the Unsure queue pushed (the badge's door in phase 1).
    func testOpenQueueLaunchesStraightIntoTheQueue() {
        let app = XCUIApplication()
        app.launchArguments = ["-LoupeFixtures", "-LoupeQueueDemo", "-LoupeTab", "now", "-LoupeOpen", "queue",
                               "-LoupeSkipOnboarding", "-LoupeModelState", "ready"]
        app.launch()
        XCTAssertTrue(app.staticTexts["queue.count"].waitForExistence(timeout: 30))
        XCTAssertTrue(app.tabBars.buttons["Ask"].isSelected)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: the iOS test command with `-only-testing:LoupeUITests/UnsureQueueUITests`
Expected: FAIL at `navigationBars["Ask"]` (the title is still "Judgments").

- [ ] **Step 3: The Ask title and header**

In `ios/Loupe/Judgments/JudgmentsView.swift` replace

```swift
            VStack(spacing: 0) {
                Picker("Section", selection: $section) {
```

with

```swift
            VStack(spacing: 0) {
                AskHeader(needsYou: service.needsYou)
                Picker("Section", selection: $section) {
```

replace `            .navigationTitle("Judgments")` with `            .navigationTitle("Ask")`, and append to the end of the file:

```swift
/// The top of Ask in phase 1 (spec §10 step 1): what Ask holds today, and the Unsure queue's door whenever anything
/// waits (the Ask tab's badge carries the same count). The composer replaces this in step 2.
struct AskHeader: View {
    let needsYou: Int

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Pick a ready question, write your own, or ask the web.")
                .font(.footnote).foregroundStyle(Palette.inkSoft)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityIdentifier("ask.header")
            if needsYou > 0 {
                NavigationLink(value: JudgmentRoute.queue) { NeedsYouCard(count: needsYou) }
                    .buttonStyle(.plain)
                    .accessibilityHint("Answers teach the decision model")
                    .accessibilityIdentifier("ask.needsYou")
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 16)
        .padding(.top, 4)
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: the iOS test command with `-only-testing:LoupeUITests/UnsureQueueUITests -only-testing:LoupeUITests/JudgmentsUITests`
Expected: `** TEST SUCCEEDED **`.

- [ ] **Step 5: Commit**

```bash
cd /Users/bistrocloud/Documents/Loupe/jevistication
git add ios/Loupe/Judgments/JudgmentsView.swift ios/LoupeUITests/UnsureQueueUITests.swift
git commit -m "ios: Ask hosts the judgments entry, with the Unsure queue's door and badge

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
git push
```

---

### Task 5: Mail is one place

**Files:**
- Modify: `ios/Loupe/Mail/MailTriageView.swift` (`MailTriageView` → `MailScreen`; add `MailFound`, `MailFoundCard`, `MailEntryCard`)
- Modify: `ios/Loupe/Sources/SourcesView.swift` (Mail as one entry; the triage link goes)
- Modify: `ios/Loupe/Guard/GuardView.swift` (`guardDestinations()` `.mail` → `MailScreen`)
- Modify: `ios/Loupe/Shell/MeView.swift` (`MeRoute.mail` → `MailScreen`)
- Test: create `ios/LoupeTests/MailScreenTests.swift`; modify `ios/LoupeUITests/MailTriageUITests.swift`, `ios/LoupeUITests/SourcesUITests.swift`, `ios/LoupeUITests/AssistUITests.swift`, `ios/LoupeUITests/PersistenceUITests.swift`

**Interfaces:**
- Consumes: `PhoneSourceRow(sources:source:)`, `MailTriageService`, `CensusRow.itemIds`, `MailSummary.rows[].itemId`, `phishingCount`, `needsReplyCount`.
- Produces: `struct MailScreen: View { init(mail: MailTriageService) }` titled "Mail"; `struct MailFound: Equatable { phishing, needsReply: Int; subscriptions: [String]; static func of(phishing:needsReply:mailItemIds:census:) -> MailFound; var line: String }`; `struct MailEntryCard: View { init(sources:mail:); static func line(account:on:phishing:needsReply:) -> String }`; ids `mail.found`, `sources.mail` (now the Mail entry).

- [ ] **Step 1: Write the failing unit tests**

Create `ios/LoupeTests/MailScreenTests.swift`:

```swift
import XCTest
import LoupeKit
@testable import Loupe

/// Mail as one place (spec D10): what the "Found in your mail" card counts, and what the Mail entry says.
final class MailScreenTests: XCTestCase {
    private func sub(_ merchant: String, _ ids: [String]) -> CensusRow {
        CensusRow(merchant: merchant, cadence: "monthly", occurrences: 3, typicalMinor: 999, lastChargedIso: "2026-09-01",
                  daysSinceLastCharge: 27, monthlyMinor: KotlinLong(value: 999), sample: false, itemIds: ids,
                  nextExpectedIso: nil, verdict: nil)
    }

    func testFoundCountsOnlySubscriptionsReadFromMail() {
        let found = MailFound.of(phishing: 1, needsReply: 3, mailItemIds: ["mail:a", "mail:b"],
                                 census: [sub("Netflix", ["mail:a", "inbox:row1"]), sub("Gym", ["calendar:x"]), sub("Spotify", ["mail:b"])])
        XCTAssertEqual(found.subscriptions, ["Netflix", "Spotify"])
        XCTAssertEqual(found.line, "Phishing: 1 · Needs a reply: 3 · Subscriptions found: 2")
    }

    func testEntryLineSaysWhatIsConnectedAndWhatWasFound() {
        XCTAssertEqual(MailEntryCard.line(account: nil, on: false, phishing: 0, needsReply: 0),
                       "No mailbox yet · add one to read receipts, bills and phishing")
        XCTAssertEqual(MailEntryCard.line(account: "me@example.com", on: false, phishing: 2, needsReply: 1), "me@example.com · off")
        XCTAssertEqual(MailEntryCard.line(account: "me@example.com", on: true, phishing: 2, needsReply: 1),
                       "me@example.com · 2 possible phishing · 1 needs a reply")
        XCTAssertEqual(MailEntryCard.line(account: "me@example.com", on: true, phishing: 0, needsReply: 0), "me@example.com")
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: the iOS test command with `-only-testing:LoupeTests/MailScreenTests`
Expected: build FAILS with `cannot find 'MailFound' in scope`.

- [ ] **Step 3: `MailScreen` and its pieces**

In `ios/Loupe/Mail/MailTriageView.swift` replace

```swift
/// The Mail triage screen: rows by section (phishing suspected first), each with its category,
/// the concrete signals behind a phishing verdict, link checks, and Open / Mark safe / Confirm.
struct MailTriageView: View {
    @ObservedObject var mail: MailTriageService
    @ObservedObject private var assist = AssistService.shared
```

with

```swift
/// Mail, one place (spec D10): the mailbox (connect, the switch, Scan again, settings and Remove), what was found in
/// the mail, then the triage rows by section (phishing suspected first), each with its category, the concrete signals
/// behind a phishing verdict, link checks, and Open / Mark safe / Confirm / Draft a reply.
struct MailScreen: View {
    @ObservedObject var mail: MailTriageService
    @ObservedObject var sources: SourcesService = .shared
    @ObservedObject private var watchers = WatchersService.shared
    @ObservedObject private var assist = AssistService.shared
```

replace

```swift
            VStack(alignment: .leading, spacing: 16) {
                LiveRunSection(view: "email")
```

with

```swift
            VStack(alignment: .leading, spacing: 16) {
                PhoneSourceRow(sources: sources, source: .mail)
                MailFoundCard(found: found)
                LiveRunSection(view: "email")
```

replace `                        Text("No mail to triage. Turn on the sample or Mail in Sources.")` with `                        Text("No mail yet. Add a mailbox above, or turn on the sample in What Loupe reads.")`, replace `        .navigationTitle("Mail triage")` with `        .navigationTitle("Mail")`, and insert before `    private func sectionView(`:

```swift
    private var found: MailFound {
        MailFound.of(phishing: Int(mail.summary?.phishingCount ?? 0), needsReply: Int(mail.summary?.needsReplyCount ?? 0),
                     mailItemIds: Set(mail.summary?.rows.map(\.itemId) ?? []),
                     census: watchers.summary?.census.rows ?? [])
    }

```

Append to the end of the file:

```swift
/// What Mail found (spec D10): possible phishing, mail that needs a reply, and the subscriptions whose charges were
/// read from an email. Pure, for the tests.
struct MailFound: Equatable {
    let phishing: Int
    let needsReply: Int
    /// Census merchants with at least one charge read from an email, in the census's order.
    let subscriptions: [String]

    static func of(phishing: Int, needsReply: Int, mailItemIds: Set<String>, census: [CensusRow]) -> MailFound {
        MailFound(phishing: phishing, needsReply: needsReply,
                  subscriptions: census.filter { row in row.itemIds.contains { mailItemIds.contains($0) } }.map(\.merchant))
    }

    /// The card's spoken line (the scenario tests read the numbers from it).
    var line: String { "Phishing: \(phishing) · Needs a reply: \(needsReply) · Subscriptions found: \(subscriptions.count)" }
}

struct MailFoundCard: View {
    let found: MailFound

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Caption(text: "Found in your mail")
            HStack(spacing: 0) {
                stat("\(found.phishing)", "possible phishing", found.phishing > 0 ? Palette.dangerText : Palette.ink)
                stat("\(found.needsReply)", "need a reply", Palette.ink)
                stat("\(found.subscriptions.count)", "subscriptions", Palette.ink)
            }
            if !found.subscriptions.isEmpty {
                Text(found.subscriptions.joined(separator: " · "))
                    .font(.caption).foregroundStyle(Palette.inkSoft)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .card()
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(found.line)
        .accessibilityIdentifier("mail.found")
    }

    private func stat(_ n: String, _ label: String, _ color: Color) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(n).font(Typeface.mono(22, weight: .bold)).monospacedDigit().foregroundStyle(color)
            Text(label).font(.caption2).foregroundStyle(Palette.inkSoft)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// Mail's one entry in What Loupe reads: the mailbox and what it found, opening `MailScreen`.
struct MailEntryCard: View {
    @ObservedObject var sources: SourcesService
    @ObservedObject var mail: MailTriageService

    var body: some View {
        HStack(spacing: 12) {
            SourceGlyph(id: PhoneSource.mail.id, on: sources.isPhoneEnabled(.mail))
            VStack(alignment: .leading, spacing: 3) {
                Text("Mail").font(Typeface.display(22)).foregroundStyle(Palette.ink)
                Text(Self.line(account: sources.mailAccount?.username, on: sources.isPhoneEnabled(.mail),
                               phishing: Int(mail.summary?.phishingCount ?? 0), needsReply: Int(mail.summary?.needsReplyCount ?? 0)))
                    .font(.footnote).foregroundStyle(Palette.inkSoft)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 8)
            Image(systemName: "chevron.forward").font(.caption).foregroundStyle(Palette.inkSoft).accessibilityHidden(true)
        }
        .frame(minHeight: 44)
        .card()
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
        .accessibilityHint("Connect, sync, what was found and the actions")
    }

    static func line(account: String?, on: Bool, phishing: Int, needsReply: Int) -> String {
        guard let account else { return "No mailbox yet · add one to read receipts, bills and phishing" }
        guard on else { return "\(account) · off" }
        var parts = [account]
        if phishing > 0 { parts.append("\(phishing) possible phishing") }
        if needsReply > 0 { parts.append("\(needsReply) need\(needsReply == 1 ? "s" : "") a reply") }
        return parts.joined(separator: " · ")
    }
}
```

In `ios/Loupe/Guard/GuardView.swift` (`guardDestinations()`) replace `case .mail: MailTriageView(mail: MailTriageService.shared)` with `case .mail: MailScreen(mail: MailTriageService.shared)`. In `ios/Loupe/Shell/MeView.swift` replace `case .mail: MailTriageView(mail: MailTriageService.shared)` with `case .mail: MailScreen(mail: MailTriageService.shared)`.

- [ ] **Step 4: Sources lists Mail once**

In `ios/Loupe/Sources/SourcesView.swift` replace

```swift
                    ForEach(PhoneSource.allCases) { source in
                        PhoneSourceRow(sources: sources, source: source).id(source.id)
                    }
```

with

```swift
                    ForEach(PhoneSource.allCases.filter { $0 != .mail }) { source in
                        PhoneSourceRow(sources: sources, source: source).id(source.id)
                    }
                    // Mail is one place (spec D10): connect, sync, what was found and the actions, on its own screen.
                    NavigationLink {
                        MailScreen(mail: MailTriageService.shared)
                    } label: {
                        MailEntryCard(sources: sources, mail: MailTriageService.shared)
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("sources.mail")
```

and delete

```swift
                    NavigationLink {
                        MailTriageView(mail: MailTriageService.shared)
                    } label: {
                        MailTriageCard(mail: MailTriageService.shared)
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("sources.mail")
                    SourcesFootnote(text: "Sorts every email from the sources that are on (the sample now, your IMAP mailbox when it is on) into Loupe Station's categories and checks it for phishing: sender, reply address, mail-server checks and links. Mechanical, on this iPhone.")
```

- [ ] **Step 5: The UI tests go through the Mail screen**

`ios/LoupeUITests/MailTriageUITests.swift`: replace the doc line `/// Now → Mail triage shows …` and the lines from `app.launchArguments = ["-LoupeTab", "now", "-LoupeSkipOnboarding"]` through `card.tap()` with:

```swift
    /// Me → Mail shows the mailbox, what was found, and the sample's PayPal phishing first with its signals.
```

```swift
        app.launchArguments = ["-LoupeTab", "mail", "-LoupeSkipOnboarding"]
        app.launch()
        XCTAssertTrue(app.switches["sources.phone.mail.toggle"].waitForExistence(timeout: 20), "the mailbox is on the Mail screen")
        let found = app.descendants(matching: .any)["mail.found"]
        XCTAssertTrue(found.waitForExistence(timeout: 30))
        expectation(for: NSPredicate(format: "NOT (label BEGINSWITH %@)", "Phishing: 0"), evaluatedWith: found)
        waitForExpectations(timeout: 60)
```

(and rename the function to `testMailShowsThePaypalPhishingAndItsSignals`).

`ios/LoupeUITests/AssistUITests.swift`, `testFakeProviderDraftAppearsLabelledDraft`: replace

```swift
        app.launchArguments = ["-LoupeTab", "now", "-LoupeSkipOnboarding", "-LoupeEphemeralKeychain", "-LoupeFakeAssistant"]
        app.launch()
        let card = app.buttons["now.mail"]
        for _ in 0..<4 where !card.waitForExistence(timeout: 15) { app.swipeUp() }
        expectation(for: NSPredicate(format: "label CONTAINS %@", "Mail triage:"), evaluatedWith: card)
        waitForExpectations(timeout: 60)
        card.tap()
```

with

```swift
        app.launchArguments = ["-LoupeTab", "mail", "-LoupeSkipOnboarding", "-LoupeEphemeralKeychain", "-LoupeFakeAssistant"]
        app.launch()
        XCTAssertTrue(app.descendants(matching: .any)["mail.section.phishing"].waitForExistence(timeout: 60), "Mail triaged the sample")
```

`ios/LoupeUITests/SourcesUITests.swift`, `testSourcesListsEveryRowAndSampleStillWorks`: replace

```swift
        for id in ["photos", "files", "calendar", "contacts", "mail"] {
            let toggle = app.switches["sources.phone.\(id).toggle"]
            if !toggle.exists { app.swipeUp() }
            XCTAssertTrue(toggle.waitForExistence(timeout: 5), "no row for \(id)")
            XCTAssertEqual(toggle.value as? String, id == "mail" ? "0" : "1",
                           id == "mail" ? "Mail waits for a sign-in" : "\(id) is on by default")
        }
        XCTAssertTrue(app.descendants(matching: .any)["sources.phone.mail.online"].exists, "Mail is labelled Online")
```

with

```swift
        for id in ["photos", "files", "calendar", "contacts"] {
            let toggle = app.switches["sources.phone.\(id).toggle"]
            if !toggle.exists { app.swipeUp() }
            XCTAssertTrue(toggle.waitForExistence(timeout: 5), "no row for \(id)")
            XCTAssertEqual(toggle.value as? String, "1", "\(id) is on by default")
        }
        XCTAssertFalse(app.switches["sources.phone.mail.toggle"].exists, "Mail is one place: its switch is on the Mail screen")
        let mail = app.buttons["sources.mail"]
        for _ in 0..<4 where !mail.isHittable { app.swipeUp() }
        mail.tap()
        let toggle = app.switches["sources.phone.mail.toggle"]
        XCTAssertTrue(toggle.waitForExistence(timeout: 10))
        XCTAssertEqual(toggle.value as? String, "0", "Mail waits for a sign-in")
        XCTAssertTrue(app.descendants(matching: .any)["sources.phone.mail.online"].exists, "Mail is labelled Online")
```

and in `testMailSetupOffersGmailSignInAndGatesOutlook` replace `app.launchArguments = ["-LoupeFixtures", "-LoupeTab", "sources", "-LoupeSkipOnboarding"]` with `app.launchArguments = ["-LoupeFixtures", "-LoupeTab", "mail", "-LoupeSkipOnboarding"]`.

`ios/LoupeUITests/PersistenceUITests.swift`: replace

```swift
        XCTAssertEqual(phoneSwitch(app, "mail").value as? String, "0", "Mail is offered, not forced")
```

with

```swift
        let mailRow = app.buttons["sources.mail"]
        for _ in 0..<8 where !(mailRow.exists && mailRow.isHittable) { app.swipeUp() }
        mailRow.tap()
        XCTAssertEqual(phoneSwitch(app, "mail").value as? String, "0", "Mail is offered, not forced")
        app.navigationBars.buttons.element(boundBy: 0).tap()
```

- [ ] **Step 6: Run the Mail tests**

Run: the iOS test command with `-only-testing:LoupeTests/MailScreenTests -only-testing:LoupeUITests/MailTriageUITests -only-testing:LoupeUITests/SourcesUITests -only-testing:LoupeUITests/AssistUITests -only-testing:LoupeUITests/PersistenceUITests`
Expected: `** TEST SUCCEEDED **`.

- [ ] **Step 7: Commit**

```bash
cd /Users/bistrocloud/Documents/Loupe/jevistication
git add ios/Loupe/Mail/MailTriageView.swift ios/Loupe/Sources/SourcesView.swift ios/Loupe/Guard/GuardView.swift ios/Loupe/Shell/MeView.swift ios/LoupeTests/MailScreenTests.swift ios/LoupeUITests/MailTriageUITests.swift ios/LoupeUITests/SourcesUITests.swift ios/LoupeUITests/AssistUITests.swift ios/LoupeUITests/PersistenceUITests.swift
git commit -m "ios: Mail is one place: mailbox, what was found, triage and actions

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
git push
```

---

### Task 6: Me in the spec's groups

**Files:**
- Create: `ios/Loupe/Shell/MeModel.swift`, `ios/Loupe/Shell/AdvancedView.swift`
- Rewrite: `ios/Loupe/Shell/MeView.swift`
- Modify: `ios/Loupe/Shell/ShellRoutes.swift` (`MeRoute` gains `advanced`, `review`; `LaunchPlace.from("advanced")`)
- Modify: `ios/Loupe/Game/GameView.swift:296-316` (Play card ids `me.play.*`)
- Test: create `ios/LoupeTests/MeModelTests.swift`; modify `ios/LoupeTests/ShellRoutingTests.swift`; modify `ios/LoupeUITests/MascotKindUITests.swift`, `ModelSettingsUITests.swift`, `GameUITests.swift`

**Interfaces:**
- Consumes: `AppRouter.mePath`, `SourcesScreen`, `MailScreen`, `PlayCard`, `SortSection`, `AssistSettingsView`, `LayaModelView`, `ModelSettingsView`, `OnlineChecksView`, `DiagnosticsView`, `ReviewView`, `DeleteDataView`, `LicencesView`.
- Produces: `enum MeModel { static func readsLine(on:) -> String; static func mailLine(account:on:) -> String; static func assistantLine(enabled:ready:) -> String }`; `MeRoute.advanced`, `MeRoute.review`; `struct AdvancedView: View`; ids `me.reads`, `me.mail`, `me.assistant`, `me.model`, `me.review`, `me.export`, `me.erase`, `me.play.watch`, `me.play.human`, `me.play.last`, `me.advanced`, `me.modelSettings`, `me.onlineChecks`, `me.mascot`, `me.diagnostics`, `me.version`, `me.privacy`, `me.terms`, `me.licences`.

- [ ] **Step 1: Write the failing unit tests**

Create `ios/LoupeTests/MeModelTests.swift`:

```swift
import XCTest
@testable import Loupe

/// Me's row values (spec 2026-09-28 §3, mockup #6).
final class MeModelTests: XCTestCase {
    func testReadsLine() {
        XCTAssertEqual(MeModel.readsLine(on: 0), "none on")
        XCTAssertEqual(MeModel.readsLine(on: 1), "1 on")
        XCTAssertEqual(MeModel.readsLine(on: 5), "5 on")
    }

    func testMailLine() {
        XCTAssertEqual(MeModel.mailLine(account: nil, on: false), "No mailbox yet")
        XCTAssertEqual(MeModel.mailLine(account: "me@example.com", on: true), "me@example.com")
        XCTAssertEqual(MeModel.mailLine(account: "me@example.com", on: false), "me@example.com · off")
    }

    func testAssistantLineIsAPlaceholderForThePaidTier() {
        XCTAssertEqual(MeModel.assistantLine(enabled: false, ready: false), "Unlock")
        XCTAssertEqual(MeModel.assistantLine(enabled: true, ready: false), "Needs setup")
        XCTAssertEqual(MeModel.assistantLine(enabled: true, ready: true), "On · Online")
    }
}
```

In `ios/LoupeTests/ShellRoutingTests.swift` add to `testEveryOldTabNameHasAPlace`:

```swift
        XCTAssertEqual(LaunchPlace.from("advanced"), LaunchPlace(place: .me, me: .advanced))
```

- [ ] **Step 2: Run to verify it fails**

Run: the iOS test command with `-only-testing:LoupeTests/MeModelTests -only-testing:LoupeTests/ShellRoutingTests`
Expected: build FAILS with `cannot find 'MeModel' in scope`.

- [ ] **Step 3: Routes, model, Advanced**

In `ios/Loupe/Shell/ShellRoutes.swift` replace

```swift
    /// Mail as one place (spec D10).
    case mail
}
```

with

```swift
    /// Mail as one place (spec D10).
    case mail
    /// Me → Advanced: Model settings, online checks, mascot, diagnostics.
    case advanced
    /// Actions to review (the queue's history stays reachable when Needs attention has none open).
    case review
}
```

and add `        case "advanced": return LaunchPlace(place: .me, me: .advanced)` after the `case "me":` line in `LaunchPlace.from`.

Create `ios/Loupe/Shell/MeModel.swift`:

```swift
import Foundation

/// Me's row values, pure for the tests.
enum MeModel {
    static func readsLine(on: Int) -> String { on == 0 ? "none on" : "\(on) on" }

    static func mailLine(account: String?, on: Bool) -> String {
        guard let account else { return "No mailbox yet" }
        return on ? account : "\(account) · off"
    }

    /// The Personal Assistant row (spec §3): a placeholder in phase 1 that opens today's assistant settings; the
    /// paid tier (step 2 on `agent-tier`) replaces it.
    static func assistantLine(enabled: Bool, ready: Bool) -> String {
        guard enabled else { return "Unlock" }
        return ready ? "On · Online" : "Needs setup"
    }
}
```

Create `ios/Loupe/Shell/AdvancedView.swift`:

```swift
import SwiftUI

/// Me → Advanced (spec §3): the decision model's settings, the online phishing checks, the mascot and, in DEBUG and
/// TestFlight diagnostics builds, the device check.
struct AdvancedView: View {
    @ObservedObject private var online = OnlineChecksService.shared
    @ObservedObject private var settings = ModelSettingsService.shared
    @AppStorage(MascotKind.storageKey) private var mascotKind = MascotKind.default.rawValue

    var body: some View {
        List {
            NeonSection("Decision model") {
                NavigationLink { ModelSettingsView() } label: {
                    row(MS.t("title"), settings.settings.changed.isEmpty ? "defaults" : "\(settings.settings.changed.count) changed")
                }
                .accessibilityIdentifier("me.modelSettings")
                Text("Per-judgment calibration, the baseline and the threshold are on each judgment's Measure screen.")
                    .font(.footnote).foregroundStyle(Palette.inkSoft)
                    .fixedSize(horizontal: false, vertical: true)
            }
            NeonSection("Online phishing checks") {
                NavigationLink { OnlineChecksView(online: online) } label: {
                    row("Online phishing checks", online.settings.anyOn ? "On · Online" : "Off")
                }
                .accessibilityIdentifier("me.onlineChecks")
            }
            NeonSection("Appearance") {
                HStack {
                    Text("Mascot")
                    Spacer()
                    Picker("Mascot", selection: $mascotKind) {
                        ForEach(MascotKind.allCases) { Text($0.title).tag($0.rawValue) }
                    }
                    .pickerStyle(.segmented)
                    .fixedSize()
                    .accessibilityIdentifier("me.mascot")
                }
            }
            #if DEBUG || LOUPE_DIAGNOSTICS
            if MeView.showDiagnostics {
                NeonSection("Diagnostics") {
                    NavigationLink { DiagnosticsView() } label: { row("Diagnostics", "device check") }
                        .accessibilityIdentifier("me.diagnostics")
                }
            }
            #endif
        }
        .scrollContentBackground(.hidden)
        .neonGround()
        .navigationTitle("Advanced")
    }

    private func row(_ k: String, _ v: String) -> some View {
        HStack { Text(k); Spacer(); Text(v).font(Typeface.mono(13)).foregroundStyle(Palette.inkSoft).lineLimit(1) }
            .frame(minHeight: 44)
    }
}
```

- [ ] **Step 4: Rewrite Me**

Replace the whole of `ios/Loupe/Shell/MeView.swift` with:

```swift
import SwiftUI

/// Me (spec 2026-09-28 §3, mockup #6): everything that is not Home or Ask, one level down, in the spec's groups:
/// What Loupe reads (the sources; Mail as one place), Personal Assistant, Decision model, Your data, See Loupe think,
/// Advanced and About.
struct MeView: View {
    @EnvironmentObject private var launcher: GameLauncher
    @EnvironmentObject private var router: AppRouter
    @ObservedObject private var laya = LayaModel.shared
    @ObservedObject private var ledger = LedgerService.shared
    @ObservedObject private var judgments = JudgmentsService.shared
    @ObservedObject private var assist = AssistService.shared
    @ObservedObject private var sources = SourcesService.shared
    @ObservedObject private var review = ReviewService.shared
    @State private var exporting = false
    @State private var exportError: String?
    @State private var shared: SharedFile?
    @State private var confirmErase = false
    @State private var showErase = false
    private let engine = EngineInfo.load()

    var body: some View {
        NavigationStack(path: $router.mePath) {
            List {
                NeonSection("What Loupe reads") {
                    NavigationLink(value: MeRoute.reads) { row("Sources", MeModel.readsLine(on: sources.enabledCount)) }
                        .accessibilityIdentifier("me.reads")
                    NavigationLink(value: MeRoute.mail) {
                        row("Mail", MeModel.mailLine(account: sources.mailAccount?.username, on: sources.isPhoneEnabled(.mail)))
                    }
                    .accessibilityIdentifier("me.mail")
                }
                NeonSection("Personal Assistant") {
                    NavigationLink { AssistSettingsView(assist: assist) } label: {
                        row("Personal Assistant", MeModel.assistantLine(enabled: assist.config.enabled, ready: assist.isReady))
                    }
                    .accessibilityIdentifier("me.assistant")
                }
                NeonSection("Decision model") {
                    NavigationLink { LayaModelView() } label: { row("Decision model", layaStatus) }
                        .accessibilityIdentifier("me.model")
                }
                SortSection()
                NeonSection("Your data") {
                    Text(ledgerLine)
                        .font(.footnote).foregroundStyle(Palette.inkSoft)
                        .accessibilityIdentifier("me.ledger.count")
                    Text(judgments.overall().line)
                        .font(.footnote).foregroundStyle(Palette.ink)
                        .accessibilityIdentifier("me.agreement")
                    NavigationLink(value: MeRoute.review) { row("To review", "\(review.toReview)") }
                        .accessibilityIdentifier("me.review")
                    Button {
                        Task { await export() }
                    } label: {
                        HStack {
                            Text("Export my data")
                            Spacer()
                            if exporting { ProgressView() }
                        }
                        .frame(minHeight: 44)
                    }
                    .disabled(exporting || ledger.problem != nil)
                    .accessibilityIdentifier("me.export")
                    if let exportError {
                        Text(exportError).font(.footnote).foregroundStyle(Palette.inkSoft)
                    }
                    // Two steps (audit P1-4): this asks, then the sheet wants DELETE typed.
                    Button(role: .destructive) { confirmErase = true } label: {
                        Text("Delete all my Loupe data…").frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                    }
                    .accessibilityIdentifier("me.erase")
                }
                Section {
                    PlayCard { launcher.open($0) }
                        .listRowInsets(EdgeInsets())
                        .listRowBackground(Color.clear)
                } header: {
                    Text("See Loupe think")
                }
                NeonSection("Advanced") {
                    NavigationLink(value: MeRoute.advanced) { row("Advanced", "model settings, online checks") }
                        .accessibilityIdentifier("me.advanced")
                }
                NeonSection("About") {
                    row("Version", Self.version)
                        .accessibilityElement(children: .combine)
                        .accessibilityIdentifier("me.version")
                    row("LoupeKit", engine.linked ? "linked" : "missing")
                    row("Built-in judgments", "\(engine.builtInJudgments)")
                    row("Public Suffix List", engine.pslVersion)
                    Link(destination: Self.privacyURL) { linkRow("Privacy policy") }
                        .accessibilityIdentifier("me.privacy")
                    Link(destination: Self.termsURL) { linkRow("Terms of use") }
                        .accessibilityIdentifier("me.terms")
                    NavigationLink("Licences") { LicencesView() }
                        .accessibilityIdentifier("me.licences")
                }
            }
            .scrollContentBackground(.hidden)
            .neonGround()
            .navigationTitle("Me")
            .onAppear { judgments.load(); judgments.refreshLedger() }
            .confirmationDialog("Delete all your Loupe data?", isPresented: $confirmErase, titleVisibility: .visible) {
                Button("Continue", role: .destructive) { showErase = true }
                    .accessibilityIdentifier("me.erase.continue")
            } message: {
                Text("Your decisions, judgments, corrections, sources' indexes, the Spotted log, settings and saved sign-ins leave this iPhone for good. You can keep the decision model.")
            }
            .sheet(isPresented: $showErase) { DeleteDataView() }
            .sheet(item: $shared) { file in ShareSheet(items: [file.url]) }
            .navigationDestination(for: MeRoute.self) { route in
                switch route {
                case .reads: SourcesScreen(sources: SourcesService.shared)
                case .mail: MailScreen(mail: MailTriageService.shared)
                case .advanced: AdvancedView()
                case .review: ReviewView(review: ReviewService.shared)
                }
            }
        }
    }

    static let privacyURL = URL(string: "https://loupe-ai.com/privacy/")!
    static let termsURL = URL(string: "https://loupe-ai.com/terms/")!

    /// "0.1.0 (1)": the marketing version and the build.
    static var version: String {
        let info = Bundle.main.infoDictionary ?? [:]
        let v = info["CFBundleShortVersionString"] as? String ?? "?"
        let b = info["CFBundleVersion"] as? String ?? "?"
        return "\(v) (\(b))"
    }

    private func linkRow(_ title: String) -> some View {
        HStack {
            Text(title).foregroundStyle(Palette.ink)
            Spacer()
            Image(systemName: "arrow.up.forward.square").foregroundStyle(Palette.inkSoft).accessibilityHidden(true)
        }
        .frame(minHeight: 44)
        .contentShape(Rectangle())
    }

    /// Honest about where the history lives: nothing here is uploaded anywhere.
    private var ledgerLine: String {
        if let problem = ledger.problem { return "Decision ledger unavailable: \(problem)" }
        return "Decisions logged: \(ledger.count) · stored only on this iPhone"
    }

    /// F4: the desktop's lossless export (ledger, judgments, calibration, corrections), zipped.
    private func export() async {
        exporting = true
        exportError = nil
        defer { exporting = false }
        do {
            shared = SharedFile(url: try await ledger.export())
        } catch {
            exportError = "Export failed: \(error.localizedDescription)"
        }
    }

    #if DEBUG
    static let showDiagnostics = true
    #elseif LOUPE_DIAGNOSTICS
    /// A diagnostics Release build shows it only under TestFlight (a sandbox receipt), never from the App Store.
    static let showDiagnostics = Bundle.main.appStoreReceiptURL?.lastPathComponent == "sandboxReceipt"
    #endif

    private var layaStatus: String {
        switch laya.status {
        case .ready: return "on this phone"
        case .checking: return "checking"
        case .downloading(let p): return "downloading \(Int(p * 100))%"
        case .paused(let p): return "paused at \(Int(p * 100))%"
        case .verifying: return "checking"
        case .failed: return "needs attention"
        case .notInstalled: return "not on this phone"
        }
    }

    private func row(_ k: String, _ v: String) -> some View {
        HStack { Text(k); Spacer(); Text(v).font(Typeface.mono(13)).foregroundStyle(Palette.inkSoft).lineLimit(1) }
            .frame(minHeight: 44)
    }
}
```

In `ios/Loupe/Game/GameView.swift` (`PlayCard`) replace `.accessibilityIdentifier("now.play.last")` with `.accessibilityIdentifier("me.play.last")`, `.accessibilityIdentifier("now.play.watch")` with `.accessibilityIdentifier("me.play.watch")`, `.accessibilityIdentifier("now.play.human")` with `.accessibilityIdentifier("me.play.human")`, and the doc line `/// The Play card on Now: …` with `/// The Play card in Me → See Loupe think (spec D9): the point is the model deciding live, fast, on this phone, with nothing`.

- [ ] **Step 5: UI tests for what moved into Advanced and See Loupe think**

`ios/LoupeUITests/MascotKindUITests.swift`: replace both `"-LoupeTab", "me"` with `"-LoupeTab", "advanced"` and the doc line with `/// Me → Advanced → Mascot (epic #7 child 17): switching to the drone persists across relaunch.`

`ios/LoupeUITests/ModelSettingsUITests.swift`: replace `app.launchArguments = ["-LoupeTab", "me", "-LoupeSkipOnboarding", "-LoupeFixtures", "-LoupeLanguage", "en"]` with `app.launchArguments = ["-LoupeTab", "advanced", "-LoupeSkipOnboarding", "-LoupeFixtures", "-LoupeLanguage", "en"]`, and replace

```swift
        // Run the privacy check from Now.
        app.tabBars.buttons["Now"].tap()
        let card = app.buttons["now.privacy"]
```

with

```swift
        // Run the privacy check from Me → What Loupe reads (a second tap on Me returns to its first screen).
        app.tabBars.buttons["Me"].tap()
        let reads = app.buttons["me.reads"]
        XCTAssertTrue(reads.waitForExistence(timeout: 10))
        reads.tap()
        let card = app.buttons["sources.privacy"]
        for _ in 0..<8 where !(card.exists && card.isHittable) { app.swipeUp() }
```

`ios/LoupeUITests/GameUITests.swift`: replace both `"-LoupeTab", "now"` with `"-LoupeTab", "me"`, `app.buttons["now.play.human"]` with `app.buttons["me.play.human"]`, `app.buttons["now.play.watch"]` with `app.buttons["me.play.watch"]`, and after each `let play = …` / `let watch = …` line add `for _ in 0..<6 where !(play.exists && play.isHittable) { app.swipeUp() }` (with `watch` in the second test). Rename the tests `testPlayFromMeRunsAndPauses` and `testWatchFromMeLevelClimbsAndPauses`.

- [ ] **Step 6: Run the Me tests**

Run: the iOS test command with `-only-testing:LoupeTests/MeModelTests -only-testing:LoupeTests/ShellRoutingTests -only-testing:LoupeUITests/MascotKindUITests -only-testing:LoupeUITests/ModelSettingsUITests -only-testing:LoupeUITests/GameUITests -only-testing:LoupeUITests/SortUITests -only-testing:LoupeUITests/ModelDeliveryUITests`
Expected: `** TEST SUCCEEDED **`.

- [ ] **Step 7: Commit**

```bash
cd /Users/bistrocloud/Documents/Loupe/jevistication
git add ios/Loupe/Shell/MeModel.swift ios/Loupe/Shell/AdvancedView.swift ios/Loupe/Shell/MeView.swift ios/Loupe/Shell/ShellRoutes.swift ios/Loupe/Game/GameView.swift ios/LoupeTests/MeModelTests.swift ios/LoupeTests/ShellRoutingTests.swift ios/LoupeUITests/MascotKindUITests.swift ios/LoupeUITests/ModelSettingsUITests.swift ios/LoupeUITests/GameUITests.swift
git commit -m "ios: Me in the spec's groups: reads, assistant, model, data, see Loupe think, advanced, about

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
git push
```

---

### Task 7: The scenario journeys and the button audit in the new places

The 13 journeys keep their launch arguments (so they also exercise the `-LoupeTab` mapping), their relaunch checks and their `audit()` calls; only the way they move between places changes, plus new checks for Home.

**Files:**
- Modify: `ios/LoupeUITests/Scenarios/ScenarioSupport.swift` (place helpers)
- Rewrite: `ios/LoupeUITests/Scenarios/DeviceSmokeScenarios.swift`, `ios/LoupeUITests/Scenarios/MailPrivacyReviewScenarios.swift`
- Modify: `Scenarios/DeleteDataScenarios.swift`, `GameScenarios.swift`, `GuardScenarios.swift`, `ProtectionScenarios.swift`, `MeScenarios.swift`, `JudgmentsScenarios.swift`, `PacksScenarios.swift`, `UnsureQueueScenarios.swift`, `SourcesScenarios.swift`, `OnboardingScenarios.swift`

**Interfaces:**
- Consumes: every id listed in Tasks 3-6.
- Produces (in `ScenarioCase`): `func root(_ place: String)`, `func openProtection()`, `func openReads()`, `func openMail()`, `func openAdvanced()`.

- [ ] **Step 1: Run the journeys to see them fail on the old navigation**

Run: the iOS test command with `-only-testing:LoupeUITests/DeleteDataScenarios -only-testing:LoupeUITests/GameScenarios -only-testing:LoupeUITests/DeviceSmokeScenarios -only-testing:LoupeUITests/MeScenarios -only-testing:LoupeUITests/MailPrivacyReviewScenarios`
Expected: FAIL (`no Judgments tab`, `no Now tab`, `now.play.human` missing).

- [ ] **Step 2: Place helpers**

In `ios/LoupeUITests/Scenarios/ScenarioSupport.swift` insert after the `tab(_:)` function:

```swift
    // MARK: The three places

    /// A place's first screen: its tab tapped twice (a second tap on the selected tab goes back to its first screen).
    func root(_ place: String) {
        tab(place)
        tab(place)
    }

    /// Home → Protected → the Protection screen (today's Guard: the watchers, Run now, link protection).
    func openProtection() {
        root("Home")
        let card = button("home.protected")
        reveal(card)
        card.tap()
        XCTAssertTrue(any("guard.header").waitForExistence(timeout: 20), "the Protection screen opens")
    }

    /// Me → What Loupe reads.
    func openReads() {
        root("Me")
        let row = button("me.reads")
        reveal(row)
        row.tap()
        XCTAssertTrue(app.switches["sources.sample.toggle"].waitForExistence(timeout: 20), "What Loupe reads opens")
    }

    /// Me → Mail.
    func openMail() {
        root("Me")
        let row = button("me.mail")
        reveal(row)
        row.tap()
        XCTAssertTrue(app.switches["sources.phone.mail.toggle"].waitForExistence(timeout: 20), "Mail opens")
    }

    /// Me → Advanced.
    func openAdvanced() {
        root("Me")
        let row = button("me.advanced")
        reveal(row)
        row.tap()
        XCTAssertTrue(button("me.modelSettings").waitForExistence(timeout: 10), "Advanced opens")
    }
```

- [ ] **Step 3: The device smoke subset**

Replace the whole of `ios/LoupeUITests/Scenarios/DeviceSmokeScenarios.swift` with:

```swift
import XCTest

/// The device smoke subset (scheme `LoupeDeviceSmoke`, 2026-09-27): only non-destructive journeys, safe on the owner's
/// own iPhone with their real data. No fixtures, no fixture home and no reset flag of any kind (no
/// `-LoupeScenarioReset`, `-LoupeResetOnboarding`, `-LoupeClipboardReset`), never Delete all my Loupe data, never a
/// model download, no mailbox. What they change is small and put back (a game preference), or is a normal use of the
/// app (one link check in Recent checks). `-LoupeSkipOnboarding` only keeps the steps out of the way; it records
/// nothing. They run in the simulator suite too.
final class DeviceSmokeScenarios: ScenarioCase {
    private func launchSmoke(_ tab: String) {
        launch(["-LoupeSkipOnboarding", "-LoupeTab", tab])
        XCTAssertTrue(tabs.waitForExistence(timeout: 30), "the places")
    }

    /// Every place: the actions it needs are there, hittable and 44 pt, and nothing says "Laya".
    func testEveryPlaceHasItsActions() {
        launchSmoke("home")
        XCTAssertEqual(tabs.buttons.count, 3, "three places")
        for name in ["Home", "Ask", "Me"] { XCTAssertTrue(tabs.buttons[name].exists, "no \(name) place") }
        XCTAssertTrue(button("home.money").waitForExistence(timeout: 30))
        audit("smoke-home", ["guard.quick.checkLink", "guard.quick.checkCopied", "home.money", "home.documents", "home.protected"])

        openProtection()
        audit("smoke-protection", ["guard.quick.checkLink", "guard.runNow"])

        root("Ask")
        let mine = app.segmentedControls["judgments.section"].buttons["My judgments"]
        if mine.waitForExistence(timeout: 5) { mine.tap() }
        audit("smoke-ask", ["judgments.write", "judgments.packs"])
        let library = app.segmentedControls["judgments.section"].buttons["Library"]
        library.tap()
        XCTAssertTrue(app.textFields["library.search"].waitForExistence(timeout: 10), "the Library opens")
        audit("smoke-library")
        app.segmentedControls["judgments.section"].buttons["Web questions"].tap()
        XCTAssertTrue(any("web.template.currency").waitForExistence(timeout: 10), "Web questions open")
        audit("smoke-web", ["web.template.currency", "web.settings"])

        root("Me")
        audit("smoke-me", ["me.reads", "me.mail", "me.assistant", "me.model", "me.review", "me.export", "me.erase",
                           "me.play.watch", "me.play.human", "me.advanced", "me.licences", "me.privacy", "me.terms"])
        XCTAssertTrue(any("me.version").exists, "the version is shown")

        openReads()
        for id in ["photos", "files", "calendar", "contacts"] {
            let sw = app.switches["sources.phone.\(id).toggle"]
            reveal(sw)
            XCTAssertTrue(sw.exists, "What Loupe reads has \(id)")
        }
        audit("smoke-reads", ["sources.mail"])

        openMail()
        audit("smoke-mail", ["sources.phone.mail.setup"])

        openAdvanced()
        audit("smoke-advanced", ["me.modelSettings", "me.onlineChecks"])
    }

    /// Protection → Check a link with an ordinary website: no warning signs, and the check is in Recent checks after
    /// a relaunch.
    func testCheckAWebsiteAndItIsInRecentChecksAfterARelaunch() {
        launchSmoke("guard")
        let check = button("guard.quick.checkLink")
        XCTAssertTrue(check.waitForExistence(timeout: 20))
        check.tap()
        let field = any("protect.link.input")
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        field.tap()
        field.typeText("www.bbc.co.uk/news")
        button("protect.link.check").tap()
        let verdict = any("protect.verdict")
        XCTAssertTrue(verdict.waitForExistence(timeout: 20))
        XCTAssertEqual(verdict.value as? String, "safe")
        audit("smoke-check-link", ["protect.link.check"])

        relaunch()
        let again = button("guard.quick.checkLink")
        XCTAssertTrue(again.waitForExistence(timeout: 20))
        again.tap()
        let row = app.descendants(matching: .any).matching(NSPredicate(format: "identifier == 'protect.recent.row' AND label CONTAINS 'bbc.co.uk'")).firstMatch
        reveal(row)
        XCTAssertTrue(row.exists, "the check survived a relaunch")
    }

    /// A preference (the game's auto-fire) survives a relaunch, then is put back.
    func testAPreferenceSurvivesARelaunch() {
        launchSmoke("me")
        let play = button("me.play.human")
        reveal(play)
        play.tap()
        let fire = any("game.fire")
        XCTAssertTrue(fire.waitForExistence(timeout: 10))
        XCTAssertFalse(fire.frame.intersects(any("game.river").frame), "FIRE is never over the river")
        let autofire = app.switches["game.autofire"]
        reveal(autofire)
        let before = autofire.value as? String
        flip(autofire)
        let changed = before == "1" ? "0" : "1"
        waitFor(autofire, "value == %@", changed)

        relaunch()
        let play2 = button("me.play.human")
        reveal(play2)
        play2.tap()
        let autofire2 = app.switches["game.autofire"]
        reveal(autofire2)
        XCTAssertEqual(autofire2.value as? String, changed, "auto-fire survived a relaunch")
        flip(autofire2)
        waitFor(autofire2, "value == %@", before ?? "0")
        let close = button("game.close")
        reveal(close)
        close.tap()
    }
}
```

- [ ] **Step 4: Mail, privacy and Review from their new places**

Replace the whole of `ios/LoupeUITests/Scenarios/MailPrivacyReviewScenarios.swift` with:

```swift
import XCTest

/// Scenario 10: Mail, the privacy check and Review over the sample. Me → Mail: run again, open a finding, Mark safe the
/// phishing mail (the "Found in your mail" count drops). Me → What Loupe reads → Privacy check: Mark safe one finding
/// and Undo it, then Mark safe another. Me → To review: the privacy check proposes removing a duplicate copy (a real
/// pair of files, `-LoupeReviewDemo`); Approve removes it, Undo puts it back. Relaunch → every verdict is kept and the
/// review queue is as it was left. The relaunch leaves out `-LoupeReviewDemo`: it writes the duplicate pair again at
/// every launch.
final class MailPrivacyReviewScenarios: ScenarioCase {
    /// "Phishing: 2 · Needs a reply: 3 · …" → 2 for "Phishing"; "Privacy check: 4 findings" → 4 for "Privacy check".
    private func value(_ label: String, _ name: String) -> Int {
        guard let r = label.range(of: name + ": ") else { return -1 }
        return number(String(label[r.upperBound...]))
    }

    /// Me → Mail's "Found in your mail" card, once triage has read the sample.
    private func mailFound() -> XCUIElement {
        openMail()
        let found = any("mail.found")
        XCTAssertTrue(found.waitForExistence(timeout: 30))
        waitFor(found, "NOT (label BEGINSWITH %@)", "Phishing: 0 · Needs a reply: 0", timeout: 90, "mail triage read the sample")
        return found
    }

    /// Me → What Loupe reads → the privacy check's card, once the check has run.
    private func privacyCard() -> XCUIElement {
        openReads()
        let card = button("sources.privacy")
        reveal(card)
        waitFor(card, "label CONTAINS %@", "finding", timeout: 90, "the privacy check ran")
        return card
    }

    /// Me → To review.
    private func openReview() {
        root("Me")
        let row = button("me.review")
        reveal(row)
        XCTAssertTrue(row.exists, "Me offers the review queue")
        row.tap()
        XCTAssertTrue(app.staticTexts["review.notUnsure"].waitForExistence(timeout: 10))
    }

    private func close() {
        if app.navigationBars.buttons.firstMatch.exists { back() }
        if app.buttons["Done"].exists { app.buttons["Done"].tap() }
    }

    func testVerdictsAndAnApprovedActionSurviveARelaunch() {
        start("mail-review", ["-LoupeTab", "now", "-LoupeSkipOnboarding", "-LoupeModelState", "missing", "-LoupeReviewDemo"])

        // 1. Mail.
        let found = mailFound()
        let phishing = value(found.label, "Phishing")
        XCTAssertGreaterThan(phishing, 0)
        XCTAssertTrue(any("mail.section.phishing").waitForExistence(timeout: 30))
        audit("10-mail", ["mail.rerun", "mail.open", "mail.markSafe", "sources.phone.mail.setup"])
        button("mail.rerun").tap()
        XCTAssertTrue(any("mail.section.phishing").waitForExistence(timeout: 30), "a re-run shows the triage again")
        let open = button("mail.open")
        reveal(open)
        open.tap()
        XCTAssertTrue(any("item.header").waitForExistence(timeout: 10), "Open shows the email")
        XCTAssertTrue(button("item.open").isHittable, "Open is offered")
        audit("10-mail-item")
        close()
        let safe = button("mail.markSafe")
        reveal(safe)
        safe.tap()
        XCTAssertTrue(button("mail.undo").waitForExistence(timeout: 5), "Mark safe offers Undo")
        let foundAfter = any("mail.found")
        reveal(foundAfter)
        waitFor(foundAfter, "label BEGINSWITH %@", "Phishing: \(phishing - 1) ", timeout: 30, "marked safe: one fewer possible phishing")

        // 2. The privacy check: Mark safe + Undo, then Mark safe another.
        let privacy = privacyCard()
        let findings = value(privacy.label, "Privacy check")
        XCTAssertGreaterThan(findings, 1)
        privacy.tap()
        XCTAssertTrue(any("privacy.group.ids").waitForExistence(timeout: 30))
        let markSafe = button("privacy.safe")
        reveal(markSafe)
        audit("10-privacy", ["privacy.rerun", "privacy.safe"])
        markSafe.tap()
        let undo = button("privacy.undo")
        reveal(undo)
        XCTAssertTrue(undo.waitForExistence(timeout: 5))
        audit("10-privacy-undo", ["privacy.undo"])
        undo.tap()
        let safe2 = button("privacy.safe")
        reveal(safe2)
        safe2.tap()
        close()
        let privacyAfter = button("sources.privacy")
        reveal(privacyAfter)
        waitFor(privacyAfter, "label BEGINSWITH %@", "Privacy check: \(findings - 1) finding", timeout: 30, "one finding marked safe")

        // 3. Review: approve the proposed file change, undo it.
        openReview()
        let title = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "Remove the extra copy review-demo-receipt")).firstMatch
        XCTAssertTrue(title.waitForExistence(timeout: 90), "the privacy check proposes removing the duplicate")
        XCTAssertFalse(app.buttons["Confirm phishing"].firstMatch.exists, "an answered email is withdrawn from Review")
        let approve = app.buttons["Remove copy"].firstMatch
        reveal(approve)
        audit("10-review", ["review.reject"])
        approve.tap()
        let notice = app.staticTexts["review.notice"]
        XCTAssertTrue(notice.waitForExistence(timeout: 20))
        waitFor(notice, "label BEGINSWITH %@", "Done: Remove the extra copy", timeout: 20)
        let undoLast = button("review.undoLast")
        XCTAssertTrue(undoLast.waitForExistence(timeout: 10))
        audit("10-review-done", ["review.undoLast"])
        undoLast.tap()
        waitFor(notice, "label == %@", "Undone.", timeout: 20)
        sleep(2)
        let pendingAfterUndo = app.buttons["Remove copy"].firstMatch.exists
        let toReview = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH 'To review: '")).firstMatch
        let reviewCount = toReview.exists ? toReview.label : ""
        shot("10-review-undone")
        close()

        // 4. Relaunch: every verdict kept; the removed copy is not proposed again.
        relaunch(dropping: ["-LoupeReviewDemo"])
        XCTAssertEqual(value(mailFound().label, "Phishing"), phishing - 1, "Mark safe survived a relaunch")
        XCTAssertEqual(value(privacyCard().label, "Privacy check"), findings - 1, "the privacy verdict survived a relaunch")
        openReview()
        sleep(5)
        XCTAssertEqual(app.buttons["Remove copy"].firstMatch.exists, pendingAfterUndo, "the undone action's state survived a relaunch")
        let toReviewAfter = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH 'To review: '")).firstMatch
        if !reviewCount.isEmpty { XCTAssertEqual(toReviewAfter.label, reviewCount, "the review count survived a relaunch") }
        shot("10-review-relaunch")
    }
}
```

- [ ] **Step 5: The other journeys**

`DeleteDataScenarios.swift`:
- in `contactsSwitch()` replace `        tab("Sources")` with `        openReads()`;
- in `assertEmpty(_:)` replace `        tab("Judgments")` with `        root("Ask")`, `        tab("Guard")` with `        root("Home")`, and `        tab("Me")` with `        root("Me")`;
- in `testDeleteEverythingButTheModelThenRelaunch()` replace `        tab("Judgments")` with `        root("Ask")`, `        tab("Guard")` with `        root("Home")`, `        tab("Me")` with `        root("Me")`;
- after `        walkOnboarding()` that follows `confirm.tap()` insert:

```swift
        XCTAssertTrue(tabs.buttons["Home"].isSelected, "after the erase Loupe starts again on Home")
        XCTAssertTrue(button("home.money").waitForExistence(timeout: 20), "Home's first screen, not a screen left pushed")
```

`GameScenarios.swift`:
- in `openYouFly()` replace `        tab("Now")` with `        root("Me")` and `button("now.play.human")` with `button("me.play.human")`;
- in the test replace `start("game", ["-LoupeTab", "now",` with `start("game", ["-LoupeTab", "me",`, `audit("11-now-play", ["now.play.human", "now.play.watch"])` with `audit("11-me-play", ["me.play.human", "me.play.watch"])`, and `XCTAssertTrue(button("now.play.human").waitForExistence(timeout: 5), "Close returns to Now")` with `XCTAssertTrue(button("me.play.human").waitForExistence(timeout: 5), "Close returns to Me")`.

`GuardScenarios.swift`: before `        shot("07-relaunch-confirmed")` insert:

```swift
        back()
        // Home: Money and Documents open the same Subscriptions and Expiring, with the verdicts kept.
        root("Home")
        audit("07-home", ["home.money", "home.documents", "home.protected"])
        let money = button("home.money")
        reveal(money)
        money.tap()
        XCTAssertTrue(any("guard.subscriptions.total").waitForExistence(timeout: 10), "Money opens Subscriptions")
        XCTAssertEqual(charges(), before - 1, "the same census as on Protection")
        back()
        let documents = button("home.documents")
        reveal(documents)
        documents.tap()
        let passportRow = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "passport")).firstMatch
        reveal(passportRow)
        XCTAssertTrue(passportRow.exists, "Documents opens Expiring with the passport")
```

`ProtectionScenarios.swift`: in `guardBadge` replace `        if !row.exists { tab("Guard") }` with `        if !row.exists { root("Home") }` and the doc sentence "The unseen count that badges the Guard tab" with "The unseen count that badges Home".

`MeScenarios.swift`:
- `openModelSettings()`: replace `        tab("Me")\n        let open = button("me.modelSettings")` with `        openAdvanced()\n        let open = button("me.modelSettings")`;
- `openOnlineChecks()`: replace `        tab("Me")\n        let open = button("me.onlineChecks")` with `        openAdvanced()\n        let open = button("me.onlineChecks")`;
- replace `audit("09-me-top", ["me.model", "me.modelSettings", "me.export", "me.erase"])` with `audit("09-me-top", ["me.reads", "me.mail", "me.assistant", "me.model", "me.export", "me.erase", "me.advanced"])`;
- after the first `closeSettings()` insert `        root("Me")`;
- replace `        // 4. The writing assistant: off by default, its fields there; nothing saved, nothing sent.` with `        // 4. The Personal Assistant row (today's writing assistant): off by default; nothing saved, nothing sent.\n        root("Me")`;
- replace `        closeSettings()\n        XCTAssertEqual(app.switches["me.sort.toggle"].value as? String, "1", "sorting stays on")` with `        closeSettings()\n        root("Me")\n        let sortAgain = app.switches["me.sort.toggle"]\n        reveal(sortAgain)\n        XCTAssertEqual(sortAgain.value as? String, "1", "sorting stays on")`.

`JudgmentsScenarios.swift`: in `openMine()` replace `        tab("Judgments")` with `        root("Ask")`.

`PacksScenarios.swift`: replace `        tab("Judgments")` with `        root("Ask")`.

`UnsureQueueScenarios.swift`: replace the body of `openQueue()` with:

```swift
        root("Ask")
        let card = button("ask.needsYou")
        XCTAssertTrue(card.waitForExistence(timeout: 60), "Ask shows Needs you")
        card.tap()
        XCTAssertTrue(count.waitForExistence(timeout: 10))
        return number(count.label)
```

and the doc line "Now → Needs you → …" with "Ask → Needs you → …".

`SourcesScenarios.swift`: replace

```swift
        let mail = phoneSwitch("mail")
        XCTAssertEqual(mail.value as? String, "0", "Mail is offered, not forced")
        audit("02-sources-mail", ["sources.phone.mail.setup"])
```

with

```swift
        // Mail is one place: its entry opens the Mail screen, where the mailbox's switch and setup are.
        let mailEntry = button("sources.mail")
        reveal(mailEntry)
        mailEntry.tap()
        let mail = phoneSwitch("mail")
        XCTAssertEqual(mail.value as? String, "0", "Mail is offered, not forced")
        audit("02-sources-mail", ["sources.phone.mail.setup"])
        back()
```

and replace `        XCTAssertEqual(phoneSwitch("mail").value as? String, "0", "Mail stays off")` with

```swift
        let mailAgain = button("sources.mail")
        reveal(mailAgain)
        mailAgain.tap()
        XCTAssertEqual(phoneSwitch("mail").value as? String, "0", "Mail stays off")
        back()
```

`OnboardingScenarios.swift`: replace `        tab("Me")` with `        root("Me")`.

- [ ] **Step 6: Run the 13 journeys**

Run: the iOS test command with `-only-testing:LoupeUITests/OnboardingScenarios -only-testing:LoupeUITests/SourcesScenarios -only-testing:LoupeUITests/JudgmentsScenarios -only-testing:LoupeUITests/UnsureQueueScenarios -only-testing:LoupeUITests/PacksScenarios -only-testing:LoupeUITests/WebQuestionsScenarios -only-testing:LoupeUITests/GuardScenarios -only-testing:LoupeUITests/ProtectionScenarios -only-testing:LoupeUITests/MeScenarios -only-testing:LoupeUITests/MailPrivacyReviewScenarios -only-testing:LoupeUITests/GameScenarios -only-testing:LoupeUITests/DeleteDataScenarios -only-testing:LoupeUITests/DeviceSmokeScenarios -only-testing:LoupeUITests/HomeUITests`
Expected: `** TEST SUCCEEDED **`, with no `user-visible "Laya"` failure.

- [ ] **Step 7: Commit**

```bash
cd /Users/bistrocloud/Documents/Loupe/jevistication
git add ios/LoupeUITests/Scenarios/ScenarioSupport.swift ios/LoupeUITests/Scenarios/DeviceSmokeScenarios.swift ios/LoupeUITests/Scenarios/MailPrivacyReviewScenarios.swift ios/LoupeUITests/Scenarios/DeleteDataScenarios.swift ios/LoupeUITests/Scenarios/GameScenarios.swift ios/LoupeUITests/Scenarios/GuardScenarios.swift ios/LoupeUITests/Scenarios/ProtectionScenarios.swift ios/LoupeUITests/Scenarios/MeScenarios.swift ios/LoupeUITests/Scenarios/JudgmentsScenarios.swift ios/LoupeUITests/Scenarios/PacksScenarios.swift ios/LoupeUITests/Scenarios/UnsureQueueScenarios.swift ios/LoupeUITests/Scenarios/SourcesScenarios.swift ios/LoupeUITests/Scenarios/OnboardingScenarios.swift
git commit -m "ios: scenario journeys and button audit on Home, Ask and Me

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
git push
```

---

### Task 8: The remaining UI tests

**Files:**
- Modify: `ios/LoupeUITests/GetLayaUITests.swift:59-64`, `GuardUITests.swift:34-47, 137-157`, `LiveRunUITests.swift:32-40, 98-116`, `LoupeUITests.swift:11`, `PrivacyUITests.swift`, `ReviewPacksUITests.swift:7-14`, `RenameShotsUITests.swift:63-95`, `GameShotsUITests.swift:24-30`, `DataEraseUITests.swift:44-48`

**Interfaces:**
- Consumes: the ids of Tasks 3-6; `-LoupeTab sources|advanced|me|mail`.

- [ ] **Step 1: Run them to see the old navigation fail**

Run: the iOS test command with `-only-testing:LoupeUITests/GetLayaUITests -only-testing:LoupeUITests/GuardUITests -only-testing:LoupeUITests/LiveRunUITests -only-testing:LoupeUITests/LoupeUITests -only-testing:LoupeUITests/PrivacyUITests -only-testing:LoupeUITests/PrivacyShowWhereUITests -only-testing:LoupeUITests/ReviewPacksUITests -only-testing:LoupeUITests/DataEraseUITests`
Expected: FAIL (`now.privacy`, `now.review`, the `Now` / `Judgments` tab buttons are gone).

- [ ] **Step 2: Edit each test**

`GetLayaUITests.swift`: replace

```swift
        // A rules-only feature keeps working: the privacy check is on Now and opens.
        app.tabBars.buttons["Now"].tap()
        let privacy = app.buttons["now.privacy"]
```

with

```swift
        // A rules-only feature keeps working: the privacy check, in Me → What Loupe reads, opens.
        app.tabBars.buttons["Me"].tap()
        app.tabBars.buttons["Me"].tap()
        let reads = app.buttons["me.reads"]
        XCTAssertTrue(reads.waitForExistence(timeout: 5))
        reads.tap()
        let privacy = app.buttons["sources.privacy"]
```

`GuardUITests.swift`: replace the function `testFiveTabsWithGuardAndNoWebTab()` with

```swift
    func testThreePlacesWithProtectionBehindHome() {
        let app = launch("now")
        let tabs = app.tabBars.firstMatch
        XCTAssertTrue(tabs.waitForExistence(timeout: 10))
        XCTAssertEqual(tabs.buttons.allElementsBoundByIndex.map(\.label), ["Home", "Ask", "Me"])
        XCTAssertFalse(tabs.buttons["Guard"].exists)
        XCTAssertFalse(tabs.buttons["Web"].exists)
        // Home's Protected card opens today's Guard screen.
        let door = app.buttons["home.protected"]
        scrollTo(door, in: app)
        XCTAssertTrue(door.waitForExistence(timeout: 60))
        door.tap()
        XCTAssertTrue(tabs.buttons["Home"].isSelected)
        XCTAssertTrue(any(app, "guard.header").waitForExistence(timeout: 5))
    }
```

rename `testWebQuestionsAreInJudgments` to `testWebQuestionsAreInAsk`, and in `testTheOldWebLaunchArgumentOpensWebQuestions` replace `app.tabBars.buttons["Judgments"].isSelected` with `app.tabBars.buttons["Ask"].isSelected`. Update the file's doc comment first sentence to: `/// Protection (today's Guard screen, behind Home's Protected card) over the bundled sample (-LoupeFixtures): the three places,`.

`LiveRunUITests.swift`: in `startPrivacyCheck(_:)` replace

```swift
        app.launchArguments = ["-LoupeTab", "now", "-LoupeSkipOnboarding", "-LoupeLanguage", "en", "-LoupeSlowJobs"] + extra
        app.launch()
        let card = app.buttons["now.privacy"]
        // The Play card sits high on Now (2026-09-25): the privacy card may start below the fold.
```

with

```swift
        app.launchArguments = ["-LoupeTab", "sources", "-LoupeSkipOnboarding", "-LoupeLanguage", "en", "-LoupeSlowJobs"] + extra
        app.launch()
        let card = app.buttons["sources.privacy"]
        // The privacy check's card is below the sources in What Loupe reads.
```

in `testDarkThemeScreenshots()` replace `        XCTAssertTrue(app.buttons["now.privacy"].waitForExistence(timeout: 60))` with `        XCTAssertTrue(app.buttons["home.money"].waitForExistence(timeout: 60))`, `        save("01-now")` with `        save("01-home")`, and `app.launchArguments = ["-LoupeTab", "me", "-LoupeSkipOnboarding", "-LoupeLanguage", "en", "-LoupeFixtures"]` with `app.launchArguments = ["-LoupeTab", "advanced", "-LoupeSkipOnboarding", "-LoupeLanguage", "en", "-LoupeFixtures"]`.

`LoupeUITests.swift`: replace `        app.tabBars.buttons["Judgments"].tap()` with `        app.tabBars.buttons["Ask"].tap()` and the comment above it with `        // The Web tab's library is Ask → Web questions (owner decisions 2026-09-26 and 2026-09-28).`

`PrivacyUITests.swift`: in both tests replace `"-LoupeTab", "now"` with `"-LoupeTab", "sources"` and `let card = app.buttons["now.privacy"]` with `let card = app.buttons["sources.privacy"]`, and after each `XCTAssertTrue(card.waitForExistence(timeout: 60))` add `        for _ in 0..<8 where !card.isHittable { app.swipeUp() }`; change the first doc line to `/// Me → What Loupe reads → Privacy check shows the sample's SPECIMEN ID documents and duplicate receipts.` and rename the test `testReadsOpensPrivacyCheckWithSampleFindings`.

`ReviewPacksUITests.swift`: replace

```swift
        app.launchArguments = ["-LoupeTab", "now", "-LoupeSkipOnboarding", "-LoupeFixtures", "-LoupeReviewDemo"]
        app.launch()
        let card = app.buttons["now.review"]
        XCTAssertTrue(card.waitForExistence(timeout: 60))
```

with

```swift
        app.launchArguments = ["-LoupeTab", "me", "-LoupeSkipOnboarding", "-LoupeFixtures", "-LoupeReviewDemo"]
        app.launch()
        let card = app.buttons["me.review"]
        XCTAssertTrue(card.waitForExistence(timeout: 60))
        for _ in 0..<6 where !card.isHittable { app.swipeUp() }
```

and the doc line `/// Now → To review → …` with `/// Me → To review → …`.

`RenameShotsUITests.swift`: replace

```swift
        app.navigationBars.buttons.element(boundBy: 0).tap()
        let settings = app.buttons["me.modelSettings"]
```

with

```swift
        app.navigationBars.buttons.element(boundBy: 0).tap()
        let advanced = app.buttons["me.advanced"]
        for _ in 0..<8 where !(advanced.exists && advanced.isHittable) { app.swipeUp() }
        advanced.tap()
        let settings = app.buttons["me.modelSettings"]
```

replace `app = launch(["-LoupeModelState", "ready", "-LoupeSkipOnboarding", "-LoupeTab", "me", "-LoupeLanguage", "ar"])` with `app = launch(["-LoupeModelState", "ready", "-LoupeSkipOnboarding", "-LoupeTab", "advanced", "-LoupeLanguage", "ar"])`, and in the last block replace `"-LoupeTab", "now", "-LoupeLanguage", "en"])` with `"-LoupeTab", "me", "-LoupeLanguage", "en"])`, `app.buttons["now.play.watch"]` with `app.buttons["me.play.watch"]`, and the comment `// The Watch card on Now.` with `// The Watch card in Me → See Loupe think.`

`GameShotsUITests.swift`: replace `app.launchArguments = ["-LoupeSkipOnboarding", "-LoupeTab", "now"]` with `app.launchArguments = ["-LoupeSkipOnboarding", "-LoupeTab", "me"]`, `app.buttons["now.play.watch"]` with `app.buttons["me.play.watch"]`, and `// The Watch card on Now.` with `// The Watch card in Me → See Loupe think.`

`DataEraseUITests.swift`: replace

```swift
        // Back at the start (onboarding is skipped here, so the tabs, on Now), and the judgment is gone.
        XCTAssertTrue(tabs.buttons["Now"].waitForExistence(timeout: 20))
        let deadline = Date().addingTimeInterval(20)
        while !tabs.buttons["Now"].isSelected && Date() < deadline { usleep(200_000) }
        XCTAssertTrue(tabs.buttons["Now"].isSelected)
        tabs.buttons["Judgments"].tap()
```

with

```swift
        // Back at the start (onboarding is skipped here, so the places, on Home), and the judgment is gone.
        XCTAssertTrue(tabs.buttons["Home"].waitForExistence(timeout: 20))
        let deadline = Date().addingTimeInterval(20)
        while !tabs.buttons["Home"].isSelected && Date() < deadline { usleep(200_000) }
        XCTAssertTrue(tabs.buttons["Home"].isSelected)
        tabs.buttons["Ask"].tap()
```

- [ ] **Step 3: Run them**

Run: the iOS test command with `-only-testing:LoupeUITests/GetLayaUITests -only-testing:LoupeUITests/GuardUITests -only-testing:LoupeUITests/LiveRunUITests -only-testing:LoupeUITests/LoupeUITests -only-testing:LoupeUITests/PrivacyUITests -only-testing:LoupeUITests/PrivacyShowWhereUITests -only-testing:LoupeUITests/ReviewPacksUITests -only-testing:LoupeUITests/RenameShotsUITests -only-testing:LoupeUITests/GameShotsUITests -only-testing:LoupeUITests/DataEraseUITests -only-testing:LoupeUITests/ClipboardUITests -only-testing:LoupeUITests/KeyboardUITests -only-testing:LoupeUITests/LinkCheckUITests -only-testing:LoupeUITests/SafariExtensionUITests -only-testing:LoupeUITests/InboxUITests`
Expected: `** TEST SUCCEEDED **` (RenameShots and GameShots skip without `TEST_RUNNER_LOUPE_SHOTS`; that is their normal state).

- [ ] **Step 4: Commit**

```bash
cd /Users/bistrocloud/Documents/Loupe/jevistication
git add ios/LoupeUITests/GetLayaUITests.swift ios/LoupeUITests/GuardUITests.swift ios/LoupeUITests/LiveRunUITests.swift ios/LoupeUITests/LoupeUITests.swift ios/LoupeUITests/PrivacyUITests.swift ios/LoupeUITests/ReviewPacksUITests.swift ios/LoupeUITests/RenameShotsUITests.swift ios/LoupeUITests/GameShotsUITests.swift ios/LoupeUITests/DataEraseUITests.swift
git commit -m "ios: UI tests move through Home, Ask and Me

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
git push
```

---

### Task 9: Right-to-left and large text, the docs, the full gate, main

**Files:**
- Create: `ios/LoupeUITests/ShellAccessibilityUITests.swift`
- Modify: `ios/README.md` (the `-LoupeTab` line; the "Shell:" bullet), `docs/PREDEPLOY-CHECKLIST.md` (lines 43, 47, 54; a new section)

**Interfaces:**
- Consumes: `ScenarioCase.audit`, `root(_:)`, every first-screen id.

- [ ] **Step 1: Write the accessibility test**

Create `ios/LoupeUITests/ShellAccessibilityUITests.swift`:

```swift
import XCTest

/// Spec §8: Arabic right-to-left and Dynamic Type on the three places' first screens. Every action stays hittable and
/// at least 44 pt, and nothing says "Laya" (`audit`), with the layout mirrored and with Accessibility XL text.
final class ShellAccessibilityUITests: ScenarioCase {
    private let homeIds = ["guard.quick.checkLink", "guard.quick.checkCopied", "home.money", "home.documents", "home.protected"]
    private let meIds = ["me.reads", "me.mail", "me.assistant", "me.model", "me.export", "me.erase", "me.advanced", "me.licences"]

    private func walk(_ tag: String) {
        XCTAssertTrue(button("home.money").waitForExistence(timeout: 60))
        audit("\(tag)-home", homeIds)
        root("Ask")
        XCTAssertTrue(app.segmentedControls["judgments.section"].waitForExistence(timeout: 10))
        audit("\(tag)-ask", ["judgments.write", "judgments.packs"])
        root("Me")
        audit("\(tag)-me", meIds)
    }

    func testTheThreePlacesRightToLeft() {
        launch(["-LoupeFixtures", "-LoupeSkipOnboarding", "-LoupeModelState", "missing", "-LoupeTab", "home",
                "-AppleTextDirection", "YES", "-NSForceRightToLeftWritingDirection", "YES"])
        XCTAssertTrue(tabs.waitForExistence(timeout: 30))
        XCTAssertGreaterThan(tabs.buttons["Home"].frame.minX, tabs.buttons["Me"].frame.minX, "the tab bar is mirrored")
        walk("rtl")
    }

    func testTheThreePlacesAtAccessibilityXL() {
        launch(["-LoupeFixtures", "-LoupeSkipOnboarding", "-LoupeModelState", "missing", "-LoupeTab", "home",
                "-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXL"])
        XCTAssertTrue(tabs.waitForExistence(timeout: 30))
        walk("axxl")
    }
}
```

- [ ] **Step 2: Run it**

Run: the iOS test command with `-only-testing:LoupeUITests/ShellAccessibilityUITests`
Expected: `** TEST SUCCEEDED **`. If a target is under 44 pt or clipped, fix the view (add `.frame(minHeight: 44)`, `.fixedSize(horizontal: false, vertical: true)`) and re-run before continuing.

- [ ] **Step 3: The docs**

`ios/README.md`: in the "DEBUG-only launch arguments" line replace ``-LoupeTab now|guard|judgments|sources|me|web` (`web` = Judgments → Web questions)`` with ``-LoupeTab home|ask|me` (the old names still work: `now` = Home, `guard` = Home → Protection, `judgments` = Ask, `web` = Ask → Web questions, `sources` = Me → What Loupe reads; also `mail` = Me → Mail and `advanced` = Me → Advanced)``, and replace the whole bullet line that begins `- Shell: Now (` with:

```markdown
- Shell (2026-09-28, spec `docs/superpowers/specs/2026-09-28-loupe-home-ask-me-design.md`, step 1): three places. **Home** (Needs attention, Quick check, Money → Subscriptions, Documents → Expiring, Protected → the Protection screen, today's Guard), **Ask** (today's judgments: My judgments, the Library, Web questions; its badge is the Unsure count, and "Needs you" opens the queue) and **Me** (What Loupe reads, Mail as one place, Personal Assistant, Decision model, Your data, See Loupe think, Advanced, About).
```

`docs/PREDEPLOY-CHECKLIST.md`: replace `In the morning: Now shows a sorted card with real counts, and Me shows the last run.` with `In the morning: Me → Sort while charging shows the last run with real counts.`; replace `- [ ] Now → **Play**.` with `- [ ] Me → See Loupe think → **Play**.`; replace `- [ ] Now → Mail triage lists your mail.` with `- [ ] Me → Mail lists your mail, with what was found (phishing, needs a reply, subscriptions).`; and append:

```markdown

## 11. The three places (Home · Ask · Me)
- [ ] Loupe opens on **Home**. Needs attention shows only when something needs you; Quick check, Money, Documents and Protected follow, each opening its screen.
- [ ] Tap **Home** again on a pushed screen: back to Home's first screen. The same for Ask and Me.
- [ ] **Ask** shows a number when answers are waiting; "Needs you" opens the queue.
- [ ] **Me → What Loupe reads** lists every source; **Mail** is one entry and one screen (mailbox, found, actions).
- [ ] Settings → Accessibility → Larger Text at the largest size, and the phone in Arabic: every button on Home, Ask and Me is still tappable and readable.
```

- [ ] **Step 4: The full gate**

Run:

```bash
cd /Users/bistrocloud/Documents/Loupe/jevistication
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
./gradlew check
cd ios && xcodegen generate && xcodebuild test -project Loupe.xcodeproj -scheme Loupe -destination 'platform=iOS Simulator,name=iPhone 17 Pro Max' -derivedDataPath /Volumes/Sambawy/.loupe-agent-tmp/ios-shell/DerivedData
```

Expected: `BUILD SUCCESSFUL`, then `** TEST SUCCEEDED **` for the whole Loupe scheme (LoupeTests and LoupeUITests). Fix any failure and run the full suite again; do not merge on a red run.

- [ ] **Step 5: Commit, then move main (after the green gate)**

```bash
cd /Users/bistrocloud/Documents/Loupe/jevistication
git add ios/LoupeUITests/ShellAccessibilityUITests.swift ios/README.md docs/PREDEPLOY-CHECKLIST.md
git commit -m "ios: shell accessibility checks (RTL, Accessibility XL) and the docs for Home, Ask and Me

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
git push
git switch main
git pull --ff-only
git merge --ff-only ios-shell
git push origin main
git log --branches --not --remotes --oneline
```

Expected: the merge fast-forwards and the last command prints nothing. If `main` moved and the fast-forward is refused: `git switch ios-shell && git rebase main`, re-run Step 4 in full, then repeat this step.

---

## Self-review (done while writing)

- **Spec coverage (§10 step 1):** three places (Task 2); Home with Needs attention / Quick check / Money → Subscriptions / Documents → Expiring / Protected with "Turn on" and empty states (Task 3); the Ask button hosting today's judgments, Library, Write your own, Web questions under "Ask", badge = Unsure count, queue door (Tasks 2, 4); Me with What Loupe reads, Mail merged (D10), Personal Assistant placeholder, Decision model, Your data, See Loupe think (D9), Advanced, About (Tasks 5, 6); `-LoupeTab` compatibility (Task 2); scenario journeys, relaunch checks, button audit (Tasks 7, 8); RTL / Dynamic Type / 44 pt / VoiceOver (Global Constraints, Task 9); device smoke and PREDEPLOY checklist (Tasks 7, 9).
- **Gaps left to later steps, on purpose:** the large custom Ask button and a separately tappable badge (step 5); the composer, reference block and saved questions (steps 2-3); §6.2/§6.3 redesigns of Subscriptions and Expiring (step 4); reminders in Advanced (no reminders exist yet); the calmer look (step 5).
- **Placeholders:** none; every code step has its code.
- **Type consistency:** `Place`, `HomeRoute` (+`subscriptions`, `expiring` in Task 3), `MeRoute` (+`advanced`, `review` in Task 6), `LaunchPlace.from`, `AppRouter.select/go/openHome/openSpotted/openReads/openMail/openAsk/reset`, `GuardScreen(title:)`, `SourcesScreen(sources:)`, `MailScreen(mail:)`, `HomeView(path:openReads:openMail:)`, `HomeModel.attention/money/documents/protected`, `MeModel.readsLine/mailLine/assistantLine` are used with the same names in every task.
