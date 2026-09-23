import Foundation
import simd

// The drone mascot's pure half (epic #7 child 17). Ported from Loupe Station's mascot
// (~/laya-studio laya_studio/static/js/mascot.js + static/mascot.css @ ea7697a, the owner's repo):
// a hovering orb-drone with a glass visor, pill eyes, an orbit ring and a beacon, whose mood is its
// glow colour. Station's timings, eye travel, tilt and colours are kept; SceneKit draws it
// (DroneScene.swift) behind the same `MascotView(state:)` as the robot.
//
// Station has six moods; Loupe has eight states. The mapping (DroneLook.of):
//
//   Loupe state  Station mood          Drone behaviour
//   idle         idle                  blue glow, 4.8 s hover bob, blinks and glances
//   greeting     booting -> happy      0.8 s power-up (amber, visor scan line, flicker, dashed ring,
//                                      blinking beacon), then the happy hop; settles to idle, ^ ^ eyes kept
//   watching     (none: idle+glanceAt) idle glow; eyes and a slight body turn follow `lookAt`
//   scanning     thinking (say:scanning) fast 1.4 s bob, long-dash ring spinning, squinted eyes, and
//                                      booting's scan line sweeping the visor (follows scanX)
//   thinking     thinking              fast bob, long-dash ring spinning, eyes scanning left-right
//   found        happy                 green, ^ ^ eyes, hop + halo flash; settles, stays green
//   happy        happy                 green, ^ ^ eyes, a smaller hop, no flash; settles, stays green
//   empty        unsure                amber, tilted 11°, one eye half shut, eyes up-right
//   (tap)        happy (2.2 s reaction) as Station's REACTION_MS.happy
//   —            error                 unused: Loupe has no error state (sad eyes are drawable)
//
// Reduce Motion: as Station's `prefers-reduced-motion` block, a static pose; mood reads through
// colour, eyes and tilt (no bob, hop, blink, spin; the scan line rests at 6 of 15 px).

/// Station's moods (mascot.js `ALL`); the drone's colour and face vocabulary.
enum DroneMood: String, CaseIterable, Equatable { case booting, idle, thinking, happy, unsure, error }

enum DroneEyes: Equatable { case open, happy, sad }

/// The orbit ring's stroke: solid, booting's fine 3/5 dashes, thinking's long 16/34 dashes.
enum DroneRing: Equatable { case solid, fine, long }

/// Station's mascot.css colours, light and dark (`--m-*`), as 0xRRGGBB.
enum DronePalette {
    static func glow(_ m: DroneMood, dark: Bool) -> UInt32 {
        switch m {
        case .idle, .thinking: return dark ? 0x66C9FF : 0x4D7DFF   // --m-idle
        case .happy: return dark ? 0x5FE0A5 : 0x1FB574             // --m-ok
        case .booting, .unsure: return dark ? 0xF5B85C : 0xF09A1A  // --m-warn
        case .error: return dark ? 0xFF7B70 : 0xF0483C             // --m-bad
        }
    }
    struct Body: Equatable {
        var body1: UInt32, body2: UInt32, body3: UInt32, visor1: UInt32, visor2: UInt32
        var edge: UInt32, edgeAlpha: Double
    }
    static func body(dark: Bool) -> Body {
        dark ? Body(body1: 0x7A8496, body2: 0x333A47, body3: 0x12151B, visor1: 0x05070D, visor2: 0x0C1426, edge: 0x8CC8FF, edgeAlpha: 0.22)
             : Body(body1: 0xFFFFFF, body2: 0xE9EDF4, body3: 0xB9C2D3, visor1: 0x1B2440, visor2: 0x080C18, edge: 0x141E3C, edgeAlpha: 0.35)
    }
    static func rgb(_ hex: UInt32) -> SIMD3<Double> {
        SIMD3(Double((hex >> 16) & 0xFF), Double((hex >> 8) & 0xFF), Double(hex & 0xFF)) / 255
    }
}

