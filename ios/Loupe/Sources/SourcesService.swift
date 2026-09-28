import Foundation
import LoupeKit

/// Sources on this iPhone (epic #7 child 2; child 7 for the phone's own sources in `SourcesService+Phone.swift`),
/// read by LoupeKit's common scanner with PDFKit/ImageIO/Vision as its platform readers, off the main thread, with
/// progress. Results are cached through LoupeKit's `SourceLibrary` (Application Support/Loupe/sources) so Judgments,
/// the watchers and the checks read items without rescanning.
///
/// No sample data ships (owner decision 2026-09-28): the synthetic sample lives in the test targets only. Tests pass
/// its folder as `sampleRoot`, and DEBUG fixture launches (`-LoupeFixtures` with `LOUPE_FIXTURE_SAMPLE` set by the UI
/// tests) read it as a hidden fixture source with the id `sample`; in every other build `sampleRoot` is nil and the
/// sample is not a source at all. Nothing is scanned at launch (2026-09-28): scans run in a `RunCoordinator` run
/// (the first check after onboarding, the nightly run, Run now, Scan again).
@MainActor
final class SourcesService: ObservableObject {
    /// The id of the synthetic sample's items (`sample:documents/…`, `sample:mail/…`): a test fixture now, and what the
    /// sample migration removes from a phone that ran an older build.
    static let sampleId = "sample"

    static let shared = SourcesService(home: LedgerService.defaultHome(), sampleRoot: SourcesService.fixtureSampleRoot())

    struct Progress: Equatable {
        let seen: Int
        let total: Int
        let current: String
        var fraction: Double { total == 0 ? 0 : Double(seen) / Double(total) }
    }

    /// The DEBUG/test fixture sample is on (never true without a `sampleRoot`).
    @Published private(set) var sampleEnabled = false
    /// The fixture sample's cached scan (tests and DEBUG fixture launches only).
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
    /// The live scan of each source being read (key: "sample" or a phone source id), kept a few seconds after it
    /// finishes for the settle. Set once at the start and once at the end, so the Sources screen itself does not
    /// redraw per item; the live display observes the `LiveScan` alone (~12 Hz).
    @Published private(set) var liveScans: [String: LiveScan] = [:]
    /// Scans started since launch (diagnostics; the no-launch-work UI test reads it through a DEBUG label).
    @Published private(set) var scansStarted = 0

    var scanning: Bool { progress != nil || phone.values.contains { $0.scanning } }
    /// Callers that asked for a phone source's scan while one was already reading it: that scan may have listed
    /// the source before their change, so one more scan runs after it, and they wait for that one.
    var phoneScanWaiters: [PhoneSource: [CheckedContinuation<Void, Never>]] = [:]
    /// Bumped by "Delete all my Loupe data": a follow-up scan queued before it does not run after it.
    private(set) var eraseGeneration = 0
    /// Where a scan the user asked for goes (a source switched on, access allowed, a folder picked, a mailbox added):
    /// `RunCoordinator` sets it, so the scan runs as a visible, cancellable run followed by the checks. nil (unit
    /// tests): the scan runs directly.
    var userScan: ((String) async -> Void)?
    /// Told when the items changed without a scan the user asked for (a source switched off, an import added or
    /// removed, a mailbox removed, the automatic Gmail retry): `RunCoordinator` re-runs the checks. nil in unit tests.
    var itemsChanged: (() -> Void)?

