import CoreGraphics
import Foundation
import LoupeKit

// The live scan display's data (Sources, 2026-09-25): what the producers really see, aggregated and throttled
// for the screen. Nothing here draws. Everything the display shows comes from a `ScanSnapshot`; every name
// and text snippet in a snapshot has been through `ScanMask` first, so raw recognised text never reaches the
// screen, and none of it is written anywhere (memory only, dropped when the card settles).

/// One stage of a source's pipeline. Only stages that really run for that source are listed: a scan reads
/// and indexes; the decision model does not run during a scan, so there is no model stage.
enum ScanStageKind: String, CaseIterable {
    /// Mail: the messages fetched from the mail server (Online).
    case fetch
    /// Files and the sample: the files the scanner walked.
    case found
    /// Items read.
    case read
    /// Photos: pictures whose text on-device OCR found.
    case text
    /// Items stored in the Sources cache when the scan finishes (what judgments and the watchers read).
    case saved

    var label: String {
        switch self {
        case .fetch: return "Fetch"
        case .found: return "Files"
        case .read: return "Read"
        case .text: return "Text"
        case .saved: return "Saved"
        }
    }
}

/// How a source looks and counts in the live display.
struct ScanPipeline: Equatable {
    enum Visual: Equatable { case thumbnails, names, initials }

    let source: String
    let title: String
    let stages: [ScanStageKind]
    let visual: Visual
    let unit: String
    let units: String
    /// Online sources: what is fetched and from where ("Online · fetches your inbox from imap.gmail.com, read-only").
    /// nil: read on this iPhone, 0 bytes out.
    let online: String?

    func unitWord(_ n: Int) -> String { n == 1 ? unit : units }

    /// The pipeline of a source ("photos", "files", "calendar", "contacts", "mail", "sample").
    static func of(_ source: String, ocr: Bool = true, mailHost: String? = nil, gmail: Bool = false) -> ScanPipeline {
        switch source {
        case "photos":
            return ScanPipeline(source: source, title: "Photos", stages: ocr ? [.read, .text, .saved] : [.read, .saved],
                                visual: .thumbnails, unit: "photo", units: "photos", online: nil)
        case "files":
            return ScanPipeline(source: source, title: "Files", stages: [.found, .read, .saved], visual: .names,
                                unit: "file", units: "files", online: nil)
        case "calendar":
            return ScanPipeline(source: source, title: "Calendar", stages: [.read, .saved], visual: .names,
                                unit: "event", units: "events", online: nil)
        case "contacts":
            return ScanPipeline(source: source, title: "Contacts", stages: [.read, .saved], visual: .initials,
                                unit: "contact", units: "contacts", online: nil)
        case "mail":
            let host = mailHost ?? "your mail server"
            let what = gmail ? "Online · reads your Gmail inbox through the Gmail API, read-only"
                : "Online · fetches your inbox from \(host), read-only"
            return ScanPipeline(source: source, title: "Mail", stages: [.fetch, .read, .saved], visual: .names,
                                unit: "message", units: "messages", online: what)
        default:
            return ScanPipeline(source: source, title: "Sample data", stages: [.found, .read, .saved], visual: .names,
                                unit: "item", units: "items", online: nil)
        }
    }
}

/// One item as its producer saw it. Raw: `ScanStream.ingest` masks `name` and `text` before keeping them.
struct ScanEvent {
    var name: String
    var read: Bool = true
    /// Text found in the item (OCR for a photo).
    var textFound: Bool = false
    /// The item's recognised or extracted text, raw. Only a masked snippet of it is kept.
    var text: String = ""
    /// Vision's boxes for the recognised lines, normalised (origin bottom-left, as Vision gives them).
    var boxes: [CGRect] = []
    /// A small thumbnail (photos).
    var thumbnail: CGImage?
    /// The producer's progress after this item, when it knows it.
    var done: Int?
    var total: Int?
}

/// Masks what the live display shows, with the privacy check's own masks (LoupeKit `SecretRules.redactText`
/// then `PiiRules.maskPii`, as "Show where" masks its context), then hides any remaining run of five or more
/// digits. Masking runs on a window wider than what is shown, so a number cut at the edge cannot survive.
enum ScanMask {
    private static let longDigits = try! NSRegularExpression(pattern: "\\d{5,}")
    private static let spaces = try! NSRegularExpression(pattern: "\\s+")

