import Foundation
import LoupeKit

/// The latest saved results of the three checks (owner decision 2026-09-28: opening the app only LOADS and shows them;
/// nothing is re-run at launch). One file per check under `<home>/results/`: the summary as LoupeKit's
/// `ResultsCodec` writes it (lossless), and a small sidecar with what the app adds (when it was saved, which findings
/// were new, the online checks' status line). Written atomically on its own queue after each run and after each answer
/// the user gives on a result; read once at launch, off the main thread. A file that cannot be read is no result
/// (the screen says "not checked yet"), never a crash. Nothing here leaves the phone; "Delete all my Loupe data"
/// empties the home and with it these files.
final class ResultsStore: @unchecked Sendable {
    enum Check: String, CaseIterable { case watchers, privacy, mail }

    /// What the app keeps beside a summary.
    struct Meta: Codable, Equatable {
        var savedAt: Date
        /// Watchers: the findings the run that produced it raised for the first time (Now shows them first).
        var newKeys: [String]?
        /// Mail: the online checks' status line for that run.
        var onlineStatus: String?
        /// When the run that produced it finished.
        var ranAt: Date?
    }

    static let shared = ResultsStore(home: LedgerService.defaultHome())

    let dir: URL
    private let queue = DispatchQueue(label: "com.loupe-ai.ios.results", qos: .utility)

    init(home: URL) {
        dir = home.appendingPathComponent("results", isDirectory: true)
    }

    func file(_ c: Check) -> URL { dir.appendingPathComponent("\(c.rawValue).json") }
    func metaFile(_ c: Check) -> URL { dir.appendingPathComponent("\(c.rawValue).meta.json") }

    /// Writes [c]'s summary (encoded by [encode] on the store's queue, so a big summary never costs the main thread)
    /// and its sidecar. The summary first, then the sidecar, each atomically: a crash between leaves a readable pair.
    func save(_ c: Check, meta: Meta, encode: @escaping @Sendable () -> String) {
        queue.async { [self] in
            do {
                try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
                try Data(encode().utf8).write(to: file(c), options: [.atomic])
                try Self.encoder.encode(meta).write(to: metaFile(c), options: [.atomic])
            } catch {
                Log.run.error("results save failed: \(c.rawValue, privacy: .public) \(error.localizedDescription, privacy: .public)")
            }
        }
    }

    /// [c]'s saved summary text and sidecar, or nil. Blocking: call it off the main thread.
    func load(_ c: Check) -> (text: String, meta: Meta?)? {
        queue.sync {
            guard let data = try? Data(contentsOf: file(c)) else { return nil }
            let meta = (try? Data(contentsOf: metaFile(c))).flatMap { try? Self.decoder.decode(Meta.self, from: $0) }
            return (String(decoding: data, as: UTF8.self), meta)
        }
    }

    /// Removes every saved result (tests; the sample migration uses its own pass over the files).
    func removeAll() { queue.sync { try? FileManager.default.removeItem(at: dir) } }

    /// Waits for the writes queued so far (tests).
    func flush() { queue.sync {} }

    private static let encoder: JSONEncoder = {
        let e = JSONEncoder()
        e.dateEncodingStrategy = .secondsSince1970
        e.outputFormatting = [.sortedKeys]
        return e
    }()
    private static let decoder: JSONDecoder = {
        let d = JSONDecoder()
        d.dateDecodingStrategy = .secondsSince1970
        return d
    }()

    // MARK: Typed helpers (the services use these)

    func saveWatchers(_ s: WatcherSummary, meta: Meta) {
        let box = Box(s)
        save(.watchers, meta: meta) { ResultsCodec.shared.encodeWatchers(s: box.value) }
    }

    func savePrivacy(_ s: PrivacySummary, meta: Meta) {
        let box = Box(s)
        save(.privacy, meta: meta) { ResultsCodec.shared.encodePrivacy(s: box.value) }
    }

    func saveMail(_ s: MailSummary, meta: Meta) {
        let box = Box(s)
        save(.mail, meta: meta) { ResultsCodec.shared.encodeMail(s: box.value) }
    }

    func loadWatchers() -> (WatcherSummary, Meta?)? {
        guard let (text, meta) = load(.watchers), let s = ResultsCodec.shared.decodeWatchers(text: text) else { return nil }
        return (s, meta)
    }

    func loadPrivacy() -> (PrivacySummary, Meta?)? {
        guard let (text, meta) = load(.privacy), let s = ResultsCodec.shared.decodePrivacy(text: text) else { return nil }
        return (s, meta)
    }

    func loadMail() -> (MailSummary, Meta?)? {
        guard let (text, meta) = load(.mail), let s = ResultsCodec.shared.decodeMail(text: text) else { return nil }
        return (s, meta)
    }

    /// Kotlin objects are immutable and safe to read from any thread; this carries one across.
    struct Box<T>: @unchecked Sendable {
        let value: T
        init(_ value: T) { self.value = value }
    }
}
