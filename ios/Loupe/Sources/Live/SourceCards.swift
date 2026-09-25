import LoupeKit
import SwiftUI

// The Sources page's visual pieces (2026-09-25): the header, each source's glyph tile, badge and status ring, the
// rest state with its numbers, and the invitation to a first scan. Colours come from the palette only.

/// A source's glyph in a tile of its hue: lit when the source is on, an outline when it is off.
struct SourceGlyph: View {
    let id: String
    let on: Bool
    var size: CGFloat = 46

    var body: some View {
        let hue = SourceLook.hue(id)
        let shape = RoundedRectangle(cornerRadius: size * 0.28, style: .continuous)
        ZStack {
            shape.fill(on ? AnyShapeStyle(LinearGradient(colors: [hue.opacity(0.34), hue.opacity(0.08)], startPoint: .topLeading, endPoint: .bottomTrailing))
                          : AnyShapeStyle(Palette.groundMid))
            shape.stroke(on ? hue.opacity(0.75) : Palette.border, lineWidth: 1)
            Image(systemName: SourceLook.symbol(id))
                .symbolRenderingMode(.hierarchical)
                .font(.system(size: size * 0.42, weight: .semibold))
                .foregroundStyle(on ? hue : Palette.inkSoft)
                .neonGlow(hue, radius: 4, on: on)
        }
        .frame(width: size, height: size)
        .shadow(color: on ? hue.opacity(0.25) : .clear, radius: on ? 8 : 0)
        .accessibilityHidden(true)
    }
}

/// On device (read on this iPhone, nothing leaves it) or Online (the one source that uses the network).
struct SourceBadge: View {
    let online: Bool
    var body: some View {
        if online {
            Pill(text: "Online", color: Palette.blue, symbol: "globe")
        } else {
            Pill(text: "On device", color: Palette.okText, symbol: "iphone")
        }
    }
}

/// The count ring of a source at rest: the arc is how much of what Loupe can see it has read (Photos reads 300 a
/// scan, newest first, so the ring fills over several scans); the number is the items read.
struct StatusRing: View {
    let fraction: Double
    let count: Int
    let hue: Color
    var size: CGFloat = 64

    var body: some View {
        ZStack {
            Circle().stroke(Palette.track, lineWidth: 5)
            Circle()
                .trim(from: 0, to: max(0.001, min(1, fraction)))
                .stroke(AngularGradient(colors: [hue.opacity(0.55), hue], center: .center),
                        style: StrokeStyle(lineWidth: 5, lineCap: .round))
                .rotationEffect(.degrees(-90))
                .neonGlow(hue, radius: 3, on: count > 0)
            VStack(spacing: -2) {
                Text(Self.compact(count))
                    .font(Typeface.display(20)).monospacedDigit()
                    .foregroundStyle(Palette.ink)
                    .minimumScaleFactor(0.6).lineLimit(1)
                Text(count == 1 ? "ITEM" : "ITEMS")
                    .font(Typeface.mono(8, weight: .medium)).tracking(0.6)
                    .foregroundStyle(Palette.inkSoft)
            }
            .padding(8)
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }

    static func compact(_ n: Int) -> String {
        if n >= 10_000 { return "\(n / 1000)k" }
        if n >= 1000 { return String(format: "%.1fk", Double(n) / 1000) }
        return "\(n)"
    }
}

/// The resting numbers of a source: ring, count line (the test's `…count` text), detail, last scan.
struct SourceRestStats: View {
    let id: String
    let count: Int
    let countLine: String
    let countId: String
    let detail: String?
    let coverage: Double
    let lastScan: Date?
    let on: Bool

    var body: some View {
        HStack(alignment: .center, spacing: 14) {
            StatusRing(fraction: coverage, count: count, hue: on ? SourceLook.hue(id) : Palette.inkSoft)
            VStack(alignment: .leading, spacing: 3) {
                Text(countLine)
                    .font(.subheadline.weight(.semibold)).foregroundStyle(Palette.ink)
                    .accessibilityIdentifier(countId)
                if let detail, !detail.isEmpty {
                    Text(detail).font(.footnote).foregroundStyle(Palette.inkSoft)
                        .fixedSize(horizontal: false, vertical: true)
                }
                Text(Self.lastScanLine(lastScan))
                    .font(Typeface.mono(11)).foregroundStyle(Palette.inkSoft)
            }
            Spacer(minLength: 0)
        }
    }

    static func lastScanLine(_ d: Date?) -> String {
        guard let d else { return "Not scanned yet" }
        if Date().timeIntervalSince(d) < 60 { return "Last scan just now" }
        return "Last scan \(d.formatted(.relative(presentation: .named)))"
    }
}

/// A source that is on but has nothing read yet: say so and offer the first scan.
struct FirstScanInvite: View {
    let id: String
    let title: String
    let action: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text("Nothing read yet").font(.subheadline.weight(.semibold)).foregroundStyle(Palette.ink)
                Text("Run the first scan to see \(title.lowercased()) pass through the lens.")
                    .font(.footnote).foregroundStyle(Palette.inkSoft)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 8)
            Button("Scan now", action: action)
                .buttonStyle(.neonPrimary)
                .accessibilityIdentifier("sources.\(id).scanNow")
        }
        .padding(12)
        .background(Palette.groundMid, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).stroke(SourceLook.hue(id).opacity(0.4), style: StrokeStyle(lineWidth: 1, dash: [5, 4])))
    }
}

