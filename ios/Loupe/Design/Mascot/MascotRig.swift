import Foundation

/// What the app asks the robot to be doing. Views pass one of these to `MascotView(state:)`.
enum MascotState: String, CaseIterable, Equatable {
    case idle, greeting, watching, scanning, thinking, found, happy, empty
    /// Older name for `empty` (the shrug); kept so call sites read naturally.
    static let shrug = MascotState.empty
}

/// The pose the rig is driven by (ported from the web prototype's Lumi `target()`). The body
/// pose ("wave", "unsure") is not always the app state: greeting waves, thinking is the prototype's
/// "unsure" (hand to chin, head tilt, chest dots), and a tap waves while happy.
enum MascotPoseName: String, Equatable {
    case idle, wave, watching, scanning, unsure, found, happy, empty

    /// Which face the visor draws.
    enum Face: Equatable { case open, happy, scanBar, unsure, empty, big }
    var face: Face {
        switch self {
        case .found, .happy, .wave: return .happy
        case .scanning: return .scanBar
        case .unsure: return .unsure
        case .empty: return .empty
        case .idle, .watching: return .open
        }
    }
}

/// Every channel of the rig, in radians or scene units. Arm A is the robot's left arm
/// (screen right, the waving arm), arm B its right. o = out (abduct), f = forward, e = elbow,
/// x = elbow twist forward. `sh` shrugs the shoulders, `kn` bends the knees.
struct MascotPose: Equatable {
    var y = 0.0, sway = 0.0, lean = 0.0, twist = 0.0
    var hx = 0.0, hy = 0.0, hz = 0.0
    var aAo = 0.14, aAf = 0.0, aAe = 0.12, aAx = 0.0
    var aBo = 0.14, aBf = 0.0, aBe = 0.12, aBx = 0.0
    var sh = 0.0, kn = 0.0

    static let rest = MascotPose()

    static let channels: [WritableKeyPath<MascotPose, Double>] = [
        \.y, \.sway, \.lean, \.twist, \.hx, \.hy, \.hz,
        \.aAo, \.aAf, \.aAe, \.aAx, \.aBo, \.aBf, \.aBe, \.aBx, \.sh, \.kn,
    ]

    /// Eases every channel toward `target` by fraction `k` (0 keeps, 1 snaps).
    func blended(toward target: MascotPose, k: Double) -> MascotPose {
        var out = self
        let k = min(1, max(0, k))
        for c in Self.channels { out[keyPath: c] = self[keyPath: c] + (target[keyPath: c] - self[keyPath: c]) * k }
        return out
    }

    /// Frame-rate independent easing fraction: 1 - e^(-dt·rate). Reduce Motion snaps (1).
    static func easing(dt: Double, rate: Double, reduceMotion: Bool) -> Double {
        reduceMotion ? 1 : 1 - exp(-max(0, dt) * rate)
    }

    func distance(to o: MascotPose) -> Double {
        Self.channels.map { abs(self[keyPath: $0] - o[keyPath: $0]) }.max() ?? 0
    }

    /// The target pose for `name` at time `t`, `stateT` seconds into the state. With
    /// `reduceMotion` every time-varying term is zero, so each pose is static.
    static func target(_ name: MascotPoseName, t: Double, stateT: Double, look: SIMD2<Double>,
                       scanX: Double?, idleWave: Bool, reduceMotion R: Bool) -> MascotPose {
        var P = MascotPose()
        let w: Double = R ? 0 : 1
        switch name {
        case .wave:
            P.hz = -0.14; P.hy = 0.12; P.aAo = 1.25; P.aAe = 1.35 + w * 0.38 * sin(t * 9); P.aAf = 0.15; P.sway = w * 0.04 * sin(t * 2)
        case .watching:
            P.hy = look.x * 0.7; P.hx = look.y * 0.45; P.lean = 0.04
        case .scanning:
            P.lean = 0.2; P.hx = 0.2
            P.hy = scanX.map { ($0 - 0.5) * 0.7 * w } ?? w * 0.3 * sin(t * 2.2)
            P.aAf = 1.25; P.aAo = 0.25; P.aAe = 0.2; P.aAx = -0.2; P.aBo = 0.2; P.aBf = 0.2; P.aBe = 0.3; P.kn = 0.08
        case .unsure:
            P.hz = 0.26; P.hx = -0.18; P.hy = -0.2; P.aBf = 1.1; P.aBo = 0.05; P.aBx = 2.05; P.aBe = 0.35; P.aAo = 0.18
            P.twist = -0.08; P.sway = w * 0.03 * sin(t * 1.1)
        case .found:
            let k = R ? 0 : max(0, sin(min(1, stateT / 0.5) * .pi))
            P.y = k * 0.16; P.aAo = 2.55; P.aBo = 2.55; P.aAe = 0.3; P.aBe = 0.3; P.hx = -0.14
            P.kn = R ? 0 : (stateT < 0.12 ? 0.3 : 0)
        case .happy:
            P.hx = R ? 0.12 : 0.12 + 0.18 * sin(t * 9) * max(0, 1 - stateT / 1.2)
            P.aAo = 0.7; P.aAf = 0.9; P.aAe = 1.5; P.aAx = 0.4; P.hz = -0.08
        case .empty:
            P.hz = 0.22; P.aAo = 0.62; P.aBo = 0.62; P.aAe = 1.05; P.aBe = 1.05; P.aAf = 0.35; P.aBf = 0.35; P.sh = 0.035
        case .idle:
            P.sway = w * 0.035 * sin(t * 0.9)
            P.hy = w * (0.28 * sin(t * 0.37) + look.x * 0.35)
            P.hx = w * (0.06 * sin(t * 0.53) + look.y * 0.2)
            if !R && idleWave { P.aAo = 1.15; P.aAe = 1.2 + 0.3 * sin(t * 9); P.hz = -0.1 }
        }
        return P
    }
}

