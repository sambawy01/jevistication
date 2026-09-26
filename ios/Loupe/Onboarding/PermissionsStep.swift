import SwiftUI
import UIKit
import UserNotifications

/// The iOS permissions onboarding asks for in one step (owner decision 2026-09-26), in this order.
enum PermissionKind: String, CaseIterable, Identifiable {
    case photos, calendar, contacts, notifications

    var id: String { rawValue }

    /// The source this permission reads, if any (notifications have none).
    var source: PhoneSource? {
        switch self {
        case .photos: return .photos
        case .calendar: return .calendar
        case .contacts: return .contacts
        case .notifications: return nil
        }
    }

    var title: String {
        switch self {
        case .photos: return "Photos"
        case .calendar: return "Calendar"
        case .contacts: return "Contacts"
        case .notifications: return "Notifications"
        }
    }

    var symbol: String {
        switch self {
        case .photos: return "photo.on.rectangle"
        case .calendar: return "calendar"
        case .contacts: return "person.crop.circle"
        case .notifications: return "bell.badge"
        }
    }

    /// The one-line reason shown beside it.
    var reason: String {
        switch self {
        case .photos: return "Finds receipts and documents in your photos and screenshots, reading their text on this iPhone."
        case .calendar: return "Full access, read only: renewals, bookings and events your judgments use. Loupe never changes an event."
        case .contacts: return "Knows who you know, so Loupe can warn you when a message borrows a contact's name."
        case .notifications: return "Tells you when Loupe spots a dangerous website, or finishes sorting while you charge."
        }
    }
}

/// Reads and asks for the permissions, behind a protocol so the step is tested without iOS prompts.
@MainActor
protocol PermissionAsking {
    /// Where iOS stands now, without asking.
    func status(_ kind: PermissionKind) async -> PhonePermission
    /// Shows iOS's prompt (only when it has not asked yet) and returns the answer.
    func request(_ kind: PermissionKind) async -> PhonePermission
}

/// The real one: the sources' own readers (PhotoKit, EventKit full access, Contacts) and UserNotifications.
@MainActor
struct SystemPermissionAsker: PermissionAsking {
    let deps: PhoneDependencies

    func status(_ kind: PermissionKind) async -> PhonePermission {
        switch kind {
        case .photos: return deps.photos.authorization()
        case .calendar: return deps.events.authorization()
        case .contacts: return deps.contacts.authorization()
        case .notifications: return Self.map(await UNUserNotificationCenter.current().notificationSettings().authorizationStatus)
        }
    }

    func request(_ kind: PermissionKind) async -> PhonePermission {
        switch kind {
        case .photos: return await deps.photos.requestAuthorization()
        case .calendar: return await deps.events.requestAccess()
        case .contacts: return await deps.contacts.requestAccess()
        case .notifications:
            _ = try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge])
            return await status(.notifications)
        }
    }

    nonisolated static func map(_ s: UNAuthorizationStatus) -> PhonePermission {
        switch s {
        case .notDetermined: return .notAsked
        case .denied: return .denied
        case .authorized, .provisional, .ephemeral: return .granted
        @unknown default: return .denied
        }
    }
}

#if DEBUG
/// `-LoupePermissions granted|denied` (DEBUG, UI tests): every permission reads "not asked" until the step
/// asks, then answers as told. No iOS prompt is shown.
@MainActor
final class FakePermissionAsker: PermissionAsking {
    let answer: PhonePermission
    private var asked: [PermissionKind: PhonePermission] = [:]

    init(answer: PhonePermission) { self.answer = answer }

    func status(_ kind: PermissionKind) async -> PhonePermission { asked[kind] ?? .notAsked }

    func request(_ kind: PermissionKind) async -> PhonePermission {
        asked[kind] = answer
        return answer
    }
}
#endif

/// The permissions step: the rows, walking the prompts in sequence, and what each answer was.
@MainActor
final class PermissionsStepModel: ObservableObject {
    struct Row: Identifiable, Equatable {
        let kind: PermissionKind
        var status: PhonePermission
        /// The user turned this source off in Sources: not asked, and it stays off.
        var sourceOff: Bool
        var id: String { kind.id }
    }

