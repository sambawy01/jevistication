import SceneKit
import UIKit
import simd

// The drone mascot's SceneKit half (epic #7 child 17), ported from Loupe Station's mascot
// (~/laya-studio laya_studio/static/js/mascot.js `buildSvg` + static/mascot.css @ ea7697a): the
// SVG's orb, glass visor, pill eyes, antenna beacon, seam and tilted orbit ring rebuilt as 3D
// primitives with the SVG's proportions. SVG user units map to scene units at 18 u = 0.5 (the orb
// radius), centred on the orb (32, 30). The visor face is drawn with Core Graphics into a texture
// on a patch of the sphere, redrawn only when what it shows changes.

private let U: Float = 0.5 / 18
private func sx(_ x: Float) -> Float { (x - 32) * U }
private func sy(_ y: Float) -> Float { (30 - y) * U }

final class DroneModel {
    let scene = SCNScene()
    let camera: SCNNode
    private let float = SCNNode(), pose = SCNNode(), ringSpin = SCNNode()
    private let body: SCNMaterial, ringMat: SCNMaterial, beaconMat: SCNMaterial, haloMat: SCNMaterial, faceMat: SCNMaterial
    private let edgeMat: SCNMaterial, stemMat: SCNMaterial
    private let ringNode: SCNNode
    private let face = DroneFaceRenderer()
    private var lastRing: DroneRing?
    private var dark: Bool?

    init() {
        scene.background.contents = UIColor.clear
        scene.lightingEnvironment.contents = MascotTemplate.studio()
        scene.lightingEnvironment.intensity = 0.8
        let world = scene.rootNode

        // halo: Station's radial glow behind the drone (does not bob, like .mascot-halo)
        haloMat = SCNMaterial(); haloMat.lightingModel = .constant
        haloMat.diffuse.contents = DroneModel.radial(); haloMat.blendMode = .alpha; haloMat.writesToDepthBuffer = false
        let halo = SCNNode(geometry: SCNPlane(width: 1.62, height: 1.56)); halo.geometry!.materials = [haloMat]
        halo.simdPosition = [0, 0.072, -0.8]; halo.renderingOrder = -2
        world.addChildNode(halo)

        world.addChildNode(float); float.addChildNode(pose)

        body = SCNMaterial(); body.lightingModel = .physicallyBased
        body.roughness.contents = 0.32; body.metalness.contents = 0.0
        body.clearCoat.contents = 0.7; body.clearCoatRoughness.contents = 0.18
        // the rim: .m-rim's glow gradient, transparent at the top, full along the lower edge
        body.shaderModifiers = [.fragment: """
        #pragma arguments
        float3 glowColor;
        #pragma body
        float3 n = normalize(_surface.normal);
        float edge = pow(1.0 - saturate(dot(n, normalize(_surface.view))), 3.0);
        float low = saturate((0.35 - n.y) / 1.1);
        _output.color.rgb = mix(_output.color.rgb, glowColor, saturate(edge * low * 1.6));
        """]
        let orbGeo = SCNSphere(radius: 0.5); orbGeo.segmentCount = 64; orbGeo.materials = [body]
        pose.addChildNode(SCNNode(geometry: orbGeo))

        edgeMat = SCNMaterial(); edgeMat.lightingModel = .constant
        // seam: .m-seam, a latitude line on the lower orb
        let seamY = sy(40.2), seamR = sqrt(0.25 - seamY * seamY) + 0.002
        let seam = SCNNode(geometry: SCNTorus(ringRadius: CGFloat(seamR), pipeRadius: 0.0045)); seam.geometry!.materials = [edgeMat]
        seam.simdPosition.y = seamY; pose.addChildNode(seam)

        // antenna stem + beacon
        stemMat = SCNMaterial(); stemMat.lightingModel = .physicallyBased; stemMat.roughness.contents = 0.4
        let stemLen = CGFloat(sy(7.4) - sy(12.2) + 0.03)
        let stem = SCNNode(geometry: SCNCapsule(capRadius: CGFloat(0.8 * U), height: stemLen)); stem.geometry!.materials = [stemMat]
        stem.simdPosition.y = (sy(7.4) + sy(12.2)) / 2; pose.addChildNode(stem)
        beaconMat = SCNMaterial(); beaconMat.lightingModel = .constant
        let beaconGeo = SCNSphere(radius: CGFloat(1.9 * U)); beaconGeo.segmentCount = 20; beaconGeo.materials = [beaconMat]
        let beacon = SCNNode(geometry: beaconGeo); beacon.simdPosition.y = sy(6.2); pose.addChildNode(beacon)

        // visor: a patch of a slightly larger sphere, textured with the face
        faceMat = SCNMaterial(); faceMat.lightingModel = .constant
        faceMat.diffuse.contents = UIColor.clear; faceMat.blendMode = .alpha; faceMat.transparencyMode = .aOne
        faceMat.writesToDepthBuffer = false
        let visor = SCNNode(geometry: DroneModel.visorPatch()); visor.geometry!.materials = [faceMat]; visor.renderingOrder = 2
        pose.addChildNode(visor)

        // orbit ring: tilted -9° (screen), seen 16° from edge-on so its ellipse is 27 × 7.5
        ringMat = SCNMaterial(); ringMat.lightingModel = .constant; ringMat.blendMode = .alpha
        ringMat.writesToDepthBuffer = false; ringMat.isDoubleSided = true
        ringMat.diffuse.wrapS = .repeat; ringMat.diffuse.wrapT = .repeat
        let torus = SCNTorus(ringRadius: CGFloat(27 * U), pipeRadius: CGFloat(0.6 * U)); torus.ringSegmentCount = 96; torus.pipeSegmentCount = 8
        torus.materials = [ringMat]
        ringNode = SCNNode(geometry: torus); ringNode.renderingOrder = 3
        let ringTilt = SCNNode()
        ringTilt.simdPosition.y = sy(34)
        ringTilt.simdOrientation = simd_quatf(angle: 9 * .pi / 180, axis: [0, 0, 1]) * simd_quatf(angle: asin(7.5 / 27), axis: [1, 0, 0])
        pose.addChildNode(ringTilt); ringTilt.addChildNode(ringSpin); ringSpin.addChildNode(ringNode)

        func light(_ type: SCNLight.LightType, _ color: UInt32, _ intensity: CGFloat, _ pos: SIMD3<Float>? = nil) {
            let l = SCNLight(); l.type = type; l.color = MascotPalette.srgb(color); l.intensity = intensity
            let n = SCNNode(); n.light = l
            if let pos { n.simdPosition = pos; n.simdLook(at: .zero) }
            world.addChildNode(n)
        }
        light(.ambient, 0xC4CDE6, 260)
        light(.directional, 0xFFF6EC, 1250, [-3, 4, 5])      // key from top-left, where .m-spec sits
        light(.directional, 0x9FC0FF, 700, [3, -1, -3])

        let cam = SCNCamera(); cam.fieldOfView = 20.2; cam.projectionDirection = .vertical; cam.zNear = 0.1; cam.zFar = 50
        camera = SCNNode(); camera.camera = cam
        camera.simdPosition = [0, 0.125, 5]; camera.simdLook(at: [0, 0.125, 0])
        world.addChildNode(camera)
        setDark(false)
    }

