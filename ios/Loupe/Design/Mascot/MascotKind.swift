import Foundation

/// Which mascot the app shows (epic #7 child 17). The robot is the default; the drone is Loupe
/// Station's orb-drone, offered as an alternate in Me → Appearance → Mascot. Every call site keeps
/// using `MascotView(state:)`; the view reads this setting and switches live.
enum MascotKind: String, CaseIterable, Identifiable, Equatable {
    case robot, drone

    static let storageKey = "mascot.kind"
    static let `default` = MascotKind.robot

    var id: String { rawValue }
    var title: String { self == .robot ? "Robot" : "Drone" }

    /// The persisted choice; anything unknown or missing reads as the default (the robot).
    static func stored(in defaults: UserDefaults = .standard) -> MascotKind {
        defaults.string(forKey: storageKey).flatMap(MascotKind.init(rawValue:)) ?? .default
    }

    static func store(_ kind: MascotKind, in defaults: UserDefaults = .standard) {
        defaults.set(kind.rawValue, forKey: storageKey)
    }
}
