import LoupeKit
import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// One phone source on the Sources page: its glyph, name, On device / Online badge and switch; what reading it
/// means; then either the live scan display (while it is read, and for the settle), its resting numbers (count
/// ring, detail, last scan, Scan again), or an invitation to the first scan.
struct PhoneSourceRow: View {
    @ObservedObject var sources: SourcesService
    let source: PhoneSource
    @State private var picking = false
    @Environment(\.accessibilityReduceMotion) private var systemReduceMotion

    private var st: PhoneSourceState { sources.state(source) }
    private var hue: Color { SourceLook.hue(source.id) }

    var body: some View {
        let live = sources.liveScans[source.id]
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .center, spacing: 12) {
                SourceGlyph(id: source.id, on: st.enabled)
                VStack(alignment: .leading, spacing: 3) {
                    HStack(spacing: 6) {
                        Text(source.title).font(Typeface.display(22)).foregroundStyle(Palette.ink)
                        SourceBadge(online: source.isOnline)
                            .accessibilityIdentifier(source.isOnline ? "sources.phone.mail.online" : "sources.phone.\(source.id).ondevice")
                    }
                    Text(connectionLine)
                        .font(Typeface.mono(11, weight: .medium))
                        .foregroundStyle(st.enabled ? hue : Palette.inkSoft)
                        .lineLimit(2)
                }
                Spacer(minLength: 8)
                Toggle(source.title, isOn: Binding(get: { st.enabled }, set: { on in Task { await sources.setPhoneEnabled(source, on) } }))
                    .labelsHidden()
                    .accessibilityLabel(source.title)
                    .accessibilityHint(st.enabled ? "Turns \(source.title) off; its items leave every judgment and watcher." : "Turns \(source.title) on and reads it.")
                    .accessibilityIdentifier("sources.phone.\(source.id).toggle")
            }
            Text(source.explainer).font(.footnote).foregroundStyle(Palette.inkSoft)
                .fixedSize(horizontal: false, vertical: true)

