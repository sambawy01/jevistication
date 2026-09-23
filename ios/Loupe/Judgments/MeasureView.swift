import SwiftUI
import LoupeKit

/// One judgment's record on your corrections (D2-D4): visible calibration with an honest gate,
/// the dumb baseline against the model, and the threshold slider with a counterfactual preview
/// over rows already logged. Per judgment only — mirrors the desktop's Measure screens.
struct MeasureView: View {
    @ObservedObject var service: JudgmentsService
    let judgmentId: String
    @State private var candidate: Double?

    var body: some View {
        ScrollView {
            if let j = service.judgment(judgmentId) {
                let s = service.measure(j)
                VStack(alignment: .leading, spacing: 12) {
                    Text(j.title).font(Typeface.display(26)).foregroundStyle(Palette.ink)
                    Text("This is one judgment's record, on your corrections only.")
                        .font(.footnote).foregroundStyle(Palette.inkSoft)
                    if s.decisions == 0 {
                        box(title: "Nothing measured yet", tone: .plain) {
                            Text("Run \"\(j.title)\" and answer some items in the Unsure queue.").font(.subheadline)
                        }
                    } else {
                        stats(s)
                        calibration(s)
                        baseline(j)
                        slider(j)
                    }
                    if s.earlierWording > 0 {
                        Text("\(s.earlierWording) decision(s) made under earlier wording are not counted here.")
                            .font(.caption).foregroundStyle(Palette.inkSoft)
                    }
                    if let n = service.notice { Text(n).font(.footnote).foregroundStyle(Palette.inkSoft) }
                }
                .padding(16)
            }
        }
        .background(Palette.ground.ignoresSafeArea())
        .navigationTitle("Measure")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { service.load(); service.refreshLedger() }
    }

