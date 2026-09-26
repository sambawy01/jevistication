import SwiftUI

/// The words shared by the card (also in onboarding's protection step) and the steps.
enum SafariCopy {
    static let what = "Loupe can warn you before a phishing or look-alike website in Safari. iOS doesn't let apps see Chrome or other browsers, so this protects Safari only."
    static let beforeTap = "Safari will ask to let Loupe see the sites you visit; Loupe checks them on this iPhone."
    /// What to flip on the page `SFSafariSettings.openExtensionsSettings` opens (iOS 26 labels).
    static let onThePage = "On the page that opens, turn on Allow Extension, then set All Websites to Allow."
    static let privacy = "Loupe for Safari reads only the name of each website you open (never the page, the full address or what you type) and checks it on this iPhone. Nothing leaves your phone unless you turned on online checks, and then only the website's domain."
    static let onLine = "Safari is protected. Loupe checks each website's name on this iPhone and warns you before a risky one."
}

/// Guard → Protection's Safari card: a banner with one button while it is off, a quiet "On" row
/// with a small celebration once it is on.
struct SafariProtectionCard: View {
    @ObservedObject var setup: SafariSetup
    @ObservedObject var store: ProtectionStore
    @State private var showSteps = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 12) {
                ZStack {
                    NeonIcon(name: setup.isOn ? "checkmark.shield.fill" : "safari", color: setup.isOn ? Palette.okText : Palette.cyan,
                             size: 24, active: setup.isOn)
                        .scaleEffect(setup.celebrate && !Motion.reduced(reduceMotion) ? 1.25 : 1)
                        .animation(Motion.reduced(reduceMotion) ? nil : .spring(response: 0.35, dampingFraction: 0.5), value: setup.celebrate)
                }
                .frame(width: 30)
                VStack(alignment: .leading, spacing: 2) {
                    Text("Safari protection").font(.subheadline.weight(.semibold)).foregroundStyle(Palette.ink)
                    Text(setup.statusLine).font(.caption).foregroundStyle(setup.isOn ? Palette.okText : Palette.inkSoft)
                        .fixedSize(horizontal: false, vertical: true)
                        .accessibilityIdentifier("protect.safari.status")
                }
                Spacer(minLength: 0)
                Pill(text: setup.isOn ? "On" : (setup.phase == .unknown ? "Check" : "Off"),
                     color: setup.isOn ? Palette.okText : Palette.warnText, symbol: setup.isOn ? "checkmark" : "power")
            }
            if setup.celebrate {
                HStack(spacing: 10) {
                    MascotView(state: .happy, size: 54)
                    Text("Done. " + SafariCopy.onLine).font(.footnote).foregroundStyle(Palette.ink)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .transition(.opacity)
                .accessibilityIdentifier("protect.safari.celebrate")
            }
            if !setup.isOn {
                Text(SafariCopy.what).font(.footnote).foregroundStyle(Palette.ink).fixedSize(horizontal: false, vertical: true)
                Text(SafariCopy.beforeTap).font(.footnote).foregroundStyle(Palette.inkSoft).fixedSize(horizontal: false, vertical: true)
                Button {
                    Task { await setup.turnOn() }
                } label: {
                    Label("Turn on Safari protection", systemImage: "safari").frame(maxWidth: .infinity, minHeight: 30)
                }
                .buttonStyle(.neonPrimary)
                .accessibilityIdentifier("protect.safari.turnOn")
                if setup.canOpenDirectly {
                    Text(SafariCopy.onThePage).font(.caption).foregroundStyle(Palette.inkSoft).fixedSize(horizontal: false, vertical: true)
                }
                Button("Show me the steps") { showSteps = true }
                    .font(.caption.weight(.semibold))
                    .frame(minHeight: 44)
                    .accessibilityIdentifier("protect.safari.steps")
            } else {
                Toggle(isOn: Binding(get: { store.notifySuspicious }, set: { store.notifySuspicious = $0 })) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Also notify me about suspicious sites").font(.footnote).foregroundStyle(Palette.ink)
                        Text("Dangerous sites always notify once you allow notifications; at most once an hour per site, never for a site you chose to continue to.")
                            .font(.caption2).foregroundStyle(Palette.inkSoft).fixedSize(horizontal: false, vertical: true)
                    }
                }
                .tint(Palette.blue)
                .accessibilityIdentifier("protect.notifySuspicious")
            }
            Text(SafariCopy.privacy).font(.caption2).foregroundStyle(Palette.inkSoft).fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .card(active: !setup.isOn)
        .animation(Motion.reduced(reduceMotion) ? nil : .easeOut(duration: 0.3), value: setup.phase)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("protect.safari")
        .sheet(isPresented: $showSteps, onDismiss: { setup.manual = nil }) { SafariStepsView() }
        .onChange(of: setup.manual) { _, reason in if reason != nil { showSteps = true } }
        .onChange(of: setup.celebrate) { _, on in
            guard on else { return }
            Task { try? await Task.sleep(nanoseconds: 4_000_000_000); setup.celebrate = false }
        }
    }
}

