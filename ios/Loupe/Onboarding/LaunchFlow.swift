import Foundation

/// What the app shows before the tabs, in order (owner decision 2026-09-26):
///
/// 1. **Get the Loupe Decision Model** — while the model is not on the phone (every launch until it is,
///    owner rule 2026-09-25; "Later" moves on).
/// 2. **Permissions** — one step for Photos, Calendar, Contacts and notifications (`PermissionsStepView`). Once.
/// 3. **Protect** — Safari protection (the Guard card) and the clipboard setup slot. Once.
/// 4. The tabs, then the one-time game intro (`OnboardingView`, `onboarding.seen`) as a sheet over them.
///
/// Steps 2 and 3 are recorded as done when the user leaves them either way (Continue or Skip), each with its
/// own key, so a completed step never comes back and a launch that was cut short resumes where it stopped.
enum LaunchStep: Equatable {
    case getModel
    case permissions
    case protect
    case tabs
}

/// The onboarding flags in UserDefaults. "Done" means seen and left (continued or skipped).
struct OnboardingRecord {
    static let permissionsKey = "onboarding.permissions.done"
    static let protectKey = "onboarding.protect.done"
    /// The game intro (the key predates the other steps; kept so existing installs are not asked again).
    static let introKey = "onboarding.seen"
    static let allKeys = [permissionsKey, protectKey, introKey]

    let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) { self.defaults = defaults }

    var permissionsDone: Bool {
        get { defaults.bool(forKey: Self.permissionsKey) }
        nonmutating set { defaults.set(newValue, forKey: Self.permissionsKey) }
    }

    var protectDone: Bool {
        get { defaults.bool(forKey: Self.protectKey) }
        nonmutating set { defaults.set(newValue, forKey: Self.protectKey) }
    }

    var introSeen: Bool {
        get { defaults.bool(forKey: Self.introKey) }
        nonmutating set { defaults.set(newValue, forKey: Self.introKey) }
    }

    /// Forgets every onboarding step (DEBUG `-LoupeResetOnboarding`, UI tests only).
    func reset() { Self.allKeys.forEach { defaults.removeObject(forKey: $0) } }
}

enum LaunchFlow {
    /// The first thing a launch shows. A test that skips onboarding, or a launch straight into the game, goes
    /// to the tabs.
    static func first(ready: Bool, launch: LaunchOptions, record: OnboardingRecord) -> LaunchStep {
        guard !launch.skipOnboarding, launch.game == nil else { return .tabs }
        if !ready { return .getModel }
        return setup(record)
    }

    /// Where a step leads once the user leaves it. The caller records the step as done first.
    static func after(_ step: LaunchStep, record: OnboardingRecord) -> LaunchStep {
        switch step {
        case .getModel: return setup(record)
        case .permissions: return record.protectDone ? .tabs : .protect
        case .protect, .tabs: return .tabs
        }
    }

    /// Whether the one-time game intro follows, over the tabs.
    static func showsIntro(launch: LaunchOptions, record: OnboardingRecord) -> Bool {
        !launch.skipOnboarding && launch.game == nil && !record.introSeen
    }

    private static func setup(_ record: OnboardingRecord) -> LaunchStep {
        if !record.permissionsDone { return .permissions }
        if !record.protectDone { return .protect }
        return .tabs
    }
}