    private func stats(_ s: MeasureSummary) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 14) {
                stat("\(s.decisions)", "judged")
                stat("\(s.corrections)", "your corrections")
                stat(pct(s.coverage), "answered at threshold")
                stat(pct(s.declined), "left unsure")
            }
            if s.mechanical > 0 {
                Text("\(s.mechanical) of these were answered by rule, not the model, and are left out of every model figure below.")
                    .font(.caption).foregroundStyle(Palette.inkSoft)
            }
            if let a = s.agreement?.doubleValue {
                fact("Agreement, forced to answer", "\(pct(a)) over \(s.corrections) correction(s)")
                fact("Selective accuracy", s.selectiveAccuracy.map { "\(pct($0.doubleValue)) on the \(s.selectiveN) corrected item(s) it answered" } ?? "no corrected item was above the threshold")
            } else {
                Text("\(s.correctionsNeededForAgreement) more correction(s) needed before an agreement figure is shown — below \(JudgmentMeasure.shared.MIN_FOR_AGREEMENT) it would mostly be noise.")
                    .font(.footnote).foregroundStyle(Palette.warnText)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading).card()
        .accessibilityIdentifier("measure.stats")
    }

    @ViewBuilder private func calibration(_ s: MeasureSummary) -> some View {
        box(title: "Calibration (is its confidence honest?)", tone: s.gateMessage == nil ? .plain : .warn) {
            if let gate = s.gateMessage {
                Text(gate).font(.subheadline).accessibilityIdentifier("measure.calibration.gate")
            } else {
                fact("ECE", String(format: "%.3f (0 is perfectly calibrated)", s.ece!.doubleValue))
                fact("Brier", String(format: "%.3f (lower is better)", s.brier?.doubleValue ?? 0))
                bins(s.reliability)
                Text("Bars: how often it was right in each confidence band. A calibrated model's bar reaches its band.")
                    .font(.caption).foregroundStyle(Palette.inkSoft)
                if !s.overconfident.isEmpty {
                    Text("Over-confident where it claims " + s.overconfident.map { "\(pct($0.lower))–\(pct($0.upper)) (right \(pct($0.accuracy)) of \($0.count))" }.joined(separator: ", "))
                        .font(.footnote).foregroundStyle(Palette.warnText)
                }
            }
        }
    }

    private func bins(_ bins: [ReliabilityBin]) -> some View {
        HStack(alignment: .bottom, spacing: 3) {
            ForEach(Array(bins.enumerated()), id: \.offset) { _, b in
                VStack(spacing: 2) {
                    ZStack(alignment: .bottom) {
                        RoundedRectangle(cornerRadius: 2).fill(Palette.track).frame(height: 80)
                        RoundedRectangle(cornerRadius: 2).fill(b.count == 0 ? Color.clear : Palette.blue)
                            .frame(height: 80 * b.accuracy)
                        Rectangle().fill(Palette.ink.opacity(0.5)).frame(height: 1).offset(y: -80 * b.upper)
                    }
                    Text("\(Int(b.lower * 100))").font(.system(size: 9)).foregroundStyle(Palette.inkSoft)
                }
                .accessibilityElement(children: .ignore)
                .accessibilityLabel("Band \(pct(b.lower)) to \(pct(b.upper)): right \(pct(b.accuracy)) of \(b.count)")
            }
        }
    }

    @ViewBuilder private func baseline(_ j: UserJudgment) -> some View {
        if let base = j.baseline {
            let v = service.baseline(j)
            box(title: "The dumb version", tone: v?.baselineWins == true ? .warn : .plain) {
                Text(base.description_).font(.subheadline)
                if let v {
                    HStack(spacing: 14) {
                        stat(pct(v.report.accuracyAtFullCoverage), "model, forced to answer")
                        stat(pct(v.report.baselineAccuracy), "baseline, same items")
                        stat("\(v.report.n)", "corrected items")
                    }
                    Text(v.message).font(.subheadline.weight(.semibold))
                        .foregroundStyle(v.baselineWins ? Palette.warnText : Palette.ink)
                        .accessibilityIdentifier("measure.baseline.verdict")
                    if v.baselineWins || j.useBaseline {
                        Toggle("Use the baseline for this judgment", isOn: Binding(
                            get: { j.useBaseline }, set: { service.setUseBaseline(j.id, $0) }))
                            .accessibilityIdentifier("measure.useBaseline")
                        Text("From the next run the baseline rule answers and the model is not asked. Those answers are logged as a rule, never as model evidence.")
                            .font(.caption).foregroundStyle(Palette.inkSoft)
                    }
                } else {
                    Text("The comparison runs on items you have corrected. Answer some in the Unsure queue.")
                        .font(.footnote).foregroundStyle(Palette.inkSoft)
                }
                Text("Compared at equal coverage — both forced to answer every item — replaying exactly what the model logged rather than re-running it.")
                    .font(.caption).foregroundStyle(Palette.inkSoft)
            }
        }
    }

    private func slider(_ j: UserJudgment) -> some View {
        let value = candidate ?? j.threshold
        let p = service.preview(j, candidate: value)
        return box(title: "Threshold", tone: .plain) {
            Text(String(format: "Act when the model's top answer is at least %.2f (raw)", value)).font(.subheadline)
            Slider(value: Binding(get: { value }, set: { candidate = ($0 * 100).rounded() / 100 }), in: 0.3...1.0)
                .accessibilityIdentifier("measure.threshold")
                .accessibilityValue(String(format: "%.2f", value))
            Text(p.line).font(.headline).foregroundStyle(Palette.ink).accessibilityIdentifier("measure.preview")
            Text(p.basis).font(.caption).foregroundStyle(Palette.inkSoft)
            HStack {
                Button(String(format: "Use %.2f", value)) {
                    service.setThreshold(j.id, value)
                    candidate = nil
                }
                .buttonStyle(.borderedProminent)
                .disabled(abs(value - j.threshold) < 0.0001)
                .accessibilityIdentifier("measure.apply")
                Button(String(format: "Reset (%.2f)", j.threshold)) { candidate = nil }.buttonStyle(.bordered)
            }
        }
    }

    // MARK: bits

    enum Tone { case plain, warn }

    private func box<C: View>(title: String, tone: Tone, @ViewBuilder _ content: () -> C) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title).font(.headline).foregroundStyle(Palette.ink)
            content().foregroundStyle(tone == .warn ? Palette.warnText : Palette.ink)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(tone == .warn ? Palette.warnSoft : Palette.card, in: RoundedRectangle(cornerRadius: 16))
    }

    private func stat(_ n: String, _ label: String) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(n).font(Typeface.display(20)).foregroundStyle(Palette.blue)
            Text(label).font(.caption2).foregroundStyle(Palette.inkSoft)
        }
    }

    private func fact(_ k: String, _ v: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(k).font(.caption).foregroundStyle(Palette.inkSoft)
            Text(v).font(.subheadline)
        }
    }

}
