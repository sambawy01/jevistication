import Foundation
import LoupeKit

/// The Web tab's template library (owner decision 2026-09-25): each sector has several ready-made
/// question variants and a custom question box. Every source is online, opt-in and labelled (§4a).
enum WebSector: String, CaseIterable, Identifiable, Hashable {
    case currency, weather, trains, flights
    var id: String { rawValue }

    /// The helper's `source` for the ledger item id (`web:<source>:<item>`).
    var source: String {
        switch self {
        case .currency: "frankfurter"
        case .weather: "open-meteo"
        case .trains: "national-rail-darwin"
        case .flights: "duffel"
        }
    }

    var symbol: String {
        switch self {
        case .currency: "coloncurrencysign.arrow.circlepath"
        case .weather: "cloud.sun"
        case .trains: "tram"
        case .flights: "airplane"
        }
    }

    var title: String { WS.t("sector.\(rawValue)") }
    var provider: String { WS.t("provider.\(rawValue)") }

    /// What leaves the phone for this source, shown before it is turned on.
    var sends: String { WS.t("sends.\(rawValue)") }
}

/// Which sectors the library shows. Flights is DEVELOPMENT-ONLY until Duffel grants permission
/// (its Services Agreement bans metasearch §2.5(d) and third-party access §2.5(e)): only a build
/// with `LOUPE_FLIGHTS_DEV` (Debug, project.yml) lists it.
enum WebBuild {
    #if LOUPE_FLIGHTS_DEV
    static let flightsDevFlag = true
    #else
    static let flightsDevFlag = false
    #endif

    static func sectors(flightsAllowed: Bool = flightsDevFlag) -> [WebSector] {
        WebSector.allCases.filter { $0 != .flights || flightsAllowed }
    }
}

/// The decision types of owner decision 1, mirrored from LoupeKit's `WebDecisionType`.
enum AnswerType: String, CaseIterable, Identifiable, Equatable {
    case yesNo = "yes_no", pick, score, rank
    var id: String { rawValue }
    var kotlin: WebDecisionType {
        switch self {
        case .yesNo: .yesNo
        case .pick: .pick
        case .score: .score
        case .rank: .rank
        }
    }
    var label: String { WS.t("type.\(rawValue)") }
    /// Pick and rank sort the items best first; yes/no and score keep the items' own order.
    var sorts: Bool { self == .pick || self == .rank }
}

/// What a variant's rule baseline computes. Each is a plain, documented rule (no model).
enum RuleKind: String, Equatable {
    // currency
    case highestRate, aboveAverage, weekHigh, strongestVsHome
    // weather
    case dryOnSaturday, beach, run7am, picnic, umbrella
    // trains
    case arriveBy, delayedAtTarget, reliability, disrupted, relaxed
}

/// The rows a variant reads, derived from the provider's items.
enum RowSet: Equatable {
    /// One row per working day for `inputs.base → inputs.quote`, the last `days` of a `window`-day series.
    case ratePairDays(days: Int, window: Int)
    /// One row per currency in `inputs.basket`, priced in `inputs.home`, change over 7 days.
    case basketVsHome
    case forecastDays
    case departures
}

struct WebVariant: Identifiable, Equatable {
    let id: String
    let sector: WebSector
    let type: AnswerType
    /// English and Arabic question, with `{base}`, `{quote}`, `{home}`, `{place}`, `{to}`, `{by}`, `{time}`.
    let en: String
    let ar: String
    let rows: RowSet
    let rule: RuleKind

    func question(_ inputs: WebInputs, lang: MS.Lang = MS.lang) -> String { inputs.fill(lang == .ar ? ar : en) }
}

/// The small form above the variants. Defaults are the owner's examples.
struct WebInputs: Equatable {
    var base = "EUR"
    var quote = "EGP"
    var home = "EGP"
    var basket = "USD, EUR, GBP, SAR, AED"
    var place = "Alexandria"
    var fromCrs = "PAD"
    var toCrs = "RDG"
    var arriveBy = "09:00"
    var journeyMinutes = 25
    var targetTime = "08:15"

    var basketCodes: [String] {
        basket.uppercased().split(whereSeparator: { $0 == "," || $0 == " " }).map(String.init)
            .filter { $0.count == 3 && $0.allSatisfy(\.isLetter) && $0 != home.uppercased() }
    }

    func fill(_ s: String) -> String {
        s.replacingOccurrences(of: "{base}", with: base.uppercased())
            .replacingOccurrences(of: "{quote}", with: quote.uppercased())
            .replacingOccurrences(of: "{home}", with: home.uppercased())
            .replacingOccurrences(of: "{place}", with: place)
            .replacingOccurrences(of: "{to}", with: toCrs.uppercased())
            .replacingOccurrences(of: "{by}", with: arriveBy)
            .replacingOccurrences(of: "{time}", with: targetTime)
    }

