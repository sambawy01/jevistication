#if DEBUG
import UIKit

/// DEBUG only: calls one pasteboard API so a UI test can see whether iOS shows its paste prompt for it
/// (`ClipboardProbeUITests`, `-LoupeFixtures -LoupeClipboard -LoupeClipboardProbe <api>`). The result
/// shown is a count or a yes/no, never the copied text.
enum ClipboardProbe {
    @MainActor
    static func run(_ api: String) async -> String {
        let pb = UIPasteboard.general
        switch api {
        case "changeCount":
            return "changeCount=\(pb.changeCount)"
        case "hasStrings":
            return "hasStrings=\(pb.hasStrings) hasURLs=\(pb.hasURLs)"
        case "detectPatterns":
            // iOS 14 form (string patterns).
            let found: Set<UIPasteboard.DetectionPattern> = await withCheckedContinuation { cont in
                pb.detectPatterns(for: [.probableWebURL, .probableWebSearch, .number]) { r in cont.resume(returning: (try? r.get()) ?? []) }
            }
            return "patterns=\(found.map(\.rawValue).sorted())"
        case "detectedPatterns":
            // iOS 15+ key-path form, all seven patterns Loupe asks about (what ClipboardMonitor uses).
            let found = (try? await pb.detectedPatterns(for: Set(SystemClipboard.keyPaths.map(\.0)))) ?? []
            return "patterns=\(SystemClipboard.keyPaths.filter { found.contains($0.0) }.map(\.1.rawValue).sorted())"
        case "detectedValues":
            let v = try? await pb.detectedValues(for: [\.probableWebURL])
            return "values=\(v.map { $0.probableWebURL.isEmpty ? "empty" : "\($0.probableWebURL.count) chars" } ?? "nil")"
        case "string":
            return "string=\(pb.string.map { "\($0.count) chars" } ?? "nil")"
        default:
            return "unknown api"
        }
    }
}
#endif
