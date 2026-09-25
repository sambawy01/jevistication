import LoupeKit
import QuartzCore
import SwiftUI

/// Each source's visual identity on the Sources page: its hue (from the palette) and glyph.
enum SourceLook {
    static func hue(_ id: String) -> Color {
        switch id {
        case "photos": return Palette.cyan
        case "files": return Palette.blue
        case "calendar": return Palette.amber
        case "contacts": return Palette.mint
        case "mail": return Palette.blueBright
        case "inbox": return Palette.cyan
        default: return Palette.amber      // the sample
        }
    }

    static func symbol(_ id: String) -> String {
        if let s = PhoneSource(rawValue: id) { return s.symbol }
        return id == "inbox" ? "tray.and.arrow.down" : "testtube.2"
    }
}

/// The live scan display (owner feedback 2026-09-25: no spinner and "Reading…"): the real items of the scan pass
/// through a scanning lens (photo thumbnails with the text boxes OCR found lighting up; file names, subjects,
/// event titles; contacts as initials only), a pipeline strip with a live count per stage that pulses as items
/// pass, a ledger of masked snippets, then throughput, progress, ETA and what leaves the phone (nothing, or for
/// an Online source what it fetches). When the scan ends the counts land and a summary line settles.
///
/// Performance: the scan publishes at most ~12 snapshots a second (`ScanFeed`); only the two canvases below run
/// per frame (TimelineView + Canvas), everything else redraws at snapshot rate. Reduce Motion: the same numbers
/// and items, no sliding, sweeping or flowing; changes crossfade.
struct ScanDisplay: View {
    @ObservedObject var live: LiveScan
    @Environment(\.accessibilityReduceMotion) private var systemReduceMotion
    private var reduceMotion: Bool { Motion.reduced(systemReduceMotion) }

