import SwiftUI

/// Navy hero band from prototype v8 (used on Now).
struct HeroBand<Content: View>: View {
    @ViewBuilder var content: Content
    var body: some View {
        content
            .padding(.horizontal, 20)
            .padding(.bottom, 20)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(
                LinearGradient(colors: [Palette.navyTop, Palette.navyBottom], startPoint: .top, endPoint: .bottom)
                    .ignoresSafeArea(edges: .top)
            )
            .clipShape(UnevenRoundedRectangle(bottomLeadingRadius: 24, bottomTrailingRadius: 24, style: .continuous))
    }
}

/// A button on an empty state: the way forward.
struct EmptyStateAction: Identifiable {
    let title: String
    let symbol: String
    let id: String
    let perform: () -> Void
}

/// Honest empty state: says what is missing and shows no fake data. Its actions are the way forward (audit P1-2:
/// no dead ends); the note is an optional pill (the old "Coming with phone sources" was stale and is gone).
struct HonestEmptyState: View {
    let title: String
    let message: String
    var symbol: String = "iphone.gen3"
    var note: String? = nil
    var actions: [EmptyStateAction] = []

    var body: some View {
        VStack(spacing: 14) {
            MascotView(state: .empty, size: 88)
            Text(title)
                .font(Typeface.display(26))
                .foregroundStyle(Palette.ink)
                .multilineTextAlignment(.center)
            Text(message)
                .font(.body)
                .multilineTextAlignment(.center)
                .foregroundStyle(Palette.inkSoft)
            if let note {
                Label(note, systemImage: symbol)
                    .font(Typeface.mono(12, weight: .medium))
                    .foregroundStyle(Palette.blue)
                    .padding(.horizontal, 12).padding(.vertical, 6)
                    .background(Palette.blue.opacity(0.08), in: Capsule())
            }
            if !actions.isEmpty {
                HStack(spacing: 10) {
                    ForEach(actions) { a in
                        CardAction(title: a.title, symbol: a.symbol, hue: Palette.cyan, action: a.perform)
                            .accessibilityIdentifier(a.id)
                    }
                }
            }
        }
        .padding(28)
        .frame(maxWidth: .infinity)
        .card()
        .padding(.horizontal, 16)
    }
}

struct Pill: View {
    let text: String
    var color: Color = Palette.blue
    var symbol: String? = nil
    var body: some View {
        HStack(spacing: 5) {
            if let symbol { Image(systemName: symbol).font(.system(size: 10, weight: .bold)) }
            Text(text).font(Typeface.mono(11, weight: .medium))
        }
        .foregroundStyle(color)
        .padding(.horizontal, 9).padding(.vertical, 4)
        .background(color.opacity(0.1), in: Capsule())
    }
}
