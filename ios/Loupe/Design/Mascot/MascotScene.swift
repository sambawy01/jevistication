import SceneKit
import UIKit
import simd

/// The rigged robot, built in code from primitives (ported from the web prototype's three.js
/// Lumi: same proportions, materials, rig and camera). One template scene is built once and
/// cloned per `MascotView`: clones share geometry and the body materials; each instance gets its
/// own face texture and glow materials.
final class MascotModel {
    let scene: SCNScene
    let root: SCNNode, hips: SCNNode, torso: SCNNode, neck: SCNNode, head: SCNNode
    let arms: [(shoulder: SCNNode, elbow: SCNNode, side: Float)]
    let legs: [SCNNode]
    let chest: SCNNode
    let chestDots: [SCNNode]
    let faceMaterial: SCNMaterial
    let slotMaterial: SCNMaterial, tipMaterial: SCNMaterial, dotMaterial: SCNMaterial
    let camera: SCNNode
    private let face = MascotFaceRenderer()

    init() {
        let t = MascotTemplate.shared
        scene = SCNScene()
        scene.background.contents = UIColor.clear
        scene.lightingEnvironment.contents = t.environment
        scene.lightingEnvironment.intensity = 0.7
        let world = t.world.clone()
        // clone() shares each SCNGeometry (and its mesh) with the template. Two mascots on screen
        // at once (Now's card under the game) then have two render threads building renderable
        // data for one mesh, which double-frees inside SceneKit. Give every instance its own mesh.
        world.enumerateHierarchy { node, _ in
            guard let g = node.geometry else { return }
            let own = SCNGeometry(sources: g.sources, elements: g.elements)
            own.materials = g.materials
            node.geometry = own
        }
        scene.rootNode.addChildNode(world)
        func n(_ name: String) -> SCNNode { world.childNode(withName: name, recursively: true)! }
        root = n("root"); hips = n("hips"); torso = n("torso"); neck = n("neck"); head = n("head")
        arms = [(n("shoulder.A"), n("elbow.A"), 1), (n("shoulder.B"), n("elbow.B"), -1)]
        legs = [n("leg.B"), n("leg.A")]
        chest = n("chest")
        chestDots = (0..<3).map { n("dot.\($0)") }
        camera = n("camera")

        // Per-instance materials: face texture and glows change per frame.
        faceMaterial = t.faceMaterial.copy() as! SCNMaterial
        n("face").geometry = (n("face").geometry!.copy() as! SCNGeometry)
        n("face").geometry!.materials = [faceMaterial]
        slotMaterial = t.glowMaterial(); tipMaterial = t.glowMaterial(); dotMaterial = t.glowMaterial()
        chest.geometry = (chest.geometry!.copy() as! SCNGeometry); chest.geometry!.materials = [slotMaterial]
        for tip in ["tip.A", "tip.B"] { let tn = n(tip); tn.geometry = (tn.geometry!.copy() as! SCNGeometry); tn.geometry!.materials = [tipMaterial] }
        for d in chestDots { d.geometry = (d.geometry!.copy() as! SCNGeometry); d.geometry!.materials = [dotMaterial] }
    }

    /// Applies one frame of the rig to the nodes (mirrors the prototype's frameGL).
    func apply(_ f: MascotFrame) {
        let c = f.pose
        root.simdPosition.y = Float(c.y)
        root.simdOrientation = MascotModel.euler(0, c.twist, c.sway)
        torso.simdOrientation = MascotModel.euler(c.lean, 0, 0)
        let b = Float(f.breath)
        torso.simdScale = SIMD3(1 + 0.008 * b, 1 + 0.015 * (b * 0.5 + 0.5), 1 + 0.008 * b)
        hips.simdPosition.y = 0.36 - Float(c.kn) * 0.12
        for l in legs { l.simdOrientation = MascotModel.euler(-c.kn * 0.6, 0, 0) }
        head.simdOrientation = MascotModel.euler(c.hx, c.hy, c.hz)
        neck.simdPosition.y = 0.5 - Float(c.sh) * 0.6
        for a in arms {
            let A = a.side > 0
            a.shoulder.simdPosition.y = 0.36 + Float(c.sh)
            a.shoulder.simdOrientation = MascotModel.euler(-(A ? c.aAf : c.aBf), 0, Double(a.side) * (A ? c.aAo : c.aBo))
            a.elbow.simdOrientation = MascotModel.euler(-(A ? c.aAx : c.aBx), 0, Double(a.side) * (A ? c.aAe : c.aBe))
        }
        slotMaterial.emission.contents = MascotPalette.mix(MascotPalette.cy0, MascotPalette.cy1, f.chest * 0.6)
        chest.simdScale.x = 0.06 * Float(1 + 0.12 * f.chest)
        tipMaterial.emission.contents = MascotPalette.mix(MascotPalette.white, MascotPalette.cyan, f.tips)
        for (i, d) in chestDots.enumerated() { d.isHidden = i >= f.chestDots }
        dotMaterial.emission.contents = MascotPalette.cyan
        if let img = face.image(for: f) { faceMaterial.diffuse.contents = img }
    }

