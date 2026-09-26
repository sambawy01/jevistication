import Foundation

// The results dashboard's aggregation layer (owner feedback 2026-09-26: "a clickable summary and a data display
// with visual elements" instead of scrolling thousands of rows). Pure Swift value types, no UI and no LoupeKit:
// the Kotlin `ResultRow`s are read once into `ResultRecord`s (ResultsBuild.swift), then every number on the
// screen — the answer split, who answered, the confidence histogram, the breakdowns — is computed here in one
// pass, and a correction moves one record from one bucket to another (`setCorrection`) instead of recounting.
// The list is filtered by walking a sort order computed once per run; a longer search string only narrows the
// previous match (no whole-list pass per keystroke).

/// Where one result sits in the answer split. Mutually exclusive: every judged item is in exactly one.
enum ResultBucket: Hashable, Comparable {
    /// The model's answer could not be used (A4).
    case unusable
    /// Below the threshold, no correction yet: it waits for you.
    case unsure
    /// Answered with option `i` — by the decision model at or above the threshold, or by your correction.
    case answer(Int)
    /// Answered with option `i` by a rule (A3: the model was not asked), not corrected.
    case rule(Int)

    var order: Int {
        switch self {
        case .unusable: return 0
        case .unsure: return 1
        case .answer(let i): return 10 + i
        case .rule(let i): return 1000 + i
        }
    }

    static func < (a: ResultBucket, b: ResultBucket) -> Bool { a.order < b.order }

    /// A stable id for accessibility identifiers and tests: `unsure`, `answer.0`, `rule.1`.
    var key: String {
        switch self {
        case .unusable: return "unusable"
        case .unsure: return "unsure"
        case .answer(let i): return "answer.\(i)"
        case .rule(let i): return "rule.\(i)"
        }
    }
}

/// Who gave an item its current answer.
enum Answerer: String, CaseIterable, Hashable {
    case rule, model, you, waiting, failed

    var title: String {
        switch self {
        case .rule: return "Rules"
        case .model: return "Decision model"
        case .you: return "You"
        case .waiting: return "Waiting for you"
        case .failed: return "Could not judge"
        }
    }
}

/// One judged item, as plain values. `correction` is the only field that changes after the index is built.
struct ResultRecord: Equatable {
    let itemId: String
    let name: String
    /// Folded (case, diacritics, width) name + source + kind: what search matches against.
    let searchKey: String
    /// The source's id (`sample`, `photos`, `files`, `mail`, …), or `gone` when the item is no longer scanned.
    let source: String
    /// The item kind's title (`PDF`, `email`, `image`, …).
    let kind: String
    /// `yyyy-MM-dd`, when the item has a date.
    let day: String?
    /// `yyyy-MM`, derived from `day`.
    let month: String?
    /// Index of the top label in the judgment's options, or -1.
    let top: Int
    /// The raw mass on the top label (the model's confidence; 1 for a rule).
    let mass: Double
    /// At or above the threshold (and not held back by a cut input): the engine acted.
    let acted: Bool
    let unusable: Bool
    /// The mechanical check that answered (`no-transaction-evidence`, `exact-duplicate`, `baseline`, …), or nil.
    let check: String?
    /// Options a correction may name (their indices as bits): the labels in the row's distribution.
    let correctable: UInt64
    /// Your answer, as an option index, or nil.
    var correction: Int?

    init(itemId: String, name: String, source: String, kind: String, day: String?, top: Int, mass: Double,
         acted: Bool, unusable: Bool, check: String?, correctable: UInt64 = .max, correction: Int? = nil,
         extraSearch: String = "") {
        self.itemId = itemId
        self.name = name
        self.searchKey = ResultsIndex.fold([name, ResultsNames.source(source), kind, extraSearch].joined(separator: " "))
        self.source = source
        self.kind = kind
        self.day = day
        self.month = day.map { String($0.prefix(7)) }
        self.top = top
        self.mass = mass
        self.acted = acted
        self.unusable = unusable
        self.check = check
        self.correctable = correctable
        self.correction = correction
    }

    /// A model answer (not a rule, not unusable): the rows the confidence histogram counts.
    var isModel: Bool { check == nil && !unusable && top >= 0 }

    var bucket: ResultBucket {
        if let c = correction { return .answer(c) }
        if unusable { return .unusable }
        if check != nil { return top >= 0 ? .rule(top) : .unsure }
        if acted && top >= 0 { return .answer(top) }
        return .unsure
    }

    var answerer: Answerer {
        if correction != nil { return .you }
        if unusable { return .failed }
        if check != nil { return .rule }
        if acted { return .model }
        return .waiting
    }

