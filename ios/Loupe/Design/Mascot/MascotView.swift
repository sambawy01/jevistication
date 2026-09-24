import SwiftUI
import SceneKit
import Metal

/// The mascot: the rigged SceneKit robot (ported from the approved web prototype, the default) or
/// Loupe Station's orb-drone (DroneRig/DroneScene), per Me → Appearance → Mascot (`MascotKind`).
/// The one mascot view in the app; size it per context. Switching kind is live.
///
/// - Reduce Motion: a static pose per state (only the expression changes), no frames rendered
///   while nothing moves.
/// - Rendering pauses when the view leaves the screen or the app goes to the background.
/// - Tiny sizes (row icons) get a cached still render instead of a live 3D view.
/// - No Metal device: a bundled still (`Mascot` for the robot, `MascotDrone` for the drone).
/// - Decorative: hidden from VoiceOver.
/// Whether the tab that hosts a mascot is the selected one. RootView sets it per tab, so a
/// mascot on an unselected tab (still mounted by TabView) renders no frames.
private struct MascotTabSelectedKey: EnvironmentKey { static let defaultValue = true }
extension EnvironmentValues {
    var mascotTabSelected: Bool {
        get { self[MascotTabSelectedKey.self] }
        set { self[MascotTabSelectedKey.self] = newValue }
    }
}

/// The pause rule: render frames only when all of these hold.
enum MascotPlayback {
    static func shouldPlay(onScreen: Bool, appActive: Bool, tabSelected: Bool, animating: Bool) -> Bool {
        onScreen && appActive && tabSelected && animating
    }
}

struct MascotView: View {
    var state: MascotState = .idle
    var size: CGFloat = 96
    /// Where the robot looks, each axis -1...1 (e.g. the last touch); used by `.watching` and idle.
    var lookAt: CGPoint? = nil
    /// Forces a kind (the gallery); nil follows the setting.
    var kind: MascotKind? = nil