    /// three.js Euler order XYZ: q = qx * qy * qz.
    static func euler(_ x: Double, _ y: Double, _ z: Double) -> simd_quatf {
        simd_quatf(angle: Float(x), axis: [1, 0, 0]) * simd_quatf(angle: Float(y), axis: [0, 1, 0]) * simd_quatf(angle: Float(z), axis: [0, 0, 1])
    }
}

enum MascotPalette {
    static func srgb(_ hex: UInt32) -> UIColor {
        UIColor(red: CGFloat((hex >> 16) & 0xFF) / 255, green: CGFloat((hex >> 8) & 0xFF) / 255, blue: CGFloat(hex & 0xFF) / 255, alpha: 1)
    }
    static let white = srgb(0xF2F3F6), band = srgb(0x8FA3DF), visor = srgb(0x07080A)
    static let cy0 = srgb(0x2AAFC0), cy1 = srgb(0xE8FFFF), cyan = srgb(0x6FF6F6)
    static func mix(_ a: UIColor, _ b: UIColor, _ k: Double) -> UIColor {
        var (r1, g1, b1, a1, r2, g2, b2, a2): (CGFloat, CGFloat, CGFloat, CGFloat, CGFloat, CGFloat, CGFloat, CGFloat) = (0, 0, 0, 0, 0, 0, 0, 0)
        a.getRed(&r1, green: &g1, blue: &b1, alpha: &a1); b.getRed(&r2, green: &g2, blue: &b2, alpha: &a2)
        let k = CGFloat(min(1, max(0, k)))
        return UIColor(red: r1 + (r2 - r1) * k, green: g1 + (g2 - g1) * k, blue: b1 + (b2 - b1) * k, alpha: 1)
    }
}

/// The shared template: geometry and body materials built once per process.
final class MascotTemplate {
    static let shared = MascotTemplate()
    let world = SCNNode()
    let environment: UIImage
    let faceMaterial: SCNMaterial

    private let WHITE: SCNMaterial, BAND: SCNMaterial, SOLE: SCNMaterial, VISOR: SCNMaterial

    func glowMaterial() -> SCNMaterial {
        let m = SCNMaterial(); m.lightingModel = .constant
        m.diffuse.contents = UIColor.black; m.emission.contents = MascotPalette.cyan
        return m
    }

    private init() {
        func pbr(_ color: UIColor, rough: CGFloat, coat: CGFloat = 0, coatRough: CGFloat = 0, metal: CGFloat = 0) -> SCNMaterial {
            let m = SCNMaterial(); m.lightingModel = .physicallyBased
            m.diffuse.contents = color; m.roughness.contents = rough; m.metalness.contents = metal
            if coat > 0 { m.clearCoat.contents = coat; m.clearCoatRoughness.contents = coatRough }
            return m
        }
        WHITE = pbr(MascotPalette.white, rough: 0.35, coat: 0.6, coatRough: 0.25)
        BAND = pbr(MascotPalette.band, rough: 0.4, metal: 0.05)
        SOLE = pbr(MascotPalette.band, rough: 0.45)
        VISOR = pbr(UIColor(white: 0.01, alpha: 1), rough: 0.22, coat: 0.5, coatRough: 0.08)
        faceMaterial = SCNMaterial()
        faceMaterial.lightingModel = .constant
        faceMaterial.diffuse.contents = UIColor.clear
        faceMaterial.transparencyMode = .aOne
        faceMaterial.blendMode = .alpha
        faceMaterial.writesToDepthBuffer = false
        faceMaterial.isDoubleSided = false
        environment = MascotTemplate.studio()
        build()
    }