    private func setDark(_ d: Bool) {
        guard d != dark else { return }
        dark = d
        let p = DronePalette.body(dark: d)
        body.diffuse.contents = MascotPalette.srgb(p.body2)
        stemMat.diffuse.contents = MascotPalette.srgb(p.body3)
        edgeMat.diffuse.contents = MascotPalette.srgb(p.edge).withAlphaComponent(CGFloat(p.edgeAlpha))
        edgeMat.blendMode = .alpha
    }

    func apply(_ f: DroneFrame, dark d: Bool) {
        setDark(d)
        let glow = UIColor(red: CGFloat(f.glow.x), green: CGFloat(f.glow.y), blue: CGFloat(f.glow.z), alpha: 1)
        float.simdPosition.y = Float(f.y)
        pose.simdOrientation = simd_quatf(angle: Float(f.pitch), axis: [1, 0, 0]) * simd_quatf(angle: Float(f.yaw), axis: [0, 1, 0])
            * simd_quatf(angle: Float(f.roll), axis: [0, 0, 1])
        pose.simdScale = SIMD3(repeating: Float(f.scale))
        body.setValue(SCNVector3(Float(f.glow.x), Float(f.glow.y), Float(f.glow.z)), forKey: "glowColor")
        beaconMat.diffuse.contents = glow.withAlphaComponent(CGFloat(f.beacon))
        beaconMat.blendMode = .alpha
        haloMat.multiply.contents = glow
        haloMat.transparency = CGFloat(min(1, f.halo))
        ringMat.multiply.contents = glow
        // .m-ring-back is fainter than the front: average of the two
        ringMat.transparency = CGFloat(f.ringOpacity * 0.8)
        if f.ring != lastRing {
            lastRing = f.ring
            switch f.ring {
            case .solid: ringMat.diffuse.contents = UIColor.white; ringMat.diffuse.contentsTransform = SCNMatrix4Identity; ringNode.simdScale = [1, 1, 1]
            case .fine: ringMat.diffuse.contents = DroneModel.dashes(on: 3, of: 8); ringMat.diffuse.contentsTransform = SCNMatrix4MakeScale(25, 1, 1); ringNode.simdScale = [1, 1, 1]
            case .long: ringMat.diffuse.contents = DroneModel.dashes(on: 16, of: 50); ringMat.diffuse.contentsTransform = SCNMatrix4MakeScale(4, 1, 1); ringNode.simdScale = [1, 1.45, 1]
            }
        }
        ringSpin.simdOrientation = simd_quatf(angle: -Float(f.ringAngle), axis: [0, 1, 0])
        if let img = face.image(for: f, dark: d) { faceMat.diffuse.contents = img }
    }

