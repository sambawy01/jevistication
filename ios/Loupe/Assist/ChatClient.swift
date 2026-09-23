import Foundation

/// A failed assistant call. Messages are safe to show: the key is never in them (see `redact`).
/// Codes follow Station's `LLMError` (`llm/client.py`).
enum AssistError: Error, Equatable, LocalizedError {
    case config(String)
    case notConfirmed
    case auth(Int, String)
    case notFound(String)
    case rateLimited(String)
    case timeout
    case unreachable(String)
    case http(Int, String)
    case badResponse(String)
    case invalidJSON(String)
    case cancelled

    var message: String {
        switch self {
        case .config(let m): return m
        case .notConfirmed: return "Nothing was sent: confirm what will be sent first."
        case .auth(let s, let d): return "The provider refused the key (HTTP \(s))" + (d.isEmpty ? "." : ": \(d)")
        case .notFound(let d): return "Not found at the provider (HTTP 404): check the base URL and the model name" + (d.isEmpty ? "." : ": \(d)")
        case .rateLimited(let d): return "The provider is rate limiting requests (HTTP 429)" + (d.isEmpty ? "." : ": \(d)")
        case .timeout: return "The provider did not answer in time."
        case .unreachable(let d): return "Cannot reach the provider (\(d))."
        case .http(let s, let d): return "The provider answered HTTP \(s)" + (d.isEmpty ? "." : ": \(d)")
        case .badResponse(let m): return m
        case .invalidJSON(let m): return "The model's answer did not match the expected format after one repair attempt: \(m)"
        case .cancelled: return "Cancelled."
        }
    }

    var errorDescription: String? { message }
}

struct ChatMessage: Codable, Equatable {
    let role: String
    let content: String
}

struct ChatResult: Equatable {
    let content: String
    let model: String
    let object: [String: Any]
    let repaired: Bool
    let tokensIn: Int
    let tokensOut: Int

    static func == (a: ChatResult, b: ChatResult) -> Bool { a.content == b.content && a.model == b.model && a.repaired == b.repaired }
}

/// OpenAI-compatible chat completions over URLSession: `POST {base}/chat/completions`, JSON mode,
/// one repair turn for an invalid answer, retry on 429/5xx/connection errors. Port of Station's
/// `llm/client.py` (no vendor SDK there either). Nothing it returns is acted on here.
final class ChatClient {
    static let retryStatus: Set<Int> = [429, 500, 502, 503, 504]
    static let maxResponseBytes = 4_000_000
    static let maxDetailChars = 300

    let baseURL: URL
    private let apiKey: String?
    private let session: URLSession
    let timeout: TimeInterval
    let maxRetries: Int
    private let sleep: (Double) async -> Void

    init(baseURL: URL, apiKey: String?, session: URLSession, timeout: TimeInterval = 60, maxRetries: Int = 1,
         sleep: @escaping (Double) async -> Void = { s in try? await Task.sleep(nanoseconds: UInt64(s * 1e9)) }) {
        self.baseURL = baseURL
        self.apiKey = (apiKey?.isEmpty == false) ? apiKey : nil
        self.session = session
        self.timeout = timeout
        self.maxRetries = max(0, maxRetries)
        self.sleep = sleep
    }

    var endpoint: URL { baseURL.appendingPathComponent("chat/completions") }

    /// Station's `schema_instruction`: DeepSeek wants the word "json" in the prompt.
    static func schemaInstruction(_ schema: String) -> String {
        "Reply with one JSON object only: no prose, no code fence. It must match this JSON schema:\n" + schema
    }

    static func requestBody(messages: [ChatMessage], model: String, maxTokens: Int, json: Bool) -> Data {
        var body: [String: Any] = ["model": model, "temperature": 0.2, "stream": false, "max_tokens": maxTokens,
                                   "messages": messages.map { ["role": $0.role, "content": $0.content] }]
        if json { body["response_format"] = ["type": "json_object"] }
        return (try? JSONSerialization.data(withJSONObject: body, options: [.sortedKeys])) ?? Data()
    }

    func request(_ messages: [ChatMessage], model: String, maxTokens: Int) -> URLRequest {
        var r = URLRequest(url: endpoint, cachePolicy: .reloadIgnoringLocalAndRemoteCacheData, timeoutInterval: timeout)
        r.httpMethod = "POST"
        r.setValue("application/json", forHTTPHeaderField: "Content-Type")
        r.setValue("application/json", forHTTPHeaderField: "Accept")
        r.setValue("Loupe", forHTTPHeaderField: "User-Agent")
        if let apiKey { r.setValue("Bearer " + apiKey, forHTTPHeaderField: "Authorization") }
        r.httpBody = Self.requestBody(messages: messages, model: model, maxTokens: maxTokens, json: true)
        return r
    }