    /// A soft studio room for the clearcoat reflections (same gradient as the prototype).
    static func studio() -> UIImage {
        let size = CGSize(width: 512, height: 256)
        return UIGraphicsImageRenderer(size: size).image { ctx in
            let g = ctx.cgContext
            let cs = CGColorSpaceCreateDeviceRGB()
            let colors = [0xFFFFFF, 0xEEF1F7, 0xC9D2E6, 0x6B7FB0].map { MascotPalette.srgb(UInt32($0)).cgColor } as CFArray
            let grad = CGGradient(colorsSpace: cs, colors: colors, locations: [0, 0.45, 0.6, 1])!
            g.drawLinearGradient(grad, start: .zero, end: CGPoint(x: 0, y: size.height), options: [])
            g.setFillColor(UIColor.white.cgColor)
            g.setShadow(offset: .zero, blur: 16, color: UIColor.white.cgColor)
            g.fill(CGRect(x: 160, y: 40, width: 110, height: 60)); g.fill(CGRect(x: 340, y: 56, width: 50, height: 40))
        }
    }

    private func M(_ geo: SCNGeometry, _ mat: SCNMaterial, _ x: Float = 0, _ y: Float = 0, _ z: Float = 0) -> SCNNode {
        geo.materials = [mat]
        let n = SCNNode(geometry: geo); n.simdPosition = [x, y, z]; return n
    }
    /// three.js capsule from the prototype: top sphere at the joint, body hanging down `len`.
    private func capsule(_ r: CGFloat, _ len: CGFloat, _ mat: SCNMaterial) -> SCNNode {
        let n = M(SCNCapsule(capRadius: r, height: len + 2 * r), mat, 0, -Float(len) / 2)
        let g = SCNNode(); g.addChildNode(n); return g
    }
    private func ring(_ r: CGFloat, _ tube: CGFloat, _ y: Float, _ mat: SCNMaterial? = nil) -> SCNNode {
        M(SCNTorus(ringRadius: r, pipeRadius: tube), mat ?? BAND, 0, y)
    }
    private func sphere(_ r: CGFloat, _ seg: Int = 24) -> SCNSphere { let s = SCNSphere(radius: r); s.segmentCount = seg; return s }

