import XCTest
import LoupeKit
@testable import Loupe

/// "Delete all my Loupe data" (audit P1-4, 2026-09-27): the storage part over throwaway folders, a settings suite
/// and a fake Keychain, the ledger's erase-and-reopen, and the services' in-memory resets.
@MainActor
final class DataEraserTests: XCTestCase {
    private var root: URL!
    private var home: URL!
    private var group: URL!
    private let suite = "DataEraserTests-\(UUID().uuidString)"
    private var defaults: UserDefaults!

    private final class FakeKeychain: KeychainWiping {
        var items: Set<String> = Set(DataEraser.keychainServices)
        var refuse: Set<String> = []
        func deleteAll(service: String) -> Bool {
            if refuse.contains(service) { return false }
            items.remove(service)
            return true
        }
    }

    override func setUp() {
        root = FileManager.default.temporaryDirectory.appendingPathComponent("LoupeErase-\(UUID().uuidString)")
        home = root.appendingPathComponent("Loupe", isDirectory: true)
        group = root.appendingPathComponent("group", isDirectory: true)
        defaults = UserDefaults(suiteName: suite)
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: root)
        defaults.removePersistentDomain(forName: suite)
    }

    private func write(_ url: URL, _ text: String = "x") {
        try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try? Data(text.utf8).write(to: url)
    }

    private func exists(_ url: URL) -> Bool { FileManager.default.fileExists(atPath: url.path) }

    /// A home as the app leaves it: the ledger, judgments, the review queue, source caches, bookmarks, mail, the
    /// held privacy copies, settings, and the model's folders; the App Group's Spotted log, recent checks, lists.
    private func seed() {
        write(home.appendingPathComponent("ledger/decisions.jsonl"))
        write(home.appendingPathComponent("loupe-judgments.json"))
        write(home.appendingPathComponent("corrections.jsonl"))
        write(home.appendingPathComponent("review/items.json"))
        write(home.appendingPathComponent("sources/bookmarks.json"))
        write(home.appendingPathComponent("sources/enabled.json"))
        write(home.appendingPathComponent("mail/1.eml"))
        write(home.appendingPathComponent("privacy-held/keys.txt"))
        write(home.appendingPathComponent("engine-settings.json"))
        write(home.appendingPathComponent("laya-multilingual/model.onnx"))
        write(home.appendingPathComponent("laya-download/model.onnx.part"))
        write(group.appendingPathComponent("protection/spotted.json"))
        write(group.appendingPathComponent("protection/recent-checks.json"))
        write(group.appendingPathComponent("online-phishing/feeds/openphish.txt"))
        write(group.appendingPathComponent("SharedInbox/link.txt"))
        write(group.appendingPathComponent("Library/Preferences/other.plist"))
        defaults.set(true, forKey: "onboarding.permissions.done")
        defaults.set("robot", forKey: "mascot.kind")
        defaults.set("int8", forKey: "laya.variant")
        defaults.set(Data("{}".utf8), forKey: "laya.download.consent.v1")
    }

    private func plan(keepModel: Bool, keychain: FakeKeychain) -> DataEraser.Plan {
        DataEraser.Plan(homes: [home], group: group, defaults: [(defaults, suite)], keepModel: keepModel, keychain: keychain)
    }

    func testEverythingGoesButTheModelWhenItIsKept() throws {
        seed()
        let keychain = FakeKeychain()
        let report = DataEraser.erase(plan(keepModel: true, keychain: keychain))
        XCTAssertTrue(report.ok, "\(report)")
        let left = try FileManager.default.contentsOfDirectory(atPath: home.path).sorted()
        XCTAssertEqual(left, ["laya-download", "laya-multilingual"], "only the model's folders stay")
        XCTAssertTrue(exists(home.appendingPathComponent("laya-multilingual/model.onnx")))
        for gone in ["protection", "online-phishing", "SharedInbox"] {
            XCTAssertFalse(exists(group.appendingPathComponent(gone)), gone)
        }
        XCTAssertTrue(exists(group.appendingPathComponent("Library/Preferences/other.plist")), "only Loupe's own group folders go")
        XCTAssertNil(defaults.object(forKey: "onboarding.permissions.done"))
        XCTAssertNil(defaults.object(forKey: "mascot.kind"))
        XCTAssertEqual(defaults.string(forKey: "laya.variant"), "int8", "the model's own settings stay with it")
        XCTAssertNotNil(defaults.data(forKey: "laya.download.consent.v1"))
        XCTAssertTrue(keychain.items.isEmpty, "every Loupe Keychain service is emptied")
    }

    func testTheModelGoesTooWhenNotKept() throws {
        seed()
        let keychain = FakeKeychain()
        let report = DataEraser.erase(plan(keepModel: false, keychain: keychain))
        XCTAssertTrue(report.ok)
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: home.path), [])
        XCTAssertNil(defaults.object(forKey: "laya.variant"))
        XCTAssertNil(defaults.object(forKey: "laya.download.consent.v1"))
    }

    func testAKeychainFailureIsReported() {
        seed()
        let keychain = FakeKeychain()
        keychain.refuse = ["com.loupe-ai.ios.mail"]
        let report = DataEraser.erase(plan(keepModel: true, keychain: keychain))
        XCTAssertFalse(report.ok)
        XCTAssertEqual(report.keychainFailed, ["com.loupe-ai.ios.mail"])
    }

    func testWithoutAnAppGroupTheGroupFoldersAreInHome() throws {
        write(home.appendingPathComponent("protection/spotted.json"))
        write(home.appendingPathComponent("laya-multilingual/model.onnx"))
        let p = DataEraser.Plan(homes: [home, home], group: home, defaults: [], keepModel: true, keychain: FakeKeychain())
        XCTAssertTrue(DataEraser.erase(p).ok)
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: home.path), ["laya-multilingual"])
    }

    func testTheKeychainServicesCoverEveryStore() {
        // Every KeychainStore the app makes writes under one of these services.
        XCTAssertEqual(Set(DataEraser.keychainServices), ["com.loupe-ai.ios.mail", "com.loupe-ai.ios.assistant",
                                                          "com.loupe-ai.ios.safebrowsing", "com.loupe-ai.ios.duffel"])
        XCTAssertEqual(KeychainStore().service, "com.loupe-ai.ios.duffel")
    }

    func testTheConfirmationWord() {
        XCTAssertFalse(DeleteDataView.confirmed(""))
        XCTAssertFalse(DeleteDataView.confirmed("DEL"))
        XCTAssertFalse(DeleteDataView.confirmed("delete me"))
        XCTAssertTrue(DeleteDataView.confirmed("DELETE"))
        XCTAssertTrue(DeleteDataView.confirmed(" delete "))
    }

    // MARK: The services

    func testTheLedgerIsEmptyAfterEraseAndStillWrites() async throws {
        let ledgerHome = root.appendingPathComponent("ledger-home", isDirectory: true)
        let ledger = LedgerService(home: ledgerHome)
        let settings = FakeSettings()
        let s = JudgmentsService(ledger: ledger, items: { [] }, model: FakeModel(installed: false), settings: settings)
        s.load()
        guard case .success = s.useTemplate("tax-receipt") else { return XCTFail("refused") }
        ledger.recordCorrection(CorrectionRecord(judgmentId: "j-tax-receipt", criteriaHash: "h", itemId: "i", label: "x", at: "2026-09-27T00:00:00Z", confirmed: false))
        ledger.flush()
        XCTAssertFalse(ledger.correctionIndex().isEmpty)

        let report = ledger.eraseAndReopen {
            DataEraser.erase(DataEraser.Plan(homes: [ledgerHome], group: nil, defaults: [], keepModel: false, keychain: FakeKeychain()))
        }
        XCTAssertTrue(report.ok)
        XCTAssertTrue(ledger.correctionIndex().isEmpty)
        XCTAssertTrue(try ledger.judgments().isEmpty)
        s.reloadAfterErase()
        XCTAssertTrue(s.judgments.isEmpty)
        XCTAssertTrue(s.rows.isEmpty)
        // The reopened store takes new writes.
        guard case .success = s.useTemplate("tax-receipt") else { return XCTFail("refused after erase") }
        ledger.flush()
        XCTAssertEqual(try ledger.judgments().count, 1)
    }

    func testTheWatchersForgetTheirLastRun() async {
        let seenSuite = "DataEraserTests-seen-\(UUID().uuidString)"
        let seen = UserDefaults(suiteName: seenSuite)!
        defer { seen.removePersistentDomain(forName: seenSuite) }
        let item = SourceItem(id: "p", sourceId: "sample", kind: .text, path: "/s/p", messageIndex: nil, name: "passport.txt",
                              text: "Passport. Date of expiry: 2026-11-01", hasText: true, textTruncated: false, sizeBytes: 10,
                              contentHash: "h-p", mime: "text/plain", date: nil, dateOrigin: nil, email: nil, facts: [:], duplicateOf: nil)
        let w = WatchersService(ledger: LedgerService(home: root.appendingPathComponent("w", isDirectory: true)), items: { [item] },
                                model: FakeModel(installed: false), seen: seen, settings: FakeSettings())
        await w.run()
        XCTAssertNotNil(w.summary)
        XCTAssertNotNil(seen.stringArray(forKey: "watchers.seenKeys"))
        w.forgetAfterErase()
        XCTAssertNil(w.summary)
        XCTAssertNil(w.lastRun)
        XCTAssertEqual(w.newCount, 0)
        XCTAssertNil(seen.stringArray(forKey: "watchers.seenKeys"))
    }
}