/// The manual way (iOS before 26.2, or when iOS would not open the page), with the illustration.
struct SafariStepsView: View {
    @Environment(\.dismiss) private var dismiss

    private let steps: [(String, String)] = [
        ("gear", "Open the Settings app."),
        ("square.grid.2x2", "Tap Apps, then Safari. (On iOS 17: tap Safari.)"),
        ("puzzlepiece.extension", "Tap Extensions, then Loupe."),
        ("switch.2", "Turn on Allow Extension."),
        ("globe", "Under Permissions, set All Websites to Allow. (If you leave it on Ask, Safari asks the first time you open each site.)"),
        ("arrow.uturn.backward", "Come back to Loupe: this card says On once Safari asks Loupe about a site."),
    ]

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    SafariSettingsIllustration()
                        .frame(maxWidth: .infinity)
                        .accessibilityLabel("Illustration: Settings, Apps, Safari, Extensions, Loupe, with Allow Extension on and All Websites set to Allow.")
                    ForEach(Array(steps.enumerated()), id: \.offset) { i, step in
                        HStack(alignment: .top, spacing: 12) {
                            Text("\(i + 1)").font(Typeface.mono(14, weight: .bold)).foregroundStyle(Palette.onAccent)
                                .frame(width: 26, height: 26).background(Palette.cyan, in: Circle())
                            Label(step.1, systemImage: step.0).font(.callout).foregroundStyle(Palette.ink)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    }
                    Text("Also from Safari: tap the page menu in the address bar (the \"aA\" or the puzzle-piece button), then Manage Extensions, and turn on Loupe.")
                        .font(.footnote).foregroundStyle(Palette.inkSoft)
                    Text(SafariCopy.what).font(.footnote).foregroundStyle(Palette.inkSoft)
                    Text(SafariCopy.privacy).font(.footnote).foregroundStyle(Palette.inkSoft)
                }
                .padding(20)
            }
            .neonGround()
            .navigationTitle("Turn on Loupe in Safari")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
        .accessibilityIdentifier("protect.safari.stepsSheet")
    }
}

/// A drawing of Safari's extension page as iOS shows it: the path, "Allow Extension" on and
/// "All Websites" set to Allow. Drawn, not a screenshot, so it stays sharp and follows the theme.
struct SafariSettingsIllustration: View {
    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 4) {
                ForEach(["Settings", "Apps", "Safari", "Extensions"], id: \.self) { crumb in
                    Text(crumb).font(.system(size: 11, weight: .medium)).foregroundStyle(Color(white: 0.55))
                    Image(systemName: "chevron.right").font(.system(size: 8, weight: .bold)).foregroundStyle(Color(white: 0.4))
                }
                Text("Loupe").font(.system(size: 11, weight: .bold)).foregroundStyle(.white)
            }
            .padding(.vertical, 10)
            VStack(spacing: 0) {
                row { Text("Allow Extension").foregroundStyle(.white); Spacer(); fakeToggle }
                Divider().overlay(Color(white: 0.25))
                row { Text("Allow in Private Browsing").foregroundStyle(Color(white: 0.7)); Spacer(); fakeToggleOff }
            }
            .background(Color(white: 0.11), in: RoundedRectangle(cornerRadius: 10))
            Text("PERMISSIONS FOR LOUPE").font(.system(size: 10, weight: .medium)).foregroundStyle(Color(white: 0.5))
                .frame(maxWidth: .infinity, alignment: .leading).padding(.top, 14).padding(.bottom, 5).padding(.leading, 12)
            VStack(spacing: 0) {
                row {
                    Text("All Websites").foregroundStyle(.white)
                    Spacer()
                    Text("Allow").foregroundStyle(Color(red: 0.2, green: 0.6, blue: 1))
                    Image(systemName: "chevron.up.chevron.down").font(.system(size: 10)).foregroundStyle(Color(white: 0.5))
                }
                .overlay(RoundedRectangle(cornerRadius: 8).stroke(Palette.cyan, lineWidth: 2).padding(2))
            }
            .background(Color(white: 0.11), in: RoundedRectangle(cornerRadius: 10))
        }
        .font(.system(size: 14))
        .padding(14)
        .frame(maxWidth: 330)
        .background(Color.black, in: RoundedRectangle(cornerRadius: 22, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 22, style: .continuous).stroke(Palette.border, lineWidth: 1.5))
        .neonGlow(Palette.cyan, radius: 4)
    }

    private func row<C: View>(@ViewBuilder _ content: () -> C) -> some View {
        HStack { content() }.padding(.horizontal, 12).frame(height: 40)
    }

    private var fakeToggle: some View {
        Capsule().fill(Color(red: 0.2, green: 0.78, blue: 0.35)).frame(width: 44, height: 26)
            .overlay(Circle().fill(.white).padding(2), alignment: .trailing)
            .overlay(RoundedRectangle(cornerRadius: 14).stroke(Palette.cyan, lineWidth: 2).padding(-4))
    }

    private var fakeToggleOff: some View {
        Capsule().fill(Color(white: 0.25)).frame(width: 44, height: 26)
            .overlay(Circle().fill(.white).padding(2), alignment: .leading)
    }
}

