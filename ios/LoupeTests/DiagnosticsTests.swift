import XCTest
@testable import Loupe

/// The Diagnostics screen's math and its bundled fixture (no model needed).
final class DiagnosticsTests: XCTestCase {

    func testPercentilesInterpolateBetweenRanks() {
        XCTAssertNil(DiagnosticsMath.percentile([], 50))
        XCTAssertEqual(DiagnosticsMath.percentile([7], 95), 7)
        XCTAssertEqual(DiagnosticsMath.percentile([4, 1, 3, 2], 50), 2.5)
        XCTAssertEqual(DiagnosticsMath.percentile([1, 2, 3, 4], 95)!, 3.85, accuracy: 1e-12)
        XCTAssertEqual(DiagnosticsMath.percentile([1, 2, 3, 4], 0), 1)
        XCTAssertEqual(DiagnosticsMath.percentile([1, 2, 3, 4], 100), 4)
        let hundred = (1...100).map(Double.init)
        XCTAssertEqual(DiagnosticsMath.percentile(hundred, 50)!, 50.5, accuracy: 1e-12)
        XCTAssertEqual(DiagnosticsMath.percentile(hundred, 95)!, 95.05, accuracy: 1e-12)
    }

    func testArgmaxDeltaAndBuckets() {
        XCTAssertEqual(DiagnosticsMath.argmax([0.1, 0.7, 0.2]), 1)
        XCTAssertNil(DiagnosticsMath.argmax([]))
        XCTAssertNil(DiagnosticsMath.argmax([0.5, .nan]))
        XCTAssertEqual(DiagnosticsMath.maxAbsDelta([0.1, 0.9], [0.15, 0.85])!, 0.05, accuracy: 1e-12)
        XCTAssertNil(DiagnosticsMath.maxAbsDelta([0.1], [0.1, 0.9]))
        XCTAssertEqual(DiagnosticsMath.bucket(tokens: 17), "1–32")
        XCTAssertEqual(DiagnosticsMath.bucket(tokens: 64), "33–64")
        XCTAssertEqual(DiagnosticsMath.bucket(tokens: 65), "65–128")
        XCTAssertEqual(DiagnosticsMath.bucket(tokens: 1024), "513–1024")
    }

    func testBatteryDrainEstimate() {
        XCTAssertEqual(DiagnosticsMath.batteryDrainPerHour(start: 0.80, end: 0.75, seconds: 1800, charging: false)!, 10, accuracy: 1e-4)
        XCTAssertNil(DiagnosticsMath.batteryDrainPerHour(start: -1, end: 0.5, seconds: 1800, charging: false))
        XCTAssertNil(DiagnosticsMath.batteryDrainPerHour(start: 0.8, end: 0.7, seconds: 30, charging: false))
        XCTAssertNil(DiagnosticsMath.batteryDrainPerHour(start: 0.8, end: 0.7, seconds: 1800, charging: true))
    }

    func testReportCountsAgreementAndLatencyByBucket() throws {
        let cases = [
            DiagnosticsCase(set: "golden", id: "a", question: "q", state: "s", candidates: ["x", "y"], descriptions: [], tokens: 20, int8: [0.9, 0.1], torch: [0.9, 0.1]),
            DiagnosticsCase(set: "golden", id: "b", question: "q", state: "s", candidates: ["x", "y"], descriptions: [], tokens: 40, int8: [0.2, 0.8], torch: [0.2, 0.8]),
            DiagnosticsCase(set: "criteria", id: "c", question: "q", state: "s", candidates: ["x", "y"], descriptions: [], tokens: 300, int8: [0.6, 0.4], torch: [0.6, 0.4]),
        ]
        let measured: [String: CaseMeasurement] = [
            "golden/a": CaseMeasurement(probabilities: [0.89, 0.11], error: nil, latenciesMs: [10, 20]),
            "golden/b": CaseMeasurement(probabilities: [0.7, 0.3], error: nil, latenciesMs: [30]),
            "criteria/c": CaseMeasurement(probabilities: nil, error: "boom", latenciesMs: []),
        ]
        let r = DiagnosticsReport.build(
            cases: cases, measured: measured,
            device: .init(model: "iPhone16,1", system: "iOS 26.3", variant: "int8", appVersion: "0.1.0", lowPowerMode: false),
            passes: 2, durationSeconds: 3600, completed: true,
            memory: .init(startPhysFootprintBytes: 100, peakPhysFootprintBytes: 900),
            thermal: [ThermalSample(t: 0, state: "nominal"), ThermalSample(t: 5, state: "serious"), ThermalSample(t: 9, state: "fair")],
            battery: (0.9, 0.85, "unplugged", false), now: Date(timeIntervalSince1970: 0))
        XCTAssertEqual(r.agreement["golden"], .init(same: 1, total: 2, maxAbsDelta: 0.5, differing: ["b"]))
        XCTAssertEqual(r.agreement["criteria"]?.same, 0)
        XCTAssertEqual(r.cases[2].error, "boom")
        XCTAssertEqual(r.latency.map(\.bucket), ["1–32", "33–64"])
        XCTAssertEqual(r.latency[0].p50Ms, 15)
        XCTAssertEqual(r.latencyAll.samples, 3)
        XCTAssertEqual(r.latencyAll.p50Ms, 20)
        XCTAssertEqual(r.thermal.worst, "serious")
        XCTAssertEqual(r.thermal.end, "fair")
        XCTAssertEqual(r.battery.drainPercentPerHour!, 5, accuracy: 1e-4)
        XCTAssertEqual(r.generatedAt, "1970-01-01T00:00:00Z")
        let back = try JSONDecoder().decode(DiagnosticsReport.self, from: r.json())
        XCTAssertEqual(back, r, "the exported JSON round-trips")
    }

    /// The bundled expectations are the pinned JVM fixtures, unchanged (regenerate with
    /// ios/scripts/make-diagnostics-fixtures.py when those change).
    func testTheBundledFixtureMatchesTheJvmFixtures() throws {
        let fixture = try XCTUnwrap(DiagnosticsFixture.bundled(Bundle(for: LayaModel.self)), "DEBUG builds bundle laya-diagnostics.json")
        XCTAssertEqual(fixture.cases.filter { $0.set == "golden" }.count, 34)
        XCTAssertEqual(fixture.cases.filter { $0.set == "criteria" }.count, 8)
        // Reading the repo from the simulator can block on macOS's Documents-folder privacy prompt,
        // so the tie to the JVM fixtures is their SHA-256, recorded by the generator.
        XCTAssertEqual(fixture.sources["golden"], "7a8d3fd17a6c24635c8e3beb36491ab254045bc68a87075b4ebd2b354edb6b40")
        XCTAssertEqual(fixture.sources["criteria"], "b0afcae22de524b5fc1266bfdfc5a5f782b25cbff6e39f92eb734ab0a37372fe")
        XCTAssertTrue(fixture.cases.allSatisfy { $0.int8.count == $0.candidates.count && $0.tokens > 0 })
    }
}