    @Published private(set) var rows: [Row]
    /// The permission whose iOS prompt is up right now.
    @Published private(set) var asking: PermissionKind?
    /// Every prompt has been through (or none was needed).
    @Published private(set) var walked = false

    private let asker: PermissionAsking
    private let onAnswered: () -> Void

    /// - Parameters:
    ///   - sourceOff: whether the user turned a source off (never-set reads on; such a source is not asked).
    ///   - onAnswered: after the walk, so the sources re-read their permissions and start their first scans.
    init(asker: PermissionAsking, sourceOff: (PhoneSource) -> Bool, onAnswered: @escaping () -> Void = {}) {
        self.asker = asker
        self.onAnswered = onAnswered
        rows = PermissionKind.allCases.map { k in
            Row(kind: k, status: .notAsked, sourceOff: k.source.map(sourceOff) ?? false)
        }
    }

    /// Reads where iOS stands on each, without asking.
    func load() async {
        for i in rows.indices { rows[i].status = await asker.status(rows[i].kind) }
    }

    /// The prompts still to show, in order.
    var pending: [PermissionKind] { rows.filter { !$0.sourceOff && $0.status == .notAsked }.map(\.kind) }

    /// Walks iOS's prompts one after another (each only if iOS has not asked yet) and marks each answer.
    func allowAll() async {
        guard asking == nil else { return }
        for kind in pending {
            asking = kind
            let answer = await asker.request(kind)
            if let i = rows.firstIndex(where: { $0.kind == kind }) { rows[i].status = answer }
        }
        asking = nil
        walked = true
        onAnswered()
    }

    /// Anything iOS was told no: a gentle "Allow in Settings" (never a blocker).
    var denied: [PermissionKind] { rows.filter { $0.status == .denied }.map(\.kind) }
}

/// "Loupe works on this iPhone with your photos, files, calendar and contacts. Nothing leaves the phone." One
/// button walks iOS's prompts in sequence, each row with its one-line reason and, once answered, what was
/// granted. Skippable; anything denied can be allowed later in Settings (Sources shows the same row).
struct PermissionsStepView: View {
    @StateObject var model: PermissionsStepModel
    /// Continue or Skip: the step is done either way and never shown again.
    var onDone: () -> Void

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                HStack(alignment: .bottom) {
                    Image("LogoLight").resizable().scaledToFit().frame(height: 30).accessibilityLabel("Loupe")
                    Spacer()
                    MascotView(state: model.asking == nil ? .greeting : .scanning, size: 84)
                }
                VStack(alignment: .leading, spacing: 8) {
                    Text("ON THIS IPHONE").font(Typeface.mono(11, weight: .medium)).tracking(0.8).foregroundStyle(Palette.cyan)
                    Text("Let Loupe read your phone").font(Typeface.display(34)).foregroundStyle(Palette.ink)
                        .accessibilityAddTraits(.isHeader)
                    Text("Loupe works on this iPhone with your photos, files, calendar and contacts. Nothing leaves the phone.")
                        .foregroundStyle(Palette.inkSoft)
                        .fixedSize(horizontal: false, vertical: true)
                        .accessibilityIdentifier("permissions.explainer")
                    Text("iOS asks you about each one in turn. Say no to any of them and the rest of Loupe still works.")
                        .font(.footnote).foregroundStyle(Palette.inkSoft)
                        .fixedSize(horizontal: false, vertical: true)
                }
                VStack(spacing: 0) {
                    ForEach(model.rows) { row in
                        PermissionRowView(row: row, asking: model.asking == row.kind)
                        Divider().overlay(Palette.inkSoft.opacity(0.2))
                    }
                    InfoRow(symbol: "folder", title: "Files", reason: "On. Pick folders any time in Sources; iOS asks nothing.",
                            id: "permissions.row.files")
                    Divider().overlay(Palette.inkSoft.opacity(0.2))
                    InfoRow(symbol: "envelope", title: "Mail", reason: "Needs your sign-in, so it waits for you: add a mailbox any time in Sources → Mail.",
                            id: "permissions.row.mail")
                }
                .card()
            }
            .padding(24)
            .frame(maxWidth: 560)
            .frame(maxWidth: .infinity)
        }
        .accessibilityIdentifier("permissions.screen")
        // The answer buttons stay in view whatever the text size, so the step can always be left.
        .safeAreaInset(edge: .bottom) {
            VStack(spacing: 4) { actions }
                .padding(.horizontal, 24).padding(.top, 10).padding(.bottom, 6)
                .frame(maxWidth: 560)
                .frame(maxWidth: .infinity)
                .background(Palette.ground.opacity(0.94).ignoresSafeArea(edges: .bottom))
        }
        .neonGround()
        .task { await model.load() }
    }

    @ViewBuilder private var actions: some View {
        if model.walked || model.pending.isEmpty {
            Button(action: onDone) {
                Text("Continue").frame(maxWidth: .infinity, minHeight: 44)
            }
            .buttonStyle(.neonPrimary)
            .accessibilityIdentifier("permissions.continue")
        } else {
            Button {
                Task { await model.allowAll() }
            } label: {
                Text(model.asking == nil ? "Allow access" : "Waiting for your answer…").frame(maxWidth: .infinity, minHeight: 44)
            }
            .buttonStyle(.neonPrimary)
            .disabled(model.asking != nil)
            .accessibilityIdentifier("permissions.allow")
            Button("Skip for now", action: onDone)
                .frame(maxWidth: .infinity, minHeight: 44)
                .foregroundStyle(Palette.inkSoft)
                .disabled(model.asking != nil)
                .accessibilityIdentifier("permissions.skip")
        }
    }
}

