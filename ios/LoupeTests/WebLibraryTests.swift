import XCTest
import LoupeKit
@testable import Loupe

/// Answers every Web question from a table keyed by a substring of the item text.
final class WebFakeBackend: NSObject, Backend {
    let answer: (JudgmentChoice, String) -> [String: Double]
    var calls = 0
    init(_ answer: @escaping (JudgmentChoice, String) -> [String: Double]) { self.answer = answer }
    func score(judgment: JudgmentChoice, state: TextState) -> Scored {
        calls += 1
        return Scored(masses: answer(judgment, state.text).mapValues { KotlinDouble(value: $0) }, modelContext: nil, optionCriteria: nil)
    }
}

/// Counts requests: a source that is off must make none.
final class CountingSearchHelper: SearchHelper {
    var calls = 0
    let inner = FixtureSearchHelper()
    func currency(_ q: CurrencyQuery) async throws -> CurrencyResponse { calls += 1; return try await inner.currency(q) }
    func weather(_ q: WeatherQuery) async throws -> WeatherResponse { calls += 1; return try await inner.weather(q) }
    func trains(_ q: TrainsQuery) async throws -> TrainsResponse { calls += 1; return try await inner.trains(q) }
}

@MainActor
final class WebLibraryTests: XCTestCase {
    private func store() -> UserDefaults { UserDefaults(suiteName: "web.tests.\(UUID().uuidString)")! }

    private func library(_ helper: SearchHelper = FixtureSearchHelper(), laya: @escaping () async -> Backend? = { nil },
                         on: Bool = true) -> WebLibraryModel {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("web-\(UUID().uuidString)")
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let m = WebLibraryModel(helper: helper, fixtureMode: true, defaults: store(), laya: laya, ledger: LedgerService(home: dir))
        if on { for s in WebSector.allCases { m.setEnabled(s, true) } }
        return m
    }

    private func settle(_ m: WebLibraryModel, _ s: WebSector) async {
        for _ in 0..<200 {
            if let r = m.runs[s]?.ranking, case .running = r { try? await Task.sleep(nanoseconds: 20_000_000) } else { return }
        }
    }

    // MARK: Templates and variants

    func testEverySectorHasFourToSixVariantsInEnglishAndArabic() {
        for s in [WebSector.currency, .weather, .trains] {
            let vs = WebCatalog.variants(s)
            XCTAssertTrue((4...6).contains(vs.count), "\(s): \(vs.count)")
            XCTAssertGreaterThanOrEqual(Set(vs.map(\.type)).count, 3, "\(s) mixes decision types")
            for v in vs {
                XCTAssertTrue(v.id.hasPrefix(s.rawValue + "."))
                XCTAssertFalse(v.en.isEmpty)
                XCTAssertTrue(WebLibraryModel.isArabic(v.ar), v.id)
            }
        }
        XCTAssertEqual(Set(WebCatalog.variants.map(\.id)).count, WebCatalog.variants.count, "ids are unique")
    }

    func testEveryVariantPassesTheLintInBothLanguages() {
        let inputs = WebInputs()
        for v in WebCatalog.variants {
            let en = WebQuestion.shared.findings(question: v.question(inputs, lang: .en), type: v.type.kotlin, arabic: false)
            XCTAssertEqual(en, [], "\(v.id) EN: \(en)")
            let ar = WebQuestion.shared.findings(question: v.question(inputs, lang: .ar), type: v.type.kotlin, arabic: true)
            XCTAssertEqual(ar, [], "\(v.id) AR: \(ar)")
        }
    }

    func testOwnerExamplesAreVariants() {
        let en = WebCatalog.variants.map { $0.question(WebInputs(), lang: .en) }
        for q in ["Best day this week to convert EUR→EGP?", "Is Saturday dry enough for the trip to Alexandria?",
                  "Which day this week is best for the beach in Alexandria?", "Score each day for a run at 7am in Alexandria",
                  "Is the 08:15 likely delayed?", "Rank departures by reliability and arrival time"] {
            XCTAssertTrue(en.contains(q), q)
        }
    }

    // MARK: Custom question: authoring and lint for each decision type

