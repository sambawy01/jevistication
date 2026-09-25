import SwiftUI
import UIKit

/// The river's touch layer: one UIKit view with multi-touch on, so the steering finger and the FIRE
/// finger are two independent `UITouch`es. Which control a touch drives is fixed when it lands
/// (`TouchRouter`): on the FIRE button it fires, anywhere else it steers. The FIRE button itself is
/// drawn by SwiftUI on top (`FireButton`, not hit-testable), from the same `FireButtonLayout`.
struct RiverTouchSurface: UIViewRepresentable {
    let game: GameController
    /// Human mode: the FIRE button's area fires. Watch mode: the whole river only pauses.
    let fireButton: Bool
    let label: String

    func makeUIView(context: Context) -> RiverTouchView {
        let v = RiverTouchView()
        v.game = game
        return v
    }

    func updateUIView(_ v: RiverTouchView, context: Context) {
        v.game = game
        v.fireButtonEnabled = fireButton
        v.accessibilityLabel = label
    }
}

final class RiverTouchView: UIView {
    weak var game: GameController?
    var fireButtonEnabled = false {
        didSet { if !fireButtonEnabled { releaseFireTouch() } }
    }
    private var router = TouchRouter<ObjectIdentifier>()

    override init(frame: CGRect) {
        super.init(frame: frame)
        isMultipleTouchEnabled = true
        isExclusiveTouch = false
        backgroundColor = .clear
        isAccessibilityElement = true
        accessibilityIdentifier = "game.river"
        accessibilityTraits = .allowsDirectInteraction
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    private func column(_ x: CGFloat) -> Double {
        ColumnMapping.column(x: Double(x), width: Double(bounds.width), columns: GameController.columns)
    }

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard let game else { return }
        for t in touches {
            let p = t.location(in: self)
            let onFire = fireButtonEnabled && FireButtonLayout.contains(p, in: bounds)
            switch router.began(ObjectIdentifier(t), onFireButton: onFire) {
            case .steer:
                game.touchBegan(column: column(p.x), x: Double(p.x), time: CACurrentMediaTime())
            case .fire:
                game.fireBegan()
            case .ignored:
                break
            }
        }
    }

    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard let game else { return }
        for t in touches where router.role(of: ObjectIdentifier(t)) == .steer {
            let p = t.location(in: self)
            game.touchMoved(column: column(p.x), x: Double(p.x))
        }
    }

    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        finish(touches, cancelled: false)
    }

    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) {
        finish(touches, cancelled: true)
    }

    private func finish(_ touches: Set<UITouch>, cancelled: Bool) {
        for t in touches {
            switch router.ended(ObjectIdentifier(t)) {
            case .steer:
                if cancelled { game?.touchCancelled() } else { game?.touchEnded(time: CACurrentMediaTime()) }
            case .fire:
                game?.fireEnded()
            case .ignored:
                break
            }
        }
    }

    private func releaseFireTouch() {
        if let id = router.fire {
            router.ended(id)
            game?.fireEnded()
        }
    }
}

/// The FIRE button, bottom-right over the river. Drawn only: the touch surface underneath owns the
/// touches (so it works as a second finger while the first steers). VoiceOver activates it as a
/// button, one shot per activation. Pressed: brighter fill and ring; it also shrinks a little unless
/// motion is reduced.
struct FireButton: View {
    @ObservedObject var state: FireButtonState
    let reducedMotion: Bool
    var onActivate: () -> Void

    var body: some View {
        let pressed = state.pressed
        ZStack {
            // A solid base, so the river's bank lines never run through the label.
            Circle().fill(Palette.navyTop.opacity(0.9))
            Circle().fill(Palette.red.opacity(pressed ? 0.85 : 0.35))
            Circle().stroke(pressed ? Palette.overlayInk : Palette.red, lineWidth: pressed ? 3 : 2)
            VStack(spacing: 2) {
                Image(systemName: "scope").font(.system(size: 22, weight: .bold))
                Text("FIRE").font(Typeface.mono(12, weight: .bold)).tracking(1)
            }
            .foregroundStyle(Palette.overlayInk)
        }
        .frame(width: FireButtonLayout.size, height: FireButtonLayout.size)
        .scaleEffect(pressed && !reducedMotion ? 0.92 : 1)
        .animation(reducedMotion ? nil : .easeOut(duration: 0.08), value: pressed)
        .allowsHitTesting(false)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Fire")
        .accessibilityHint("Shoots. Hold it with one thumb while the other steers.")
        .accessibilityAddTraits(.isButton)
        .accessibilityValue(pressed ? "firing" : "")
        .accessibilityAction { onActivate() }
        .accessibilityIdentifier("game.fire")
    }
}
