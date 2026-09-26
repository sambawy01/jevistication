import Foundation
import LoupeKit

/// Reads LoupeKit's `ResultRow`s (the shared selection: current wording only, latest per item) into plain
/// `ResultRecord`s, once per run. Off the main thread: every property read here crosses into Kotlin, so it is
/// done once for all rows instead of on every render.
enum ResultsBuilder {
    /// The judgment's options, in its order.
    static func options(_ j: UserJudgment) -> [String] { j.shape.candidates }

    /// The option shown in a muted tone: the negative of a yes/no, the "none of these" of a pick.
    static func mutedOption(_ j: UserJudgment) -> Int? {
        let options = self.options(j)
        if let pick = j.shape as? ShapePick, let noOp = pick.noOp { return options.firstIndex(of: noOp) }
        if options.count == 2, let positive = j.positiveLabel { return options.firstIndex { $0 != positive } }
        return nil
    }

    static func records(_ rows: [ResultRow], judgment j: UserJudgment,
                        fallback: (String) -> SourceItem? = { _ in nil }) -> [ResultRecord] {
        let options = self.options(j)
        let position = Dictionary(options.enumerated().map { ($1, $0) }, uniquingKeysWith: { a, _ in a })
        var out: [ResultRecord] = []
        out.reserveCapacity(rows.count)
        for r in rows {
            let itemId = r.itemId
            let item = r.item ?? fallback(itemId)
            var mask: UInt64 = 0
            for label in r.row.distribution.labels {
                if let i = position[label], i < 64 { mask |= 1 << UInt64(i) }
            }
            let name = item?.name ?? String(itemId.split(separator: "/").last ?? Substring(itemId))
            out.append(ResultRecord(
                itemId: itemId,
                name: name,
                source: item.map { sourceKey($0) } ?? "gone",
                kind: item?.kind.title ?? "item",
                day: item?.dateIso,
                top: position[r.topLabel] ?? -1,
                mass: r.topMass,
                acted: r.acted,
                unusable: r.unusable,
                check: r.mechanicalCheck,
                correctable: mask,
                correction: r.correction.flatMap { position[$0] },
                extraSearch: item?.email?.subject ?? ""))
        }
        return out
    }

    /// The source a breakdown groups an item under (Send to Loupe files group with Files' own id).
    static func sourceKey(_ item: SourceItem) -> String { item.sourceId }
}

/// When rows were last written for a judgment (a run from the results screen, the passive sort, a fixture).
/// The ledger rows carry no time, so the time is kept beside them, per judgment, in UserDefaults.
enum JudgmentRunLog {
    private static let prefix = "results.lastRun."

    static func stamp(_ judgmentIds: some Sequence<String>, at date: Date = Date(), defaults: UserDefaults = .standard) {
        for id in judgmentIds { defaults.set(date.timeIntervalSince1970, forKey: prefix + id) }
    }

    static func last(_ judgmentId: String, defaults: UserDefaults = .standard) -> Date? {
        let t = defaults.double(forKey: prefix + judgmentId)
        return t > 0 ? Date(timeIntervalSince1970: t) : nil
    }

    /// "Last run 3 min ago", "Last run: not recorded".
    static func line(_ judgmentId: String, now: Date = Date()) -> String {
        guard let d = last(judgmentId) else { return "Last run: not recorded" }
        if now.timeIntervalSince(d) < 60 { return "Last run just now" }
        return "Last run " + d.formatted(.relative(presentation: .named, unitsStyle: .abbreviated))
    }
}

#if DEBUG
/// `-LoupeResultsFixture <n>` (DEBUG, with `-LoupeFixtures` so the ledger is throwaway): `n` synthetic items
/// (several sources, kinds and months; some with payment words, some without) judged by a deterministic
/// stand-in scorer through the real `JudgmentSweep`, so rule answers, unsure items and the ledger are all real.
/// The items live only here — they are not added to the sources, so nothing else in the app scans them.
enum ResultsFixture {
    nonisolated(unsafe) static var items: [String: SourceItem] = [:]

    static var requested: Int? {
        let args = ProcessInfo.processInfo.arguments
        guard LaunchOptions.current.fixtureMode, let i = args.firstIndex(of: "-LoupeResultsFixture") else { return nil }
        return i + 1 < args.count ? Int(args[i + 1]) ?? 400 : 400
    }

    /// `-LoupeResultsPerf`: show the measured build and paint times on the screen (UI test reads them).
    static var showPerf: Bool { ProcessInfo.processInfo.arguments.contains("-LoupeResultsPerf") }

    private static let merchants = ["Tesco", "Pret", "Uber", "EDF Energy", "Boots", "Trainline", "Netflix", "Amazon",
                                    "Waitrose", "Deliveroo", "Thames Water", "Apple", "Octopus", "Ryanair", "Lloyds"]
    private static let sources: [(id: String, kind: ItemKind, ext: String, mime: String)] = [
        ("files", .pdf, "pdf", "application/pdf"), ("mail", .email, "eml", "message/rfc822"),
        ("photos", .image, "jpg", "image/jpeg"), ("sample", .text, "txt", "text/plain"),
        ("files", .image, "png", "image/png"), ("inbox", .csv, "csv", "text/csv"), ("calendar", .event, "ics", "text/calendar"),
    ]

