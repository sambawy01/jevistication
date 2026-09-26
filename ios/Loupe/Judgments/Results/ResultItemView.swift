import LoupeKit
import SwiftUI

/// One item from the dashboard: the preview, the answer and who gave it and why, the model's whole raw
/// distribution against the threshold, correct / remove / undo (the same correction records as the queue),
/// the second opinion, the item's facts and what the model read.
struct ResultItemView: View {
    @ObservedObject var model: ResultsModel
    @ObservedObject var service: JudgmentsService
    let index: Int32
    let muted: Int?

    var body: some View {
        ScrollView {
            if let r = model.record(index), let j = model.judgment {
                let row = model.row(index)
                let item = row?.item ?? fallbackItem(r.itemId)
                VStack(alignment: .leading, spacing: 12) {
                    if item == nil {
                        Text(r.name).font(Typeface.display(24)).foregroundStyle(Palette.ink)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    if let item {
                        ItemRefHeader(item: item)
                        ItemPreview(item: item)
                        ItemActions(item: item)
                    } else {
                        Text("This item is no longer scanned, so it cannot be opened.").font(.caption).foregroundStyle(Palette.inkSoft)
                    }
                    answerCard(r, row, j, item)
                    correctCard(r)
                    if let row { SecondOpinionSection(judgment: j, result: row, assist: AssistService.shared) }
                    if let item { facts(item) }
                }
                .padding(16)
            } else {
                Text("This item is not in the results any more.").foregroundStyle(Palette.inkSoft).padding(24)
            }
        }
        .neonGround()
        .navigationTitle("Item")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func fallbackItem(_ id: String) -> SourceItem? {
        #if DEBUG
        return ResultsFixture.items[id]
        #else
        return nil
        #endif
    }

    // MARK: The answer and why

    private func answerCard(_ r: ResultRecord, _ row: ResultRow?, _ j: UserJudgment, _ item: SourceItem?) -> some View {
        let color = ResultsLook.bucket(r.bucket, muted: muted)
        return VStack(alignment: .leading, spacing: 10) {
            Caption(text: "Answer")
            HStack(alignment: .firstTextBaseline, spacing: 10) {
                Text(model.bucketTitle(r.bucket))
                    .font(Typeface.display(22)).foregroundStyle(color)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityIdentifier("detail.answer")
                Spacer()
                if r.isModel {
                    Text(pct(r.mass)).font(Typeface.mono(18, weight: .semibold)).monospacedDigit().foregroundStyle(Palette.ink)
                }
            }
            Label {
                Text(why(r, j)).font(.footnote).foregroundStyle(Palette.ink).fixedSize(horizontal: false, vertical: true)
            } icon: {
                NeonIcon(name: ResultsLook.answererSymbol(r.answerer), color: ResultsLook.answerer(r.answerer), size: 14)
            }
            if let row {
                VStack(alignment: .leading, spacing: 6) {
                    Caption(text: r.check != nil ? "What the rule said" : "What the model said (raw)")
                    ForEach(row.masses(judgment: j), id: \.first) { pair in
                        let label = (pair.first as String?) ?? ""
                        let mass = (pair.second as? KotlinDouble)?.doubleValue ?? 0
                        let o = model.options.firstIndex(of: label) ?? 0
                        VStack(alignment: .leading, spacing: 3) {
                            HStack {
                                Text(j.shown(label)).font(.subheadline).foregroundStyle(Palette.ink)
                                Spacer()
                                Text(pct(mass)).font(Typeface.mono(12)).monospacedDigit().foregroundStyle(Palette.ink)
                            }
                            ConfidenceBar(value: mass, threshold: j.threshold, color: ResultsLook.option(o, muted: muted))
                        }
                        .accessibilityElement(children: .combine)
                    }
                    ForEach([row.ruleNote, row.row.failure.map { "Unusable answer: \($0)" }, row.inputCutNote, row.criteriaCutNote].compactMap { $0 },
                            id: \.self) {
                        Text($0).font(.caption).foregroundStyle(Palette.inkSoft).fixedSize(horizontal: false, vertical: true)
                    }
                }
            }
            if let item, TransactionEvidence.shared.applies(judgment: j), item.hasText {
                Text(evidence(TransactionEvidence.shared.assess(text: item.text)))
                    .font(.caption).foregroundStyle(Palette.inkSoft).fixedSize(horizontal: false, vertical: true)
            }
            Text("Preview only: Loupe changes nothing on this phone.").font(.caption2).foregroundStyle(Palette.inkSoft)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .card()
    }

    private func why(_ r: ResultRecord, _ j: UserJudgment) -> String {
        let lean = r.top >= 0 ? model.optionTitle(r.top) : "?"
        switch r.answerer {
        case .you:
            if r.isModel { return "You answered. The decision model said \(lean) at \(pct(r.mass)) (it acts at \(pct(j.threshold)))." }
            if let c = r.check { return "You answered. Before that a rule had: \(ResultsNames.check(c)) → \(lean)." }
            return "You answered."
        case .rule:
            return "Answered by rule — \(ResultsNames.check(r.check ?? "")). \(ReasonsCard.meaning(r.check ?? "", j))"
        case .model:
            return "The decision model answered at \(pct(r.mass)), at or above its threshold of \(pct(j.threshold))."
        case .waiting:
            return r.mass >= j.threshold
                ? "The decision model leans \(lean) at \(pct(r.mass)), but it read only part of the item, so it waits for you."
                : "Unsure: the decision model leans \(lean) at \(pct(r.mass)), below its threshold of \(pct(j.threshold)). It waits for you."
        case .failed:
            return "The model's answer could not be used. \(j.onFailure == .loud ? "This judgment is loud: check it yourself." : "Nothing was done with it.")"
        }
    }

    private func evidence(_ v: TransactionEvidence.Verdict) -> String {
        switch v {
        case .transaction: return "Payment evidence: receipt, invoice or payment wording found, so the decision model decides."
        case .weak: return "Payment evidence: only weak hints (a total, \"paid\", a card brand), so the decision model decides."
        case .listing: return "Payment evidence: reads as a shop listing, with nothing that records a payment."
        default: return "Payment evidence: none found in the text."
        }
    }

    // MARK: Correct / undo

    private func correctCard(_ r: ResultRecord) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Caption(text: r.correction == nil ? "Is this right?" : "Your answer")
            ForEach(model.options.indices, id: \.self) { i in
                if r.canCorrect(to: i) {
                    let current = r.answer == i
                    Button {
                        model.correct([index], to: i, service: service)
                    } label: {
                        HStack {
                            Text(model.optionTitle(i)).font(.body.weight(.semibold)).foregroundStyle(Palette.ink)
                                .multilineTextAlignment(.leading)
                            Spacer()
                            if current {
                                Text(r.correction == i ? "your answer" : "current").font(.caption).foregroundStyle(Palette.inkSoft)
                                Image(systemName: "checkmark.circle.fill").foregroundStyle(ResultsLook.option(i, muted: muted))
                            } else {
                                Image(systemName: "circle").foregroundStyle(Palette.inkSoft)
                            }
                        }
                        .padding(12)
                        .frame(maxWidth: .infinity, minHeight: 44)
                        .background(current ? Palette.accentSoft : Palette.groundMid, in: RoundedRectangle(cornerRadius: 10))
                        .overlay(RoundedRectangle(cornerRadius: 10).stroke(current ? Palette.borderActive : Palette.hairline))
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Answer: \(model.optionTitle(i))")
                    .accessibilityAddTraits(current ? .isSelected : [])
                    .accessibilityIdentifier("detail.option.\(i)")
                }
            }
            HStack {
                if r.correction == nil, let a = r.answer {
                    Button { model.correct([index], to: a, service: service) } label: { Label("Confirm", systemImage: "checkmark.seal") }
                        .frame(minHeight: 44)
                        .accessibilityIdentifier("detail.confirm")
                }
                if r.correction != nil {
                    Button(role: .destructive) { model.clearCorrection(index, service: service) } label: {
                        Label("Remove my answer", systemImage: "xmark.circle")
                    }
                    .frame(minHeight: 44)
                    .accessibilityIdentifier("detail.clear")
                }
                Spacer()
                Button { model.undoLast(service: service) } label: { Label("Undo", systemImage: "arrow.uturn.backward") }
                    .disabled(!model.canUndo)
                    .frame(minHeight: 44)
                    .accessibilityIdentifier("detail.undo")
            }
            .font(.subheadline)
            Text("Answers go to your corrections log, keyed to this wording; they teach calibration and the Measure screen.")
                .font(.caption2).foregroundStyle(Palette.inkSoft)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .card()
    }

    // MARK: Facts

    private func facts(_ item: SourceItem) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            VStack(alignment: .leading, spacing: 6) {
                Caption(text: "Mechanical facts")
                fact("Source", item.sourceLabel)
                fact("Type", "\(item.kind.title) (\(item.mime))")
                if let d = item.dateIso { fact("Date", "\(d) (from the \(item.dateOrigin?.title ?? "file"))") }
                if let e = item.email {
                    fact("From", [e.fromName, e.fromAddress.map { "<\($0)>" }].compactMap { $0 }.joined(separator: " "))
                    if let s = e.subject { fact("Subject", s) }
                }
                if let dup = item.duplicateOf { fact("Exact duplicate of", dup) }
            }
            .frame(maxWidth: .infinity, alignment: .leading).card()
            VStack(alignment: .leading, spacing: 6) {
                Caption(text: "What the model read")
                Text(String(item.text.prefix(2000)) + (item.text.count > 2000 ? "\n…" : ""))
                    .font(Typeface.mono(11)).foregroundStyle(Palette.inkSoft)
                    .textSelection(.enabled)
            }
            .frame(maxWidth: .infinity, alignment: .leading).card()
        }
    }

    private func fact(_ k: String, _ v: String) -> some View {
        ViewThatFits(in: .horizontal) {
            HStack(alignment: .firstTextBaseline) {
                Text(k).font(.caption.weight(.semibold)).foregroundStyle(Palette.inkSoft).frame(width: 90, alignment: .leading)
                Text(v).font(.caption).foregroundStyle(Palette.ink)
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(k).font(.caption.weight(.semibold)).foregroundStyle(Palette.inkSoft)
                Text(v).font(.caption).foregroundStyle(Palette.ink)
            }
        }
    }
}

/// The item itself, large: the picture for photos and image files, else the start of its text.
struct ItemPreview: View {
    let item: SourceItem
    @State private var image: UIImage?