            if let live {
                ScanDisplay(live: live)
                    .transition(.opacity)
            } else if st.enabled || st.itemCount > 0 {
                rest
            } else {
                Label("Off. Turn it on to read \(source == .mail ? "your mailbox" : "your \(source.title.lowercased())"); \(source == .mail || source == .files ? "nothing is asked of iOS" : "iOS asks for permission once").",
                      systemImage: "power")
                    .font(.caption).foregroundStyle(Palette.inkSoft)
            }
            if source == .files && st.enabled { filesControls }
            if source == .mail { mailLink }
            if let problem = st.problem {
                Text(problem).font(.footnote).foregroundStyle(Palette.dangerText)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityIdentifier("sources.phone.\(source.id).problem")
                if st.permission == .denied { settingsButton }
            } else if st.enabled, let recovery = st.permission.recovery(for: source) {
                Text(recovery).font(.footnote).foregroundStyle(Palette.warnText)
                    .fixedSize(horizontal: false, vertical: true)
                if st.permission == .denied { settingsButton }
            }
            if st.enabled && st.permission == .notAsked && live == nil {
                // On by default (2026-09-26) but iOS has not asked yet (onboarding's permissions step was skipped).
                CardAction(title: "Allow access", symbol: "checkmark.shield", hue: hue) { Task { await sources.requestAccess(source) } }
                    .accessibilityIdentifier("sources.phone.\(source.id).allow")
            }
            if source == .photos && st.permission == .limited && live == nil {
                CardAction(title: "Choose more photos", symbol: "photo.badge.plus", hue: hue) { PhotoKitLibrary.presentLimitedPicker() }
            }
        }
        .card(active: live.map { !$0.finished } ?? false)
        .animation(Motion.reduced(systemReduceMotion) ? .easeInOut(duration: 0.25) : .spring(response: 0.45, dampingFraction: 0.9), value: live?.id)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("sources.phone.\(source.id)")
        .sheet(isPresented: $picking) {
            DocumentPicker { urls in Task { await sources.addPicked(urls) } }.ignoresSafeArea()
        }
    }

    /// "Connected · Allowed", "Connected · Limited: only the photos you chose", "Not connected".
    private var connectionLine: String {
        guard st.enabled else { return st.itemCount > 0 ? "Off · \(st.itemCount.formatted()) items kept aside" : "Not connected" }
        var parts = ["Connected"]
        if st.permission == .notAsked { parts = ["On · waiting for your OK"] }
        if st.permission != .notNeeded && st.permission != .notAsked { parts.append(st.permission.label) }
        if source == .mail, sources.mailAccount == nil { parts = ["No mailbox yet"] }
        return parts.joined(separator: " · ")
    }

    @ViewBuilder private var rest: some View {
        if st.enabled && st.lastScan == nil && st.itemCount == 0 && st.permission.canRead && st.problem == nil
            && !(source == .mail && sources.mailAccount == nil) && !(source == .files && sources.deps.bookmarks.all().isEmpty) {
            FirstScanInvite(id: "phone.\(source.id)", title: source.title) { Task { await sources.scanPhone(source) } }
        } else {
            if st.permission != .notNeeded && st.permission != .granted && st.permission != .limited {
                Text(st.permission.label).font(.footnote.weight(.semibold)).foregroundStyle(Palette.warnText)
                    .accessibilityIdentifier("sources.phone.\(source.id).permission")
            }
            SourceRestStats(id: source.id, count: st.itemCount,
                            countLine: "\(st.itemCount) \(st.itemCount == 1 ? "item" : "items")" + extras,
                            countId: "sources.phone.\(source.id).count", detail: st.detail, coverage: coverage,
                            lastScan: st.lastScan, on: st.enabled)
            if st.enabled && st.permission != .notAsked {   // not asked yet: "Allow access" below does the first read
                CardAction(title: "Scan again", symbol: "arrow.clockwise", hue: hue) { Task { await sources.scanPhone(source) } }
                    .accessibilityIdentifier("sources.phone.\(source.id).rescan")
            }
        }
    }

    /// How much of what Loupe can see is read: Photos counts the photos still waiting; others read everything.
    private var coverage: Double {
        guard st.itemCount > 0 else { return 0 }
        let pending = st.pending ?? 0
        return Double(st.itemCount) / Double(st.itemCount + pending)
    }

    private var extras: String {
        var parts: [String] = []
        if st.skippedCount > 0 { parts.append("\(st.skippedCount) skipped") }
        if st.unavailableCount > 0 { parts.append("\(st.unavailableCount) unavailable") }
        return parts.isEmpty ? "" : " · " + parts.joined(separator: " · ")
    }

    private var settingsButton: some View {
        CardAction(title: "Allow in Settings", symbol: "gear", hue: Palette.warnText) {
            if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
        }
    }

    @ViewBuilder private var filesControls: some View {
        VStack(alignment: .leading, spacing: 6) {
            ForEach(sources.deps.bookmarks.all()) { loc in
                HStack {
                    Label(loc.name, systemImage: loc.isFolder ? "folder" : "doc").font(.footnote).foregroundStyle(Palette.ink)
                    Spacer()
                    Button(role: .destructive) { Task { await sources.removePicked(loc.id) } } label: {
                        Image(systemName: "minus.circle").frame(minWidth: 44, minHeight: 44)
                    }
                    .buttonStyle(.borderless)
                    .accessibilityLabel("Stop reading \(loc.name)")
                }
            }
            CardAction(title: "Add files or a folder", symbol: "plus", hue: hue) { picking = true }
                .accessibilityIdentifier("sources.phone.files.add")
            Text("Share anything to “Send to Loupe” from another app and it appears here on the next open.")
                .font(.caption).foregroundStyle(Palette.inkSoft)
        }
    }

    private var mailLink: some View {
        NavigationLink {
            MailSetupView(sources: sources)
        } label: {
            HStack {
                Label(sources.mailAccount == nil ? "Add a mailbox" : "Mailbox settings", systemImage: "envelope.badge")
                    .font(.footnote.weight(.semibold)).foregroundStyle(hue)
                Spacer()
                Image(systemName: "chevron.right").font(.caption).foregroundStyle(Palette.inkSoft)
            }
            .frame(minHeight: 44)
            .contentShape(Rectangle())
        }
        .accessibilityIdentifier("sources.phone.mail.setup")
    }
}

/// `UIDocumentPickerViewController` for files and folders, opened in place (no copy): the picked
/// URLs are security-scoped and kept as bookmarks.
struct DocumentPicker: UIViewControllerRepresentable {
    let onPick: ([URL]) -> Void

    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.item, .folder], asCopy: false)
        picker.allowsMultipleSelection = true
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ controller: UIDocumentPickerViewController, context: Context) {}
    func makeCoordinator() -> Coordinator { Coordinator(onPick) }

    final class Coordinator: NSObject, UIDocumentPickerDelegate {
        let onPick: ([URL]) -> Void
        init(_ onPick: @escaping ([URL]) -> Void) { self.onPick = onPick }
        func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) { onPick(urls) }
    }
}