    @AppStorage(MascotKind.storageKey) private var storedKind = MascotKind.default.rawValue
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.mascotTabSelected) private var tabSelected

    static let liveMinimum: CGFloat = 40
    static let hasMetal: Bool = MTLCreateSystemDefaultDevice() != nil

    var body: some View {
        let kind = self.kind ?? MascotKind(rawValue: storedKind) ?? .default
        let dark = colorScheme == .dark
        Group {
            if !Self.hasMetal {
                Image(kind == .drone ? "MascotDrone" : "Mascot").resizable().scaledToFit()
            } else if size < Self.liveMinimum {
                Image(uiImage: MascotStills.shared.image(state, points: size, kind: kind, dark: dark))
                    .resizable().scaledToFit()
            } else {
                MascotSceneView(kind: kind, state: state, lookAt: lookAt, reduceMotion: Motion.reduced(reduceMotion), dark: dark,
                                active: scenePhase == .active && tabSelected)
                    .id(kind)
            }
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}

/// Still renders for small sizes, one per state and pixel size, rendered once.
@MainActor
final class MascotStills {
    static let shared = MascotStills()
    private var cache: [String: UIImage] = [:]
    private lazy var model = MascotModel()
    private lazy var drone = DroneModel()
    private lazy var renderer: SCNRenderer = {
        let r = SCNRenderer(device: MTLCreateSystemDefaultDevice(), options: nil)
        r.autoenablesDefaultLighting = false
        return r
    }()

    func image(_ state: MascotState, points: CGFloat, kind: MascotKind = .robot, dark: Bool = false) -> UIImage {
        let px = max(1, (points * 3).rounded())
        let key = "\(kind.rawValue).\(state.rawValue)@\(px)\(kind == .drone && dark ? ".dark" : "")"
        if let hit = cache[key] { return hit }
        switch kind {
        case .robot:
            var rig = MascotRig(random: { 0.5 })
            rig.reduceMotion = true
            rig.set(state)
            model.apply(rig.step(dt: 1 / 60))
            renderer.scene = model.scene; renderer.pointOfView = model.camera
        case .drone:
            var rig = DroneRig(random: { 0.5 })
            rig.reduceMotion = true; rig.dark = dark
            rig.set(state)
            drone.apply(rig.step(dt: 1 / 60), dark: dark)
            renderer.scene = drone.scene; renderer.pointOfView = drone.camera
        }
        let img = renderer.snapshot(atTime: 0, with: CGSize(width: px, height: px), antialiasingMode: .multisampling4X)
        cache[key] = img
        return img
    }
}

/// SwiftUI host for one live mascot (a new view per kind: MascotView sets `.id(kind)`).
struct MascotSceneView: UIViewRepresentable {
    var kind: MascotKind = .robot
    var state: MascotState
    var lookAt: CGPoint?
    var reduceMotion: Bool
    var dark = false
    var active: Bool

    func makeUIView(context: Context) -> MascotSCNView { MascotSCNView(kind: kind) }

    func updateUIView(_ v: MascotSCNView, context: Context) {
        v.update(state: state, look: lookAt, reduceMotion: reduceMotion, dark: dark, active: active)
    }

    static func dismantleUIView(_ v: MascotSCNView, coordinator: ()) { v.stop() }
}

/// One mascot's rig + model pair. The rig lives behind a lock: SwiftUI writes state on the main
/// thread, SceneKit steps it on its render thread.
protocol MascotDriver: AnyObject {
    var scene: SCNScene { get }
    var camera: SCNNode { get }
    var isAnimating: Bool { get }
    func update(state: MascotState, look: CGPoint?, reduceMotion: Bool, dark: Bool)
    func tap()
    /// Steps the rig by `dt` and applies the frame to the nodes.
    func advance(dt: Double)
}

private func clampedLook(_ p: CGPoint) -> SIMD2<Double> {
    SIMD2(Double(max(-1, min(1, p.x))), Double(max(-1, min(1, p.y))))
}

final class RobotDriver: MascotDriver {
    private let model = MascotModel()
    private let lock = NSLock()
    private var rig = MascotRig()
    init() { model.apply(rig.step(dt: 0)) }
    var scene: SCNScene { model.scene }
    var camera: SCNNode { model.camera }
    var isAnimating: Bool { lock.lock(); defer { lock.unlock() }; return rig.isAnimating }
    func update(state: MascotState, look: CGPoint?, reduceMotion: Bool, dark: Bool) {
        lock.lock(); defer { lock.unlock() }
        rig.set(state); rig.reduceMotion = reduceMotion
        if let look { rig.lookTarget = clampedLook(look) }
    }
    func tap() { lock.lock(); rig.tap(); lock.unlock() }
    /// Step + apply under one lock: apply runs on the main thread (settled stills) and the render
    /// thread, and the face renderer's cache is not thread-safe.
    func advance(dt: Double) { lock.lock(); defer { lock.unlock() }; model.apply(rig.step(dt: dt)) }
}

final class DroneDriver: MascotDriver {
    private let model = DroneModel()
    private let lock = NSLock()
    private var rig = DroneRig()
    init() { model.apply(rig.step(dt: 0), dark: false) }
    var scene: SCNScene { model.scene }
    var camera: SCNNode { model.camera }
    var isAnimating: Bool { lock.lock(); defer { lock.unlock() }; return rig.isAnimating }
    func update(state: MascotState, look: CGPoint?, reduceMotion: Bool, dark: Bool) {
        lock.lock(); defer { lock.unlock() }
        rig.set(state); rig.reduceMotion = reduceMotion; rig.dark = dark
        if let look { rig.lookTarget = clampedLook(look) }
    }
    func tap() { lock.lock(); rig.tap(); lock.unlock() }
    func advance(dt: Double) {
        lock.lock(); defer { lock.unlock() }        // see RobotDriver.advance
        model.apply(rig.step(dt: dt), dark: rig.dark)
    }
}

/// The SCNView that drives a mascot (robot or drone) and applies the pause rule.
final class MascotSCNView: SCNView, SCNSceneRendererDelegate {
    let kind: MascotKind
    private let driver: MascotDriver
    private var lastTime: TimeInterval?
    private var active = true
    private var onScreen = false
    private var visibilityTimer: Timer?
    // fps probe (DEBUG, -LoupeMascotFPS)
    private var frames = 0, fpsWindowStart: TimeInterval = 0
    static let logFPS: Bool = {
        #if DEBUG
        return ProcessInfo.processInfo.arguments.contains("-LoupeMascotFPS")
        #else
        return false
        #endif
    }()

    init(kind: MascotKind = .robot) {
        self.kind = kind
        driver = kind == .drone ? DroneDriver() : RobotDriver()
        super.init(frame: .zero, options: [SCNView.Option.preferredRenderingAPI.rawValue: SCNRenderingAPI.metal.rawValue])
        scene = driver.scene
        pointOfView = driver.camera
        backgroundColor = .clear
        isOpaque = false
        antialiasingMode = .multisampling4X
        preferredFramesPerSecond = 60
        autoenablesDefaultLighting = false
        rendersContinuously = false
        isUserInteractionEnabled = true
        delegate = self
        isPlaying = false
        addGestureRecognizer(UITapGestureRecognizer(target: self, action: #selector(tapped)))
        accessibilityElementsHidden = true
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    @objc private func tapped() {
        driver.tap()
        refreshPlaying()
    }

    func update(state: MascotState, look: CGPoint?, reduceMotion: Bool, dark: Bool = false, active: Bool) {
        driver.update(state: state, look: look, reduceMotion: reduceMotion, dark: dark)
        self.active = active
        refreshPlaying()
    }

    func stop() { visibilityTimer?.invalidate(); visibilityTimer = nil; isPlaying = false }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        visibilityTimer?.invalidate(); visibilityTimer = nil
        if window != nil {
            let t = Timer(timeInterval: 0.5, repeats: true) { [weak self] _ in self?.refreshPlaying() }
            RunLoop.main.add(t, forMode: .common)
            visibilityTimer = t
        }
        refreshPlaying()
    }

    /// Plays only while visible, the app is active, and something moves.
    private func refreshPlaying() {
        onScreen = isVisibleInWindow
        let animating = driver.isAnimating
        let want = MascotPlayback.shouldPlay(onScreen: onScreen, appActive: active, tabSelected: true, animating: animating)
        if want && !isPlaying { lastTime = nil }
        if want != isPlaying { isPlaying = want }
        if !want && onScreen {
            // Reduce Motion: render the settled pose once.
            SCNTransaction.lock(); driver.advance(dt: 1 / 60); SCNTransaction.unlock()
        }
    }

    private var isVisibleInWindow: Bool {
        guard let window, !isHidden, alpha > 0.01, bounds.width > 0 else { return false }
        var v: UIView? = superview
        while let s = v { if s.isHidden || s.alpha < 0.01 { return false }; v = s.superview }
        let r = convert(bounds, to: window)
        return r.intersects(window.bounds)
    }

    func renderer(_ renderer: SCNSceneRenderer, updateAtTime time: TimeInterval) {
        let dt = lastTime.map { time - $0 } ?? 1 / 60
        lastTime = time
        driver.advance(dt: dt)
        let animating = driver.isAnimating
        if !animating { DispatchQueue.main.async { [weak self] in self?.refreshPlaying() } }
        if Self.logFPS {
            frames += 1
            if time - fpsWindowStart >= 2 {
                if fpsWindowStart > 0 { print("[mascot] kind=\(kind.rawValue) fps=\(String(format: "%.1f", Double(frames) / (time - fpsWindowStart))) size=\(Int(bounds.width))") }
                frames = 0; fpsWindowStart = time
            }
        }
    }
}

#if DEBUG
/// -LoupeMascotGallery [-LoupeMascotKind drone]: every state side by side, for design screenshots.
struct MascotGallery: View {
    var kind: MascotKind? = {
        let a = ProcessInfo.processInfo.arguments
        guard let i = a.firstIndex(of: "-LoupeMascotKind"), i + 1 < a.count else { return nil }
        return MascotKind(rawValue: a[i + 1])
    }()
    let columns = [GridItem(.flexible()), GridItem(.flexible())]
    var body: some View {
        ScrollView {
            LazyVGrid(columns: columns, spacing: 8) {
                ForEach(MascotState.allCases, id: \.self) { s in
                    VStack(spacing: 2) {
                        MascotView(state: s, size: 170, lookAt: s == .watching ? CGPoint(x: 0.8, y: -0.3) : nil, kind: kind)
                        Text(s.rawValue).font(Typeface.mono(13, weight: .medium)).foregroundStyle(Palette.ink)
                    }
                }
            }
            .padding(12)
        }
        .neonGround()
    }
}
#endif
