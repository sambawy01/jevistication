import Foundation

/// The opt-in writing assistant (epic #7 child 16): the user's own OpenAI-compatible provider.
/// Ported from Loupe Station (the owner's repo `~/laya-studio` at `ea7697a`): `llm/providers.py`
/// (provider types, `check_base_url`) and `llm/settings.py` (off until configured). Not ported: the
/// server's key store, public URL and log redaction — the key is in the iOS Keychain here.
///
/// **Off by default.** With `enabled == false`, or no model/URL, or no key for a hosted provider,
/// `AssistService.isReady` is false and no request is ever built.
enum AssistProviderKind: String, Codable, CaseIterable, Identifiable {
    /// Any HTTPS endpoint speaking `POST {base}/chat/completions` (DeepSeek, OpenAI, OpenRouter…). Key required.
    case openAICompatible
    /// Ollama on the user's own network (`http://192.168.1.20:11434/v1`). No key.
    case ollama

    var id: String { rawValue }
    var title: String { self == .ollama ? "Ollama on your network" : "OpenAI-compatible (HTTPS)" }
    var needsKey: Bool { self == .openAICompatible }
}

struct AssistConfig: Codable, Equatable {
    var enabled = false
    var kind: AssistProviderKind = .openAICompatible
    var baseURL = ""
    var model = ""
    /// What the Online label names ("DeepSeek"); defaults to the host.
    var name = ""
    /// Station's reply settings, trimmed: tone and an optional signature.
    var tone: String = "friendly"
    var signature = ""

    var providerName: String {
        let n = name.trimmingCharacters(in: .whitespacesAndNewlines)
        if !n.isEmpty { return String(n.prefix(60)) }
        return URL(string: baseURL)?.host ?? "your provider"
    }

    /// Why this cannot be used yet, or nil when it is complete (key checked separately).
    var problem: String? {
        if model.trimmingCharacters(in: .whitespaces).isEmpty { return "Set a model name." }
        do { _ = try AssistConfig.checkBaseURL(baseURL, kind: kind) } catch { return (error as? AssistError)?.message ?? "Bad URL." }
        return nil
    }

    /// Station's `check_base_url`, tightened for the phone: a hosted provider must be HTTPS; Ollama
    /// may be plain HTTP only to a local-network host (private IPv4, `.local`, localhost).
    static func checkBaseURL(_ raw: String, kind: AssistProviderKind) throws -> URL {
        var s = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        while s.hasSuffix("/") { s.removeLast() }
        guard !s.isEmpty else { throw AssistError.config("Set the base URL.") }
        guard s.count <= 300, !s.unicodeScalars.contains(where: { $0.value < 33 }) else {
            throw AssistError.config("The base URL must not contain spaces or control characters.")
        }
        guard let c = URLComponents(string: s), let scheme = c.scheme?.lowercased(), let host = c.host, !host.isEmpty else {
            throw AssistError.config("The base URL is not a valid URL.")
        }
        if c.user != nil || c.password != nil { throw AssistError.config("The base URL must not contain a user name or password; use the key field.") }
        if c.query != nil || c.fragment != nil { throw AssistError.config("The base URL must not contain ? or #.") }
        switch kind {
        case .openAICompatible:
            guard scheme == "https" else { throw AssistError.config("A hosted provider must use https://.") }
        case .ollama:
            guard scheme == "http" || scheme == "https" else { throw AssistError.config("Use http:// or https://.") }
            guard isLocalNetwork(host) else { throw AssistError.config("Ollama must be on your own network (a 10.x, 172.16–31.x or 192.168.x address, or a .local name).") }
        }
        guard let url = URL(string: s) else { throw AssistError.config("The base URL is not a valid URL.") }
        return url
    }

    static func isLocalNetwork(_ host: String) -> Bool {
        let h = host.lowercased()
        if h == "localhost" || h.hasSuffix(".local") || h.hasSuffix(".localhost") { return true }
        let parts = h.split(separator: ".").compactMap { Int($0) }
        guard parts.count == 4, parts.allSatisfy({ (0...255).contains($0) }) else { return false }
        switch (parts[0], parts[1]) {
        case (10, _), (127, _), (192, 168), (169, 254): return true
        case (172, let b) where (16...31).contains(b): return true
        default: return false
        }
    }
}

/// Where the config lives: UserDefaults (no secret in it); the key is in the Keychain.
final class AssistSettingsStore {
    private let defaults: UserDefaults
    private let key = "assist.config.v1"
    init(defaults: UserDefaults = .standard) { self.defaults = defaults }

    func load() -> AssistConfig {
        guard let d = defaults.data(forKey: key), let c = try? JSONDecoder().decode(AssistConfig.self, from: d) else { return AssistConfig() }
        return c
    }

    func save(_ c: AssistConfig) {
        if let d = try? JSONEncoder().encode(c) { defaults.set(d, forKey: key) }
    }

    func clear() { defaults.removeObject(forKey: key) }
}
