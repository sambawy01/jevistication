import Foundation
import LoupeKit

/// Facts read from the KMP engine (LoupeKit.xcframework). Pure on-device reads, no I/O.
struct EngineInfo {
    var linked: Bool
    var builtInJudgments: Int
    var pslVersion: String

    static func load() -> EngineInfo {
        let judgments = BuiltInJudgments.shared.ALL.count
        let psl = LoupeKit.shared.publicSuffixListVersion
        return EngineInfo(linked: true, builtInJudgments: judgments, pslVersion: psl.isEmpty ? "unknown" : psl)
    }
}