    var body: some View {
        let s = live.snapshot
        let hue = SourceLook.hue(s.pipeline.source)
        VStack(alignment: .leading, spacing: 12) {
            statusRow(s, hue: hue)
            ScanLens(snapshot: s, hue: hue, reduceMotion: reduceMotion)
                .frame(height: 176)
                .accessibilityElement()
                .accessibilityLabel(s.accessibilitySummary)
                .accessibilityIdentifier("sources.scan.live")
            PipelineStrip(snapshot: s, hue: hue, reduceMotion: reduceMotion)
            ledger(s, hue: hue)
            telemetry(s, hue: hue)
            if s.phase == .finished, let summary = s.summary {
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Image(systemName: "checkmark.seal.fill").foregroundStyle(Palette.okText).accessibilityHidden(true)
                    Text(summary)
                        .font(Typeface.display(19))
                        .foregroundStyle(Palette.ink)
                        .fixedSize(horizontal: false, vertical: true)
                        .accessibilityIdentifier("sources.scan.summary")
                }
                .transition(.opacity)
            } else if s.phase == .failed {
                Text("The scan stopped. What happened is below.")
                    .font(.footnote).foregroundStyle(Palette.warnText)
            }
        }
        .animation(reduceMotion ? .easeInOut(duration: 0.25) : .spring(response: 0.4, dampingFraction: 0.85), value: s.phase)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("sources.scan.\(s.pipeline.source)")
        .accessibilityValue(reduceMotion ? "static" : "animated")
        #if DEBUG
        .onAppear { FrameProbe.shared.start() }
        .onDisappear { FrameProbe.shared.stop() }
        #endif
        .onChange(of: s.phase) { _, phase in
            if phase == .finished, let summary = s.summary {
                AccessibilityNotification.Announcement("\(s.pipeline.title): \(summary)").post()
            }
        }
    }

    private func statusRow(_ s: ScanSnapshot, hue: Color) -> some View {
        HStack(spacing: 8) {
            LiveDot(color: s.phase == .running ? hue : (s.phase == .finished ? Palette.okText : Palette.warnText),
                    pulsing: s.phase == .running && !reduceMotion)
            Text(s.phase == .running ? "LIVE" : s.phase == .finished ? "DONE" : "STOPPED")
                .font(Typeface.mono(11, weight: .bold)).tracking(1.2)
                .foregroundStyle(s.phase == .running ? hue : s.phase == .finished ? Palette.okText : Palette.warnText)
            Text(statusText(s))
                .font(Typeface.mono(11)).foregroundStyle(Palette.inkSoft).lineLimit(1)
            Spacer(minLength: 0)
        }
        .accessibilityHidden(true)
    }

    private func statusText(_ s: ScanSnapshot) -> String {
        switch s.phase {
        case .finished: return "counts in"
        case .failed: return ""
        case .running: return s.done == 0 || s.pipeline.source == "mail" ? s.status.lowercased() : "reading \(s.pipeline.units)"
        }
    }

    // MARK: Ledger

    private func ledger(_ s: ScanSnapshot, hue: Color) -> some View {
        VStack(alignment: .leading, spacing: 5) {
            Caption(text: ledgerTitle(s.pipeline))
            if s.ledger.isEmpty {
                Text(s.pipeline.stages.contains(.text) ? (s.done == 0 ? "Waiting for the first photo" : "No text in these photos yet")
                     : "Waiting for the first item")
                    .font(Typeface.mono(11)).foregroundStyle(Palette.inkSoft)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            ForEach(Array(s.ledger.prefix(4).enumerated()), id: \.element.id) { i, r in
                HStack(alignment: .firstTextBaseline, spacing: 6) {
                    Text("›").font(Typeface.mono(12, weight: .bold)).foregroundStyle(hue).fixedSize()
                    Text(r.snippet.isEmpty ? r.name : Self.shortName(r.name))
                        .font(Typeface.mono(11, weight: .semibold)).foregroundStyle(Palette.ink)
                        .lineLimit(1)
                        .fixedSize(horizontal: !r.snippet.isEmpty, vertical: false)
                    if !r.snippet.isEmpty {
                        Text(r.snippet)
                            .font(Typeface.mono(11)).foregroundStyle(Palette.inkSoft)
                            .lineLimit(1).layoutPriority(1)
                    }
                    Spacer(minLength: 0)
                }
                .opacity(1 - Double(i) * 0.2)
                .transition(reduceMotion ? .opacity : .asymmetric(insertion: .move(edge: .top).combined(with: .opacity), removal: .opacity))
                .accessibilityElement(children: .combine)
                .accessibilityIdentifier("sources.scan.ledger.\(i)")
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .clipped()
        .animation(reduceMotion ? .easeInOut(duration: 0.2) : .spring(response: 0.35, dampingFraction: 0.9), value: s.ledger.first?.id)
    }

    /// "IMG_4107.JPG" → "IMG_4107": the name column beside a snippet stays short.
    static func shortName(_ n: String) -> String {
        let base = (n as NSString).deletingPathExtension
        return base.count > 14 ? String(base.prefix(13)) + "…" : base
    }

    private func ledgerTitle(_ p: ScanPipeline) -> String {
        switch p.visual {
        case .thumbnails: return "Text found · masked"
        case .initials: return "Contacts read · initials only"
        case .names: return p.source == "mail" ? "Subjects read · masked" : "\(p.units.capitalized) read"
        }
    }

    // MARK: Telemetry

    private func telemetry(_ s: ScanSnapshot, hue: Color) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            SweepBar(fraction: s.fraction ?? 0, color: hue, height: 6, live: s.phase == .running)
                .accessibilityElement()
                .accessibilityLabel("Progress")
                .accessibilityValue(s.fraction.map { "\(Int(($0 * 100).rounded())) percent" } ?? "starting")
            HStack(spacing: 8) {
                Text(s.telemetry.isEmpty ? " " : s.telemetry)
                    .font(Typeface.mono(11)).monospacedDigit().foregroundStyle(Palette.inkSoft)
                    .lineLimit(1).minimumScaleFactor(0.8)
                    .accessibilityIdentifier("sources.scan.telemetry")
                Spacer(minLength: 4)
                if s.pipeline.online == nil {
                    Pill(text: "0 bytes out", color: Palette.okText, symbol: "iphone")
                        .accessibilityLabel("0 bytes out. Read on this iPhone.")
                        .accessibilityIdentifier("sources.scan.bytesOut")
                } else {
                    Pill(text: "Online", color: Palette.blue, symbol: "globe")
                }
            }
            if let online = s.pipeline.online {
                Text(online).font(.caption).foregroundStyle(Palette.inkSoft)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityIdentifier("sources.scan.online")
            }
        }
    }
}

