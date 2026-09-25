import Foundation
import Network

/// Reachability from NWPathMonitor. Local only: it observes the interface, it sends nothing.
@MainActor
final class Connectivity: ObservableObject {
    @Published private(set) var isOnline = true
    private let monitor = NWPathMonitor()

    init(start: Bool = true) {
        guard start else { return }
        monitor.pathUpdateHandler = { [weak self] path in
            let online = path.status == .satisfied
            Task { @MainActor in self?.isOnline = online }
        }
        monitor.start(queue: DispatchQueue(label: "com.loupe-ai.ios.connectivity"))
    }

    deinit { monitor.cancel() }
}