    /// One JSON chat call. `schema` is sent as an instruction; `validate` checks the parsed object
    /// (problems → one repair turn, then `.invalidJSON`).
    func chat(_ messages: [ChatMessage], model: String, schema: String, maxTokens: Int,
              validate: ([String: Any]) -> [String]) async throws -> ChatResult {
        guard !model.isEmpty else { throw AssistError.config("No model is set.") }
        var msgs = [ChatMessage(role: "system", content: Self.schemaInstruction(schema))] + messages
        var (content, used, tin, tout) = try await complete(msgs, model: model, maxTokens: maxTokens)
        var (obj, problems) = Self.parseChecked(content, validate)
        var repaired = false
        if !problems.isEmpty {
            msgs += [ChatMessage(role: "assistant", content: String(content.prefix(8000))),
                     ChatMessage(role: "user", content: "That reply was invalid: \(problems.prefix(8).joined(separator: "; ")). Reply again with only the corrected JSON object.")]
            let second = try await complete(msgs, model: model, maxTokens: maxTokens)
            content = second.0; used = second.1; tin += second.2; tout += second.3
            (obj, problems) = Self.parseChecked(content, validate)
            repaired = true
            if !problems.isEmpty { throw AssistError.invalidJSON(problems.prefix(5).joined(separator: "; ")) }
        }
        return ChatResult(content: content, model: used, object: obj ?? [:], repaired: repaired, tokensIn: tin, tokensOut: tout)
    }

    private func complete(_ msgs: [ChatMessage], model: String, maxTokens: Int) async throws -> (String, String, Int, Int) {
        let data = try await send(request(msgs, model: model, maxTokens: maxTokens))
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw AssistError.badResponse("The provider did not answer with JSON (is the base URL an OpenAI-compatible API?).")
        }
        guard let choices = root["choices"] as? [[String: Any]], let first = choices.first,
              let message = first["message"] as? [String: Any] else {
            throw AssistError.badResponse("The provider's answer has no choices[0].message.")
        }
        let content = message["content"] as? String ?? ""
        let usage = root["usage"] as? [String: Any] ?? [:]
        return (content, root["model"] as? String ?? model, usage["prompt_tokens"] as? Int ?? 0, usage["completion_tokens"] as? Int ?? 0)
    }

    private func send(_ req: URLRequest) async throws -> Data {
        var attempt = 0
        while true {
            var retryAfter: Double?
            do {
                try Task.checkCancellation()
                let (data, resp) = try await session.data(for: req)
                let status = (resp as? HTTPURLResponse)?.statusCode ?? 0
                if Self.retryStatus.contains(status), attempt < maxRetries {
                    retryAfter = ((resp as? HTTPURLResponse)?.value(forHTTPHeaderField: "Retry-After")).flatMap(Double.init)
                } else if status >= 400 || status < 200 {
                    throw httpError(status, data)
                } else {
                    guard data.count <= Self.maxResponseBytes else { throw AssistError.badResponse("The provider's answer is too large.") }
                    return data
                }
            } catch let e as AssistError {
                throw e
            } catch is CancellationError {
                throw AssistError.cancelled
            } catch let e as URLError {
                if e.code == .cancelled { throw AssistError.cancelled }
                if attempt >= maxRetries {
                    if e.code == .timedOut { throw AssistError.timeout }
                    throw AssistError.unreachable(redact("\(baseURL.host ?? "?"): \(e.code.rawValue)"))
                }
            }
            await sleep(min(retryAfter ?? 0.6 * pow(2, Double(attempt)), 20))
            attempt += 1
        }
    }

    private func httpError(_ status: Int, _ data: Data) -> AssistError {
        var text = String(decoding: data.prefix(20_000), as: UTF8.self)
        if let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
            if let err = obj["error"] as? [String: Any], let m = err["message"] as? String { text = m }
            else if let m = obj["error"] as? String { text = m }
            else if let m = obj["message"] as? String { text = m }
        }
        let d = String(redact(text).split(whereSeparator: \.isWhitespace).joined(separator: " ").prefix(Self.maxDetailChars))
        switch status {
        case 401, 403: return .auth(status, d)
        case 404: return .notFound(d)
        case 429: return .rateLimited(d)
        default: return .http(status, d)
        }
    }

    /// Station's `redact`: the key verbatim, bearer tokens and key-shaped strings.
    func redact(_ text: String) -> String { Self.redact(text, secrets: [apiKey].compactMap { $0 }) }

    static func redact(_ text: String, secrets: [String]) -> String {
        var out = text
        for s in secrets where s.count >= 4 { out = out.replacingOccurrences(of: s, with: "[redacted]") }
        out = out.replacingOccurrences(of: #"(?i)(bearer\s+)[A-Za-z0-9._~+/=-]{6,}"#, with: "$1[redacted]", options: .regularExpression)
        return out.replacingOccurrences(of: #"\b(sk|ak|key)[-_][A-Za-z0-9._-]{12,}"#, with: "[redacted]", options: .regularExpression)
    }

    /// Station's `extract_json`: tolerate a ```json fence or prose around one object.
    static func extractJSON(_ text: String) -> [String: Any]? {
        var s = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if s.hasPrefix("```") {
            s = String(s.drop(while: { $0 != "\n" }))
            if s.hasSuffix("```") { s = String(s.dropLast(3)) }
        }
        if let o = try? JSONSerialization.jsonObject(with: Data(s.utf8)) as? [String: Any] { return o }
        guard let a = s.firstIndex(of: "{"), let b = s.lastIndex(of: "}"), a < b else { return nil }
        return try? JSONSerialization.jsonObject(with: Data(s[a...b].utf8)) as? [String: Any]
    }

    static func parseChecked(_ content: String, _ validate: ([String: Any]) -> [String]) -> ([String: Any]?, [String]) {
        guard let o = extractJSON(content) else { return (nil, ["it is not valid JSON"]) }
        return (o, validate(o))
    }
}
