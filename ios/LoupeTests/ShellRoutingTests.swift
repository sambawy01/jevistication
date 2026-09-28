import SwiftUI
import XCTest
@testable import Loupe

/// The three places (spec 2026-09-28 §3): every old `-LoupeTab` name still lands somewhere sensible, and the router
/// keeps one stack per place.
@MainActor
final class ShellRoutingTests: XCTestCase {
    func testThreePlacesInOrder() {
        XCTAssertEqual(Place.allCases.map(\.title), ["Home", "Ask", "Me"])
    }

    func testEveryOldTabNameHasAPlace() {
        XCTAssertEqual(LaunchPlace.from("now"), LaunchPlace(place: .home))
        XCTAssertEqual(LaunchPlace.from("home"), LaunchPlace(place: .home))
        XCTAssertEqual(LaunchPlace.from("guard"), LaunchPlace(place: .home, home: .protection))
        XCTAssertEqual(LaunchPlace.from("judgments"), LaunchPlace(place: .ask))
        XCTAssertEqual(LaunchPlace.from("ask"), LaunchPlace(place: .ask))
        XCTAssertEqual(LaunchPlace.from("web"), LaunchPlace(place: .ask, ask: .web))
        XCTAssertEqual(LaunchPlace.from("sources"), LaunchPlace(place: .me, me: .reads))
        XCTAssertEqual(LaunchPlace.from("mail"), LaunchPlace(place: .me, me: .mail))
        XCTAssertEqual(LaunchPlace.from("me"), LaunchPlace(place: .me))
        XCTAssertNil(LaunchPlace.from("nowhere"))
    }

    func testGoPushesTheOldTabsScreen() {
        let router = AppRouter()
        router.go(LaunchPlace.from("guard")!)
        XCTAssertEqual(router.place, .home)
        XCTAssertEqual(router.homePath.count, 1)
        router.go(LaunchPlace.from("sources")!)
        XCTAssertEqual(router.place, .me)
        XCTAssertEqual(router.mePath.count, 1)
        XCTAssertEqual(router.homePath.count, 0, "go starts every place from its first screen")
        router.go(LaunchPlace.from("web")!)
        XCTAssertEqual(router.place, .ask)
        XCTAssertEqual(router.askSection, .web)
    }

    func testSelectingTheSamePlaceAgainGoesBackToItsFirstScreen() {
        let router = AppRouter()
        router.openHome(.protection)
        router.select(.me)
        XCTAssertEqual(router.homePath.count, 1, "another place keeps Home's screen")
        router.select(.home)
        XCTAssertEqual(router.homePath.count, 1, "coming back shows the screen that was open")
        router.select(.home)
        XCTAssertEqual(router.homePath.count, 0, "a second tap goes back to Home's first screen")
        router.openReads()
        router.select(.me)
        XCTAssertEqual(router.mePath.count, 0)
    }

    func testOpenAskWithTheQueuePushesTheQueue() {
        let router = AppRouter()
        router.openAsk(.mine, queue: true)
        XCTAssertEqual(router.place, .ask)
        XCTAssertEqual(router.askSection, .mine)
        XCTAssertEqual(router.askPath.count, 1)
    }

    func testSpottedAndMailOpenInTheirPlaces() {
        let router = AppRouter()
        router.openSpotted()
        XCTAssertEqual(router.place, .home)
        XCTAssertEqual(router.homePath.count, 1)
        router.openMail()
        XCTAssertEqual(router.place, .me)
        XCTAssertEqual(router.mePath.count, 1)
    }

    func testResetClearsEveryStackAndGoesHome() {
        let router = AppRouter()
        router.openHome(.review)
        router.openAsk(.web, queue: true)
        router.openReads()
        router.reset()
        XCTAssertEqual(router.place, .home)
        XCTAssertEqual(router.homePath.count + router.askPath.count + router.mePath.count, 0)
        XCTAssertNil(router.askSection)
    }
}
