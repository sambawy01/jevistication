import LoupeKit
import MessageUI
import SwiftUI
import UIKit

/// Me → Writing assistant. Port of Station's `static/js/llm-settings.js` (provider, base URL,
/// model, key) for one provider. Off by default; off means no request is made.
struct AssistSettingsView: View {
    @ObservedObject var assist: AssistService
    @State private var draft = AssistConfig()
    @State private var keyText = ""
    @State private var message: String?

    var body: some View {
        Form {
            Section {
                Toggle("Use a writing assistant", isOn: $draft.enabled)
                    .accessibilityIdentifier("assist.enabled")
                Text(assist.statusLine).font(.footnote).foregroundStyle(Palette.inkSoft)
                    .accessibilityIdentifier("assist.status")
            } footer: {
                Text("Off by default. Off, nothing is sent anywhere and Loupe stays fully offline. On, it can draft a reply to an email or give a second opinion on a judgment — each time you see exactly what will be sent, to whom, and confirm first. Judgments never depend on it.")
            }
            Section("Provider (your own)") {
                Picker("Type", selection: $draft.kind) {
                    ForEach(AssistProviderKind.allCases) { Text($0.title).tag($0) }
                }
                TextField(draft.kind == .ollama ? "http://192.168.1.20:11434/v1" : "https://api.deepseek.com", text: $draft.baseURL)
                    .keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                    .accessibilityIdentifier("assist.baseURL")
                TextField("Model (e.g. deepseek-chat)", text: $draft.model)
                    .textInputAutocapitalization(.never).autocorrectionDisabled()
                    .accessibilityIdentifier("assist.model")
                TextField("Name shown on the Online label (optional)", text: $draft.name)
                if draft.kind.needsKey {
                    SecureField(assist.hasKey ? "Key saved — paste to replace" : "API key", text: $keyText)
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                        .accessibilityIdentifier("assist.key")
                }
            }
            Section("Replies") {
                Picker("Tone", selection: $draft.tone) {
                    Text("Friendly").tag("friendly"); Text("Formal").tag("formal"); Text("Brief").tag("brief")
                }
                TextField("Signature (optional)", text: $draft.signature, axis: .vertical)
            }
            Section {
                Button("Save") { save() }.accessibilityIdentifier("assist.save")
                if let message { Text(message).font(.footnote).foregroundStyle(Palette.inkSoft) }
                Button("Turn off and remove key", role: .destructive) {
                    assist.removeAll(); draft = assist.config; keyText = ""; message = "Removed."
                }
            } footer: {
                Text("The key stays in this iPhone's Keychain (this device only) and is sent only to the provider above, with each request. Loupe's makers never see it or your mail. A hosted provider must use HTTPS; Ollama must be on your own network.")
            }
        }
        .scrollContentBackground(.hidden)
        .background(Palette.ground.ignoresSafeArea())
        .navigationTitle("Writing assistant")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { draft = assist.config }
    }

    private func save() {
        do {
            if !keyText.isEmpty { try assist.setKey(keyText); keyText = "" }
            assist.update(draft)
            message = draft.enabled ? (draft.problem ?? "Saved.") : "Saved. The assistant is off."
        } catch {
            message = (error as? AssistError)?.message ?? error.localizedDescription
        }
    }
}

/// The Online label every assistant call carries.
struct OnlineBadge: View {
    let provider: String
    var body: some View {
        Pill(text: "ONLINE · \(provider)", color: Palette.warnText, symbol: "network")
            .accessibilityIdentifier("assist.online")
    }
}

/// "Exactly what will be sent": the preview the user confirms before the first send.
struct AssistPreview: View {
    let request: AssistRequest
    let what: String
    let send: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            OnlineBadge(provider: request.provider)
            Text("\(request.feature.rawValue): this goes to \(request.provider) (\(request.host), model \(request.model)) and nowhere else. \(what) Nothing else from your phone is sent.")
                .font(.caption).foregroundStyle(Palette.ink)
            Caption(text: "Exactly what will be sent")
            ScrollView {
                Text(request.preview).font(Typeface.mono(11)).foregroundStyle(Palette.inkSoft)
                    .frame(maxWidth: .infinity, alignment: .leading).textSelection(.enabled)
                    .accessibilityIdentifier("assist.preview")
            }
            .frame(maxHeight: 260)
            .padding(8)
            .background(Palette.track, in: RoundedRectangle(cornerRadius: 8))
            Button("Send to \(request.provider)", action: send)
                .buttonStyle(.borderedProminent)
                .accessibilityIdentifier("assist.send")
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .card()
    }
}

