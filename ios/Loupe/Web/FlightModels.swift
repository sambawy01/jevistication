import Foundation

// Wire schema v1 of loupe-web-helper (see sambawy01/loupe-web-helper README).

enum CabinClass: String, Codable, CaseIterable, Identifiable {
    case economy, premium_economy, business, first
    var id: String { rawValue }
    var label: String {
        switch self {
        case .economy: return "Economy"
        case .premium_economy: return "Premium economy"
        case .business: return "Business"
        case .first: return "First"
        }
    }
}

struct SearchRequest: Codable, Equatable {
    struct Slice: Codable, Equatable {
        var origin: String
        var destination: String
        var departureDate: String   // YYYY-MM-DD
    }
    struct Passenger: Codable, Equatable {
        var type: String            // adult | child | infant_without_seat
    }
    var schema: Int = 1
    var slices: [Slice]
    var passengers: [Passenger]
    var cabinClass: CabinClass
    var maxConnections: Int?
}

struct SearchResponse: Codable, Equatable {
    var schema: Int
    var source: String
    var fetchedAt: String
    var live: Bool
    var offers: [Offer]
}

struct Offer: Codable, Equatable, Identifiable {
    struct Segment: Codable, Equatable {
        var from: String
        var to: String
        var departAt: String        // airport-local, no offset
        var arriveAt: String
        var carrier: String
        var flight: String
        var aircraft: String?
    }
    struct Slice: Codable, Equatable {
        var segments: [Segment]
        var durationMinutes: Int?
    }
    struct Baggage: Codable, Equatable { var checked: Int; var carryOn: Int }
    struct Conditions: Codable, Equatable { var refundable: Bool?; var changeable: Bool? }

    var id: String
    var totalAmount: String
    var currency: String
    var owner: String
    var slices: [Slice]
    var baggage: Baggage
    var conditions: Conditions
    var expiresAt: String?

    var price: Decimal { Decimal(string: totalAmount, locale: Locale(identifier: "en_US_POSIX")) ?? 0 }
    /// Stops on the worst slice (0 = nonstop everywhere).
    var maxStops: Int { slices.map { max(0, $0.segments.count - 1) }.max() ?? 0 }
    var totalMinutes: Int { slices.compactMap(\.durationMinutes).reduce(0, +) }
    /// Minutes after local midnight of each slice's first departure.
    var sliceDepartureMinutes: [Int] { slices.compactMap { $0.segments.first.flatMap { LocalTime.minutes($0.departAt) } } }
}

struct HealthResponse: Codable, Equatable { var ok: Bool; var version: String? }

enum LocalTime {
    /// "2026-10-14T07:35" or "2026-10-14T07:35:00" → 455. Airport-local, so no time zone math.
    static func minutes(_ s: String) -> Int? {
        guard let t = s.firstIndex(of: "T") else { return nil }
        let parts = s[s.index(after: t)...].split(separator: ":")
        guard parts.count >= 2, let h = Int(parts[0]), let m = Int(parts[1].prefix(2)), (0..<24).contains(h), (0..<60).contains(m) else { return nil }
        return h * 60 + m
    }
    static func hhmm(_ s: String) -> String {
        guard let m = minutes(s) else { return "--:--" }
        return String(format: "%02d:%02d", m / 60, m % 60)
    }
    static func format(minutes m: Int) -> String { String(format: "%02d:%02d", m / 60, m % 60) }
}
