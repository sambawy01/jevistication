import SwiftUI

/// Clipboard checks' setup in one card (2026-09-26), for onboarding and Guard → Protection → Clipboard
/// → Set up. Self-contained: it reads `ClipboardMonitor.shared` and `KeyboardSetup.shared` and opens
/// Loupe's page in Settings itself, so onboarding only has to place it:
///
///     ClipboardSetupCard()                  // a card, as in Guard
///     ClipboardSetupCard(style: .plain)     // no card background, for a sheet that already has one
///
/// Three things, each optional: the "Check what I copy" switch (on by default), Paste from Other Apps →
/// Allow (no more paste question), and the Loupe keyboard with Allow Full Access; then where
/// "Check what I copied" works from (Siri, Shortcuts, the Action Button, the widget).
struct ClipboardSetupCard: View {
    enum Style { case card, plain }

    var style: Style = .card
    @ObservedObject var monitor: ClipboardMonitor = .shared
    @ObservedObject var keyboard: KeyboardSetup = .shared

    var body: some View {
        let content = VStack(alignment: .leading, spacing: 14) {
            header
            Divider().overlay(Palette.hairline)
            pasteStep
            Divider().overlay(Palette.hairline)
            keyboardStep
            Divider().overlay(Palette.hairline)
            anywhereStep
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("clip.setup")
        .onAppear { keyboard.refresh() }

        switch style {
        case .card: content.card()
        case .plain: content
        }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("CLIPBOARD CHECKS").font(Typeface.mono(11, weight: .medium)).tracking(0.8).foregroundStyle(Palette.cyan)
            Toggle(isOn: $monitor.enabled) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Check what I copy").font(.subheadline.weight(.semibold)).foregroundStyle(Palette.ink)
                    Text("When you copy a link and open Loupe, it offers to check it. iOS tells Loupe only that a link is there; Loupe reads it when you tap Check.")
                        .font(.caption).foregroundStyle(Palette.inkSoft).fixedSize(horizontal: false, vertical: true)
                }
            }
            .tint(Palette.cyan)
            .accessibilityIdentifier("clip.setup.toggle")
        }
    }

    private var pasteStep: some View {
        VStack(alignment: .leading, spacing: 8) {
            Label("Skip the paste question", systemImage: "hand.tap").font(.subheadline.weight(.semibold)).foregroundStyle(Palette.ink)
            Text("iOS asks \"Allow Paste?\" each time Loupe reads what you copied. To stop it: Settings → Loupe → Paste from Other Apps → Allow. (The row appears after Loupe first asks.)")
                .font(.caption).foregroundStyle(Palette.inkSoft).fixedSize(horizontal: false, vertical: true)
            Button { ClipboardSettingsLink.open() } label: {
                Label("Open Loupe's settings", systemImage: "gear").font(.subheadline.weight(.semibold))
            }
            .buttonStyle(.bordered).tint(Palette.cyan)
            .accessibilityIdentifier("clip.setup.paste")
        }
    }

    private var keyboardStep: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Label("The Loupe keyboard", systemImage: "keyboard").font(.subheadline.weight(.semibold)).foregroundStyle(Palette.ink)
                Spacer(minLength: 4)
                KeyboardStatusPill(status: keyboard.status)
            }
            Text("English and Arabic, with a Loupe strip on top that checks the link you copied or just pasted: \"Pasted link: no warning signs\".")
                .font(.caption).foregroundStyle(Palette.inkSoft).fixedSize(horizontal: false, vertical: true)
            if keyboard.status != .ready {
                VStack(alignment: .leading, spacing: 6) {
                    ForEach(Array(KeyboardSteps.steps.enumerated()), id: \.offset) { i, step in
                        HStack(alignment: .top, spacing: 8) {
                            Text("\(i + 1)").font(Typeface.mono(11, weight: .bold)).foregroundStyle(Palette.onAccent)
                                .frame(width: 20, height: 20).background(Palette.cyan, in: Circle())
                            Text(step.1).font(.caption).foregroundStyle(Palette.ink).fixedSize(horizontal: false, vertical: true)
                        }
                        .accessibilityElement(children: .combine)
                    }
                }
                Button { ClipboardSettingsLink.open() } label: {
                    Label(keyboard.status == .notAdded ? "Add the Loupe keyboard" : "Turn on Full Access", systemImage: "keyboard.badge.ellipsis")
                        .font(.subheadline.weight(.semibold))
                }
                .buttonStyle(.neonPrimary)
                .accessibilityIdentifier("clip.setup.keyboard")
            }
            Label(KeyboardSteps.privacy, systemImage: "lock.shield")
                .font(.caption).foregroundStyle(Palette.inkSoft).fixedSize(horizontal: false, vertical: true)
                .accessibilityIdentifier("clip.setup.keyboardPrivacy")
        }
    }

    private var anywhereStep: some View {
        VStack(alignment: .leading, spacing: 6) {
            Label("From anywhere", systemImage: "mic").font(.subheadline.weight(.semibold)).foregroundStyle(Palette.ink)
            Text("Say \"Check what I copied with Loupe\", use it in Shortcuts or on the Action Button, or add the Check copied widget to your Home Screen.")
                .font(.caption).foregroundStyle(Palette.inkSoft).fixedSize(horizontal: false, vertical: true)
        }
    }
}

struct KeyboardStatusPill: View {
    let status: KeyboardSetup.Status

    var body: some View {
        switch status {
        case .notAdded: Pill(text: "Not added", color: Palette.inkSoft, symbol: "plus")
        case .needsFullAccess: Pill(text: "Needs Full Access", color: Palette.warnText, symbol: "lock")
        case .ready: Pill(text: "On", color: Palette.okText, symbol: "checkmark")
        }
    }
}
