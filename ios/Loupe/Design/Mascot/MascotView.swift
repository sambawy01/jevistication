import SwiftUI
import SceneKit
import Metal

/// The robot mascot: a rigged SceneKit robot (ported from the approved web prototype) with an
/// animated visor face. The one mascot view in the app; size it per context.
///
/// - Reduce Motion: a static pose per state (only the expression changes), no frames rendered
///   while nothing moves.
/// - Rendering pauses when the view leaves the screen or the app goes to the background.
/// - Tiny sizes (row icons) get a cached still render instead of a live 3D view.
/// - No Metal device: the owner's reference render (`Mascot` image) as before.
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

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.mascotTabSelected) private var tabSelected

    static let liveMinimum: CGFloat = 40
    static let hasMetal: Bool = MTLCreateSystemDefaultDevice() != nil

    var body: some View {
        Group {
            if !Self.hasMetal {
                Image("Mascot").resizable().scaledToFit()
            } else if size < Self.liveMinimum {
                Image(uiImage: MascotStills.shared.image(state, points: size))
                    .resizable().scaledToFit()
            } else {
                MascotSceneView(state: state, lookAt: lookAt, reduceMotion: reduceMotion, active: scenePhase == .active && tabSelected)
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
    private lazy var renderer: SCNRenderer = {
        let r = SCNRenderer(device: MTLCreateSystemDefaultDevice(), options: nil)
        r.scene = model.scene; r.pointOfView = model.camera; r.autoenablesDefaultLighting = false
        return r
    }()

    func image(_ state: MascotState, points: CGFloat) -> UIImage {
        let px = max(1, (points * 3).rounded())
        let key = "\(state.rawValue)@\(px)"
        if let hit = cache[key] { return hit }
        var rig = MascotRig(random: { 0.5 })
        rig.reduceMotion = true
        rig.set(state)
        model.apply(rig.step(dt: 1 / 60))
        let img = renderer.snapshot(atTime: 0, with: CGSize(width: px, height: px), antialiasingMode: .multisampling4X)
        cache[key] = img
        return img
    }
}

/// SwiftUI host for one live robot.
struct MascotSceneView: UIViewRepresentable {
    var state: MascotState
    var lookAt: CGPoint?
    var reduceMotion: Bool
    var active: Bool

    func makeUIView(context: Context) -> MascotSCNView { MascotSCNView() }

    func updateUIView(_ v: MascotSCNView, context: Context) {
        v.update(state: state, look: lookAt, reduceMotion: reduceMotion, active: active)
    }

    static func dismantleUIView(_ v: MascotSCNView, coordinator: ()) { v.stop() }
}

/// The SCNView that drives the rig. The rig lives behind a lock: SwiftUI writes state on the main
/// thread, SceneKit steps it on its render thread.
final class MascotSCNView: SCNView, SCNSceneRendererDelegate {
    private let model = MascotModel()
    private let lock = NSLock()
    private var rig = MascotRig()
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

    init() {
        super.init(frame: .zero, options: [SCNView.Option.preferredRenderingAPI.rawValue: SCNRenderingAPI.metal.rawValue])
        scene = model.scene
        pointOfView = model.camera
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
        // first frame, so there is a robot before the first tick
        model.apply(rig.step(dt: 0))
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    @objc private func tapped() {
        lock.lock(); rig.tap(); lock.unlock()
        refreshPlaying()
    }

    func update(state: MascotState, look: CGPoint?, reduceMotion: Bool, active: Bool) {
        lock.lock()
        rig.set(state)
        rig.reduceMotion = reduceMotion
        if let look { rig.lookTarget = SIMD2(Double(max(-1, min(1, look.x))), Double(max(-1, min(1, look.y)))) }
        lock.unlock()
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
        lock.lock(); let animating = rig.isAnimating; lock.unlock()
        let want = MascotPlayback.shouldPlay(onScreen: onScreen, appActive: active, tabSelected: true, animating: animating)
        if want && !isPlaying { lastTime = nil }
        if want != isPlaying { isPlaying = want }
        if !want && onScreen {
            // Reduce Motion: render the settled pose once.
            lock.lock(); let f = rig.step(dt: 1 / 60); lock.unlock()
            SCNTransaction.lock(); model.apply(f); SCNTransaction.unlock()
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
        lock.lock(); let f = rig.step(dt: dt); let animating = rig.isAnimating; lock.unlock()
        model.apply(f)
        if !animating { DispatchQueue.main.async { [weak self] in self?.refreshPlaying() } }
        if Self.logFPS {
            frames += 1
            if time - fpsWindowStart >= 2 {
                if fpsWindowStart > 0 { print("[mascot] fps=\(String(format: "%.1f", Double(frames) / (time - fpsWindowStart))) size=\(Int(bounds.width))") }
                frames = 0; fpsWindowStart = time
            }
        }
    }
}

#if DEBUG
/// -LoupeMascotGallery: every state side by side, for design screenshots.
struct MascotGallery: View {
    let columns = [GridItem(.flexible()), GridItem(.flexible())]
    var body: some View {
        ScrollView {
            LazyVGrid(columns: columns, spacing: 8) {
                ForEach(MascotState.allCases, id: \.self) { s in
                    VStack(spacing: 2) {
                        MascotView(state: s, size: 170, lookAt: s == .watching ? CGPoint(x: 0.8, y: -0.3) : nil)
                        Text(s.rawValue).font(Typeface.mono(13, weight: .medium)).foregroundStyle(Palette.ink)
                    }
                }
            }
            .padding(12)
        }
        .background(Color(red: 0.96, green: 0.96, blue: 0.94).ignoresSafeArea())
    }
}
#endif
