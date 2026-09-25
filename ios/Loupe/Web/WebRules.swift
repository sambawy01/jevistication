import Foundation

/// One item a question is asked about, built from the provider's items. Every row is verifiable
/// (owner rule 5): it carries the provider record it came from and, where one exists, a link to
/// the provider's own page for it.
struct WebRow: Identifiable, Equatable {
    enum Payload: Equatable {
        case rate(date: String, base: String, quote: String, rate: Double, average: Double?, weekHigh: Double, weekLow: Double)
        case basket(code: String, home: String, now: Double, then: Double, fromDate: String, toDate: String)
        case day(WeatherDay, units: WeatherUnits)
        case train(TrainItem, journeyMinutes: Int, arriveBy: Int, target: Int)
    }

    let id: String
    let title: String
    let detail: String
    /// The facts Laya reads, most important first.
    let lines: [String]
    /// The provider record(s) behind the row, as the provider sent them.
    let sourceItem: String
    let link: URL?
    let data: Payload
}

/// The answer to one row: from the rules, or from Laya.
struct WebAnswerRow: Identifiable, Equatable {
    var row: WebRow
    var rank: Int
    /// 0...1, higher is better / more "yes".
    var value: Double
    /// Short verdict, e.g. "yes · dry, 5%" or "4/5".
    var label: String
    var unsure = false
    var note: String?
    /// For a yes/no question: whether this row's answer is yes.
    var yes: Bool { value >= 0.5 }
    var id: String { row.id }
}

enum WebRows {
    /// The rows [set] reads from [result]. Empty when the provider sent nothing usable.
    static func build(_ set: RowSet, from result: SearchResult, inputs: WebInputs) -> [WebRow] {
        switch (set, result) {
        case let (.ratePairDays(days, _), .currency(r)): return ratePairDays(r, days: days)
        case let (.basketVsHome, .currency(r)): return basket(r, inputs: inputs)
        case let (.forecastDays, .weather(r)): return forecast(r)
        case let (.departures, .trains(r)): return departures(r, inputs: inputs)
        default: return []
        }
    }

    static func frankfurterLink(base: String, quote: String, date: String) -> URL? {
        URL(string: "https://api.frankfurter.dev/v2/rates?base=\(base)&quotes=\(quote)&date=\(date)")
    }

    static func ratePairDays(_ r: CurrencyResponse, days: Int) -> [WebRow] {
        let items = r.items.sorted { $0.date < $1.date }
        guard let first = items.first else { return [] }
        let average = items.count >= 10 ? items.map(\.rate).reduce(0, +) / Double(items.count) : nil
        let shown = Array(items.suffix(max(1, days * 5 / 7)))
        let high = shown.map(\.rate).max() ?? first.rate
        let low = shown.map(\.rate).min() ?? first.rate
        return shown.map { it in
            var lines = [
                "Date: \(it.date) (\(WebDates.label(it.date)))",
                "Rate: 1 \(it.base) = \(fmt(it.rate)) \(it.quote)",
                "This week: high \(fmt(high)), low \(fmt(low))",
            ]
            if let average { lines.append("Average over \(items.count) working days: \(fmt(average))") }
            if it.date == shown.last?.date { lines.append("The latest rate") }
            return WebRow(id: "\(it.date):\(it.base):\(it.quote)",
                          title: WebDates.label(it.date),
                          detail: "1 \(it.base) = \(fmt(it.rate)) \(it.quote)",
                          lines: lines,
                          sourceItem: "\(it.date) \(it.base)→\(it.quote) \(fmt(it.rate))",
                          link: frankfurterLink(base: it.base, quote: it.quote, date: it.date),
                          data: .rate(date: it.date, base: it.base, quote: it.quote, rate: it.rate, average: average, weekHigh: high, weekLow: low))
        }
    }