    func testCustomQuestionCompilesForEachTypeWithNeutralOptions() {
        let m = library()
        m.choice[.weather] = .custom
        m.customText[.weather] = "Is this day warm enough to swim"
        for t in AnswerType.allCases {
            m.customType[.weather] = t
            XCTAssertEqual(m.customFindings(.weather), [], "\(t)")
            XCTAssertTrue(m.canAsk(.weather))
            let r = WebQuestion.shared.compile(question: m.plan(.weather).question, type: t.kotlin, arabic: false)
            guard let ready = r as? WebQuestionResultReady else { return XCTFail("\(t) refused") }
            let j = ready.judgment
            XCTAssertFalse(j.candidates.contains("yes") || j.candidates.contains("no"), "no bare yes/no")
            XCTAssertEqual(j.descriptions.count, j.candidates.count, "every option is described")
            // Score levels are written lowest first and flagged ordinal: Laya sends them highest first.
            XCTAssertEqual(j.ordinal, t == .score || t == .rank)
            if j.ordinal { XCTAssertEqual(j.candidates.first, "very poor") }
        }
    }

    func testCustomQuestionLintBlocksAsking() {
        let m = library()
        m.choice[.currency] = .custom
        for t in AnswerType.allCases {
            m.customType[.currency] = t
            for bad in ["Explain why the rate moved", "Write a note about the week", "Is it up? Is it down?", "Rate each day 1 to 10", ""] {
                m.customText[.currency] = bad
                XCTAssertFalse(m.customFindings(.currency).isEmpty, "\(t): \(bad)")
                XCTAssertFalse(m.canAsk(.currency), "\(t): \(bad)")
            }
        }
    }

    // MARK: Decoding the helper's schema v1

    func testFixturesDecodeWithRequiredAttribution() throws {
        let c = try JSONDecoder().decode(CurrencyResponse.self, from: FixtureSearchHelper.data("search-currency"))
        XCTAssertEqual(c.schema, 1); XCTAssertEqual(c.source, "frankfurter"); XCTAssertEqual(c.mode, "series")
        XCTAssertTrue(c.attribution.text.contains("Frankfurter") && c.attribution.text.contains("European Central Bank"))
        XCTAssertEqual(c.attribution.url, "https://frankfurter.dev")
        XCTAssertEqual(c.attribution.providersUrl, "https://api.frankfurter.dev/v2/providers")
        XCTAssertFalse(c.items.isEmpty)

        let w = try JSONDecoder().decode(WeatherResponse.self, from: FixtureSearchHelper.data("search-weather"))
        XCTAssertEqual(w.source, "open-meteo")
        XCTAssertEqual(w.attribution.text, "Weather data by Open-Meteo.com (CC BY 4.0)")
        XCTAssertEqual(w.attribution.url, "https://open-meteo.com/")
        XCTAssertEqual(w.items.count, 7)
        XCTAssertEqual(w.location.name, "Alexandria")

        let t = try JSONDecoder().decode(TrainsResponse.self, from: FixtureSearchHelper.data("search-uk-trains"))
        XCTAssertEqual(t.source, "national-rail-darwin")
        XCTAssertEqual(t.attribution.text, "Powered by National Rail Enquiries")
        XCTAssertEqual(t.attribution.url, "https://www.nationalrail.co.uk/")
        XCTAssertEqual(t.board.filterCrs, "RDG")
        XCTAssertTrue(t.items.contains { $0.isCancelled })
    }

    func testTheHelpersNullsAndAnUnreadableBody() throws {
        // A weather day with every optional null, and a train with no platform, as the helper may send.
        let day = #"{"date":"2026-09-26","weatherCode":null,"tempMax":null,"tempMin":null,"precipitationProbabilityMax":null,"precipitationSum":null,"sunrise":null,"sunset":null,"uvIndexMax":null}"#
        XCTAssertNoThrow(try JSONDecoder().decode(WeatherDay.self, from: Data(day.utf8)))
        XCTAssertThrowsError(try SearchDecoding.decode(CurrencyResponse.self, from: Data("{\"schema\":1}".utf8))) {
            XCTAssertEqual($0 as? HelperError, .unexpected("The helper sent a reply this version cannot read."))
        }
    }

