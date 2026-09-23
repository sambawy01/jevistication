import Foundation
import LoupeKit

/// The phone's own sources (epic #7 child 7). Each is off until the user turns it on in Sources;
/// turning it on is the only thing that asks iOS for its permission. Each yields `SourceItem`s into
/// the same cache (`SourceLibrary`) the sample uses, so Judgments, the watchers and the sort read
/// them with no change.
enum PhoneSource: String, CaseIterable, Identifiable {
    case photos, files, calendar, contacts, mail

    var id: String { rawValue }

    /// The `SourceLibrary` ids this source's items are stored under. Files also owns the share-sheet
    /// inbox ("Send to Loupe").
    var cacheIds: [String] {
        switch self {
        case .photos: return [PhoneSourceIds.shared.PHOTOS]
        case .files: return [PhoneSourceIds.shared.FILES, PhoneSourceIds.shared.SHARED]
        case .calendar: return [PhoneSourceIds.shared.CALENDAR]
        case .contacts: return [PhoneSourceIds.shared.CONTACTS]
        case .mail: return [PhoneSourceIds.shared.MAIL]
        }
    }

    var title: String {
        switch self {
        case .photos: return "Photos"
        case .files: return "Files"
        case .calendar: return "Calendar"
        case .contacts: return "Contacts"
        case .mail: return "Mail"
        }
    }

    var symbol: String {
        switch self {
        case .photos: return "photo.on.rectangle"
        case .files: return "folder"
        case .calendar: return "calendar"
        case .contacts: return "person.crop.circle"
        case .mail: return "envelope"
        }
    }

    /// What reading it means, in the words the row shows under its name.
    var explainer: String {
        switch self {
        case .photos: return "Reads photos and screenshots on this iPhone, and the text in them, with on-device OCR. Nothing leaves the phone."
        case .files: return "Reads files and folders you pick, and anything you send with “Send to Loupe” from the share sheet. Nothing leaves the phone."
        case .calendar: return "Reads your events: titles, times, attendees and how they repeat. Nothing leaves the phone."
        case .contacts: return "Reads names and email addresses so the impersonation watcher knows who you know. Nothing leaves the phone."
        case .mail: return "Online: fetches your own mailbox over IMAP, directly between this iPhone and your mail provider. We never see it."
        }
    }

    /// Mail is the one source that uses the network (PRODUCT.md §4a).
    var isOnline: Bool { self == .mail }
}

/// Where iOS stands on a source's permission. `notAsked` is the state before the user turns it on.
enum PhonePermission: Equatable {
    case notAsked
    case granted
    /// Photos with "Limited access": only the photos the user picked are visible.
    case limited
    case denied
    /// Restricted by Screen Time or a device profile: the user cannot grant it here.
    case restricted
    /// Not an iOS permission (Files, Mail): nothing to ask.
    case notNeeded

    var canRead: Bool { self == .granted || self == .limited || self == .notNeeded }

    var label: String {
        switch self {
        case .notAsked: return "Not asked yet"
        case .granted: return "Allowed"
        case .limited: return "Limited: only the photos you chose"
        case .denied: return "Not allowed"
        case .restricted: return "Restricted on this iPhone"
        case .notNeeded: return ""
        }
    }

    /// What to do about it, when something can be done.
    func recovery(for source: PhoneSource) -> String? {
        switch self {
        case .denied:
            return "Loupe was not allowed to read your \(source.title.lowercased()). Open Settings → Loupe → \(source.title) and allow it, then scan again."
        case .restricted:
            return "Screen Time or a device profile blocks \(source.title.lowercased()) for every app. Whoever manages this iPhone can lift it."
        case .limited:
            return "Only the photos you chose are read. Choose more, or allow all photos in Settings → Loupe → Photos."
        default:
            return nil
        }
    }
}

/// A scan's outcome for one source, before it is merged and stored.
struct PhoneScanOutput {
    var result: ScanResult
    /// Anything the next incremental scan needs (a PhotoKit change token, IMAP UIDVALIDITY/UID).
    var state: [String: String] = [:]
}

/// Errors a phone source reports, each with the sentence the Sources row shows.
struct PhoneSourceError: Error, Equatable, LocalizedError {
    let message: String
    init(_ message: String) { self.message = message }
    var errorDescription: String? { message }
}

extension ScanResult {
    static func of(_ items: [SourceItem], skipped: [Skipped] = [], unavailable: [Skipped] = []) -> ScanResult {
        ScanResult(items: items, skipped: skipped, unavailable: unavailable)
    }
}

/// Small persisted state per source (tokens, cursors), as JSON beside the scan cache.
final class PhoneStateStore {
    private let url: URL
    private let lock = NSLock()

    init(home: URL) {
        url = home.appendingPathComponent("sources/phone-state.json")
    }

    func state(_ source: String) -> [String: String] {
        lock.lock(); defer { lock.unlock() }
        return readAll()[source] ?? [:]
    }

    func set(_ source: String, _ value: [String: String]) {
        lock.lock(); defer { lock.unlock() }
        var all = readAll()
        all[source] = value.isEmpty ? nil : value
        try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        if let data = try? JSONEncoder().encode(all) { try? data.write(to: url, options: [.atomic, .completeFileProtection]) }
    }

    private func readAll() -> [String: [String: String]] {
        guard let data = try? Data(contentsOf: url) else { return [:] }
        return (try? JSONDecoder().decode([String: [String: String]].self, from: data)) ?? [:]
    }
}

enum ISOStamp {
    static func now(_ date: Date = Date()) -> String {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime]
        return f.string(from: date)
    }

    /// Local `yyyy-MM-dd'T'HH:mm` (or `yyyy-MM-dd` when [dayOnly]) in [zone].
    static func local(_ date: Date, zone: TimeZone = .current, dayOnly: Bool = false) -> String {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = zone
        let d = c.dateComponents([.year, .month, .day, .hour, .minute], from: date)
        let day = String(format: "%04d-%02d-%02d", d.year ?? 0, d.month ?? 0, d.day ?? 0)
        return dayOnly ? day : day + String(format: "T%02d:%02d", d.hour ?? 0, d.minute ?? 0)
    }
}
