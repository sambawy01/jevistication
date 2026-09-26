import SwiftUI

extension ProtectionLevel {
    var color: Color {
        switch self {
        case .dangerous: return Palette.dangerText
        case .suspicious: return Palette.warnText
        case .safe: return Palette.okText
        }
    }

    var soft: Color {
        switch self {
        case .dangerous: return Palette.dangerSoft
        case .suspicious: return Palette.warnSoft
        case .safe: return Palette.okSoft
        }
    }

    var symbol: String {
        switch self {
        case .dangerous: return "xmark.shield.fill"
        case .suspicious: return "exclamationmark.shield.fill"
        case .safe: return "checkmark.shield.fill"
        }
    }
}

/// Check a link: the input card, the verdict and the recent checks.
struct LinkCheckView: View {
    @StateObject private var model: LinkCheckModel
    @ObservedObject private var store: ProtectionStore
    @FocusState private var focused: Bool
    @State private var confirmClear = false

    init(model: LinkCheckModel? = nil, store: ProtectionStore = .shared) {
        _model = StateObject(wrappedValue: model ?? LinkCheckModel(store: store))
        self.store = store
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                inputCard
                if let problem = model.problem {
                    Text(problem).font(.callout).foregroundStyle(Palette.warnText)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .accessibilityIdentifier("protect.link.problem")
                }
                if let v = model.verdict {
                    VerdictCard(verdict: v)
                        .transition(.opacity)
                }
                recentSection
            }
            .padding(16)
        }
        .scrollDismissesKeyboard(.interactively)
        .neonGround()
        .navigationTitle("Check a link")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { store.reload() }
    }

    private var inputCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Got a link in a message and not sure? Paste it here. Loupe checks it on this iPhone with the same phishing checks it uses on your mail. It never opens the link.")
                .font(.footnote).foregroundStyle(Palette.inkSoft)
            TextField("Paste or type a link", text: $model.input, axis: .vertical)
                .lineLimit(1...4)
                .font(Typeface.mono(15))
                .foregroundStyle(Palette.ink)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .keyboardType(.URL)
                .submitLabel(.go)
                .focused($focused)
                .onSubmit { run() }
                .padding(12)
                .background(Palette.groundMid, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).stroke(Palette.border))
                .accessibilityIdentifier("protect.link.input")
            HStack(spacing: 10) {
                // The system's paste control: the clipboard is read only when the user taps it (no
                // "Loupe pasted from…" prompt, and never on its own).
                PasteButton(payloadType: String.self) { strings in
                    if let s = strings.first { model.paste(s); run() }
                }
                .labelStyle(.titleAndIcon)
                .buttonBorderShape(.capsule)
                .tint(Palette.blue)
                .accessibilityIdentifier("protect.link.paste")
                Spacer()
                if !model.input.isEmpty {
                    Button("Clear") { model.clear() }
                        .font(.callout).foregroundStyle(Palette.inkSoft)
                        .accessibilityIdentifier("protect.link.clear")
                }
                Button {
                    run()
                } label: {
                    if model.checking { ProgressView().tint(Palette.onAccent) } else { Text("Check") }
                }
                .buttonStyle(.neonPrimary)
                .disabled(model.input.trimmingCharacters(in: .whitespaces).isEmpty || model.checking)
                .accessibilityIdentifier("protect.link.check")
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .card()
    }

    private func run() {
        focused = false
        Task { await model.check() }
    }

    @ViewBuilder private var recentSection: some View {
        if !store.recent.isEmpty {
            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    Caption(text: "Recent checks")
                    Spacer()
                    Button("Clear") { confirmClear = true }
                        .font(.caption.weight(.semibold))
                        .accessibilityIdentifier("protect.recent.clear")
                }
                Text("Kept on this iPhone only, without the part after \"?\" (it can hold sign-in codes).")
                    .font(.caption).foregroundStyle(Palette.inkSoft)
                ForEach(store.recent) { v in
                    Button { model.show(v) } label: {
                        HStack(spacing: 10) {
                            NeonIcon(name: v.level.symbol, color: v.level.color, size: 16)
                            VStack(alignment: .leading, spacing: 2) {
                                // A name in international letters shows as written (punycode): "pаypal.com" must not read as paypal.com.
                                Text(v.unicodeHost == v.host ? v.unicodeHost : v.host).font(Typeface.mono(13)).foregroundStyle(Palette.ink).lineLimit(1)
                                Text("\(v.level.title) · \(v.origin.title) · \(v.checkedAt.formatted(date: .abbreviated, time: .shortened))")
                                    .font(.caption).foregroundStyle(Palette.inkSoft).lineLimit(1)
                            }
                            Spacer()
                        }
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("protect.recent.row")
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .card()
            .confirmationDialog("Clear recent checks?", isPresented: $confirmClear, titleVisibility: .visible) {
                Button("Clear recent checks", role: .destructive) { store.clearRecent() }
            } message: {
                Text("The list of links you checked is removed from this iPhone. The Spotted log stays.")
            }
        }
    }
}

/// One verdict: the level, the score, the website, the reasons, and what ran where.
struct VerdictCard: View {
    let verdict: LinkVerdict

