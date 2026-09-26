import AppIntents
import SwiftUI
import WidgetKit

/// Loupe's widgets (2026-09-26): "Check copied", a Home Screen and Lock Screen button that opens Loupe
/// and checks what you copied. It shows the last check's level and time only, never the link or its
/// website (the App Group keeps no clipboard content).
@main
struct LoupeWidgetBundle: WidgetBundle {
    var body: some Widget {
        CheckCopiedWidget()
    }
}

struct CheckCopiedEntry: TimelineEntry {
    let date: Date
    /// "safe" / "suspicious" / "dangerous", or nil before the first check.
    let lastLevel: String?
    let lastAt: Date?
}

struct CheckCopiedProvider: TimelineProvider {
    func placeholder(in context: Context) -> CheckCopiedEntry { CheckCopiedEntry(date: Date(), lastLevel: nil, lastAt: nil) }

    func getSnapshot(in context: Context, completion: @escaping (CheckCopiedEntry) -> Void) { completion(entry()) }

    func getTimeline(in context: Context, completion: @escaping (Timeline<CheckCopiedEntry>) -> Void) {
        completion(Timeline(entries: [entry()], policy: .never))
    }

    private func entry() -> CheckCopiedEntry {
        let d = ProtectionGroup.defaults
        let at = d.double(forKey: ClipboardShared.Keys.lastAt)
        return CheckCopiedEntry(date: Date(), lastLevel: d.string(forKey: ClipboardShared.Keys.lastLevel),
                                lastAt: at > 0 ? Date(timeIntervalSince1970: at) : nil)
    }
}

struct CheckCopiedWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: ClipboardShared.widgetKind, provider: CheckCopiedProvider()) { entry in
            CheckCopiedView(entry: entry)
                .containerBackground(for: .widget) {
                    LinearGradient(colors: [Color(red: 0.043, green: 0.082, blue: 0.188), Color(red: 0.027, green: 0.043, blue: 0.094)],
                                   startPoint: .top, endPoint: .bottom)
                }
        }
        .configurationDisplayName("Check copied")
        .description("Copied a link from a message? Check it with Loupe, on this iPhone.")
        .supportedFamilies([.systemSmall, .accessoryCircular, .accessoryRectangular])
    }
}

struct CheckCopiedView: View {
    let entry: CheckCopiedEntry
    @Environment(\.widgetFamily) private var family

    private static let cyan = Color(red: 0.133, green: 0.827, blue: 0.933)
    private static let ink = Color(red: 0.918, green: 0.941, blue: 1)
    private static let inkSoft = Color(red: 0.655, green: 0.706, blue: 0.831)

    var body: some View {
        switch family {
        case .accessoryCircular:
            Button(intent: CheckCopiedIntent()) {
                Image(systemName: "doc.on.clipboard").font(.title2)
            }
            .buttonStyle(.plain)
            .widgetAccentable()
            .accessibilityLabel("Check copied")
        case .accessoryRectangular:
            Button(intent: CheckCopiedIntent()) {
                HStack {
                    Image(systemName: "doc.on.clipboard")
                    VStack(alignment: .leading) {
                        Text("Check copied").font(.headline)
                        lastLine.font(.caption2)
                    }
                }
            }
            .buttonStyle(.plain)
        default:
            VStack(alignment: .leading, spacing: 8) {
                HStack(spacing: 6) {
                    Image(systemName: "magnifyingglass.circle.fill").foregroundStyle(Self.cyan)
                    Text("Loupe").font(.system(size: 13, weight: .bold, design: .rounded)).foregroundStyle(Self.ink)
                }
                Text("Copied a link? Check it first.").font(.caption).foregroundStyle(Self.inkSoft)
                    .fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: 0)
                Button(intent: CheckCopiedIntent()) {
                    Label("Check copied", systemImage: "doc.on.clipboard")
                        .font(.system(size: 13, weight: .semibold))
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .tint(Self.cyan)
                .foregroundStyle(Color(red: 0.016, green: 0.063, blue: 0.149))
                lastLine.font(.system(size: 10)).foregroundStyle(Self.inkSoft).lineLimit(1)
            }
        }
    }

    /// "Last: Dangerous · 5 min ago" (the time keeps counting on its own), or what the widget does.
    private var lastLine: Text {
        guard let level = entry.lastLevel, let at = entry.lastAt else { return Text("On this iPhone · 0 bytes out") }
        let word = level == "dangerous" ? "Dangerous" : level == "suspicious" ? "Suspicious" : "No warning signs"
        return Text("Last: \(word) · ") + Text(at, style: .relative) + Text(" ago")
    }
}
