import Foundation
import UIKit

/// What the downloader needs from a network transport. The app uses a background `URLSession`
/// (`BackgroundDownloadTransport`); tests use a fake that records calls and replays events.
/// Every file is identified by its manifest name (the task's `taskDescription`).
@MainActor
protocol DownloadTransport: AnyObject {
    var events: DownloadTransportEvents? { get set }
    /// Starts (or, with `resumeData`, resumes) one file. When it finishes, the transport has already
    /// moved it to `stagedAt(name)` before calling `finished`.
    func start(name: String, url: URL, resumeData: Data?, allowsCellular: Bool)
    /// Stops the running file and hands back resume data when the server allows it.
    func pause(_ done: @escaping @MainActor (Data?) -> Void)
    /// Stops everything with no resume data.
    func cancelAll()
}

@MainActor
protocol DownloadTransportEvents: AnyObject {
    func transport(progress name: String, written: Int64, expected: Int64)
    func transport(finished name: String, httpStatus: Int)
    func transport(failed name: String, message: String, resumeData: Data?, cancelled: Bool)
}

/// The consent record: which variant, how many bytes, when. A download only starts with a record
/// that matches the variant and size being fetched, so a changed manifest asks again.
struct ModelConsent: Codable, Equatable {
    let variant: String
    let bytes: Int64
    let at: Date

    static let key = "laya.download.consent.v1"

    static func load(_ defaults: UserDefaults) -> ModelConsent? {
        defaults.data(forKey: key).flatMap { try? JSONDecoder().decode(ModelConsent.self, from: $0) }
    }

    static func save(_ c: ModelConsent?, _ defaults: UserDefaults) {
        if let c, let d = try? JSONEncoder().encode(c) { defaults.set(d, forKey: key) } else { defaults.removeObject(forKey: key) }
    }

    func covers(_ v: ModelManifest.Variant) -> Bool { variant == v.id && bytes == v.totalBytes }
}

/// The one-time model download: consent-gated, one file at a time, resumable, each file checked
/// against its SHA-256 before it may enter the model folder. All state is on the main actor.
@MainActor
final class ModelDownloader: DownloadTransportEvents {
    enum Phase: Equatable {
        case idle
        case downloading(file: String, done: Int64, total: Int64)
        case paused(done: Int64, total: Int64)
        case verifying(file: String)
        case installed
        case failed(DeliveryError)
    }

    let manifest: ModelManifest
    let storage: ModelStorage
    let transport: DownloadTransport
    let defaults: UserDefaults
    /// Injected so tests can pin free space; the app reads the volume.
    var availableBytes: () -> Int64?
    var onChange: ((Phase) -> Void)?

    private(set) var phase: Phase = .idle { didSet { if phase != oldValue { onChange?(phase) } } }
    private(set) var variant: ModelManifest.Variant
    private(set) var allowsCellular = false
    private var queue: [ModelManifest.File] = []
    private(set) var current: ModelManifest.File?
    private var currentWritten: Int64 = 0

    static let activeKey = "laya.download.active"

    init(manifest: ModelManifest, variant: ModelManifest.Variant, storage: ModelStorage, transport: DownloadTransport,
         defaults: UserDefaults = .standard, availableBytes: (() -> Int64?)? = nil) {
        self.manifest = manifest
        self.variant = variant
        self.storage = storage
        self.transport = transport
        self.defaults = defaults
        self.availableBytes = availableBytes ?? { [dir = storage.modelDir] in
            DiskSpace.available(at: FileManager.default.fileExists(atPath: dir.path) ? dir : dir.deletingLastPathComponent())
        }
        transport.events = self
        if storage.missing(variant).isEmpty { phase = .installed }
    }

    var consent: ModelConsent? { ModelConsent.load(defaults) }
    var hasConsent: Bool { consent?.covers(variant) ?? false }
    var isActive: Bool {
        switch phase {
        case .downloading, .verifying: return true
        default: return false
        }
    }
    var totalBytes: Int64 { variant.totalBytes }
    private var installedBytes: Int64 { variant.totalBytes - storage.remainingBytes(variant) }