    var body: some View {
        let v = verdict
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .firstTextBaseline, spacing: 10) {
                NeonIcon(name: v.level.symbol, color: v.level.color, size: 26, active: v.level.flagged)
                VStack(alignment: .leading, spacing: 2) {
                    Text(v.title).font(Typeface.display(26)).foregroundStyle(v.level.color)
                        .accessibilityIdentifier("protect.verdict.title")
                    Text("Risk score \(v.score) of 100").font(Typeface.mono(12)).foregroundStyle(Palette.inkSoft)
                        .accessibilityIdentifier("protect.verdict.score")
                }
            }
            .accessibilityElement(children: .combine)
            .accessibilityIdentifier("protect.verdict")
            .accessibilityValue(v.level.rawValue)
            VStack(alignment: .leading, spacing: 2) {
                Text(v.unicodeHost).font(Typeface.mono(15)).foregroundStyle(Palette.ink).textSelection(.enabled)
                if v.unicodeHost != v.host {
                    Text("Written as \(v.host)").font(Typeface.mono(11)).foregroundStyle(Palette.inkSoft)
                        .accessibilityIdentifier("protect.verdict.ascii")
                }
            }
            if !v.reasons.isEmpty {
                VStack(alignment: .leading, spacing: 4) {
                    Text(v.reasonsTitle).font(.caption.weight(.semibold)).foregroundStyle(v.level.flagged ? v.level.color : Palette.inkSoft)
                    ForEach(Array(v.reasons.enumerated()), id: \.offset) { _, r in
                        VStack(alignment: .leading, spacing: 1) {
                            Text("• " + r.text).font(.callout).foregroundStyle(Palette.ink)
                            if let o = r.online { Text("  " + o).font(.caption2).foregroundStyle(Palette.cyan) }
                        }
                    }
                }
                .accessibilityIdentifier("protect.verdict.reasons")
            } else {
                Text("None of Loupe's checks found a warning sign. That is not a promise the site is safe: stay careful with sign-in and payment pages you reached from a message.")
                    .font(.callout).foregroundStyle(Palette.inkSoft)
            }
            if !v.reassuring.isEmpty {
                VStack(alignment: .leading, spacing: 3) {
                    Text("Reassuring facts").font(.caption.weight(.semibold)).foregroundStyle(Palette.okText)
                    ForEach(Array(v.reassuring.enumerated()), id: \.offset) { _, line in Text("• " + line).font(.caption).foregroundStyle(Palette.ink) }
                }
            }
            if !v.otherFacts.isEmpty {
                VStack(alignment: .leading, spacing: 3) {
                    Text("Other facts").font(.caption.weight(.semibold)).foregroundStyle(Palette.inkSoft)
                    ForEach(Array(v.otherFacts.enumerated()), id: \.offset) { _, line in Text("• " + line).font(.caption).foregroundStyle(Palette.inkSoft) }
                }
            }
            if v.reasons.contains(where: { $0.code == "url_shortener" }) {
                Text("Loupe does not open short links to see where they lead (that would visit the link). Ask the sender for the full address.")
                    .font(.caption).foregroundStyle(Palette.inkSoft)
            }
            Divider().overlay(Palette.hairline)
            DisclosureGroup {
                VStack(alignment: .leading, spacing: 3) {
                    ForEach(Array(v.onDevice.enumerated()), id: \.offset) { _, line in
                        Text("• " + line).font(.caption).foregroundStyle(Palette.inkSoft).frame(maxWidth: .infinity, alignment: .leading)
                    }
                    if !v.disclosure.onlineNotes.isEmpty {
                        Text("Online").font(.caption.weight(.semibold)).foregroundStyle(Palette.cyan).padding(.top, 4)
                        ForEach(Array(v.disclosure.onlineNotes.enumerated()), id: \.offset) { _, line in
                            Text("• " + line).font(.caption).foregroundStyle(Palette.inkSoft).frame(maxWidth: .infinity, alignment: .leading)
                        }
                    } else {
                        Text("Online checks: off (Me → Online phishing checks).").font(.caption).foregroundStyle(Palette.inkSoft).padding(.top, 4)
                    }
                }
                .padding(.top, 4)
            } label: {
                Text("What Loupe checked on this iPhone").font(.caption.weight(.semibold)).foregroundStyle(Palette.ink)
            }
            .accessibilityIdentifier("protect.verdict.checked")
            HStack(alignment: .top, spacing: 6) {
                Image(systemName: v.disclosure.sent.isEmpty ? "lock.shield" : "arrow.up.forward.circle")
                    .font(.caption).foregroundStyle(v.disclosure.sent.isEmpty ? Palette.okText : Palette.cyan)
                Text(v.privacyLine).font(.caption).foregroundStyle(Palette.inkSoft)
                    .accessibilityIdentifier("protect.verdict.privacy")
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(v.level.soft.opacity(0.6), in: RoundedRectangle(cornerRadius: Effects.radius, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: Effects.radius, style: .continuous).stroke(v.level.color.opacity(0.45)))
    }
}