    /// Prices of each basket currency in the home currency, through the USD base: X→home = (home per USD) / (X per USD).
    static func basket(_ r: CurrencyResponse, inputs: WebInputs) -> [WebRow] {
        let home = inputs.home.uppercased()
        let dates = Array(Set(r.items.map(\.date))).sorted()
        guard let d0 = dates.first, let d1 = dates.last, d0 != d1 else { return [] }
        func perUSD(_ c: String, _ d: String) -> Double? {
            c == r.items.first?.base ? 1 : r.items.first { $0.date == d && $0.quote == c }?.rate
        }
        return inputs.basketCodes.compactMap { code in
            guard let h0 = perUSD(home, d0), let h1 = perUSD(home, d1), let c0 = perUSD(code, d0), let c1 = perUSD(code, d1), c0 > 0, c1 > 0 else { return nil }
            let then = h0 / c0, now = h1 / c1
            let change = (now / then - 1) * 100
            return WebRow(id: "\(code):\(home):\(d0):\(d1)",
                          title: "\(code) → \(home)",
                          detail: "\(fmt(then)) → \(fmt(now)) \(home) (\(pct(change)))",
                          lines: ["Currency: \(code), priced in \(home)",
                                  "\(d0): 1 \(code) = \(fmt(then)) \(home)",
                                  "\(d1): 1 \(code) = \(fmt(now)) \(home)",
                                  "Change: \(pct(change))"],
                          sourceItem: "\(r.items.first?.base ?? "USD")→\(code), \(r.items.first?.base ?? "USD")→\(home) on \(d0) and \(d1)",
                          link: URL(string: "https://api.frankfurter.dev/v2/rates?base=\(code)&quotes=\(home)&date=\(d1)"),
                          data: .basket(code: code, home: home, now: now, then: then, fromDate: d0, toDate: d1))
        }
    }

    static func forecast(_ r: WeatherResponse) -> [WebRow] {
        let t = r.units.temperature ?? "°C", p = r.units.precipitation ?? "mm"
        let page = URL(string: String(format: "https://open-meteo.com/en/docs?latitude=%.4f&longitude=%.4f", r.location.latitude, r.location.longitude))
        return r.items.map { d in
            let rain = d.precipitationProbabilityMax.map { "\(Int($0))%" } ?? "?"
            let sum = d.precipitationSum.map { "\(fmt1($0)) \(p)" } ?? "?"
            let hi = d.tempMax.map { "\(fmt1($0))\(t)" } ?? "?", lo = d.tempMin.map { "\(fmt1($0))\(t)" } ?? "?"
            return WebRow(id: d.date,
                          title: WebDates.label(d.date),
                          detail: "\(WeatherWords.text(d.weatherCode)) · \(lo)–\(hi) · rain \(rain), \(sum)",
                          lines: ["Day: \(WebDates.label(d.date)) (\(d.date)), \(r.location.name ?? "")",
                                  "Sky: \(WeatherWords.text(d.weatherCode))",
                                  "Temperature: low \(lo), high \(hi)",
                                  "Rain: chance \(rain), total \(sum)",
                                  "UV index: \(d.uvIndexMax.map(fmt1) ?? "?")",
                                  "Sunrise \(d.sunrise?.suffix(5) ?? "?"), sunset \(d.sunset?.suffix(5) ?? "?")"],
                          sourceItem: "\(d.date) code \(d.weatherCode.map(String.init) ?? "–"), max \(hi), min \(lo), rain \(rain) / \(sum)",
                          link: page,
                          data: .day(d, units: r.units))
        }
    }

    static func departures(_ r: TrainsResponse, inputs: WebInputs) -> [WebRow] {
        let from = r.board.crs ?? inputs.fromCrs.uppercased()
        let to = r.board.filterCrs ?? inputs.toCrs.uppercased()
        let page = URL(string: "https://www.nationalrail.co.uk/live-trains/departures/\(from)/\(to)/")
        let by = WebDates.minutes(inputs.arriveBy) ?? 9 * 60, target = WebDates.minutes(inputs.targetTime) ?? -1
        return r.items.enumerated().map { i, s in
            let dest = s.destination.map { $0.name ?? $0.crs ?? "?" }.joined(separator: ", ")
            let status = s.isCancelled ? "Cancelled" : (s.expected ?? "?")
            var lines = ["Departs \(r.board.locationName ?? from) at \(s.scheduled ?? "?"), expected: \(status)",
                         "To: \(dest), calling at \(r.board.filterLocationName ?? to)",
                         "Platform: \(s.platform ?? "not yet shown")",
                         "Operator: \(s.operator ?? "?")"]
            if let why = s.cancelReason ?? s.delayReason { lines.append(why) }
            if let dep = TrainsRule.departure(s) { lines.append("About \(inputs.journeyMinutes) min to \(to): arrives about \(WebDates.hhmm(dep + inputs.journeyMinutes))") }
            return WebRow(id: s.serviceId ?? "svc\(i)",
                          title: "\(s.scheduled ?? "?") → \(dest)",
                          detail: "\(status) · platform \(s.platform ?? "–") · \(s.operator ?? "")",
                          lines: lines,
                          sourceItem: "Darwin service \(s.serviceId ?? "?"): std \(s.scheduled ?? "?"), etd \(s.expected ?? "?")\(s.isCancelled ? ", cancelled" : "")",
                          link: page,
                          data: .train(s, journeyMinutes: inputs.journeyMinutes, arriveBy: by, target: target))
        }
    }

