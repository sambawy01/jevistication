import Foundation

// Device verification (epic #7 child 9; docs/BUILD.md milestone 1, risk 13). Pure data and math:
// the Diagnostics screen measures, this turns measurements into the report the owner hands back.

/// One pinned parity question, as bundled in `Resources/Diagnostics/laya-diagnostics.json`
/// (generated from backend-onnx's golden/criteria fixtures by ios/scripts/make-diagnostics-fixtures.py).
struct DiagnosticsCase: Codable, Equatable {
    let set: String
    let id: String
    let question: String
    let state: String
    let candidates: [String]
    let descriptions: [String]
    let tokens: Int
    /// The JVM's INT8 probabilities (ORT CPU, same graph): what the phone must reproduce.
    let int8: [Double]
    let torch: [Double]
}

struct DiagnosticsFixture: Codable, Equatable {
    let checkpoint: String
    let graphSha256: String
    let sources: [String: String]
    let cases: [DiagnosticsCase]

    static func bundled(_ bundle: Bundle = .main) -> DiagnosticsFixture? {
        guard let url = bundle.url(forResource: "laya-diagnostics", withExtension: "json"),
              let data = try? Data(contentsOf: url) else { return nil }
        return try? JSONDecoder().decode(DiagnosticsFixture.self, from: data)
    }
}

enum DiagnosticsMath {
    /// Linear interpolation between closest ranks (the R-7 / NumPy default). `p` in 0...100.
    static func percentile(_ values: [Double], _ p: Double) -> Double? {
        guard !values.isEmpty else { return nil }
        let s = values.sorted()
        let h = (Double(s.count - 1)) * min(max(p, 0), 100) / 100
        let lo = Int(h.rounded(.down))
        let hi = min(lo + 1, s.count - 1)
        return s[lo] + (h - Double(lo)) * (s[hi] - s[lo])
    }

    static func argmax(_ xs: [Double]) -> Int? {
        guard !xs.isEmpty, xs.allSatisfy({ !$0.isNaN }) else { return nil }
        return xs.indices.max { xs[$0] < xs[$1] }
    }

    static func maxAbsDelta(_ a: [Double], _ b: [Double]) -> Double? {
        guard a.count == b.count, !a.isEmpty else { return nil }
        return zip(a, b).map { abs($0 - $1) }.max()
    }

    /// Token-length buckets for latency (Laya's cost scales with tokens; 1,024 is the cap).
    static let buckets: [(label: String, upTo: Int)] = [("1–32", 32), ("33–64", 64), ("65–128", 128), ("129–256", 256), ("257–512", 512), ("513–1024", Int.max)]

    static func bucket(tokens: Int) -> String { buckets.first { tokens <= $0.upTo }!.label }

    /// Percentage points per hour, from two battery readings (UIDevice levels 0...1, -1 = unknown).
    /// Nil when either reading is unknown, the run was under a minute, or the phone was charging.
    static func batteryDrainPerHour(start: Float, end: Float, seconds: Double, charging: Bool) -> Double? {
        guard start >= 0, end >= 0, seconds >= 60, !charging else { return nil }
        return Double(start - end) * 100 / (seconds / 3600)
    }
}

/// What the run measured for one case (all passes).
struct CaseMeasurement: Equatable {
    var probabilities: [Double]?
    var error: String?
    var latenciesMs: [Double]
}

struct ThermalSample: Codable, Equatable {
    let t: Double          // seconds since start
    let state: String      // nominal / fair / serious / critical
}

struct DiagnosticsReport: Codable, Equatable {
    struct Device: Codable, Equatable {
        let model: String
        let system: String
        let variant: String
        let appVersion: String
        let lowPowerMode: Bool
    }

    struct CaseRow: Codable, Equatable {
        let set: String
        let id: String
        let tokens: Int
        let expectedAnswer: String
        let answer: String?
        let agreesWithJvm: Bool
        let maxAbsDeltaVsJvm: Double?
        let latenciesMs: [Double]
        let error: String?
    }

    struct Agreement: Codable, Equatable {
        let same: Int
        let total: Int
        let maxAbsDelta: Double?
        let differing: [String]
    }

    struct LatencyRow: Codable, Equatable {
        let bucket: String
        let samples: Int
        let p50Ms: Double?
        let p95Ms: Double?
        let maxMs: Double?
    }

    struct Memory: Codable, Equatable {
        let startPhysFootprintBytes: UInt64
        let peakPhysFootprintBytes: UInt64
    }