    /// Re-opened only by "Delete all my Loupe data" (`reloadAfterErase`).
    private(set) var library: SourceLibrary?
    /// Imported CSVs, mail files, ZIP archives and shared text (child 15), in LoupeKit.
    private(set) var inbox: Inbox?
    let home: URL
    let deps: PhoneDependencies
    let sampleRoot: URL?
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
        if let library, sampleRoot != nil {
            sampleEnabled = library.isEnabled(sourceId: Self.sampleId, default: true)
            sampleScan = library.cached(sourceId: Self.sampleId)
        }
        loadPhoneStates()
    }

    /// Whether the fixture sample is a source here (tests and DEBUG fixture launches only).
    var hasFixtureSample: Bool { sampleRoot != nil }

    /// After "Delete all my Loupe data" (audit P1-4; the button waits while a scan runs): opens the emptied cache
    /// again and forgets every source's state, as on a fresh install. `start()` then runs again from onboarding.
    func reloadAfterErase() {
        // Pending follow-up scans are cancelled: their callers are released, nothing reads into the emptied cache.
        eraseGeneration += 1
        let waiters = phoneScanWaiters.values.flatMap { $0 }
        phoneScanWaiters = [:]
        waiters.forEach { $0.resume() }
        progress = nil
        problem = nil
        liveScans = [:]
        inboxBusy = false
        inboxProblem = nil
        do {
            try FileManager.default.createDirectory(at: home, withIntermediateDirectories: true)
            library = try SourceLibrary(home: home.path)
        } catch {
            library = nil
            problem = "The sources cache could not be opened: \(error.localizedDescription)"
        }
        inbox = library == nil ? nil : try? Inbox(home: home.path, extractors: AppleExtractors.live())
        inboxBatches = inbox?.batches() ?? []
        sampleEnabled = sampleRoot != nil && (library?.isEnabled(sourceId: Self.sampleId, default: true) ?? true)
        sampleScan = sampleRoot == nil ? nil : library?.cached(sourceId: Self.sampleId)
        phone = [:]
        loadPhoneStates()
        started = false
        revision += 1
    }

    /// The fixture sample's folder for DEBUG fixture launches: `LOUPE_FIXTURE_SAMPLE` (the UI tests set it to the
    /// sample folder in their own bundle; the simulator shares the Mac's file system), read only under `-LoupeFixtures`.
    /// nil in every other launch and always in Release: the app bundle carries no sample.
    nonisolated static func fixtureSampleRoot() -> URL? {
        #if DEBUG
        guard LaunchOptions.current.fixtureMode,
              let path = ProcessInfo.processInfo.environment["LOUPE_FIXTURE_SAMPLE"], !path.isEmpty,
              FileManager.default.fileExists(atPath: path) else { return nil }
        return URL(fileURLWithPath: path, isDirectory: true)
        #else
        return nil
        #endif
    }

    /// Called when the app appears. Opening the app only LOADS (owner decision 2026-09-28): no source is scanned and
    /// no check runs here. What was shared to Loupe while it was closed is filed into the Inbox (a copy, no reading
    /// of the phone), and it is picked up by the next run.
    func start() {
        guard !started else { return }
        started = true
        Task { await collectShared() }
        #if DEBUG
        if LaunchOptions.current.inboxDemo { Task { await seedInboxDemo() } }
        if let s = SourcesDemo.autoScan { Task { await setPhoneEnabled(s, true) } }
        #endif
    }

    /// The two roots of the sample: the documents folder and the mail export, with stable ids.
    nonisolated static func sampleRoots(_ root: URL) -> [SourceRoot] {
        [SourceRoot(id: sampleId, type: .folder, path: root.appendingPathComponent("documents").path, idPrefix: "sample:documents/"),
         SourceRoot(id: sampleId, type: .mailExport, path: root.appendingPathComponent("mail").path, idPrefix: "sample:mail/")]
    }

    /// Reads the fixture sample (tests and DEBUG fixture launches). Cancellable between files: a cancelled scan keeps
    /// what it read and, for the files it did not reach, what the last scan had (`SourceMerge`), never a cut cache.
    func scanSample(cancel: RunCancel? = nil) async {
        guard !scanning, let library else { return }
        guard let root = sampleRoot else { return }
        progress = Progress(seen: 0, total: 0, current: "")
        problem = nil
        let run = SourceScanRun(source: "sample")
        let live = beginLive("sample", ScanPipeline.of("sample"))
        let feed = live.feed
        feed.status("Listing the sample files")
        // Progress goes to the live run and the live display only; publishing it here would redraw every screen
        // that observes this service once per file.
        let observer = Observer({ p in
            run.scanned(p)
            feed.scanned(p)
        }, cancel: cancel)
        let previous = sampleScan?.result
        let outcome: Result<CachedScan, Error> = await withCheckedContinuation { c in
            queue.async {
                c.resume(returning: Result { () -> CachedScan in
                    var result = try SourceScanner(extractors: AppleExtractors()).scan(sources: Self.sampleRoots(root), observer: observer)
                    if cancel?.isCancelled == true { result = SourceMerge.keepUnreached(partial: result, previous: previous) }
                    return try library.store(sourceId: Self.sampleId, result: result)
                })
            }
        }
        progress = nil
        switch outcome {
        case .success(let scan):
            sampleScan = scan; revision += 1
            run.ocrCount(Self.ocrItems(scan.result))
            run.finish(items: scan.result.items.count, skipped: scan.result.skipped.count)
            feed.finish(saved: scan.result.items.count)
        case .failure(let error):
            problem = "Scan failed: \(error.localizedDescription)"
            run.fail()
            feed.fail()
        }
        settle(live)
    }

    // MARK: Live scans

    /// How long a finished scan's summary stays before the card returns to rest.
    static let settleSeconds: TimeInterval = 4

    /// Starts the live display of one scan.
    func beginLive(_ key: String, _ pipeline: ScanPipeline) -> LiveScan {
        scansStarted += 1
        let live = LiveScan(pipeline: pipeline)
        liveScans[key] = live
        return live
    }

    /// After the settle, the card returns to rest (only if no newer scan of that source started meanwhile).
    func settle(_ live: LiveScan) {
        let key = live.source
        DispatchQueue.main.asyncAfter(deadline: .now() + Self.settleSeconds) { [weak self] in
            MainActor.assumeIsolated {
                guard let self, self.liveScans[key]?.id == live.id else { return }
                self.liveScans[key] = nil
            }
        }
    }

    /// The fixture sample's switch (tests only; there is no sample in the app's screens).
    func setSampleEnabled(_ on: Bool) {
        guard let library, sampleRoot != nil else { return }
        do {
            try library.setEnabled(sourceId: Self.sampleId, enabled: on)
            sampleEnabled = on
            revision += 1
        } catch {
            problem = "Could not save the setting: \(error.localizedDescription)"
        }
    }

    /// Items of every source that is on — what the Now watchers and the sort read. Includes contact
    /// cards (the impersonation watcher's address book; the sort does not judge them).
    func items() -> [SourceItem] {
        guard let library else { return [] }
        let phoneIds = PhoneSource.allCases.filter { isPhoneEnabled($0) }.flatMap(\.cacheIds)
        return (sampleEnabled ? library.items(sourceIds: [Self.sampleId], defaultEnabled: true) : [])
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

    /// `items()` (or `judgeableItems()`) as a reader to run **off** the main thread (2026-09-28 launch hang):
    /// reading every source parses each source's whole cache file, far too slow for the main thread on a phone
    /// with thousands of files. Which sources are on is decided here, now; the reads happen when it is called
    /// (the library and the Inbox serialise their own file access).
    func itemsReader(judgeable: Bool = false) -> ItemsReader {
        guard let library else { return ItemsReader { [] } }
        let phoneIds = PhoneSource.allCases.filter { isPhoneEnabled($0) }.flatMap(\.cacheIds)
        let inbox = self.inbox
        let extra = debugItems
        let sampleIds = sampleEnabled ? [Self.sampleId] : []
        return ItemsReader {
            let all = library.items(sourceIds: sampleIds, defaultEnabled: true)
                + library.items(sourceIds: phoneIds, defaultEnabled: true)
                + (inbox?.items() ?? [])
                + extra
            return judgeable ? all.filter { $0.kind != .contact } : all
        }
    }

    /// How many sources are on (the fixture sample counts in tests and DEBUG fixture launches).
    var enabledCount: Int {
        (sampleEnabled ? 1 : 0) + PhoneSource.allCases.filter { isPhoneEnabled($0) }.count
            + (inboxEnabled && !inboxBatches.isEmpty ? 1 : 0)
    }

    /// Waits for a queued scan to finish on the background queue (tests).
    nonisolated func flush() { queue.sync {} }

    private final class Observer: NSObject, ScanObserver {
        let onProgress: (ScanProgress) -> Void
        let cancel: RunCancel?
        init(_ onProgress: @escaping (ScanProgress) -> Void, cancel: RunCancel?) { self.onProgress = onProgress; self.cancel = cancel }
        func onProgress(progress: ScanProgress) { onProgress(progress) }
        func isCancelled() -> Bool { cancel?.isCancelled ?? false }
    }
}