    /// The option this item currently reads as (your answer, else the rule's or the model's), or nil when unsure.
    var answer: Int? {
        switch bucket {
        case .answer(let i), .rule(let i): return i
        default: return nil
        }
    }

    func canCorrect(to option: Int) -> Bool { option >= 0 && option < 64 && correctable & (1 << UInt64(option)) != 0 }
}

/// Counts for one value of a breakdown (a source, a kind, a month), split by bucket for the stacked bars.
struct BreakdownCount: Equatable {
    var total = 0
    var buckets: [ResultBucket: Int] = [:]

    mutating func add(_ b: ResultBucket, _ sign: Int) {
        total += sign
        buckets[b, default: 0] += sign
        if buckets[b] == 0 { buckets[b] = nil }
    }
}

/// The model's confidence histogram: fixed-width bins from `lower` to 1, counts per option.
struct ConfidenceBins: Equatable {
    static let width = 0.05
    /// Where the first bin starts: 1/options rounded down to the bin width (0.5 for yes/no).
    let lower: Double
    let binCount: Int
    /// `counts[bin][option]`.
    var counts: [[Int]]

    init(options: Int) {
        let floorShare = 1.0 / Double(max(options, 1))
        lower = (floorShare / Self.width + 1e-9).rounded(.down) * Self.width
        binCount = max(1, Int(((1 - lower) / Self.width).rounded()))
        counts = Array(repeating: Array(repeating: 0, count: max(options, 1)), count: binCount)
    }

    /// The bin a mass falls in: [lo, hi), the last bin also takes 1.0. The epsilon keeps 0.85 out of the 0.80 bin.
    func bin(for mass: Double) -> Int {
        let raw = ((mass - lower) / Self.width + 1e-9).rounded(.down)
        return min(binCount - 1, max(0, Int(raw)))
    }

    func range(_ bin: Int) -> (lo: Double, hi: Double) {
        let lo = lower + Double(bin) * Self.width
        return (lo, min(1, lo + Self.width))
    }

    func total(_ bin: Int) -> Int { counts[bin].reduce(0, +) }

    var total: Int { counts.reduce(0) { $0 + $1.reduce(0, +) } }

    /// Bins whose range meets [threshold − margin, threshold + margin): "the borderline ones".
    func borderline(threshold: Double, margin: Double = 0.1) -> ClosedRange<Int> {
        let a = bin(for: max(lower, threshold - margin))
        let b = bin(for: min(1, threshold + margin - 1e-6))
        return min(a, b)...max(a, b)
    }
}

/// Every number the dashboard shows, from one pass over the records.
struct ResultsSummary: Equatable {
    var total = 0
    var buckets: [ResultBucket: Int] = [:]
    var answerers: [Answerer: Int] = [:]
    /// Rule answers by check, for items a rule still answers (not corrected).
    var checks: [String: Int] = [:]
    var sources: [String: BreakdownCount] = [:]
    var kinds: [String: BreakdownCount] = [:]
    var months: [String: BreakdownCount] = [:]
    /// How many items had no date (the timeline says so).
    var undated = 0

    func count(_ b: ResultBucket) -> Int { buckets[b] ?? 0 }
    func count(_ a: Answerer) -> Int { answerers[a] ?? 0 }

    /// Buckets that have items, in display order.
    var orderedBuckets: [ResultBucket] { buckets.keys.filter { (buckets[$0] ?? 0) > 0 }.sorted() }

    mutating func add(_ r: ResultRecord, _ sign: Int) {
        let b = r.bucket
        let a = r.answerer
        total += sign
        buckets[b, default: 0] += sign
        if buckets[b] == 0 { buckets[b] = nil }
        answerers[a, default: 0] += sign
        if answerers[a] == 0 { answerers[a] = nil }
        if a == .rule, let c = r.check {
            checks[c, default: 0] += sign
            if checks[c] == 0 { checks[c] = nil }
        }
        sources[r.source, default: BreakdownCount()].add(b, sign)
        if sources[r.source]?.total == 0 { sources[r.source] = nil }
        kinds[r.kind, default: BreakdownCount()].add(b, sign)
        if kinds[r.kind]?.total == 0 { kinds[r.kind] = nil }
        if let m = r.month {
            months[m, default: BreakdownCount()].add(b, sign)
            if months[m]?.total == 0 { months[m] = nil }
        } else {
            undated += sign
        }
    }
}

/// What the list shows: every facet set is ANDed; a nil facet does not filter.
struct ResultsFilter: Equatable {
    var bucket: ResultBucket?
    var answerer: Answerer?
    /// A rule check (implies answerer == .rule).
    var check: String?
    /// Confidence bins (model answers only).
    var bins: ClosedRange<Int>?
    var source: String?
    var kind: String?
    var month: String?
    var query: String = ""

