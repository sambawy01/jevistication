import LoupeKit
import SpriteKit

/// Draws the world. Holds no game state of its own: every frame it asks the controller to advance
/// and then lays the river, entities and effects out from `session.world`. Nodes are pooled, so a
/// frame allocates nothing in steady state.
///
/// World → screen: a square cell of `size.width / COLUMNS` points; `VIEW_ROWS` rows fill the
/// height (the view is sized to that aspect). Row `y` in the world sits at `(y − cameraY) × cell`.
/// Positions snap to whole device pixels so the pixel art stays crisp while the river scrolls.
final class RiverScene: SKScene {
    weak var controller: GameController?
    var reducedMotion = false

    private let shake = SKNode()
    private let world = SKNode()
    private let landLayer = SKNode()
    private let entityLayer = SKNode()
    private let effectLayer = SKNode()
    private var landPool: [SKSpriteNode] = []
    private var shorePool: [SKSpriteNode] = []
    private var spritePool: [SKSpriteNode] = []
    private var landUsed = 0, shoreUsed = 0, spritesUsed = 0
    private let player = SKSpriteNode(texture: PixelArt.player)
    private var pixel: CGFloat = 1

    override init(size: CGSize) {
        super.init(size: size)
        scaleMode = .resizeFill
        anchorPoint = .zero
        backgroundColor = GameColors.water
        addChild(shake)
        shake.addChild(world)
        world.addChild(landLayer)
        world.addChild(entityLayer)
        world.addChild(effectLayer)
        player.anchorPoint = CGPoint(x: 0.5, y: 0)
        player.zPosition = 5
        entityLayer.addChild(player)
    }

    required init?(coder: NSCoder) { fatalError("not used") }

    override func didMove(to view: SKView) {
        pixel = 1 / max(view.contentScaleFactor, 1)
        view.ignoresSiblingOrder = true
    }

    private var cell: CGFloat { size.width / CGFloat(GameController.columns) }

    private func snap(_ v: CGFloat) -> CGFloat { (v / pixel).rounded() * pixel }

    override func update(_ currentTime: TimeInterval) {
        guard let controller else { return }
        MainActor.assumeIsolated {
            controller.advance(to: currentTime)
            render(controller)
        }
    }

    @MainActor
    private func render(_ controller: GameController) {
        let w = controller.session.world
        let c = cell
        let camera = CGFloat(w.cameraY)
        world.position = CGPoint(x: 0, y: snap(-camera * c))
        landUsed = 0; shoreUsed = 0; spritesUsed = 0

        let first = Int(w.cameraY.rounded(.down)) - 1
        let last = first + GameController.viewRows + 2
        let columns = GameController.columns
        for index in max(first, 0)...last {
            let row = GameSessions.shared.row(world: w, index: Int32(index))
            let y = CGFloat(index) * c
            let l = CGFloat(row.left), r = CGFloat(row.right)
            land(x: 0, width: l * c, y: y, height: c, shade: false)
            land(x: (CGFloat(columns) - r) * c, width: r * c, y: y, height: c, shade: false)
            shore(x: l * c - 2, y: y, height: c)
            shore(x: (CGFloat(columns) - r) * c, y: y, height: c)
            if row.hasIsland {
                let a = CGFloat(row.islandFrom), b = CGFloat(row.islandTo)
                land(x: a * c, width: (b - a) * c, y: y, height: c, shade: false)
                shore(x: a * c, y: y, height: c)
                shore(x: b * c - 2, y: y, height: c)
            }
        }

        for bridge in w.bridges where bridge.alive {
            sprite(PixelArt.bridge, x: CGFloat(bridge.x), y: CGFloat(bridge.y), w: CGFloat(bridge.width), h: 1, c: c)
        }
        for depot in w.depots where depot.alive {
            sprite(PixelArt.depot, x: CGFloat(depot.x), y: CGFloat(depot.y), w: CGFloat(depot.width), h: CGFloat(depot.height), c: c)
        }
        for enemy in w.enemies where enemy.alive {
            let heli = enemy.kind == EnemyKind.heli
            let node = sprite(heli ? PixelArt.heli : PixelArt.boat, x: CGFloat(enemy.x), y: CGFloat(enemy.y),
                              w: CGFloat(enemy.width), h: CGFloat(enemy.height), c: c)
            node.xScale = enemy.vx < 0 ? -abs(node.xScale) : abs(node.xScale)
        }
        for bullet in w.bullets {
            let node = sprite(PixelArt.solid, x: CGFloat(bullet.x), y: CGFloat(bullet.y), w: CGFloat(bullet.width), h: CGFloat(bullet.height), c: c)
            node.color = GameColors.bullet
            node.colorBlendFactor = 1
        }
        hideUnused()

        player.isHidden = w.over
        player.size = CGSize(width: CGFloat(Rules.shared.PLAYER_W) * c, height: CGFloat(Rules.shared.PLAYER_H) * c)
        player.position = CGPoint(x: snap(CGFloat(w.playerX) * c), y: snap(CGFloat(w.playerY) * c))
        let steer = controller.session.lastFlown.steer
        player.zRotation = CGFloat(-steer) * 0.12

        for effect in controller.drainEffects() {
            explode(at: CGPoint(x: CGFloat(effect.x) * c, y: CGFloat(effect.y) * c), big: effect.big, cell: c)
        }
    }

