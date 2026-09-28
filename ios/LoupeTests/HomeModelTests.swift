import XCTest
import LoupeKit
@testable import Loupe

/// Home's cards (spec 2026-09-28 §3): what Needs attention holds and in what order, and what Money, Documents and
/// Protected say, empty states included.
final class HomeModelTests: XCTestCase {
    private func sub(_ merchant: String, monthly: Int64?, next: String?) -> CensusRow {
        CensusRow(merchant: merchant, cadence: "monthly", occurrences: 3, typicalMinor: monthly ?? 500,
                  lastChargedIso: "2026-09-01", daysSinceLastCharge: 27, monthlyMinor: monthly.map { KotlinLong(value: $0) },
                  sample: false, itemIds: ["a", "b", "c"], nextExpectedIso: next, verdict: nil, currency: "", lines: [])
    }

    private func doc(_ name: String, days: Int64) -> ExpiryRow {
        ExpiryRow(itemId: "id:\(name)", itemName: name, expiryIso: "2027-01-14", daysRemaining: days, ambiguous: false,
                  breachesRule: days < 183, documentType: nil, findingKey: nil, line: nil, sample: false, documentKind: nil)
    }

    func testNeedsAttentionIsEmptyWhenNothingWaits() {
        XCTAssertEqual(HomeModel.attention(findingKeys: [], spottedHeadline: nil, dangerousThisWeek: 0, phishing: 0,
                                           toReview: 0, privacyFindings: 0), [])
    }

    func testNeedsAttentionOrder() {
        let items = HomeModel.attention(findingKeys: ["impersonation:x", "site-fraud:y", "expiry:z", "term-change:w"],
                                        spottedHeadline: "Loupe spotted 1 risky site this week", dangerousThisWeek: 1,
                                        phishing: 2, toReview: 3, privacyFindings: 4)
        XCTAssertEqual(items.map(\.id), ["spotted", "mail", "finding:impersonation:x", "finding:site-fraud:y",
                                         "finding:expiry:z", "review", "privacy"],
                       "risky sites, mail phishing, three findings at most, review proposals, then privacy")
    }

    func testMoneyShowsTheMonthlyTotalAndTheNextCharge() {
        let census = SubscriptionCensus(rows: [sub("Netflix", monthly: 16500, next: "2026-10-03"),
                                               sub("Spotify", monthly: 6999, next: "2026-10-01")],
                                        monthlyTotalMinor: 23499, chargesFound: 6, sample: false, setAside: 0)
        let m = HomeModel.money(census, mailCovered: true)
        XCTAssertEqual(m.headline, "234.99 a month")
        XCTAssertEqual(m.detail, "2 subscriptions · Spotify next on \(GuardModel.day("2026-10-01"))")
        XCTAssertFalse(m.needsSource)
    }

    func testMoneyAsksForMailWhenNothingCouldHoldCharges() {
        let empty = SubscriptionCensus(rows: [], monthlyTotalMinor: 0, chargesFound: 0, sample: false, setAside: 0)
        let m = HomeModel.money(empty, mailCovered: false)
        XCTAssertEqual(m.headline, "No subscriptions yet")
        XCTAssertTrue(m.needsSource)
        XCTAssertTrue(m.detail.contains("Mail"), m.detail)
        XCTAssertEqual(HomeModel.money(empty, mailCovered: true).needsSource, false)
        XCTAssertEqual(HomeModel.money(nil, mailCovered: true, running: true).headline, "Reading your sources")
    }

    func testDocumentsLeadWithTheSoonest() {
        let d = HomeModel.documents([doc("passport-scan.txt", days: 150), doc("car-licence.jpg", days: 12)], documentsCovered: true)
        XCTAssertEqual(d.headline, "car-licence.jpg: \(GuardModel.daysLeftLine(12))")
        XCTAssertEqual(d.detail, "+ 1 more")
        XCTAssertFalse(d.needsSource)
    }

