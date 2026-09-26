import SwiftUI

/// Guard → Protection → Clipboard (2026-09-26): the switch, "Check what I copied", the last clipboard
/// and keyboard checks, the keyboard's status, and Set up (the `ClipboardSetupCard` in a sheet).
struct ClipboardCard: View {
    @ObservedObject var monitor: ClipboardMonitor = .shared
    @ObservedObject var keyboard: KeyboardSetup = .shared
    @ObservedObject var store: ProtectionStore = .shared
    @State private var showSetup = false

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 12) {
                NeonIcon(name: "doc.on.clipboard", color: monitor.enabled ? Palette.cyan : Palette.inkSoft, size: 20).frame(width: 28)
                VStack(alignment: .leading, spacing: 2) {
                    Text("Clipboard").font(.subheadline.weight(.semibold)).foregroundStyle(Palette.ink)
                    Text(monitor.enabled ? "On · offers to check links you copy, on this iPhone" : "Off · Loupe does not look at the clipboard")
                        .font(.caption).foregroundStyle(Palette.inkSoft).fixedSize(horizontal: false, vertical: true)
                }
                Spacer(minLength: 0)
                Toggle("Check what I copy", isOn: $monitor.enabled).labelsHidden().tint(Palette.cyan)
                    .accessibilityIdentifier("clip.card.toggle")
            }
            if !recent.isEmpty {
                VStack(alignment: .leading, spacing: 6) {
                    Caption(text: "Last checks")
                    ForEach(recent) { v in
                        HStack(spacing: 8) {
                            NeonIcon(name: v.level.symbol, color: v.level.color, size: 14)
                            // A look-alike written in international letters shows its written (punycode) form, never
                            // a name that reads like the real site.
                            Text(v.host).font(Typeface.mono(12)).foregroundStyle(Palette.ink).lineLimit(1)
                            Spacer(minLength: 4)
                            Text("\(v.origin == .keyboard ? "Keyboard" : "Copied") · \(v.checkedAt.formatted(.relative(presentation: .named)))")
                                .font(.caption2).foregroundStyle(Palette.inkSoft).lineLimit(1)
                        }
                        .accessibilityElement(children: .combine)
                        .accessibilityIdentifier("clip.card.recent")
                    }
                }
            }
            HStack(spacing: 8) {
                Image(systemName: "keyboard").font(.caption).foregroundStyle(Palette.inkSoft)
                Text("Loupe keyboard: \(keyboard.statusLine)").font(.caption).foregroundStyle(Palette.inkSoft)
                    .fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: 0)
                KeyboardStatusPill(status: keyboard.status)
            }
            .accessibilityElement(children: .combine)
            .accessibilityIdentifier("clip.card.keyboard")
            #if DEBUG
            if let peak = keyboard.peakMB {
                Text("DEBUG · keyboard peak memory \(String(format: "%.1f", peak)) MB").font(Typeface.mono(10)).foregroundStyle(Palette.inkSoft)
            }
            #endif
            HStack(spacing: 10) {
                Button {
                    Task { await monitor.checkNow() }
                } label: {
                    Label("Check what I copied", systemImage: "magnifyingglass").font(.subheadline.weight(.semibold))
                }
                .buttonStyle(.neonPrimary)
                .accessibilityIdentifier("clip.card.checkNow")
                Button("Set up") { showSetup = true }
                    .font(.subheadline.weight(.semibold))
                    .buttonStyle(.bordered).tint(Palette.cyan)
                    .accessibilityIdentifier("clip.card.setup")
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .card()
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("clip.card")
        .task {
            keyboard.refresh()
            // Guard appeared: a copy made since Loupe last looked gets its chip.
            await monitor.scan(.guardAppeared)
        }
        .sheet(isPresented: $showSetup) {
            NavigationStack {
                ScrollView { ClipboardSetupCard().padding(16) }
                    .neonGround()
                    .navigationTitle("Clipboard checks")
                    .navigationBarTitleDisplayMode(.inline)
                    .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { showSetup = false } } }
            }
            .preferredColorScheme(.dark)
        }
    }

    private var recent: [LinkVerdict] {
        Array(store.recent.filter { $0.origin == .clipboard || $0.origin == .keyboard }.prefix(3))
    }
}
