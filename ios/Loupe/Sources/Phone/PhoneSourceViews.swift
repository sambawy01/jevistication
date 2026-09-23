import LoupeKit
import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// One phone source in the Sources list.
struct PhoneSourceRow: View {
    @ObservedObject var sources: SourcesService
    let source: PhoneSource
    @State private var picking = false

    private var st: PhoneSourceState { sources.state(source) }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Toggle(isOn: Binding(get: { st.enabled }, set: { on in Task { await sources.setPhoneEnabled(source, on) } })) {
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 6) {
                        Label(source.title, systemImage: source.symbol).font(.headline)
                        if source.isOnline { Pill(text: "Online", color: Palette.blue).accessibilityIdentifier("sources.phone.mail.online") }
                    }
                    Text(source.explainer).font(.footnote).foregroundStyle(Palette.inkSoft)
                }
            }
            .accessibilityIdentifier("sources.phone.\(source.id).toggle")

            if st.enabled || st.itemCount > 0 { status }
            if source == .files && st.enabled { filesControls }
            if source == .mail { mailLink }
            if let problem = st.problem {
                Text(problem).font(.footnote).foregroundStyle(Palette.dangerText)
                    .accessibilityIdentifier("sources.phone.\(source.id).problem")
                if st.permission == .denied { settingsButton }
            } else if st.enabled, let recovery = st.permission.recovery(for: source) {
                Text(recovery).font(.footnote).foregroundStyle(Palette.warnText)
                if st.permission == .denied { settingsButton }
            }
            if source == .photos && st.permission == .limited {
                Button("Choose more photos") { PhotoKitLibrary.presentLimitedPicker() }
                    .font(.footnote).buttonStyle(.borderless)
            }
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("sources.phone.\(source.id)")
        .sheet(isPresented: $picking) {
            DocumentPicker { urls in Task { await sources.addPicked(urls) } }.ignoresSafeArea()
        }
    }

    @ViewBuilder private var status: some View {
        if st.scanning {
            ProgressView { Text("Reading…").font(.footnote).foregroundStyle(Palette.inkSoft) }
                .accessibilityIdentifier("sources.phone.\(source.id).progress")
        } else {
            if st.permission != .notNeeded && st.permission != .granted {
                Text(st.permission.label).font(.footnote.weight(.semibold)).foregroundStyle(Palette.warnText)
                    .accessibilityIdentifier("sources.phone.\(source.id).permission")
            }
            Text("\(st.itemCount) \(st.itemCount == 1 ? "item" : "items")" + extras)
                .font(.subheadline)
                .accessibilityIdentifier("sources.phone.\(source.id).count")
            if let detail = st.detail { Text(detail).font(.footnote).foregroundStyle(Palette.inkSoft) }
            HStack {
                Text(st.lastScan.map { "Last scan \($0.formatted(date: .abbreviated, time: .shortened))" } ?? "Not scanned yet")
                    .font(.footnote).foregroundStyle(Palette.inkSoft)
                Spacer()
                if st.enabled {
                    Button("Scan again") { Task { await sources.scanPhone(source) } }
                        .font(.footnote).buttonStyle(.borderless)
                        .accessibilityIdentifier("sources.phone.\(source.id).rescan")
                }
            }
        }
    }

    private var extras: String {
        var parts: [String] = []
        if st.skippedCount > 0 { parts.append("\(st.skippedCount) skipped") }
        if st.unavailableCount > 0 { parts.append("\(st.unavailableCount) unavailable") }
        return parts.isEmpty ? "" : " · " + parts.joined(separator: " · ")
    }

    private var settingsButton: some View {
        Button("Open Settings") {
            if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
        }
        .font(.footnote).buttonStyle(.borderless)
    }

    @ViewBuilder private var filesControls: some View {
        ForEach(sources.deps.bookmarks.all()) { loc in
            HStack {
                Label(loc.name, systemImage: loc.isFolder ? "folder" : "doc").font(.footnote)
                Spacer()
                Button(role: .destructive) { Task { await sources.removePicked(loc.id) } } label: {
                    Image(systemName: "minus.circle")
                }
                .buttonStyle(.borderless)
                .accessibilityLabel("Stop reading \(loc.name)")
            }
        }
        Button { picking = true } label: { Label("Add files or a folder", systemImage: "plus") }
            .font(.footnote).buttonStyle(.borderless)
            .accessibilityIdentifier("sources.phone.files.add")
        Text("Share anything to “Send to Loupe” from another app and it appears here on the next open.")
            .font(.caption).foregroundStyle(Palette.inkSoft)
    }

    private var mailLink: some View {
        NavigationLink {
            MailSetupView(sources: sources)
        } label: {
            Text(sources.mailAccount == nil ? "Add a mailbox" : "Mailbox settings").font(.footnote)
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
            Section {
                Text("Mail is Online: Loupe connects from this iPhone straight to your mail server over TLS and reads your inbox without marking anything read. We never see it. Every message it brings back is labelled Online, and turning Mail off stops all requests.")
                    .font(.footnote)
            }
            if let account = sources.mailAccount {
                Section("Mailbox") {
                    LabeledContent("Server", value: account.host)
                    LabeledContent("User", value: account.username)
                    LabeledContent("Sign-in", value: account.auth == .appPassword ? "App password (in the Keychain)" : "OAuth token (in the Keychain)")
                    Button("Remove mailbox and its messages", role: .destructive) { sources.removeMail(); dismiss() }
                        .accessibilityIdentifier("mail.remove")
                }
            }
            Section("App password") {
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
            Section("Sign in with your provider") {
                ForEach(OAuthProvider.allCases) { provider in
                    switch sources.deps.oauth.availability(provider) {
                    case .ready:
                        Button("Sign in with \(provider.name)") { Task { await signIn(provider) } }.disabled(saving)
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
                Section { Text(error).font(.footnote).foregroundStyle(Palette.dangerText) }
            }
        }
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