/// Mail triage → Draft a reply. Preview → confirm → Draft (in the Review queue) → Approve →
/// Copy / Open in Mail (the user taps Send in Mail). Port of Station's `assist.js` reply flow.
struct ReplyDraftSheet: View {
    let item: SourceItem
    @ObservedObject var assist: AssistService
    @ObservedObject var review: ReviewService
    @Environment(\.dismiss) private var dismiss
    @State private var request: AssistRequest?
    @State private var working = false
    @State private var error: String?
    @State private var draft: ReplyDraft?
    @State private var queued: ReviewItem?
    @State private var approved = false
    @State private var composing = false
    @State private var copied = false
    @State private var task: Task<Void, Never>?

    private var to: String { item.email?.fromAddress ?? "" }
    private var subject: String { item.email?.subject ?? "" }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    if let draft { draftView(draft) }
                    else if let request {
                        AssistPreview(request: request, what: "Only this email's sender, subject and its own text (\(request.sentCharacters) characters; the quoted thread is cut) and the drafting rules.") { send(request) }
                            .disabled(working)
                        if working { HStack { ProgressView(); Text("Waiting for \(request.provider)…"); Spacer(); Button("Cancel") { task?.cancel() } }.font(.caption) }
                    } else {
                        Text("The writing assistant is off. Turn it on in Me → Writing assistant.").font(.subheadline).card()
                    }
                    if let error { Text(error).font(.caption).foregroundStyle(Palette.dangerText).accessibilityIdentifier("assist.error") }
                }
                .padding(16)
            }
            .background(Palette.ground.ignoresSafeArea())
            .navigationTitle("Reply draft")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Close") { task?.cancel(); dismiss() } } }
            .onAppear { if request == nil { request = assist.replyRequest(for: item) } }
            .sheet(isPresented: $composing) {
                if let draft { MailComposeView(to: draft.to, subject: draft.subject, body: draft.body) }
            }
        }
    }

    private func send(_ r: AssistRequest) {
        working = true; error = nil
        task = Task {
            defer { working = false }
            do {
                let d = try await assist.draftReply(r, confirmed: true, itemId: item.id, to: to, originalSubject: subject)
                draft = d
                queued = review.submitDraft(d)
            } catch {
                self.error = (error as? AssistError)?.message ?? error.localizedDescription
            }
        }
    }

    @ViewBuilder private func draftView(_ d: ReplyDraft) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 6) {
                Pill(text: "DRAFT", color: Palette.blue, symbol: "pencil")
                    .accessibilityElement(children: .ignore).accessibilityLabel("Draft")
                    .accessibilityIdentifier("assist.draftLabel")
                OnlineBadge(provider: d.provider)
                Spacer()
            }
            Text("Written by \(d.provider). Loupe never sends it. It waits in Review until you approve it; then copy it or open it in Mail and send it yourself.")
                .font(.caption).foregroundStyle(Palette.inkSoft)
            if !d.to.isEmpty { Text("To: \(d.to)").font(Typeface.mono(11)).foregroundStyle(Palette.inkSoft) }
            Text(d.subject).font(.headline).foregroundStyle(Palette.ink).accessibilityIdentifier("assist.draftSubject")
            TextEditor(text: Binding(get: { draft?.body ?? "" }, set: { draft?.body = $0 }))
                .frame(minHeight: 180).font(.body)
                .accessibilityIdentifier("assist.draftBody")
                .disabled(approved)
            ForEach(d.needsInfo, id: \.self) { Text("• Check before sending: \($0)").font(.caption).foregroundStyle(Palette.warnText) }
            if !d.notes.isEmpty { Text("Note: \(d.notes)").font(.caption).foregroundStyle(Palette.inkSoft) }
            Text("Not checked by Laya: the phone does not run Station's injection guard or brand gate on drafts. Read it before you send it.")
                .font(.caption2).foregroundStyle(Palette.inkSoft)
            if approved {
                HStack {
                    Button(copied ? "Copied" : "Copy") {
                        UIPasteboard.general.string = (draft?.subject ?? "") + "\n\n" + (draft?.body ?? "")
                        copied = true
                    }
                    .buttonStyle(.bordered).accessibilityIdentifier("assist.copy")
                    Button("Open in Mail") { composing = true }
                        .buttonStyle(.borderedProminent).accessibilityIdentifier("assist.openMail")
                        .disabled(!MFMailComposeViewController.canSendMail())
                }
                if !MFMailComposeViewController.canSendMail() {
                    Text("Mail is not set up on this iPhone: copy the draft instead.").font(.caption2).foregroundStyle(Palette.inkSoft)
                }
            } else {
                HStack {
                    Button("Approve draft") { Task { await approve() } }
                        .buttonStyle(.borderedProminent).accessibilityIdentifier("assist.approve")
                    Text(queued == nil ? "" : "Waiting in Review").font(.caption).foregroundStyle(Palette.inkSoft)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .card()
    }

    private func approve() async {
        guard let d = draft else { return }
        if let q = queued {
            // Station's approve takes the whole edited proposal, not a patch.
            var edited: [String: String]?
            if d.body != (q.proposal["body"] ?? "") { edited = q.proposal; edited?["body"] = String(d.body.prefix(10_000)) }
            _ = await review.approve(q, edited: edited)
        }
        approved = true
    }
}

