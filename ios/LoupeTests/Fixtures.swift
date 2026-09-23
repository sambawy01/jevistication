import Foundation
@testable import Loupe

enum Fixture {
    static var data: Data {
        let url = Bundle.main.url(forResource: "flights-lis-lhr", withExtension: "json")!
        return try! Data(contentsOf: url)
    }
    static var response: SearchResponse { try! JSONDecoder().decode(SearchResponse.self, from: data) }
}