    func testRequestsSendOnlyTheSearch() throws {
        let q = WebCatalog.weatherQuery(WebInputs())
        let r = try HelperEndpoint.providerRequest("weather", body: q, installId: "3b241101-e2bb-4255-8caf-4136c566a962")
        XCTAssertEqual(r.url?.path, "/v1/search/weather")
        XCTAssertNil(r.value(forHTTPHeaderField: "Authorization"))
        let body = try JSONSerialization.jsonObject(with: r.httpBody!) as! [String: Any]
        XCTAssertEqual(Set(body.keys), ["place", "days", "units"])
        let c = try JSONSerialization.jsonObject(with: JSONEncoder().encode(WebCatalog.currencyQuery(.ratePairDays(days: 7, window: 7), WebInputs()))) as! [String: Any]
        XCTAssertEqual(Set(c.keys), ["base", "symbols", "from", "to"])
        let t = try JSONSerialization.jsonObject(with: JSONEncoder().encode(WebCatalog.trainsQuery(WebInputs()))) as! [String: Any]
        XCTAssertEqual(t["crs"] as? String, "PAD"); XCTAssertEqual(t["filterCrs"] as? String, "RDG")
    }

    // MARK: NOT_CONFIGURED and not deployed

    func testErrorMappingForNewStates() {
        let nc = Data(#"{"error":{"code":"NOT_CONFIGURED","message":"x"}}"#.utf8)
        XCTAssertEqual(HelperError.from(status: 503, data: nc), .notConfigured)
        XCTAssertEqual(HelperError.from(status: 404, data: Data("Not Found".utf8)), .notDeployed)
        XCTAssertEqual(HelperError.from(status: 404, data: Data(#"{"error":{"code":"NO_RESULTS"}}"#.utf8)), .noResults)
    }

    func testNotConfiguredIsShownHonestlyAndNothingIsJudged() async {
        let m = library(FixtureSearchHelper(forced: .notConfigured, sources: [.weather]))
        await m.ask(.weather, online: true)
        XCTAssertEqual(m.phase[.weather], .failed(.notConfigured))
        XCTAssertNil(m.runs[.weather])
        XCTAssertTrue(WS.t("notConfigured.weather").contains("Open-Meteo plan — not set up yet"))
        await m.ask(.currency, online: true)          // other sources are unaffected
        XCTAssertEqual(m.phase[.currency], .done)
    }

    func testNotDeployedSaysNotAvailableYet() async {
        let m = library(FixtureSearchHelper(forced: .notDeployed))
        await m.ask(.trains, online: true)
        XCTAssertEqual(m.phase[.trains], .failed(.notDeployed))
        XCTAssertTrue(WS.t("notDeployed", ["sector": "UK trains"]).contains("not available yet"))
    }

    func testAnOffSourceMakesNoRequest() async {
        let helper = CountingSearchHelper()
        let m = library(helper, on: false)
        XCTAssertFalse(m.isEnabled(.currency), "off by default")
        XCTAssertFalse(m.canAsk(.currency))
        await m.ask(.currency, online: true)
        XCTAssertEqual(helper.calls, 0)
        m.setEnabled(.currency, true)
        await m.ask(.currency, online: true)
        XCTAssertEqual(helper.calls, 1)
    }

    // MARK: Rules, verifiability, and Laya

    func testCurrencyBestDayPicksTheHighestRateAndEveryRowIsVerifiable() async {
        let m = library()
        m.choice[.currency] = .variant("currency.bestDay")
        await m.ask(.currency, online: true)
        await settle(m, .currency)
        guard let run = m.runs[.currency] else { return XCTFail("no run") }
        XCTAssertEqual(run.ranking, .rulesOnly(.modelNotInstalled))
        let rates = run.rows.compactMap { r -> Double? in if case let .rate(_, _, _, v, _, _, _) = r.data { return v }; return nil }
        guard case let .rate(_, _, _, top, _, _, _) = run.rules[0].row.data else { return XCTFail() }
        XCTAssertEqual(top, rates.max())
        XCTAssertEqual(run.rules.map(\.rank), Array(1...run.rules.count))
        for r in run.rows {
            XCTAssertFalse(r.sourceItem.isEmpty)
            XCTAssertEqual(r.link?.host, "api.frankfurter.dev")
            XCTAssertTrue(r.link!.absoluteString.contains("date="))
        }
    }

    func testYesNoVariantsNameTheirTarget() async {
        let m = library()
        m.choice[.trains] = .variant("trains.delayedAt")
        await m.ask(.trains, online: true)
        guard let run = m.runs[.trains], let t = run.targetId else { return XCTFail("no target") }
        let row = run.rules.first { $0.row.id == t }!
        XCTAssertTrue(row.row.title.hasPrefix("08:15"))
        XCTAssertTrue(row.yes, "the 08:15 is running 16 min late in the fixture")
        XCTAssertEqual(run.rules.first?.row.link?.host, "www.nationalrail.co.uk")

        m.choice[.weather] = .variant("weather.saturdayDry")
        await m.ask(.weather, online: true)
        let w = m.runs[.weather]!
        XCTAssertEqual(w.targetId, "2026-09-26")
        XCTAssertTrue(w.rules.first { $0.row.id == "2026-09-26" }!.yes)
    }

    func testLayaAnswersOnThePhoneAndTheRulesStayAlongside() async {
        // Laya likes the rainiest day best: the opposite of the picnic rule.
        let backend = WebFakeBackend { j, text in
            let wet = text.contains("chance 80%")
            let levels = WebQuestion.shared.LEVELS
            return Dictionary(uniqueKeysWithValues: levels.enumerated().map { i, l in (l, i == (wet ? 4 : 1) ? 0.9 : 0.025) })
        }
        let m = library(laya: { backend })
        m.choice[.weather] = .variant("weather.picnic")
        await m.ask(.weather, online: true)
        await settle(m, .weather)
        guard let run = m.runs[.weather], let laya = run.laya else { return XCTFail("Laya did not answer") }
        XCTAssertEqual(run.ranking, .laya)
        XCTAssertEqual(backend.calls, run.rows.count)
        XCTAssertEqual(laya.first?.row.id, "2026-09-28")
        XCTAssertNotEqual(run.rules.first?.row.id, "2026-09-28")
        XCTAssertNotNil(WebLibraryModel.disagreement(run))
        XCTAssertTrue(m.answeredByLaya(.weather))
        m.showRules[.weather] = true
        XCTAssertEqual(m.shown(.weather).first?.row.id, run.rules.first?.row.id)
    }

    // MARK: Flights is development-only

    func testFlightsIsAbsentWithoutTheDevFlag() {
        XCTAssertFalse(WebBuild.sectors(flightsAllowed: false).contains(.flights))
        XCTAssertEqual(WebBuild.sectors(flightsAllowed: false), [.currency, .weather, .trains])
        XCTAssertTrue(WebBuild.sectors(flightsAllowed: true).contains(.flights))
        #if DEBUG
        XCTAssertTrue(WebBuild.flightsDevFlag, "Debug builds define LOUPE_FLIGHTS_DEV")
        #endif
    }

    func testOnlyTheDebugConfigurationDefinesTheFlightsFlag() throws {
        // project.yml is the one source of the build settings (XcodeGen). Release must not define it.
        let yml = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("project.yml")
        let text = try String(contentsOf: yml, encoding: .utf8)
        let lines = text.components(separatedBy: "\n").filter { $0.contains("LOUPE_FLIGHTS_DEV") && !$0.trimmingCharacters(in: .whitespaces).hasPrefix("#") }
        XCTAssertEqual(lines.count, 1, "defined exactly once")
        let debug = text.range(of: "        Debug:\n          SWIFT_ACTIVE_COMPILATION_CONDITIONS: \"$(inherited) DEBUG LOUPE_FLIGHTS_DEV\"")
        XCTAssertNotNil(debug, "under the app's Debug config")
        let release = text.components(separatedBy: "        Release:\n").dropFirst().first ?? ""
        XCTAssertFalse(release.prefix(400).contains("LOUPE_FLIGHTS_DEV"))
        XCTAssertTrue(release.prefix(400).contains("search-currency.json"), "fixtures never ship")
    }
}
