import Contacts
import EventKit
import Foundation
import LoupeKit

// MARK: - Calendar (EventKit, read only)

/// One event occurrence as EventKit gives it, reduced to what Loupe reads.
struct CalendarEventRecord: Equatable {
    let eventId: String
    let title: String
    let start: Date
    let end: Date?
    let allDay: Bool
    let location: String?
    let calendar: String?
    let organizer: String?
    let attendees: [String]
    let recurrence: String?
    let notes: String?
}

protocol EventStoreReading: AnyObject {
    func authorization() -> PhonePermission
    func requestAccess() async -> PhonePermission
    func events(from: Date, to: Date) -> [CalendarEventRecord]
}

/// EventKit with full read access (iOS 17 `requestFullAccessToEvents`). Loupe never writes events.
final class EventKitReader: EventStoreReading {
    private let store = EKEventStore()

    func authorization() -> PhonePermission {
        switch EKEventStore.authorizationStatus(for: .event) {
        case .fullAccess, .authorized: return .granted
        case .writeOnly, .denied: return .denied
        case .restricted: return .restricted
        case .notDetermined: return .notAsked
        @unknown default: return .denied
        }
    }

    func requestAccess() async -> PhonePermission {
        _ = try? await store.requestFullAccessToEvents()
        return authorization()
    }

    func events(from: Date, to: Date) -> [CalendarEventRecord] {
        store.refreshSourcesIfNecessary()
        let predicate = store.predicateForEvents(withStart: from, end: to, calendars: nil)
        return store.events(matching: predicate).map { e in
            CalendarEventRecord(
                eventId: e.calendarItemIdentifier, title: e.title ?? "", start: e.startDate, end: e.endDate,
                allDay: e.isAllDay, location: e.location, calendar: e.calendar?.title,
                organizer: e.organizer.map(Self.participant),
                attendees: (e.attendees ?? []).map(Self.participant),
                recurrence: e.recurrenceRules?.first.map(Self.describe), notes: e.notes)
        }
    }

    private static func participant(_ p: EKParticipant) -> String {
        let address = p.url.scheme == "mailto" ? String(p.url.absoluteString.dropFirst("mailto:".count)) : nil
        switch (p.name, address) {
        case let (name?, addr?) where !name.isEmpty: return "\(name) <\(addr)>"
        case let (name?, nil) where !name.isEmpty: return name
        case let (_, addr?): return addr
        default: return "(unnamed)"
        }
    }

    static func describe(_ rule: EKRecurrenceRule) -> String {
        let unit: String
        switch rule.frequency {
        case .daily: unit = "day"
        case .weekly: unit = "week"
        case .monthly: unit = "month"
        case .yearly: unit = "year"
        @unknown default: unit = "period"
        }
        var s = rule.interval == 1 ? "every \(unit)" : "every \(rule.interval) \(unit)s"
        if let end = rule.recurrenceEnd {
            if let d = end.endDate { s += " until \(ISOStamp.local(d, dayOnly: true))" } else if end.occurrenceCount > 0 { s += ", \(end.occurrenceCount) times" }
        }
        return s
    }
}

/// The Calendar producer: occurrences from a year back to a year ahead, each its own item. A full
/// read each time (EventKit has no change token for events), cheap because it is text only.
struct CalendarProducer {
    let store: EventStoreReading
    var zone: TimeZone = .current
    var now = Date()
    var daysBack = 365
    var daysAhead = 365

    func scan() -> PhoneScanOutput {
        let from = now.addingTimeInterval(-Double(daysBack) * 86_400)
        let to = now.addingTimeInterval(Double(daysAhead) * 86_400)
        let builder = PhoneItems()
        var seen = Set<String>()
        let items = store.events(from: from, to: to).compactMap { e -> SourceItem? in
            let item = builder.event(eventId: e.eventId, title: e.title,
                                     startIso: ISOStamp.local(e.start, zone: zone, dayOnly: e.allDay),
                                     endIso: e.end.map { ISOStamp.local($0, zone: zone, dayOnly: e.allDay) },
                                     allDay: e.allDay, location: e.location, calendar: e.calendar, organizer: e.organizer,
                                     attendees: e.attendees, recurrence: e.recurrence, notes: e.notes)
            return seen.insert(item.id).inserted ? item : nil
        }
        return PhoneScanOutput(result: .of(items))
    }
}

// MARK: - Contacts (Contacts framework, read only)

struct ContactRecord: Equatable {
    let contactId: String
    let name: String
    let organization: String?
    let emails: [String]
    let phones: [String]
}

protocol ContactStoreReading: AnyObject {
    func authorization() -> PhonePermission
    func requestAccess() async -> PhonePermission
    func contacts() throws -> [ContactRecord]
}

final class ContactsReader: ContactStoreReading {
    private let store = CNContactStore()

    func authorization() -> PhonePermission {
        switch CNContactStore.authorizationStatus(for: .contacts) {
        case .authorized: return .granted
        case .limited: return .limited
        case .denied: return .denied
        case .restricted: return .restricted
        case .notDetermined: return .notAsked
        @unknown default: return .denied
        }
    }

    func requestAccess() async -> PhonePermission {
        _ = try? await store.requestAccess(for: .contacts)
        return authorization()
    }

    func contacts() throws -> [ContactRecord] {
        let keys: [CNKeyDescriptor] = [CNContactFormatter.descriptorForRequiredKeys(for: .fullName),
                                       CNContactOrganizationNameKey as CNKeyDescriptor,
                                       CNContactEmailAddressesKey as CNKeyDescriptor,
                                       CNContactPhoneNumbersKey as CNKeyDescriptor]
        var out: [ContactRecord] = []
        try store.enumerateContacts(with: CNContactFetchRequest(keysToFetch: keys)) { c, _ in
            out.append(ContactRecord(contactId: c.identifier, name: CNContactFormatter.string(from: c, style: .fullName) ?? "",
                                     organization: c.organizationName.isEmpty ? nil : c.organizationName,
                                     emails: c.emailAddresses.map { $0.value as String },
                                     phones: c.phoneNumbers.map { $0.value.stringValue }))
        }
        return out
    }
}

/// The Contacts producer: every card, as contact facts the impersonation watcher reads.
struct ContactsProducer {
    let store: ContactStoreReading

    func scan() throws -> PhoneScanOutput {
        let builder = PhoneItems()
        let items = try store.contacts().map {
            builder.contact(contactId: $0.contactId, name: $0.name, organization: $0.organization, emails: $0.emails, phones: $0.phones)
        }
        return PhoneScanOutput(result: .of(items))
    }
}