    /// The visor rect (17, 22.5, 30 × 13) pressed onto a sphere just outside the orb, UV 0...1.
    static func visorPatch(cols: Int = 30, rows: Int = 14) -> SCNGeometry {
        let R: Float = 0.506
        var p: [SCNVector3] = [], n: [SCNVector3] = [], uv: [CGPoint] = []
        for r in 0...rows {
            for c in 0...cols {
                let u = Float(c) / Float(cols), v = Float(r) / Float(rows)
                let x = sx(17 + 30 * u), y = sy(22.5 + 13 * v)
                let z = sqrt(max(0, R * R - x * x - y * y))
                p.append(SCNVector3(x, y, z)); n.append(SCNVector3(x / R, y / R, z / R)); uv.append(CGPoint(x: CGFloat(u), y: CGFloat(v)))
            }
        }
        var idx: [UInt16] = []
        for r in 0..<rows { for c in 0..<cols {
            let a = UInt16(r * (cols + 1) + c), b = a + 1, d = a + UInt16(cols + 1), e = d + 1
            idx += [a, d, b, b, d, e]
        } }
        return SCNGeometry(sources: [SCNGeometrySource(vertices: p), SCNGeometrySource(normals: n), SCNGeometrySource(textureCoordinates: uv)],
                           elements: [SCNGeometryElement(indices: idx, primitiveType: .triangles)])
    }

    /// radial-gradient(closest-side, glow, transparent), in white; the material multiplies the glow in.
    private static func radial() -> UIImage {
        UIGraphicsImageRenderer(size: CGSize(width: 128, height: 128)).image { ctx in
            let grad = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(), colors: [UIColor.white.cgColor, UIColor(white: 1, alpha: 0).cgColor] as CFArray, locations: [0, 1])!
            ctx.cgContext.drawRadialGradient(grad, startCenter: CGPoint(x: 64, y: 64), startRadius: 0, endCenter: CGPoint(x: 64, y: 64), endRadius: 64, options: [])
        }
    }

    /// One dash period along the ring: `on` lit out of `of`.
    private static func dashes(on: CGFloat, of: CGFloat) -> UIImage {
        let f = UIGraphicsImageRendererFormat(); f.scale = 1; f.opaque = false
        return UIGraphicsImageRenderer(size: CGSize(width: 64, height: 4), format: f).image { ctx in
            UIColor.white.setFill()
            ctx.fill(CGRect(x: 0, y: 0, width: 64 * on / of, height: 4))
        }
    }
}

/// Draws the visor (glass, eyes, scan line, glare, glowing edge) the way Station's SVG does,
/// in the SVG's own units. Redraws only when the quantised spec changes.
final class DroneFaceRenderer {
    static let ppu: CGFloat = 16                         // pixels per SVG unit
    static let size = CGSize(width: 30 * ppu, height: 13 * ppu)
    private var lastKey: [Int] = []
    private let renderer: UIGraphicsImageRenderer = {
        let f = UIGraphicsImageRendererFormat(); f.scale = 1; f.opaque = false
        return UIGraphicsImageRenderer(size: DroneFaceRenderer.size, format: f)
    }()