private struct PermissionRowView: View {
    let row: PermissionsStepModel.Row
    let asking: Bool

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            NeonIcon(name: row.kind.symbol, color: asking ? Palette.cyan : Palette.blue, size: 20, active: asking)
                .frame(width: 28)
            VStack(alignment: .leading, spacing: 3) {
                HStack {
                    Text(row.kind.title).font(.subheadline.weight(.semibold)).foregroundStyle(Palette.ink)
                    Spacer(minLength: 8)
                    mark
                }
                Text(row.kind.reason).font(.caption).foregroundStyle(Palette.inkSoft)
                    .fixedSize(horizontal: false, vertical: true)
                if row.status == .denied {
                    Button {
                        if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                    } label: {
                        Label("Allow in Settings", systemImage: "gear").font(.caption.weight(.semibold))
                    }
                    .frame(minHeight: 44)
                    .tint(Palette.warnText)
                    .accessibilityIdentifier("permissions.row.\(row.kind.id).settings")
                }
            }
        }
        .padding(.vertical, 10)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("permissions.row.\(row.kind.id)")
    }

    /// The answer, as one accessibility element: "Photos access", value "Allowed" (UI tests read the value).
    private var mark: some View {
        Group {
            if asking && !row.sourceOff {
                ProgressView()
            } else {
                let m = look
                Pill(text: m.text, color: m.color, symbol: m.symbol)
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(row.kind.title) access")
        .accessibilityIdentifier("permissions.row.\(row.kind.id).status")
        .accessibilityValue(asking && !row.sourceOff ? "Waiting for your answer" : look.text)
    }

    private var look: (text: String, color: Color, symbol: String) {
        if row.sourceOff { return ("Off in Sources", Palette.inkSoft, "power") }
        switch row.status {
        case .granted, .notNeeded: return ("Allowed", Palette.okText, "checkmark")
        case .limited: return ("Some photos", Palette.okText, "checkmark")
        case .denied: return ("Not allowed", Palette.warnText, "xmark")
        case .restricted: return ("Restricted", Palette.warnText, "lock")
        case .notAsked: return ("Not asked yet", Palette.inkSoft, "circle")
        }
    }
}

private struct InfoRow: View {
    let symbol: String
    let title: String
    let reason: String
    let id: String

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            NeonIcon(name: symbol, color: Palette.blue, size: 20, active: false).frame(width: 28)
            VStack(alignment: .leading, spacing: 3) {
                Text(title).font(.subheadline.weight(.semibold)).foregroundStyle(Palette.ink)
                Text(reason).font(.caption).foregroundStyle(Palette.inkSoft).fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 0)
        }
        .padding(.vertical, 10)
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier(id)
    }
}
