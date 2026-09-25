import Foundation
import LoupeKit

/// The live run of one Sources scan (Photos, Files, the sample, Mail, Calendar, Contacts): a `source_scan`
/// job on the Sources screen, drawn by `LiveRunSection(view: "sources")` with Station's Folder Scan loop.
/// Real numbers only: items read, pictures whose text was read (OCR), items skipped, the done/total progress
/// (the registry derives the rate and ETA from it). Counts only, never a name.
final class SourceScanRun: @unchecked Sendable {
    let job: LiveJob
    private let lock = NSLock()
    private var read = 0
    private var skipped = 0
    private var ocr = 0
    private var lastSeen = 0

    @MainActor
    init(source: String, stage: String = "act.stage.walking", cancel: (() -> Void)? = nil) {
        job = ActivityCenter.shared.start("source_scan", title: "act.title.scan.\(source)", view: "sources",
                                          stage: stage, cancel: cancel)
        job.meta(["files_seen": 0, "ocr_files": 0, "phase_unit": "files"])
    }

    /// One item of a producer that reads item by item (Photos).
    func item(done: Int, of total: Int, read didRead: Bool, ocr didOcr: Bool) {
        lock.lock()
        if didRead { read += 1 } else { skipped += 1 }
        if didOcr { ocr += 1 }
        let o = ocr
        lock.unlock()
        if done == 1 { job.stage("act.stage.reading") }
        job.count("read", didRead ? 1 : 0)
        job.gate(didRead ? "accepted" : "skipped", 1)
        job.progress(done, of: total)
        job.meta(["files_seen": done, "files_total": total, "ocr_files": o])
    }

    /// The common scanner's progress (Files, the sample, Mail): deltas of items read and skipped.
    func scanned(_ p: ScanProgress) {
        lock.lock()
        let dRead = max(0, Int(p.itemsRead) - read)
        let dSkip = max(0, Int(p.skipped) - skipped)
        read += dRead; skipped += dSkip
        let first = lastSeen == 0 && p.filesSeen > 0
        lastSeen = Int(p.filesSeen)
        lock.unlock()
        if first { job.stage("act.stage.reading") }
        if dRead > 0 { job.count("read", dRead); job.gate("accepted", dRead) }
        if dSkip > 0 { job.gate("skipped", dSkip) }
        job.progress(Int(p.filesSeen), of: Int(p.filesTotal))
        job.meta(["files_seen": Int(p.filesSeen), "files_total": Int(p.filesTotal)])
    }

    /// Pictures read by OCR in the scanner (counted from the result: items whose text came from an image).
    func ocrCount(_ n: Int) {
        lock.lock(); ocr = n; lock.unlock()
        job.meta(["ocr_files": n])
    }

    /// A scanner observer that reports here.
    var observer: ScanObserver { Obs(self) }

    func finish(items: Int, skipped: Int) {
        job.stage("act.stage.finishing")
        job.finish("done", "act.res.sourceScan", ["items": items, "skipped": skipped])
    }

    func fail() { job.finish("error", "act.res.failed") }
    func cancelled() { job.finish("cancelled") }

    private final class Obs: NSObject, ScanObserver {
        let run: SourceScanRun
        init(_ run: SourceScanRun) { self.run = run }
        func onProgress(progress: ScanProgress) { run.scanned(progress) }
        func isCancelled() -> Bool { false }
    }
}