    struct Thermal: Codable, Equatable {
        let start: String
        let end: String
        let worst: String
        let samples: [ThermalSample]
    }

    struct Battery: Codable, Equatable {
        let startLevel: Float
        let endLevel: Float
        let state: String
        let drainPercentPerHour: Double?
        let note: String
    }

    var schema = 1
    let generatedAt: String
    let device: Device
    let passes: Int
    let durationSeconds: Double
    let completed: Bool
    let agreement: [String: Agreement]
    let latency: [LatencyRow]
    let latencyAll: LatencyRow
    let memory: Memory
    let thermal: Thermal
    let battery: Battery
    let cases: [CaseRow]

    static let thermalOrder = ["nominal", "fair", "serious", "critical"]

    /// Builds the report from the fixture and what was measured, in fixture order.
    static func build(cases: [DiagnosticsCase], measured: [String: CaseMeasurement], device: Device, passes: Int,
                      durationSeconds: Double, completed: Bool, memory: Memory, thermal samples: [ThermalSample],
                      battery: (start: Float, end: Float, state: String, charging: Bool),
                      now: Date = Date()) -> DiagnosticsReport {
        var rows: [CaseRow] = []
        for c in cases {
            let m = measured["\(c.set)/\(c.id)"]
            let expected = DiagnosticsMath.argmax(c.int8).map { c.candidates[$0] } ?? "?"
            let got = m?.probabilities.flatMap(DiagnosticsMath.argmax).map { c.candidates[$0] }
            rows.append(CaseRow(set: c.set, id: c.id, tokens: c.tokens, expectedAnswer: expected, answer: got,
                                agreesWithJvm: got != nil && got == expected,
                                maxAbsDeltaVsJvm: m?.probabilities.flatMap { DiagnosticsMath.maxAbsDelta($0, c.int8) },
                                latenciesMs: m?.latenciesMs ?? [], error: m?.error ?? (m == nil ? "not run" : nil)))
        }
        var agreement: [String: Agreement] = [:]
        for set in Array(Set(rows.map(\.set))).sorted() {
            let r = rows.filter { $0.set == set }
            agreement[set] = Agreement(same: r.filter(\.agreesWithJvm).count, total: r.count,
                                       maxAbsDelta: r.compactMap(\.maxAbsDeltaVsJvm).max(),
                                       differing: r.filter { !$0.agreesWithJvm }.map(\.id))
        }
        func latencyRow(_ label: String, _ xs: [Double]) -> LatencyRow {
            LatencyRow(bucket: label, samples: xs.count, p50Ms: DiagnosticsMath.percentile(xs, 50),
                       p95Ms: DiagnosticsMath.percentile(xs, 95), maxMs: xs.max())
        }
        let latency = DiagnosticsMath.buckets.compactMap { b -> LatencyRow? in
            let xs = rows.filter { DiagnosticsMath.bucket(tokens: $0.tokens) == b.label }.flatMap(\.latenciesMs)
            return xs.isEmpty ? nil : latencyRow(b.label, xs)
        }
        let rank: (String) -> Int = { thermalOrder.firstIndex(of: $0) ?? 0 }
        let worst = samples.map(\.state).max { rank($0) < rank($1) } ?? "unknown"
        let drain = DiagnosticsMath.batteryDrainPerHour(start: battery.start, end: battery.end, seconds: durationSeconds, charging: battery.charging)
        let note = drain != nil
            ? "Estimate from two battery readings (1% resolution); run 10+ minutes unplugged for a usable number."
            : "No estimate: battery level unknown, the phone was charging, or the run was under a minute."
        let iso = ISO8601DateFormatter()
        return DiagnosticsReport(generatedAt: iso.string(from: now), device: device, passes: passes,
                                 durationSeconds: durationSeconds, completed: completed, agreement: agreement,
                                 latency: latency, latencyAll: latencyRow("all", rows.flatMap(\.latenciesMs)),
                                 memory: memory,
                                 thermal: Thermal(start: samples.first?.state ?? "unknown", end: samples.last?.state ?? "unknown",
                                                  worst: worst, samples: samples),
                                 battery: Battery(startLevel: battery.start, endLevel: battery.end, state: battery.state,
                                                  drainPercentPerHour: drain, note: note),
                                 cases: rows)
    }

    func json() throws -> Data {
        let e = JSONEncoder()
        e.outputFormatting = [.prettyPrinted, .sortedKeys]
        return try e.encode(self)
    }
}
