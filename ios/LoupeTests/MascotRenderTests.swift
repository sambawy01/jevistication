import XCTest
import SceneKit
@testable import Loupe

/// The robot mascot's built scene (2026-09-25 device bug): the render must match the designed
/// proportions, and no two mascots may share a mesh (the SceneKit double free).
///
/// The broken build replaced every node's geometry with `SCNGeometry(sources:elements:)`. For
/// SceneKit's parametric primitives `sources` is the unit mesh at the default parameters, so the
/// arms became huge capsules and tori. These tests measure the built tree and a real render, and
/// `testTheChecksCatchTheSourcesRebuild` proves they fail on exactly that rebuild.
final class MascotRenderTests: XCTestCase {
    /// Union of every visible geometry node's bounding box, in world space.
    static func worldBounds(_ root: SCNNode) -> (min: SIMD3<Float>, max: SIMD3<Float>) {
        var lo = SIMD3<Float>(repeating: .greatestFiniteMagnitude), hi = -lo
        root.enumerateHierarchy { node, _ in
            guard let g = node.geometry, !node.isHidden, node.name != "shadow" else { return }
            let (a, b) = g.boundingBox
            for x in [a.x, b.x] { for y in [a.y, b.y] { for z in [a.z, b.z] {
                let w = node.simdConvertPosition(SIMD3(Float(x), Float(y), Float(z)), to: nil)
                lo = simd_min(lo, w); hi = simd_max(hi, w)
            } } }
        }
        return (lo, hi)
    }

    /// The broken e984ed2 rebuild, applied to a tree (for the negative control).
    static func rebuildFromSources(_ root: SCNNode) {
        root.enumerateHierarchy { node, _ in
            guard let g = node.geometry else { return }
            let own = SCNGeometry(sources: g.sources, elements: g.elements)
            own.materials = g.materials
            node.geometry = own
        }
    }

