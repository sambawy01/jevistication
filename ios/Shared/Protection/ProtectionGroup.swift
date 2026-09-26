import Foundation

/// Browsing protection's shared ground (2026-09-26): the App Group `group.com.loupe-ai.ios` that the
/// app, "Send to Loupe" and Loupe for Safari all open. What lives here:
///
/// - `online-phishing/`: the phishing lists the app downloads (only when the user turned the lists on)
///   and Phishing.Database's flat index (`phishingdb/phishingdb.index`) that the extensions map.
/// - `protection/spotted.json`: the Spotted log (domains only, 90 days).
/// - `protection/recent-checks.json`: Check a link's recent checks (clearable).
/// - The group's UserDefaults: a mirror of the online-check switches (the app is the only writer) and
///   the extension's "last asked" time (a time only, never a site).
///
/// A build without the App Group (an unsigned test run) falls back to the process's own
/// Application Support, so each process then sees only its own files.
enum ProtectionGroup {
    static let id = "group.com.loupe-ai.ios"
    /// The Safari extension's bundle id (project.yml `LoupeSafari`).
    static let safariExtensionId = "com.loupe-ai.ios.safari"
    /// Posted (Darwin notify) by an extension after it wrote the Spotted log, so the app reloads it.
    static let spottedChanged = "com.loupe-ai.ios.protection.spotted"

    /// The App Group container, or nil when this build has none.
    static func container(_ fm: FileManager = .default) -> URL? {
        fm.containerURL(forSecurityApplicationGroupIdentifier: id)
    }

    /// The App Group container, else this process's own Application Support/Loupe.
    static func root(_ fm: FileManager = .default) -> URL {
        container(fm) ?? fm.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("Loupe")
    }

    /// Where the phishing lists live (moved from the app's own Application Support on 2026-09-26).
    static func phishingRoot(_ fm: FileManager = .default) -> URL { root(fm).appendingPathComponent("online-phishing", isDirectory: true) }

    /// Phishing.Database's folder inside [phishingRoot] (the app's `PhishingDatabaseStore.dir`).
    static func phishingDbDir(_ fm: FileManager = .default) -> URL { phishingRoot(fm).appendingPathComponent("phishingdb", isDirectory: true) }

    /// OpenPhish / PhishTank text lists (the app's `PhishingFeeds.dir`).
    static func feedsDir(_ fm: FileManager = .default) -> URL { phishingRoot(fm).appendingPathComponent("feeds", isDirectory: true) }

    /// The flat index the extensions map (written by the app whenever it rebuilds the index).
    static let phishingDbIndexName = "phishingdb.index"

    static func protectionDir(_ fm: FileManager = .default) -> URL {
        let dir = root(fm).appendingPathComponent("protection", isDirectory: true)
        try? fm.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    /// The group's shared defaults, or `.standard` without the App Group.
    static var defaults: UserDefaults { UserDefaults(suiteName: id) ?? .standard }

    enum Keys {
        /// When Loupe for Safari last answered Safari (seconds since 1970). A time only.
        static let safariLastSeen = "protection.safari.lastSeen"
        /// Notify about suspicious sites too (dangerous ones always notify once notifications are allowed).
        static let notifySuspicious = "protection.notify.suspicious"
    }

    /// Posts the Darwin notification [name] (no payload; any process in the group may listen).
    static func post(_ name: String) {
        CFNotificationCenterPostNotification(CFNotificationCenterGetDarwinNotifyCenter(), CFNotificationName(name as CFString), nil, nil, true)
    }
}

/// A cross-process lock on a file in the group (flock), for read-modify-write of the small JSON
/// stores that the app and the extensions share.
enum GroupFileLock {
    static func with<T>(_ lockFile: URL, _ body: () throws -> T) rethrows -> T {
        let fd = open(lockFile.path, O_CREAT | O_RDWR, 0o600)
        if fd >= 0 { flock(fd, LOCK_EX) }
        defer { if fd >= 0 { flock(fd, LOCK_UN); close(fd) } }
        return try body()
    }
}
