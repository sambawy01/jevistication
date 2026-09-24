import SwiftUI
import LoupeKit

/// The live run of the job a screen owns, in place, centred in that screen's main content (never a separate screen,
/// a sidebar or a drawer). Put it where the screen shows its progress: `LiveRunSection(view: "scan")`.
///
/// Shows the screen's running job, else its last finished one from this session; nothing when it has none.
struct LiveRunSection: View {
    let view: String
    /// Maps an opaque judgment question id back to its name (on the device only).
    var question: (String) -> String? = { _ in nil }
    /// Show only while the job runs (Now, where a finished run is summed up by its own card).
    var whileRunning = false
    @ObservedObject private var center = ActivityCenter.shared

    init(view: String, whileRunning: Bool = false, question: @escaping (String) -> String? = { _ in nil }) {
        self.view = view
        self.whileRunning = whileRunning
        self.question = question
    }

    var body: some View {
        Group {
            if let job = center.latest(view), !whileRunning || job.running {
                LiveRunView(job: job, reference: center.snapshot.reference, question: question)
                    .id(job.id)
            }
        }
        .onAppear { center.show(view) }
        .onDisappear { center.hide(view) }
    }
}

/// One job's live run: header, the vertical pipeline with the mascot engine and the three decision diamonds,
/// the particles, then the four cards.
struct LiveRunView: View {
    let job: JobSnapshot
    let reference: CostReference
    var question: (String) -> String? = { _ in nil }

    @Environment(\.accessibilityReduceMotion) private var systemReduceMotion
    private var reduceMotion: Bool { Motion.reduced(systemReduceMotion) }
    @State private var previous: JobSnapshot?
    @State private var particles: [Particle] = []
    @State private var announcer = LiveAnnouncer(everyMs: LiveRun.shared.LIVE_EVERY_MS)
    @State private var spoken = ""

    private var model: LiveRunModel { LiveRunModel(job, previous: previous, reference: reference, question: question) }

    var body: some View {
        let m = model
        VStack(alignment: .leading, spacing: 14) {
            header(m)
            if m.hasLoop {
                PipelineView(model: m, particles: particles, reduceMotion: reduceMotion)
            } else {
                HStack {
                    Spacer()
                    EngineView(model: m, lookAt: nil)
                    Spacer()
                }
            }
            counters(m)
            if m.hasLoop {
                LiveCards(model: m)
            }
            // The polite status line for screen readers (and UI tests): at most every 12 s or on a stage change.
            Text(spoken)
                .font(.caption2)
                .foregroundStyle(Palette.inkSoft)
                .accessibilityIdentifier("liverun.status")
                .accessibilityHidden(spoken.isEmpty)
                .frame(maxWidth: .infinity, alignment: .leading)
                .lineLimit(2)
        }
        .card(active: m.running)
        .environment(\.layoutDirection, ActStrings.direction)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("liverun")
        .accessibilityValue(reduceMotion ? "static" : "animated")
        .onAppear { update(job) }
        .onChange(of: job) { _, j in update(j) }
    }

    private func update(_ j: JobSnapshot) {
        let prev = previous
        if !reduceMotion {
            let now = Date()
            particles.removeAll { $0.finished(at: now) }
            for s in LiveRun.shared.spawns(previous: prev, job: j) {
                particles.append(Particle(fate: s.fate, end: s.end, start: now.addingTimeInterval(Double(s.delayMs) / 1000)))
            }
        }
        previous = j
        let text = LiveRunModel(j, previous: prev, reference: reference, question: question).statusLine
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        if let say = announcer.offer(nowMs: now, job: j, text: text) {
            spoken = say
            var a = AttributedString(say)
            a.accessibilitySpeechAnnouncementPriority = .low
            AccessibilityNotification.Announcement(a).post()
        }
    }

    // MARK: Header and counters

