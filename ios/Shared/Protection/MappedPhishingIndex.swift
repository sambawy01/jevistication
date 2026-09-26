import Foundation
import LoupeKit

/// Phishing.Database's flat index (`PhishingDbBinary`, written by the app into the App Group), mapped
/// into memory by Loupe for Safari and "Send to Loupe". The 100 MB of list text would not fit an
/// extension; the index is three sorted arrays of 64-bit hashes (about 8 MB for the whole list),
/// and the mapping keeps them out of the extension's own memory (clean, file-backed pages).
///
/// Matching stays in LoupeKit: for one URL, this looks up the three hashes `PhishingDbIndex.match`
/// would look for (the URL key, the host, the registrable domain) and hands LoupeKit a tiny
/// `PhishingDbIndex` holding only the ones that are listed. LoupeKit's own `match` then applies
/// every rule (the shared-host suppression, url → host → domain), so the answer is the app's.
final class MappedPhishingIndex {
    let listDate: String?
    let linkCount: Int
    let hostCount: Int
    let truncated: Bool
    private let data: Data
    private let urls: Range<Int>
    private let hosts: Range<Int>
    private let domains: Range<Int>

    /// nil when the file is missing, is not a whole index, or has another version.
    convenience init?(file: URL) {
        guard let data = try? Data(contentsOf: file, options: .alwaysMapped) else { return nil }
        self.init(data: data)
    }

    init?(data: Data) {
        let header = 64
        guard data.count >= header, data.prefix(8) == Data("LPDBIDX1".utf8) else { return nil }
        func long(_ at: Int) -> Int64 {
            data.withUnsafeBytes { raw in Int64(littleEndian: raw.loadUnaligned(fromByteOffset: at, as: Int64.self)) }
        }
        let counts = (0..<7).map { long(8 + 8 * $0) }
        guard counts.allSatisfy({ $0 >= 0 && $0 < Int64(Int32.max) }) else { return nil }
        let (nu, nh, nd) = (Int(counts[0]), Int(counts[1]), Int(counts[2]))
        let dateLen = Int(counts[6])
        let start = header + (dateLen + 7) / 8 * 8
        guard data.count == start + 8 * (nu + nh + nd) else { return nil }
        self.data = data
        let dateBytes = data.subdata(in: header..<(header + dateLen))
        listDate = dateLen == 0 ? nil : String(decoding: dateBytes, as: UTF8.self)
        linkCount = Int(counts[3])
        hostCount = Int(counts[4])
        truncated = counts[5] != 0
        urls = start..<(start + 8 * nu)
        hosts = urls.upperBound..<(urls.upperBound + 8 * nh)
        domains = hosts.upperBound..<(hosts.upperBound + 8 * nd)
    }

    var entryCount: Int { (urls.count + hosts.count + domains.count) / 8 }

    /// Binary search of one sorted Int64 array inside the mapping.
    private func has(_ range: Range<Int>, _ v: Int64) -> Bool {
        data.withUnsafeBytes { raw in
            var lo = 0, hi = range.count / 8 - 1
            while lo <= hi {
                let mid = (lo + hi) >> 1
                let x = Int64(littleEndian: raw.loadUnaligned(fromByteOffset: range.lowerBound + 8 * mid, as: Int64.self))
                if x < v { lo = mid + 1 } else if x > v { hi = mid - 1 } else { return true }
            }
            return false
        }
    }

    /// The listed hashes for [url] as a LoupeKit index (usually empty), for `OnlineContext.phishingDb`.
    func slice(for url: String) -> PhishingDbIndex? {
        guard let key = ListUrls.shared.normalize(url: url), let k = key.first as String?, let host = key.second as String? else { return nil }
        let reg = Hosts.shared.registrableDomain(host: host) ?? host
        func pick(_ range: Range<Int>, _ values: [String]) -> KotlinLongArray {
            let found = Array(Set(values.map { PhishingDb.shared.hash64(s: $0) }.filter { has(range, $0) })).sorted()
            let out = KotlinLongArray(size: Int32(found.count))
            for (i, v) in found.enumerated() { out.set(index: Int32(i), value: v) }
            return out
        }
        return PhishingDbIndex(urls: pick(urls, [k]), hosts: pick(hosts, [host]), domains: pick(domains, [reg]),
                               listDate: listDate, linkCount: Int32(clamping: linkCount), hostCount: Int32(clamping: hostCount), truncated: truncated)
    }
}