/// A read of the scanned items that may run on any thread (see `SourcesService.itemsReader`).
struct ItemsReader: @unchecked Sendable {
    let read: () -> [SourceItem]
    init(_ read: @escaping () -> [SourceItem]) { self.read = read }
    func callAsFunction() -> [SourceItem] { read() }

    /// The items for a run (2026-09-28: the main thread never reads the caches): through [reader] off the main thread
    /// when one is given (the app's services), else [items] as is (unit tests pass plain closures).
    @MainActor
    static func load(_ items: () -> [SourceItem], _ reader: (() -> ItemsReader)?) async -> [SourceItem] {
        guard let reader else { return items() }
        let r = reader()
        return await Task.detached(priority: .userInitiated) { r() }.value
    }
}

/// A cancelled scan's result made safe to store (2026-09-28): the scanner stops between files and returns what it read;
/// stored alone it would drop every item it did not reach. So the items it read replace theirs, and the previous scan's
/// items it never reached are kept. A file deleted since stays until the next complete scan; nothing is lost.
enum SourceMerge {
    static func keepUnreached(partial: ScanResult, previous: ScanResult?) -> ScanResult {
        guard let previous else { return partial }
        let read = Set(partial.items.map(\.id))
        let kept = previous.items.filter { !read.contains($0.id) }
        let skipped = Set(partial.skipped.map(\.path))
        return .of(partial.items + kept,
                   skipped: partial.skipped + previous.skipped.filter { !skipped.contains($0.path) },
                   unavailable: partial.unavailable)
    }
}