    private func header(_ m: LiveRunModel) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 8) {
                NeonIcon(name: m.running ? "waveform.path.ecg" : (job.state == "done" ? "checkmark.seal.fill" : "exclamationmark.triangle.fill"),
                         color: m.running ? Palette.cyan : (job.state == "done" ? Palette.okText : Palette.warnText), size: 16, active: m.running)
                Text(m.title)
                    .font(Typeface.display(20))
                    .foregroundStyle(Palette.ink)
                    .accessibilityAddTraits(.isHeader)
                Spacer()
                if m.running && job.cancellable {
                    Button(job.cancelRequested ? ActStrings.t("act.cancelling") : ActStrings.t("act.cancel")) {
                        ActivityCenter.shared.cancel(job.id)
                    }
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(Palette.dangerText)
                    .disabled(job.cancelRequested)
                    .accessibilityIdentifier("liverun.cancel")
                }
            }
            Text(m.running ? m.stageText : (job.result.map(LiveRunModel.message) ?? ActStrings.t("act.state.\(job.state)")))
                .font(.subheadline)
                .foregroundStyle(Palette.inkSoft)
                .accessibilityIdentifier("liverun.stage")
            if let f = m.fraction {
                SweepBar(fraction: f, live: m.running)
                    .accessibilityElement()
                    .accessibilityLabel(ActStrings.t("act.progress"))
                    .accessibilityValue(LiveRunModel.pct(f))
            }
            Text(m.progressText)
                .font(Typeface.mono(11))
                .foregroundStyle(Palette.inkSoft)
        }
    }

    private func counters(_ m: LiveRunModel) -> some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 72), spacing: 8)], spacing: 8) {
            ForEach(m.counters) { c in
                VStack(spacing: 2) {
                    RollingNumber(value: c.value, font: Typeface.display(26),
                                  color: c.id == "flagged" ? Palette.dangerText : c.id == "to_you" ? Palette.warnText : Palette.ink)
                    Text(c.label)
                        .font(.caption2)
                        .foregroundStyle(Palette.inkSoft)
                        .lineLimit(2)
                        .multilineTextAlignment(.center)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 8)
                .background(Palette.groundMid, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(c.label)
                .accessibilityValue("\(c.value)")
                .accessibilityIdentifier("liverun.counter.\(c.id)")
            }
        }
    }
}

// MARK: - The pipeline

/// One item moving down the pipeline to where it went.
struct Particle: Identifiable, Equatable {
    let id = UUID()
    let fate: String
    let end: String
    let start: Date
    static let duration: TimeInterval = Double(LiveRun.shared.LOOP_MS) / 1000 * 0.6

    func progress(at d: Date) -> Double { max(0, min(1, d.timeIntervalSince(start) / Self.duration)) }
    func finished(at d: Date) -> Bool { d.timeIntervalSince(start) > Self.duration + 0.4 }
}

private struct NodeFrames: PreferenceKey {
    static var defaultValue: [String: CGRect] = [:]
    static func reduce(value: inout [String: CGRect], nextValue: () -> [String: CGRect]) { value.merge(nextValue()) { $1 } }
}

/// The vertical pipeline for phone width: entry and read nodes, the mascot engine with the three decision
/// diamonds, then rules, evidence, outcome and review. Particles run down the spine at the inline start and stop
/// at the node their gate ends at. RTL mirrors it.
struct PipelineView: View {
    let model: LiveRunModel
    let particles: [Particle]
    let reduceMotion: Bool
    @Environment(\.layoutDirection) private var direction
    @State private var frames: [String: CGRect] = [:]

    private let spineInset: CGFloat = 14

    var body: some View {
        let byId = Dictionary(uniqueKeysWithValues: model.nodes.map { ($0.id, $0) })
        let first = model.nodes.first
        TimelineView(.animation(minimumInterval: 1 / 30, paused: reduceMotion || particles.isEmpty)) { ctx in
            let look = lookAt(at: ctx.date)
            VStack(spacing: 8) {
                if let first { nodeRow(first) }
                if let read = byId["read"] { nodeRow(read) }
                VStack(spacing: 10) {
                    EngineView(model: model, lookAt: look)
                    HStack(spacing: 8) {
                        ForEach(["q1", "q2", "q3"], id: \.self) { q in
                            if let d = byId[q] { DiamondView(node: d, reduceMotion: reduceMotion) }
                        }
                    }
                }
                .padding(.vertical, 6)
                .frame(maxWidth: .infinity)
                .background(GeometryReader { g in Color.clear.preference(key: NodeFrames.self, value: ["engine": g.frame(in: .named("pipe"))]) })
                ForEach(["rules", "evidence"], id: \.self) { id in if let n = byId[id] { nodeRow(n) } }
                HStack(spacing: 8) {
                    if let n = byId["outcome"] { nodeRow(n) }
                    if let n = byId["review"] { nodeRow(n) }
                }
            }
            .padding(.leading, spineInset + 14)
            .overlay(alignment: .topLeading) {
                Canvas { g, size in draw(&g, size: size, at: ctx.date) }
                    .allowsHitTesting(false)
                    .accessibilityHidden(true)
            }
        }
        .coordinateSpace(name: "pipe")
        .onPreferenceChange(NodeFrames.self) { frames = $0 }
    }

    private func nodeRow(_ n: LiveRunModel.Node) -> some View {
        NodeCard(node: n)
            .background(GeometryReader { g in Color.clear.preference(key: NodeFrames.self, value: [n.id: g.frame(in: .named("pipe"))]) })
    }

    private func spineX(_ width: CGFloat) -> CGFloat { direction == .rightToLeft ? width - spineInset : spineInset }