    var isEmpty: Bool { facetsEmpty && trimmedQuery.isEmpty }
    var facetsEmpty: Bool {
        bucket == nil && answerer == nil && check == nil && bins == nil && source == nil && kind == nil && month == nil
    }
    var trimmedQuery: String { query.trimmingCharacters(in: .whitespacesAndNewlines) }

    /// Equal apart from the search text.
    func sameFacets(as o: ResultsFilter) -> Bool {
        bucket == o.bucket && answerer == o.answerer && check == o.check && bins == o.bins
            && source == o.source && kind == o.kind && month == o.month
    }
}

enum ResultsSort: String, CaseIterable, Identifiable {
    /// Least sure first (unusable, then lowest confidence): the order that needs you most.
    case confidence
    /// Newest first; undated last.
    case date
    case name

    var id: String { rawValue }
    var title: String {
        switch self {
        case .confidence: return "Least sure first"
        case .date: return "Newest first"
        case .name: return "Name"
        }
    }
}

/// One group of the list: the rows of one bucket, in the chosen order.
struct ResultSection: Equatable, Identifiable {
    let bucket: ResultBucket
    let rows: [Int32]
    var id: String { bucket.key }
}

/// The index over one judgment's results: built once per run, updated in place by corrections.
final class ResultsIndex {
    let options: [String]
    let threshold: Double
    private(set) var records: [ResultRecord]
    private(set) var summary: ResultsSummary
    /// The model's confidence per answer (fixed per run: a correction does not change what the model said).
    let bins: ConfidenceBins
    private let orders: [ResultsSort: [Int32]]
    private let byItem: [String: Int]

    // The incremental filter: matches per search prefix under the same facets and sort.
    private var cacheFacets: ResultsFilter?
    private var cacheSort: ResultsSort?
    private var cache: [String: [Int32]] = [:]

    init(options: [String], threshold: Double, records: [ResultRecord]) {
        self.options = options
        self.threshold = threshold
        self.records = records
        var s = ResultsSummary()
        var bins = ConfidenceBins(options: options.count)
        var byItem: [String: Int] = [:]
        byItem.reserveCapacity(records.count)
        for (i, r) in records.enumerated() {
            s.add(r, 1)
            if r.isModel, r.top < options.count { bins.counts[bins.bin(for: r.mass)][r.top] += 1 }
            byItem[r.itemId] = i
        }
        summary = s
        self.bins = bins
        self.byItem = byItem
        orders = Self.makeOrders(records)
    }

    var count: Int { records.count }

    func index(of itemId: String) -> Int? { byItem[itemId] }

    /// Model answers within 10 points of the threshold, either side.
    var borderlineBins: ClosedRange<Int> { bins.borderline(threshold: threshold) }

    var borderlineCount: Int { borderlineBins.reduce(0) { $0 + bins.total($1) } }

    // MARK: Corrections

    /// Moves one record to its new answer: the summary changes by exactly that record (no recount).
    func setCorrection(_ i: Int, _ option: Int?) {
        guard records.indices.contains(i), records[i].correction != option else { return }
        summary.add(records[i], -1)
        records[i].correction = option
        summary.add(records[i], 1)
        invalidate()
    }

    private func invalidate() {
        cache.removeAll()
        cacheFacets = nil
        cacheSort = nil
    }

    // MARK: Filtering

    func matches(_ r: ResultRecord, _ f: ResultsFilter) -> Bool {
        if let b = f.bucket, r.bucket != b { return false }
        if let a = f.answerer, r.answerer != a { return false }
        if let c = f.check, r.answerer != .rule || r.check != c { return false }
        if let bins = f.bins {
            guard r.isModel, bins.contains(self.bins.bin(for: r.mass)) else { return false }
        }
        if let s = f.source, r.source != s { return false }
        if let k = f.kind, r.kind != k { return false }
        if let m = f.month, r.month != m { return false }
        return true
    }

