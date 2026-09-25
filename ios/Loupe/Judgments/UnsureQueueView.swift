import SwiftUI
import LoupeKit

/// The Unsure queue (D1) across every judgment: the items a correction teaches most, plus a random
/// audit arm of confident ones. One tap per answer (each option is a button); Skip moves on; Undo
/// retracts the last answer. Every answer is a correction in the ledger, keyed by item + the
/// judgment's criteria hash, so rewording a judgment starts its queue and numbers again.
struct UnsureQueueView: View {
    @ObservedObject var service: JudgmentsService
    @State private var skipped: Set<String> = []
    @State private var happy = false

    private func id(_ e: UnsureEntry) -> String { "\(e.judgment.id)|\(e.itemId)" }

    var body: some View {
        let all = service.unsure()
        let waiting = all.filter { !skipped.contains(id($0)) }
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                HStack(alignment: .center, spacing: 12) {
                    MascotView(state: happy ? .found : (all.isEmpty ? .idle : .thinking), size: 72)
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Needs you: \(all.count)")
                            .font(Typeface.display(26)).foregroundStyle(Palette.ink)
                            .accessibilityIdentifier("queue.count")
                        Text("Check the decision model's decisions one at a time. The ones it was unsure about come first; about one in five is a random pick from answers it was sure of, so the measurements are not built only from hard cases.")
                            .font(.footnote).foregroundStyle(Palette.inkSoft)
                    }
                }
                if let e = waiting.first {
                    card(e, position: (all.firstIndex { id($0) == id(e) } ?? 0) + 1, of: all.count)
                        .requiresLaya("queue", what: "Answering the Unsure queue")
                } else if !all.isEmpty {
                    empty("You skipped everything waiting", "Skipped items come back next time you open the queue.") {
                        Button("Show skipped again") { skipped.removeAll() }.buttonStyle(.bordered)
                    }
                } else {
                    empty("Nothing waiting",
                          service.rows.isEmpty
                            ? "Run a judgment first; the queue fills with what it is least sure about."
                            : "Every decision under each judgment's current wording has your answer.") { EmptyView() }
                }
                HStack {
                    Button {
                        service.undoLastAnswer()
                    } label: { Label("Undo last answer", systemImage: "arrow.uturn.backward") }
                        .disabled(!service.canUndo)
                        .accessibilityIdentifier("queue.undo")
                    Spacer()
                }
                .font(.subheadline)
            }
            .padding(16)
        }
        .neonGround()
        .navigationTitle("Unsure")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { service.load(); service.refreshLedger() }
    }

    private func card(_ e: UnsureEntry, position: Int, of total: Int) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text("\(position) of \(total) · \(e.why)").font(Typeface.mono(11)).foregroundStyle(Palette.inkSoft)
                Spacer()
                if e.isAudit { Pill(text: "spot check", color: Palette.inkSoft) }
            }
            Text(e.judgment.title).font(.caption).foregroundStyle(Palette.inkSoft)
            Text(e.judgment.question).font(.headline).foregroundStyle(Palette.blue)
            ItemRefHeader(item: e.item)
            ItemActions(item: e.item)
            Text(String(e.item.text.prefix(600)))
                .font(.footnote).foregroundStyle(Palette.inkSoft).lineLimit(8)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(10).background(Palette.track, in: RoundedRectangle(cornerRadius: 8))
            ForEach(Array(e.options.enumerated()), id: \.offset) { i, pair in
                let label = pair.first! as String
                let mass = pair.second!.doubleValue
                Button {
                    service.answer(e, label: label)
                    cheer()
                } label: {
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(e.judgment.shown(label)).font(.body.weight(.semibold)).foregroundStyle(Palette.ink)
                                .multilineTextAlignment(.leading)
                            if label == e.modelPick {
                                Text("model's pick · \(Int((mass * 100).rounded()))% raw").font(.caption).foregroundStyle(Palette.inkSoft)
                            } else {
                                Text("\(Int((mass * 100).rounded()))% raw").font(.caption).foregroundStyle(Palette.inkSoft)
                            }
                        }
                        Spacer()
                        Image(systemName: "checkmark.circle").foregroundStyle(Palette.blue)
                    }
                    .padding(12)
                    .frame(maxWidth: .infinity, minHeight: 44)
                    .background(label == e.modelPick ? Palette.accentSoft : Palette.card, in: RoundedRectangle(cornerRadius: 10))
                    .overlay(RoundedRectangle(cornerRadius: 10).stroke(Palette.hairline))
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("queue.option.\(i)")
                .accessibilityLabel("Answer: \(e.judgment.shown(label))")
            }
            Button("Skip") { skipped.insert(id(e)) }
                .buttonStyle(.bordered)
                .accessibilityIdentifier("queue.skip")
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .card()
    }

    private func cheer() {
        happy = true
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 900_000_000)
            happy = false
        }
    }

    private func empty<A: View>(_ title: String, _ message: String, @ViewBuilder action: () -> A) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title).font(.headline).foregroundStyle(Palette.ink)
            Text(message).font(.subheadline).foregroundStyle(Palette.inkSoft)
            action()
        }
        .frame(maxWidth: .infinity, alignment: .leading).card()
        .accessibilityIdentifier("queue.empty")
    }
}
