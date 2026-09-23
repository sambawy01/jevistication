import SwiftUI

struct NowView: View {
    var body: some View {
        ScrollView {
            VStack(spacing: 20) {
                HeroBand {
                    VStack(alignment: .leading, spacing: 14) {
                        HStack(alignment: .bottom) {
                            Image("LogoLight")
                                .resizable().scaledToFit()
                                .frame(height: 34)
                                .accessibilityLabel("Loupe")
                            Spacer()
                            MascotView(state: .idle, size: 92)
                        }
                        .padding(.top, 16)
                        HStack {
                            Circle().fill(Palette.mint).frame(width: 8, height: 8)
                            Text("On this phone. Nothing leaves it.")
                                .font(.subheadline)
                                .foregroundStyle(.white.opacity(0.9))
                            Spacer()
                            Text("0 bytes out")
                                .font(Typeface.mono(12))
                                .foregroundStyle(.white.opacity(0.85))
                        }
                        Divider().overlay(.white.opacity(0.15))
                        HStack(spacing: 0) {
                            stat("Needs you")
                            stat("Matches today")
                            stat("Sources")
                        }
                    }
                }
                HonestEmptyState(
                    title: "Nothing to judge yet",
                    message: "Now fills in once Loupe can read the photos, mail and files on this phone. Until then there is nothing to count, so it shows nothing.",
                    symbol: "photo.on.rectangle")
            }
            .padding(.bottom, 24)
        }
        .background(Palette.ground.ignoresSafeArea())
        .scrollBounceBehavior(.basedOnSize)
    }

    private func stat(_ label: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label).font(.caption).foregroundStyle(.white.opacity(0.7))
            // An em dash, not a zero: there is no data yet, and zero would be a claim.
            Text("—").font(Typeface.display(26)).foregroundStyle(Palette.cyan)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}