    static func isCode(_ s: String) -> Bool { s.count == 3 && s.allSatisfy { $0.isASCII && $0.isLetter } }
    static func isHHMM(_ s: String) -> Bool { WebDates.minutes(s) != nil }

    /// Why the inputs cannot be sent for [sector], or nil.
    func problem(for sector: WebSector) -> String? {
        switch sector {
        case .currency:
            guard Self.isCode(base), Self.isCode(quote), Self.isCode(home) else { return WS.t("input.badCurrency") }
            return base.uppercased() == quote.uppercased() ? WS.t("input.sameCurrency") : nil
        case .weather:
            let p = place.trimmingCharacters(in: .whitespaces)
            return p.count < 2 || p.count > 80 ? WS.t("input.badPlace") : nil
        case .trains:
            guard Self.isCode(fromCrs), Self.isCode(toCrs) else { return WS.t("input.badCrs") }
            guard Self.isHHMM(arriveBy), Self.isHHMM(targetTime) else { return WS.t("input.badTime") }
            return nil
        case .flights:
            return nil
        }
    }
}

enum WebCatalog {
    /// 4–6 variants per sector (owner decision 2), EN and AR. The rule of each is in `WebRules`.
    static let variants: [WebVariant] = [
        // Currency (Frankfurter)
        .init(id: "currency.bestDay", sector: .currency, type: .pick,
              en: "Best day this week to convert {base}→{quote}?", ar: "ما أفضل يوم هذا الأسبوع لتحويل {base}←{quote}؟",
              rows: .ratePairDays(days: 7, window: 7), rule: .highestRate),
        .init(id: "currency.aboveAverage", sector: .currency, type: .yesNo,
              en: "Is this day's {base}→{quote} rate better than the 30-day average?", ar: "هل سعر {base}←{quote} في هذا اليوم أفضل من متوسط 30 يومًا؟",
              rows: .ratePairDays(days: 7, window: 30), rule: .aboveAverage),
        .init(id: "currency.rankVsHome", sector: .currency, type: .rank,
              en: "Rank these currencies by change vs {home} this week", ar: "رتّب هذه العملات حسب تغيّرها مقابل {home} هذا الأسبوع",
              rows: .basketVsHome, rule: .strongestVsHome),
        .init(id: "currency.scoreDays", sector: .currency, type: .score,
              en: "Score each day this week for converting {base}→{quote}", ar: "قيّم كل يوم هذا الأسبوع لتحويل {base}←{quote}",
              rows: .ratePairDays(days: 7, window: 7), rule: .highestRate),
        .init(id: "currency.weekHigh", sector: .currency, type: .yesNo,
              en: "Is this day's {base}→{quote} rate the highest of the week?", ar: "هل سعر {base}←{quote} في هذا اليوم هو الأعلى هذا الأسبوع؟",
              rows: .ratePairDays(days: 7, window: 7), rule: .weekHigh),

        // Weather (Open-Meteo)
        .init(id: "weather.saturdayDry", sector: .weather, type: .yesNo,
              en: "Is Saturday dry enough for the trip to {place}?", ar: "هل يوم السبت جاف بما يكفي للرحلة إلى {place}؟",
              rows: .forecastDays, rule: .dryOnSaturday),
        .init(id: "weather.beach", sector: .weather, type: .pick,
              en: "Which day this week is best for the beach in {place}?", ar: "أي يوم هذا الأسبوع هو الأفضل للشاطئ في {place}؟",
              rows: .forecastDays, rule: .beach),
        .init(id: "weather.run7am", sector: .weather, type: .score,
              en: "Score each day for a run at 7am in {place}", ar: "قيّم كل يوم للجري في السابعة صباحًا في {place}",
              rows: .forecastDays, rule: .run7am),
        .init(id: "weather.picnic", sector: .weather, type: .rank,
              en: "Rank the days for a picnic in {place}", ar: "رتّب الأيام لنزهة في {place}",
              rows: .forecastDays, rule: .picnic),
        .init(id: "weather.umbrella", sector: .weather, type: .yesNo,
              en: "Will I need an umbrella in {place} on this day?", ar: "هل سأحتاج إلى مظلة في {place} في هذا اليوم؟",
              rows: .forecastDays, rule: .umbrella),

        // UK trains (National Rail Darwin)
        .init(id: "trains.arriveBy", sector: .trains, type: .pick,
              en: "Which departure gets me to {to} before {by}?", ar: "أي قطار يوصلني إلى {to} قبل {by}؟",
              rows: .departures, rule: .arriveBy),
        .init(id: "trains.delayedAt", sector: .trains, type: .yesNo,
              en: "Is the {time} likely delayed?", ar: "هل قطار {time} مرجّح أن يتأخر؟",
              rows: .departures, rule: .delayedAtTarget),
        .init(id: "trains.rankReliable", sector: .trains, type: .rank,
              en: "Rank departures by reliability and arrival time", ar: "رتّب القطارات حسب الموثوقية ووقت الوصول",
              rows: .departures, rule: .reliability),
        .init(id: "trains.disrupted", sector: .trains, type: .yesNo,
              en: "Is this service cancelled or running late?", ar: "هل هذه الرحلة ملغاة أو متأخرة؟",
              rows: .departures, rule: .disrupted),
        .init(id: "trains.relaxed", sector: .trains, type: .score,
              en: "Score each departure for a relaxed trip", ar: "قيّم كل قطار لرحلة مريحة",
              rows: .departures, rule: .relaxed),
    ]