    static func fmt(_ v: Double) -> String { String(format: v >= 100 ? "%.2f" : "%.4f", v) }
    static func fmt1(_ v: Double) -> String { String(format: "%.1f", v) }
    static func pct(_ v: Double) -> String { String(format: "%+.2f%%", v) }
}

/// WMO weather codes, in words (Open-Meteo's documented table, grouped).
enum WeatherWords {
    static func text(_ code: Int?) -> String {
        guard let c = code else { return WS.t("wx.unknown") }
        switch c {
        case 0: return WS.t("wx.clear")
        case 1, 2: return WS.t("wx.partly")
        case 3: return WS.t("wx.overcast")
        case 45, 48: return WS.t("wx.fog")
        case 51...57: return WS.t("wx.drizzle")
        case 61...67, 80...82: return WS.t("wx.rain")
        case 71...77, 85, 86: return WS.t("wx.snow")
        case 95...99: return WS.t("wx.storm")
        default: return WS.t("wx.unknown")
        }
    }
}

enum TrainsRule {
    /// The expected departure in minutes: the expected time, else the scheduled one when "On time";
    /// nil when cancelled or "Delayed" with no time.
    static func departure(_ s: TrainItem) -> Int? {
        if s.isCancelled { return nil }
        if let e = s.expected, let m = WebDates.minutes(e) { return m }
        if s.expected == "On time", let sch = s.scheduled { return WebDates.minutes(sch) }
        return nil
    }

    /// Minutes late (0 when on time); nil when unknown or cancelled.
    static func lateness(_ s: TrainItem) -> Int? {
        guard let dep = departure(s), let sch = s.scheduled.flatMap(WebDates.minutes) else { return nil }
        return max(0, dep - sch)
    }
}

/// The rule baselines: plain arithmetic over the provider's facts, shown beside Laya's answer.
enum WebRules {
    static func clamp(_ x: Double) -> Double { min(1, max(0, x)) }

    /// Each row's rule value (0...1) and verdict.
    static func evaluate(_ rule: RuleKind, _ row: WebRow) -> (value: Double, label: String) {
        switch (rule, row.data) {
        case let (.highestRate, .rate(_, _, _, rate, _, high, low)):
            let v = high > low ? (rate - low) / (high - low) : 1
            return (v, rate == high ? WS.t("rule.weekHigh") : WS.t("rule.rate", ["v": WebRows.fmt(rate)]))
        case let (.aboveAverage, .rate(_, _, _, rate, average, _, _)):
            guard let average else { return (0, WS.t("rule.noAverage")) }
            let yes = rate > average
            return (yes ? 1 : 0, WS.t(yes ? "rule.aboveAvg" : "rule.belowAvg", ["v": WebRows.fmt(average)]))
        case let (.weekHigh, .rate(_, _, _, rate, _, high, _)):
            return rate >= high ? (1, WS.t("rule.weekHigh")) : (0, WS.t("rule.belowHigh", ["v": WebRows.fmt(high)]))
        case let (.strongestVsHome, .basket(_, _, now, then, _, _)):
            let change = (now / then - 1) * 100
            return (clamp(0.5 + change / 4), WebRows.pct(change))

        case let (.dryOnSaturday, .day(d, _)):
            let dry = dry(d)
            let isSat = WebDates.weekday(d.date) == 7
            let words = WS.t(dry ? "rule.dry" : "rule.wet", ["p": "\(Int(d.precipitationProbabilityMax ?? -1))%"])
            return (dry && isSat ? 1 : 0, isSat ? words : WS.t("rule.notSaturday") + " · " + words)
        case let (.beach, .day(d, _)):
            let t = d.tempMax ?? 0
            return (clamp(1 - abs(t - 30) / 10) * rainFactor(d), WS.t("rule.warmDry", ["t": WebRows.fmt1(t)]))
        case let (.run7am, .day(d, _)):
            let t = d.tempMin ?? 0
            return (clamp(1 - max(0, abs(t - 14) - 4) / 10) * rainFactor(d), WS.t("rule.morning", ["t": WebRows.fmt1(t)]))
        case let (.picnic, .day(d, _)):
            let t = d.tempMax ?? 0
            return (clamp(1 - max(0, abs(t - 25) - 3) / 10) * rainFactor(d), WS.t("rule.mildDry", ["t": WebRows.fmt1(t)]))
        case let (.umbrella, .day(d, _)):
            let need = (d.precipitationProbabilityMax ?? 0) >= 50 || (d.precipitationSum ?? 0) >= 1
            return (need ? 1 : 0, WS.t(need ? "rule.umbrella" : "rule.noUmbrella", ["p": "\(Int(d.precipitationProbabilityMax ?? 0))%"]))

        case let (.arriveBy, .train(s, journey, by, _)):
            guard let dep = TrainsRule.departure(s) else { return (0, status(s)) }
            let arrive = dep + journey
            guard arrive <= by else { return (0, WS.t("rule.arrivesLate", ["t": WebDates.hhmm(arrive)])) }
            // Arrives in time: the latest such departure waits least; a late-running one scores less.
            let onTime = (TrainsRule.lateness(s) ?? 0) == 0 ? 1.0 : 0.6
            return (0.5 + 0.5 * onTime * clamp(1 - Double(by - arrive) / 90), WS.t("rule.arrivesBy", ["t": WebDates.hhmm(arrive)]))
        case let (.delayedAtTarget, .train(s, _, _, target)):
            let late = s.isCancelled || (TrainsRule.lateness(s) ?? 1) > 0
            let isTarget = s.scheduled.flatMap(WebDates.minutes) == target
            return (late && isTarget ? 1 : 0, (isTarget ? "" : WS.t("rule.notTarget") + " · ") + status(s))
        case let (.reliability, .train(s, _, _, _)):
            let dep = Double(s.scheduled.flatMap(WebDates.minutes) ?? 0)
            let base: Double = s.isCancelled ? 0 : (TrainsRule.lateness(s).map { $0 == 0 ? 1.0 : clamp(0.9 - Double($0) / 60) } ?? 0.2)
            return (clamp(base - dep / 1_000_000), status(s))
        case let (.disrupted, .train(s, _, _, _)):
            let bad = s.isCancelled || (TrainsRule.lateness(s) ?? 1) > 0
            return (bad ? 1 : 0, status(s))
        case let (.relaxed, .train(s, _, _, _)):
            let v: Double
            if s.isCancelled { v = 0 } else if let l = TrainsRule.lateness(s) {
                v = l == 0 ? (s.platform == nil ? 0.75 : 1) : (l < 10 ? 0.5 : 0.25)
            } else { v = 0.25 }
            return (v, status(s))
        default:
            return (0, "–")
        }
    }