    static func key(_ f: DroneFrame, dark: Bool) -> [Int] {
        let eyes: Int = f.eyes == .open ? 0 : f.eyes == .happy ? 1 : 2
        return [eyes, dark ? 1 : 0, Int((f.eyeScale.x * 40).rounded()), Int((f.eyeScale.y * 40).rounded()),
                Int((f.eyeOffset.x * 20).rounded()), Int((f.eyeOffset.y * 20).rounded()), Int((f.eyeAlpha * 20).rounded()),
                f.scanU.map { Int(($0 * 60).rounded()) } ?? -1,
                Int((f.glow.x * 64).rounded()), Int((f.glow.y * 64).rounded()), Int((f.glow.z * 64).rounded())]
    }

    func image(for f: DroneFrame, dark: Bool) -> UIImage? {
        let k = Self.key(f, dark: dark)
        guard k != lastKey else { return nil }
        lastKey = k
        return renderer.image { Self.draw(f, dark: dark, in: $0.cgContext) }
    }

    static func draw(_ f: DroneFrame, dark: Bool, in g: CGContext) {
        let p = DronePalette.body(dark: dark)
        let glow = UIColor(red: CGFloat(f.glow.x), green: CGFloat(f.glow.y), blue: CGFloat(f.glow.z), alpha: 1)
        g.clear(CGRect(origin: .zero, size: size))
        g.scaleBy(x: ppu, y: ppu); g.translateBy(x: -17, y: -22.5)
        let rect = CGRect(x: 17, y: 22.5, width: 30, height: 13)
        let visor = UIBezierPath(roundedRect: rect, cornerRadius: 6.5).cgPath
        g.saveGState()
        g.addPath(visor); g.clip()
        let cs = CGColorSpaceCreateDeviceRGB()
        let vg = CGGradient(colorsSpace: cs, colors: [MascotPalette.srgb(p.visor1).cgColor, MascotPalette.srgb(p.visor2).cgColor] as CFArray, locations: [0, 1])!
        g.drawLinearGradient(vg, start: CGPoint(x: 0, y: rect.minY), end: CGPoint(x: 0, y: rect.maxY), options: [])
        if let u = f.scanU {
            g.setFillColor(glow.withAlphaComponent(0.55).cgColor)
            g.fill(CGRect(x: 17, y: 21 + 15 * CGFloat(u), width: 30, height: 2.4))
        }
        g.saveGState()
        g.translateBy(x: CGFloat(f.eyeOffset.x), y: CGFloat(f.eyeOffset.y))
        g.setShadow(offset: .zero, blur: 1.4 * ppu, color: glow.withAlphaComponent(0.7).cgColor)
        g.setFillColor(glow.cgColor); g.setStrokeColor(glow.cgColor)
        g.setLineWidth(2.1); g.setLineCap(.round)
        switch f.eyes {
        case .open:
            g.setAlpha(CGFloat(f.eyeAlpha))
            for (x, s) in [(CGFloat(23.4), f.eyeScale.x), (CGFloat(35.4), f.eyeScale.y)] {
                let h = 7.2 * CGFloat(s), cy: CGFloat = 25.4 + 3.6
                let r = CGRect(x: x, y: cy - h / 2, width: 5.2, height: h)
                g.addPath(UIBezierPath(roundedRect: r, cornerRadius: min(2.6, h / 2)).cgPath); g.fillPath()
            }
        case .happy:
            for x0 in [CGFloat(23.2), 35.2] {
                g.move(to: CGPoint(x: x0, y: 31)); g.addQuadCurve(to: CGPoint(x: x0 + 5.6, y: 31), control: CGPoint(x: x0 + 2.8, y: 26.4))
            }
            g.strokePath()
        case .sad:
            g.move(to: CGPoint(x: 23.4, y: 30.9)); g.addLine(to: CGPoint(x: 28.6, y: 28.6))
            g.move(to: CGPoint(x: 35.4, y: 28.6)); g.addLine(to: CGPoint(x: 40.6, y: 30.9))
            g.strokePath()
        }
        g.restoreGState()
        g.setFillColor(UIColor(white: 1, alpha: 0.09).cgColor)
        g.addPath(UIBezierPath(roundedRect: CGRect(x: 19, y: 23.4, width: 26, height: 2.6), cornerRadius: 1.3).cgPath); g.fillPath()
        g.restoreGState()
        g.addPath(UIBezierPath(roundedRect: rect.insetBy(dx: 0.35, dy: 0.35), cornerRadius: 6.15).cgPath)
        g.setStrokeColor(glow.withAlphaComponent(0.45).cgColor); g.setLineWidth(0.7); g.strokePath()
    }
}