    private func build() {
        let root = SCNNode(); root.name = "root"; world.addChildNode(root)
        let hips = SCNNode(); hips.name = "hips"; hips.simdPosition.y = 0.36; root.addChildNode(hips)
        for (sx, tag) in [(Float(-1), "B"), (Float(1), "A")] {
            let L = SCNNode(); L.name = "leg.\(tag)"; L.simdPosition = [sx * 0.13, 0.02, 0]; hips.addChildNode(L)
            L.addChildNode(capsule(0.095, 0.2, WHITE)); L.addChildNode(ring(0.097, 0.007, -0.07)); L.addChildNode(ring(0.097, 0.007, -0.15))
            let boot = M(sphere(1, 32), WHITE, sx * 0.01, -0.3, 0.035); boot.simdScale = [0.13, 0.085, 0.165]; L.addChildNode(boot)
            let soleGeo = SCNCylinder(radius: 1, height: 1); soleGeo.radialSegmentCount = 32
            let sole = M(soleGeo, SOLE, sx * 0.01, -0.37, 0.035); sole.simdScale = [0.128, 0.022, 0.16]; L.addChildNode(sole)
        }
        let torso = SCNNode(); torso.name = "torso"; hips.addChildNode(torso)
        var prof: [SIMD2<Float>] = []
        for i in 0...24 {
            let a = Float(i) / 24, y = -0.06 + a * 0.56
            let r = 0.27 * sin(acos(1 - 2 * a)) * (1 - 0.22 * a) + 0.005
            prof.append([max(0.01, r), y])
        }
        torso.addChildNode(M(MascotMesh.lathe(prof, segments: 40), WHITE))
        torso.addChildNode(ring(0.262, 0.009, 0.27))
        let chest = M(sphere(1, 20), glowMaterial(), 0, 0.41, 0.205); chest.name = "chest"
        chest.simdScale = [0.06, 0.016, 0.02]; chest.simdOrientation = simd_quatf(angle: -0.5, axis: [1, 0, 0]); torso.addChildNode(chest)
        // thinking: three chest dots under the slot
        for i in 0..<3 {
            let d = M(sphere(0.013, 10), glowMaterial(), Float(i - 1) * 0.045, 0.35, 0.235); d.name = "dot.\(i)"; d.isHidden = true
            torso.addChildNode(d)
        }
        for (sx, tag) in [(Float(1), "A"), (Float(-1), "B")] {
            let sh = SCNNode(); sh.name = "shoulder.\(tag)"; sh.simdPosition = [sx * 0.27, 0.36, 0]; torso.addChildNode(sh)
            sh.addChildNode(capsule(0.075, 0.17, WHITE)); sh.addChildNode(ring(0.077, 0.006, -0.06))
            let el = SCNNode(); el.name = "elbow.\(tag)"; el.simdPosition.y = -0.17; sh.addChildNode(el)
            el.addChildNode(capsule(0.07, 0.14, WHITE)); el.addChildNode(ring(0.073, 0.008, -0.12))
            let hand = M(sphere(1), WHITE, 0, -0.2, 0); hand.simdScale = [0.085, 0.095, 0.06]; el.addChildNode(hand)
            el.addChildNode(M(sphere(0.032, 12), WHITE, -sx * 0.07, -0.17, 0.03))
        }
        let neck = SCNNode(); neck.name = "neck"; neck.simdPosition.y = 0.5; torso.addChildNode(neck)
        let neckGeo = SCNCone(topRadius: 0.07, bottomRadius: 0.09, height: 0.08); neckGeo.radialSegmentCount = 20
        neck.addChildNode(M(neckGeo, WHITE, 0, 0.02))
        let head = SCNNode(); head.name = "head"; head.simdPosition.y = 0.42; neck.addChildNode(head)
        let shell = M(MascotMesh.supersphere(0.55), WHITE); shell.simdScale = [0.64, 0.46, 0.46]; head.addChildNode(shell)
        let visor = M(MascotMesh.supersphere(0.5), VISOR, 0, -0.01, 0.04); visor.simdScale = [0.53, 0.36, 0.44]; head.addChildNode(visor)
        let face = M(SCNPlane(width: 0.98, height: 0.62), faceMaterial, 0, -0.01, 0.49); face.name = "face"; face.renderingOrder = 2
        head.addChildNode(face)
        let tipM = glowMaterial()
        for (sx, tag) in [(Float(-1), "B"), (Float(1), "A")] {
            let pod = SCNNode(); pod.simdPosition = [sx * 0.635, -0.03, 0]; head.addChildNode(pod)
            let cup = M(sphere(1, 28), WHITE); cup.simdScale = [0.07, 0.16, 0.15]; pod.addChildNode(cup)
            let bandGeo = SCNTorus(ringRadius: 0.135, pipeRadius: 0.022); bandGeo.ringSegmentCount = 40; bandGeo.pipeSegmentCount = 8
            let band = M(bandGeo, BAND, sx * 0.012, 0, 0)
            band.simdOrientation = simd_quatf(angle: .pi / 2, axis: [0, 0, 1]); band.simdScale = [1.08, 1, 1]; pod.addChildNode(band)
            let ant = SCNNode(); ant.simdPosition = [sx * 0.02, 0.12, -0.03]; ant.simdOrientation = simd_quatf(angle: -sx * 0.12, axis: [0, 0, 1])
            pod.addChildNode(ant)
            let stalk = SCNCone(topRadius: 0.018, bottomRadius: 0.03, height: 0.22); stalk.radialSegmentCount = 12
            ant.addChildNode(M(stalk, WHITE, 0, 0.11))
            let tip = M(sphere(0.036, 16), tipM, 0, 0.235); tip.name = "tip.\(tag)"; ant.addChildNode(tip)
        }
        // soft contact shadow: a blurred ellipse under the boots (no shadow maps, cheap)
        let shadow = M(SCNPlane(width: 0.9, height: 0.36), MascotTemplate.shadowMaterial(), 0, -0.012, 0.03)
        shadow.simdOrientation = simd_quatf(angle: -.pi / 2, axis: [1, 0, 0]); shadow.renderingOrder = -1
        world.addChildNode(shadow)

        // lights (prototype: hemisphere + warm key + two cool rims)
        func light(_ type: SCNLight.LightType, _ color: UInt32, _ intensity: CGFloat, _ pos: SIMD3<Float>? = nil) {
            let l = SCNLight(); l.type = type; l.color = MascotPalette.srgb(color); l.intensity = intensity
            let n = SCNNode(); n.light = l
            if let pos { n.simdPosition = pos; n.simdLook(at: [0, 0.9, 0]) }
            world.addChildNode(n)
        }
        light(.ambient, 0xB8C4E6, 140)
        light(.directional, 0xFFF1E0, 1350, [-2.5, 4, 5])
        light(.directional, 0x7FB0FF, 1600, [3.5, 2, -4])
        light(.directional, 0x9FC0FF, 900, [-4, 1, -3])

        let cam = SCNCamera(); cam.fieldOfView = 26; cam.projectionDirection = .vertical; cam.zNear = 0.1; cam.zFar = 50
        cam.wantsHDR = false
        let camNode = SCNNode(); camNode.name = "camera"; camNode.camera = cam
        camNode.simdPosition = [0, 1.0, 5.0]; camNode.simdLook(at: [0, 0.95, 0])
        world.addChildNode(camNode)
    }