    static func dry(_ d: WeatherDay) -> Bool { (d.precipitationProbabilityMax ?? 100) <= 30 && (d.precipitationSum ?? 0) < 1 }
    static func rainFactor(_ d: WeatherDay) -> Double { clamp(1 - (d.precipitationProbabilityMax ?? 50) / 100) }

    static func status(_ s: TrainItem) -> String {
        if s.isCancelled { return WS.t("rule.cancelled") }
        if let l = TrainsRule.lateness(s) { return l == 0 ? WS.t("rule.onTime") : WS.t("rule.late", ["m": "\(l)"]) }
        return WS.t("rule.delayedUnknown")
    }

    /// The rule's answer over all rows for a question of [type]: sorted best first for pick and
    /// rank, in the rows' own order for yes/no and score.
    static func answer(_ rule: RuleKind, type: AnswerType, rows: [WebRow]) -> [WebAnswerRow] {
        let scored = rows.map { r -> WebAnswerRow in
            let (v, l) = evaluate(rule, r)
            return WebAnswerRow(row: r, rank: 0, value: v, label: l)
        }
        return finish(scored, type: type) { r in
            switch type {
            case .yesNo: return (r.yes ? WS.t("yes") : WS.t("no")) + " · " + r.label
            case .score: return "\(level(r.value))/5 · " + r.label
            case .pick, .rank: return r.label
            }
        }
    }

    /// Orders [rows] for [type] (stable: ties keep the given order) and numbers them.
    static func finish(_ rows: [WebAnswerRow], type: AnswerType, label: (WebAnswerRow) -> String) -> [WebAnswerRow] {
        let ordered = type.sorts
            ? rows.enumerated().sorted { $0.element.value != $1.element.value ? $0.element.value > $1.element.value : $0.offset < $1.offset }.map(\.element)
            : rows
        return ordered.enumerated().map { i, r in
            var out = r
            out.rank = i + 1
            out.label = label(r)
            return out
        }
    }

    /// A 0...1 value as a level 1...5.
    static func level(_ v: Double) -> Int { min(5, max(1, Int((v * 4).rounded()) + 1)) }

    /// The row a yes/no variant is about (Saturday; the {time} train; the latest rate), when it names one.
    static func target(_ rule: RuleKind, rows: [WebRow]) -> WebRow? {
        switch rule {
        case .dryOnSaturday:
            return rows.first { if case let .day(d, _) = $0.data { return WebDates.weekday(d.date) == 7 }; return false }
        case .delayedAtTarget:
            return rows.first { if case let .train(s, _, _, t) = $0.data { return s.scheduled.flatMap(WebDates.minutes) == t }; return false }
        case .aboveAverage, .weekHigh:
            return rows.last
        default:
            return nil
        }
    }
}