/// What one Loupe state looks like on the drone at a moment (see the table above).
struct DroneLook: Equatable {
    var mood: DroneMood
    var eyes: DroneEyes = .open
    var ring: DroneRing = .solid
    var ringOpacity = 0.38
    var floatPeriod = 4.8
    var halo = 0.14
    var roll = 0.0                        // radians, + = counter-clockwise on screen
    var squint = SIMD2<Double>(1, 1)      // eye scaleY, left and right
    var eyeBase = SIMD2<Double>(0, 0)     // visor units (the SVG's user units)
    var scan = false                      // visor scan line
    var flicker = false                   // booting: eyes flicker, beacon blinks
    var hopStart: Double? = nil           // stateT the hop starts at
    var hopScale = 1.0
    var flash = false
    var followsLook = false
    var scanLook = false                  // thinking: eyes sweep left-right
    var lively = false                    // Station `lively()`: blinks and glances

    static let eyeTravel = SIMD2<Double>(2.4, 1.5)   // mascot.js EYE_X, EYE_Y
    static let greetingBoot = 0.8
    static let settleAfter: [MascotState: Double] = [.greeting: 2.8, .found: 2.6, .happy: 1.8]
    static let tapDuration = 2.2                       // mascot.js REACTION_MS.happy

    static let idle = DroneLook(mood: .idle, lively: true)
    static let booting = DroneLook(mood: .booting, ring: .fine, ringOpacity: 0.5, squint: [0.45, 0.45], scan: true, flicker: true)
    static let thinking = DroneLook(mood: .thinking, ring: .long, ringOpacity: 0.95, floatPeriod: 1.4, halo: 0.3, squint: [0.55, 0.55], scanLook: true)
    static let unsure = DroneLook(mood: .unsure, roll: 11 * .pi / 180, squint: [1, 0.5], eyeBase: [1.6, -1.2], lively: true)
    static func happy(hopAt: Double?, hop: Double = 1, flash: Bool) -> DroneLook {
        DroneLook(mood: .happy, eyes: .happy, ringOpacity: 0.7, hopStart: hopAt, hopScale: hop, flash: flash, lively: true)
    }

    static func of(_ state: MascotState, stateT: Double, tapping: Bool, reduceMotion R: Bool) -> DroneLook {
        if tapping { return happy(hopAt: 0, flash: false) }
        let settled = settleAfter[state].map { stateT > $0 } ?? false
        switch state {
        case .idle: return idle
        case .greeting:
            if !R && stateT < greetingBoot { return booting }
            if settled { var l = idle; l.eyes = .happy; return l }
            return happy(hopAt: greetingBoot, flash: true)
        case .watching:
            var l = idle; l.followsLook = true; l.halo = 0.2; return l
        case .scanning:
            var l = thinking; l.scanLook = false; l.scan = true; return l
        case .thinking: return thinking
        case .found:
            var l = happy(hopAt: 0, flash: true)
            if settled { l.hopStart = nil; l.flash = false }
            return l
        case .happy:
            var l = happy(hopAt: 0, hop: 0.6, flash: false)
            if settled { l.hopStart = nil }
            return l
        case .empty: return unsure
        }
    }
}

/// Everything the drone renderer needs for one frame.
struct DroneFrame: Equatable {
    var mood: DroneMood
    var glow: SIMD3<Double>
    var eyes: DroneEyes
    var eyeScale: SIMD2<Double>
    var eyeOffset: SIMD2<Double>          // visor units
    var eyeAlpha: Double
    var scanU: Double?                    // 0...1 down the scan track, nil = no scan line
    var y: Double, roll: Double, yaw: Double, pitch: Double, scale: Double
    var halo: Double
    var ring: DroneRing
    var ringOpacity: Double
    var ringAngle: Double
    var beacon: Double
}

/// The drone's state machine, pure Swift, the counterpart of `MascotRig`: time, bob, hop,
/// blinks and glances, the tap reaction, and eased blending between looks.
struct DroneRig {
    private(set) var state: MascotState = .idle
    private(set) var stateT = 0.0
    private(set) var t = 0.0
    var reduceMotion = false
    var dark = false
    var lookTarget = SIMD2<Double>(0, 0)
    var scanX: Double?