    // MARK: Pools

    private func land(x: CGFloat, width: CGFloat, y: CGFloat, height: CGFloat, shade: Bool) {
        guard width > 0 else { return }
        if landUsed == landPool.count {
            let n = SKSpriteNode(texture: PixelArt.solid)
            n.anchorPoint = .zero
            n.colorBlendFactor = 1
            landLayer.addChild(n)
            landPool.append(n)
        }
        let n = landPool[landUsed]
        landUsed += 1
        n.isHidden = false
        n.color = shade ? GameColors.landShade : GameColors.land
        n.position = CGPoint(x: snap(x), y: snap(y))
        n.size = CGSize(width: snap(width), height: snap(height) + pixel)
    }

    private func shore(x: CGFloat, y: CGFloat, height: CGFloat) {
        if shoreUsed == shorePool.count {
            let n = SKSpriteNode(texture: PixelArt.solid)
            n.anchorPoint = .zero
            n.color = GameColors.shore
            n.colorBlendFactor = 1
            n.zPosition = 1
            landLayer.addChild(n)
            shorePool.append(n)
        }
        let n = shorePool[shoreUsed]
        shoreUsed += 1
        n.isHidden = false
        n.position = CGPoint(x: snap(x), y: snap(y))
        n.size = CGSize(width: 2, height: snap(height) + pixel)
    }

    /// An entity: `x` is its centre column, `y` its bottom row, as in the game's `Box`.
    @discardableResult
    private func sprite(_ texture: SKTexture, x: CGFloat, y: CGFloat, w: CGFloat, h: CGFloat, c: CGFloat) -> SKSpriteNode {
        if spritesUsed == spritePool.count {
            let n = SKSpriteNode(texture: texture)
            n.anchorPoint = CGPoint(x: 0.5, y: 0)
            n.zPosition = 3
            entityLayer.addChild(n)
            spritePool.append(n)
        }
        let n = spritePool[spritesUsed]
        spritesUsed += 1
        n.isHidden = false
        if n.texture !== texture { n.texture = texture }
        n.colorBlendFactor = 0
        n.xScale = 1
        n.size = CGSize(width: snap(w * c), height: snap(h * c))
        n.position = CGPoint(x: snap(x * c), y: snap(y * c))
        return n
    }

    private func hideUnused() {
        for i in landUsed..<landPool.count { landPool[i].isHidden = true }
        for i in shoreUsed..<shorePool.count { shorePool[i].isHidden = true }
        for i in spritesUsed..<spritePool.count { spritePool[i].isHidden = true }
    }

    // MARK: Effects

    /// Square sparks flung out and faded. Reduced motion: a few, slower, and no screen shake.
    private func explode(at point: CGPoint, big: Bool, cell: CGFloat) {
        let count = reducedMotion ? (big ? 5 : 3) : (big ? 22 : 12)
        let reach = cell * (big ? 3.0 : 1.8) * (reducedMotion ? 0.5 : 1)
        let duration = reducedMotion ? 0.5 : 0.45
        for i in 0..<count {
            let n = SKSpriteNode(texture: PixelArt.solid)
            n.color = GameColors.spark[i % GameColors.spark.count]
            n.colorBlendFactor = 1
            let side = max(2, (cell * (big ? 0.45 : 0.3)).rounded())
            n.size = CGSize(width: side, height: side)
            n.position = point
            n.zPosition = 6
            effectLayer.addChild(n)
            let angle = Double(i) / Double(count) * 2 * .pi + Double(i % 3) * 0.4
            let d = reach * CGFloat(0.5 + Double((i * 7) % 5) / 8)
            let move = SKAction.moveBy(x: CGFloat(cos(angle)) * d, y: CGFloat(sin(angle)) * d, duration: duration)
            move.timingMode = .easeOut
            n.run(.sequence([.group([move, .fadeOut(withDuration: duration)]), .removeFromParent()]))
        }
        if big && !reducedMotion {
            let a = cell * 0.35
            shake.removeAllActions()
            shake.position = .zero
            shake.run(.sequence([
                .moveBy(x: a, y: 0, duration: 0.03), .moveBy(x: -2 * a, y: a / 2, duration: 0.05),
                .moveBy(x: 1.5 * a, y: -a, duration: 0.05), .moveBy(x: -0.5 * a, y: a / 2, duration: 0.04),
                .move(to: .zero, duration: 0.03),
            ]))
        }
    }
}