    private func y(for id: String) -> CGFloat? { frames[id]?.midY }

    private func draw(_ g: inout GraphicsContext, size: CGSize, at date: Date) {
        let x = spineX(size.width)
        let top = y(for: model.nodes.first?.id ?? "") ?? 0
        let bottom = max(y(for: "review") ?? size.height, y(for: "outcome") ?? 0)
        // The spine.
        var spine = Path(); spine.move(to: CGPoint(x: x, y: top)); spine.addLine(to: CGPoint(x: x, y: bottom))
        g.stroke(spine, with: .color(Palette.border), style: StrokeStyle(lineWidth: 2, lineCap: .round))
        // Ticks to each node.
        for (id, f) in frames where id != "engine" {
            var tick = Path()
            let edge = direction == .rightToLeft ? f.maxX : f.minX
            tick.move(to: CGPoint(x: x, y: f.midY)); tick.addLine(to: CGPoint(x: edge, y: f.midY))
            let lit = model.nodes.first { $0.id == id }?.active == true
            g.stroke(tick, with: .color(lit ? Palette.cyan : Palette.border), lineWidth: lit ? 2 : 1)
        }
        guard !reduceMotion else { return }
        for p in particles {
            let t = p.progress(at: date)
            guard t > 0, let endY = y(for: p.end) else { continue }
            let py = top + (endY - top) * CGFloat(easeOut(t))
            let color = Palette.gate(p.fate)
            let r: CGFloat = 4
            let rect = CGRect(x: x - r, y: py - r, width: r * 2, height: r * 2)
            var glow = g
            glow.addFilter(.blur(radius: 6))
            glow.fill(Path(ellipseIn: rect.insetBy(dx: -4, dy: -4)), with: .color(color.opacity(0.7)))
            g.fill(Path(ellipseIn: rect), with: .color(color))
        }
    }

    private func easeOut(_ t: Double) -> Double { 1 - pow(1 - t, 2.2) }

    /// Where the mascot looks: at the particle nearest to entering the engine, else ahead.
    private func lookAt(at date: Date) -> CGPoint? {
        guard !reduceMotion, let engine = frames["engine"], let first = y(for: model.nodes.first?.id ?? "") else { return nil }
        let live = particles.filter { let t = $0.progress(at: date); return t > 0 && t < 1 }
        guard let p = live.last, let endY = y(for: p.end) else { return nil }
        let py = first + (endY - first) * CGFloat(easeOut(p.progress(at: date)))
        let dy = max(-1, min(1, (py - engine.midY) / 160))
        return CGPoint(x: direction == .rightToLeft ? 0.7 : -0.7, y: dy)
    }
}

/// A stage node: a bordered card; the active one glows with the working border.
struct NodeCard: View {
    let node: LiveRunModel.Node
    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(node.title).font(.subheadline.weight(.semibold)).foregroundStyle(Palette.ink)
            if !node.sub.isEmpty {
                Text(node.sub).font(.caption).foregroundStyle(Palette.inkSoft).lineLimit(2)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 12).padding(.vertical, 8)
        .background(node.active ? Palette.cardHigh : Palette.groundMid, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        .overlay {
            if node.active { WorkingBorder(radius: 12) } else {
                RoundedRectangle(cornerRadius: 12, style: .continuous).stroke(Palette.border, lineWidth: 1)
            }
        }
        .neonGlow(Palette.cyan, radius: 5, on: node.active)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(node.active ? .isSelected : [])
        .accessibilityIdentifier("liverun.node.\(node.id)")
    }
}

/// A decision diamond: the question, its latest answer and confidence; it flashes when a new answer lands.
struct DiamondView: View {
    let node: LiveRunModel.Node
    let reduceMotion: Bool
    @State private var flash = false

    var body: some View {
        VStack(spacing: 4) {
            ZStack {
                Rectangle()
                    .fill(Palette.groundMid)
                    .overlay(Rectangle().stroke(node.answer.isEmpty ? Palette.border : Palette.cyan, lineWidth: 1.5))
                    .frame(width: 26, height: 26)
                    .rotationEffect(.degrees(45))
                    .neonGlow(Palette.cyan, radius: flash ? 10 : 4, on: !node.answer.isEmpty)
                Text(node.id.uppercased()).font(Typeface.mono(9, weight: .bold)).foregroundStyle(Palette.cyan)
            }
            .frame(height: 40)
            Text(node.question).font(.caption2).foregroundStyle(Palette.ink).lineLimit(2).multilineTextAlignment(.center)
            Text(node.answer.isEmpty ? "—" : node.answer).font(Typeface.mono(10)).foregroundStyle(Palette.inkSoft).lineLimit(1)
        }
        .frame(maxWidth: .infinity)
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("liverun.\(node.id)")
        .onChange(of: node.answer) { _, _ in
            guard !reduceMotion, node.fresh else { return }
            withAnimation(.easeOut(duration: 0.18)) { flash = true }
            withAnimation(.easeIn(duration: 0.5).delay(0.2)) { flash = false }
        }
    }
}