/// Everything the renderer needs for one frame besides the pose.
struct MascotFrame: Equatable {
    var pose: MascotPose
    var face: MascotPoseName.Face
    var eyeOpen: Double     // 1 open, ~0.12 mid-blink
    var look: SIMD2<Double>
    var scanU: Double       // 0...1 scan-bar position
    var chest: Double       // 0...1 chest slot glow
    var tips: Double        // 0...1 antenna tip glow
    var breath: Double      // -1...1
    var chestDots: Int      // thinking: 0...3 dots lit
}

/// The state machine, pure Swift: time, blinking, idle glances, the tap wave, transient states,
/// and eased blending between poses. `MascotSceneView` calls `step(dt:)` once per rendered frame.
struct MascotRig {
    private(set) var state: MascotState = .idle
    private(set) var stateT = 0.0
    private(set) var t = 0.0
    private(set) var current = MascotPose.rest
    var reduceMotion = false
    /// Where to look, each axis -1...1 (x right, y down), e.g. the last touch.
    var lookTarget = SIMD2<Double>(0, 0)
    var scanX: Double?

    private var look = SIMD2<Double>(0, 0)
    private var blinkT: Double
    private var blink = 0.0
    private var waveT: Double
    private var tapT = 0.0          // > 0 while the tap's wave + happy plays
    private var random: () -> Double

    /// How long the one-shot part of a state lasts before the body settles (face stays).
    static let settleAfter: [MascotState: Double] = [.greeting: 2.8, .found: 2.6, .happy: 1.8]
    static let tapDuration = 2.2

    init(random: @escaping () -> Double = { Double.random(in: 0..<1) }) {
        self.random = random
        blinkT = 3 + random() * 3
        waveT = 8 + random() * 6
    }

    mutating func set(_ s: MascotState) {
        guard s != state else { return }
        state = s; stateT = 0
    }

    /// Tap: wave and be happy for a moment, then go back to the app's state.
    mutating func tap() { tapT = Self.tapDuration; stateT = 0 }

    var isTapping: Bool { tapT > 0 }

    /// The body pose for the current moment.
    var poseName: MascotPoseName {
        if tapT > 0 { return .wave }
        let settled = Self.settleAfter[state].map { stateT > $0 } ?? false
        switch state {
        case .idle: return .idle
        case .greeting: return settled ? .idle : .wave
        case .watching: return .watching
        case .scanning: return .scanning
        case .thinking: return .unsure
        case .found: return settled ? .idle : .found
        case .happy: return settled ? .idle : .happy
        case .empty: return .empty
        }
    }

    /// The face may differ from the body once a one-shot state settles (found keeps ^ ^ eyes),
    /// and greeting shows the reference render's face: open eyes, big smile.
    var face: MascotPoseName.Face {
        if tapT > 0 { return .happy }
        switch state {
        case .found, .happy: return .happy
        case .greeting: return .big
        default: return poseName.face
        }
    }

    /// Whether anything would move this frame. Reduce Motion with a settled pose needs no frames.
    var isAnimating: Bool {
        if !reduceMotion { return true }
        return tapT > 0 || current.distance(to: target()) > 1e-4
    }

    func target() -> MascotPose {
        MascotPose.target(poseName, t: t, stateT: stateT, look: reduceMotion ? .zero : look,
                          scanX: scanX, idleWave: waveT < 0, reduceMotion: reduceMotion)
    }

    mutating func step(dt rawDt: Double) -> MascotFrame {
        let dt = min(0.05, max(0, rawDt))
        let R = reduceMotion
        t += dt; stateT += dt
        if tapT > 0 { tapT -= dt; if tapT <= 0 { stateT = 0 } }
        if !R { look += (lookTarget - look) * min(1, dt * 5) } else { look = .zero }
        let ps = poseName
        if state == .idle && tapT <= 0 && !R {
            waveT -= dt
            if waveT < -1.6 { waveT = 9 + random() * 8 }
        }
        let k = MascotPose.easing(dt: dt, rate: ps == .found ? 14 : 7, reduceMotion: R)
        current = current.blended(toward: target(), k: k)

        let face = self.face
        var chest = 0.55 + 0.45 * sin(t * 2), tips = 0.0, dots = 0
        switch face {
        case .scanBar: chest = 0.5 + 0.5 * sin(t * 14); tips = 0.5 + 0.5 * sin(t * 9)
        case .unsure:
            let phase = Int(t.truncatingRemainder(dividingBy: 2.4) / 0.8)
            chest = 0.3; dots = min(3, phase + 1)
        case .happy: chest = 1; tips = 0.6
        default: break
        }
        if R {
            chest = face == .scanBar ? 1 : 0.8
            tips = face == .scanBar ? 1 : 0
            dots = face == .unsure ? 3 : 0
        }
        var eye = 1.0
        if !R && face != .scanBar {
            blinkT -= dt
            if blinkT < 0 { blink = 0.11; blinkT = 3 + random() * 3 }
            if blink > 0 { blink -= dt; eye = 0.12 }
        }
        let scanU = scanX ?? (R ? 0.5 : sin(t * 3.2) * 0.5 + 0.5)
        return MascotFrame(pose: current, face: face, eyeOpen: eye, look: look, scanU: scanU,
                           chest: chest, tips: tips, breath: R ? 0 : sin(t * 2.1), chestDots: dots)
    }
}
