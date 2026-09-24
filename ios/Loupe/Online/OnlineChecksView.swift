import LoupeKit
import SwiftUI

/// Me → Online phishing checks: each source off by default, with what it sends and to whom
/// (PRODUCT.md §4a). Mail triage and link checks use them only while a switch is on.
struct OnlineChecksView: View {
    @ObservedObject var online: OnlineChecksService
    @State private var keyDraft = ""
    @State private var keyError: String?

    var body: some View {
        Form {
            Section {
                Text("Loupe checks phishing on this phone. These switches add facts that only exist online. Each is off until you turn it on, every result says \"Online\" with its source and time, and Loupe works fully with them off.")
                    .font(.footnote).foregroundStyle(Palette.inkSoft)
            }
            Section {
                Toggle("Domain age and certificates", isOn: binding(\.domainFacts))
                    .accessibilityIdentifier("online.domainFacts")
                Text("Online · Loupe web helper. Sends one website domain at a time (e.g. example.com; never a full address, a page, an email or anything about you) and gets back when it was registered and when its first security certificate appeared. The helper keeps looked-up domains in memory for up to 6 hours, never on disk, never shared between installs. Websites on hosting platforms (x.github.io) are never looked up.")
                    .font(.caption).foregroundStyle(Palette.inkSoft)
            } header: { Text("Online · Web helper") }
            Section {
                Toggle("Known-phishing lists", isOn: binding(\.feeds))
                    .accessibilityIdentifier("online.feeds")
                if online.settings.feeds {
                    Toggle("Also PhishTank (large download)", isOn: binding(\.phishTank))
                        .accessibilityIdentifier("online.phishtank")
                }
                Text("Online · OpenPhish (and PhishTank if you add it). Downloads the public list — the same file for everyone — at most twice a day, and matches your links on this phone. Nothing about your links is sent.")
                    .font(.caption).foregroundStyle(Palette.inkSoft)
            } header: { Text("Online · Phishing lists") }
            Section {
                Toggle("Google Safe Browsing", isOn: binding(\.safeBrowsing))
                    .disabled(!online.keySet)
                    .accessibilityIdentifier("online.safeBrowsing")
                if online.keySet {
                    Button("Remove my key", role: .destructive) { online.deleteKey() }
                } else {
                    SecureField("Your Google API key", text: $keyDraft)
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                        .accessibilityIdentifier("online.safeBrowsingKey")
                    Button("Save key") {
                        do { try online.saveKey(keyDraft); keyDraft = ""; keyError = nil }
                        catch { keyError = "That does not look like a Google API key." }
                    }
                    .disabled(keyDraft.isEmpty)
                    if let keyError { Text(keyError).font(.caption).foregroundStyle(Palette.dangerText) }
                }
                Text("Online · Google, with your own key (kept in this iPhone's Keychain, never sent anywhere else). Uses Safe Browsing API v5: downloads Google's lists of hash prefixes and matches your links on this phone. Only hash prefixes leave the phone — when a link matches the list, its 4-byte hash prefixes are sent to Google to confirm; never the link, its domain or a full hash.")
                    .font(.caption).foregroundStyle(Palette.inkSoft)
            } header: { Text("Online · Google Safe Browsing") }
            if let status = online.status {
                Section("Last run") {
                    Text(status).font(.caption).foregroundStyle(Palette.ink)
                        .accessibilityIdentifier("online.status")
                }
            }
        }
        .scrollContentBackground(.hidden)
        .background(Palette.ground.ignoresSafeArea())
        .navigationTitle("Online phishing checks")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func binding(_ path: WritableKeyPath<OnlinePhishingSettings, Bool>) -> Binding<Bool> {
        Binding(get: { online.settings[keyPath: path] }, set: { v in
            var s = online.settings
            s[keyPath: path] = v
            online.update(s)
        })
    }
}