    static func text(_ raw: String, limit: Int) -> String {
        guard !raw.isEmpty else { return "" }
        let window = String(raw.prefix(400))
        var out = PiiRules.shared.maskPii(text: SecretRules.shared.redactText(text: window))
        out = replace(longDigits, in: out) { String(repeating: "•", count: $0.count) }
        out = replace(spaces, in: out) { _ in " " }.trimmingCharacters(in: .whitespaces)
        if out.count > limit { out = String(out.prefix(limit - 1)).trimmingCharacters(in: .whitespaces) + "…" }
        return out
    }

    /// A contact's initials ("Salma Hany" → "S.H."), never the name.
    static func initials(_ name: String) -> String {
        let parts = name.split(whereSeparator: { $0.isWhitespace || $0 == "-" }).compactMap(\.first)
        guard !parts.isEmpty else { return "—" }
        return parts.prefix(3).map { "\(String($0).uppercased())." }.joined()
    }

    private static func replace(_ rx: NSRegularExpression, in s: String, _ f: (String) -> String) -> String {
        let ns = s as NSString
        var out = ""
        var at = 0
        for m in rx.matches(in: s, range: NSRange(location: 0, length: ns.length)) {
            out += ns.substring(with: NSRange(location: at, length: m.range.location - at))
            out += f(ns.substring(with: m.range))
            at = m.range.location + m.range.length
        }
        return out + ns.substring(from: at)
    }
}

/// A CGImage with identity, so snapshots can be compared cheaply.
final class ScanThumb {
    let image: CGImage
    init(_ image: CGImage) { self.image = image }
}

/// What the live display draws, at most ~12 times a second.
struct ScanSnapshot {
    enum Phase: Equatable { case running, finished, failed }

    struct Stage: Identifiable, Equatable {
        let kind: ScanStageKind
        /// nil: not counted yet (Saved lands when the scan finishes; Fetch shows a tick once fetched).
        let count: Int?
        let complete: Bool
        /// When the count last went up (monotonic seconds), for the node's pulse.
        let pulseAt: TimeInterval?
        var id: String { kind.rawValue }
    }

    /// One recent item on the conveyor (newest first).
    struct Recent: Identifiable, Equatable {
        let id: Int
        let name: String
        let snippet: String
        let read: Bool
        let textFound: Bool
        let boxes: [CGRect]
        let thumb: ScanThumb?
        let at: TimeInterval
        static func == (a: Recent, b: Recent) -> Bool { a.id == b.id }
    }

    var pipeline: ScanPipeline
    var phase: Phase = .running
    var status: String = ""
    var done = 0
    var total: Int?
    var read = 0
    var skipped = 0
    var withText = 0
    var saved: Int?
    var stages: [Stage] = []
    var recent: [Recent] = []
    /// Masked text snippets, newest first (the ledger).
    var ledger: [Recent] = []
    var rate: Double?
    var eta: TimeInterval?
    /// Items that arrived since the previous snapshot (the conveyor slides by this many).
    var batch = 0
    var publishedAt: TimeInterval = 0
    var summary: String?

    var fraction: Double? {
        guard let total, total > 0 else { return phase == .finished ? 1 : nil }
        return min(1, Double(done) / Double(total))
    }

    var bytesOut: String { pipeline.online ?? "0 bytes out · read on this iPhone" }

    /// "12 of 24 · 2.4/s · about 5 s left"
    var telemetry: String {
        var parts: [String] = []
        if let total { parts.append("\(done.formatted()) of \(total.formatted())") } else if done > 0 { parts.append(done.formatted()) }
        if let rate, rate > 0 { parts.append("\(ScanSnapshot.rateText(rate))/s") }
        if phase == .running, let eta { parts.append("about \(ScanSnapshot.duration(eta)) left") }
        return parts.joined(separator: " · ")
    }

    /// VoiceOver: "Photos: 312 of 1,204 read, 48 with text".
    var accessibilitySummary: String {
        if phase == .finished, let summary { return "\(pipeline.title): finished. \(summary)" }
        if phase == .failed { return "\(pipeline.title): the scan stopped." }
        var s = "\(pipeline.title): "
        if let total { s += "\(read.formatted()) of \(total.formatted()) read" } else { s += "\(read.formatted()) read" }
        if pipeline.stages.contains(.text) { s += ", \(withText.formatted()) with text" }
        if skipped > 0 { s += ", \(skipped.formatted()) skipped" }
        if phase == .running, let eta { s += ", about \(ScanSnapshot.duration(eta)) left" }
        return s
    }

