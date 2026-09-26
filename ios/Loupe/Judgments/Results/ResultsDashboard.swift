import Charts
import LoupeKit
import SwiftUI

// The clickable summary above the list (owner feedback 2026-09-26). Every segment, bar and row here is a
// labelled button that sets one filter facet and scrolls to the list; tapping it again clears the facet.
// Numbers are mono tabular; charts carry an audio-graph descriptor and a one-line summary for VoiceOver.

/// The header: the question, when it last ran, how many were judged, and the answer split as a donut.
struct ResultsSummaryCard: View {
    @ObservedObject var model: ResultsModel
    let judgment: UserJudgment
    let muted: Int?
    let onFilter: () -> Void
    @State private var angle: Int?

    var body: some View {
        let s = model.index?.summary ?? ResultsSummary()
        let buckets = s.orderedBuckets
        let pcts = ResultsIndex.percentages(buckets.map { s.count($0) })
        VStack(alignment: .leading, spacing: 14) {
            VStack(alignment: .leading, spacing: 4) {
                if judgment.warnOnly { Pill(text: "warn-only", color: Palette.amber) }
                Text(judgment.question)
                    .font(Typeface.display(24)).foregroundStyle(Palette.ink)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityAddTraits(.isHeader)
                Text("\(JudgmentRunLog.line(judgment.id)) · \(ResultsNames.count(s.total)) judged · acts at \(pct(judgment.threshold))")
                    .font(Typeface.mono(11)).foregroundStyle(Palette.inkSoft)
                    .accessibilityIdentifier("results.lastRun")
            }
            ViewThatFits(in: .horizontal) {
                HStack(alignment: .center, spacing: 16) {
                    donut(s, buckets).frame(width: 136, height: 136)
                    legend(s, buckets, pcts).frame(minWidth: 180)
                }
                VStack(alignment: .leading, spacing: 12) {
                    donut(s, buckets).frame(width: 150, height: 150).frame(maxWidth: .infinity)
                    legend(s, buckets, pcts)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .card()
    }

    private func donut(_ s: ResultsSummary, _ buckets: [ResultBucket]) -> some View {
        Chart(buckets, id: \.self) { b in
            SectorMark(angle: .value("Items", s.count(b)), innerRadius: .ratio(0.64), angularInset: 1.5)
                .cornerRadius(3)
                .foregroundStyle(ResultsLook.bucket(b, muted: muted))
                .opacity(model.filter.bucket == nil || model.filter.bucket == b ? 1 : 0.3)
        }
        .chartLegend(.hidden)
        .chartAngleSelection(value: $angle)
        .onChange(of: angle) { _, v in
            guard let v else { return }
            var run = 0
            for b in buckets {
                run += s.count(b)
                if v <= run { model.toggle(bucket: b); onFilter(); break }
            }
            angle = nil
        }
        .overlay {
            VStack(spacing: -2) {
                Text(StatusRing.compact(s.total))
                    .font(Typeface.display(28)).monospacedDigit().foregroundStyle(Palette.ink)
                    .minimumScaleFactor(0.5).lineLimit(1)
                Text("JUDGED").font(Typeface.mono(9, weight: .medium)).tracking(0.8).foregroundStyle(Palette.inkSoft)
            }
            .padding(22)
            .allowsHitTesting(false)
        }
        .accessibilityLabel("Answer split")
        .accessibilityValue(buckets.map { "\(model.bucketTitle($0)) \(s.count($0))" }.joined(separator: ", "))
        .accessibilityChartDescriptor(SplitDescriptor(model: model, summary: s, buckets: buckets))
        .accessibilityIdentifier("results.donut")
    }

    private func legend(_ s: ResultsSummary, _ buckets: [ResultBucket], _ pcts: [Int]) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            ForEach(Array(buckets.enumerated()), id: \.element) { i, b in
                let on = model.filter.bucket == b
                Button {
                    model.toggle(bucket: b)
                    onFilter()
                } label: {
                    HStack(alignment: .firstTextBaseline, spacing: 8) {
                        RoundedRectangle(cornerRadius: 3).fill(ResultsLook.bucket(b, muted: muted))
                            .frame(width: 10, height: 10)
                            .alignmentGuide(.firstTextBaseline) { $0[.bottom] - 1 }
                        Text(model.bucketTitle(b))
                            .font(.subheadline.weight(on ? .bold : .medium)).foregroundStyle(Palette.ink)
                            .multilineTextAlignment(.leading)
                            .fixedSize(horizontal: false, vertical: true)
                        Spacer(minLength: 6)
                        Text(ResultsNames.count(s.count(b)))
                            .font(Typeface.mono(13, weight: .semibold)).monospacedDigit().foregroundStyle(Palette.ink)
                        Text("\(pcts[i])%")
                            .font(Typeface.mono(12)).monospacedDigit().foregroundStyle(Palette.inkSoft)
                            .frame(minWidth: 38, alignment: .trailing)
                    }
                    .padding(.horizontal, 8)
                    .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                    .background(on ? Palette.accentSoft : Color.clear, in: RoundedRectangle(cornerRadius: 10))
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("\(model.bucketTitle(b)): \(s.count(b)) items, \(pcts[i]) percent")
                .accessibilityHint(on ? "Shows every item again" : "Shows only these items in the list")
                .accessibilityAddTraits(on ? .isSelected : [])
                .accessibilityIdentifier("results.segment.\(b.key)")
            }
        }
    }
}

/// Who answered: rules (by check), the decision model, you, and what still waits.
struct WhoAnsweredCard: View {
    @ObservedObject var model: ResultsModel
    let onFilter: () -> Void

    var body: some View {
        let s = model.index?.summary ?? ResultsSummary()
        let parts = Answerer.allCases.filter { s.count($0) > 0 }
        let pcts = ResultsIndex.percentages(parts.map { s.count($0) })
        VStack(alignment: .leading, spacing: 10) {
            Caption(text: "Who answered")
            StackedBar(parts: parts.map { (ResultsLook.answerer($0), Double(s.count($0))) }, height: 12)
                .accessibilityHidden(true)
            ForEach(Array(parts.enumerated()), id: \.element) { i, a in
                let on = model.filter.answerer == a && model.filter.check == nil
                FacetRow(color: ResultsLook.answerer(a), symbol: ResultsLook.answererSymbol(a), title: a.title,
                         count: s.count(a), percent: pcts[i], on: on, id: "results.by.\(a.rawValue)") {
                    model.toggle(answerer: a); onFilter()
                }
                if a == .rule {
                    ForEach(s.checks.sorted { $0.value > $1.value }, id: \.key) { c, n in
                        FacetRow(color: ResultsLook.answerer(.rule).opacity(0.6), symbol: nil, title: ResultsNames.check(c),
                                 count: n, percent: nil, on: model.filter.check == c, id: "results.check.\(c)", indent: true) {
                            model.toggle(check: c); onFilter()
                        }
                    }
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .card()
    }
}

/// The model's confidence per answer, with the acceptance threshold drawn; tap a bin to list those items.
struct ConfidenceCard: View {
    @ObservedObject var model: ResultsModel
    let muted: Int?
    let onFilter: () -> Void

    var body: some View {
        if let index = model.index, index.bins.total > 0 {
            let bins = index.bins
            let border = index.borderlineBins
            VStack(alignment: .leading, spacing: 10) {
                HStack {
                    Caption(text: "The decision model's confidence")
                    Spacer()
                    Text("\(ResultsNames.count(bins.total)) answers").font(Typeface.mono(11)).foregroundStyle(Palette.inkSoft)
                }
                chart(index, bins)
                    .padding(.top, 16)
                    .frame(height: 166)
                Text("Raw probabilities, uncalibrated. At or above \(pct(index.threshold)) (the line) the engine answers; below it, the item waits for you.")
                    .font(.caption).foregroundStyle(Palette.inkSoft)
                    .fixedSize(horizontal: false, vertical: true)
                let n = index.borderlineCount
                let on = model.filter.bins == border
                Button {
                    model.toggle(bins: border); onFilter()
                } label: {
                    HStack {
                        NeonIcon(name: "scope", color: Palette.amber, size: 15)
                        VStack(alignment: .leading, spacing: 0) {
                            Text("\(ResultsNames.count(n)) borderline")
                                .font(.subheadline.weight(.semibold)).monospacedDigit().foregroundStyle(Palette.ink)
                            Text("within 10 points of the line").font(.caption).foregroundStyle(Palette.inkSoft)
                        }
                        Spacer(minLength: 4)
                        Image(systemName: on ? "xmark.circle.fill" : "chevron.right").foregroundStyle(Palette.inkSoft)
                    }
                    .padding(.horizontal, 10)
                    .frame(maxWidth: .infinity, minHeight: 44)
                    .background(on ? Palette.accentSoft : Palette.groundMid, in: RoundedRectangle(cornerRadius: 10))
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("\(n) borderline answers, within 10 points of the threshold")
                .accessibilityAddTraits(on ? .isSelected : [])
                .accessibilityIdentifier("results.borderline")
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .card()
        }
    }

    private struct Bar: Identifiable {
        let bin: Int, option: Int, lo: Double, hi: Double, from: Int, to: Int
        var id: Int { bin * 64 + option }
    }

    /// Stacked by hand (options in their fixed order from the baseline up), so each bin is one column.
    private func bars(_ index: ResultsIndex, _ bins: ConfidenceBins) -> [Bar] {
        var out: [Bar] = []
        for b in 0..<bins.binCount {
            var run = 0
            let r = bins.range(b)
            for o in index.options.indices {
                let n = bins.counts[b][o]
                guard n > 0 else { continue }
                out.append(Bar(bin: b, option: o, lo: r.lo, hi: r.hi, from: run, to: run + n))
                run += n
            }
        }
        return out
    }

    private func barOpacity(_ bin: Int) -> Double {
        guard let selected = model.filter.bins else { return 1 }
        return selected.contains(bin) ? 1 : 0.3
    }

    private func barMark(_ bar: Bar) -> some ChartContent {
        let x0: Double = bar.lo + 0.004
        let x1: Double = bar.hi - 0.004
        let y0 = Double(bar.from)
        let y1 = Double(bar.to)
        let color: Color = ResultsLook.option(bar.option, muted: muted)
        let mark = RectangleMark(xStart: .value("From", x0), xEnd: .value("To", x1),
                                 yStart: .value("Items", y0), yEnd: .value("Items", y1))
        return mark.foregroundStyle(color).opacity(barOpacity(bar.bin)).cornerRadius(2)
    }

    private func thresholdMark(_ t: Double) -> some ChartContent {
        RuleMark(x: .value("Threshold", t))
            .foregroundStyle(Palette.ink.opacity(0.85))
            .lineStyle(StrokeStyle(lineWidth: 1.5, dash: [4, 3]))
            .annotation(position: .top, alignment: .center, spacing: 2) {
                Text("acts at \(pct(t))").font(Typeface.mono(10, weight: .medium)).foregroundStyle(Palette.ink)
            }
    }

    private func chart(_ index: ResultsIndex, _ bins: ConfidenceBins) -> some View {
        let all = bars(index, bins)
        let selected = model.filter.bins
        let t = index.threshold
        let tallest: Int = (0..<bins.binCount).map { bins.total($0) }.max() ?? 1
        let yMax: Double = max(4, Double(tallest) * 1.1)
        return Chart {
            ForEach(all) { bar in barMark(bar) }
            thresholdMark(t)
        }
        .chartXScale(domain: bins.lower...1)
        .chartYScale(domain: 0...yMax)
        .chartXAxis {
            AxisMarks(values: .stride(by: 0.1)) { v in
                AxisGridLine().foregroundStyle(Palette.hairline)
                AxisValueLabel { if let d = v.as(Double.self) { Text(pct(d)).font(Typeface.mono(9)).foregroundStyle(Palette.inkSoft) } }
            }
        }
        .chartYAxis {
            AxisMarks(position: .leading, values: .automatic(desiredCount: 3)) { v in
                AxisGridLine().foregroundStyle(Palette.hairline)
                AxisValueLabel { if let n = v.as(Int.self) { Text("\(n)").font(Typeface.mono(9)).foregroundStyle(Palette.inkSoft) } }
            }
        }
        .chartOverlay { proxy in
            GeometryReader { geo in
                if let anchor = proxy.plotFrame {
                    let frame = geo[anchor]
                    ForEach(0..<bins.binCount, id: \.self) { b in
                        let r = bins.range(b)
                        let x0 = proxy.position(forX: r.lo) ?? 0, x1 = proxy.position(forX: r.hi) ?? 0
                        let on = selected == b...b
                        Button {
                            model.toggle(bins: b...b); onFilter()
                        } label: {
                            Rectangle().fill(on ? Palette.cyan.opacity(0.08) : Color.clear).contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .frame(width: max(1, x1 - x0), height: frame.height)
                        .position(x: frame.minX + (x0 + x1) / 2, y: frame.midY)
                        .accessibilityLabel("Confidence \(pct(r.lo)) to \(pct(r.hi)): \(bins.total(b)) answers")
                        .accessibilityAddTraits(on ? .isSelected : [])
                        .accessibilityIdentifier("results.bin.\(b)")
                    }
                }
            }
        }
        .accessibilityChartDescriptor(HistogramDescriptor(model: model, bins: bins))
    }
}

/// By source, by kind and over time — only the ones the data supports (two or more values).
struct BreakdownsCard: View {
    @ObservedObject var model: ResultsModel
    let muted: Int?
    let onFilter: () -> Void
    @State private var allSources = false

    var body: some View {
        let s = model.index?.summary ?? ResultsSummary()
        let sources = s.sources.sorted { $0.value.total != $1.value.total ? $0.value.total > $1.value.total : $0.key < $1.key }
        let kinds = s.kinds.sorted { $0.value.total != $1.value.total ? $0.value.total > $1.value.total : $0.key < $1.key }
        let months = s.months.keys.sorted()
        if sources.count > 1 || kinds.count > 1 || months.count > 1 {
            VStack(alignment: .leading, spacing: 14) {
                if sources.count > 1 {
                    VStack(alignment: .leading, spacing: 4) {
                        Caption(text: "By source")
                        ForEach(allSources ? sources : Array(sources.prefix(5)), id: \.key) { id, c in
                            BreakdownRow(title: ResultsNames.source(id), count: c, total: s.total, muted: muted,
                                         on: model.filter.source == id, id: "results.source.\(id)") {
                                SourceGlyph(id: ResultsLook.glyphSource(id), on: true, size: 28)
                            } action: { model.toggle(source: id); onFilter() }
                        }
                        if sources.count > 5 {
                            Button(allSources ? "Fewer" : "All \(sources.count) sources") { allSources.toggle() }
                                .font(.footnote.weight(.semibold)).frame(minHeight: 44)
                        }
                    }
                }
                if kinds.count > 1 {
                    VStack(alignment: .leading, spacing: 4) {
                        Caption(text: "By kind")
                        ForEach(kinds.prefix(6), id: \.key) { k, c in
                            BreakdownRow(title: k, count: c, total: s.total, muted: muted,
                                         on: model.filter.kind == k, id: "results.kind.\(k)") {
                                NeonIcon(name: ResultsLook.kindSymbol(k), color: Palette.blue, size: 15).frame(width: 28, height: 28)
                            } action: { model.toggle(kind: k); onFilter() }
                        }
                    }
                }
                if months.count > 1 {
                    TimelineChart(model: model, months: months, summary: s, muted: muted, onFilter: onFilter)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .card()
        }
    }
}

/// Items by month (the item's own date: taken, sent, modified), stacked by answer; tap a month to list it.
struct TimelineChart: View {
    @ObservedObject var model: ResultsModel
    let months: [String]
    let summary: ResultsSummary
    let muted: Int?
    let onFilter: () -> Void

    private struct Bar: Identifiable {
        let month: String, date: Date, bucket: ResultBucket, count: Int
        var id: String { month + bucket.key }
    }

    var body: some View {
        let shown = Array(months.suffix(24))
        let bars = shown.flatMap { m in
            (summary.months[m]?.buckets ?? [:]).sorted { $0.key < $1.key }.map { Bar(month: m, date: Self.date(m), bucket: $0.key, count: $0.value) }
        }
        let selected = model.filter.month
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Caption(text: "Over time")
                Spacer()
                Text(summary.undated > 0 ? "\(ResultsNames.count(summary.undated)) undated" : "by the item's date")
                    .font(Typeface.mono(10)).foregroundStyle(Palette.inkSoft)
            }
            Chart(bars) { bar in
                BarMark(x: .value("Month", bar.date, unit: .month), y: .value("Items", bar.count))
                    .foregroundStyle(ResultsLook.bucket(bar.bucket, muted: muted))
                    .opacity(selected == nil || selected == bar.month ? 1 : 0.3)
            }
            .chartXAxis {
                AxisMarks(values: .stride(by: .month, count: max(1, shown.count / 4))) { _ in
                    AxisValueLabel(format: .dateTime.month(.abbreviated).year(.twoDigits))
                        .font(Typeface.mono(9)).foregroundStyle(Palette.inkSoft)
                }
            }
            .chartYAxis {
                AxisMarks(position: .leading, values: .automatic(desiredCount: 3)) { v in
                    AxisGridLine().foregroundStyle(Palette.hairline)
                    AxisValueLabel { if let n = v.as(Int.self) { Text("\(n)").font(Typeface.mono(9)).foregroundStyle(Palette.inkSoft) } }
                }
            }
            .chartOverlay { proxy in
                GeometryReader { geo in
                    if let anchor = proxy.plotFrame {
                        let frame = geo[anchor]
                        let w = frame.width / CGFloat(max(shown.count, 1))
                        ForEach(Array(shown.enumerated()), id: \.element) { i, m in
                            let on = selected == m
                            Button {
                                model.toggle(month: m); onFilter()
                            } label: {
                                Rectangle().fill(on ? Palette.cyan.opacity(0.08) : Color.clear).contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                            .frame(width: w, height: frame.height)
                            .position(x: frame.minX + w * (CGFloat(i) + 0.5), y: frame.midY)
                            .accessibilityLabel("\(ResultsNames.month(m)): \(summary.months[m]?.total ?? 0) items")
                            .accessibilityAddTraits(on ? .isSelected : [])
                            .accessibilityIdentifier("results.month.\(m)")
                        }
                    }
                }
            }
            .frame(height: 110)
            .accessibilityChartDescriptor(TimelineDescriptor(months: shown, summary: summary))
            if months.count > shown.count {
                Text("The last 24 months shown; \(months.count - shown.count) earlier months are in the list.")
                    .font(.caption2).foregroundStyle(Palette.inkSoft)
            }
        }
    }

    static func date(_ month: String) -> Date {
        let p = month.split(separator: "-").compactMap { Int($0) }
        var c = DateComponents()
        c.year = p.first ?? 2000; c.month = p.count > 1 ? p[1] : 1; c.day = 15
        return Calendar(identifier: .gregorian).date(from: c) ?? Date(timeIntervalSince1970: 0)
    }
}

/// What decided: the rules by name and what each means, and the baseline's own words.
struct ReasonsCard: View {
    @ObservedObject var model: ResultsModel
    let judgment: UserJudgment
    let onFilter: () -> Void

    var body: some View {
        let s = model.index?.summary ?? ResultsSummary()
        VStack(alignment: .leading, spacing: 8) {
            Caption(text: "What decided")
            if s.checks.isEmpty {
                Text("No rule answered any item: every answer here is the decision model's or yours.")
                    .font(.footnote).foregroundStyle(Palette.inkSoft)
            }
            ForEach(s.checks.sorted { $0.value > $1.value }, id: \.key) { c, n in
                Button {
                    model.toggle(check: c); onFilter()
                } label: {
                    HStack(alignment: .top, spacing: 10) {
                        NeonIcon(name: "ruler", color: Palette.blue, size: 15).frame(width: 24)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(ResultsNames.check(c)).font(.subheadline.weight(.semibold)).foregroundStyle(Palette.ink)
                            Text(Self.meaning(c, judgment)).font(.caption).foregroundStyle(Palette.inkSoft)
                                .multilineTextAlignment(.leading).fixedSize(horizontal: false, vertical: true)
                        }
                        Spacer(minLength: 4)
                        Text(ResultsNames.count(n)).font(Typeface.mono(13, weight: .semibold)).monospacedDigit().foregroundStyle(Palette.ink)
                    }
                    .padding(.vertical, 6)
                    .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("\(ResultsNames.check(c)): \(n) items. \(Self.meaning(c, judgment))")
                .accessibilityIdentifier("results.reason.\(c)")
            }
            Text("The decision model gives a confidence, not reasons. Its least sure answers are one tap away above, and each item shows what it read.")
                .font(.caption).foregroundStyle(Palette.inkSoft)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .card()
    }

    static func meaning(_ check: String, _ j: UserJudgment) -> String {
        let base = j.baseline?.description_ ?? "the judgment's baseline rule"
        switch check {
        case "no-transaction-evidence": return "No sign that money moved or is owed, so the answer is no without asking the model."
        case "exact-duplicate": return "A byte-identical copy of another item."
        case "baseline": return "Set to always answer by the baseline: \(base)."
        case "auto-baseline": return "The baseline beat the model on your corrections, so it answers: \(base)."
        case "laya-off": return "The decision model was off for judgments; the baseline answered: \(base)."
        default: return "A mechanical check; the model was not asked."
        }
    }
}

/// "17 need you →": the way into the Unsure queue for this judgment.
struct NeedsYouEntry: View {
    let count: Int
    var body: some View {
        HStack(spacing: 12) {
            ZStack {
                RoundedRectangle(cornerRadius: 12, style: .continuous).fill(Palette.warnSoft)
                NeonIcon(name: "questionmark.bubble.fill", color: Palette.amber, size: 18, active: count > 0)
            }
            .frame(width: 44, height: 44)
            VStack(alignment: .leading, spacing: 1) {
                Text("\(ResultsNames.count(count)) need you")
                    .font(Typeface.display(22)).monospacedDigit().foregroundStyle(Palette.ink)
                Text(count == 0 ? "Nothing unsure waits — the queue also spot-checks sure answers." : "Answer them one tap at a time, most torn first.")
                    .font(.footnote).foregroundStyle(Palette.inkSoft)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 4)
            Image(systemName: "arrow.right").font(.headline).foregroundStyle(Palette.amber)
        }
        .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
        .card()
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(count) need you. Open the Unsure queue for this judgment")
        .accessibilityAddTraits(.isButton)
    }
}

// MARK: - Shared pieces

/// A horizontal bar of parts with 2 pt gaps.
struct StackedBar: View {
    let parts: [(Color, Double)]
    var height: CGFloat = 8

    var body: some View {
        GeometryReader { g in
            let total = max(parts.map(\.1).reduce(0, +), 1)
            let gaps = CGFloat(max(parts.filter { $0.1 > 0 }.count - 1, 0)) * 2
            HStack(spacing: 2) {
                ForEach(Array(parts.enumerated()), id: \.offset) { _, p in
                    if p.1 > 0 {
                        RoundedRectangle(cornerRadius: min(3, height / 2))
                            .fill(p.0)
                            .frame(width: max(2, (g.size.width - gaps) * p.1 / total))
                    }
                }
            }
        }
        .frame(height: height)
        .background(Palette.track.opacity(0.5), in: RoundedRectangle(cornerRadius: min(3, height / 2)))
    }
}

/// One tappable facet: swatch or symbol, title, count, percent. 44 pt tall.
struct FacetRow: View {
    let color: Color
    let symbol: String?
    let title: String
    let count: Int
    let percent: Int?
    let on: Bool
    let id: String
    var indent = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 10) {
                if let symbol {
                    NeonIcon(name: symbol, color: color, size: 14).frame(width: 22)
                } else {
                    RoundedRectangle(cornerRadius: 2).fill(color).frame(width: 8, height: 8).frame(width: 22)
                }
                Text(title).font(indent ? .footnote : .subheadline.weight(on ? .bold : .medium))
                    .foregroundStyle(indent ? Palette.inkSoft : Palette.ink)
                    .multilineTextAlignment(.leading)
                Spacer(minLength: 6)
                Text(ResultsNames.count(count)).font(Typeface.mono(13, weight: .semibold)).monospacedDigit().foregroundStyle(Palette.ink)
                if let percent {
                    Text("\(percent)%").font(Typeface.mono(12)).monospacedDigit().foregroundStyle(Palette.inkSoft)
                        .frame(minWidth: 38, alignment: .trailing)
                }
            }
            .padding(.leading, indent ? 22 : 6).padding(.trailing, 6)
            .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
            .background(on ? Palette.accentSoft : Color.clear, in: RoundedRectangle(cornerRadius: 10))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("\(title): \(count) items" + (percent.map { ", \($0) percent" } ?? ""))
        .accessibilityAddTraits(on ? .isSelected : [])
        .accessibilityIdentifier(id)
    }
}

/// A breakdown value: its glyph, name, the answer split as a thin bar, and its count.
struct BreakdownRow<Glyph: View>: View {
    let title: String
    let count: BreakdownCount
    let total: Int
    let muted: Int?
    let on: Bool
    let id: String
    @ViewBuilder let glyph: Glyph
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 10) {
                glyph
                VStack(alignment: .leading, spacing: 4) {
                    HStack {
                        Text(title).font(.subheadline.weight(on ? .bold : .medium)).foregroundStyle(Palette.ink).lineLimit(1)
                        Spacer(minLength: 4)
                        Text(ResultsNames.count(count.total)).font(Typeface.mono(12, weight: .semibold)).monospacedDigit()
                            .foregroundStyle(Palette.ink)
                    }
                    StackedBar(parts: count.buckets.sorted { $0.key < $1.key }.map { (ResultsLook.bucket($0.key, muted: muted), Double($0.value)) },
                               height: 6)
                        .frame(width: nil)
                        .accessibilityHidden(true)
                }
            }
            .padding(.horizontal, 6)
            .frame(maxWidth: .infinity, minHeight: 44)
            .background(on ? Palette.accentSoft : Color.clear, in: RoundedRectangle(cornerRadius: 10))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("\(title): \(count.total) items")
        .accessibilityAddTraits(on ? .isSelected : [])
        .accessibilityIdentifier(id)
    }
}

// MARK: - Audio graphs

struct SplitDescriptor: AXChartDescriptorRepresentable {
    let model: ResultsModel
    let summary: ResultsSummary
    let buckets: [ResultBucket]

    @MainActor func makeChartDescriptor() -> AXChartDescriptor {
        let names = buckets.map { model.bucketTitle($0) }
        let x = AXCategoricalDataAxisDescriptor(title: "Answer", categoryOrder: names)
        let y = AXNumericDataAxisDescriptor(title: "Items", range: 0...Double(max(summary.total, 1)), gridlinePositions: []) { "\(Int($0)) items" }
        let series = AXDataSeriesDescriptor(name: "Items per answer", isContinuous: false,
                                            dataPoints: buckets.map { AXDataPoint(x: model.bucketTitle($0), y: Double(summary.count($0))) })
        return AXChartDescriptor(title: "Answer split", summary: "\(summary.total) items judged", xAxis: x, yAxis: y, additionalAxes: [], series: [series])
    }
}

struct HistogramDescriptor: AXChartDescriptorRepresentable {
    let model: ResultsModel
    let bins: ConfidenceBins

    @MainActor func makeChartDescriptor() -> AXChartDescriptor {
        let x = AXNumericDataAxisDescriptor(title: "Confidence", range: bins.lower...1, gridlinePositions: []) { pct($0) }
        let maxBin = (0..<bins.binCount).map { bins.total($0) }.max() ?? 1
        let y = AXNumericDataAxisDescriptor(title: "Answers", range: 0...Double(max(maxBin, 1)), gridlinePositions: []) { "\(Int($0))" }
        let series = model.options.indices.map { o in
            AXDataSeriesDescriptor(name: model.optionTitle(o), isContinuous: false,
                                   dataPoints: (0..<bins.binCount).map { AXDataPoint(x: bins.range($0).lo, y: Double(bins.counts[$0][o])) })
        }
        let below = (0..<bins.binCount).filter { bins.range($0).hi <= (model.index?.threshold ?? 0) }.reduce(0) { $0 + bins.total($1) }
        return AXChartDescriptor(title: "The decision model's confidence",
                                 summary: "\(bins.total) answers; \(below) below the threshold of \(pct(model.index?.threshold ?? 0))",
                                 xAxis: x, yAxis: y, additionalAxes: [], series: series)
    }
}

struct TimelineDescriptor: AXChartDescriptorRepresentable {
    let months: [String]
    let summary: ResultsSummary

    func makeChartDescriptor() -> AXChartDescriptor {
        let x = AXCategoricalDataAxisDescriptor(title: "Month", categoryOrder: months.map(ResultsNames.month))
        let maxN = months.map { summary.months[$0]?.total ?? 0 }.max() ?? 1
        let y = AXNumericDataAxisDescriptor(title: "Items", range: 0...Double(max(maxN, 1)), gridlinePositions: []) { "\(Int($0)) items" }
        let series = AXDataSeriesDescriptor(name: "Items per month", isContinuous: false,
                                            dataPoints: months.map { AXDataPoint(x: ResultsNames.month($0), y: Double(summary.months[$0]?.total ?? 0)) })
        return AXChartDescriptor(title: "Items over time", summary: "\(months.count) months", xAxis: x, yAxis: y, additionalAxes: [], series: [series])
    }
}
