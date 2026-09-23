import UIKit

/// Draws the cyan visor face (eyes, mouth, gleam) into an image, the prototype's face canvas.
/// Redraws only when what it shows changes, quantised, so a still face costs nothing per frame.
final class MascotFaceRenderer {
    static let W: CGFloat = 384, H: CGFloat = 240
    private var lastKey: [Int] = []
    private let renderer: UIGraphicsImageRenderer = {
        let f = UIGraphicsImageRendererFormat(); f.scale = 1; f.opaque = false
        return UIGraphicsImageRenderer(size: CGSize(width: W, height: H), format: f)
    }()

    struct Spec: Equatable {
        var face: MascotPoseName.Face
        var eyeOpen: Double
        var look: SIMD2<Double>
        var scanU: Double
    }

    /// nil when the face has not changed since the last call.
    func image(for f: MascotFrame) -> UIImage? {
        let spec = Spec(face: f.face, eyeOpen: f.eyeOpen, look: f.look, scanU: f.scanU)
        let key = MascotFaceRenderer.key(spec)
        guard key != lastKey else { return nil }
        lastKey = key
        return renderer.image { MascotFaceRenderer.draw(spec, in: $0.cgContext) }
    }

    static func key(_ s: Spec) -> [Int] {
        let faceId: Int
        switch s.face { case .open: faceId = 0; case .happy: faceId = 1; case .scanBar: faceId = 2; case .unsure: faceId = 3; case .empty: faceId = 4; case .big: faceId = 5 }
        return [faceId, Int((s.eyeOpen * 10).rounded()), Int((s.look.x * 40).rounded()), Int((s.look.y * 40).rounded()),
                s.face == .scanBar ? Int((s.scanU * 120).rounded()) : 0]
    }

    static func draw(_ s: Spec, in g: CGContext) {
        let k = W / 512 // the prototype's coordinates are for a 512 × 320 canvas
        g.clear(CGRect(x: 0, y: 0, width: W, height: H))
        g.scaleBy(x: k, y: k)
        let FW: CGFloat = 512, FH: CGFloat = 320
        let cx = FW / 2 + CGFloat(s.look.x) * 30, cy = FH * 0.42 + CGFloat(s.look.y) * 16, sp: CGFloat = 100
        let cyan = MascotPalette.cyan.cgColor
        g.setFillColor(cyan); g.setStrokeColor(cyan); g.setLineCap(.round)
        g.setShadow(offset: .zero, blur: 22 * k, color: UIColor(red: 111 / 255, green: 246 / 255, blue: 246 / 255, alpha: 0.9).cgColor)
        switch s.face {
        case .scanBar:
            let x = FW / 2 - 130 + CGFloat(s.scanU) * 260
            g.setAlpha(0.25); g.addPath(UIBezierPath(roundedRect: CGRect(x: x - 40 - 56, y: cy - 7, width: 80, height: 14), cornerRadius: 7).cgPath); g.fillPath()
            g.setAlpha(1); g.addPath(UIBezierPath(roundedRect: CGRect(x: x - 44, y: cy - 10, width: 88, height: 20), cornerRadius: 10).cgPath); g.fillPath()
        case .happy:
            g.setLineWidth(17)
            for sx in [-1.0, 1.0] as [CGFloat] {
                g.addArc(center: CGPoint(x: cx + sx * sp, y: cy + 20), radius: 30, startAngle: .pi * 1.15, endAngle: .pi * 1.85, clockwise: false)
                g.strokePath()
            }
        default:
            let up: CGFloat = s.face == .unsure ? 1 : 0, w: CGFloat = 70, h = 98 * CGFloat(s.eyeOpen)
            for sx in [-1.0, 1.0] as [CGFloat] {
                let ex = cx + sx * sp + up * 18, ey = cy - up * 24, hh = max(5, h / 2)
                g.fillEllipse(in: CGRect(x: ex - w / 2, y: ey - hh, width: w, height: hh * 2))
            }
        }
        g.setLineWidth(18)
        switch s.face {
        case .unsure: g.move(to: CGPoint(x: cx - 18, y: cy + 92)); g.addLine(to: CGPoint(x: cx + 26, y: cy + 88))
        case .empty:
            g.move(to: CGPoint(x: cx - 34, y: cy + 92))
            g.addQuadCurve(to: CGPoint(x: cx + 6, y: cy + 92), control: CGPoint(x: cx - 12, y: cy + 82))
            g.addQuadCurve(to: CGPoint(x: cx + 34, y: cy + 90), control: CGPoint(x: cx + 22, y: cy + 100))
        case .scanBar: g.move(to: CGPoint(x: cx - 20, y: cy + 90)); g.addLine(to: CGPoint(x: cx + 20, y: cy + 90))
        default:
            let big = s.face == .happy || s.face == .big
            g.addArc(center: CGPoint(x: cx, y: cy + (big ? 50 : 58)), radius: big ? 50 : 40, startAngle: .pi * 0.2, endAngle: .pi * 0.8, clockwise: false)
        }
        g.strokePath()
        // soft visor gleam, top right
        g.setShadow(offset: .zero, blur: 0, color: nil)
        let cs = CGColorSpaceCreateDeviceRGB()
        let grad = CGGradient(colorsSpace: cs, colors: [UIColor(white: 1, alpha: 0.28).cgColor, UIColor(white: 1, alpha: 0).cgColor] as CFArray, locations: [0, 1])!
        let c = CGPoint(x: FW * 0.84, y: FH * 0.14)
        g.drawRadialGradient(grad, startCenter: c, startRadius: 4, endCenter: c, endRadius: 70, options: [])
    }
}
