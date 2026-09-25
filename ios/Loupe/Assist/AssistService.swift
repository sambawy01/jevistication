import Foundation
import LoupeKit

/// One call the user is about to make: exactly the text that will be sent, where, and to whom.
/// The UI shows `preview` and sends only after the user confirms it (`AssistService.send…(confirmed:)`).
struct AssistRequest: Identifiable, Equatable {
    enum Feature: String { case emailReply = "Reply draft", secondOpinion = "Second opinion" }
    let id = UUID()
    let feature: Feature
    let provider: String
    let host: String
    let model: String
    let messages: [ChatMessage]
    /// What only the target contributes (for the "N characters of this email" line).
    let sentCharacters: Int

    /// The exact text of every message, in order, as it goes into the request body.
    var preview: String { messages.map { "[\($0.role)]\n\($0.content)" }.joined(separator: "\n\n") }

    static func == (a: AssistRequest, b: AssistRequest) -> Bool { a.id == b.id }
}

/// The opt-in writing assistant (epic #7 child 16): reply drafts and a second opinion through the
/// user's own OpenAI-compatible provider (BYOK) or their Ollama on the local network. Ported from
/// Loupe Station's `workflows/{email_reply,second_opinion}.py`, `llm/*` and `static/js/assist.js`.
///
/// The rules (docs/PRODUCT.md §4 and §4a):
/// - **Off by default**; unconfigured, `isReady` is false, no request is built and nothing goes online.
/// - Every call is labelled Online with the provider's name and previewed; `send…` refuses without `confirmed`.
/// - Drafts are drafts: they wait in the Review queue; Loupe never sends them. The user copies them or
///   opens them in Mail and taps Send there.
/// - Judgments never depend on it: a second opinion is shown beside Laya's answer and written nowhere.
@MainActor
final class AssistService: ObservableObject {
    static let shared: AssistService = {
        #if DEBUG
        if LaunchOptions.current.fakeAssistant { return .fake() }
        #endif
        return AssistService(store: AssistSettingsStore(),
                             key: LaunchOptions.current.ephemeralKey ? MemoryKeyStore() : KeychainStore(service: "com.loupe-ai.ios.assistant", account: "provider-api-key"),
                             session: AssistService.ephemeralSession())
    }()

    @Published private(set) var config: AssistConfig
    @Published private(set) var hasKey: Bool
    /// Second opinions shown this session, by "judgment id|item id". Display only; never persisted.
    @Published private(set) var opinions: [String: SecondOpinion] = [:]

    private let store: AssistSettingsStore
    private let key: KeyStore
    private let session: URLSession
    private let sleep: (Double) async -> Void

    init(store: AssistSettingsStore, key: KeyStore, session: URLSession,
         sleep: @escaping (Double) async -> Void = { s in try? await Task.sleep(nanoseconds: UInt64(s * 1e9)) }) {
        self.store = store
        self.key = key
        self.session = session
        self.sleep = sleep
        config = store.load()
        hasKey = !(key.read() ?? "").isEmpty
    }

    /// No cookies, no cache, no credential store: the key goes only in the Authorization header.
    nonisolated static func ephemeralSession(protocols: [AnyClass]? = nil) -> URLSession {
        let c = URLSessionConfiguration.ephemeral
        c.urlCache = nil
        c.httpCookieStorage = nil
        c.httpShouldSetCookies = false
        c.timeoutIntervalForRequest = 60
        c.waitsForConnectivity = false
        if let protocols { c.protocolClasses = protocols }
        return URLSession(configuration: c)
    }

    // MARK: Gate

    /// On, complete, and (for a hosted provider) a key in the Keychain. Nothing is sent otherwise.
    var isReady: Bool { config.enabled && config.problem == nil && (!config.kind.needsKey || hasKey) }

    var statusLine: String {
        if !config.enabled { return "Off" }
        if let p = config.problem { return "Needs setup: \(p)" }
        if config.kind.needsKey && !hasKey { return "Needs setup: add your key." }
        return "On · \(config.providerName) · Online"
    }

    func update(_ c: AssistConfig) {
        config = c
        store.save(c)
    }

    func setKey(_ raw: String) throws {
        let k = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !k.isEmpty, !k.contains(where: \.isWhitespace), k.count <= 500 else { throw AssistError.config("That does not look like an API key.") }
        try key.save(k)
        hasKey = true
    }

