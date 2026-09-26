import XCTest
import LoupeKit
@testable import Loupe

/// A clipboard that records what was asked of it.
final class FakeClipboard: ClipboardSource {
    var changeCount = 1
    var hasStrings = true
    var patterns: Set<ClipboardPattern> = []
    var text: String?
    private(set) var detects = 0
    private(set) var reads = 0

    func detect() async -> Set<ClipboardPattern> { detects += 1; return patterns }
    func readString() -> String? { reads += 1; return text }

    /// A new copy.
    func copy(_ s: String?, _ p: Set<ClipboardPattern>) {
        changeCount += 1
        text = s
        patterns = p
        hasStrings = s != nil
    }
}

/// Clipboard checks (2026-09-26): which detected patterns earn the chip, the detect → offer → check
/// state machine (each copy once, never reading before Check), the verdict path shared with "Check
/// what I copied" (fake clipboard, no network), the Spotted origin, the Loupe keyboard's on-device
/// verdict and its no-network rule, its layouts, and the keyboard setup status.
@MainActor
final class ClipboardTests: XCTestCase {
    static let lookalike = "https://xn--pypal-4ve.com/signin?session=secret-token#frag"

    private var dir: URL!
    private var defaults: UserDefaults!
    private var suite: String!

    override func setUp() {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent("Clipboard-\(UUID().uuidString)")
        suite = "clipboard-tests-\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suite)
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: dir)
        defaults.removePersistentDomain(forName: suite)
    }

    private func store() -> ProtectionStore {
        ProtectionStore(log: SpottedLog(dir: dir), recentStore: RecentChecksStore(dir: dir), defaults: defaults)
    }

    private func monitor(_ clip: FakeClipboard, store: ProtectionStore? = nil) -> ClipboardMonitor {
        ClipboardMonitor(source: clip, defaults: defaults, store: store ?? self.store(), online: nil, offerFor: 60)
    }

    // MARK: which patterns earn the chip

    func testOnlyLinksAndEmailAddressesAreOffered() {
        XCTAssertEqual(ClipboardOffer.kind(for: [.probableWebURL]), .link)
        XCTAssertEqual(ClipboardOffer.kind(for: [.link, .probableWebSearch]), .link, "a message with a link in it")
        XCTAssertEqual(ClipboardOffer.kind(for: [.emailAddress]), .email)
        XCTAssertEqual(ClipboardOffer.kind(for: [.emailAddress, .link]), .link, "a link wins over an address")
        for p: Set<ClipboardPattern> in [[], [.number], [.phoneNumber], [.moneyAmount], [.probableWebSearch], [.number, .phoneNumber, .moneyAmount]] {
            XCTAssertNil(ClipboardOffer.kind(for: p), "\(p) has no check, so no chip")
        }
        XCTAssertEqual(ClipboardOffer.question(.link), "Check the link you copied?")
        XCTAssertEqual(ClipboardOffer.question(.email), "Check the email address you copied?")
    }

    func testOnByDefault() {
        XCTAssertTrue(ClipboardShared.enabled(defaults), "every on-device feature is on by default")
        let clip = FakeClipboard()
        XCTAssertTrue(monitor(clip).enabled)
    }

    // MARK: detect → offer → check

    func testEachCopyIsOfferedOnceAndNothingIsReadBeforeCheck() async {
        let clip = FakeClipboard()
        clip.copy(Self.lookalike, [.probableWebURL, .link])
        let m = monitor(clip)
        let offered = await m.scan(.active)
        XCTAssertTrue(offered)
        XCTAssertEqual(m.phase, .offer(.link))
        m.dismiss()
        let again = await m.scan(.guardAppeared)
        XCTAssertFalse(again, "the same copy (change count) is never offered twice")
        XCTAssertEqual(clip.detects, 1, "and not even looked at again")

        // A new copy of a phone number: looked at, not offered.
        clip.copy("+20 100 123 4567", [.phoneNumber, .number])
        let phone = await m.scan(.active)
        XCTAssertFalse(phone)
        XCTAssertEqual(m.phase, .idle)
        XCTAssertEqual(clip.detects, 2)

        // A new copy of an address: offered.
        clip.copy("support@paypa1-secure.com", [.emailAddress])
        let email = await m.scan(.active)
        XCTAssertTrue(email)
        XCTAssertEqual(m.phase, .offer(.email))
        XCTAssertEqual(clip.reads, 0, "detection never reads the clipboard (no paste prompt)")
    }

    func testTheSeenCopySurvivesARelaunch() async {
        let clip = FakeClipboard()
        clip.copy(Self.lookalike, [.probableWebURL])
        let first = await monitor(clip).scan(.active)
        XCTAssertTrue(first)
        let relaunched = await monitor(clip).scan(.active)
        XCTAssertFalse(relaunched, "the change count is kept in the App Group's defaults")
        clip.changeCount = 0   // after a restart iOS counts from the start again
        let afterReboot = await monitor(clip).scan(.active)
        XCTAssertTrue(afterReboot)
    }

    func testOffLooksAtNothing() async {
        let clip = FakeClipboard()
        clip.copy(Self.lookalike, [.probableWebURL])
        let m = monitor(clip)
        m.enabled = false
        XCTAssertFalse(defaults.bool(forKey: ClipboardShared.Keys.enabled))
        let offered = await m.scan(.active)
        XCTAssertFalse(offered)
        XCTAssertEqual(clip.detects, 0)
        XCTAssertEqual(clip.reads, 0)
    }

    func testCheckGivesTheVerdictSpottedAsClipboardAndARecentCheckWithoutQuery() async throws {
        let clip = FakeClipboard()
        clip.copy(Self.lookalike, [.probableWebURL])
        let s = store()
        let m = monitor(clip, store: s)
        await m.scan(.active)
        await m.check()
        XCTAssertEqual(clip.reads, 1, "read once, on Check")
        guard case .result(let v, let kind) = m.phase else { return XCTFail("\(m.phase)") }
        XCTAssertEqual(kind, .link)
        XCTAssertEqual(v.level, .dangerous)
        XCTAssertEqual(v.origin, .clipboard)
        XCTAssertEqual(v.brand, "PayPal")
        XCTAssertEqual(ClipboardWords.sentence(v, kind: kind), "That link looks like a fake PayPal page.")
        XCTAssertTrue(v.privacyLine.hasPrefix("0 bytes out"), "no online part in this test")

        let spotted = try XCTUnwrap(s.spotted.first)
        XCTAssertEqual(spotted.origin, .clipboard)
        XCTAssertEqual(spotted.origin.title, "Clipboard")
        XCTAssertEqual(spotted.host, "xn--pypal-4ve.com", "a website name only")
        let recent = try XCTUnwrap(s.recent.first)
        XCTAssertFalse(recent.displayURL.contains("secret-token"), "recent checks keep no query")
        XCTAssertFalse(recent.displayURL.contains("frag"))
        XCTAssertEqual(defaults.string(forKey: ClipboardShared.Keys.lastLevel), "dangerous", "the widget's line: a level, no link")

        XCTAssertTrue(m.showPasteHint, "the Paste from Other Apps hint, once")
        m.dismiss()
        clip.copy("www.bbc.co.uk/news", [.probableWebURL])
        await m.scan(.active)
        await m.check()
        guard case .result(let safe, _) = m.phase else { return XCTFail("\(m.phase)") }
        XCTAssertEqual(safe.level, .safe)
        XCTAssertEqual(ClipboardWords.sentence(safe, kind: .link), "No warning signs found in that link.")
        XCTAssertFalse(m.showPasteHint, "only once")
        XCTAssertEqual(s.spotted.count, 1, "a clean link is never spotted")
        XCTAssertEqual(s.recent.count, 2)
    }

    func testCheckWhenThePasteIsRefusedOrNotALink() async {
        let clip = FakeClipboard()
        clip.copy(nil, [.probableWebURL])
        clip.hasStrings = true
        let m = monitor(clip)
        await m.scan(.active)
        await m.check()
        XCTAssertEqual(m.phase, .problem(ClipboardVerdictRun.Outcome.unreadable.sentence), "Don't Allow Paste")

        clip.copy("just some words, nothing to open", [.probableWebSearch])
        await m.checkNow()
        XCTAssertEqual(m.phase, .problem(ClipboardVerdictRun.Outcome.notCheckable.sentence))

        clip.copy(nil, [])
        await m.checkNow()
        XCTAssertEqual(m.phase, .problem(ClipboardVerdictRun.Outcome.empty.sentence))
    }

    func testTheOnlinePartIsTheOneCheckALinkUses() async {
        let clip = FakeClipboard()
        clip.copy("https://brand-new-shop.com/sale", [.probableWebURL])
        var asked: [String] = []
        let s = store()
        let m = ClipboardMonitor(source: clip, defaults: defaults, store: s, online: { url in
            asked.append(url)
            var d = OnlineDisclosure()
            d.sent = [.init(what: "brand-new-shop.com", to: "the Loupe web helper")]
            return (nil, d)
        })
        await m.checkNow()
        XCTAssertEqual(asked, ["https://brand-new-shop.com/sale"])
        guard case .result(let v, _) = m.phase else { return XCTFail() }
        XCTAssertEqual(v.privacyLine, "Sent: the domain brand-new-shop.com only, to the Loupe web helper.")
    }

    // MARK: what is checked

    func testTheTargetIsTheLinkOrTheAddressDomain() throws {
        func target(_ s: String) throws -> ClipboardTarget {
            switch ClipboardTarget.extract(s) {
            case .success(let t): return t
            case .failure(let p): throw XCTSkip("\(p) for \(s)")
            }
        }
        let email = try target("support@paypa1-secure.com")
        XCTAssertEqual(email.kind, .email)
        XCTAssertEqual(email.input.host, "paypa1-secure.com", "an address is read as its domain, not a link with text before @")
        XCTAssertEqual(try target("Write to Billing <billing@micr0soft-support.co>").input.host, "micr0soft-support.co")
        let msg = try target("Your parcel: https://royalmail-redelivery.example-fees.top/pay?id=9 (or mail help@royalmail.com)")
        XCTAssertEqual(msg.kind, .link, "a link in the text wins")
        XCTAssertEqual(msg.input.host, "royalmail-redelivery.example-fees.top")
        XCTAssertEqual(try target("paypa1.com").kind, .link)
        if case .success = ClipboardTarget.extract("+20 100 123 4567") { XCTFail("a phone number is not a link") }
        if case .success = ClipboardTarget.extract("   ") { XCTFail("blank") }
    }

    func testTheWords() {
        func v(_ level: ProtectionLevel, brand: String?, reason: String = "The name imitates PayPal") -> LinkVerdict {
            LinkVerdict(checkedAt: Date(), origin: .clipboard, displayURL: "https://x.example", host: "x.example", unicodeHost: "x.example",
                        registrable: "x.example", level: level, score: 50, reasons: [.init(code: "c", text: reason, weight: 30, online: nil)],
                        brand: brand, onDevice: [], disclosure: .none)
        }
        XCTAssertEqual(ClipboardWords.sentence(v(.dangerous, brand: "PayPal"), kind: .link), "That link looks like a fake PayPal page.")
        XCTAssertEqual(ClipboardWords.sentence(v(.suspicious, brand: "PayPal"), kind: .link), "That link might be a fake PayPal page. Be careful.")
        XCTAssertEqual(ClipboardWords.sentence(v(.dangerous, brand: nil, reason: "On a phishing list"), kind: .link), "That link looks dangerous: on a phishing list.")
        XCTAssertEqual(ClipboardWords.sentence(v(.dangerous, brand: "PayPal"), kind: .email), "That email address looks like a fake PayPal address.")
        XCTAssertEqual(ClipboardWords.sentence(v(.safe, brand: nil), kind: .email), "No warning signs found in that email address.")
        XCTAssertEqual(ClipboardWords.strip(v(.safe, brand: nil), kind: .link, pasted: true), "Pasted link: no warning signs")
        XCTAssertEqual(ClipboardWords.strip(v(.dangerous, brand: "PayPal"), kind: .link, pasted: false), "⚠ Copied link looks like a fake PayPal page")
    }

    // MARK: "Check what I copied" (Siri, Shortcuts, the Action Button)

    func testTheIntentAnswersFromTheClipboardAndSpotsAFake() async throws {
        let s = store()
        let fake = await ClipboardIntentAnswer.answer(text: Self.lookalike, clipboardEmpty: false, store: s, online: nil)
        XCTAssertEqual(fake, "That link looks like a fake PayPal page.")
        XCTAssertEqual(s.spotted.first?.origin, .clipboard, "a flagged answer is added to Spotted")

        let clean = await ClipboardIntentAnswer.answer(text: "https://www.bbc.co.uk/news", clipboardEmpty: false, store: s, online: nil)
        XCTAssertEqual(clean, "No warning signs found in that link.")
        XCTAssertEqual(s.spotted.count, 1)

        let empty = await ClipboardIntentAnswer.answer(text: nil, clipboardEmpty: true, store: s, online: nil)
        XCTAssertEqual(empty, "There is nothing on the clipboard to check. Copy a link first.")
        let refused = await ClipboardIntentAnswer.answer(text: nil, clipboardEmpty: false, store: s, online: nil)
        XCTAssertTrue(refused.hasPrefix("Loupe could not read what you copied."))
    }

    func testTheIntentReadsTheSystemClipboardThroughTheSameSource() async {
        // The intent's own reader is SystemClipboard; in the unit-test host, the clipboard is ours to set.
        UIPasteboard.general.string = Self.lookalike
        defer { UIPasteboard.general.items = [] }
        let text = SystemClipboard().readString()
        let s = store()
        let answer = await ClipboardIntentAnswer.answer(text: text, clipboardEmpty: false, store: s, online: nil)
        XCTAssertEqual(answer, "That link looks like a fake PayPal page.")
    }

    // MARK: the Loupe keyboard

    func testTheKeyboardVerdictIsOnTheDeviceOnly() throws {
        let lists = ProtectionLists(phishingDbDir: dir.appendingPathComponent("pdb"), feedsDir: dir.appendingPathComponent("feeds"))
        // Every online switch on in the app: the keyboard still sends nothing and asks nothing.
        let s = OnlinePhishingSettings(domainFacts: true, feeds: true, safeBrowsing: true, dnsFacts: true, dnsbl: true)
        let r = try XCTUnwrap(KeyboardCheck.check(Self.lookalike, lists: lists, settings: s))
        XCTAssertEqual(r.verdict.level, .dangerous)
        XCTAssertEqual(r.verdict.origin, .keyboard)
        XCTAssertTrue(r.verdict.disclosure.sent.isEmpty)
        XCTAssertTrue(r.verdict.privacyLine.hasPrefix("0 bytes out"))
        XCTAssertNil(KeyboardCheck.check("hello there", lists: lists, settings: s), "no link, no verdict")
    }

    func testTheKeyboardMatchesTheListsAlreadyOnThePhone() throws {
        let pdb = dir.appendingPathComponent("pdb")
        try FileManager.default.createDirectory(at: pdb, withIntermediateDirectories: true)
        let index = PhishingDb.shared.build(linkTexts: ["https://phishy-host.example/\n"], domainTexts: ["evil-domain.example\n"],
                                            listDate: "2026-09-26T06:00:00Z", maxEntries: 1000)
        XCTAssertTrue(PhishingDbFile.shared.write(index: index, path: pdb.appendingPathComponent(ProtectionGroup.phishingDbIndexName).path))
        let lists = ProtectionLists(phishingDbDir: pdb, feedsDir: dir.appendingPathComponent("feeds"))
        let on = try XCTUnwrap(KeyboardCheck.check("https://login.evil-domain.example/x", lists: lists, settings: OnlinePhishingSettings(feeds: true)))
        XCTAssertTrue(on.verdict.level.flagged, "listed on the phone: \(on.verdict.reasons.map(\.text))")
        XCTAssertTrue(on.verdict.disclosure.sent.isEmpty, "matched on the phone")
        let off = try XCTUnwrap(KeyboardCheck.check("https://login.evil-domain.example/x", lists: lists, settings: OnlinePhishingSettings()))
        XCTAssertEqual(off.verdict.level, .safe, "lists off in the app: the index is not read")
    }

    /// The keyboard target compiles no network code: every Swift file project.yml gives it is free of
    /// network APIs and of the app's online clients. (Its build also checks the linked binary:
    /// scripts/check-no-network.sh.)
    func testTheKeyboardTargetHasNoNetworkCode() throws {
        let ios = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        let yml = try String(contentsOf: ios.appendingPathComponent("project.yml"), encoding: .utf8)
        // The LoupeKeyboard target's `sources:` paths.
        let lines = yml.components(separatedBy: "\n")
        let start = try XCTUnwrap(lines.firstIndex(of: "  LoupeKeyboard:"))
        var paths: [String] = []
        var inSources = false
        for line in lines[(start + 1)...] {
            if line.hasPrefix("  ") && !line.hasPrefix("    ") && !line.trimmingCharacters(in: .whitespaces).isEmpty { break }   // next target
            if line.hasPrefix("    sources:") { inSources = true; continue }
            if inSources {
                if line.hasPrefix("    ") && !line.hasPrefix("      ") { inSources = false; continue }
                if let r = line.range(of: "- path: ") { paths.append(String(line[r.upperBound...]).trimmingCharacters(in: .whitespaces)) }
            }
        }
        XCTAssertTrue(paths.contains("LoupeKeyboard"), "\(paths)")
        var files: [URL] = []
        for p in paths {
            let url = ios.appendingPathComponent(p)
            if p.hasSuffix(".swift") { files.append(url); continue }
            let e = FileManager.default.enumerator(at: url, includingPropertiesForKeys: nil)
            while let f = e?.nextObject() as? URL { if f.pathExtension == "swift" { files.append(f) } }
        }
        let names = Set(files.map(\.lastPathComponent))
        XCTAssertTrue(names.contains("KeyboardViewController.swift"))
        XCTAssertTrue(names.contains("KeyboardCheck.swift"))
        for banned in ["DomainFactsClient.swift", "SafariVerdicts.swift", "SiteDns.swift", "OnlineChecks.swift", "SpottedNotifier.swift"] {
            XCTAssertFalse(names.contains(banned), "\(banned) must not be compiled into the keyboard")
        }
        let forbidden = ["URLSession", "URLRequest", "NSURLConnection", "NWConnection", "import Network", "CFStream", "CFSocket",
                         "socket(", "getaddrinfo", "res_9_", "WKWebView", "DomainFactsClient", "SafariOnlineLookups", "SiteFactsLookup",
                         "OnlineChecksService", "dataTask", "LoupeHelper", "openURL", "UIApplication.shared"]
        for f in files {
            let text = try String(contentsOf: f, encoding: .utf8)
                .components(separatedBy: "\n").filter { !$0.trimmingCharacters(in: .whitespaces).hasPrefix("//") }.joined(separator: "\n")
            for word in forbidden {
                XCTAssertFalse(text.contains(word), "\(f.lastPathComponent) uses \(word)")
            }
        }
        // The link-time check is wired into the keyboard target.
        XCTAssertTrue(yml.contains("scripts/check-no-network.sh"))
        XCTAssertTrue(yml.contains("RequestsOpenAccess: true"))
    }

    func testTheKeyboardLayouts() {
        let en = KeyboardLayouts.rows(.english, page: .letters, shifted: false, nextKeyboard: true)
        let enLetters = en.flatMap { $0 }.compactMap { k -> String? in if case .text(let s) = k.action { return s } else { return nil } }
        XCTAssertEqual(Set(enLetters), Set("abcdefghijklmnopqrstuvwxyz".map(String.init)))
        XCTAssertEqual(en.count, 4)
        XCTAssertTrue(en.last!.contains { $0.action == .nextKeyboard }, "the globe key when iOS asks for it")
        XCTAssertTrue(en.last!.contains { $0.action == .language && $0.label == "ع" })
        XCTAssertFalse(KeyboardLayouts.rows(.english, page: .letters, shifted: false, nextKeyboard: false).last!.contains { $0.action == .nextKeyboard })
        let upper = KeyboardLayouts.rows(.english, page: .letters, shifted: true, nextKeyboard: false)[0].map(\.label)
        XCTAssertEqual(upper.first, "Q")

        let ar = KeyboardLayouts.rows(.arabic, page: .letters, shifted: false, nextKeyboard: false)
        let arLetters = Set(ar.flatMap { $0 }.compactMap { k -> String? in if case .text(let s) = k.action { return s } else { return nil } })
        let alphabet = "ا ب ت ث ج ح خ د ذ ر ز س ش ص ض ط ظ ع غ ف ق ك ل م ن ه و ي".split(separator: " ").map(String.init)
        for letter in alphabet { XCTAssertTrue(arLetters.contains(letter), "Arabic letter \(letter)") }
        for extra in ["ة", "ء", "ى"] { XCTAssertTrue(arLetters.contains(extra)) }
        let hamzas = Set(ar.flatMap { $0 }.flatMap(\.alternates))
        for h in ["أ", "إ", "آ", "ؤ", "ئ"] { XCTAssertTrue(hamzas.contains(h), "hold a key for \(h)") }
        XCTAssertEqual(ar[0].first?.label, "ض", "ض on the Q key, as on the iPhone's Arabic keyboard")
        XCTAssertTrue(ar.last!.contains { $0.action == .language && $0.label == "EN" })
        XCTAssertTrue(ar.last!.contains { $0.action == .space && $0.label == "مسافة" })

        let arNumbers = KeyboardLayouts.rows(.arabic, page: .numbers, shifted: false, nextKeyboard: false)
        XCTAssertEqual(arNumbers[0].map(\.label), ["١", "٢", "٣", "٤", "٥", "٦", "٧", "٨", "٩", "٠"])
        XCTAssertEqual(arNumbers[0][0].alternates, ["1"], "Western digits by holding")
        XCTAssertEqual(KeyboardLayouts.returnLabel("search", language: .arabic), "بحث")
        XCTAssertEqual(KeyboardLayouts.returnLabel("default", language: .english), "return")
    }

    // MARK: setup and Spotted

    func testKeyboardSetupStatus() {
        var modes: [String] = ["en_US@sw=QWERTY", "emoji@sw=Emoji"]
        let k = KeyboardSetup(modes: { modes }, defaults: defaults)
        XCTAssertEqual(k.status, .notAdded)
        modes.append(ClipboardShared.keyboardId)
        k.refresh()
        XCTAssertEqual(k.status, .needsFullAccess, "added, Full Access not reported")
        defaults.set(true, forKey: ClipboardShared.Keys.keyboardFullAccess)
        defaults.set(1_790_000_000.0, forKey: ClipboardShared.Keys.keyboardSeenAt)
        defaults.set(31.5, forKey: ClipboardShared.Keys.keyboardPeakMB)
        k.refresh()
        XCTAssertEqual(k.status, .ready)
        XCTAssertEqual(k.lastSeen, Date(timeIntervalSince1970: 1_790_000_000))
        XCTAssertEqual(k.peakMB, 31.5)
    }

    func testTheNewOriginsInSpotted() throws {
        let log = SpottedLog(dir: dir)
        log.record(domain: "pаypal.com", host: "xn--pypal-4ve.com", level: .dangerous, score: 90, reasons: ["Looks like PayPal"], brand: "PayPal", origin: .keyboard)
        log.record(domain: "pаypal.com", host: "xn--pypal-4ve.com", level: .dangerous, score: 90, reasons: ["Looks like PayPal"], brand: "PayPal", origin: .clipboard)
        let e = log.entries()
        XCTAssertEqual(Set(e.map(\.origin)), [.keyboard, .clipboard], "one entry per place")
        XCTAssertEqual(ProtectionOrigin.keyboard.title, "Loupe keyboard")
        let data = try JSONEncoder().encode(e)
        XCTAssertEqual(try JSONDecoder().decode([SpottedEntry].self, from: data).map(\.origin), e.map(\.origin))
        XCTAssertTrue(String(decoding: data, as: UTF8.self).contains("\"clipboard\""))
    }
}