    /// Alpha-coverage box of a render, as fractions of the image (x0, y0, x1, y1), and coverage.
    static func silhouette(_ scene: SCNScene, camera: SCNNode, px: Int = 240) -> (box: CGRect, coverage: Double)? {
        let r = SCNRenderer(device: MTLCreateSystemDefaultDevice(), options: nil)
        r.autoenablesDefaultLighting = false
        r.scene = scene; r.pointOfView = camera
        let img = r.snapshot(atTime: 0, with: CGSize(width: px, height: px), antialiasingMode: .none)
        guard let cg = img.cgImage else { return nil }
        let w = cg.width, h = cg.height
        var buf = [UInt8](repeating: 0, count: w * h * 4)
        let ctx = CGContext(data: &buf, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4,
                            space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
        ctx.draw(cg, in: CGRect(x: 0, y: 0, width: w, height: h))
        var x0 = w, y0 = h, x1 = -1, y1 = -1, n = 0
        for y in 0..<h { for x in 0..<w where buf[(y * w + x) * 4 + 3] > 200 {
            n += 1; x0 = min(x0, x); x1 = max(x1, x); y0 = min(y0, y); y1 = max(y1, y)
        } }
        guard n > 0 else { return (.zero, 0) }
        return (CGRect(x: Double(x0) / Double(w), y: Double(y0) / Double(h),
                       width: Double(x1 - x0 + 1) / Double(w), height: Double(y1 - y0 + 1) / Double(h)),
                Double(n) / Double(w * h))
    }

    private func restModel() -> MascotModel {
        let m = MascotModel()
        var rig = MascotRig(random: { 0.5 }); rig.reduceMotion = true; rig.set(.idle)
        m.apply(rig.step(dt: 1 / 60))
        return m
    }

    /// Designed extents (identical to the pre-e984ed2 build): about 1.45 wide with the ear pods,
    /// feet at 0, antenna tips at 1.75, and 0.95 deep.
    private func assertDesignedBounds(_ root: SCNNode, file: StaticString = #filePath, line: UInt = #line) {
        let b = Self.worldBounds(root)
        XCTAssertEqual(b.min.x, -0.723, accuracy: 0.02, file: file, line: line)
        XCTAssertEqual(b.max.x, 0.723, accuracy: 0.02, file: file, line: line)
        XCTAssertEqual(b.min.y, -0.005, accuracy: 0.02, file: file, line: line)
        XCTAssertEqual(b.max.y, 1.750, accuracy: 0.02, file: file, line: line)
        XCTAssertEqual(b.max.z - b.min.z, 0.95, accuracy: 0.03, file: file, line: line)
    }

    private func assertDesignedSilhouette(_ m: MascotModel, file: StaticString = #filePath, line: UInt = #line) throws {
        let s = try XCTUnwrap(Self.silhouette(m.scene, camera: m.camera), file: file, line: line)
        // The robot sits in the middle of the frame with margin all round, about a third covered.
        XCTAssertEqual(s.box.width, 0.617, accuracy: 0.04, file: file, line: line)
        XCTAssertEqual(s.box.height, 0.788, accuracy: 0.04, file: file, line: line)
        XCTAssertGreaterThan(s.box.minX, 0.1, file: file, line: line)
        XCTAssertGreaterThan(s.box.minY, 0.05, file: file, line: line)
        XCTAssertEqual(s.coverage, 0.321, accuracy: 0.04, file: file, line: line)
    }

    func testBuiltMascotHasTheDesignedProportions() {
        let m = restModel()
        assertDesignedBounds(m.scene.rootNode)
        // The primitives stay primitives: only the torso lathe and the two helmet shells are custom.
        var custom = 0
        m.scene.rootNode.enumerateHierarchy { n, _ in
            if let g = n.geometry, type(of: g) == SCNGeometry.self { custom += 1 }
        }
        XCTAssertEqual(custom, 3)
        // Limbs are limb-sized: every capsule and ring is smaller than the torso.
        m.scene.rootNode.enumerateHierarchy { n, _ in
            guard let g = n.geometry, g is SCNCapsule || g is SCNTorus else { return }
            let (a, b) = g.boundingBox
            XCTAssertLessThan(Float(b.y - a.y), 0.45, "\(g) is taller than a limb")
            XCTAssertLessThan(Float(b.x - a.x), 0.6, "\(g) is wider than the torso")
        }
    }

    func testRenderedSilhouetteMatchesTheDesign() throws {
        try assertDesignedSilhouette(restModel())
    }

    func testTwoMascotsShareNoGeometryOrMaterial() {
        let a = restModel(), b = restModel()
        var ga: [ObjectIdentifier] = [], ma: [ObjectIdentifier] = []
        a.scene.rootNode.enumerateHierarchy { n, _ in
            if let g = n.geometry { ga.append(ObjectIdentifier(g)); ma += g.materials.map(ObjectIdentifier.init) }
        }
        var gb: Set<ObjectIdentifier> = [], mb: Set<ObjectIdentifier> = []
        b.scene.rootNode.enumerateHierarchy { n, _ in
            if let g = n.geometry { gb.insert(ObjectIdentifier(g)); mb.formUnion(g.materials.map(ObjectIdentifier.init)) }
        }
        XCTAssertFalse(ga.isEmpty)
        XCTAssertTrue(gb.isDisjoint(with: ga), "two mascots share a mesh: SceneKit double-frees when both render")
        XCTAssertTrue(mb.isDisjoint(with: ma))
        assertDesignedBounds(b.scene.rootNode)
    }

    /// Negative control: the broken e984ed2 rebuild fails both checks.
    func testTheChecksCatchTheSourcesRebuild() throws {
        let m = restModel()
        Self.rebuildFromSources(m.scene.rootNode)
        let b = Self.worldBounds(m.scene.rootNode)
        XCTAssertGreaterThan(b.max.x, 1.0)                        // the arms' unit capsules and tori
        let s = try XCTUnwrap(Self.silhouette(m.scene, camera: m.camera))
        XCTAssertGreaterThan(s.coverage, 0.5)
    }
}