/// A small live dot; it breathes while the scan runs (not under Reduce Motion).
private struct LiveDot: View {
    let color: Color
    let pulsing: Bool
    @State private var on = false
    var body: some View {
        Circle().fill(color).frame(width: 7, height: 7)
            .shadow(color: color.opacity(0.9), radius: pulsing && on ? 6 : 2)
            .opacity(pulsing && on ? 0.55 : 1)
            .onAppear { if pulsing { withAnimation(.easeInOut(duration: 0.8).repeatForever(autoreverses: true)) { on = true } } }
            .onChange(of: pulsing) { _, p in if !p { on = false } }
    }
}

// MARK: - The lens

/// The conveyor and the scanning lens. The newest item sits in the lens (a photo with the boxes OCR found lighting up
/// as the beam passes; a name card; an initials badge); the items read before it slide off to the left and dim;
/// empty frames on the right stand for items still to read (only as many as really remain).
struct ScanLens: View {
    let snapshot: ScanSnapshot
    let hue: Color
    let reduceMotion: Bool
    @State private var frozen = false

    var body: some View {
        ZStack {
            // The static ground: a dot grid and a glow behind the lens (drawn once, not per frame).
            RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Palette.ground)
            Canvas { g, size in LensGround.draw(&g, size: size, hue: hue) }
                .accessibilityHidden(true)
            TimelineView(.animation(paused: reduceMotion || frozen)) { ctx in
                Canvas { g, size in
                    let t0 = CACurrentMediaTime()
                    LensPainter(snapshot: snapshot, hue: hue, reduceMotion: reduceMotion)
                        .draw(&g, size: size, now: reduceMotion ? snapshot.publishedAt + 10 : ctx.date.timeIntervalSinceReferenceDate)
                    #if DEBUG
                    FrameProbe.shared.draw(CACurrentMediaTime() - t0)
                    #endif
                    _ = t0
                }
            }
            .accessibilityHidden(true)
            lensText
        }
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(Palette.border, lineWidth: 1))
        .task(id: snapshot.phase) {
            // Let the last slide and the settle finish, then stop drawing frames.
            guard snapshot.phase != .running else { frozen = false; return }
            try? await Task.sleep(nanoseconds: 1_400_000_000)
            frozen = true
        }
    }

    /// The real text of the item in the lens (names and initials), above the canvas, crossfading per item.
    @ViewBuilder private var lensText: some View {
        GeometryReader { geo in
            let r = LensGeometry(size: geo.size).lens
            if let item = snapshot.recent.first, snapshot.pipeline.visual != .thumbnails {
                Group {
                    if snapshot.pipeline.visual == .initials {
                        Text(item.name)
                            .font(Typeface.display(34)).foregroundStyle(Palette.ink)
                            .minimumScaleFactor(0.5)
                    } else {
                        // Let a long file name break before its extension rather than mid-word.
                        Text(item.name.replacingOccurrences(of: ".", with: ".\u{200B}"))
                            .font(Typeface.mono(12, weight: .semibold)).foregroundStyle(Palette.ink)
                            .multilineTextAlignment(.center).lineLimit(4).minimumScaleFactor(0.7)
                            .padding(.top, 22)
                    }
                }
                .frame(width: r.width - 16, height: r.height - 16)
                .position(x: r.midX, y: r.midY)
                .id(item.id)
                .transition(.opacity)
            } else if snapshot.recent.isEmpty && snapshot.phase == .running {
                Text(snapshot.status)
                    .font(Typeface.mono(11)).foregroundStyle(Palette.inkSoft)
                    .multilineTextAlignment(.center)
                    .frame(width: r.width - 12)
                    .position(x: r.midX, y: r.midY)
            }
            if let item = snapshot.recent.first {
                Text(item.name)
                    .font(Typeface.mono(10, weight: .medium)).foregroundStyle(hue)
                    .lineLimit(1)
                    .frame(width: max(40, r.width + 60))
                    .position(x: r.midX, y: r.maxY + 11)
                    .opacity(snapshot.pipeline.visual == .thumbnails ? 1 : 0)
                    .id("caption-\(item.id)")
                    .transition(.opacity)
            }
        }
        .animation(.easeInOut(duration: reduceMotion ? 0.25 : 0.18), value: snapshot.recent.first?.id)
        .allowsHitTesting(false)
    }
}