    static func rateText(_ r: Double) -> String { r >= 10 ? "\(Int(r.rounded()))" : String(format: "%.1f", r) }

    static func duration(_ s: TimeInterval) -> String {
        if s < 60 { return "\(max(1, Int(s.rounded()))) s" }
        if s < 3600 { return "\(Int((s / 60).rounded())) min" }
        return "\(Int(s / 3600)) h \(Int(s.truncatingRemainder(dividingBy: 3600) / 60)) min"
    }
}

/// The aggregator: counts, stage counts, rate and ETA, the recent items and the masked ledger. A value type with
/// an injected clock, so it is tested without threads or a screen. `ScanFeed` wraps it for the producers' threads.
struct ScanStream {
    static let recentLimit = 8
    static let ledgerLimit = 5
    static let rateWindow: TimeInterval = 4

    let pipeline: ScanPipeline
    private(set) var status = ""
    private(set) var done = 0
    private(set) var total: Int?
    private(set) var read = 0
    private(set) var skipped = 0
    private(set) var withText = 0
    private(set) var found: Int?
    private(set) var fetched = false
    private(set) var saved: Int?
    private(set) var phase: ScanSnapshot.Phase = .running
    private(set) var summary: String?
    private var seq = 0
    private var publishedSeq = 0
    private var recent: [ScanSnapshot.Recent] = []
    private var ledger: [ScanSnapshot.Recent] = []
    private var pulses: [ScanStageKind: TimeInterval] = [:]
    private var samples: [(t: TimeInterval, done: Int)] = []
    private var lastName = ""

    init(pipeline: ScanPipeline) { self.pipeline = pipeline }

    mutating func setStatus(_ s: String) { status = s }

    /// One item read (or skipped) by a producer that reports item by item (Photos, Calendar, Contacts).
    mutating func ingest(_ e: ScanEvent, now: TimeInterval) {
        seq += 1
        if e.read { read += 1; pulse(.read, now) } else { skipped += 1 }
        if e.textFound { withText += 1; pulse(.text, now) }
        done = e.done ?? (done + 1)
        if let t = e.total { total = t }
        let name = pipeline.visual == .initials ? ScanMask.initials(e.name) : ScanMask.text(e.name, limit: 40)
        let snippet = e.text.isEmpty ? "" : ScanMask.text(e.text, limit: 64)
        let r = ScanSnapshot.Recent(id: seq, name: name, snippet: snippet, read: e.read, textFound: e.textFound,
                                    boxes: Array(e.boxes.prefix(24)), thumb: e.thumbnail.map(ScanThumb.init), at: now)
        push(r)
        sample(now)
    }

    /// The common scanner's progress (Files, the sample, Mail): files walked, items read and skipped, and the
    /// path it is on. Deltas become items; the current file's name goes on the conveyor.
    mutating func scanned(seen: Int, total t: Int, itemsRead: Int, skippedNow: Int, current: String, label: String? = nil, now: TimeInterval) {
        let dRead = max(0, itemsRead - read)
        let dSkip = max(0, skippedNow - skipped)
        if seen > (found ?? 0) { found = seen; pulse(.found, now) }
        if dRead > 0 { read += dRead; pulse(.read, now) }
        skipped += dSkip
        done = seen
        if t > 0 { total = t }
        // The scanner's last report says "done" rather than a path: counts only.
        let name = current == "done" ? "" : label ?? (current as NSString).lastPathComponent
        if !name.isEmpty, current != lastName {
            lastName = current
            seq += 1
            push(ScanSnapshot.Recent(id: seq, name: ScanMask.text(name, limit: 40), snippet: "", read: dSkip == 0,
                                     textFound: false, boxes: [], thumb: nil, at: now))
        }
        sample(now)
    }

    /// The producer listed what it will read (Photos: the photos not read yet, up to 300).
    mutating func listed(total t: Int) {
        total = t
        status = t == 0 ? "Nothing new to read" : "Reading \(t.formatted()) \(pipeline.unitWord(t))" + (pipeline.stages.contains(.text) ? " with on-device OCR" : "")
    }

    /// Mail: the fetch from the server finished.
    mutating func markFetched(now: TimeInterval) { fetched = true; pulse(.fetch, now) }

