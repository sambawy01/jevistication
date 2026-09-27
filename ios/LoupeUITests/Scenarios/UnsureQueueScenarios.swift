import XCTest

/// Scenario 4: the Unsure queue. Now → Needs you → answer two items, Undo one, Skip one; relaunch → the one answer
/// that stands is kept (the count is one lower than at the start) and the undone one is back in the queue.
/// `-LoupeQueueDemo` seeds the fixture queue once (with a stand-in scorer), only while the ledger is empty, so the
/// relaunch does not seed it again.
final class UnsureQueueScenarios: ScenarioCase {
    private var count: XCUIElement { app.staticTexts["queue.count"] }

    private func openQueue() -> Int {
        let card = button("now.needsYou")
        XCTAssertTrue(card.waitForExistence(timeout: 60), "Now shows Needs you")
        card.tap()
        XCTAssertTrue(count.waitForExistence(timeout: 10))
        return number(count.label)
    }

    func testAnswersSurviveARelaunch() {
        start("queue", ["-LoupeQueueDemo", "-LoupeTab", "now", "-LoupeSkipOnboarding", "-LoupeModelState", "ready"])
        let start = openQueue()
        XCTAssertGreaterThan(start, 3, "the demo has items to answer")
        audit("04-queue", ["queue.option.0", "queue.option.1", "queue.skip", "queue.undo"])

        // Answer two; the count drops each time.
        button("queue.option.0").tap()
        waitFor(count, "label == %@", "Needs you: \(start - 1)")
        button("queue.option.1").tap()
        waitFor(count, "label == %@", "Needs you: \(start - 2)")
        // Undo the last answer: it comes back.
        let undo = button("queue.undo")
        reveal(undo)
        XCTAssertTrue(undo.isEnabled)
        undo.tap()
        waitFor(count, "label == %@", "Needs you: \(start - 1)")
        // Skip one (for this session only; it is not an answer).
        let skip = button("queue.skip")
        reveal(skip)
        skip.tap()
        shot("04-queue-answered")

        // Relaunch: the answer that stands is kept; the undone and the skipped ones wait again.
        relaunch()
        let after = openQueue()
        XCTAssertEqual(after, start - 1, "one answer stands after a relaunch (was \(start), now \(after))")
        audit("04-queue-relaunch", ["queue.option.0", "queue.skip"])
    }
}