/// Where things sit in the lens stage.
struct LensGeometry {
    let size: CGSize
    var side: CGFloat { min(size.height - 40, 128) }
    var lens: CGRect { CGRect(x: size.width / 2 - side / 2, y: 12, width: side, height: side) }
    var small: CGFloat { side * 0.52 }
    var gap: CGFloat { 16 }

    /// The frame at continuous slot [p]: 0 is the lens, 1… slide off to the left, -1 waits on the right.
    func frame(at p: Double) -> CGRect {
        let l = lens
        let s = small
        let cy = l.midY
        let slot1 = l.minX - gap - s / 2
        let incoming = l.maxX + gap + s / 2
        let step = s + 10
        let x: CGFloat
        let w: CGFloat
        if p >= 1 {
            x = slot1 - CGFloat(p - 1) * step; w = s
        } else if p >= 0 {
            let t = CGFloat(p)
            x = l.midX + (slot1 - l.midX) * t; w = side + (s - side) * t
        } else if p >= -1 {
            let t = CGFloat(-p)
            x = l.midX + (incoming - l.midX) * t; w = side + (s - side) * t
        } else {
            x = incoming + CGFloat(-p - 1) * step; w = s
        }
        return CGRect(x: x - w / 2, y: cy - w / 2, width: w, height: w)
    }
}

/// The lens stage's still ground.
enum LensGround {
    static func draw(_ g: inout GraphicsContext, size: CGSize, hue: Color) {
        let geo = LensGeometry(size: size)
        g.fill(Path(ellipseIn: geo.lens.insetBy(dx: -70, dy: -40)),
               with: .radialGradient(Gradient(colors: [hue.opacity(0.16), .clear]), center: CGPoint(x: geo.lens.midX, y: geo.lens.midY),
                                     startRadius: 10, endRadius: geo.side))
        var dots = Path()
        let step: CGFloat = 14
        var y: CGFloat = step / 2
        while y < size.height {
            var x: CGFloat = step / 2
            while x < size.width { dots.addRect(CGRect(x: x, y: y, width: 1, height: 1)); x += step }
            y += step
        }
        g.fill(dots, with: .color(Palette.inkSoft.opacity(0.14)))
        // The conveyor rail.
        var rail = Path()
        rail.move(to: CGPoint(x: 8, y: geo.lens.midY)); rail.addLine(to: CGPoint(x: size.width - 8, y: geo.lens.midY))
        g.stroke(rail, with: .color(hue.opacity(0.18)), style: StrokeStyle(lineWidth: 1, dash: [3, 5]))
    }
}

/// Draws one frame of the lens stage.
struct LensPainter {
    let snapshot: ScanSnapshot
    let hue: Color
    let reduceMotion: Bool

    static let slide: TimeInterval = 0.45
    static let beamPeriod: TimeInterval = 1.6

