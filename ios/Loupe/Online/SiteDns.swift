import Darwin
import Foundation
import LoupeKit

/// DNS through this iPhone's own system resolver (formula v1.2 §5b / §5c): `res_9_nsend` from the
/// system's libresolv, looked up at run time. No DNS-over-HTTPS provider and no third party: the
/// question goes wherever the phone's network sends every other DNS question. LoupeKit builds the
/// packet (RD + AD bits, EDNS0) and checks the answer (`DnsWire`, the ID, the echoed question and
/// every length); this only moves bytes. Each query gets its own resolver state (thread-safe) and a
/// deadline of 3 s.
final class SystemDnsTransport: NSObject, DnsTransport {
    private typealias Ninit = @convention(c) (UnsafeMutableRawPointer) -> Int32
    private typealias Nsend = @convention(c) (UnsafeMutableRawPointer, UnsafePointer<UInt8>, Int32, UnsafeMutablePointer<UInt8>, Int32) -> Int32
    private typealias Ndestroy = @convention(c) (UnsafeMutableRawPointer) -> Void

    private struct Fns { let ninit: Ninit; let nsend: Nsend; let ndestroy: Ndestroy }

    private static let fns: Fns? = {
        guard let lib = dlopen("/usr/lib/libresolv.9.dylib", RTLD_NOW) ?? dlopen("libresolv.9.dylib", RTLD_NOW),
              let a = dlsym(lib, "res_9_ninit"), let b = dlsym(lib, "res_9_nsend"), let c = dlsym(lib, "res_9_ndestroy") else { return nil }
        return Fns(ninit: unsafeBitCast(a, to: Ninit.self), nsend: unsafeBitCast(b, to: Nsend.self), ndestroy: unsafeBitCast(c, to: Ndestroy.self))
    }()

    /// sizeof(struct __res_9_state) is 552 on arm64; a larger zeroed block is used.
    private static let stateBytes = 4096
    static let deadline: TimeInterval = 3

    private final class Slot: @unchecked Sendable {
        let lock = NSLock()
        var answer: [UInt8]?
    }

    func send(packet: KotlinByteArray) -> KotlinByteArray? {
        guard let fns = Self.fns else { return nil }
        let query = (0..<Int(packet.size)).map { UInt8(bitPattern: packet.get(index: Int32($0))) }
        let slot = Slot()
        let done = DispatchSemaphore(value: 0)
        let thread = Thread {
            let state = UnsafeMutableRawPointer.allocate(byteCount: Self.stateBytes, alignment: 16)
            state.initializeMemory(as: UInt8.self, repeating: 0, count: Self.stateBytes)
            defer { state.deallocate() }
            guard fns.ninit(state) == 0 else { done.signal(); return }
            // retrans / retry are the first two ints of the state (BIND's __res_state layout): 1 s, 2 tries
            state.storeBytes(of: Int32(1), toByteOffset: 0, as: Int32.self)
            state.storeBytes(of: Int32(2), toByteOffset: 4, as: Int32.self)
            var answer = [UInt8](repeating: 0, count: 4096)
            let n = query.withUnsafeBufferPointer { q in
                answer.withUnsafeMutableBufferPointer { a in fns.nsend(state, q.baseAddress!, Int32(q.count), a.baseAddress!, Int32(a.count)) }
            }
            fns.ndestroy(state)
            if n > 0 {
                slot.lock.lock(); slot.answer = Array(answer.prefix(Int(n))); slot.lock.unlock()
            }
            done.signal()
        }
        thread.start()
        guard done.wait(timeout: .now() + Self.deadline) == .success else { return nil }
        slot.lock.lock(); defer { slot.lock.unlock() }
        guard let bytes = slot.answer else { return nil }
        let out = KotlinByteArray(size: Int32(bytes.count))
        for (i, b) in bytes.enumerated() { out.set(index: Int32(i), value: Int8(bitPattern: b)) }
        return out
    }
}

/// The v1.2 site facts the phone looks up for link domains: DNS facts (cached 6 h, 15 min when four
/// or more questions failed) and blocklist answers (1 h, 10 min when a list was unavailable), for
/// ICANN registrable domains only. Blocking: call it off the main thread.
final class SiteFactsLookup {
    static let dnsTTL: TimeInterval = 6 * 3600
    static let dnsErrorTTL: TimeInterval = 15 * 60
    static let dnsblTTL: TimeInterval = 3600
    static let dnsblUnavailableTTL: TimeInterval = 10 * 60

    let resolver: DnsQuery
    private let blocklists: DnsblClient
    private let now: () -> Date
    private let lock = NSLock()
    private var dnsCache: [String: (DnsFacts, Date)] = [:]
    private var blCache: [String: ([String: DnsblResult], Date)] = [:]

    init(resolver: DnsQuery = WireResolver(transport: SystemDnsTransport()), now: @escaping () -> Date = Date.init) {
        self.resolver = resolver
        self.blocklists = DnsblClient(resolver: resolver)
        self.now = now
    }

    struct Result { var dns: [String: DnsFacts] = [:]; var dnsbl: [String: [String: DnsblResult]] = [:]; var noAnswer = false }

    /// Facts for [domains]. Stops asking when the resolver answers nothing for the first domain.
    func lookup(domains: [String], dns: Bool, lists: [String], at: String) -> Result {
        var out = Result()
        for d in domains {
            if dns {
                lock.lock(); let hit = dnsCache[d]; lock.unlock()
                if let hit, now().timeIntervalSince(hit.1) < (hit.0.errors.count >= 4 ? Self.dnsErrorTTL : Self.dnsTTL) {
                    out.dns[d] = hit.0
                } else {
                    let f = DnsFactsReader.shared.read(r: resolver, domain: d, fetchedAt: at)
                    lock.lock(); dnsCache[d] = (f, now()); lock.unlock()
                    out.dns[d] = f
                    if f.errors.count >= 5 && out.dns.count == 1 { out.noAnswer = true; break }
                }
            }
            if !lists.isEmpty {
                lock.lock(); let hit = blCache[d]; lock.unlock()
                let unavailable = hit?.0.values.contains { $0.status == "unavailable" } ?? false
                if let hit, Set(hit.0.keys) == Set(lists), now().timeIntervalSince(hit.1) < (unavailable ? Self.dnsblUnavailableTTL : Self.dnsblTTL) {
                    out.dnsbl[d] = hit.0
                } else {
                    let r = blocklists.check(domain: d, sources: lists)
                    lock.lock(); blCache[d] = (r, now()); lock.unlock()
                    out.dnsbl[d] = r
                }
            }
        }
        return out
    }

    func forget() {
        lock.lock(); dnsCache = [:]; blCache = [:]; lock.unlock()
    }
}
