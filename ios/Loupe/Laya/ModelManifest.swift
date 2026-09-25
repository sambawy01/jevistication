import CryptoKit
import Foundation

// Model delivery (epic #7 child 9). The manifest, the file checks and the disk-space rule are pure
// Foundation so they are unit-tested without a network or the real 418 MB files.
//
// Adapted from Loupe Station's ModelStore.swift (~/laya-studio/macos/Sources/LoupeStation,
// the owner's repo): the bundled pinned manifest, the streaming hash check, the free-space margin
// and "a partial file never sits where a finished one is loaded from". iOS differs: a background
// URLSession download task (not a data task), resume data instead of Range appends, and
// Application Support excluded from backup instead of a Hugging Face hub cache.

/// `Resources/Laya/models.json`: every graph variant with its files, sizes and SHA-256 pins, and
/// where they are downloaded from (the owner's Supabase Storage bucket; an empty host = not configured).
struct ModelManifest: Decodable, Equatable {
    struct Source: Decodable, Equatable {
        /// `https://…` with no trailing slash, or "" (not configured).
        let host: String
        /// `{host}`, `{variant}` and `{file}` are substituted.
        let urlTemplate: String
    }

    struct File: Decodable, Equatable, Hashable {
        let name: String
        let size: Int64
        let sha256: String
    }

    struct Variant: Decodable, Equatable, Identifiable {
        let id: String
        let title: String
        let detail: String
        let optIn: Bool
        let files: [File]
        var totalBytes: Int64 { files.reduce(0) { $0 + $1.size } }
    }

    enum Problem: Error, Equatable, LocalizedError {
        case unreadable(String)
        case invalid(String)
        var errorDescription: String? {
            switch self {
            case .unreadable(let m): return "The model manifest could not be read: \(m)"
            case .invalid(let m): return "The model manifest is not valid: \(m)"
            }
        }
    }

    let schema: Int
    let source: Source
    let defaultVariant: String
    let variants: [Variant]

    /// Decodes and checks: schema 1, a known default, unique variants, 64-hex lower-case pins,
    /// positive sizes, plain file names (no path separators, so nothing escapes the model folder).
    static func parse(_ data: Data) throws -> ModelManifest {
        let m: ModelManifest
        do { m = try JSONDecoder().decode(ModelManifest.self, from: data) } catch {
            throw Problem.unreadable(String(describing: error))
        }
        guard m.schema == 1 else { throw Problem.invalid("schema \(m.schema) is not 1") }
        guard !m.variants.isEmpty else { throw Problem.invalid("no variants") }
        guard Set(m.variants.map(\.id)).count == m.variants.count else { throw Problem.invalid("duplicate variant ids") }
        guard let def = m.variants.first(where: { $0.id == m.defaultVariant }) else {
            throw Problem.invalid("default variant \(m.defaultVariant) is not listed")
        }
        guard !def.optIn else { throw Problem.invalid("the default variant cannot be opt-in") }
        guard m.source.urlTemplate.contains("{file}") else { throw Problem.invalid("urlTemplate has no {file}") }
        let hex = CharacterSet(charactersIn: "0123456789abcdef")
        for v in m.variants {
            guard !v.files.isEmpty else { throw Problem.invalid("\(v.id) has no files") }
            for f in v.files {
                guard f.size > 0 else { throw Problem.invalid("\(f.name): size \(f.size)") }
                guard f.sha256.count == 64, f.sha256.unicodeScalars.allSatisfy(hex.contains) else {
                    throw Problem.invalid("\(f.name): SHA-256 must be 64 lower-case hex characters")
                }
                guard !f.name.isEmpty, !f.name.contains("/"), !f.name.contains("\\"), f.name != ".", f.name != ".." else {
                    throw Problem.invalid("\(f.name): not a plain file name")
                }
            }
        }
        return m
    }

    static func bundled(_ bundle: Bundle = .main) -> Result<ModelManifest, Problem> {
        guard let url = bundle.url(forResource: "models", withExtension: "json"),
              let data = try? Data(contentsOf: url) else { return .failure(.unreadable("models.json is not in the app")) }
        do {
            let m = try parse(data)
            #if DEBUG
            // -LoupeNoModelHost: behave as a build with no host (UI tests keep the not-configured copy covered).
            if ProcessInfo.processInfo.arguments.contains("-LoupeNoModelHost") { return .success(m.withoutHost) }
            #endif
            return .success(m)
        } catch let p as Problem { return .failure(p) } catch {
            return .failure(.unreadable(error.localizedDescription))
        }
    }

