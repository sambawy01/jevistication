import Foundation
import LoupeKit

// The web helper's client (split out of OnlineShared.swift on 2026-09-26): the app and Loupe for Safari
// compile it; the Loupe keyboard never does (it has no network code at all, LoupeTests/ClipboardTests
// and the keyboard's link-time check both verify that).

/// The web helper's address, shared by the app (`HelperEndpoint`) and the extensions.
enum LoupeHelper {
    static let base = URL(string: "https://loupe-web-helper-production.up.railway.app")!
}

enum OnlineCheckError: Error, Equatable {
    /// The helper answered 404: the route is not deployed yet.
    case notAvailableYet
    /// 429 `PROVIDER_RATE_LIMITED` (or any 429): back off.
    case rateLimited
    case offline
    case badKey
    case unexpected(String)

    static func from(status: Int, data: Data) -> OnlineCheckError {
        switch status {
        case 404: return .notAvailableYet
        case 429: return .rateLimited
        case 400, 401, 403: return .badKey
        default:
            let code = (try? JSONSerialization.jsonObject(with: data) as? [String: Any]).flatMap { ($0?["error"] as? [String: Any])?["code"] as? String }
            return .unexpected(code ?? "HTTP \(status)")
        }
    }
}

// MARK: - Domain facts (loupe-web-helper)

final class DomainFactsClient {
    static let timeout: TimeInterval = 15
    let base: URL
    private let session: URLSession

    init(base: URL = LoupeHelper.base, session: URLSession) {
        self.base = base
        self.session = session
    }

    /// The request for one domain: the JSON body `{"domain": d}` and nothing about the user. The
    /// helper's rate limiter wants an `X-Loupe-Install`; a fresh random UUID per request means two
    /// lookups cannot be linked to one phone.
    static func request(domain: String, base: URL = LoupeHelper.base, timeout: TimeInterval = DomainFactsClient.timeout) -> URLRequest {
        var r = URLRequest(url: base.appendingPathComponent("v1/domain-facts"), timeoutInterval: timeout)
        r.httpMethod = "POST"
        r.setValue("application/json", forHTTPHeaderField: "Content-Type")
        r.setValue("application/json", forHTTPHeaderField: "Accept")
        r.setValue(UUID().uuidString, forHTTPHeaderField: "X-Loupe-Install")
        r.httpBody = try? JSONSerialization.data(withJSONObject: ["domain": domain], options: [.sortedKeys])
        return r
    }

    /// [timeout]: the app waits 15 s; Loupe for Safari waits less, so a page is never held up long.
    func facts(for domain: String, timeout: TimeInterval = DomainFactsClient.timeout) async throws -> DomainFacts {
        let data: Data, response: URLResponse
        do { (data, response) = try await session.data(for: Self.request(domain: domain, base: base, timeout: timeout)) }
        catch { throw OnlineCheckError.offline }
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard status == 200 else { throw OnlineCheckError.from(status: status, data: data) }
        do { return try OnlineSignals.shared.parseDomainFacts(json: String(decoding: data, as: UTF8.self), domain: domain) }
        catch { throw OnlineCheckError.unexpected("The helper sent a reply this version cannot read.") }
    }
}

