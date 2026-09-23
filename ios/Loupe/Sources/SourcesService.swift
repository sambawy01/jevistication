import Foundation
import LoupeKit

/// Sources on this iPhone (epic #7 child 2). Today that is the bundled synthetic sample — labelled
/// "Sample data — not from your phone" wherever it appears — read by LoupeKit's common scanner with
/// PDFKit/ImageIO as its platform readers, off the main thread, with progress. Results are cached
/// through LoupeKit's `SourceLibrary` (Application Support/Loupe/sources) so Judgments and the Now
/// watchers read items without rescanning. Phone sources (Photos, Files, Mail, …) come in child 7.
@MainActor
final class SourcesService: ObservableObject {
    static let sampleId = "sample"
    static let sampleLabel = "Sample data — not from your phone"

    static let shared = SourcesService(home: LedgerService.defaultHome(), sampleRoot: SourcesService.bundledSample())

    struct Progress: Equatable {
        let seen: Int
        let total: Int
        let current: String
        var fraction: Double { total == 0 ? 0 : Double(seen) / Double(total) }
    }

    @Published private(set) var sampleEnabled = true
    @Published private(set) var sampleScan: CachedScan?
    @Published private(set) var progress: Progress?
    @Published private(set) var problem: String?

    var scanning: Bool { progress != nil }

    private let library: SourceLibrary?
    private let sampleRoot: URL?
    private let queue = DispatchQueue(label: "dev.loupe.sources", qos: .utility)
    private var started = false

    init(home: URL, sampleRoot: URL?) {
        self.sampleRoot = sampleRoot
        do {
            try FileManager.default.createDirectory(at: home, withIntermediateDirectories: true)
            library = try SourceLibrary(home: home.path)
        } catch {
            library = nil
            problem = "The sources cache could not be opened: \(error.localizedDescription)"
        }
        if let library {
            sampleEnabled = library.isEnabled(sourceId: Self.sampleId, default: true)
            sampleScan = library.cached(sourceId: Self.sampleId)
        }
    }

    /// The synthetic sample shipped in the app bundle (the desktop's sample resources, verbatim).
    nonisolated static func bundledSample() -> URL? {
        Bundle.main.url(forResource: "sample", withExtension: nil)
    }

    /// Scans the sample once if it is on and has never been scanned. Called when the app appears.
    func start() {
        guard !started else { return }
        started = true
        if sampleEnabled && sampleScan == nil { scanSample() }
    }

    /// The two roots of the sample: the documents folder and the mail export, with stable ids.
    nonisolated static func sampleRoots(_ root: URL) -> [SourceRoot] {
        [SourceRoot(id: sampleId, type: .folder, path: root.appendingPathComponent("documents").path, idPrefix: "sample:documents/"),
         SourceRoot(id: sampleId, type: .mailExport, path: root.appendingPathComponent("mail").path, idPrefix: "sample:mail/")]
    }

    func scanSample() {
        guard !scanning, let library else { return }
        guard let root = sampleRoot else {
            problem = "The sample data is missing from this build."
            return
        }
        progress = Progress(seen: 0, total: 0, current: "")
        problem = nil
        let observer = Observer { [weak self] p in
            Task { @MainActor in
                guard let self, self.progress != nil else { return }
                self.progress = Progress(seen: Int(p.filesSeen), total: Int(p.filesTotal), current: p.current)
            }
        }
        queue.async {
            let outcome = Result { () -> CachedScan in
                let result = try SourceScanner(extractors: AppleExtractors()).scan(sources: Self.sampleRoots(root), observer: observer)
                return try library.store(sourceId: Self.sampleId, result: result)
            }
            Task { @MainActor in
                self.progress = nil
                switch outcome {
                case .success(let scan): self.sampleScan = scan
                case .failure(let error): self.problem = "Scan failed: \(error.localizedDescription)"
                }
            }
        }
    }

    func setSampleEnabled(_ on: Bool) {
        guard let library else { return }
        do {
            try library.setEnabled(sourceId: Self.sampleId, enabled: on)
            sampleEnabled = on
            if on && sampleScan == nil { scanSample() }
        } catch {
            problem = "Could not save the setting: \(error.localizedDescription)"
        }
    }

    /// Items of every source that is on — what Judgments and the Now watchers read.
    func items() -> [SourceItem] {
        library?.items(sourceIds: [Self.sampleId], defaultEnabled: true) ?? []
    }

    /// Waits for a queued scan to finish on the background queue (tests).
    nonisolated func flush() { queue.sync {} }

    private final class Observer: NSObject, ScanObserver {
        let onProgress: (ScanProgress) -> Void
        init(_ onProgress: @escaping (ScanProgress) -> Void) { self.onProgress = onProgress }
        func onProgress(progress: ScanProgress) { onProgress(progress) }
        func isCancelled() -> Bool { false }
    }
}