    /// The same manifest with an empty host: "not configured", no request is ever made.
    var withoutHost: ModelManifest {
        ModelManifest(schema: schema, source: Source(host: "", urlTemplate: source.urlTemplate),
                      defaultVariant: defaultVariant, variants: variants)
    }

    func variant(_ id: String) -> Variant? { variants.first { $0.id == id } }

    /// The HTTPS host, or nil: an empty or non-https host is "not configured", and the app then
    /// makes no request at all.
    var configuredHost: URL? {
        let h = source.host.trimmingCharacters(in: .whitespaces)
        guard !h.isEmpty, let u = URL(string: h), u.scheme?.lowercased() == "https", u.host?.isEmpty == false else { return nil }
        return u
    }

    /// Where [file] of [variant] comes from, or nil while the host is not configured.
    func url(for file: File, variant: Variant) -> URL? {
        guard configuredHost != nil else { return nil }
        let host = source.host.trimmingCharacters(in: .whitespaces).trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        let enc: (String) -> String = { $0.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? $0 }
        let s = source.urlTemplate
            .replacingOccurrences(of: "{host}", with: host)
            .replacingOccurrences(of: "{variant}", with: enc(variant.id))
            .replacingOccurrences(of: "{file}", with: enc(file.name))
        guard let u = URL(string: s), u.scheme?.lowercased() == "https" else { return nil }
        return u
    }
}

/// Streaming SHA-256 over a file, 4 MiB at a time (the 384 MB graph never sits in memory).
enum FileHash {
    enum Verdict: Equatable {
        case match
        case mismatch(actual: String)
        case wrongSize(actual: Int64)
        case unreadable
    }

    static func sha256(of url: URL, chunk: Int = 4 << 20) -> String? {
        guard let h = try? FileHandle(forReadingFrom: url) else { return nil }
        defer { try? h.close() }
        var hasher = SHA256()
        while true {
            let data: Data?
            do { data = try h.read(upToCount: chunk) } catch { return nil }
            guard let data, !data.isEmpty else { break }     // nil or empty: end of file
            hasher.update(data: data)
        }
        return hasher.finalize().map { String(format: "%02x", $0) }.joined()
    }

    /// Size first (cheap, catches a partial file without hashing), then the hash.
    static func verify(_ url: URL, against file: ModelManifest.File) -> Verdict {
        guard let size = (try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? NSNumber)?.int64Value else {
            return .unreadable
        }
        guard size == file.size else { return .wrongSize(actual: size) }
        guard let actual = sha256(of: url) else { return .unreadable }
        return actual == file.sha256 ? .match : .mismatch(actual: actual)
    }
}

/// "Is there room?" — what is left to download plus a margin, against the volume's
/// important-usage capacity (what iOS will free up for a user-initiated download).
enum DiskSpace {
    /// Head-room kept free beyond the files themselves (the move and the OS need some).
    static let margin: Int64 = 200_000_000

    enum Verdict: Equatable {
        case enough
        case short(needed: Int64, available: Int64)
        case unknown
    }

    static func check(remaining: Int64, available: Int64?) -> Verdict {
        guard let available else { return .unknown }
        let needed = max(0, remaining) + margin
        return available >= needed ? .enough : .short(needed: needed, available: available)
    }

    static func available(at url: URL) -> Int64? {
        let values = try? url.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])
        return values?.volumeAvailableCapacityForImportantUsage
    }
}

/// Where the files live: `Library/Application Support/Loupe/laya-multilingual` (where LoupeKit's
/// LayaModelStore opens them) and, beside it, a staging folder for downloaded-but-unchecked files
/// and resume data. A file is only ever moved into the model folder after its hash matched.
struct ModelStorage {
    let modelDir: URL
    let stagingDir: URL

    init(modelDir: URL, stagingDir: URL? = nil) {
        self.modelDir = modelDir
        self.stagingDir = stagingDir ?? modelDir.deletingLastPathComponent().appendingPathComponent("laya-download", isDirectory: true)
    }

    func finalURL(_ f: ModelManifest.File) -> URL { modelDir.appendingPathComponent(f.name) }
    func stagedURL(_ f: ModelManifest.File) -> URL { stagingDir.appendingPathComponent(f.name + ".download") }
    func resumeDataURL(_ f: ModelManifest.File) -> URL { stagingDir.appendingPathComponent(f.name + ".resume") }