/// Adding the mailbox: a provider preset, server, user name and app password (Keychain, this device
/// only), and the Google / Microsoft sign-in, which says plainly when it has no client ID.
struct MailSetupView: View {
    @ObservedObject var sources: SourcesService
    @State private var preset = MailPreset.all[0]
    @State private var host = MailPreset.all[0].host
    @State private var username = ""
    @State private var password = ""
    @State private var saving = false
    @State private var error: String?
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        Form {
            NeonSection {
                Text("Mail is Online: Loupe connects from this iPhone straight to your mail server over TLS and reads your inbox without marking anything read. We never see it. Every message it brings back is labelled Online, and turning Mail off stops all requests.")
                    .font(.footnote)
            }
            if let account = sources.mailAccount {
                NeonSection("Mailbox") {
                    LabeledContent("Server", value: account.host)
                    LabeledContent("User", value: account.username)
                    LabeledContent("Sign-in", value: account.auth == .appPassword ? "App password (in the Keychain)" : "OAuth token (in the Keychain)")
                    Button("Remove mailbox and its messages", role: .destructive) { sources.removeMail(); dismiss() }
                        .accessibilityIdentifier("mail.remove")
                }
            }
            NeonSection("App password") {
                Picker("Provider", selection: $preset) {
                    ForEach(MailPreset.all) { Text($0.name).tag($0) }
                }
                .onChange(of: preset) { _, p in if !p.host.isEmpty { host = p.host } }
                TextField("IMAP server", text: $host)
                    .textInputAutocapitalization(.never).autocorrectionDisabled().keyboardType(.URL)
                    .accessibilityIdentifier("mail.host")
                TextField("User name (email address)", text: $username)
                    .textInputAutocapitalization(.never).autocorrectionDisabled().keyboardType(.emailAddress)
                    .accessibilityIdentifier("mail.user")
                SecureField("App password", text: $password)
                    .accessibilityIdentifier("mail.password")
                Text(preset.help).font(.footnote).foregroundStyle(Palette.inkSoft)
                Button(saving ? "Connecting…" : "Save and fetch") {
                    Task { await save() }
                }
                .disabled(saving || username.isEmpty || password.isEmpty || host.isEmpty)
                .accessibilityIdentifier("mail.save")
            }
            NeonSection("Sign in with your provider") {
                ForEach(OAuthProvider.allCases) { provider in
                    switch sources.deps.oauth.availability(provider) {
                    case .ready:
                        VStack(alignment: .leading, spacing: 4) {
                            Button("Sign in with \(provider.name)") { Task { await signIn(provider) } }.disabled(saving)
                                .accessibilityIdentifier("mail.oauth.\(provider.id).signin")
                            if provider == .google {
                                Text("Online. Loupe asks Google only to read Gmail (read-only); it never changes, sends or deletes mail.")
                                    .font(.footnote).foregroundStyle(Palette.inkSoft)
                            }
                        }
                    case .needsClientId(let why):
                        VStack(alignment: .leading, spacing: 4) {
                            Text("Sign in with \(provider.name)").foregroundStyle(Palette.inkSoft)
                            Text(why).font(.footnote).foregroundStyle(Palette.warnText)
                        }
                        .accessibilityElement(children: .combine)
                        .accessibilityIdentifier("mail.oauth.\(provider.id)")
                    }
                }
            }
            if let error {
                NeonSection { Text(error).font(.footnote).foregroundStyle(Palette.dangerText) }
            }
        }
        .neonList()
        .navigationTitle("Mail")
    }

    private func save() async {
        saving = true
        defer { saving = false }
        error = nil
        do {
            try await sources.saveMail(MailAccount(host: host, port: 993, username: username, auth: .appPassword), secret: password)
            password = ""
            if let p = sources.state(.mail).problem { error = p } else { dismiss() }
        } catch {
            self.error = error.localizedDescription
        }
    }

    private func signIn(_ provider: OAuthProvider) async {
        saving = true
        defer { saving = false }
        do { try await sources.signInMail(provider, username: username) } catch { self.error = error.localizedDescription }
    }
}

extension MailPreset: Hashable {
    func hash(into h: inout Hasher) { h.combine(id) }
}