    /// The records that pass `filter`, in `sort` order. A search that extends the previous one under the same
    /// facets narrows the previous matches; a shorter one (a deleted letter) comes back from the cache.
    func filtered(_ filter: ResultsFilter, sort: ResultsSort) -> [Int32] {
        let q = Self.fold(filter.trimmedQuery)
        if cacheFacets == nil || !cacheFacets!.sameFacets(as: filter) || cacheSort != sort {
            cache.removeAll()
            cacheFacets = filter
            cacheSort = sort
        }
        if let hit = cache[q] { return hit }
        let base: [Int32]
        if let facets = cache[""] {
            base = facets
        } else {
            let order = orders[sort] ?? []
            base = filter.facetsEmpty ? order : order.filter { matches(records[Int($0)], filter) }
            cache[""] = base
        }
        if q.isEmpty { return base }
        // The longest cached prefix of this query is a superset of its matches.
        var from = base
        var best = 0
        for (k, v) in cache where !k.isEmpty && k.count > best && q.hasPrefix(k) {
            best = k.count
            from = v
        }
        let hit = from.filter { records[Int($0)].searchKey.contains(q) }
        if cache.count > 64 { cache = cache.filter { $0.key.isEmpty } }
        cache[q] = hit
        return hit
    }

    /// Groups matches by bucket (display order), keeping the sort order inside each group.
    func sections(_ matched: [Int32]) -> [ResultSection] {
        var groups: [ResultBucket: [Int32]] = [:]
        for i in matched { groups[records[Int(i)].bucket, default: []].append(i) }
        return groups.keys.sorted().map { ResultSection(bucket: $0, rows: groups[$0]!) }
    }

    // MARK: Helpers

    static func fold(_ s: String) -> String {
        s.folding(options: [.caseInsensitive, .diacriticInsensitive, .widthInsensitive], locale: nil)
    }

    private static func makeOrders(_ records: [ResultRecord]) -> [ResultsSort: [Int32]] {
        let all = Array(0..<Int32(records.count))
        let confidence = all.sorted { a, b in
            let x = records[Int(a)], y = records[Int(b)]
            if x.unusable != y.unusable { return x.unusable }
            if x.mass != y.mass { return x.mass < y.mass }
            return a < b
        }
        let date = all.sorted { a, b in
            let x = records[Int(a)].day, y = records[Int(b)].day
            switch (x, y) {
            case let (p?, q?) where p != q: return p > q
            case (nil, _?): return false
            case (_?, nil): return true
            default: return a < b
            }
        }
        // One folded key per record, compared as plain strings (a localized compare per pair is far slower).
        let keys = records.map { fold($0.name) }
        let name = all.sorted { a, b in
            let x = keys[Int(a)], y = keys[Int(b)]
            return x != y ? x < y : a < b
        }
        return [.confidence: confidence, .date: date, .name: name]
    }

    /// Whole percentages that add up to exactly 100 (largest remainder), for the legend. All zero when total is 0.
    static func percentages(_ counts: [Int]) -> [Int] {
        let total = counts.reduce(0, +)
        guard total > 0 else { return counts.map { _ in 0 } }
        let exact = counts.map { Double($0) * 100 / Double(total) }
        var floors = exact.map { Int($0.rounded(.down)) }
        let short = 100 - floors.reduce(0, +)
        let order = exact.indices.sorted { a, b in
            let ra = exact[a] - Double(floors[a]), rb = exact[b] - Double(floors[b])
            if ra != rb { return ra > rb }
            if counts[a] != counts[b] { return counts[a] > counts[b] }
            return a < b
        }
        for i in order.prefix(short) { floors[i] += 1 }
        return floors
    }
}

/// Plain names for source ids and rule checks (shared by the charts, chips and rows).
enum ResultsNames {
    static func source(_ id: String) -> String {
        switch id {
        case "sample": return "Sample data"
        case "photos": return "Photos"
        case "files": return "Files"
        case "shared": return "Send to Loupe"
        case "mail": return "Mail"
        case "calendar": return "Calendar"
        case "contacts": return "Contacts"
        case "inbox": return "Inbox"
        case "gone": return "No longer scanned"
        default: return id.isEmpty ? "Unknown" : id.prefix(1).uppercased() + id.dropFirst()
        }
    }

    /// A rule check in a few words.
    static func check(_ c: String) -> String {
        switch c {
        case "no-transaction-evidence": return "No sign of a payment"
        case "exact-duplicate": return "Exact duplicate"
        case "baseline": return "Baseline rule (always)"
        case "auto-baseline": return "Baseline rule (beat the model)"
        case "laya-off": return "Baseline rule (model off)"
        default: return c
        }
    }

    /// "yyyy-MM" → "Mar 2026".
    static func month(_ m: String) -> String {
        let parts = m.split(separator: "-")
        guard parts.count == 2, let y = Int(parts[0]), let mo = Int(parts[1]), (1...12).contains(mo) else { return m }
        return "\(monthNames[mo - 1]) \(y)"
    }

    private static let monthNames = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"]

    /// 12,345 with grouping (shown in mono tabular numbers).
    static func count(_ n: Int) -> String { n.formatted(.number.grouping(.automatic)) }
}