    static func variants(_ sector: WebSector) -> [WebVariant] { variants.filter { $0.sector == sector } }
    static func variant(_ id: String) -> WebVariant? { variants.first { $0.id == id } }

    /// A custom question reads the sector's plain rows and is compared with the sector's default rule.
    static func customRows(_ sector: WebSector) -> RowSet {
        switch sector {
        case .currency: .ratePairDays(days: 7, window: 7)
        case .weather: .forecastDays
        case .trains, .flights: .departures
        }
    }

    static func defaultRule(_ sector: WebSector) -> RuleKind {
        switch sector {
        case .currency: .highestRate
        case .weather: .picnic
        case .trains, .flights: .reliability
        }
    }

    // MARK: Queries: exactly the search, nothing else (§4a)

    static func currencyQuery(_ rows: RowSet, _ i: WebInputs, today: Date = Date()) -> CurrencyQuery {
        switch rows {
        case .basketVsHome:
            // One USD-based request; every price in the home currency is a cross rate through USD.
            let symbols = Array(Set(i.basketCodes + [i.home.uppercased()]).subtracting(["USD"])).sorted()
            return CurrencyQuery(base: "USD", symbols: symbols, from: WebDates.ymd(daysBefore: 9, today), to: WebDates.ymd(daysBefore: 0, today))
        case .ratePairDays(_, let window):
            return CurrencyQuery(base: i.base.uppercased(), symbols: [i.quote.uppercased()],
                                 from: WebDates.ymd(daysBefore: window + 3, today), to: WebDates.ymd(daysBefore: 0, today))
        default:
            return CurrencyQuery(base: i.base.uppercased(), symbols: [i.quote.uppercased()])
        }
    }

    static func weatherQuery(_ i: WebInputs) -> WeatherQuery {
        WeatherQuery(place: i.place.trimmingCharacters(in: .whitespaces), days: 7)
    }

    static func trainsQuery(_ i: WebInputs) -> TrainsQuery {
        TrainsQuery(crs: i.fromCrs.uppercased(), filterCrs: i.toCrs.uppercased(), numRows: 20)
    }
}

enum WebDates {
    static let posix: DateFormatter = {
        let f = DateFormatter()
        f.calendar = Calendar(identifier: .gregorian)
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone(identifier: "UTC")
        f.dateFormat = "yyyy-MM-dd"
        return f
    }()

    static func day(_ s: String) -> Date? { posix.date(from: String(s.prefix(10))) }

    static func ymd(daysBefore n: Int, _ today: Date) -> String {
        posix.string(from: Calendar(identifier: .gregorian).date(byAdding: .day, value: -n, to: today) ?? today)
    }

    /// "Sat 26 Sep" (or Arabic), from `YYYY-MM-DD`, read as a calendar day.
    static func label(_ ymd: String) -> String {
        guard let d = day(ymd) else { return ymd }
        let f = DateFormatter()
        f.calendar = Calendar(identifier: .gregorian)
        f.timeZone = TimeZone(identifier: "UTC")
        f.locale = Locale(identifier: MS.lang == .ar ? "ar" : "en_GB")
        f.setLocalizedDateFormatFromTemplate("EEE d MMM")
        return f.string(from: d)
    }

    /// 1 = Sunday … 7 = Saturday.
    static func weekday(_ ymd: String) -> Int? {
        guard let d = day(ymd) else { return nil }
        var c = Calendar(identifier: .gregorian)
        c.timeZone = TimeZone(identifier: "UTC")!
        return c.component(.weekday, from: d)
    }

    /// Minutes after midnight for "HH:MM".
    static func minutes(_ s: String) -> Int? {
        let p = s.split(separator: ":")
        guard p.count == 2, let h = Int(p[0]), let m = Int(p[1]), (0..<24).contains(h), (0..<60).contains(m), p[1].count == 2 else { return nil }
        return h * 60 + m
    }

    static func hhmm(_ minutes: Int) -> String { String(format: "%02d:%02d", (minutes / 60) % 24, minutes % 60) }

    /// "HH:mm" local, from the helper's ISO `fetchedAt`.
    static func fetched(_ iso: String) -> String { ResultsView.fetchedTime(iso) }
}
