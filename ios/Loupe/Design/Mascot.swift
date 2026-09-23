import SwiftUI

/// The robot mascot. For child #5 this is the owner's reference render used as a static image,
/// animated with SwiftUI transforms. The rigged SceneKit/RealityKit robot (states with real
/// limb motion) is a later task; see ios/README.md.
enum MascotState: Equatable {
    case idle, greeting, thinking, found, empty, shrug
}

struct MascotView: View {
    var state: MascotState = .idle
    var size: CGFloat = 96

    @State private var bob = false
    @State private var pulse = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        Image("Mascot")
            .resizable()
            .scaledToFit()
            .frame(width: size, height: size)
            .rotationEffect(.degrees(tilt), anchor: .bottom)
            .offset(y: reduceMotion ? 0 : (bob ? -3 : 3) + hop)
            .scaleEffect(state == .found && pulse ? 1.06 : 1.0)
            .background(alignment: .bottom) {
                Ellipse()
                    .fill(Palette.cyan.opacity(0.25))
                    .frame(width: size * 0.5, height: size * 0.08)
                    .blur(radius: 4)
                    .offset(y: size * 0.04)
            }
            .animation(.easeInOut(duration: 2.2).repeatForever(autoreverses: true), value: bob)
            .animation(.spring(response: 0.35, dampingFraction: 0.5), value: state)
            .onAppear {
                guard !reduceMotion else { return }
                bob = true
                withAnimation(.easeInOut(duration: 0.6).repeatForever(autoreverses: true)) { pulse = true }
            }
            .accessibilityLabel("Loupe robot")
            .accessibilityHidden(true)
    }

    private var tilt: Double {
        switch state {
        case .thinking: return -8
        case .shrug: return 6
        case .empty: return -4
        default: return 0
        }
    }

    private var hop: CGFloat { state == .found || state == .greeting ? -6 : 0 }
}
