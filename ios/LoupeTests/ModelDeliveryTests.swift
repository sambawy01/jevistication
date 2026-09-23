import CryptoKit
import XCTest
import LoupeKit
@testable import Loupe

/// A transport that records what it was asked and lets the test play the network's part.
@MainActor
final class FakeTransport: DownloadTransport {
    weak var events: DownloadTransportEvents?
    struct Start: Equatable { let name: String; let url: URL; let resumeData: Data?; let cellular: Bool }
    var starts: [Start] = []
    var pauses = 0
    var cancels = 0
    var resumeDataOnPause: Data?

    func start(name: String, url: URL, resumeData: Data?, allowsCellular: Bool) {
        starts.append(Start(name: name, url: url, resumeData: resumeData, cellular: allowsCellular))
    }
    func pause(_ done: @escaping @MainActor (Data?) -> Void) { pauses += 1; done(resumeDataOnPause) }
    func cancelAll() { cancels += 1 }
}

@MainActor
final class ModelDeliveryTests: XCTestCase {
    var dir: URL!
    var defaults: UserDefaults!

    override func setUp() async throws {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent("delivery-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defaults = UserDefaults(suiteName: "delivery-\(UUID().uuidString)")!
    }

    override func tearDown() async throws { try? FileManager.default.removeItem(at: dir) }

    // Two small files with real hashes, as a manifest would list them.
    let tok = Data("tokenizer bytes".utf8)
    let graph = Data(repeating: 7, count: 70_000)
    static func sha(_ d: Data) -> String { SHA256.hash(data: d).map { String(format: "%02x", $0) }.joined() }

    func manifestJSON(host: String = "https://models.example.org", optIn: Bool = false, sha: String? = nil,
                      name: String = "g.onnx", defaultVariant: String = "int8") -> Data {
        """
        {"schema":1,"source":{"host":"\(host)","urlTemplate":"{host}/laya/{variant}/{file}"},"defaultVariant":"\(defaultVariant)",
         "variants":[{"id":"int8","title":"T","detail":"D","optIn":\(optIn),"files":[
           {"name":"tok.json","size":\(tok.count),"sha256":"\(Self.sha(tok))"},
           {"name":"\(name)","size":\(graph.count),"sha256":"\(sha ?? Self.sha(graph))"}]}]}
        """.data(using: .utf8)!
    }

    func makeDownloader(host: String = "https://models.example.org", free: Int64? = 10_000_000_000) throws -> (ModelDownloader, FakeTransport, ModelStorage) {
        let m = try ModelManifest.parse(manifestJSON(host: host))
        let storage = ModelStorage(modelDir: dir.appendingPathComponent("model", isDirectory: true))
        let t = FakeTransport()
        let d = ModelDownloader(manifest: m, variant: m.variant("int8")!, storage: storage, transport: t,
                                defaults: defaults, availableBytes: { free })
        return (d, t, storage)
    }

    func waitFor(_ d: ModelDownloader, _ done: @escaping (ModelDownloader.Phase) -> Bool, timeout: TimeInterval = 5) async {
        let deadline = Date().addingTimeInterval(timeout)
        while !done(d.phase), Date() < deadline { try? await Task.sleep(nanoseconds: 10_000_000) }
    }

    /// The network's part: the file lands in staging, then the transport says it finished.
    func deliver(_ data: Data, as name: String, _ d: ModelDownloader, _ s: ModelStorage, status: Int = 200) throws {
        let f = d.variant.files.first { $0.name == name }!
        try FileManager.default.createDirectory(at: s.stagingDir, withIntermediateDirectories: true)
        try data.write(to: s.stagedURL(f))
        d.transport(finished: name, httpStatus: status)
    }

    // MARK: manifest

    func testTheBundledManifestParsesWithAnEmptyHostAndTheKotlinPins() throws {
        let m = try ModelManifest.bundled(Bundle(for: LayaModel.self)).get()
        XCTAssertEqual(m.defaultVariant, "int8")
        XCTAssertEqual(m.variants.map(\.id), ["int8", "int8-partial"])
        XCTAssertFalse(m.variant("int8")!.optIn)
        XCTAssertTrue(m.variant("int8-partial")!.optIn)
        XCTAssertEqual(m.source.host, "", "the host ships empty until the owner sets it")
        XCTAssertNil(m.configuredHost)
        XCTAssertNil(m.url(for: m.variants[0].files[0], variant: m.variants[0]))
        XCTAssertEqual(m.variant("int8")!.totalBytes, 34_363_188 + 383_883_281)
        XCTAssertEqual(m.variant("int8-partial")!.totalBytes, 34_363_188 + 357_361_791)
        // The manifest and LoupeKit's LayaModelStore must pin the same files to the same hashes.
        for v in m.variants {
            XCTAssertEqual(v.files.map(\.name), LayaOnPhone.shared.fileNames(variant: v.id), v.id)
            for f in v.files { XCTAssertEqual(f.sha256, LayaOnPhone.shared.pinnedSha256(variant: v.id, name: f.name), f.name) }
        }
    }

    func testTheUrlTemplateIsFilledOnlyForAnHttpsHost() throws {
        let m = try ModelManifest.parse(manifestJSON())
        let v = m.variants[0]
        XCTAssertEqual(m.url(for: v.files[1], variant: v)?.absoluteString, "https://models.example.org/laya/int8/g.onnx")
        XCTAssertNil(try ModelManifest.parse(manifestJSON(host: "http://plain.example.org")).configuredHost)
        XCTAssertNil(try ModelManifest.parse(manifestJSON(host: "  ")).configuredHost)
    }

    func testInvalidManifestsAreRefused() {
        XCTAssertThrowsError(try ModelManifest.parse(Data("{".utf8)))
        XCTAssertThrowsError(try ModelManifest.parse(manifestJSON(sha: "ABC")))                     // not 64 hex
        XCTAssertThrowsError(try ModelManifest.parse(manifestJSON(sha: String(repeating: "A", count: 64))))  // upper case
        XCTAssertThrowsError(try ModelManifest.parse(manifestJSON(name: "../escape.onnx")))        // path traversal
        XCTAssertThrowsError(try ModelManifest.parse(manifestJSON(defaultVariant: "fp16")))        // unknown default
        XCTAssertThrowsError(try ModelManifest.parse(manifestJSON(optIn: true)))                   // opt-in default
    }

    // MARK: SHA-256

    func testShaVerificationGoodBadAndPartial() throws {
        let f = ModelManifest.File(name: "g.onnx", size: Int64(graph.count), sha256: Self.sha(graph))
        let url = dir.appendingPathComponent("g.onnx")
        try graph.write(to: url)
        XCTAssertEqual(FileHash.verify(url, against: f), .match)
        XCTAssertEqual(FileHash.sha256(of: url, chunk: 1000), Self.sha(graph), "streaming in chunks gives the same hash")

        var bad = graph; bad[100] = 8
        try bad.write(to: url)
        guard case .mismatch = FileHash.verify(url, against: f) else { return XCTFail("a changed byte must not match") }

        try graph.prefix(1000).write(to: url)
        XCTAssertEqual(FileHash.verify(url, against: f), .wrongSize(actual: 1000))
        XCTAssertEqual(FileHash.verify(dir.appendingPathComponent("absent"), against: f), .unreadable)
        XCTAssertEqual(FileHash.sha256(of: dir.appendingPathComponent("absent")), nil)
    }

    func testInstallMovesOnlyAMatchingFileAndExcludesItFromBackup() throws {
        let (d, _, s) = try makeDownloader()
        try s.prepare()
        let f = d.variant.files[1]
        try Data(repeating: 1, count: graph.count).write(to: s.stagedURL(f))
        XCTAssertThrowsError(try s.install(f)) { e in
            guard case .verification(_, .mismatch) = e as? DeliveryError else { return XCTFail("\(e)") }
        }
        XCTAssertFalse(FileManager.default.fileExists(atPath: s.stagedURL(f).path), "a mismatch is deleted")
        XCTAssertFalse(FileManager.default.fileExists(atPath: s.finalURL(f).path), "and never enters the model folder")

        try graph.write(to: s.stagedURL(f))
        try s.install(f)
        XCTAssertTrue(FileManager.default.fileExists(atPath: s.finalURL(f).path))
        XCTAssertTrue(ModelStorage.isExcludedFromBackup(s.finalURL(f)))
        XCTAssertTrue(ModelStorage.isExcludedFromBackup(s.modelDir))
    }

    // MARK: disk space

    func testDiskSpaceCheck() {
        XCTAssertEqual(DiskSpace.check(remaining: 400_000_000, available: 700_000_000), .enough)
        XCTAssertEqual(DiskSpace.check(remaining: 400_000_000, available: 500_000_000),
                       .short(needed: 600_000_000, available: 500_000_000))
        XCTAssertEqual(DiskSpace.check(remaining: 0, available: DiskSpace.margin), .enough)
        XCTAssertEqual(DiskSpace.check(remaining: 1, available: nil), .unknown)
    }

    func testTooLittleSpaceRefusesBeforeAnyRequest() throws {
        let (d, t, _) = try makeDownloader(free: 1_000)
        d.recordConsent()
        d.start(allowsCellular: false)
        guard case .failed(.diskSpace) = d.phase else { return XCTFail("\(d.phase)") }
        XCTAssertTrue(t.starts.isEmpty)
    }

    // MARK: consent

    func testNoConsentNoRequest() throws {
        let (d, t, _) = try makeDownloader()
        XCTAssertFalse(d.hasConsent)
        d.start(allowsCellular: true)
        XCTAssertEqual(d.phase, .failed(.consentRequired))
        XCTAssertTrue(t.starts.isEmpty, "nothing is requested without consent")
    }

    func testAnEmptyHostMakesNoRequestEvenWithConsent() throws {
        let (d, t, _) = try makeDownloader(host: "")
        d.recordConsent()
        d.start(allowsCellular: false)
        XCTAssertEqual(d.phase, .failed(.notConfigured))
        XCTAssertTrue(t.starts.isEmpty)
    }

    func testConsentCoversOnlyTheVariantAndSizeAgreedTo() throws {
        let (d, _, _) = try makeDownloader()
        ModelConsent.save(ModelConsent(variant: "int8", bytes: 1, at: Date()), defaults)
        XCTAssertFalse(d.hasConsent, "a different size asks again")
        d.recordConsent()
        XCTAssertTrue(d.hasConsent)
        d.withdrawConsent()
        XCTAssertFalse(d.hasConsent)
    }

    // MARK: download, pause, resume

    func testDownloadsEachFileVerifiesAndInstalls() async throws {
        let (d, t, s) = try makeDownloader()
        d.recordConsent()
        d.start(allowsCellular: false)
        XCTAssertEqual(t.starts.map(\.name), ["tok.json"])
        XCTAssertEqual(t.starts[0].url.absoluteString, "https://models.example.org/laya/int8/tok.json")
        XCTAssertFalse(t.starts[0].cellular)
        d.transport(progress: "tok.json", written: 5, expected: Int64(tok.count))
        XCTAssertEqual(d.phase, .downloading(file: "tok.json", done: 5, total: d.totalBytes))
        try deliver(tok, as: "tok.json", d, s)
        await waitFor(d) { if case .downloading(file: "g.onnx", _, _) = $0 { return true }; return false }
        XCTAssertEqual(t.starts.map(\.name), ["tok.json", "g.onnx"])
        try deliver(graph, as: "g.onnx", d, s)
        await waitFor(d) { $0 == .installed }
        XCTAssertEqual(d.phase, .installed)
        XCTAssertTrue(s.missing(d.variant).isEmpty)
        XCTAssertFalse(FileManager.default.fileExists(atPath: s.stagingDir.path), "staging is cleared")
        XCTAssertFalse(defaults.bool(forKey: ModelDownloader.activeKey))
    }

    func testPauseKeepsResumeDataAndResumeUsesIt() async throws {
        let (d, t, s) = try makeDownloader()
        d.recordConsent()
        d.start(allowsCellular: true)
        d.transport(progress: "tok.json", written: 4, expected: Int64(tok.count))
        t.resumeDataOnPause = Data("resume-blob".utf8)
        d.pause()
        XCTAssertEqual(d.phase, .paused(done: 4, total: d.totalBytes))
        XCTAssertEqual(try Data(contentsOf: s.resumeDataURL(d.variant.files[0])), Data("resume-blob".utf8))
        // The cancelled task's own completion arrives after the pause: ignored.
        d.transport(failed: "tok.json", message: "cancelled", resumeData: Data("resume-blob".utf8), cancelled: true)
        XCTAssertEqual(d.phase, .paused(done: 4, total: d.totalBytes))

        d.start(allowsCellular: true)
        XCTAssertEqual(t.starts.count, 2)
        XCTAssertEqual(t.starts[1].resumeData, Data("resume-blob".utf8), "resumes from where it stopped")
        XCTAssertTrue(t.starts[1].cellular)
        XCTAssertFalse(FileManager.default.fileExists(atPath: s.resumeDataURL(d.variant.files[0]).path), "resume data is used once")
    }

    func testAFailedTransferKeepsResumeDataForTheNextStart() throws {
        let (d, t, _) = try makeDownloader()
        d.recordConsent()
        d.start(allowsCellular: false)
        d.transport(failed: "tok.json", message: "The network connection was lost.", resumeData: Data("r2".utf8), cancelled: false)
        guard case .failed(.transport(let m)) = d.phase else { return XCTFail("\(d.phase)") }
        XCTAssertTrue(m.contains("Resume"), m)
        d.start(allowsCellular: false)
        XCTAssertEqual(t.starts.last?.resumeData, Data("r2".utf8))
    }

    func testABadDownloadIsDeletedAndReported() async throws {
        let (d, _, s) = try makeDownloader()
        d.recordConsent()
        d.start(allowsCellular: false)
        try deliver(Data("tampered".utf8), as: "tok.json", d, s)
        await waitFor(d) { if case .failed = $0 { return true }; return false }
        guard case .failed(.verification("tok.json", _)) = d.phase else { return XCTFail("\(d.phase)") }
        XCTAssertFalse(FileManager.default.fileExists(atPath: s.finalURL(d.variant.files[0]).path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: s.stagedURL(d.variant.files[0]).path))
    }

    func testAnHttpErrorFails() throws {
        let (d, _, s) = try makeDownloader()
        d.recordConsent()
        d.start(allowsCellular: false)
        try deliver(Data("<html>not found</html>".utf8), as: "tok.json", d, s, status: 404)
        XCTAssertEqual(d.phase, .failed(.http(404, "tok.json")))
    }

    func testAFileFinishedWhileTheAppWasNotRunningIsCheckedAndInstalled() async throws {
        let (d, t, s) = try makeDownloader()
        try s.prepare()
        d.recordConsent()
        // A fresh launch: nothing current; iOS hands over the finished graph.
        try deliver(graph, as: "g.onnx", d, s)
        await waitFor(d) { if case .downloading(file: "tok.json", _, _) = $0 { return true }; return false }
        XCTAssertTrue(FileManager.default.fileExists(atPath: s.finalURL(d.variant.files[1]).path))
        XCTAssertEqual(t.starts.map(\.name), ["tok.json"], "then carries on with what is still missing")
    }

    func testABackgroundFinishWithoutConsentInstallsButRequestsNothingMore() async throws {
        let (d, t, s) = try makeDownloader()
        try s.prepare()
        try deliver(graph, as: "g.onnx", d, s)
        await waitFor(d) { if case .paused = $0 { return true }; return false }
        XCTAssertTrue(FileManager.default.fileExists(atPath: s.finalURL(d.variant.files[1]).path))
        XCTAssertTrue(t.starts.isEmpty)
    }

    func testAStagedFileFromAnEarlierRunIsVerifiedWithoutARequest() async throws {
        let (d, t, s) = try makeDownloader()
        try s.prepare()
        try tok.write(to: s.stagedURL(d.variant.files[0]))
        d.recordConsent()
        d.start(allowsCellular: false)
        await waitFor(d) { if case .downloading(file: "g.onnx", _, _) = $0 { return true }; return false }
        XCTAssertEqual(t.starts.map(\.name), ["g.onnx"])
    }

    func testDeleteRemovesTheFilesAndAsksForConsentAgain() async throws {
        let (d, t, s) = try makeDownloader()
        try s.prepare()
        try tok.write(to: s.finalURL(d.variant.files[0]))
        try graph.write(to: s.finalURL(d.variant.files[1]))
        d.recordConsent()
        d.deleteModel()
        XCTAssertEqual(s.missing(d.variant).count, 2)
        XCTAssertFalse(d.hasConsent)
        XCTAssertEqual(t.cancels, 1)
        XCTAssertEqual(d.phase, .idle)
    }

    func testLayaModelWithTheShippedManifestIsNotConfiguredAndRequestsNothing() throws {
        let t = FakeTransport()
        let model = LayaModel(directory: dir.path, manifest: ModelManifest.bundled(Bundle(for: LayaModel.self)),
                              transport: t, defaults: defaults)
        XCTAssertFalse(model.hostConfigured)
        XCTAssertEqual(model.status, .notInstalled)
        XCTAssertEqual(model.variantID, "int8")
        model.grantConsent()
        model.startDownload()
        XCTAssertEqual(model.deliveryError, .notConfigured)
        XCTAssertTrue(t.starts.isEmpty)
        model.select(variant: "int8-partial")
        XCTAssertEqual(model.variantID, "int8-partial")
        XCTAssertEqual(defaults.string(forKey: LayaModel.variantKey), "int8-partial")
        XCTAssertFalse(model.hasConsent, "consent was for the other variant")
    }

    func testTheConsentCopySaysSizeOneTimeAndNothingElse() {
        let lines = ModelConsentView.lines(size: "418 MB")
        XCTAssertTrue(lines[0].contains("418 MB") && lines[0].contains("once"))
        XCTAssertTrue(lines[1].contains("one-time"))
        XCTAssertTrue(lines[2].contains("nothing else goes online"))
    }
}
