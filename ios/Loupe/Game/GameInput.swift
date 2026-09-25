import CoreGraphics
import Foundation

/// Touch → the three keys the game reads (left, right, fire). Pure value logic, no UIKit, so the
/// mapping is unit-tested (`GameInputTests`).
///
/// Steering and firing are separate controls, tracked on separate touches (owner's report
/// 2026-09-25: firing on every drag destroyed fuel depots while the player was only steering):
///
/// - **Drag (or hold) anywhere on the river to steer:** the plane chases the finger's column. Inside
///   a small deadband it holds course, so it does not jitter left-right around the finger. A steering
///   touch never fires, however long it lasts or however far it travels.
/// - **FIRE button to fire:** a second finger on the button (in the bar under the river, right) fires while it stays down,
///   at the gun's cooldown. It is a deliberate choice, so it fires even at a fuel depot.
/// - **Auto-fire (a toggle)** fires whenever the gun is ready, except while a live fuel depot is in
///   the line of fire (`FireControl`).
/// - **Tap to pause:** a steering touch that ends quickly and nearly where it began is a tap.
struct TouchSteering: Equatable {
    /// Columns either side of the finger inside which the plane holds course.
    var deadband: Double = 0.35
    /// Longest touch that still counts as a tap, seconds.
    var tapMaxDuration: TimeInterval = 0.22
    /// Furthest a tap may travel, in points.
    var tapMaxTravel: Double = 12

    private(set) var touching = false
    private(set) var targetColumn: Double?
    private var startTime: TimeInterval = 0
    private var startX: Double = 0
    private var travel: Double = 0

    struct Keys: Equatable {
        var left = false
        var right = false
    }

    /// A touch began at `column` (world columns) / `x` (points) at `time`.
    mutating func began(column: Double, x: Double, time: TimeInterval) {
        touching = true
        targetColumn = column
        startTime = time
        startX = x
        travel = 0
    }

    mutating func moved(column: Double, x: Double) {
        guard touching else { return }
        targetColumn = column
        travel = max(travel, abs(x - startX))
    }

    /// The touch lifted. Returns true when it was a tap.
    @discardableResult
    mutating func ended(time: TimeInterval) -> Bool {
        defer {
            touching = false
            targetColumn = nil
        }
        return touching && time - startTime <= tapMaxDuration && travel <= tapMaxTravel
    }

    mutating func cancel() {
        touching = false
        targetColumn = nil
    }

    /// The steering keys to hold this tick, for a plane at `playerX`. Never fire.
    func keys(playerX: Double) -> Keys {
        var k = Keys()
        if touching, let target = targetColumn {
            let dx = target - playerX
            if dx < -deadband { k.left = true } else if dx > deadband { k.right = true }
        }
        return k
    }
}

/// Whether the gun fires this tick.
enum FireControl {
    /// `button`: the FIRE button is held (or VoiceOver pressed it this tick) — the player chose to
    /// fire, so it fires even at a depot. Otherwise auto-fire fires unless a live depot is in the line
    /// of fire; `depotInLine` is asked only then (it scans the world), and in the app it is the shared
    /// `Mechanics.depotInLineOfFire`, the same check the pilots' candidate set uses.
    static func fire(button: Bool, autoFire: Bool, depotInLine: () -> Bool) -> Bool {
        if button { return true }
        return autoFire && !depotInLine()
    }
}

/// Which control a touch drives, fixed when it begins: a touch that starts on the FIRE button fires
/// until it lifts (sliding off the button does not steer), one that starts on the river steers until
/// it lifts (sliding onto the button does not fire). One steering touch and one fire touch at a time;
/// any further finger is ignored until it lifts. Generic over the touch's identity so it is unit-tested
/// without UIKit.
struct TouchRouter<ID: Hashable>: Equatable {
    enum Role: Equatable { case steer, fire, ignored }

    private(set) var steer: ID?
    private(set) var fire: ID?

    /// A touch began; `onFireButton` when it landed on the FIRE button.
    mutating func began(_ id: ID, onFireButton: Bool) -> Role {
        if onFireButton {
            guard fire == nil else { return .ignored }
            fire = id
            return .fire
        }
        guard steer == nil else { return .ignored }
        steer = id
        return .steer
    }

    func role(of id: ID) -> Role {
        if id == steer { return .steer }
        if id == fire { return .fire }
        return .ignored
    }

    /// A touch lifted (or was cancelled); returns the role it had.
    @discardableResult
    mutating func ended(_ id: ID) -> Role {
        let r = role(of: id)
        if r == .steer { steer = nil }
        if r == .fire { fire = nil }
        return r
    }

    mutating func reset() {
        steer = nil
        fire = nil
    }
}

/// The FIRE button in the control bar under the river (never over the river: it covered the plane).
/// The drawn button and its touch view's hit area both come from here, so they cannot drift apart.
enum FireButtonLayout {
    /// The button's diameter, points (at least 64 for a thumb).
    static let size: CGFloat = 68
    /// Extra touch area around the drawn button, so a thumb that lands just off it still fires.
    static let slop: CGFloat = 8
    /// The touch view's side: the button plus its slop all round.
    static var touchSize: CGFloat { size + 2 * slop }

    /// The drawn button, centred in a touch view's `bounds` (UIKit coordinates).
    static func frame(in bounds: CGRect) -> CGRect {
        CGRect(x: bounds.midX - size / 2, y: bounds.midY - size / 2, width: size, height: size)
    }

    /// Whether a touch at `point` lands on the button (round, with the slop).
    static func contains(_ point: CGPoint, in bounds: CGRect) -> Bool {
        let f = frame(in: bounds)
        let r = size / 2 + slop
        let dx = point.x - f.midX, dy = point.y - f.midY
        return dx * dx + dy * dy <= r * r
    }
}

/// Screen x (points in a view `width` wide) → world column.
enum ColumnMapping {
    static func column(x: Double, width: Double, columns: Int) -> Double {
        guard width > 0 else { return Double(columns) / 2 }
        return min(max(x / width * Double(columns), 0), Double(columns))
    }
}
