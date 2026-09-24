import SwiftUI

extension GameMode: Identifiable {
    var id: String { rawValue }
}

/// Opens the game full screen from anywhere in the shell (Now's Play card, Me → Game, onboarding).
@MainActor
final class GameLauncher: ObservableObject {
    @Published var mode: GameMode?
    var seed: Int64 = 1

    func open(_ mode: GameMode) { self.mode = mode }
}

/// First launch: what Loupe is, and the game as the demo — the model working where you can see it.
struct OnboardingView: View {
    var onWatch: () -> Void
    var onSkip: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack {
                Image("LogoLight").resizable().scaledToFit().frame(height: 30).accessibilityLabel("Loupe")
                Spacer()
                MascotView(state: .greeting, size: 84)
            }
            Text("Judgment, on this phone.").font(Typeface.display(34)).foregroundStyle(Palette.overlayInk)
            Text("Loupe asks a small model plain questions about what is on your phone, and shows how sure it is. Nothing leaves the phone to be judged.")
                .foregroundStyle(Palette.overlayInk.opacity(0.85))
            VStack(alignment: .leading, spacing: 6) {
                Text("SEE IT WORK").font(Typeface.mono(11, weight: .medium)).tracking(0.8).foregroundStyle(Palette.cyan)
                Text("Watch Laya fly a river: every move is a question it answers live, with its probabilities on screen, against a simple rule-based pilot on the same river.")
                    .font(.subheadline).foregroundStyle(Palette.overlayInk.opacity(0.85))
            }
            Spacer()
            Button(action: onWatch) {
                Label("Watch Laya fly", systemImage: "eye").font(.headline).frame(maxWidth: .infinity).padding(.vertical, 6)
            }
            .buttonStyle(.neonPrimary)
            .accessibilityIdentifier("onboarding.watch")
            Button("Not now", action: onSkip)
                .frame(maxWidth: .infinity)
                .foregroundStyle(Palette.overlayInk.opacity(0.85))
                .accessibilityIdentifier("onboarding.skip")
        }
        .padding(24)
        .background(LinearGradient(colors: [Palette.navyTop, Palette.navyBottom], startPoint: .top, endPoint: .bottom).ignoresSafeArea())
        .interactiveDismissDisabled()
    }
}
