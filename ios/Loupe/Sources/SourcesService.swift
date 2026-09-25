import Foundation
import LoupeKit

/// Sources on this iPhone (epic #7 child 2). Today that is the bundled synthetic sample — labelled
/// "Sample data — not from your phone" wherever it appears — read by LoupeKit's common scanner with
/// PDFKit/ImageIO as its platform readers, off the main thread, with progress. Results are cached
/// through LoupeKit's `SourceLibrary` (Application Support/Loupe/sources) so Judgments and the Now
/// watchers read items without rescanning. The phone's own sources (Photos, Files, Calendar,
/// Contacts, Mail — child 7) live in `SourcesService+Phone.swift` and store into the same cache.
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
    /// Each phone source's row: on/off, permission, count, last scan, error (child 7).
    @Published var phone: [PhoneSource: PhoneSourceState] = [:]
    /// Bumped whenever any source's items change (a scan lands, a source is switched), so Now re-runs
    /// the watchers.
    @Published var revision = 0
    /// The Inbox's imports, newest first (epic #7 child 15).
    @Published var inboxBatches: [InboxBatch] = []
    @Published var inboxBusy = false
    @Published var inboxProblem: String?

    var scanning: Bool { progress != nil || phone.values.contains { $0.scanning } }

    let library: SourceLibrary?
    /// Imported CSVs, mail files, ZIP archives and shared text (child 15), in LoupeKit.
    let inbox: Inbox?
    let home: URL
    let deps: PhoneDependencies
    private let sampleRoot: URL?
    let queue = DispatchQueue(label: "com.loupe-ai.ios.sources", qos: .utility)
    private var started = false

    convenience init(home: URL, sampleRoot: URL?) {
        self.init(home: home, sampleRoot: sampleRoot, deps: .live(home: home))
    }

    init(home: URL, sampleRoot: URL?, deps: PhoneDependencies) {
        self.home = home
        self.deps = deps
        self.sampleRoot = sampleRoot
        do {
            try FileManager.default.createDirectory(at: home, withIntermediateDirectories: true)
            library = try SourceLibrary(home: home.path)
        } catch {
            library = nil
            problem = "The sources cache could not be opened: \(error.localizedDescription)"
        }
        inbox = library == nil ? nil : try? Inbox(home: home.path, extractors: AppleExtractors.live())
        inboxBatches = inbox?.batches() ?? []
        if let library {
            sampleEnabled = library.isEnabled(sourceId: Self.sampleId, default: true)
            sampleScan = library.cached(sourceId: Self.sampleId)
        }
        loadPhoneStates()
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
        refreshOnOpen()
        Task { await collectShared() }
        #if DEBUG
        if LaunchOptions.current.inboxDemo { Task { await seedInboxDemo() } }
        #endif
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
        let run = SourceScanRun(source: "sample")
        let observer = Observer { [weak self] p in
            run.scanned(p)
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
                case .success(let scan):
                    self.sampleScan = scan; self.revision += 1
                    run.ocrCount(Self.ocrItems(scan.result))
                    run.finish(items: scan.result.items.count, skipped: scan.result.skipped.count)
                case .failure(let error):
                    self.problem = "Scan failed: \(error.localizedDescription)"
                    run.fail()
                }
            }
        }
    }

    func setSampleEnabled(_ on: Bool) {
        guard let library else { return }
        do {
            try library.setEnabled(sourceId: Self.sampleId, enabled: on)
            sampleEnabled = on
            revision += 1
            if on && sampleScan == nil { scanSample() }
        } catch {
            problem = "Could not save the setting: \(error.localizedDescription)"
        }
    }

    /// Items of every source that is on — what the Now watchers and the sort read. Includes contact
    /// cards (the impersonation watcher's address book; the sort does not judge them).
    func items() -> [SourceItem] {
        guard let library else { return [] }
        let phoneIds = PhoneSource.allCases.filter { isPhoneEnabled($0) }.flatMap(\.cacheIds)
        return library.items(sourceIds: [Self.sampleId], defaultEnabled: true)
            + library.items(sourceIds: phoneIds, defaultEnabled: true)   // already filtered by each source's switch
            + (inbox?.items() ?? [])                                      // empty when the Inbox is off
            + debugItems
    }

    #if DEBUG
    private var debugItems: [SourceItem] {
        if LaunchOptions.current.privacyPhotoDemo, DemoItems.extra.isEmpty { DemoItems.installPhotoDemo() }
        return DemoItems.extra
    }
    #else
    private var debugItems: [SourceItem] { [] }
    #endif

    /// Items a judgment reads: every source that is on, less contact cards (facts, not documents).
    func judgeableItems() -> [SourceItem] {
        items().filter { $0.kind != .contact }
    }

    /// How many sources are on, the sample included.
    var enabledCount: Int {
        (sampleEnabled ? 1 : 0) + PhoneSource.allCases.filter { isPhoneEnabled($0) }.count
            + (inboxEnabled && !inboxBatches.isEmpty ? 1 : 0)
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