    private static func shadowMaterial() -> SCNMaterial {
        let img = UIGraphicsImageRenderer(size: CGSize(width: 128, height: 64)).image { ctx in
            let cs = CGColorSpaceCreateDeviceRGB()
            let grad = CGGradient(colorsSpace: cs, colors: [UIColor(white: 0, alpha: 0.32).cgColor, UIColor(white: 0, alpha: 0).cgColor] as CFArray, locations: [0, 1])!
            ctx.cgContext.scaleBy(x: 1, y: 0.5)
            ctx.cgContext.drawRadialGradient(grad, startCenter: CGPoint(x: 64, y: 64), startRadius: 0, endCenter: CGPoint(x: 64, y: 64), endRadius: 64, options: [])
        }
        let m = SCNMaterial(); m.lightingModel = .constant
        m.diffuse.contents = img; m.writesToDepthBuffer = false; m.blendMode = .alpha
        return m
    }
}

/// Custom meshes the primitives do not cover: the rounded-box helmet and the egg torso.
enum MascotMesh {
    /// A unit sphere pushed toward a rounded box: each coordinate raised to `e` (< 1 squares it).
    static func supersphere(_ e: Float, seg: Int = 48) -> SCNGeometry {
        let rings = seg * 3 / 4
        var p: [SIMD3<Float>] = []
        for i in 0...rings {
            let v = Float(i) / Float(rings) * .pi
            for j in 0...seg {
                let u = Float(j) / Float(seg) * 2 * .pi
                let s = SIMD3<Float>(-cos(u) * sin(v), cos(v), sin(u) * sin(v))
                p.append(SIMD3(f(s.x, e), f(s.y, e), f(s.z, e)))
            }
        }
        return grid(p, cols: seg + 1, rows: rings + 1)
    }
    private static func f(_ v: Float, _ e: Float) -> Float { (v < 0 ? -1 : 1) * pow(abs(v), e) }

    /// Revolves a (radius, y) profile around the y axis, like THREE.LatheGeometry.
    static func lathe(_ prof: [SIMD2<Float>], segments: Int) -> SCNGeometry {
        var p: [SIMD3<Float>] = []
        for pt in prof.reversed() {
            for j in 0...segments {
                let u = Float(j) / Float(segments) * 2 * .pi
                p.append([pt.x * sin(u), pt.y, pt.x * cos(u)])
            }
        }
        return grid(p, cols: segments + 1, rows: prof.count)
    }

    /// Triangulates a row-major grid of points with smooth normals accumulated from the faces.
    private static func grid(_ p: [SIMD3<Float>], cols: Int, rows: Int) -> SCNGeometry {
        var idx: [UInt32] = []
        var nrm = [SIMD3<Float>](repeating: .zero, count: p.count)
        for r in 0..<(rows - 1) {
            for c in 0..<(cols - 1) {
                let a = UInt32(r * cols + c), b = a + 1, d = UInt32((r + 1) * cols + c), e = d + 1
                for tri in [[a, d, b], [b, d, e]] {
                    let n = cross(p[Int(tri[1])] - p[Int(tri[0])], p[Int(tri[2])] - p[Int(tri[0])])
                    if length(n) > 1e-9 { for i in tri { nrm[Int(i)] += n } ; idx += tri }
                }
            }
        }
        // weld the seam and poles: same position → same normal
        var byPos: [SIMD3<Int32>: SIMD3<Float>] = [:]
        func key(_ v: SIMD3<Float>) -> SIMD3<Int32> { SIMD3(Int32((v.x * 1e4).rounded()), Int32((v.y * 1e4).rounded()), Int32((v.z * 1e4).rounded())) }
        for (i, v) in p.enumerated() { byPos[key(v), default: .zero] += nrm[i] }
        let normals = p.map { normalize(byPos[key($0)] ?? [0, 1, 0]) }
        let vs = SCNGeometrySource(vertices: p.map { SCNVector3($0) })
        let ns = SCNGeometrySource(normals: normals.map { SCNVector3($0) })
        return SCNGeometry(sources: [vs, ns], elements: [SCNGeometryElement(indices: idx, primitiveType: .triangles)])
    }
}