    func recordConsent(now: Date = Date()) {
        ModelConsent.save(ModelConsent(variant: variant.id, bytes: variant.totalBytes, at: now), defaults)
    }

    func withdrawConsent() { ModelConsent.save(nil, defaults) }

    /// Switches the variant while idle (a running download must be paused or cancelled first).
    func select(_ v: ModelManifest.Variant) {
        guard !isActive else { return }
        variant = v
        phase = storage.missing(v).isEmpty ? .installed : .idle
    }

    /// Checks everything that must hold before any byte is requested, in order: a host, the user's
    /// consent for this variant and size, and room on the phone. Throws the first that fails.
    func preflight() throws {
        guard manifest.configuredHost != nil else { throw DeliveryError.notConfigured }
        guard hasConsent else { throw DeliveryError.consentRequired }
        let partial = storage.missing(variant).reduce(Int64(0)) { sum, f in
            sum + ((try? FileManager.default.attributesOfItem(atPath: storage.stagedURL(f).path)[.size] as? NSNumber)?.int64Value ?? 0)
        }
        if case let .short(needed, available) = DiskSpace.check(remaining: storage.remainingBytes(variant) - partial, available: availableBytes()) {
            throw DeliveryError.diskSpace(needed: needed, available: available)
        }
    }

    /// Starts or resumes. Refuses (and sets `.failed`) when `preflight` does; makes no request then.
    func start(allowsCellular: Bool) {
        guard !isActive else { return }
        self.allowsCellular = allowsCellular
        do {
            try preflight()
            try storage.prepare()
        } catch let e as DeliveryError {
            phase = .failed(e); return
        } catch {
            phase = .failed(.io(error.localizedDescription)); return
        }
        queue = storage.missing(variant)
        defaults.set(true, forKey: Self.activeKey)
        next()
    }

    /// Pauses the running file, keeping its resume data on disk so a later start (even after the
    /// app was quit) continues where it stopped.
    func pause() {
        guard case .downloading = phase, let f = current else { return }
        let done = installedBytes + currentWritten
        current = nil
        queue = []
        phase = .paused(done: done, total: totalBytes)
        transport.pause { [storage] data in
            if let data { try? data.write(to: storage.resumeDataURL(f), options: .atomic) }
        }
    }

    /// Stops and forgets the partial download (the files already installed stay).
    func cancel() {
        transport.cancelAll()
        current = nil
        queue = []
        try? FileManager.default.removeItem(at: storage.stagingDir)
        defaults.set(false, forKey: Self.activeKey)
        phase = storage.missing(variant).isEmpty ? .installed : .idle
    }

    /// Deletes every model file and the staging folder, and withdraws consent (a new download asks again).
    func deleteModel() {
        cancel()
        storage.deleteAll(manifest)
        withdrawConsent()
        phase = .idle
    }

    private func next() {
        guard !queue.isEmpty else {
            current = nil
            if storage.missing(variant).isEmpty {
                defaults.set(false, forKey: Self.activeKey)
                try? FileManager.default.removeItem(at: storage.stagingDir)
                phase = .installed
            } else {
                phase = .paused(done: installedBytes, total: totalBytes)
            }
            return
        }
        let f = queue.removeFirst()
        current = f
        currentWritten = 0
        // Downloaded in an earlier run (e.g. finished in the background) but not yet checked.
        if FileManager.default.fileExists(atPath: storage.stagedURL(f).path) {
            verify(f)
            return
        }
        guard let url = manifest.url(for: f, variant: variant) else { phase = .failed(.notConfigured); return }
        let resume = try? Data(contentsOf: storage.resumeDataURL(f))
        try? FileManager.default.removeItem(at: storage.resumeDataURL(f))
        phase = .downloading(file: f.name, done: installedBytes, total: totalBytes)
        transport.start(name: f.name, url: url, resumeData: resume, allowsCellular: allowsCellular)
    }

