import Foundation
import SwiftUI
import Combine

@MainActor
final class WebModel: ObservableObject {
    enum KeyCheck: Equatable { case idle, checking, failed(HelperError), malformed }
    enum SearchState: Equatable { case idle, searching, failed(HelperError), results }

    // Settings (persisted, local only)
    @Published var helperEnabled: Bool { didSet { defaults.set(helperEnabled, forKey: "web.helperEnabled") } }
    @Published var flightsEnabled: Bool { didSet { defaults.set(flightsEnabled, forKey: "web.flightsEnabled") } }
    @Published var seenExplainer: Bool { didSet { defaults.set(seenExplainer, forKey: "web.seenExplainer") } }

    @Published private(set) var hasKey = false
    @Published var keyCheck: KeyCheck = .idle
    @Published var searchState: SearchState = .idle
    @Published private(set) var response: SearchResponse?
    @Published private(set) var ranked: [RankedOffer] = []
    @Published private(set) var priorities = Priorities()
    @Published var form = SearchForm()

    let connectivity: Connectivity
    let isFixtureMode: Bool
    let autoSearch: Bool
    private let helper: FlightsHelper
    private let keys: KeyStore
    private let ranker: OfferRanker
    private let defaults: UserDefaults
    private var bag: Set<AnyCancellable> = []

    init(helper: FlightsHelper, keys: KeyStore, connectivity: Connectivity,
         ranker: OfferRanker = Rankers.primary(), fixtureMode: Bool = false, autoSearch: Bool = false,
         defaults: UserDefaults = .standard) {
        self.defaults = defaults
        self.helperEnabled = defaults.object(forKey: "web.helperEnabled") as? Bool ?? true
        self.flightsEnabled = defaults.object(forKey: "web.flightsEnabled") as? Bool ?? true
        self.seenExplainer = fixtureMode ? true : (defaults.object(forKey: "web.seenExplainer") as? Bool ?? false)
        self.helper = helper
        self.keys = keys
        self.connectivity = connectivity
        self.ranker = ranker
        self.isFixtureMode = fixtureMode
        self.autoSearch = autoSearch
        self.hasKey = keys.read() != nil
        connectivity.objectWillChange
            .sink { [weak self] _ in self?.objectWillChange.send() }
            .store(in: &bag)
    }

    static func make(launch: LaunchOptions) -> WebModel {
        #if DEBUG
        if launch.fixtureMode {
            let m = WebModel(helper: FixtureFlightsHelper(), keys: MemoryKeyStore("duffel_test_fixture_only_key"),
                             connectivity: Connectivity(start: false), fixtureMode: true, autoSearch: launch.autoSearch)
            m.form = .fixtureExample
            return m
        }
        if launch.ephemeralKey {
            return WebModel(helper: LiveFlightsHelper(installId: InstallID.value), keys: MemoryKeyStore(), connectivity: Connectivity(),
                            defaults: UserDefaults(suiteName: "dev.loupe.ephemeral.\(UUID().uuidString)") ?? .standard)
        }
        #endif
        return WebModel(helper: LiveFlightsHelper(installId: InstallID.value), keys: KeychainStore(), connectivity: Connectivity())
    }

    var isOnline: Bool { isFixtureMode || connectivity.isOnline }

    // MARK: Key onboarding

    /// Verify with /v1/health and one minimal search, then store in the Keychain.
    func addKey(_ raw: String) async {
        guard let key = DuffelKey.validate(raw) else { keyCheck = .malformed; return }
        guard isOnline, helperEnabled else { keyCheck = .failed(.offline); return }
        keyCheck = .checking
        do {
            _ = try await helper.health()
            do { _ = try await helper.search(SearchForm.probe(), key: key) }
            catch HelperError.noResults { /* key accepted; the probe route simply had nothing */ }
            try keys.save(key)
            hasKey = true
            keyCheck = .idle
        } catch let e as HelperError {
            keyCheck = .failed(e)
        } catch {
            keyCheck = .failed(.unexpected("Could not save the key to the Keychain."))
        }
    }