    func draw(_ g: inout GraphicsContext, size: CGSize, now: TimeInterval) {
        let geo = LensGeometry(size: size)
        let s = snapshot
        let running = s.phase == .running
        // Slide: everything moves left by the batch that arrived with this snapshot.
        let t = reduceMotion ? 1 : min(1, max(0, (now - s.publishedAt) / Self.slide))
        let e = 1 - pow(1 - t, 3)
        let shift = Double(s.batch) * (1 - e)

        // Frames still to read, on the right (as many as really remain, at most two).
        if let total = s.total, running {
            let remaining = min(2, max(0, total - s.done))
            for i in 0..<remaining {
                let r = geo.frame(at: -1 - Double(i) - shift)
                g.stroke(Path(roundedRect: r, cornerRadius: 8), with: .color(hue.opacity(0.28 - Double(i) * 0.1)),
                         style: StrokeStyle(lineWidth: 1, dash: [4, 4]))
            }
        }

        // Items read, newest in the lens.
        for (k, item) in s.recent.enumerated().reversed() {
            let p = Double(k) - shift
            let r = geo.frame(at: p)
            guard r.maxX > -10, r.minX < size.width + 10 else { continue }
            let fade = p <= 1 ? 1 : max(0, 1 - (p - 1) * 0.2)
            var layer = g
            layer.opacity = fade
            drawItem(item, in: r, inLens: p < 0.5, context: &layer, now: now)
        }

        drawLens(&g, geo: geo, now: now, running: running)
    }

    private func drawItem(_ item: ScanSnapshot.Recent, in r: CGRect, inLens: Bool, context g: inout GraphicsContext, now: TimeInterval) {
        let shape = Path(roundedRect: r, cornerRadius: inLens ? 10 : 8)
        let tint: Color = !item.read ? Palette.inkSoft : item.textFound ? Palette.mint : hue
        switch snapshot.pipeline.visual {
        case .thumbnails:
            g.fill(shape, with: .color(Palette.groundMid))
            if let thumb = item.thumb {
                let img = g.resolve(Image(decorative: thumb.image, scale: 1))
                let fit = inLens ? aspectFit(img.size, in: r.insetBy(dx: 3, dy: 3)) : aspectFill(img.size, in: r)
                var clip = g
                clip.clip(to: shape)
                clip.draw(img, in: fit)
                if inLens { drawBoxes(item, in: fit, context: &clip, now: now) }
                if !inLens {
                    clip.fill(shape, with: .color(Palette.ground.opacity(0.35)))
                }
            } else {
                // Skipped: the original is not on this iPhone (iCloud only). An empty frame, honestly.
                g.stroke(Path(roundedRect: r.insetBy(dx: r.width * 0.3, dy: r.height * 0.3), cornerRadius: 3),
                         with: .color(Palette.inkSoft.opacity(0.5)), lineWidth: 1)
            }
        case .names:
            g.fill(shape, with: .linearGradient(Gradient(colors: [Palette.cardHigh, Palette.card]),
                                                 startPoint: CGPoint(x: r.minX, y: r.minY), endPoint: CGPoint(x: r.maxX, y: r.maxY)))
            let glyph = g.resolve(Image(systemName: SourceLook.symbol(snapshot.pipeline.source)))
            let gs: CGFloat = inLens ? 16 : 12
            var glyph2 = glyph
            glyph2.shading = .color(hue)
            g.draw(glyph2, in: CGRect(x: r.minX + 7, y: r.minY + 7, width: gs, height: gs * glyph.size.height / max(1, glyph.size.width)))
            if !inLens {
                // Text bars stand for the name at this size (the name itself is in the lens and the ledger).
                for i in 0..<3 {
                    let w = r.width * (i == 2 ? 0.4 : 0.72)
                    g.fill(Path(roundedRect: CGRect(x: r.minX + 7, y: r.minY + gs + 14 + CGFloat(i) * 7, width: w, height: 3), cornerRadius: 1.5),
                           with: .color(Palette.inkSoft.opacity(0.35)))
                }
            }
        case .initials:
            let c = Path(ellipseIn: r.insetBy(dx: r.width * 0.08, dy: r.height * 0.08))
            g.fill(c, with: .color(Palette.cardHigh))
            if !inLens {
                g.draw(Text(item.name).font(Typeface.mono(11, weight: .bold)).foregroundColor(Palette.ink), at: CGPoint(x: r.midX, y: r.midY))
            }
        }
        g.stroke(shape, with: .color(tint.opacity(inLens ? 0.9 : 0.55)), lineWidth: inLens ? 1.5 : 1)
        if !inLens {
            // Where it went: mint with text, the source hue read, grey skipped.
            let dot = CGRect(x: r.maxX - 9, y: r.minY + 4, width: 5, height: 5)
            g.fill(Path(ellipseIn: dot), with: .color(tint))
        }
    }

