import SwiftUI
import UIKit

/// The river's touch layer: one UIKit view, so the steering finger is a plain `UITouch` that UIKit
/// delivers alongside the FIRE finger on its own view (`FireTouchSurface`, in the control bar under
/// the river: owner's report 2026-09-25, FIRE over the river covered the plane). Both are UIKit
/// views that are neither exclusive-touch nor inside a scroll view, so a held FIRE and a drag on the
/// river register at the same time. Every river touch steers (`TouchRouter`: one steering finger).
struct RiverTouchSurface: UIViewRepresentable {
    let game: GameController
    let label: String

    func makeUIView(context: Context) -> RiverTouchView {
        let v = RiverTouchView()
        v.game = game
        return v
    }

    func updateUIView(_ v: RiverTouchView, context: Context) {
        v.game = game
        v.accessibilityLabel = label
    }
}

final class RiverTouchView: UIView {
    weak var game: GameController?
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
            switch router.began(ObjectIdentifier(t), onFireButton: false) {
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
}

/// FIRE's touch layer in the control bar under the river: its own UIKit view, so a thumb held here
/// and a finger dragging on the river are separate touches on separate views. The drawn button
/// (`FireButton`) sits on top and is not hit-testable. A touch counts when it lands within the round
/// button plus `FireButtonLayout.slop`.
struct FireTouchSurface: UIViewRepresentable {
    let game: GameController

    func makeUIView(context: Context) -> FireTouchView {
        let v = FireTouchView()
        v.game = game
        return v
    }

    func updateUIView(_ v: FireTouchView, context: Context) { v.game = game }

    static func dismantleUIView(_ v: FireTouchView, coordinator: ()) { v.release() }
}

final class FireTouchView: UIView {
    weak var game: GameController?
    private var touch: UITouch?

    override init(frame: CGRect) {
        super.init(frame: frame)
        isMultipleTouchEnabled = false
        isExclusiveTouch = false
        backgroundColor = .clear
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    override func point(inside point: CGPoint, with event: UIEvent?) -> Bool {
        FireButtonLayout.contains(point, in: bounds)
    }

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard touch == nil, let t = touches.first else { return }
        touch = t
        game?.fireBegan()
    }

    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) { end(touches) }
    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) { end(touches) }

    private func end(_ touches: Set<UITouch>) {
        guard let t = touch, touches.contains(t) else { return }
        release()
    }

    func release() {
        guard touch != nil else { return }
        touch = nil
        game?.fireEnded()
    }
}

/// The FIRE button, in the control bar under the river. Drawn only: `FireTouchSurface` underneath
/// owns the touch (so it works as a second finger while the first steers on the river). VoiceOver activates it as a
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
