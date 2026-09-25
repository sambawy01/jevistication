import Foundation

/// "Send to Loupe": the Share Extension drops what was shared into an App Group folder; the app reads
/// it as the `shared` source. Files keep their names; links and text become `.txt` files.
enum SharedInbox {
    static let appGroup = "group.com.loupe-ai.ios"

    /// The inbox folder: the App Group container when the entitlement is present, else a folder in
    /// the app's own container (a build without the App Group, e.g. an unsigned test run).
    static func folder(fileManager: FileManager = .default) -> URL {
        let base = fileManager.containerURL(forSecurityApplicationGroupIdentifier: appGroup)
            ?? fileManager.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("Loupe")
        let dir = base.appendingPathComponent("SharedInbox", isDirectory: true)
        try? fileManager.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    /// The App Group inbox only, or nil when the build has no App Group entitlement. The Share
    /// Extension uses this: its own container is not the app's, so a fallback would lose the file.
    static func groupFolder(fileManager: FileManager = .default) -> URL? {
        guard let base = fileManager.containerURL(forSecurityApplicationGroupIdentifier: appGroup) else { return nil }
        let dir = base.appendingPathComponent("SharedInbox", isDirectory: true)
        try? fileManager.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    /// Writes shared text or a link as a small text file; returns its URL.
    @discardableResult
    static func drop(text: String, title: String?, into dir: URL, now: Date = Date()) throws -> URL {
        let stamp = Int(now.timeIntervalSince1970 * 1000)
        let name = sanitize(title ?? "Shared text") + "-\(stamp).txt"
        let url = dir.appendingPathComponent(name)
        try Data(text.utf8).write(to: url, options: .atomic)
        return url
    }

    /// Copies a shared file in, never overwriting an earlier one with the same name.
    @discardableResult
    static func drop(file: URL, into dir: URL) throws -> URL {
        var target = dir.appendingPathComponent(sanitize(file.lastPathComponent))
        var n = 2
        while FileManager.default.fileExists(atPath: target.path) {
            let ext = file.pathExtension
            let base = sanitize(file.deletingPathExtension().lastPathComponent)
            target = dir.appendingPathComponent(ext.isEmpty ? "\(base) \(n)" : "\(base) \(n).\(ext)")
            n += 1
        }
        try FileManager.default.copyItem(at: file, to: target)
        return target
    }

    /// What the share sheet hands to the Inbox (epic #7 child 15) rather than to Files: CSVs, mail
    /// files, ZIP archives, and shared text or links. They wait in a hidden `.import` folder (the
    /// `shared` scan skips hidden folders) until the app opens and imports them as one batch.
    static let inboxExtensions: Set<String> = ["csv", "tsv", "eml", "mbox", "zip"]

    static func goesToInbox(_ file: URL) -> Bool { inboxExtensions.contains(file.pathExtension.lowercased()) }

    static func importFolder(in inbox: URL) -> URL {
        let dir = inbox.appendingPathComponent(".import", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    static func sanitize(_ s: String) -> String {
        let cleaned = s.replacingOccurrences(of: "/", with: "-").replacingOccurrences(of: ":", with: "-")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmed = String(cleaned.prefix(80))
        return trimmed.isEmpty || trimmed.hasPrefix(".") ? "Shared" + trimmed : trimmed
    }

}
