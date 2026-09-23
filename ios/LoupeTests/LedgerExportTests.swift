import XCTest
import LoupeKit
@testable import Loupe

/// The ledger on the phone (epic #7 child 1): Laya's decisions land in the file, survive a reopen,
/// and export (F4) as the desktop's four files, zipped, that parse back to the same rows.
final class LedgerExportTests: XCTestCase {
    private var home: URL!

    override func setUp() {
        home = FileManager.default.temporaryDirectory.appendingPathComponent("LedgerExportTests-\(UUID().uuidString)")
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: home)
    }

    private func layaRows() throws -> [LedgerRow] {
        let offers = Fixture.response.offers
        var table: [String: Double] = [:]
        for (i, o) in offers.enumerated() { table[o.totalAmount] = [0.97, 0.9, 0.7, 0.4, 0.1, 0.02][i % 6] }
        let run = try LayaRanker(backend: FakeBackend(table)).decide(offers, by: PriorityParser.parse("nonstop")) { _, _ in }
        XCTAssertEqual(run.rows.count, offers.count)
        return run.rows
    }

    func testLayaDecisionsAreModelRowsFromDuffel() throws {
        for row in try layaRows() {
            XCTAssertEqual(row.resolvedBy.code, "model")
            XCTAssertEqual(row.judgmentId, FlightPriorities.shared.ID)
            XCTAssertTrue(row.itemId?.hasPrefix("web:duffel:off_") == true, row.itemId ?? "nil")
            XCTAssertEqual(row.propensity, 1.0)
            XCTAssertEqual(row.distribution.labels, ["fits", "does not fit"])
        }
    }

    func testRecordedRowsPersistAndCount() throws {
        let rows = try layaRows()
        let service = LedgerService(home: home)
        service.record(rows)
        service.flush()
        XCTAssertEqual(service.stats()?.decisions, Int32(rows.count))
        XCTAssertEqual(service.stats()?.modelRows, Int32(rows.count))
        XCTAssertEqual(service.rows(judgmentId: FlightPriorities.shared.ID).count, rows.count)
        let file = home.appendingPathComponent("ledger.jsonl")
        let lines = try String(contentsOf: file, encoding: .utf8).split(separator: "\n")
        XCTAssertEqual(lines.count, rows.count)
        // A second open reads the same history back.
        let reopened = try PhoneLedger.companion.open(home: home.path)
        XCTAssertEqual(reopened.rows(), rows)
    }

    func testExportCreatesAZipOfTheLosslessFilesThatRoundTrips() async throws {
        let rows = try layaRows()
        let service = LedgerService(home: home)
        service.record(rows)
        let zip = try await service.export(now: Date(timeIntervalSince1970: 1_790_000_000))
        XCTAssertEqual(zip.pathExtension, "zip")
        XCTAssertTrue(zip.lastPathComponent.hasPrefix("Loupe-export-"))
        let size = try XCTUnwrap(try FileManager.default.attributesOfItem(atPath: zip.path)[.size] as? NSNumber)
        XCTAssertGreaterThan(size.intValue, 0)
        let zipBytes = try Data(contentsOf: zip)
        XCTAssertEqual(Array(zipBytes.prefix(2)), [0x50, 0x4B]) // "PK"

        // The folder the zip was made from holds the desktop's four files.
        let dir = zip.deletingPathExtension()
        let names = try FileManager.default.contentsOfDirectory(atPath: dir.path).sorted()
        XCTAssertEqual(names, ["loupe-calibration.json", "loupe-corrections.jsonl", "loupe-judgments.json", "loupe-ledger.jsonl"])
        let text = try String(contentsOf: dir.appendingPathComponent("loupe-ledger.jsonl"), encoding: .utf8)
        let parsed = try text.split(separator: "\n").map { try PhoneLedger.companion.parseRow(line: String($0)) }
        XCTAssertEqual(parsed, rows)
        XCTAssertTrue(text.contains("\"propensity\":1.0"))
        XCTAssertTrue(text.contains("\"resolvedBy\":\"model\""))
        let calibration = try String(contentsOf: dir.appendingPathComponent("loupe-calibration.json"), encoding: .utf8)
        XCTAssertTrue(calibration.contains("\"judgmentId\":\"web.flights.fit\""), calibration)
    }
}
