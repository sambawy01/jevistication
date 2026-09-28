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
    /// Guard's Subscriptions section on its own screen (the Money card).
    case subscriptions
    /// Guard's Expiring soon timeline on its own screen (the Documents card).
    case expiring
    case review
    case privacy
}

/// Screens pushed on Me's stack.
enum MeRoute: Hashable {
    /// What Loupe reads (today's Sources screen).
    case reads
    /// Mail as one place (spec D10).
    case mail
    /// Me → Advanced: Model settings, online checks, mascot, diagnostics.
    case advanced
    /// Actions to review (the queue's history stays reachable when Needs attention has none open).
    case review
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
        case "advanced": return LaunchPlace(place: .me, me: .advanced)
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
