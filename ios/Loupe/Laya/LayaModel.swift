import Foundation
import LoupeKit

/// Laya's files on this phone, and Laya itself once opened. One per app; opening hashes ~418 MB,
/// so it happens once, off the main thread, and the result is kept.
///
/// Getting the files here is the one network use on this path (PRODUCT.md §4a): `ModelDownloader`
/// over the bundled `models.json`, only after the user's consent. The manifest's host ships EMPTY
/// (the owner sets it; docs/BUILD.md) and the screen then says so and requests nothing.
@MainActor
final class LayaModel: ObservableObject {
    enum Status: Equatable {
        case notInstalled
        case checking
        case ready
        case downloading(Double)          // 0...1
        case paused(Double)
        case verifying(String)
        case failed(String)
    }

    static let shared = LayaModel()

    static let variantKey = "laya.variant"
    static let cellularKey = "laya.download.cellular"

    @Published private(set) var status: Status = .notInstalled
    @Published private(set) var variantID: String
    @Published var allowsCellular: Bool {
        didSet { defaults.set(allowsCellular, forKey: Self.cellularKey) }
    }
    /// The last delivery refusal or failure, typed (the consent screen and tests read it).
    @Published private(set) var deliveryError: DeliveryError?

    let manifest: ModelManifest?
    let manifestProblem: String?
    let directory: String?
    private let defaults: UserDefaults
    private(set) var downloader: ModelDownloader?
    private var opened: LayaOnDevice?

    init(directory: String? = LayaOnPhone.shared.directory(),
         manifest: Result<ModelManifest, ModelManifest.Problem> = ModelManifest.bundled(),
         transport: DownloadTransport? = nil,
         defaults: UserDefaults = .standard) {
        self.directory = directory
        self.defaults = defaults
        self.allowsCellular = defaults.bool(forKey: Self.cellularKey)
        switch manifest {
        case .success(let m):
            self.manifest = m
            self.manifestProblem = nil
            let saved = defaults.string(forKey: Self.variantKey)
            self.variantID = m.variant(saved ?? "") != nil ? saved! : m.defaultVariant
        case .failure(let p):
            self.manifest = nil
            self.manifestProblem = p.localizedDescription
            self.variantID = "int8"
        }
        if let m = self.manifest, let dir = directory, let v = m.variant(variantID) {
            let storage = ModelStorage(modelDir: URL(fileURLWithPath: dir, isDirectory: true))
            let t: DownloadTransport
            if let transport { t = transport } else {
                let bg = BackgroundDownloadTransport.shared
                bg.stage = { name in
                    m.variants.flatMap(\.files).first { $0.name == name }.map(storage.stagedURL)
                }
                // Only when a download was running in an earlier launch; creating a session requests nothing.
                if defaults.bool(forKey: ModelDownloader.activeKey) { bg.reconnect() }
                t = bg
            }
            let d = ModelDownloader(manifest: m, variant: v, storage: storage, transport: t, defaults: defaults)
            d.onChange = { [weak self] phase in self?.apply(phase) }
            downloader = d
            if defaults.bool(forKey: ModelDownloader.activeKey), !storage.missing(v).isEmpty {
                // Interrupted by a quit: show it as paused with its progress; Resume continues.
                status = .paused(Double(v.totalBytes - storage.remainingBytes(v)) / Double(max(1, v.totalBytes)))
                return
            }
        }
        refresh()
    }

    var variant: ModelManifest.Variant? { manifest?.variant(variantID) }
    var hostConfigured: Bool { manifest?.configuredHost != nil }
    var hasConsent: Bool { downloader?.hasConsent ?? false }
    var downloadBytes: Int64 { variant?.totalBytes ?? 0 }

    var isInstalled: Bool {
        directory.map { LayaOnPhone.shared.missing(directory: $0, variant: variantID).isEmpty } ?? false
    }

    /// Re-reads whether the files are present; does not hash them.
    func refresh() {
        if opened != nil { status = .ready; return }
        switch status {
        case .downloading, .verifying, .paused: return
        default: break
        }
        status = isInstalled ? .ready : .notInstalled
    }

    /// The backend, verifying and opening on first use (off the main thread). Nil when not installed
    /// or when a file does not match its pin — the status then says why.
    func backend() async -> Backend? {
        if let opened { return opened.backend }
        guard let dir = directory, isInstalled else { status = .notInstalled; return nil }
        status = .checking
        let variant = variantID
        let result = await Task.detached(priority: .userInitiated) { LayaOnPhone.shared.open(directory: dir, variant: variant) }.value
        if let ready = result as? LayaOnPhone.OpenedReady {
            opened = ready.laya
            status = .ready
            return ready.laya.backend
        }
        status = .failed((result as? LayaOnPhone.OpenedFailed)?.message ?? "Could not open the model.")
        return nil
    }

    // MARK: Delivery (only after consent, only on the user's tap)

    /// The user agreed on the consent screen to this variant and size.
    func grantConsent() { downloader?.recordConsent() }

    func startDownload() {
        guard let d = downloader else { return }
        deliveryError = nil
        d.start(allowsCellular: allowsCellular)
    }

    func pauseDownload() { downloader?.pause() }

    func cancelDownload() {
        downloader?.cancel()
        status = isInstalled ? .ready : .notInstalled
    }

    /// Picks the graph variant (the downloaded files of the other one are kept until Remove).
    func select(variant id: String) {
        guard let m = manifest, let v = m.variant(id), id != variantID, !(downloader?.isActive ?? false) else { return }
        opened?.close()
        opened = nil
        variantID = id
        defaults.set(id, forKey: Self.variantKey)
        downloader?.select(v)
        status = isInstalled ? .ready : .notInstalled
    }

    /// Removes every model file and the partial download (and closes Laya). Everything else in
    /// Loupe keeps working. A new download asks for consent again.
    func remove() {
        opened?.close()
        opened = nil
        if let d = downloader {
            d.deleteModel()
        } else if let dir = directory {
            for name in LayaOnPhone.shared.fileNames { try? FileManager.default.removeItem(atPath: dir + "/" + name) }
        }
        status = .notInstalled
        refresh()
    }

    private func apply(_ phase: ModelDownloader.Phase) {
        let total = Double(max(1, downloadBytes))
        switch phase {
        case .idle: status = isInstalled ? .ready : .notInstalled
        case let .downloading(_, done, _): status = .downloading(min(1, Double(done) / total))
        case let .paused(done, _): status = .paused(min(1, Double(done) / total))
        case .verifying(let f): status = .verifying(f)
        case .installed:
            status = .checking
            Task { _ = await self.backend() }
        case .failed(let e):
            deliveryError = e
            status = .failed(e.localizedDescription)
        }
    }
}
