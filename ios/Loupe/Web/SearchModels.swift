import Foundation

// The helper's `/v1/search/<provider>` wire schema v1 (loupe-web-helper README, commit c140705).
// Every 200 carries `schema`, `source`, `attribution` {text, url}, `fetchedAt` and `items`.
// Decoding is strict about what the app shows and lenient (optional) about what it does not.

struct SearchAttribution: Decodable, Equatable {
    let text: String
    let url: String
    /// Currency only: Frankfurter's list of the central banks and rate providers.
    let providersUrl: String?
}

// MARK: Currency (Frankfurter)

struct CurrencyItem: Decodable, Equatable {
    let date: String
    let base: String
    let quote: String
    let rate: Double
}

struct CurrencyResponse: Decodable, Equatable {
    let schema: Int
    let source: String
    let attribution: SearchAttribution
    let fetchedAt: String
    /// `latest` | `historical` | `series`.
    let mode: String
    let items: [CurrencyItem]
}

// MARK: Weather (Open-Meteo)

struct WeatherLocation: Decodable, Equatable {
    let name: String?
    let country: String?
    let countryCode: String?
    let admin1: String?
    let latitude: Double
    let longitude: Double
    let timezone: String?
    let utcOffsetSeconds: Int?
}

struct WeatherUnits: Decodable, Equatable {
    let temperature: String?
    let windSpeed: String?
    let precipitation: String?
}

struct WeatherCurrent: Decodable, Equatable {
    let time: String?
    let temperature: Double?
    let apparentTemperature: Double?
    let relativeHumidity: Double?
    let precipitation: Double?
    let weatherCode: Int?
    let windSpeed: Double?
    let isDay: Bool?
}

struct WeatherDay: Decodable, Equatable {
    let date: String
    let weatherCode: Int?
    let tempMax: Double?
    let tempMin: Double?
    let precipitationProbabilityMax: Double?
    let precipitationSum: Double?
    let sunrise: String?
    let sunset: String?
    let uvIndexMax: Double?
}

struct WeatherResponse: Decodable, Equatable {
    let schema: Int
    let source: String
    let attribution: SearchAttribution
    let fetchedAt: String
    let location: WeatherLocation
    let units: WeatherUnits
    let current: WeatherCurrent?
    let items: [WeatherDay]
}

// MARK: UK trains (National Rail Darwin)

struct TrainLocation: Decodable, Equatable {
    let name: String?
    let crs: String?
    let via: String?
}

struct TrainItem: Decodable, Equatable {
    let serviceId: String?
    /// UK local "HH:MM".
    let scheduled: String?
    /// "On time", "Delayed", "Cancelled" or "HH:MM".
    let expected: String?
    let platform: String?
    let `operator`: String?
    let operatorCode: String?
    let origin: [TrainLocation]
    let destination: [TrainLocation]
    let isCancelled: Bool
    let cancelReason: String?
    let delayReason: String?
    let serviceType: String?
}

struct TrainBoard: Decodable, Equatable {
    let type: String
    let crs: String?
    let locationName: String?
    let filterCrs: String?
    let filterLocationName: String?
    let generatedAt: String?
    let platformAvailable: Bool?
    let messages: [String]
}

struct TrainsResponse: Decodable, Equatable {
    let schema: Int
    let source: String
    let attribution: SearchAttribution
    let fetchedAt: String
    let board: TrainBoard
    let items: [TrainItem]
}

// MARK: Requests (only the search itself leaves the phone: §4a "send the minimum")

struct CurrencyQuery: Encodable, Equatable {
    var base: String
    var symbols: [String]
    var date: String?
    var from: String?
    var to: String?
}

struct WeatherQuery: Encodable, Equatable {
    var place: String
    var days: Int
    var units = "metric"
}

struct TrainsQuery: Encodable, Equatable {
    var crs: String
    var board = "departures"
    var filterCrs: String?
    var numRows = 20
}

/// Any of the three: what one template run fetches.
enum SearchResult: Equatable {
    case currency(CurrencyResponse)
    case weather(WeatherResponse)
    case trains(TrainsResponse)

    var attribution: SearchAttribution {
        switch self {
        case .currency(let r): r.attribution
        case .weather(let r): r.attribution
        case .trains(let r): r.attribution
        }
    }
    var source: String {
        switch self {
        case .currency(let r): r.source
        case .weather(let r): r.source
        case .trains(let r): r.source
        }
    }
    var fetchedAt: String {
        switch self {
        case .currency(let r): r.fetchedAt
        case .weather(let r): r.fetchedAt
        case .trains(let r): r.fetchedAt
        }
    }
}
