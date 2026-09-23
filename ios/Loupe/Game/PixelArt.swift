import SpriteKit
import UIKit

/// The game's colours: the app's clean-room palette on a navy river.
enum GameColors {
    static let water = UIColor(red: 0x0E / 255, green: 0x22 / 255, blue: 0x5E / 255, alpha: 1)
    static let waterLine = UIColor(red: 0x16 / 255, green: 0x30 / 255, blue: 0x7F / 255, alpha: 1)
    static let land = UIColor(red: 0xDD / 255, green: 0xE6 / 255, blue: 0xF5 / 255, alpha: 1)
    static let landShade = UIColor(red: 0xC6 / 255, green: 0xD3 / 255, blue: 0xEA / 255, alpha: 1)
    static let shore = UIColor(red: 0x06 / 255, green: 0xB6 / 255, blue: 0xD4 / 255, alpha: 1)
    static let bullet = UIColor(red: 0x7D / 255, green: 0xE8 / 255, blue: 0xF7 / 255, alpha: 1)
    static let bridge = UIColor(red: 0x0A / 255, green: 0x12 / 255, blue: 0x22 / 255, alpha: 1)
    static let bridgeStripe = UIColor(red: 0xF5 / 255, green: 0x9E / 255, blue: 0x0B / 255, alpha: 1)
    static let spark: [UIColor] = [
        UIColor(red: 0xF5 / 255, green: 0x9E / 255, blue: 0x0B / 255, alpha: 1),
        UIColor(red: 0xEF / 255, green: 0x44 / 255, blue: 0x44 / 255, alpha: 1),
        .white,
    ]
}

/// Pixel sprites drawn from character grids, sampled nearest-neighbour so they stay crisp at any
/// scale. Original art; nothing is taken from any commercial game.
enum PixelArt {
    private static let palette: [Character: UIColor] = [
        "c": UIColor(red: 0x06 / 255, green: 0xB6 / 255, blue: 0xD4 / 255, alpha: 1),   // cyan
        "b": UIColor(red: 0x2F / 255, green: 0x6B / 255, blue: 0xFF / 255, alpha: 1),   // blue
        "n": UIColor(red: 0x0B / 255, green: 0x1B / 255, blue: 0x4D / 255, alpha: 1),   // navy
        "w": .white,
        "a": UIColor(red: 0xF5 / 255, green: 0x9E / 255, blue: 0x0B / 255, alpha: 1),   // amber
        "m": UIColor(red: 0x10 / 255, green: 0xB9 / 255, blue: 0x81 / 255, alpha: 1),   // mint
        "i": UIColor(red: 0x0A / 255, green: 0x12 / 255, blue: 0x22 / 255, alpha: 1),   // ink
        "g": UIColor(red: 0xDD / 255, green: 0xE6 / 255, blue: 0xF5 / 255, alpha: 1),   // ground
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
