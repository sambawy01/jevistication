import SwiftUI

struct KeyOnboardingView: View {
    @EnvironmentObject private var web: WebModel
    @State private var key = ""

    var body: some View {
        VStack(spacing: 16) {
            if !web.seenExplainer { explainer } else { addKey }
        }
    }

    private var explainer: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 6) {
                    Caption(text: "Web · Flights")
                    Text("Search flights, judged on this phone")
                        .font(Typeface.display(28)).foregroundStyle(Palette.ink)
                }
                Spacer()
                MascotView(state: .greeting, size: 78)
            }
            Text("Flights uses your own Duffel key. Searches go online through Loupe's helper; your files, mail and judgments never do.")
                .foregroundStyle(Palette.ink)
            VStack(alignment: .leading, spacing: 8) {
                point("key", "Your key stays in this phone's Keychain. The helper uses it for one request and keeps nothing.")
                point("iphone", "Offers are ranked on this phone against your priorities.")
                point("hand.raised", "Loupe never books or pays. You finish on Duffel or the airline.")
                point("wifi.slash", "Turn the helper off any time. Everything else keeps working offline.")
            }
            Button {
                web.seenExplainer = true
            } label: {
                Text("Add key").font(.headline).frame(maxWidth: .infinity).padding(.vertical, 6)
            }
            .buttonStyle(.neonPrimary)
            .accessibilityIdentifier("web.onboarding.addKey")
        }
        .card()
    }

    private var addKey: some View {
        VStack(alignment: .leading, spacing: 12) {
            Caption(text: "Add your Duffel key")
            Text("Paste an access token from your Duffel dashboard. Test tokens start with duffel_test_, live ones with duffel_live_.")
                .foregroundStyle(Palette.ink)
            Text("No account? Sign up free at duffel.com")
                .font(Typeface.mono(13)).foregroundStyle(Palette.blue)
                .textSelection(.enabled)
            SecureField("duffel_test_…", text: $key)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .font(Typeface.mono(14))
                .padding(12)
                .background(Palette.ground.opacity(0.6), in: RoundedRectangle(cornerRadius: 10))
                .accessibilityIdentifier("web.key.field")
            status
            Button {
                Task { await web.addKey(key) }
            } label: {
                HStack {
                    if web.keyCheck == .checking { ProgressView().tint(Palette.onAccent) }
                    Text(web.keyCheck == .checking ? "Checking with Duffel…" : "Verify and save")
                }
                .font(.headline).frame(maxWidth: .infinity).padding(.vertical, 6)
            }
            .buttonStyle(.neonPrimary)
            .disabled(key.isEmpty || web.keyCheck == .checking)
            Text("Verifying makes one health check and one small test search.")
                .font(.footnote).foregroundStyle(Palette.inkSoft)
        }
        .card()
    }

    @ViewBuilder private var status: some View {
        switch web.keyCheck {
        case .malformed:
            ErrorBanner(title: "That doesn't look like a Duffel key",
                        message: "It should start with duffel_test_ or duffel_live_.")
        case .failed(let e):
            ErrorBanner(title: e.title, message: e.message).accessibilityIdentifier("web.key.error")
        default: EmptyView()
        }
    }

    private func point(_ symbol: String, _ text: String) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 10) {
            Image(systemName: symbol).foregroundStyle(Palette.blue).frame(width: 20)
            Text(text).font(.subheadline).foregroundStyle(Palette.inkSoft)
        }
    }
}

struct ErrorBanner: View {
    let title: String, message: String
    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(Palette.red)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.subheadline.weight(.semibold)).foregroundStyle(Palette.ink)
                Text(message).font(.footnote).foregroundStyle(Palette.inkSoft)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(12)
        .background(Palette.red.opacity(0.08), in: RoundedRectangle(cornerRadius: 10))
    }
}