    var body: some View {
        Group {
            if let image {
                Image(uiImage: image).resizable().scaledToFit()
                    .frame(maxWidth: .infinity, maxHeight: 260)
                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                    .accessibilityLabel("Picture of \(item.name)")
            } else if item.hasText {
                Text(String(item.text.prefix(500)))
                    .font(.footnote).foregroundStyle(Palette.ink).lineLimit(10)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(12)
                    .background(Palette.track, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                    .accessibilityLabel("Start of the text: \(String(item.text.prefix(200)))")
            }
        }
        .task(id: item.id) {
            guard item.kind == .image, RowThumb.wantsThumb(item) else { return }
            image = await ItemImages.image(for: LiveItemResolver.live.target(for: item), size: CGSize(width: 900, height: 900))
        }
    }
}

/// A thin confidence bar with the threshold tick.
struct ConfidenceBar: View {
    let value: Double
    let threshold: Double
    var color: Color = Palette.blue

    var body: some View {
        GeometryReader { g in
            ZStack(alignment: .leading) {
                Capsule().fill(Palette.track)
                Capsule().fill(color.opacity(0.85)).frame(width: max(0, min(1, value)) * g.size.width)
                Rectangle().fill(Palette.ink.opacity(0.6)).frame(width: 1.5).offset(x: threshold * g.size.width)
            }
        }
        .frame(height: 6)
        .accessibilityHidden(true)
    }
}