    /// Turns it off and forgets everything: the key, the settings and this session's opinions.
    func removeAll() {
        try? key.delete()
        hasKey = false
        store.clear()
        config = AssistConfig()
        opinions = [:]
    }

    private func client() throws -> ChatClient {
        guard isReady else { throw AssistError.config("The writing assistant is off.") }
        let url = try AssistConfig.checkBaseURL(config.baseURL, kind: config.kind)
        return ChatClient(baseURL: url, apiKey: config.kind.needsKey ? key.read() : nil, session: session, sleep: sleep)
    }

    private func request(_ f: AssistRequest.Feature, _ messages: [ChatMessage], sent: Int) -> AssistRequest? {
        guard isReady, let url = try? AssistConfig.checkBaseURL(config.baseURL, kind: config.kind) else { return nil }
        return AssistRequest(feature: f, provider: config.providerName, host: url.host ?? "", model: config.model,
                             messages: messages, sentCharacters: sent)
    }

    // MARK: Reply drafts

    /// The request for a reply to this one email (nil when off, or not an email). Builds nothing online.
    func replyRequest(sender: String, subject: String, text: String) -> AssistRequest? {
        let m = EmailReplyWorkflow.messages(sender: sender, subject: subject, text: text, tone: config.tone, signature: config.signature)
        return request(.emailReply, m, sent: min(AssistText.ownText(text).count, EmailReplyWorkflow.maxText))
    }

    func replyRequest(for item: SourceItem) -> AssistRequest? {
        guard item.kind == .email else { return nil }
        let e = item.email
        let sender = [e?.fromName, e?.fromAddress.map { "<\($0)>" }].compactMap { $0 }.joined(separator: " ")
        return replyRequest(sender: sender, subject: e?.subject ?? "", text: item.text)
    }

    /// Sends a previewed, confirmed request and returns the draft. Nothing is saved or sent anywhere.
    func draftReply(_ req: AssistRequest, confirmed: Bool, itemId: String, to: String, originalSubject: String) async throws -> ReplyDraft {
        guard confirmed else { throw AssistError.notConfirmed }
        guard req.feature == .emailReply else { throw AssistError.config("Wrong request.") }
        let job = ActivityCenter.shared.start("llm_job", title: "act.title.drafts", view: "assist", total: 1, stage: "act.stage.asking")
        let r: ChatResult
        do {
            r = try await client().chat(req.messages, model: req.model, schema: EmailReplyWorkflow.schema,
                                        maxTokens: EmailReplyWorkflow.maxTokens, validate: EmailReplyWorkflow.validate)
        } catch { job.finish("error", "act.res.llm", ["done": 0, "errors": 1]); throw error }
        job.finish("done", "act.res.llm", ["done": 1, "errors": 0])
        return EmailReplyWorkflow.draft(from: r.object, originalSubject: originalSubject, to: to, itemId: itemId, provider: req.provider)
    }

    // MARK: Second opinion

    struct OpinionInput: Equatable {
        let judgmentId: String
        let itemId: String
        let question: String
        let options: [String]
        let layaAnswer: String
        let layaP: Double
        let text: String
        var key: String { judgmentId + "|" + itemId }
    }

    func opinionRequest(_ i: OpinionInput) -> AssistRequest? {
        guard !i.options.isEmpty else { return nil }
        let m = SecondOpinionWorkflow.messages(questionId: i.judgmentId, question: i.question, options: i.options,
                                               layaAnswer: i.layaAnswer, layaP: i.layaP, text: i.text)
        return request(.secondOpinion, m, sent: min(i.text.count, SecondOpinionWorkflow.maxText))
    }

    /// Display only: kept in memory for this screen, never written to the ledger, corrections,
    /// calibration or any queue.
    @discardableResult
    func secondOpinion(_ req: AssistRequest, confirmed: Bool, input i: OpinionInput) async throws -> SecondOpinion {
        guard confirmed else { throw AssistError.notConfirmed }
        guard req.feature == .secondOpinion else { throw AssistError.config("Wrong request.") }
        let job = ActivityCenter.shared.start("llm_job", title: "act.title.secondOpinion", view: "assist", total: 1, stage: "act.stage.asking")
        let r: ChatResult
        do {
            r = try await client().chat(req.messages, model: req.model,
                                        schema: SecondOpinionWorkflow.schema(questionId: i.judgmentId, options: i.options),
                                        maxTokens: SecondOpinionWorkflow.maxTokens,
                                        validate: { SecondOpinionWorkflow.validate($0, questionId: i.judgmentId, options: i.options) })
        } catch { job.finish("error", "act.res.llm", ["done": 0, "errors": 1]); throw error }
        job.finish("done", "act.res.llm", ["done": 1, "errors": 0])
        let o = SecondOpinionWorkflow.opinion(from: r.object, questionId: i.judgmentId, options: i.options, layaAnswer: i.layaAnswer, provider: req.provider)
        opinions[i.key] = o
        return o
    }
}