/// A small action in a card ("Scan again", "Choose more photos"): 44 pt tall, the source's hue.
struct CardAction: View {
    let title: String
    let symbol: String
    let hue: Color
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Label(title, systemImage: symbol)
                .font(.footnote.weight(.semibold))
                .foregroundStyle(hue)
                .padding(.horizontal, 12)
                .frame(minHeight: 44)
                .background(hue.opacity(0.1), in: Capsule())
                .overlay(Capsule().stroke(hue.opacity(0.35), lineWidth: 1))
        }
        .buttonStyle(.plain)
    }
}

/// The page header: the mascot, every item read across the sources that are on, which sources are connected,
/// the last scan, and what leaves the phone.
struct SourcesHeader: View {
    @ObservedObject var sources: SourcesService

    private struct Entry: Identifiable { let id: String; let title: String; let on: Bool }

    private var entries: [Entry] {
        [Entry(id: "sample", title: "Sample", on: sources.sampleEnabled)]
            + PhoneSource.allCases.map { Entry(id: $0.id, title: $0.title, on: sources.state($0).enabled) }
            + [Entry(id: "inbox", title: "Inbox", on: sources.inboxEnabled && !sources.inboxBatches.isEmpty)]
    }

    private var totalItems: Int {
        (sources.sampleEnabled ? Int(sources.sampleScan?.itemCount ?? 0) : 0)
            + PhoneSource.allCases.filter { sources.state($0).enabled }.map { sources.state($0).itemCount }.reduce(0, +)
            + (sources.inboxEnabled ? sources.inboxBatches.reduce(0) { $0 + Int($1.itemCount) } : 0)
    }

    private var lastScan: Date? {
        let sample = sources.sampleScan.map { Date(timeIntervalSince1970: Double($0.scannedAtEpochMillis) / 1000) }
        return ([sample] + PhoneSource.allCases.map { sources.state($0).lastScan }).compactMap { $0 }.max()
    }

    private var running: [LiveScan] { sources.liveScans.values.filter { !$0.finished } }

    private var mascot: MascotState {
        if !running.isEmpty { return .scanning }
        if !sources.liveScans.isEmpty { return .happy }
        return totalItems == 0 ? .empty : .idle
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            topRow
            glyphRow
            statusRow
        }
        .padding(16)
        .background(background.clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous)))
        .overlay { border }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("sources.header")
    }

    private var topRow: some View {
        let total = totalItems
        return HStack(alignment: .center, spacing: 14) {
            MascotView(state: mascot, size: 76)
            VStack(alignment: .leading, spacing: 0) {
                Caption(text: running.isEmpty ? "Read on this iPhone" : "Reading now")
                RollingNumber(value: total, font: Typeface.display(44), color: Palette.ink)
                Text(total == 1 ? "item ready for judgments and watchers" : "items ready for judgments and watchers")
                    .font(.caption).foregroundStyle(Palette.inkSoft)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("\(total) items read on this iPhone")
            .accessibilityIdentifier("sources.header.total")
            Spacer(minLength: 0)
        }
    }

    private var glyphRow: some View {
        let e = entries
        let on = e.filter(\.on)
        let names = on.map(\.title).joined(separator: ", ")
        return HStack(spacing: 6) {
            ForEach(e) { x in SourceGlyph(id: x.id, on: x.on, size: 30) }
            Spacer(minLength: 4)
            Text("\(on.count) of \(e.count) on")
                .font(Typeface.mono(12, weight: .semibold)).foregroundStyle(Palette.ink)
                .lineLimit(1).fixedSize()
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(on.count) of \(e.count) sources on: \(names)")
        .accessibilityIdentifier("sources.header.connected")
    }

    private var statusRow: some View {
        let live = running
        let line = live.isEmpty ? SourceRestStats.lastScanLine(lastScan)
            : "Reading " + live.map { $0.snapshot.pipeline.title }.sorted().joined(separator: ", ")
        return HStack(spacing: 8) {
            Image(systemName: "clock.arrow.circlepath").font(.caption).foregroundStyle(Palette.inkSoft).accessibilityHidden(true)
            Text(line).font(Typeface.mono(11)).foregroundStyle(Palette.inkSoft).lineLimit(1)
            Spacer(minLength: 4)
            if sources.state(.mail).enabled {
                Pill(text: "Mail is Online", color: Palette.blue, symbol: "globe")
            } else {
                Pill(text: "0 bytes out", color: Palette.okText, symbol: "lock.fill")
                    .accessibilityLabel("0 bytes out: every source that is on is read on this iPhone")
            }
        }
    }

    private var background: some View {
        ZStack {
            LinearGradient(colors: [Palette.groundHigh.opacity(0.9), Palette.card], startPoint: .topLeading, endPoint: .bottomTrailing)
            Canvas { g, size in
                // Faint scan lines: the header's texture, drawn once.
                var lines = Path()
                var y: CGFloat = 0
                while y < size.height { lines.addRect(CGRect(x: 0, y: y, width: size.width, height: 1)); y += 4 }
                g.fill(lines, with: .color(Palette.cyan.opacity(0.035)))
            }
        }
    }

    @ViewBuilder private var border: some View {
        if running.isEmpty {
            RoundedRectangle(cornerRadius: 20, style: .continuous).stroke(Palette.border, lineWidth: 1)
        } else {
            WorkingBorder(radius: 20)
        }
    }
}

/// A section caption with an optional footnote under the cards.
struct SourcesSectionTitle: View {
    let title: String
    var body: some View {
        Caption(text: title)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.top, 8)
            .accessibilityAddTraits(.isHeader)
    }
}

struct SourcesFootnote: View {
    let text: String
    var body: some View {
        Text(text).font(.caption).foregroundStyle(Palette.inkSoft)
            .frame(maxWidth: .infinity, alignment: .leading)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.horizontal, 4)
    }
}