    struct Eased: Equatable {
        var roll = 0.0, yaw = 0.0, pitch = 0.0, halo = 0.14, ringOpacity = 0.38
        var eye = SIMD2<Double>(0, 0)
        var glow = DronePalette.rgb(DronePalette.glow(.idle, dark: false))
        func distance(to o: Eased) -> Double {
            let e = simd_abs(eye - o.eye), g = simd_abs(glow - o.glow)
            return [abs(roll - o.roll), abs(yaw - o.yaw), abs(pitch - o.pitch), abs(halo - o.halo),
                    abs(ringOpacity - o.ringOpacity), e.x, e.y, g.x, g.y, g.z].max()!
        }
    }
    private(set) var current = Eased()
    private var ringAngle = 0.0
    private var tapT = 0.0
    private var blinkT: Double
    private var blink = 0.0
    private var glance = SIMD2<Double>(0, 0)
    private var glanceT = 0.0
    private var random: () -> Double

    init(random: @escaping () -> Double = { Double.random(in: 0..<1) }) {
        self.random = random
        blinkT = 2.6 + random() * 4.2
    }

    mutating func set(_ s: MascotState) {
        guard s != state else { return }
        state = s; stateT = 0
    }

    mutating func tap() { tapT = DroneLook.tapDuration; stateT = 0 }
    var isTapping: Bool { tapT > 0 }

    var look: DroneLook { DroneLook.of(state, stateT: stateT, tapping: tapT > 0, reduceMotion: reduceMotion) }

    /// Where the eased channels are heading.
    func target() -> Eased {
        let l = look, R = reduceMotion
        var e = Eased()
        e.roll = l.roll
        e.halo = l.halo
        e.ringOpacity = l.ringOpacity
        e.glow = DronePalette.rgb(DronePalette.glow(l.mood, dark: dark))
        var eye = l.eyeBase
        if !R {
            if l.followsLook {
                eye += lookTarget * DroneLook.eyeTravel
                e.yaw = lookTarget.x * 0.35; e.pitch = lookTarget.y * 0.22
            } else if l.scanLook {
                eye.x += -DroneLook.eyeTravel.x * cos(.pi * t / 0.7)   // m-scanlook .7s alternate
            } else if l.scan {
                eye.x += (scanU - 0.5) * 2 * DroneLook.eyeTravel.x * 0.8
            } else if l.lively {
                eye += glanceT > 0 ? glance : lookTarget * DroneLook.eyeTravel * 0.6
            }
        }
        e.eye = eye
        return e
    }

    /// The scan line's position: scanX when known, else booting's 1.5 s sweep (6/15 at rest).
    var scanU: Double {
        if let scanX { return min(1, max(0, scanX)) }
        return reduceMotion ? 0.4 : t.truncatingRemainder(dividingBy: 1.5) / 1.5
    }

    var isAnimating: Bool {
        if !reduceMotion { return true }
        return tapT > 0 || current.distance(to: target()) > 1e-4
    }