    /// The boxes Vision found, lighting up top to bottom as the beam passes them (all lit under Reduce Motion).
    private func drawBoxes(_ item: ScanSnapshot.Recent, in r: CGRect, context g: inout GraphicsContext, now: TimeInterval) {
        guard !item.boxes.isEmpty else { return }
        let age = now - item.at
        for b in item.boxes {
            let rect = CGRect(x: r.minX + b.minX * r.width, y: r.minY + (1 - b.maxY) * r.height,
                              width: max(2, b.width * r.width), height: max(2, b.height * r.height)).insetBy(dx: -1.5, dy: -1)
            let reveal = 0.12 + (1 - b.midY) * Self.beamPeriod * 0.5
            let a = reduceMotion ? 1 : min(1, max(0, (age - reveal) / 0.18))
            guard a > 0 else { continue }
            let p = Path(roundedRect: rect, cornerRadius: 2)
            g.fill(p, with: .color(Palette.cyan.opacity(0.22 * a)))
            g.stroke(p, with: .color(Palette.cyan.opacity(0.95 * a)), lineWidth: 1)
        }
    }

    private func drawLens(_ g: inout GraphicsContext, geo: LensGeometry, now: TimeInterval, running: Bool) {
        let l = geo.lens.insetBy(dx: -6, dy: -6)
        let arm = l.width * 0.2
        var brackets = Path()
        for (c, dx, dy) in [(CGPoint(x: l.minX, y: l.minY), 1.0, 1.0), (CGPoint(x: l.maxX, y: l.minY), -1.0, 1.0),
                            (CGPoint(x: l.minX, y: l.maxY), 1.0, -1.0), (CGPoint(x: l.maxX, y: l.maxY), -1.0, -1.0)] {
            brackets.move(to: CGPoint(x: c.x + arm * dx, y: c.y))
            brackets.addLine(to: c)
            brackets.addLine(to: CGPoint(x: c.x, y: c.y + arm * dy))
        }
        let done = snapshot.phase == .finished
        let color = done ? Palette.okText : hue
        g.stroke(brackets, with: .color(color.opacity(0.25)), style: StrokeStyle(lineWidth: 6, lineCap: .round, lineJoin: .round))
        g.stroke(brackets, with: .color(color), style: StrokeStyle(lineWidth: 2, lineCap: .round, lineJoin: .round))

        guard running, !reduceMotion else { return }
        // The beam: a bright line with a fading wake, sweeping the lens.
        let phase = (now.truncatingRemainder(dividingBy: Self.beamPeriod)) / Self.beamPeriod
        let y = geo.lens.minY + CGFloat(0.5 - 0.5 * cos(phase * 2 * .pi)) * geo.lens.height
        let down = phase < 0.5
        let wake = CGRect(x: geo.lens.minX, y: down ? y - 26 : y, width: geo.lens.width, height: 26)
        g.fill(Path(wake), with: .linearGradient(Gradient(colors: down ? [hue.opacity(0), hue.opacity(0.28)] : [hue.opacity(0.28), hue.opacity(0)]),
                                                 startPoint: CGPoint(x: 0, y: wake.minY), endPoint: CGPoint(x: 0, y: wake.maxY)))
        var line = Path()
        line.move(to: CGPoint(x: geo.lens.minX - 4, y: y)); line.addLine(to: CGPoint(x: geo.lens.maxX + 4, y: y))
        g.stroke(line, with: .linearGradient(Gradient(colors: [hue.opacity(0), Palette.ink, hue.opacity(0)]),
                                             startPoint: CGPoint(x: geo.lens.minX, y: y), endPoint: CGPoint(x: geo.lens.maxX, y: y)),
                 lineWidth: 1.5)
        g.stroke(line, with: .color(hue.opacity(0.25)), lineWidth: 5)

        // Text found in the newest item drifts down towards the ledger.
        if let item = snapshot.recent.first, item.textFound || snapshot.pipeline.visual != .thumbnails {
            let age = now - item.at
            for i in 0..<4 {
                let tt = (age - Double(i) * 0.12) / 0.9
                guard tt > 0, tt < 1 else { continue }
                let x0 = geo.lens.midX + CGFloat(i - 2) * 10
                let px = x0 + CGFloat(tt) * CGFloat(i % 2 == 0 ? -40 : 30)
                let py = geo.lens.maxY + CGFloat(tt) * (geo.size.height - geo.lens.maxY)
                let w: CGFloat = 10 + CGFloat(i) * 3
                g.fill(Path(roundedRect: CGRect(x: px - w / 2, y: py, width: w, height: 2), cornerRadius: 1),
                       with: .color((item.textFound ? Palette.mint : hue).opacity(1 - tt)))
            }
        }
    }