    private func verify(_ f: ModelManifest.File) {
        phase = .verifying(file: f.name)
        let storage = storage
        Task.detached(priority: .userInitiated) {
            let result: DeliveryError?
            do { try storage.install(f); result = nil } catch let e as DeliveryError { result = e } catch {
                result = .io(error.localizedDescription)
            }
            await MainActor.run {
                if let result {
                    self.current = nil
                    self.queue = []
                    self.phase = .failed(result)
                } else {
                    self.next()
                }
            }
        }
    }

    // MARK: transport events

    func transport(progress name: String, written: Int64, expected: Int64) {
        guard current?.name == name, case .downloading = phase else { return }
        currentWritten = min(written, current?.size ?? written)
        phase = .downloading(file: name, done: installedBytes + currentWritten, total: totalBytes)
    }

    func transport(finished name: String, httpStatus: Int) {
        guard let f = variant.files.first(where: { $0.name == name }) else { return }
        guard httpStatus == 200 || httpStatus == 206 else {
            try? FileManager.default.removeItem(at: storage.stagedURL(f))
            if current?.name == name { current = nil; queue = []; phase = .failed(.http(httpStatus, name)) }
            return
        }
        if current?.name == name {
            verify(f)
        } else if current == nil, case .paused = phase {
            // Finished in the background after a pause raced it: keep it staged; the next start checks it.
        } else if current == nil {
            // Finished while the app was not running (a background session relaunch): check it now.
            // Carry on with the rest only if this download was agreed to and can still run.
            current = f
            let mayContinue = hasConsent && manifest.configuredHost != nil
            queue = mayContinue ? storage.missing(variant).filter { $0 != f } : []
            verify(f)
        }
    }

    func transport(failed name: String, message: String, resumeData: Data?, cancelled: Bool) {
        if let resumeData, let f = variant.files.first(where: { $0.name == name }) {
            try? resumeData.write(to: storage.resumeDataURL(f), options: .atomic)
        }
        guard current?.name == name else { return }      // a pause or cancel already moved on
        current = nil
        queue = []
        if cancelled {
            phase = .paused(done: installedBytes + currentWritten, total: totalBytes)
        } else {
            phase = .failed(.transport(message + (resumeData != nil ? " What was downloaded so far is kept: tap Resume." : "")))
        }
    }
}

// MARK: - The real transport

/// A background `URLSession` download: it continues while Loupe is suspended, and iOS relaunches
/// the app to hand over a finished file (`handleEventsForBackgroundURLSession`). Two session
/// identifiers, because `allowsCellularAccess` is fixed per background session.
///
/// This is the **only** network code on the model path, and it runs only after `ModelDownloader`
/// has checked the host, the user's consent and the free space.
@MainActor
final class BackgroundDownloadTransport: NSObject, DownloadTransport {
    static let shared = BackgroundDownloadTransport()
    static let wifiIdentifier = "com.loupe-ai.ios.model"
    static let cellularIdentifier = "com.loupe-ai.ios.model.cellular"

    weak var events: DownloadTransportEvents?
    /// Where a finished file is moved (synchronously, inside the delegate callback).
    nonisolated(unsafe) var stage: (String) -> URL? = { _ in nil }
    private var sessions: [String: URLSession] = [:]
    private var running: URLSessionDownloadTask?
    private var systemCompletion: [String: () -> Void] = [:]
    private let bridge = Bridge()

    /// Re-creates the sessions so iOS can deliver events for downloads started in an earlier run.
    /// Creating a session makes no request.
    func reconnect() {
        _ = session(cellular: false)
        _ = session(cellular: true)
    }

    /// From the app delegate: iOS woke Loupe for a background session's events.
    func handleEvents(identifier: String, completion: @escaping () -> Void) {
        guard identifier == Self.wifiIdentifier || identifier == Self.cellularIdentifier else { return completion() }
        systemCompletion[identifier] = completion
        _ = session(cellular: identifier == Self.cellularIdentifier)
    }