/// The mascot as the engine, with "powered by Laya · multilingual" beneath.
struct EngineView: View {
    let model: LiveRunModel
    let lookAt: CGPoint?
    var body: some View {
        VStack(spacing: 4) {
            MascotView(state: model.mascot, size: 96, lookAt: lookAt)
                .neonGlow(model.running ? Palette.cyan : Palette.blueBright, radius: model.running ? 10 : 5)
            Text(model.poweredBy)
                .font(Typeface.mono(11, weight: .medium))
                .foregroundStyle(Palette.cyan)
                .accessibilityIdentifier("liverun.poweredBy")
        }
    }
}

// MARK: - The four cards

struct LiveCards: View {
    let model: LiveRunModel

    var body: some View {
        VStack(spacing: 12) {
            card(ActStrings.t("act.card.log"), icon: "list.bullet.rectangle.portrait") {
                if model.log.isEmpty {
                    Text(ActStrings.t("act.log.empty")).font(.footnote).foregroundStyle(Palette.inkSoft)
                } else {
                    ForEach(model.log.prefix(6)) { r in
                        HStack(spacing: 6) {
                            Text(r.question).font(.caption).foregroundStyle(Palette.ink).lineLimit(1)
                            Spacer(minLength: 4)
                            Text(r.answer).font(Typeface.mono(11, weight: .medium)).foregroundStyle(Palette.cyan)
                            Text(r.confidence).font(Typeface.mono(11)).foregroundStyle(Palette.inkSoft)
                            Text(r.source).font(Typeface.mono(10)).foregroundStyle(Palette.inkSoft)
                                .padding(.horizontal, 5).padding(.vertical, 1)
                                .background(Palette.accentSoft, in: Capsule())
                        }
                        .accessibilityElement(children: .combine)
                    }
                }
            }
            card(ActStrings.t("act.card.shares"), icon: "person.3.sequence.fill") {
                ForEach(model.shares) { s in bar(s, color: Palette.source(s.id)) }
            }
            card(ActStrings.t("act.card.cost"), icon: "dollarsign.circle") {
                HStack(alignment: .firstTextBaseline) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(model.costCloudTitle).font(.caption).foregroundStyle(Palette.inkSoft)
                        Text(model.costCloud).font(Typeface.display(24)).foregroundStyle(Palette.warnText)
                            .accessibilityIdentifier("liverun.cost.cloud")
                    }
                    Spacer()
                    VStack(alignment: .trailing, spacing: 2) {
                        Text(ActStrings.t("act.cost.local")).font(.caption).foregroundStyle(Palette.inkSoft)
                        Text(model.costLocal).font(Typeface.display(20)).foregroundStyle(Palette.okText)
                    }
                }
                Text(model.costDecisions).font(.caption2).foregroundStyle(Palette.inkSoft)
                Text(model.costLabel).font(Typeface.mono(10)).foregroundStyle(Palette.inkSoft)
                    .accessibilityIdentifier("liverun.cost.label")
            }
            card(ActStrings.t("act.card.gates"), icon: "arrow.triangle.branch") {
                HStack(spacing: 14) {
                    Donut(parts: model.gates.map { (Palette.gate($0.id), Double($0.count)) }, lineWidth: 10)
                        .frame(width: 64, height: 64)
                    VStack(spacing: 6) { ForEach(model.gates) { g in bar(g, color: Palette.gate(g.id)) } }
                }
            }
        }
    }

    private func card<C: View>(_ title: String, icon: String, @ViewBuilder _ content: () -> C) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 6) {
                NeonIcon(name: icon, color: Palette.blue, size: 13)
                Text(title).font(.footnote.weight(.semibold)).foregroundStyle(Palette.ink).accessibilityAddTraits(.isHeader)
            }
            content()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(12)
        .background(Palette.groundMid, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).stroke(Palette.border, lineWidth: 1))
    }

    private func bar(_ s: LiveRunModel.Share, color: Color) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            HStack {
                Text(s.label).font(.caption).foregroundStyle(Palette.ink)
                Spacer()
                Text("\(s.count)").font(Typeface.mono(11)).foregroundStyle(Palette.inkSoft)
            }
            SweepBar(fraction: s.fraction, color: color, height: 5)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(s.label)
        .accessibilityValue("\(s.count), \(LiveRunModel.pct(s.fraction))")
    }
}