    private func aspectFit(_ s: CGSize, in r: CGRect) -> CGRect {
        guard s.width > 0, s.height > 0 else { return r }
        let k = min(r.width / s.width, r.height / s.height)
        let w = s.width * k, h = s.height * k
        return CGRect(x: r.midX - w / 2, y: r.midY - h / 2, width: w, height: h)
    }

    private func aspectFill(_ s: CGSize, in r: CGRect) -> CGRect {
        guard s.width > 0, s.height > 0 else { return r }
        let k = max(r.width / s.width, r.height / s.height)
        let w = s.width * k, h = s.height * k
        return CGRect(x: r.midX - w / 2, y: r.midY - h / 2, width: w, height: h)
    }
}

// MARK: - The pipeline strip

/// The stages that really run for this source, left to right, each with its live count in mono tabular digits.
/// A node pulses when its count goes up; while the scan runs, dots flow between nodes at the scan's rate.
struct PipelineStrip: View {
    let snapshot: ScanSnapshot
    let hue: Color
    let reduceMotion: Bool
    @State private var frozen = false

    static let countHeight: CGFloat = 24
    static let nodeRow: CGFloat = 16

    var body: some View {
        let s = snapshot
        ZStack(alignment: .top) {
            TimelineView(.animation(paused: reduceMotion || frozen)) { ctx in
                Canvas { g, size in
                    let t0 = CACurrentMediaTime()
                    draw(&g, size: size, now: reduceMotion ? s.publishedAt + 10 : ctx.date.timeIntervalSinceReferenceDate)
                    #if DEBUG
                    FrameProbe.shared.draw(CACurrentMediaTime() - t0)
                    #endif
                    _ = t0
                }
            }
            .frame(height: Self.countHeight + Self.nodeRow + 18)
            .accessibilityHidden(true)
            HStack(spacing: 0) {
                ForEach(s.stages) { st in
                    VStack(spacing: 0) {
                        Text(countText(st))
                            .font(Typeface.mono(16, weight: .semibold)).monospacedDigit()
                            .foregroundStyle(st.count == nil && !st.complete ? Palette.inkSoft : Palette.ink)
                            .contentTransition(reduceMotion ? .opacity : .numericText(value: Double(st.count ?? 0)))
                            .lineLimit(1).minimumScaleFactor(0.6)
                            .frame(height: Self.countHeight)
                        Color.clear.frame(height: Self.nodeRow)
                        Text(st.kind.label.uppercased())
                            .font(Typeface.mono(10, weight: .medium)).tracking(0.8)
                            .foregroundStyle(Palette.inkSoft)
                            .lineLimit(1).minimumScaleFactor(0.7)
                            .frame(height: 18)
                    }
                    .frame(maxWidth: .infinity)
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel(st.kind.label)
                    .accessibilityValue(accessibilityCount(st))
                    .accessibilityIdentifier("sources.scan.stage.\(st.kind.rawValue)")
                }
            }
            .animation(reduceMotion ? .easeInOut(duration: 0.2) : .spring(response: 0.35, dampingFraction: 0.8), value: s.stages)
        }
        .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
        .task(id: s.phase) {
            // The Saved count's pulse plays out, then the strip stops drawing frames.
            guard s.phase != .running else { frozen = false; return }
            try? await Task.sleep(nanoseconds: 1_000_000_000)
            frozen = true
        }
    }