    func removeKey() {
        try? keys.delete()
        hasKey = keys.read() != nil
        response = nil
        ranked = []
        searchState = .idle
    }

    // MARK: Search

    func search() async {
        guard helperEnabled, flightsEnabled else { return }
        guard let key = keys.read() else { hasKey = false; return }
        guard isOnline else { searchState = .failed(.offline); return }
        let request: SearchRequest
        do { request = try form.request() }
        catch let e as HelperError { searchState = .failed(e); return }
        catch { return }
        searchState = .searching
        priorities = PriorityParser.parse(form.priorities)
        do {
            let r = try await helper.search(request, key: key)
            if r.offers.isEmpty { searchState = .failed(.noResults); return }
            response = r
            ranked = ranker.rank(r.offers, by: priorities)
            searchState = .results
        } catch let e as HelperError {
            if e == .invalidKey { keyCheck = .failed(.invalidKey) }
            searchState = .failed(e)
        } catch {
            searchState = .failed(.unexpected(error.localizedDescription))
        }
    }

    var rankerName: String { ranker.name }
}

enum InstallID {
    static let defaultsKey = "dev.loupe.installId"
    /// A random per-install UUID, used by the helper only as its rate-limit key.
    static func value(_ defaults: UserDefaults = .standard) -> String {
        if let v = defaults.string(forKey: defaultsKey), UUID(uuidString: v) != nil { return v }
        let v = UUID().uuidString.lowercased()
        defaults.set(v, forKey: defaultsKey)
        return v
    }
    static var value: String { value() }
}

struct SearchForm: Equatable {
    var origin = ""
    var destination = ""
    var departDate = Calendar.current.date(byAdding: .day, value: 21, to: Date()) ?? Date()
    var returnTrip = true
    var returnDate = Calendar.current.date(byAdding: .day, value: 23, to: Date()) ?? Date()
    var adults = 1
    var cabin: CabinClass = .economy
    var maxStops = 1          // 0, 1, 2
    var priorities = ""

    static func isIATA(_ s: String) -> Bool {
        s.count == 3 && s.allSatisfy { $0.isASCII && $0.isLetter }
    }

    static func ymd(_ d: Date) -> String {
        let f = DateFormatter()
        f.calendar = Calendar(identifier: .gregorian)
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = .current
        f.dateFormat = "yyyy-MM-dd"
        return f.string(from: d)
    }

    func request() throws -> SearchRequest {
        let o = origin.uppercased().trimmingCharacters(in: .whitespaces)
        let d = destination.uppercased().trimmingCharacters(in: .whitespaces)
        guard Self.isIATA(o), Self.isIATA(d) else { throw HelperError.badRequest("From and To need 3-letter airport codes, like LIS or LHR.") }
        guard o != d else { throw HelperError.badRequest("From and To are the same airport.") }
        guard (1...9).contains(adults) else { throw HelperError.badRequest("Between 1 and 9 passengers.") }
        var slices = [SearchRequest.Slice(origin: o, destination: d, departureDate: Self.ymd(departDate))]
        if returnTrip {
            guard returnDate >= Calendar.current.startOfDay(for: departDate) else { throw HelperError.badRequest("The return is before the outbound flight.") }
            slices.append(.init(origin: d, destination: o, departureDate: Self.ymd(returnDate)))
        }
        return SearchRequest(slices: slices,
                             passengers: Array(repeating: .init(type: "adult"), count: adults),
                             cabinClass: cabin,
                             maxConnections: max(0, min(2, maxStops)))
    }

    /// The minimal search used to verify a new key.
    static func probe(now: Date = Date()) -> SearchRequest {
        let day = Calendar.current.date(byAdding: .day, value: 30, to: now) ?? now
        return SearchRequest(slices: [.init(origin: "LHR", destination: "LIS", departureDate: ymd(day))],
                             passengers: [.init(type: "adult")], cabinClass: .economy, maxConnections: 0)
    }

    static var fixtureExample: SearchForm {
        var f = SearchForm()
        f.origin = "LIS"; f.destination = "LHR"
        f.priorities = "nonstop, under £200, not before 7am, 1 checked bag"
        return f
    }
}