extension ReviewService {
    /// Queues a reply draft (Station's `email_reply` kind). It waits there until approved; approving
    /// records it — Loupe never sends it, saves it in a mailbox or files it.
    func submitDraft(_ d: ReplyDraft) -> ReviewItem? {
        let p = ReviewProposal(
            feature: ReviewRegistry.shared.EMAIL_REPLY, kind: "email_reply",
            title: "Reply draft: " + (d.subject.isEmpty ? "(no subject)" : d.subject),
            sourceKey: "email_reply:\(d.itemId):\(UUID().uuidString)",
            inputSummary: "Draft · written by \(d.provider) (Online). Loupe never sends it: approve, then copy it or open it in Mail.",
            proposal: ["item_id": d.itemId, "to": String(d.to.prefix(300)), "subject": String(d.subject.prefix(300)),
                       "body": String(d.body.prefix(10_000)), "provider": String(d.provider.prefix(200)),
                       "warnings": String(d.needsInfo.joined(separator: "\n").prefix(1000))],
            actionType: "none", actionParams: [:])
        return submitProposal(p)
    }
}

#if DEBUG
/// `-LoupeFakeAssistant` (UI tests, screenshots): a configured assistant whose provider is this
/// URLProtocol — no request leaves the simulator.
final class FakeAssistantProtocol: URLProtocol {
    nonisolated(unsafe) static var requests: [URLRequest] = []
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        Self.requests.append(request)
        let body = (request.httpBody ?? request.httpBodyStream.map(Self.read)) ?? Data()
        let text = String(decoding: body, as: UTF8.self)
        let content: String
        let user = ((try? JSONSerialization.jsonObject(with: body) as? [String: Any])?["messages"] as? [[String: Any]])?.last?["content"] as? String ?? ""
        if text.contains("second opinion"), let id = Self.capture(#"- id (\S+) \("#, user), let first = Self.capture(#"allowed: ([^;\n]+)"#, user) {
            content = #"{"answers":{"\#(id)":{"answer":"\#(first)","confidence":0.7,"reason":"The fake provider always picks the first option."}}}"#
        } else {
            content = #"{"subject":"Re: Your order","body":"Hello,\n\nThank you for your message. [confirm the date]\n\nBest regards","language":"English","tone":"friendly","needs_info":["Which date can you confirm?"],"notes_for_reviewer":""}"#
        }
        let root: [String: Any] = ["model": "fake-model", "choices": [["message": ["role": "assistant", "content": content], "finish_reason": "stop"]]]
        let data = try! JSONSerialization.data(withJSONObject: root)
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: ["Content-Type": "application/json"])!, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: data)
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}

    static func capture(_ pattern: String, _ s: String) -> String? {
        guard let re = try? NSRegularExpression(pattern: pattern), let m = re.firstMatch(in: s, range: NSRange(s.startIndex..., in: s)),
              let r = Range(m.range(at: 1), in: s) else { return nil }
        return String(s[r])
    }

    static func read(_ s: InputStream) -> Data {
        s.open(); defer { s.close() }
        var d = Data(); var buf = [UInt8](repeating: 0, count: 4096)
        while s.hasBytesAvailable { let n = s.read(&buf, maxLength: buf.count); if n <= 0 { break }; d.append(buf, count: n) }
        return d
    }
}

extension AssistService {
    static func fake() -> AssistService {
        let defaults = UserDefaults(suiteName: "com.loupe-ai.ios.fakeAssistant")!
        defaults.removePersistentDomain(forName: "com.loupe-ai.ios.fakeAssistant")
        let s = AssistService(store: AssistSettingsStore(defaults: defaults), key: MemoryKeyStore("sk-fake-key-for-ui-tests"),
                              session: ephemeralSession(protocols: [FakeAssistantProtocol.self]))
        s.update(AssistConfig(enabled: true, kind: .openAICompatible, baseURL: "https://assistant.invalid/v1", model: "fake-model", name: "Fake provider"))
        return s
    }
}
#endif
