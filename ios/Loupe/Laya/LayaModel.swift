import Foundation
import LoupeKit

/// Where the one-time model download comes from. PRODUCT.md §4a: the app downloads nothing without
/// the user's say-so, and the engine itself never touches the network.
///
/// Left EMPTY on purpose: the repo records no host for the exported INT8 graph (the upstream
/// Hugging Face repo holds the PyTorch checkpoint, not this export). Set it to a pinned base URL
/// serving `tokenizer.json` and `laya-multilingual-choice.int8.onnx`; the SHA-256 pins in
/// LayaModelStore are checked before anything is used, whatever the host.
enum LayaModelSource {
    static let baseURL = ""
    static var configured: URL? {
        guard !baseURL.isEmpty, let u = URL(string: baseURL), u.scheme == "https" else { return nil }
        return u
    }
    /// Approximate download sizes, for the consent screen.
    static let bytes: [String: Int64] = [
        "tokenizer.json": 34_363_188,
        "laya-multilingual-choice.int8.onnx": 383_883_281,
    ]
    static var totalBytes: Int64 { bytes.values.reduce(0, +) }
}

/// Laya's files on this phone, and Laya itself once opened. One per app; opening hashes ~418 MB,
/// so it happens once, off the main thread, and the result is kept.
@MainActor
final class LayaModel: ObservableObject {
    enum Status: Equatable {
        case notInstalled
        case checking
        case ready
        case downloading(Double)          // 0...1
        case failed(String)
    }

    static let shared = LayaModel()

    @Published private(set) var status: Status = .notInstalled
    private var opened: LayaOnDevice?
    private var download: Task<Void, Never>?
    let directory: String?

    init(directory: String? = LayaOnPhone.shared.directory()) {
        self.directory = directory
        refresh()
    }

    var isInstalled: Bool { directory.map { LayaOnPhone.shared.missing(directory: $0).isEmpty } ?? false }

    /// Re-reads whether the files are present; does not hash them.
    func refresh() {
        if opened != nil { status = .ready; return }
        if case .downloading = status { return }
        status = isInstalled ? .ready : .notInstalled
    }

    /// The backend, verifying and opening on first use (off the main thread). Nil when not installed
    /// or when a file does not match its pin — the status then says why.
    func backend() async -> Backend? {
        if let opened { return opened.backend }
        guard let dir = directory, isInstalled else { status = .notInstalled; return nil }
        status = .checking
        let result = await Task.detached(priority: .userInitiated) { LayaOnPhone.shared.open(directory: dir) }.value
        if let ready = result as? LayaOnPhone.OpenedReady {
            opened = ready.laya
            status = .ready
            return ready.laya.backend
        }
        status = .failed((result as? LayaOnPhone.OpenedFailed)?.message ?? "Could not open the model.")
        return nil
    }

    // MARK: Download (only on the user's tap)

    func startDownload() {
        guard download == nil, let base = LayaModelSource.configured, let dir = directory else { return }
        status = .downloading(0)
        download = Task { [weak self] in
            do {
                try await Self.fetchAll(base: base, into: dir) { p in
                    Task { @MainActor in self?.status = .downloading(p) }
                }
                await MainActor.run {
                    self?.download = nil
                    self?.status = .checking
                }
                _ = await self?.backend()
            } catch is CancellationError {
                await MainActor.run { self?.download = nil; self?.refresh() }
            } catch {
                await MainActor.run {
                    self?.download = nil
                    self?.status = .failed(error.localizedDescription)
                }
            }
        }
    }

    func cancelDownload() { download?.cancel() }

    /// Removes the files (and closes Laya). Everything else in Loupe keeps working.
    func remove() {
        opened?.close()
        opened = nil
        if let dir = directory {
            for name in LayaOnPhone.shared.fileNames { try? FileManager.default.removeItem(atPath: dir + "/" + name) }
        }
        refresh()
    }

    struct DownloadError: LocalizedError {
        var errorDescription: String?
    }

    /// Each file goes to a temporary name, is checked against its SHA-256 pin, and only then moved
    /// into place. A mismatch deletes it.
    nonisolated static func fetchAll(base: URL, into dir: String, progress: @escaping @Sendable (Double) -> Void) async throws {
        let names = LayaOnPhone.shared.fileNames
        let total = Double(LayaModelSource.totalBytes)
        var done: Int64 = 0
        for name in names {
            try Task.checkCancellation()
            let dest = URL(fileURLWithPath: dir).appendingPathComponent(name)
            if FileManager.default.fileExists(atPath: dest.path),
               LayaOnPhone.shared.sha256(path: dest.path) == LayaOnPhone.shared.pinnedSha256(name: name) {
                done += LayaModelSource.bytes[name] ?? 0
                continue
            }
            let offset = done
            let (tmp, response) = try await URLSession.shared.download(from: base.appendingPathComponent(name), delegate: ProgressDelegate { written in
                progress(min(1, Double(offset + written) / total))
            })
            guard (response as? HTTPURLResponse)?.statusCode == 200 else {
                throw DownloadError(errorDescription: "The model host answered \((response as? HTTPURLResponse)?.statusCode ?? 0) for \(name).")
            }
            guard LayaOnPhone.shared.sha256(path: tmp.path) == LayaOnPhone.shared.pinnedSha256(name: name) else {
                try? FileManager.default.removeItem(at: tmp)
                throw DownloadError(errorDescription: "\(name) did not match its SHA-256 pin, so it was deleted and not used.")
            }
            try? FileManager.default.removeItem(at: dest)
            try FileManager.default.moveItem(at: tmp, to: dest)
            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            var d = dest
            try? d.setResourceValues(values)
            done += LayaModelSource.bytes[name] ?? 0
        }
    }
}

private final class ProgressDelegate: NSObject, URLSessionDownloadDelegate, @unchecked Sendable {
    let onBytes: (Int64) -> Void
    init(_ onBytes: @escaping (Int64) -> Void) { self.onBytes = onBytes }
    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didWriteData _: Int64,
                    totalBytesWritten: Int64, totalBytesExpectedToWrite _: Int64) {
        onBytes(totalBytesWritten)
    }
    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {}
}
