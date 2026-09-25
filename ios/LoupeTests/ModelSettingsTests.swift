import XCTest
import LoupeKit
@testable import Loupe

/// A settings source with its own in-memory store, recording the banner flags runs report.
@MainActor
final class FakeSettings: ModelSettingsSource {
    let store = EngineSettingsStore(directory: nil)
    var runs: [String: Bool] = [:]
    var current: EngineSettings { store.current }
    func recordRun(_ feature: String, layaOff: Bool) { runs[feature] = layaOff }
    func off(_ feature: String) -> FakeSettings { _ = store.setBool(key: "features.\(feature).use_laya", value: false); return self }
}

@MainActor
final class ModelSettingsTests: XCTestCase {
    private var home: URL!
    private let suite = "ModelSettingsTests-\(UUID().uuidString)"

    override func setUp() {
        home = FileManager.default.temporaryDirectory.appendingPathComponent("LoupeSettings-\(UUID().uuidString)")
        MS.lang = .en
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: home)
        UserDefaults.standard.removePersistentDomain(forName: suite)
        MS.lang = .en
    }

    private func model() -> (ModelSettingsModel, ModelSettingsService) {
        let service = ModelSettingsService(store: EngineSettingsStore(directory: home.path))
        return (ModelSettingsModel(service: service), service)
    }

    private func item(_ id: String, _ text: String) -> SourceItem {
        SourceItem(id: id, sourceId: "sample", kind: .text, path: "/s/\(id)", messageIndex: nil, name: id, text: text,
                   hasText: true, textTruncated: false, sizeBytes: Int64(text.count), contentHash: "h-\(id)",
                   mime: "text/plain", date: nil, dateOrigin: nil, email: nil, facts: [:], duplicateOf: nil)
    }

    // MARK: View model

    func testSectionsAreGlobalThenEveryPhoneFeature() {
        let (m, _) = model()
        XCTAssertEqual(m.sections.map(\.scope), ["global", "judgments", "scan", "email", "browser", "watchers", "flights", "game", "playground"])
        XCTAssertEqual(m.sections.first?.rows.map(\.name),
                       ["routing", "memory_mode", "idle_unload_min", "accept_confidence", "use_calibration", "rules_first",
                        "baseline_switch", "bias_correction", "text_chars_english", "text_chars_multilingual",
                                                   "cost_input_per_mtok", "cost_output_per_mtok", "cost_tokens_in", "cost_tokens_out"])
        XCTAssertEqual(m.rows("game").map(\.name), ["use_laya", "routing", "text_chars", "max_decisions_per_s"])
        XCTAssertEqual(m.rows("scan").map(\.name), ["use_laya", "routing", "text_chars", "read_content", "content_budget_s", "ocr", "ocr_max_pages"])
        XCTAssertTrue(m.sections.last!.rows.isEmpty, "the Playground is desktop-only")
    }

    func testRowsShowValueDefaultAndTradeOff() throws {
        let (m, _) = model()
        let mem = try XCTUnwrap(m.row("global.memory_mode"))
        XCTAssertEqual(mem.valueText, "Balanced")
        XCTAssertEqual(mem.defaultText, "Balanced")
        XCTAssertTrue(mem.isDefault)
        XCTAssertTrue(mem.tradeOff.contains("memory"))
        XCTAssertEqual(m.row("global.idle_unload_min")?.valueText, "10 min")
        XCTAssertEqual(m.row("global.accept_confidence")?.valueText, "Each feature's own rule")
        XCTAssertEqual(m.row("features.judgments.text_chars")?.valueText, "Built-in: 4,000 characters")
        XCTAssertEqual(m.row("features.flights.text_chars")?.valueText, "Built-in: 480 characters")
        XCTAssertEqual(m.row("features.game.max_decisions_per_s")?.valueText, "No cap")
        XCTAssertFalse(try XCTUnwrap(m.row("global.text_chars_english")).appliesOnPhone, "no English model on iPhone")
        XCTAssertFalse(try XCTUnwrap(m.row("features.browser.time_limit_s")).appliesOnPhone)
        XCTAssertTrue(try XCTUnwrap(m.row("features.scan.read_content")).appliesOnPhone)
        XCTAssertTrue(try XCTUnwrap(m.row("global.routing")).note!.contains("one model"))
        for section in m.sections { for row in section.rows { XCTAssertFalse(row.tradeOff.isEmpty, row.key); XCTAssertFalse(row.title.hasPrefix("fk."), row.key) } }
    }

    func testToggleResetAndRestoreAll() throws {
        let (m, service) = model()
        m.setBool("features.scan.use_laya", false)
        var row = try XCTUnwrap(m.row("features.scan.use_laya"))
        XCTAssertEqual(row.valueText, "Off")
        XCTAssertEqual(row.defaultText, "On")
        XCTAssertFalse(row.isDefault)
        XCTAssertEqual(service.notice, "Saved. Applies to the next run.")
        m.reset("features.scan.use_laya")
        row = try XCTUnwrap(m.row("features.scan.use_laya"))
        XCTAssertTrue(row.isDefault)

        m.setNumber("global.accept_confidence", 0.7)
        m.setTextChars("features.watchers.text_chars", mode: "custom", value: 50)
        XCTAssertEqual(m.row("features.watchers.text_chars")?.valueText, "100 characters", "clamped to Station's range")
        m.setChoice("features.flights.routing", "english")
        XCTAssertTrue(m.row("features.flights.routing")!.valueText.contains("not on iPhone"))
        XCTAssertTrue(m.anyChanged)
        m.restoreAll()
        XCTAssertFalse(m.anyChanged)
        XCTAssertEqual(service.notice, "All model settings are back to their defaults.")
    }

    func testMemoryChangesCallTheReloadHookAndPersist() {
        let (m, service) = model()
        var reloads: [String] = []
        service.onReload = { reloads.append($0.memoryMode) }
        m.setChoice("global.memory_mode", "low")
        m.setNumber("global.idle_unload_min", 3)
        m.setBool("features.game.use_laya", false)
        XCTAssertEqual(reloads, ["low", "low"], "memory_mode and idle_unload_min apply at once; use_laya does not reload")
        let reread = EngineSettingsStore(directory: home.path).current
        XCTAssertEqual(reread.memoryMode, "low")
        XCTAssertEqual(reread.idleUnloadMin, 3)
        XCTAssertFalse(reread.useLaya(feature: "game"))
        XCTAssertTrue(FileManager.default.fileExists(atPath: home.appendingPathComponent("engine_settings.json").path))
    }

    func testArabicWordingReusesStation() throws {
        MS.lang = .ar
        let (m, _) = model()
        XCTAssertEqual(MS.t("title"), "إعدادات النموذج")
        XCTAssertEqual(MS.t("reset"), "إعادة إلى الافتراضي")
        XCTAssertEqual(MS.direction, .rightToLeft)
        XCTAssertEqual(m.row("global.memory_mode")?.valueText, "متوازن")
        XCTAssertEqual(MS.t("banner.turnOn"), "شغّله")
        // Every key has both languages.
        for (key, pair) in MS.table { XCTAssertFalse(pair.0.isEmpty || pair.1.isEmpty, key) }
    }

    // MARK: Consumers: defaults are the old behaviour; use_laya off routes to rules and sets the banner flag

    func testJudgmentsSweepWithLayaOffUsesRulesWithoutTheModel() async throws {
        let settings = FakeSettings().off("judgments")
        let model = FakeModel(installed: false)
        let s = JudgmentsService(ledger: LedgerService(home: home), items: { [self.item("r1", "Receipt total paid"), self.item("n1", "hello, about the payment")] },
                                 model: model, settings: settings)
        s.load()
        guard case .success(let id) = s.useTemplate("is-receipt") else { return XCTFail("refused") }
        await s.startSweep(id)
        XCTAssertEqual(settings.runs["judgments"], true)
        let end = try XCTUnwrap(s.sweep)
        XCTAssertTrue(end.layaOff)
        XCTAssertEqual(model.fake.calls, 0, "Laya is never asked")
        XCTAssertTrue(s.rows.allSatisfy { ($0.resolvedBy as? ResolvedByMechanical)?.check == JudgmentSweep.companion.LAYA_OFF_CHECK })
        XCTAssertEqual(s.rows.count, 2)
    }

    func testJudgmentsSweepDefaultsAskLayaAsBefore() async {
        let settings = FakeSettings()
        let model = FakeModel(installed: true)
        let s = JudgmentsService(ledger: LedgerService(home: home), items: { [self.item("r1", "Receipt total paid"), self.item("n1", "hello, about the payment")] },
                                 model: model, settings: settings)
        s.load()
        guard case .success(let id) = s.useTemplate("is-receipt") else { return XCTFail("refused") }
        await s.startSweep(id)
        XCTAssertEqual(settings.runs["judgments"], false)
        XCTAssertEqual(model.fake.calls, 2)
        XCTAssertFalse(s.sweep!.layaOff)
    }

    func testWatchersWithLayaOffDoNotOpenTheModel() async {
        let settings = FakeSettings().off("watchers")
        let model = FakeModel(installed: true)
        let ledger = LedgerService(home: home)
        let w = WatchersService(ledger: ledger, items: { [self.item("p", "Passport. Date of expiry: 2026-11-01")] }, model: model,
                                seen: UserDefaults(suiteName: suite)!, settings: settings)
        await w.run()
        XCTAssertEqual(settings.runs["watchers"], true)
        XCTAssertEqual(model.fake.calls, 0)
        XCTAssertEqual(w.summary?.modelRan, false)
    }

    func testPrivacyAndMailRecordTheFlag() async {
        let settings = FakeSettings().off("scan").off("email")
        let ledger = LedgerService(home: home)
        let items = [item("keys.txt", "hello")]
        let p = PrivacyService(ledger: ledger, items: { items }, locator: NoLocator(), files: PrivacyFileActions(home: home),
                               photos: NoPhotos(), settings: settings)
        await p.run()
        XCTAssertEqual(settings.runs["scan"], true)
        let mail = MailTriageService(ledger: ledger, items: { items }, settings: settings)
        await mail.run()
        XCTAssertEqual(settings.runs["email"], true)
        XCTAssertEqual(settings.runs["browser"], false)
        _ = settings.store.reset(key: "features.scan.use_laya")
        await p.run()
        XCTAssertEqual(settings.runs["scan"], false, "back on: the banner goes")
    }

    func testGameWithLayaOffFliesTheBaseline() {
        let settings = FakeSettings().off("game")
        let game = GameController(mode: .watch, seed: 1, modelInstalled: { true }, settings: settings)
        guard case .baseline(let reason) = game.pilot else { return XCTFail("\(game.pilot)") }
        XCTAssertTrue(reason.contains("Laya is off"))
        XCTAssertEqual(settings.runs["game"], true)
        game.close()
    }

    func testDecisionsPerSecondCapThrottlesDispatch() {
        let backend = FakeGameBackend(preferring: "hold course")
        let exec = ManualExecutor()
        let decider = GameSessions.shared.modelDecider(backend: backend)
        let scheduler = PilotScheduler(decider: decider, executor: exec, returnToSimulation: { $0() })
        let session = GameSessions.shared.hosted(seed: 4, decider: decider, decisionInterval: 1)
        var clock: TimeInterval = 0
        scheduler.now = { clock }
        scheduler.maxPerSecond = { 2 }   // at most one every 0.5 s
        for _ in 0..<60 {
            clock += 1.0 / 60
            session.tick(); scheduler.afterTick()
            exec.runAll()
        }
        XCTAssertEqual(scheduler.dispatched, 2, "one second at 2 per second")
        scheduler.maxPerSecond = { nil }
        for _ in 0..<10 { clock += 1.0 / 60; session.tick(); scheduler.afterTick(); exec.runAll() }
        XCTAssertGreaterThan(scheduler.dispatched, 5, "no cap: as often as the session asks")
        session.close()
    }

    func testFlightsWithLayaOffRankByTheRules() async {
        let settings = FakeSettings().off("flights")
        var asked = false
        let m = WebModel(helper: FixtureFlightsHelper(), keys: MemoryKeyStore("k"), connectivity: Connectivity(start: false),
                         laya: { asked = true; return FakeJudgmentBackend() }, ledger: LedgerService(home: home), fixtureMode: true,
                         defaults: UserDefaults(suiteName: suite)!, settings: settings)
        m.form = .fixtureExample
        await m.search()
        XCTAssertEqual(m.ranking, .rulesOnly(.layaOff))
        XCTAssertFalse(asked)
        XCTAssertEqual(settings.runs["flights"], true)
    }
}

private struct NoLocator: PrivacyLocating {
    func access(for itemId: String) -> PrivacyAccess { .suggestOnly("test") }
}

private final class NoPhotos: PhotoDeleting {
    func delete(localId: String) async throws {}
}
