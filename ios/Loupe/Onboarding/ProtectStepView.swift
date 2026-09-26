import SwiftUI

/// Onboarding's protection step (2026-09-26), after the permissions: Safari protection (the same card Guard →
/// Protection shows) and the clipboard setup. Optional: Continue leaves it either way, and it is not shown again.
struct ProtectStepView: View {
    @ObservedObject var setup: SafariSetup = .shared
    @ObservedObject var store: ProtectionStore = .shared
    /// Continue: the step is done and never shown again.
    var onDone: () -> Void

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                HStack(alignment: .bottom) {
                    Image("LogoLight").resizable().scaledToFit().frame(height: 30).accessibilityLabel("Loupe")
                    Spacer()
                    MascotView(state: setup.isOn ? .happy : .greeting, size: 84)
                }
                VStack(alignment: .leading, spacing: 8) {
                    Text("PROTECTION").font(Typeface.mono(11, weight: .medium)).tracking(0.8).foregroundStyle(Palette.cyan)
                    Text("Guard what you open").font(Typeface.display(34)).foregroundStyle(Palette.ink)
                        .accessibilityAddTraits(.isHeader)
                    Text("Both are optional and both run on this iPhone. You can turn them on later in Guard → Protection.")
                        .foregroundStyle(Palette.inkSoft)
                        .fixedSize(horizontal: false, vertical: true)
                }
                SafariProtectionCard(setup: setup, store: store)
                OnboardingClipboardSlot()
            }
            .padding(24)
            .frame(maxWidth: 560)
            .frame(maxWidth: .infinity)
        }
        .accessibilityIdentifier("protect.step.screen")
        // Continue stays in view: both cards are optional, so the step can be left at any point.
        .safeAreaInset(edge: .bottom) {
            Button(action: onDone) {
                Text("Continue").frame(maxWidth: .infinity, minHeight: 44)
            }
            .buttonStyle(.neonPrimary)
            .accessibilityIdentifier("protect.step.continue")
            .padding(.horizontal, 24).padding(.top, 10).padding(.bottom, 6)
            .frame(maxWidth: 560)
            .frame(maxWidth: .infinity)
            .background(Palette.ground.opacity(0.94).ignoresSafeArea(edges: .bottom))
        }
        .neonGround()
        .task { await setup.refresh() }
    }
}

/// The clipboard checks' setup (`ClipboardSetupCard`, from `Loupe/Clipboard/`): the "Check what I copy" switch,
/// Paste from Other Apps, and the Loupe keyboard, each optional.
struct OnboardingClipboardSlot: View {
    var body: some View {
        ClipboardSetupCard()
    }
}