    private func session(cellular: Bool) -> URLSession {
        let id = cellular ? Self.cellularIdentifier : Self.wifiIdentifier
        if let s = sessions[id] { return s }
        let cfg = URLSessionConfiguration.background(withIdentifier: id)
        cfg.isDiscretionary = false
        cfg.sessionSendsLaunchEvents = true
        cfg.allowsCellularAccess = cellular
        cfg.allowsExpensiveNetworkAccess = cellular
        cfg.allowsConstrainedNetworkAccess = cellular
        cfg.urlCache = nil
        cfg.requestCachePolicy = .reloadIgnoringLocalCacheData
        cfg.httpCookieStorage = nil
        cfg.httpShouldSetCookies = false
        cfg.timeoutIntervalForResource = 24 * 3600
        bridge.owner = self
        let s = URLSession(configuration: cfg, delegate: bridge, delegateQueue: nil)
        sessions[id] = s
        return s
    }

    func start(name: String, url: URL, resumeData: Data?, allowsCellular: Bool) {
        let s = session(cellular: allowsCellular)
        let task = resumeData.map { s.downloadTask(withResumeData: $0) } ?? s.downloadTask(with: URLRequest(url: url))
        task.taskDescription = name
        running = task
        task.resume()
    }

    func pause(_ done: @escaping @MainActor (Data?) -> Void) {
        guard let t = running else { return done(nil) }
        running = nil
        t.cancel { data in Task { @MainActor in done(data) } }
    }

    func cancelAll() {
        running?.cancel()
        running = nil
        for s in sessions.values { s.getAllTasks { $0.forEach { $0.cancel() } } }
    }

    fileprivate func finishedEvents(identifier: String?) {
        if let identifier, let c = systemCompletion.removeValue(forKey: identifier) { c() }
    }

    /// URLSession's delegate, off the main actor; forwards to it in order.
    private final class Bridge: NSObject, URLSessionDownloadDelegate, @unchecked Sendable {
        weak var owner: BackgroundDownloadTransport?
        private var lastProgress = Date.distantPast

        func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didWriteData _: Int64,
                        totalBytesWritten: Int64, totalBytesExpectedToWrite: Int64) {
            let now = Date()
            guard now.timeIntervalSince(lastProgress) > 0.25 else { return }
            lastProgress = now
            let name = downloadTask.taskDescription ?? ""
            Task { @MainActor [weak owner] in
                owner?.events?.transport(progress: name, written: totalBytesWritten, expected: totalBytesExpectedToWrite)
            }
        }

        func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
            let name = downloadTask.taskDescription ?? ""
            let status = (downloadTask.response as? HTTPURLResponse)?.statusCode ?? 0
            // The temp file is deleted when this returns: move it now, synchronously.
            if (status == 200 || status == 206), let dest = owner?.stage(name) {
                try? FileManager.default.createDirectory(at: dest.deletingLastPathComponent(), withIntermediateDirectories: true)
                try? FileManager.default.removeItem(at: dest)
                try? FileManager.default.moveItem(at: location, to: dest)
            }
            Task { @MainActor [weak owner] in owner?.events?.transport(finished: name, httpStatus: status) }
        }

        func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
            guard let error else { return }
            let ns = error as NSError
            let name = task.taskDescription ?? ""
            let resume = ns.userInfo[NSURLSessionDownloadTaskResumeData] as? Data
            let cancelled = ns.domain == NSURLErrorDomain && ns.code == NSURLErrorCancelled
            Task { @MainActor [weak owner] in
                owner?.events?.transport(failed: name, message: error.localizedDescription, resumeData: resume, cancelled: cancelled)
            }
        }

        func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
            let id = session.configuration.identifier
            Task { @MainActor [weak owner] in owner?.finishedEvents(identifier: id) }
        }
    }
}

/// Hands background-session wake-ups to the transport.
final class LoupeAppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication, handleEventsForBackgroundURLSession identifier: String,
                     completionHandler: @escaping () -> Void) {
        MainActor.assumeIsolated {
            _ = LayaModel.shared   // wires the transport's events to the downloader
            BackgroundDownloadTransport.shared.handleEvents(identifier: identifier, completion: completionHandler)
        }
    }
}
