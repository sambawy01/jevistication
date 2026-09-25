import Foundation

/// The helper's provider searches (`POST /v1/search/<provider>`). Fetch-only: the body is the
/// search itself and nothing else (§4a); every judgment runs on the phone.
protocol SearchHelper {
    func currency(_ q: CurrencyQuery) async throws -> CurrencyResponse
    func weather(_ q: WeatherQuery) async throws -> WeatherResponse
    func trains(_ q: TrainsQuery) async throws -> TrainsResponse
}

extension HelperEndpoint {
    static func providerRequest<B: Encodable>(_ provider: String, body: B, installId: String, base: URL = base) throws -> URLRequest {
        var r = URLRequest(url: base.appendingPathComponent("v1/search/\(provider)"), timeoutInterval: timeout)
        r.httpMethod = "POST"
        r.setValue(installId, forHTTPHeaderField: "X-Loupe-Install")
        r.setValue("application/json", forHTTPHeaderField: "Content-Type")
        r.setValue("application/json", forHTTPHeaderField: "Accept")
        let enc = JSONEncoder()
        enc.outputFormatting = [.sortedKeys]
        r.httpBody = try enc.encode(body)
        return r
    }
}

final class LiveSearchHelper: SearchHelper {
    private let session: URLSession
    private let installId: String
    private let base: URL

    init(installId: String, base: URL = HelperEndpoint.base, session: URLSession? = nil) {
        self.installId = installId
        self.base = base
        if let session { self.session = session } else {
            let cfg = URLSessionConfiguration.ephemeral     // no cookies, no URL cache on disk
            cfg.timeoutIntervalForRequest = HelperEndpoint.timeout
            cfg.timeoutIntervalForResource = HelperEndpoint.timeout
            cfg.waitsForConnectivity = false
            cfg.urlCache = nil
            self.session = URLSession(configuration: cfg)
        }
    }

    func currency(_ q: CurrencyQuery) async throws -> CurrencyResponse { try await send("currency", q) }
    func weather(_ q: WeatherQuery) async throws -> WeatherResponse { try await send("weather", q) }
    func trains(_ q: TrainsQuery) async throws -> TrainsResponse { try await send("uk-trains", q) }

    private func send<B: Encodable, T: Decodable>(_ provider: String, _ body: B) async throws -> T {
        let req = try HelperEndpoint.providerRequest(provider, body: body, installId: installId, base: base)
        let data: Data, response: URLResponse
        do { (data, response) = try await session.data(for: req) }
        catch let e as URLError { throw HelperError.from(urlError: e) }
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else { throw HelperError.from(status: status, data: data) }
        return try SearchDecoding.decode(T.self, from: data)
    }
}

enum SearchDecoding {
    /// Decodes a 200 body; a body this version cannot read is an error, never a guess.
    static func decode<T: Decodable>(_ type: T.Type, from data: Data) throws -> T {
        do { return try JSONDecoder().decode(T.self, from: data) }
        catch { throw HelperError.unexpected("The helper sent a reply this version cannot read.") }
    }
}

#if DEBUG
/// DEBUG-only: serves the bundled `search-*.json` fixtures (the helper's schema v1). No network.
/// `failure` forces one state for tests and screenshots (`-LoupeWebState notConfigured|notDeployed`).
final class FixtureSearchHelper: SearchHelper {
    enum Forced: String { case notConfigured, notDeployed }
    let forced: Forced?
    /// Which sources the forced state applies to; nil means every source.
    let forcedSources: Set<WebSector>?

    init(forced: Forced? = nil, sources: Set<WebSector>? = nil) {
        self.forced = forced
        self.forcedSources = sources
    }

    private func check(_ s: WebSector) throws {
        guard let forced, forcedSources?.contains(s) ?? true else { return }
        switch forced {
        case .notConfigured: throw HelperError.notConfigured
        case .notDeployed: throw HelperError.notDeployed
        }
    }

    static func data(_ name: String) throws -> Data {
        guard let url = Bundle.main.url(forResource: name, withExtension: "json") else { throw HelperError.unexpected("Fixture missing") }
        return try Data(contentsOf: url)
    }

    func currency(_ q: CurrencyQuery) async throws -> CurrencyResponse {
        try check(.currency)
        let all = try SearchDecoding.decode(CurrencyResponse.self, from: Self.data("search-currency"))
        return Self.answer(q, from: all)
    }

    func weather(_ q: WeatherQuery) async throws -> WeatherResponse {
        try check(.weather)
        return try SearchDecoding.decode(WeatherResponse.self, from: Self.data("search-weather"))
    }

    func trains(_ q: TrainsQuery) async throws -> TrainsResponse {
        try check(.trains)
        return try SearchDecoding.decode(TrainsResponse.self, from: Self.data("search-uk-trains"))
    }

    /// The fixture is a 30-day USD series; any base and window are answered from it (cross rates
    /// through USD, the last N working days of the fixture for an N-day window), as Frankfurter would.
    static func answer(_ q: CurrencyQuery, from all: CurrencyResponse) -> CurrencyResponse {
        let dates = Array(Set(all.items.map(\.date))).sorted()
        var span = 1
        if let f = q.from, let t = q.to, let fd = WebDates.day(f), let td = WebDates.day(t) {
            span = max(1, (Calendar(identifier: .gregorian).dateComponents([.day], from: fd, to: td).day ?? 0) + 1)
        }
        let kept = Set(q.from == nil ? [dates.last ?? ""] : dates.suffix(Int((Double(span) * 5.0 / 7.0).rounded(.up))))
        var items: [CurrencyItem] = []
        for d in dates where kept.contains(d) {
            let usd = Dictionary(uniqueKeysWithValues: all.items.filter { $0.date == d }.map { ($0.quote, $0.rate) })
            func perUSD(_ c: String) -> Double? { c == "USD" ? 1 : usd[c] }
            guard let b = perUSD(q.base) else { continue }
            for s in q.symbols.sorted() {
                guard let v = perUSD(s) else { continue }
                items.append(CurrencyItem(date: d, base: q.base, quote: s, rate: (v / b * 10_000).rounded() / 10_000))
            }
        }
        return CurrencyResponse(schema: 1, source: all.source, attribution: all.attribution, fetchedAt: all.fetchedAt,
                                mode: q.from == nil ? "latest" : "series", items: items)
    }
}
#endif