/// MFMailComposeViewController with the draft filled in. The user taps Send (or Cancel) in Mail.
struct MailComposeView: UIViewControllerRepresentable {
    let to: String
    let subject: String
    let body: String
    @Environment(\.dismiss) private var dismiss

    func makeUIViewController(context: Context) -> MFMailComposeViewController {
        let vc = MFMailComposeViewController()
        vc.mailComposeDelegate = context.coordinator
        if !to.isEmpty { vc.setToRecipients([to]) }
        vc.setSubject(subject)
        vc.setMessageBody(body, isHTML: false)
        return vc
    }

    func updateUIViewController(_ vc: MFMailComposeViewController, context: Context) {}
    func makeCoordinator() -> Coordinator { Coordinator { dismiss() } }

    final class Coordinator: NSObject, MFMailComposeViewControllerDelegate {
        let done: () -> Void
        init(done: @escaping () -> Void) { self.done = done }
        func mailComposeController(_ c: MFMailComposeViewController, didFinishWith result: MFMailComposeResult, error: Error?) { done() }
    }
}

/// Beside Laya's answer on a judgment result: "Second opinion: agrees / disagrees — reason".
/// Display only; it never changes the decision, the ledger, calibration or the queue.
struct SecondOpinionSection: View {
    let judgment: UserJudgment
    let result: ResultRow
    @ObservedObject var assist: AssistService
    @State private var request: AssistRequest?
    @State private var working = false
    @State private var error: String?

    private var input: AssistService.OpinionInput? {
        guard let item = result.item, !result.unusable else { return nil }
        let labels = result.masses(judgment: judgment).compactMap { $0.first as String? }
        return .init(judgmentId: judgment.id, itemId: result.itemId, question: judgment.question, options: labels,
                     layaAnswer: result.topLabel, layaP: result.topMass, text: item.text)
    }

    var body: some View {
        if assist.isReady, let i = input {
            VStack(alignment: .leading, spacing: 8) {
                Caption(text: "Second opinion")
                if let o = assist.opinions[i.key] {
                    OnlineBadge(provider: o.provider)
                    Text("Second opinion: \(o.agrees ? "agrees" : "disagrees") — \(judgment.shown(o.answer))\(o.reason.isEmpty ? "" : " — \(o.reason)")")
                        .font(.subheadline).foregroundStyle(o.agrees ? Palette.ink : Palette.warnText)
                        .accessibilityIdentifier("assist.opinion")
                    Text("Shown beside Laya's answer only. It changes nothing: not the answer, the ledger, calibration or the queue.")
                        .font(.caption2).foregroundStyle(Palette.inkSoft)
                } else if let r = request {
                    AssistPreview(request: r, what: "Only this item's text (\(r.sentCharacters) characters), the question, its answers and Laya's answer.") { ask(r, i) }
                        .disabled(working)
                    if working { ProgressView() }
                } else {
                    Button("Ask \(assist.config.providerName) for a second opinion (Online)") { request = assist.opinionRequest(i) }
                        .font(.caption.weight(.semibold))
                        .accessibilityIdentifier("assist.secondOpinion")
                }
                if let error { Text(error).font(.caption).foregroundStyle(Palette.dangerText) }
            }
            .frame(maxWidth: .infinity, alignment: .leading).card()
        }
    }

    private func ask(_ r: AssistRequest, _ i: AssistService.OpinionInput) {
        working = true; error = nil
        Task {
            defer { working = false }
            do { try await assist.secondOpinion(r, confirmed: true, input: i) }
            catch { self.error = (error as? AssistError)?.message ?? error.localizedDescription }
        }
    }
}