    private func countText(_ st: ScanSnapshot.Stage) -> String {
        if let n = st.count { return n.formatted() }
        if st.kind == .fetch { return st.complete ? "✓" : "…" }
        return "—"
    }

    private func accessibilityCount(_ st: ScanSnapshot.Stage) -> String {
        if let n = st.count { return "\(n)" }
        if st.kind == .fetch { return st.complete ? "fetched" : "fetching" }
        return "not yet"
    }

    private func draw(_ g: inout GraphicsContext, size: CGSize, now: TimeInterval) {
        let s = snapshot
        let n = s.stages.count
        guard n > 0 else { return }
        let y = Self.countHeight + Self.nodeRow / 2
        let xs = (0..<n).map { CGFloat($0) + 0.5 }.map { $0 * size.width / CGFloat(n) }
        let r: CGFloat = 5
        // Links.
        for i in 0..<(n - 1) {
            var link = Path()
            link.move(to: CGPoint(x: xs[i] + r + 3, y: y)); link.addLine(to: CGPoint(x: xs[i + 1] - r - 3, y: y))
            let lit = (s.stages[i + 1].count ?? 0) > 0 || s.stages[i + 1].complete
            g.stroke(link, with: .color(lit ? hue.opacity(0.55) : Palette.border), lineWidth: 1.5)
            // Flowing dots while running, as many as the rate supports (at most four per link).
            if s.phase == .running, !reduceMotion, let rate = s.rate, rate > 0 {
                let dots = min(4, 1 + Int(rate / 3))
                let speed = 0.9 + min(2, rate / 6)
                for d in 0..<dots {
                    let ph = (now * speed / 1.6 + Double(d) / Double(dots) + Double(i) * 0.37).truncatingRemainder(dividingBy: 1)
                    let x = xs[i] + r + 3 + CGFloat(ph) * (xs[i + 1] - xs[i] - 2 * r - 6)
                    let dot = CGRect(x: x - 2, y: y - 2, width: 4, height: 4)
                    g.fill(Path(ellipseIn: dot.insetBy(dx: -2, dy: -2)), with: .color(hue.opacity(0.25)))
                    g.fill(Path(ellipseIn: dot), with: .color(Palette.ink))
                }
            }
        }
        // Nodes, with a ring that expands when the count goes up.
        for (i, st) in s.stages.enumerated() {
            let c = CGPoint(x: xs[i], y: y)
            let node = CGRect(x: c.x - r, y: c.y - r, width: 2 * r, height: 2 * r)
            let active = (st.count ?? 0) > 0 || st.complete
            let color = st.complete && s.phase == .finished ? Palette.okText : hue
            if active {
                g.fill(Path(ellipseIn: node.insetBy(dx: -4, dy: -4)), with: .color(color.opacity(0.18)))
                g.fill(Path(ellipseIn: node), with: .color(color))
            } else {
                g.stroke(Path(ellipseIn: node), with: .color(Palette.inkSoft.opacity(0.6)), lineWidth: 1.2)
            }
            if !reduceMotion, let at = st.pulseAt {
                let age = now - at
                if age >= 0, age < 0.7 {
                    let k = CGFloat(age / 0.7)
                    let ring = node.insetBy(dx: -4 - 12 * k, dy: -4 - 12 * k)
                    g.stroke(Path(ellipseIn: ring), with: .color(color.opacity(Double(1 - k) * 0.8)), lineWidth: 1.5)
                }
            }
        }
    }
}