    static func makeItems(_ n: Int) -> [SourceItem] {
        (0..<n).map { i in
            var g = SplitMix(seed: UInt64(i) &* 2_654_435_761 &+ 7)
            let src = sources[Int(g.next() % UInt64(sources.count))]
            let merchant = merchants[Int(g.next() % UInt64(merchants.count))]
            // Up to September 2026 (no dates after today's fixture day).
            let year = g.next() % 5 == 0 ? 2025 : 2026
            let month = 1 + Int(g.next() % (year == 2026 ? 9 : 12))
            let day = 1 + Int(g.next() % (year == 2026 && month == 9 ? 25 : 28))
            let iso = String(format: "%04d-%02d-%02d", year, month, day)
            let kind = g.next() % 10
            let (name, text): (String, String)
            switch kind {
            case 0...3:
                name = "\(merchant) receipt \(iso).\(src.ext)"
                text = "\(merchant)\nReceipt no. \(1000 + i)\nTotal paid £\(3 + Int(g.next() % 200)).\(10 + Int(g.next() % 89))\nVisa ending 4242"
            case 4...5:
                name = "\(merchant) order \(1000 + i).\(src.ext)"
                text = "Your order from \(merchant)\nOrder total £\(5 + Int(g.next() % 90)).00\nThanks for shopping"
            case 6:
                name = "\(merchant) newsletter \(iso).\(src.ext)"
                text = "\(merchant) news\nNew arrivals this week. Add to basket. 4.8 stars, 212 reviews. Buy now from £9.99"
            default:
                name = ["Notes", "Holiday plan", "Meeting", "Photo", "Letter", "Draft"][Int(g.next() % 6)] + " \(i).\(src.ext)"
                text = "Notes about the weekend, the garden and who is bringing what. Nothing to buy."
            }
            return SourceItem(id: "fixture:\(i)", sourceId: src.id, kind: src.kind, path: "/fixture/\(i).\(src.ext)",
                              messageIndex: nil, name: name, text: text, hasText: true, textTruncated: false,
                              sizeBytes: Int64(text.count), contentHash: "fixture-\(i)", mime: src.mime,
                              date: PhoneItems.companion.day(iso: iso), dateOrigin: .fileModified, email: nil,
                              facts: [:], duplicateOf: nil)
        }
    }

    /// Adds the template's judgment if needed and judges `n` fixture items once (a later launch reuses the rows).
    @MainActor static func seed(_ service: JudgmentsService, judgmentId: String, n: Int) async {
        guard let j = service.judgment(judgmentId) else { return }
        let all = makeItems(n)
        items = Dictionary(all.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
        let ledger = service.ledger
        let already = service.rows.contains { $0.judgmentId == j.id && ($0.itemId ?? "").hasPrefix("fixture:") }
        if already { return }
        let plan = JudgmentResults.shared.plan(all: [], judgment: j, items: all, rerunAll: true)
        await Task.detached(priority: .userInitiated) {
            let bridge = SweepBridge(progress: { _ in }, rows: { ledger.record($0) })
            _ = JudgmentSweep(backend: FixtureResultsBackend()).run(judgment: j, plan: plan, observer: bridge, autoBaseline: false)
            ledger.flush()
        }.value
        JudgmentRunLog.stamp([j.id], at: Date().addingTimeInterval(-42 * 60))
        service.refreshLedger()
    }
}

/// The fixture's stand-in scorer: a spread of confidences from the item id, leaning to the first option when
/// the text reads like a receipt. Deterministic, so screenshots and tests are stable.
final class FixtureResultsBackend: NSObject, Backend {
    func score(judgment: JudgmentChoice, state: TextState) -> Scored {
        let c = judgment.candidates
        var h: UInt64 = 1469598103934665603
        for b in state.text.utf8 { h = (h ^ UInt64(b)) &* 1099511628211 }
        let spread = Double(h % 1000) / 1000                       // 0 ..< 1
        let receipty = state.text.contains("Receipt") || state.text.contains("Total paid")
        let lead = receipty ? 0 : (c.count > 2 ? Int(h % UInt64(c.count)) : 1)
        let floor = 1 / Double(max(c.count, 1))
        let top = floor + (1 - floor) * pow(spread, 0.55)           // most sure, a tail of torn ones
        var masses: [String: KotlinDouble] = [:]
        let rest = (1 - top) / Double(max(c.count - 1, 1))
        for (i, label) in c.enumerated() { masses[label] = KotlinDouble(value: i == lead ? top : rest) }
        return Scored(masses: masses, modelContext: nil, optionCriteria: nil)
    }
}

/// A tiny deterministic generator for the fixture.
struct SplitMix {
    var state: UInt64
    init(seed: UInt64) { state = seed }
    mutating func next() -> UInt64 {
        state &+= 0x9E3779B97F4A7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58476D1CE4E5B9
        z = (z ^ (z >> 27)) &* 0x94D049BB133111EB
        return z ^ (z >> 31)
    }
}
#endif