    mutating func step(dt rawDt: Double) -> DroneFrame {
        let dt = min(0.05, max(0, rawDt))
        let R = reduceMotion
        t += dt; stateT += dt
        if tapT > 0 { tapT -= dt; if tapT <= 0 { stateT = 0 } }
        let l = look

        // blinks and glances (mascot.js tick): only while lively and moving
        if !R && l.lively && l.eyes == .open {
            blinkT -= dt
            if blinkT < 0 {
                if random() < 0.3 && !l.followsLook {
                    glance = SIMD2((random() * 2 - 1) * DroneLook.eyeTravel.x, (random() * 2 - 1) * DroneLook.eyeTravel.y * 0.6)
                    glanceT = 1.1 + random() * 0.8
                } else {
                    blink = 0.13
                }
                blinkT = 2.6 + random() * 4.2
            }
        } else if R { blink = 0; glanceT = 0 }
        if glanceT > 0 { glanceT -= dt }

        let tg = target()
        let k = R ? 1 : 1 - exp(-dt * 8)          // ≈ Station's .28–.4 s transitions
        func mix(_ a: Double, _ b: Double) -> Double { a + (b - a) * k }
        current.roll = mix(current.roll, tg.roll); current.yaw = mix(current.yaw, tg.yaw); current.pitch = mix(current.pitch, tg.pitch)
        current.halo = mix(current.halo, tg.halo); current.ringOpacity = mix(current.ringOpacity, tg.ringOpacity)
        current.eye += (tg.eye - current.eye) * k
        current.glow += (tg.glow - current.glow) * k
        if R { current = tg }                     // Reduce Motion: exactly the target

        // hover bob (m-float: -7% at the midpoint) and the hop (m-hop, .7 s)
        var y = R ? 0 : Self.bob * (0.5 - 0.5 * cos(2 * .pi * t / l.floatPeriod))
        var scale = 1.0
        // m-breathe: the halo swells .14 -> .26 with the bob
        var halo = current.halo + (R ? 0 : 0.12 * (0.5 - 0.5 * cos(2 * .pi * t / l.floatPeriod)))
        if !R, let h0 = l.hopStart {
            let h = Self.hop(stateT - h0)
            y += h.y * l.hopScale; scale += (h.scale - 1) * l.hopScale
            if l.flash { halo += Self.flash(stateT - h0) }
        }
        // ring spin: dashes travel 50 units per period around a 200-unit ring
        if !R {
            switch l.ring {
            case .long: ringAngle += dt * (.pi / 2) / 0.7
            case .fine: ringAngle += dt * (.pi / 2) / 3.2
            case .solid: ringAngle += dt * 0.35
            }
        }
        var eyeScale = l.squint
        var eyeAlpha = 1.0, beacon = 1.0
        if blink > 0 { blink -= dt; eyeScale *= 0.08 }
        if l.flicker && !R {
            eyeAlpha = t.truncatingRemainder(dividingBy: 1.5) < 0.75 ? 0.45 : 1
            beacon = t.truncatingRemainder(dividingBy: 1) < 0.5 ? 0.25 : 1
        }
        return DroneFrame(mood: l.mood, glow: current.glow, eyes: l.eyes, eyeScale: eyeScale, eyeOffset: current.eye,
                          eyeAlpha: eyeAlpha, scanU: l.scan ? scanU : nil,
                          y: y, roll: current.roll, yaw: current.yaw, pitch: current.pitch, scale: scale,
                          halo: halo, ring: l.ring, ringOpacity: current.ringOpacity, ringAngle: R ? 0 : ringAngle, beacon: beacon)
    }

    /// m-float's 7% of the mascot's height, in scene units (orb radius 0.5 = 18 SVG units).
    static let bob = 0.0914
    /// m-hop keyframes: 35% up 14% and 1.06×, 70% down 2% and .98×, over .7 s.
    static func hop(_ s: Double) -> (y: Double, scale: Double) {
        guard s >= 0, s < 0.7 else { return (0, 1) }
        let p = s / 0.7
        func ease(_ a: Double, _ b: Double, _ u: Double) -> Double { let e = 1 - pow(1 - u, 3); return a + (b - a) * e }
        if p < 0.35 { let u = p / 0.35; return (ease(0, 0.183, u), ease(1, 1.06, u)) }
        if p < 0.7 { let u = (p - 0.35) / 0.35; return (ease(0.183, -0.026, u), ease(1.06, 0.98, u)) }
        let u = (p - 0.7) / 0.3; return (ease(-0.026, 0, u), ease(0.98, 1, u))
    }
    /// m-flash: halo up to .5 at 20% of .9 s, then back.
    static func flash(_ s: Double) -> Double {
        guard s >= 0, s < 0.9 else { return 0 }
        let p = s / 0.9
        return 0.36 * (p < 0.2 ? p / 0.2 : 1 - (p - 0.2) / 0.8)
    }
}
