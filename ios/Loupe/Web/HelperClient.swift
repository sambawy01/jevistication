import Foundation

/// The only network surface in the app: loupe-web-helper. Fetch-only; nothing else goes online.
protocol FlightsHelper {
    func health() async throws -> HealthResponse
    func search(_ request: SearchRequest, key: String) async throws -> SearchResponse
}

struct HelperEndpoint {
    static let base = LoupeHelper.base
    static let timeout: TimeInterval = 20

    static func healthRequest(base: URL = base) -> URLRequest {
        var r = URLRequest(url: base.appendingPathComponent("v1/health"), timeoutInterval: timeout)
        r.httpMethod = "GET"
        return r
    }

    static func searchRequest(_ body: SearchRequest, key: String, installId: String, base: URL = base) throws -> URLRequest {
        var r = URLRequest(url: base.appendingPathComponent("v1/flights/search"), timeoutInterval: timeout)
        r.httpMethod = "POST"
        r.setValue("Bearer \(key)", forHTTPHeaderField: "Authorization")
        r.setValue(installId, forHTTPHeaderField: "X-Loupe-Install")
        r.setValue("application/json", forHTTPHeaderField: "Content-Type")
        r.setValue("application/json", forHTTPHeaderField: "Accept")
        let enc = JSONEncoder()
        enc.outputFormatting = [.sortedKeys]
        r.httpBody = try enc.encode(body)
        return r
    }
}

final class LiveFlightsHelper: FlightsHelper {
    private let session: URLSession
    private let installId: String

    init(installId: String, session: URLSession? = nil) {
        self.installId = installId
        if let session { self.session = session } else {
            let cfg = URLSessionConfiguration.ephemeral     // no cookies, no URL cache on disk
            cfg.timeoutIntervalForRequest = HelperEndpoint.timeout
            cfg.timeoutIntervalForResource = HelperEndpoint.timeout
            cfg.waitsForConnectivity = false
            cfg.urlCache = nil
            self.session = URLSession(configuration: cfg)
        }
    }

    func health() async throws -> HealthResponse {
        try await send(HelperEndpoint.healthRequest(), as: HealthResponse.self)
    }

    func search(_ request: SearchRequest, key: String) async throws -> SearchResponse {
        let resp = try await send(try HelperEndpoint.searchRequest(request, key: key, installId: installId), as: SearchResponse.self)
        if resp.offers.isEmpty { throw HelperError.noResults }
        return resp
    }

    private func send<T: Decodable>(_ req: URLRequest, as: T.Type) async throws -> T {
        let data: Data, response: URLResponse
        do { (data, response) = try await session.data(for: req) }
        catch let e as URLError { throw HelperError.from(urlError: e) }
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else { throw HelperError.from(status: status, data: data) }
        do { return try JSONDecoder().decode(T.self, from: data) }
        catch { throw HelperError.unexpected("The helper sent a reply this version cannot read.") }
    }
}

#if DEBUG
/// DEBUG-only: serves bundled fixture offers. Makes no network calls.
final class FixtureFlightsHelper: FlightsHelper {
    func health() async throws -> HealthResponse { HealthResponse(ok: true, version: "fixture") }
    func search(_ request: SearchRequest, key: String) async throws -> SearchResponse {
        guard let url = Bundle.main.url(forResource: "flights-lis-lhr", withExtension: "json") else {
            throw HelperError.unexpected("Fixture missing")
        }
        return try JSONDecoder().decode(SearchResponse.self, from: Data(contentsOf: url))
    }
}
#endif
