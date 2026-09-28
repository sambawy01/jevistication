import XCTest
import LoupeKit
@testable import Loupe

/// Mail as one place (spec D10): what the "Found in your mail" card counts, and what the Mail entry says.
final class MailScreenTests: XCTestCase {
    private func sub(_ merchant: String, _ ids: [String]) -> CensusRow {
        CensusRow(merchant: merchant, cadence: "monthly", occurrences: 3, typicalMinor: 999, lastChargedIso: "2026-09-01",
                  daysSinceLastCharge: 27, monthlyMinor: KotlinLong(value: 999), sample: false, itemIds: ids,
                  nextExpectedIso: nil, verdict: nil)
    }

    func testFoundCountsOnlySubscriptionsReadFromMail() {
        let found = MailFound.of(phishing: 1, needsReply: 3, mailItemIds: ["mail:a", "mail:b"],
                                 census: [sub("Netflix", ["mail:a", "inbox:row1"]), sub("Gym", ["calendar:x"]), sub("Spotify", ["mail:b"])])
        XCTAssertEqual(found.subscriptions, ["Netflix", "Spotify"])
        XCTAssertEqual(found.line, "Phishing: 1 · Needs a reply: 3 · Subscriptions found: 2")
    }

    func testEntryLineSaysWhatIsConnectedAndWhatWasFound() {
        XCTAssertEqual(MailEntryCard.line(account: nil, on: false, phishing: 0, needsReply: 0),
                       "No mailbox yet · add one to read receipts, bills and phishing")
        XCTAssertEqual(MailEntryCard.line(account: "me@example.com", on: false, phishing: 2, needsReply: 1), "me@example.com · off")
        XCTAssertEqual(MailEntryCard.line(account: "me@example.com", on: true, phishing: 2, needsReply: 1),
                       "me@example.com · 2 possible phishing · 1 needs a reply")
        XCTAssertEqual(MailEntryCard.line(account: "me@example.com", on: true, phishing: 0, needsReply: 0), "me@example.com")
    }
}