    /// The scan finished and [savedCount] items this scan read were stored; [summary] is the settle line.
    mutating func finish(saved savedCount: Int, now: TimeInterval) {
        saved = savedCount
        phase = .finished
        pulse(.saved, now)
        if let total, done < total { done = total }
        summary = Self.summary(pipeline: pipeline, read: read, withText: withText, skipped: skipped)
    }

    mutating func fail() { phase = .failed }

    /// "24 photos · 9 with text · 0 bytes out"; "No new photos · 0 bytes out" when nothing was new.
    static func summary(pipeline: ScanPipeline, read: Int, withText: Int, skipped: Int) -> String {
        var parts: [String] = []
        parts.append(read == 0 ? "No new \(pipeline.units)" : "\(read.formatted()) \(pipeline.unitWord(read))")
        if pipeline.stages.contains(.text) && read > 0 { parts.append("\(withText.formatted()) with text") }
        if skipped > 0 { parts.append("\(skipped.formatted()) skipped") }
        parts.append(pipeline.online == nil ? "0 bytes out" : "fetched read-only")
        return parts.joined(separator: " · ")
    }

    mutating func snapshot(now: TimeInterval) -> ScanSnapshot {
        var s = ScanSnapshot(pipeline: pipeline)
        s.phase = phase
        s.status = status
        s.done = done
        s.total = total
        s.read = read
        s.skipped = skipped
        s.withText = withText
        s.saved = saved
        s.recent = recent
        s.ledger = ledger
        s.batch = min(seq - publishedSeq, Self.recentLimit)
        publishedSeq = seq
        s.publishedAt = now
        s.summary = summary
        s.stages = pipeline.stages.map { k in
            let count: Int?
            let complete: Bool
            switch k {
            case .fetch: count = nil; complete = fetched
            case .found: count = found; complete = phase == .finished
            case .read: count = read; complete = phase == .finished
            case .text: count = withText; complete = phase == .finished
            case .saved: count = saved; complete = saved != nil
            }
            return ScanSnapshot.Stage(kind: k, count: count, complete: complete, pulseAt: pulses[k])
        }
        let r = rate(now: now)
        s.rate = r
        if phase == .running, let total, let r, r > 0 { s.eta = Double(max(0, total - done)) / r }
        return s
    }

    /// Items per second over the last few seconds (nil until there is half a second of data).
    func rate(now: TimeInterval) -> Double? {
        guard let base = samples.first, let last = samples.last else { return nil }
        let span = now - base.t
        guard span >= 0.5 else { return nil }
        return Double(max(0, last.done - base.done)) / span
    }

    private mutating func push(_ r: ScanSnapshot.Recent) {
        recent.insert(r, at: 0)
        if recent.count > Self.recentLimit { recent.removeLast(recent.count - Self.recentLimit) }
        // The ledger: items with text (their masked snippet), or every item's name for sources without text.
        if !r.snippet.isEmpty || !pipeline.stages.contains(.text) {
            ledger.insert(r, at: 0)
            if ledger.count > Self.ledgerLimit { ledger.removeLast(ledger.count - Self.ledgerLimit) }
        }
    }

    private mutating func pulse(_ k: ScanStageKind, _ now: TimeInterval) { pulses[k] = now }

    private mutating func sample(_ now: TimeInterval) {
        if samples.isEmpty, done > 0 { samples.append((now, 0)) }   // the first item starts the clock
        samples.append((now, done))
        // Keep one sample older than the window as the rate's base.
        while samples.count > 2, now - samples[1].t > Self.rateWindow { samples.removeFirst() }
        if samples.count > 400 { samples.removeFirst(samples.count - 400) }
    }
}

/// Coalesces redraw requests to at most one per `interval` (~12 Hz), with a trailing flush so the last change
/// always lands. Pure: the caller schedules the returned delay.
struct ScanThrottle {
    let interval: TimeInterval
    private(set) var scheduled = false
    private(set) var lastFire: TimeInterval = -.infinity

    init(hz: Double = 12) { interval = 1 / hz }

    /// A change happened at [now]: the delay after which to flush, or nil when a flush is already scheduled.
    mutating func request(now: TimeInterval) -> TimeInterval? {
        guard !scheduled else { return nil }
        scheduled = true
        return max(0, lastFire + interval - now)
    }

    mutating func fired(now: TimeInterval) {
        scheduled = false
        lastFire = now
    }
}
