import SpriteKit
import UIKit

/// The game's colours: the dark neon palette (Theme.swift, `Palette.UI`) on a night river.
enum GameColors {
    static let water = Palette.UI.water
    static let waterLine = Palette.UI.waterLine
    static let land = Palette.UI.land
    static let landShade = Palette.UI.landShade
    static let shore = Palette.UI.shore
    static let bullet = Palette.UI.bullet
    static let bridge = Palette.UI.bridge
    static let bridgeStripe = Palette.UI.amber
    static let spark: [UIColor] = [Palette.UI.amber, Palette.UI.red, Palette.UI.paper]
}

/// Pixel sprites drawn from character grids, sampled nearest-neighbour so they stay crisp at any
/// scale. Original art; nothing is taken from any commercial game.
enum PixelArt {
    private static let palette: [Character: UIColor] = [
        "c": Palette.UI.cyan, "b": Palette.UI.blue, "n": Palette.UI.navy, "w": Palette.UI.paper,
        "a": Palette.UI.amber, "m": Palette.UI.mint, "i": Palette.UI.ink, "g": Palette.UI.snow,
    ]

    static let player = texture([
        ".....cc.....",
        ".....cc.....",
        "....cwwc....",
        "....cwwc....",
        "...bccccb...",
        ".bbbccccbbb.",
        "bbbbccccbbbb",
        "b...cccc...b",
        ".....cc.....",
        "....bccb....",
        "...bb..bb...",
        "............",
    ])

    static let boat = texture([
        "......ww..........",
        ".....wiiw.........",
        "..wwwwwwwwwwww....",
        "bbbbbbbbbbbbbbbbbb",
        ".bbbbbbbbbbbbbbbb.",
        "..nnnnnnnnnnnnnn..",
        "..................",
    ])

    static let heli = texture([
        "wwwwwwwwwwwww",
        "......w......",
        "....aaaaa....",
        "...aaiiaaaaaa",
        "a..aaaaaa...a",
        "aaaaaaaa.....",
        "....a..a.....",
        "...aaaaaa....",
    ])

    static let depot = texture([
        "mmmmmmm",
        "mwwwwwm",
        "mwmmmmm",
        "mwwwwmm",
        "mwmmmmm",
        "mwmmmmm",
        "mmmmmmm",
        "wwwwwww",
        "mmmmmmm",
        "wwwwwww",
        "mmmmmmm",
        "mmmmmmm",
    ])

    static let bridge = texture([
        "iaiiiaiiiaiiiaiiiaii",
        "iiiiiiiiiiiiiiiiiiii",
        "wwwwwwwwwwwwwwwwwwww",
        "iiiiiiiiiiiiiiiiiiii",
    ])

    static func texture(_ rows: [String]) -> SKTexture {
        let h = rows.count, w = rows.first?.count ?? 0
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = false
        let image = UIGraphicsImageRenderer(size: CGSize(width: w, height: h), format: format).image { ctx in
            for (y, row) in rows.enumerated() {
                for (x, ch) in row.enumerated() {
                    guard let color = palette[ch] else { continue }
                    color.setFill()
                    ctx.fill(CGRect(x: x, y: y, width: 1, height: 1))
                }
            }
        }
        let t = SKTexture(image: image)
        t.filteringMode = .nearest
        return t
    }

    /// A 1×1 white texture, tinted per node, for rectangles that should stay crisp.
    static let solid: SKTexture = {
        let t = texture(["w"])
        return t
    }()
}