    func testDocumentsAskForFilesWhenNothingCouldHoldDocuments() {
        let d = HomeModel.documents([], documentsCovered: false)
        XCTAssertEqual(d.headline, "No documents read yet")
        XCTAssertTrue(d.needsSource)
        XCTAssertEqual(HomeModel.documents([], documentsCovered: true).headline, "No expiry dates found")
        XCTAssertEqual(HomeModel.documents(nil, documentsCovered: true, running: true).headline, "Reading your sources")
    }

    /// No summary yet once the saved results are read (nothing heavy starts at open, O-4): "Reading your sources" only
    /// while a run, a scan or the watchers go; otherwise say it is not checked (Home's scan panel offers Run now).
    func testNoSummaryYetSaysWhetherAnythingIsRunning() {
        let reading = HomeModel.money(nil, mailCovered: true, running: true)
        XCTAssertEqual(reading.headline, "Reading your sources")
        XCTAssertFalse(reading.needsRun)
        let idle = HomeModel.money(nil, mailCovered: true, running: false)
        XCTAssertEqual(idle.headline, "Not checked yet")
        XCTAssertEqual(idle.detail, "Run a check to see your subscriptions.")
        XCTAssertTrue(idle.needsRun)
        XCTAssertFalse(idle.needsSource)
        XCTAssertNotEqual(reading, idle)

        let docsReading = HomeModel.documents(nil, documentsCovered: true, running: true)
        XCTAssertEqual(docsReading.headline, "Reading your sources")
        XCTAssertFalse(docsReading.needsRun)
        let docsIdle = HomeModel.documents(nil, documentsCovered: true, running: false)
        XCTAssertEqual(docsIdle.headline, "Not checked yet")
        XCTAssertEqual(docsIdle.detail, "Run a check to see which documents expire soon.")
        XCTAssertTrue(docsIdle.needsRun)
        XCTAssertNotEqual(docsReading, docsIdle)

        let census = SubscriptionCensus(rows: [], monthlyTotalMinor: 0, chargesFound: 0, sample: false, setAside: 0)
        XCTAssertFalse(HomeModel.money(census, mailCovered: true, running: false).needsRun, "a summary in hand needs no run")
        XCTAssertFalse(HomeModel.documents([], documentsCovered: true, running: false).needsRun)
    }

    /// The last results are saved and read back at launch (2026-09-28): until they are, the cards say so, and neither
    /// "Not checked yet" nor its Run now shows.
    func testCardsSayLoadingUntilTheSavedResultsAreRead() {
        let money = HomeModel.money(nil, mailCovered: true, running: false, loaded: false)
        XCTAssertEqual(money.headline, "Loading the last results…")
        XCTAssertFalse(money.needsRun)
        XCTAssertFalse(money.needsSource)
        let docs = HomeModel.documents(nil, documentsCovered: true, running: true, loaded: false)
        XCTAssertEqual(docs.headline, "Loading the last results…", "loading wins over running")
        XCTAssertFalse(docs.needsRun)
        let census = SubscriptionCensus(rows: [], monthlyTotalMinor: 0, chargesFound: 0, sample: false, setAside: 0)
        XCTAssertEqual(HomeModel.money(census, mailCovered: true, loaded: false).headline, "No subscriptions yet",
                       "a summary in hand is shown whatever the flag says")
    }

    func testProtectedNamesWhatIsOffAndOffersTheFirst() {
        let all = HomeModel.protected(safari: true, keyboard: true, clipboard: true, onlineChecksOn: false)
        XCTAssertEqual(all.title, "Protected")
        XCTAssertNil(all.firstOff)
        XCTAssertEqual(all.line, "Safari on · Keyboard on · Clipboard on")
        XCTAssertEqual(all.bytesLine, "0 bytes out")
        let some = HomeModel.protected(safari: false, keyboard: true, clipboard: false, onlineChecksOn: true)
        XCTAssertEqual(some.title, "Not fully protected")
        XCTAssertEqual(some.firstOff, .safari)
        XCTAssertEqual(some.line, "Safari off · Keyboard on · Clipboard off")
        XCTAssertEqual(some.bytesLine, "Online checks on")
    }
}