    /// Files not present at their exact size (no hashing: LoupeKit hashes on open).
    func missing(_ v: ModelManifest.Variant) -> [ModelManifest.File] {
        v.files.filter { f in
            let size = (try? FileManager.default.attributesOfItem(atPath: finalURL(f).path)[.size] as? NSNumber)?.int64Value
            return size != f.size
        }
    }

    func remainingBytes(_ v: ModelManifest.Variant) -> Int64 { missing(v).reduce(0) { $0 + $1.size } }

    func prepare() throws {
        let fm = FileManager.default
        for dir in [modelDir, stagingDir] {
            try fm.createDirectory(at: dir, withIntermediateDirectories: true)
            Self.excludeFromBackup(dir)
        }
    }

    /// Checks a staged file and, only if it matches, moves it into the model folder: `rename` within
    /// one volume is atomic, so the model folder never holds a half or unchecked file. A mismatch
    /// deletes the staged file (and any resume data, which would reproduce it).
    func install(_ f: ModelManifest.File) throws {
        let staged = stagedURL(f)
        let verdict = FileHash.verify(staged, against: f)
        guard verdict == .match else {
            try? FileManager.default.removeItem(at: staged)
            try? FileManager.default.removeItem(at: resumeDataURL(f))
            throw DeliveryError.verification(f.name, verdict)
        }
        let dest = finalURL(f)
        try FileManager.default.createDirectory(at: modelDir, withIntermediateDirectories: true)
        if rename(staged.path, dest.path) != 0 {
            throw DeliveryError.io("Could not move \(f.name) into place (errno \(errno)).")
        }
        Self.excludeFromBackup(dest)
        try? FileManager.default.removeItem(at: resumeDataURL(f))
    }

    /// Removes every file of every variant, and the staging folder. Nothing else is touched.
    func deleteAll(_ manifest: ModelManifest) {
        let names = Set(manifest.variants.flatMap(\.files).map(\.name))
        for n in names { try? FileManager.default.removeItem(at: modelDir.appendingPathComponent(n)) }
        try? FileManager.default.removeItem(at: stagingDir)
    }

    /// Files the other variants use that the chosen one does not (the tokenizer is shared).
    func unusedGraphs(_ manifest: ModelManifest, keeping v: ModelManifest.Variant) -> [URL] {
        let keep = Set(v.files.map(\.name))
        return Set(manifest.variants.flatMap(\.files).map(\.name)).subtracting(keep).sorted()
            .map { modelDir.appendingPathComponent($0) }
            .filter { FileManager.default.fileExists(atPath: $0.path) }
    }

    static func excludeFromBackup(_ url: URL) {
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var u = url
        try? u.setResourceValues(values)
    }

    static func isExcludedFromBackup(_ url: URL) -> Bool {
        (try? url.resourceValues(forKeys: [.isExcludedFromBackupKey]))?.isExcludedFromBackup ?? false
    }
}

enum DeliveryError: Error, Equatable, LocalizedError {
    case notConfigured
    case consentRequired
    case cellularNotAllowed
    case diskSpace(needed: Int64, available: Int64)
    case http(Int, String)
    case verification(String, FileHash.Verdict)
    case io(String)
    case transport(String)

    var errorDescription: String? {
        switch self {
        case .notConfigured: return "This build has no download host for the model."
        case .consentRequired: return "The download needs your go-ahead first."
        case .cellularNotAllowed: return "Waiting for Wi-Fi. Allow mobile data to download now."
        case let .diskSpace(needed, available):
            return "Not enough space: the model needs \(Self.bytes(needed)) free and this iPhone has \(Self.bytes(available)). Free some space, then try again."
        case let .http(code, name): return "The model host answered HTTP \(code) for \(name)."
        case let .verification(name, verdict):
            switch verdict {
            case .wrongSize(let s): return "\(name) arrived incomplete (\(Self.bytes(s))), so it was deleted and not used."
            default: return "\(name) did not match its SHA-256 fingerprint, so it was deleted and not used."
            }
        case .io(let m): return m
        case .transport(let m): return m
        }
    }

    static func bytes(_ n: Int64) -> String { ByteCountFormatter.string(fromByteCount: n, countStyle: .file) }
}
