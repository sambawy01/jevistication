import Foundation

/// Touch → the three keys the game reads (left, right, fire). Pure value logic, no UIKit, so the
/// mapping is unit-tested (`GameInputTests`).
///
/// - **Drag horizontally to steer:** the plane chases the finger's column. Inside a small deadband
///   it holds course, so it does not jitter left-right around the finger.
/// - **Hold to fire:** a touch that has lasted past the tap window, or moved past the tap travel,
///   fires while it stays down. Auto-fire (a toggle) fires whenever the gun is ready.
/// - **Tap to pause:** a touch that ends quickly and nearly where it began is a tap.
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
        var fire = false
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
        return touching && isTapSoFar(now: time)
    }

    mutating func cancel() {
        touching = false
        targetColumn = nil
    }

    private func isTapSoFar(now: TimeInterval) -> Bool {
        now - startTime <= tapMaxDuration && travel <= tapMaxTravel
    }

    /// The keys to hold this tick, for a plane at `playerX`.
    func keys(playerX: Double, autoFire: Bool, now: TimeInterval) -> Keys {
        var k = Keys()
        if touching, let target = targetColumn {
            let dx = target - playerX
            if dx < -deadband { k.left = true } else if dx > deadband { k.right = true }
        }
        k.fire = autoFire || (touching && !isTapSoFar(now: now))
        return k
    }
}

/// Screen x (points in a view `width` wide) → world column.
enum ColumnMapping {
    static func column(x: Double, width: Double, columns: Int) -> Double {
        guard width > 0 else { return Double(columns) / 2 }
        return min(max(x / width * Double(columns), 0), Double(columns))
    }
}
