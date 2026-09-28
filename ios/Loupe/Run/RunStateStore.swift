import Foundation

/// Where a stopped nightly run picks up (2026-09-28): the stages that ran to the end, the sources the sources stage
/// already read, and the counts so far, so the next night's run continues rather than starting over.
struct RunCheckpoint: Equatable, Codable {
    var runId: UUID
    var startedAt: Date
    var stagesDone: [RunStage]
    /// Source ids the sources stage finished in this run.
    var sourcesDone: [String]
    var counts: [RunStage: Int]
    var newFindings: RunFindings
}

/// The run's saved state, `<home>/run/state.json`: the last run, a short history, the nightly run's day and
/// checkpoint, and whether the first check after onboarding has run. In the home, so "Delete all my Loupe data"
/// resets it with everything else, and test and fixture homes never touch the phone's.
struct RunState: Equatable, Codable {
    var last: RunRecord?
    /// Newest first, at most `RunStateStore.historyLimit`.
    var history: [RunRecord] = []
    /// The calendar day (`yyyy-MM-dd`, the phone's time zone) of the last nightly attempt that did work.
    var nightlyDay: String?
    /// When that attempt started (no second run within 12 hours, across midnight too).
    var nightlyAt: Date?
    /// A nightly run iOS ended (or heat stopped) before its last stage.
    var checkpoint: RunCheckpoint?
    /// The one full check after onboarding has run (or started: it never runs twice).
    var firstCheckDone = false
    /// The last nightly run's record (the morning notification and Me's "Last night" line).
    var lastNightly: RunRecord?

    init() {}

    /// Every field optional on the way in, so a state written by an older or newer build still reads.
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        last = try? c.decodeIfPresent(RunRecord.self, forKey: .last)
        history = (try? c.decodeIfPresent([RunRecord].self, forKey: .history)) ?? []
        nightlyDay = try? c.decodeIfPresent(String.self, forKey: .nightlyDay)
        nightlyAt = try? c.decodeIfPresent(Date.self, forKey: .nightlyAt)
        checkpoint = try? c.decodeIfPresent(RunCheckpoint.self, forKey: .checkpoint)
        firstCheckDone = (try? c.decodeIfPresent(Bool.self, forKey: .firstCheckDone)) ?? false
        lastNightly = try? c.decodeIfPresent(RunRecord.self, forKey: .lastNightly)
    }
}

/// Reads and writes `RunState` atomically. Main-actor use only (the coordinator); the file is tiny.
final class RunStateStore {
    static let historyLimit = 14
    let file: URL

    init(home: URL) {
        file = home.appendingPathComponent("run", isDirectory: true).appendingPathComponent("state.json")
    }

    func load() -> RunState {
        guard let data = try? Data(contentsOf: file), let s = try? Self.decoder.decode(RunState.self, from: data) else { return RunState() }
        return s
    }

    func save(_ s: RunState) {
        do {
            try FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
            try Self.encoder.encode(s).write(to: file, options: [.atomic])
        } catch {
            Log.run.error("run state save failed: \(error.localizedDescription, privacy: .public)")
        }
    }

    /// Changes the state and saves it.
    @discardableResult
    func update(_ change: (inout RunState) -> Void) -> RunState {
        var s = load()
        change(&s)
        save(s)
        return s
    }

    private static let encoder: JSONEncoder = {
        let e = JSONEncoder()
        e.dateEncodingStrategy = .secondsSince1970
        e.outputFormatting = [.sortedKeys, .prettyPrinted]
        return e
    }()
    private static let decoder: JSONDecoder = {
        let d = JSONDecoder()
        d.dateDecodingStrategy = .secondsSince1970
        return d
    }()
}
