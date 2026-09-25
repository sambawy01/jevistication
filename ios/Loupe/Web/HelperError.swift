import Foundation

/// Typed failures from the online helper, mapped from `{ "error": { "code", "message" } }`.
enum HelperError: Error, Equatable {
    case invalidKey
    case rateLimited
    case providerRateLimited
    case noResults
    case busy
    case providerError
    case badRequest(String)
    case offline
    case timeout
    case unexpected(String)
    /// 503 NOT_CONFIGURED: the helper has this route but not Loupe's provider key or flag yet.
    case notConfigured
    /// A bare 404: the deployed helper does not have this route yet (it is not deployed).
    case notDeployed

    struct Body: Decodable { struct Inner: Decodable { var code: String; var message: String? }; var error: Inner }

    static func from(status: Int, data: Data) -> HelperError {
        let body = try? JSONDecoder().decode(Body.self, from: data)
        switch body?.error.code {
        case "INVALID_KEY": return .invalidKey
        case "RATE_LIMITED": return .rateLimited
        case "PROVIDER_RATE_LIMITED": return .providerRateLimited
        case "NO_RESULTS": return .noResults
        case "BUSY": return .busy
        case "PROVIDER_ERROR": return .providerError
        case "BAD_REQUEST": return .badRequest(body?.error.message ?? "The helper rejected the search.")
        case "NOT_CONFIGURED": return .notConfigured
        default: break
        }
        switch status {
        case 401, 403: return .invalidKey
        case 404: return .notDeployed
        case 429: return .rateLimited
        case 503: return .busy
        case 500...599: return .providerError
        default: return .unexpected("HTTP \(status)")
        }
    }

    static func from(urlError: URLError) -> HelperError {
        switch urlError.code {
        case .notConnectedToInternet, .networkConnectionLost, .dataNotAllowed, .internationalRoamingOff,
             .cannotFindHost, .cannotConnectToHost, .dnsLookupFailed:
            return .offline
        case .timedOut: return .timeout
        default: return .unexpected(urlError.localizedDescription)
        }
    }

    var title: String {
        switch self {
        case .invalidKey: return "Duffel rejected this key"
        case .rateLimited: return "Too many searches"
        case .providerRateLimited: return "Duffel is rate limiting"
        case .noResults: return "No flights found"
        case .busy: return "Helper is busy"
        case .providerError: return "Duffel had a problem"
        case .badRequest: return "Search not accepted"
        case .offline: return "Needs a connection"
        case .timeout: return "Search timed out"
        case .unexpected: return "Something went wrong"
        case .notConfigured: return "Not set up yet"
        case .notDeployed: return "Not available yet"
        }
    }

    var message: String {
        switch self {
        case .invalidKey: return "INVALID_KEY. Check the key in your Duffel dashboard and add it again."
        case .rateLimited: return "This phone has hit the helper's limit of 60 searches an hour. Try again later."
        case .providerRateLimited: return "Duffel asked us to slow down. Wait a minute and search again."
        case .noResults: return "Nothing matches these dates and route. Try other dates or allow a stop."
        case .busy: return "The helper is at capacity. Try again in a few seconds."
        case .providerError: return "Duffel did not answer properly. Try again shortly."
        case .badRequest(let m): return m
        case .offline: return "Needs a connection. Everything else keeps working."
        case .timeout: return "No answer within 20 seconds. Try again."
        case .unexpected(let m): return m
        case .notConfigured: return "Loupe's helper does not have this source's provider plan set up yet. Nothing was fetched."
        case .notDeployed: return "Loupe's online helper does not offer this search yet. Nothing was fetched."
        }
    }
}
