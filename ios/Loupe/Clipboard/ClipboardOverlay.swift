import Combine
import SwiftUI
import UIKit

/// Shows the clipboard chip and its banner over whatever screen is open, without touching the app's
/// view tree: a small window above the app's own, which takes touches only on the chip or banner
/// (everything else falls through to the app). It exists only while there is something to show.
@MainActor
final class ClipboardOverlay {
    static let shared = ClipboardOverlay()

    private var window: PassthroughWindow?
    private var cancellable: AnyCancellable?
    private weak var monitor: ClipboardMonitor?
    /// The verdict sheet is up (the banner's Details): the banner stays.
    private(set) var presentingDetails = false

    func attach(_ monitor: ClipboardMonitor) {
        self.monitor = monitor
        cancellable = monitor.$phase.receive(on: RunLoop.main).sink { [weak self] phase in
            phase.isVisible ? self?.show() : self?.hide()
        }
    }

    private func activeScene() -> UIWindowScene? {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        return scenes.first { $0.activationState == .foregroundActive } ?? scenes.first
    }

    private func show() {
        guard window == nil, let monitor, let scene = activeScene() else { return }
        let w = PassthroughWindow(windowScene: scene)
        w.windowLevel = .normal + 10
        w.backgroundColor = .clear
        let host = UIHostingController(rootView: ClipboardOverlayView(monitor: monitor, window: w) { [weak self] v in
            self?.presentDetails(v)
        }.preferredColorScheme(.dark))
        host.view.backgroundColor = .clear
        w.rootViewController = host
        w.isHidden = false          // visible, never key: the app's text fields keep the keyboard
        window = w
    }

    private func hide() {
        guard !presentingDetails else { return }
        window?.isHidden = true
        window = nil
    }

    private func presentDetails(_ v: LinkVerdict) {
        guard let root = window?.rootViewController, root.presentedViewController == nil else { return }
        presentingDetails = true
        let sheet = UIHostingController(rootView: ClipboardVerdictSheet(verdict: v) { [weak self] in
            self?.window?.rootViewController?.dismiss(animated: true)
            self?.presentingDetails = false
        }.preferredColorScheme(.dark))
        sheet.presentationController?.delegate = SheetDismissWatcher.shared
        SheetDismissWatcher.shared.onDismiss = { [weak self] in self?.presentingDetails = false }
        root.present(sheet, animated: true)
    }
}

/// Clears `presentingDetails` when the verdict sheet is swiped away.
private final class SheetDismissWatcher: NSObject, UIAdaptivePresentationControllerDelegate {
    static let shared = SheetDismissWatcher()
    var onDismiss: (() -> Void)?
    func presentationControllerDidDismiss(_ presentationController: UIPresentationController) { onDismiss?() }
}

/// A window that takes touches only inside [interactive] (the chip or the banner, reported by SwiftUI),
/// or anywhere while it presents a sheet.
final class PassthroughWindow: UIWindow {
    var interactive: CGRect = .zero

    override func hitTest(_ point: CGPoint, with event: UIEvent?) -> UIView? {
        if rootViewController?.presentedViewController != nil { return super.hitTest(point, with: event) }
        guard interactive.insetBy(dx: -4, dy: -4).contains(point) else { return nil }
        return super.hitTest(point, with: event)
    }
}

/// The chip, then the banner, above the tab bar.
struct ClipboardOverlayView: View {
    @ObservedObject var monitor: ClipboardMonitor
    weak var window: PassthroughWindow?
    let details: (LinkVerdict) -> Void
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        VStack {
            Spacer()
            content
                .background(GeometryReader { g in
                    Color.clear
                        .onAppear { window?.interactive = g.frame(in: .global) }
                        .onChange(of: g.frame(in: .global)) { _, f in window?.interactive = f }
                })
                .padding(.horizontal, 12)
                .padding(.bottom, 60)
        }
        .animation(Motion.reduced(reduceMotion) ? nil : .spring(response: 0.35, dampingFraction: 0.85), value: monitor.phase)
    }

    @ViewBuilder private var content: some View {
        switch monitor.phase {
        case .idle:
            EmptyView()
        case .offer(let kind):
            ClipboardChip(question: ClipboardOffer.question(kind), checking: false,
                          check: { Task { await monitor.check() } }, dismiss: { monitor.dismiss() })
                .transition(.move(edge: .bottom).combined(with: .opacity))
        case .checking(let kind):
            ClipboardChip(question: ClipboardOffer.question(kind), checking: true, check: {}, dismiss: { monitor.dismiss() })
        case .result(let v, let kind):
            ClipboardBanner(verdict: v, kind: kind, pasteHint: monitor.showPasteHint,
                            details: { details(v) }, dismiss: { monitor.dismiss() })
                .transition(.move(edge: .bottom).combined(with: .opacity))
        case .problem(let text):
            ClipboardProblemBanner(text: text, dismiss: { monitor.dismiss() })
        }
    }
}

/// "Check the link you copied?" · Check · ×
struct ClipboardChip: View {
    let question: String
    let checking: Bool
    let check: () -> Void
    let dismiss: () -> Void

    var body: some View {
        HStack(spacing: 10) {
            NeonIcon(name: "doc.on.clipboard", color: Palette.cyan, size: 17, active: true)
            Text(question)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(Palette.ink)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityIdentifier("clip.chip.text")
            Spacer(minLength: 4)
            Button(action: check) {
                if checking { ProgressView().tint(Palette.onAccent).frame(width: 44) } else { Text("Check") }
            }
            .buttonStyle(.neonPrimary)
            .disabled(checking)
            .accessibilityIdentifier("clip.chip.check")
            Button(action: dismiss) {
                Image(systemName: "xmark").font(.system(size: 13, weight: .bold)).foregroundStyle(Palette.inkSoft)
                    .frame(width: 32, height: 44)
            }
            .accessibilityLabel("Not now")
            .accessibilityIdentifier("clip.chip.dismiss")
        }
        .padding(.leading, 14).padding(.trailing, 6).padding(.vertical, 6)
        .background(
            LinearGradient(colors: [Palette.cardHigh, Palette.card], startPoint: .top, endPoint: .bottom),
            in: Capsule())
        .overlay(Capsule().stroke(Palette.borderActive, lineWidth: 1))
        .shadow(color: .black.opacity(0.45), radius: 16, y: 6)
        .neonGlow(Palette.cyan, radius: 3)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("clip.chip")
    }
}

/// The verdict for what was copied: level, the sentence, the website, Details and Dismiss; the one-time
/// "Paste from Other Apps: Allow" hint under the first one.
struct ClipboardBanner: View {
    let verdict: LinkVerdict
    let kind: ClipboardKind
    let pasteHint: Bool
    let details: () -> Void
    let dismiss: () -> Void

    var body: some View {
        let v = verdict
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .top, spacing: 10) {
                NeonIcon(name: v.level.symbol, color: v.level.color, size: 24, active: v.level.flagged)
                VStack(alignment: .leading, spacing: 3) {
                    Text(v.title).font(Typeface.display(20)).foregroundStyle(v.level.color)
                        .accessibilityIdentifier("clip.banner.title")
                    Text(ClipboardWords.sentence(v, kind: kind)).font(.subheadline).foregroundStyle(Palette.ink)
                        .fixedSize(horizontal: false, vertical: true)
                        .accessibilityIdentifier("clip.banner.sentence")
                    Text(v.siteLine).font(Typeface.mono(12)).foregroundStyle(Palette.inkSoft).lineLimit(2)
                }
                Spacer(minLength: 0)
                Button(action: dismiss) {
                    Image(systemName: "xmark").font(.system(size: 13, weight: .bold)).foregroundStyle(Palette.inkSoft)
                        .frame(width: 32, height: 32)
                }
                .accessibilityLabel("Dismiss")
                .accessibilityIdentifier("clip.banner.dismiss")
            }
            HStack(spacing: 10) {
                Button("Details", action: details)
                    .font(.subheadline.weight(.semibold))
                    .buttonStyle(.bordered).tint(Palette.cyan)
                    .accessibilityIdentifier("clip.banner.details")
                Text(v.level.flagged ? "Added to Spotted. Don't sign in or pay there." : v.privacyLine)
                    .font(.caption).foregroundStyle(Palette.inkSoft)
                    .fixedSize(horizontal: false, vertical: true)
            }
            if pasteHint { PasteAllowHint() }
        }
        .padding(14)
        .background(
            LinearGradient(colors: [Palette.cardHigh, Palette.card], startPoint: .top, endPoint: .bottom),
            in: RoundedRectangle(cornerRadius: Effects.radius, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: Effects.radius, style: .continuous).stroke(v.level.color.opacity(0.6), lineWidth: 1.2))
        .shadow(color: .black.opacity(0.5), radius: 18, y: 6)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("clip.banner")
        .accessibilityValue(v.level.rawValue)
    }
}

struct ClipboardProblemBanner: View {
    let text: String
    let dismiss: () -> Void

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            NeonIcon(name: "doc.on.clipboard", color: Palette.inkSoft, size: 18)
            Text(text).font(.subheadline).foregroundStyle(Palette.ink).fixedSize(horizontal: false, vertical: true)
                .accessibilityIdentifier("clip.problem.text")
            Spacer(minLength: 0)
            Button(action: dismiss) {
                Image(systemName: "xmark").font(.system(size: 13, weight: .bold)).foregroundStyle(Palette.inkSoft).frame(width: 32, height: 32)
            }
            .accessibilityLabel("Dismiss")
        }
        .padding(14)
        .background(Palette.card, in: RoundedRectangle(cornerRadius: Effects.radius, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: Effects.radius, style: .continuous).stroke(Palette.border))
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("clip.problem")
    }
}

/// "Paste from Other Apps: Allow" — offered once under the first result, and always in the setup card.
struct PasteAllowHint: View {
    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            Image(systemName: "lightbulb").font(.caption).foregroundStyle(Palette.warnText).padding(.top, 2)
            VStack(alignment: .leading, spacing: 6) {
                Text("Skip iOS's paste question: Settings → Loupe → Paste from Other Apps → Allow.")
                    .font(.caption).foregroundStyle(Palette.ink).fixedSize(horizontal: false, vertical: true)
                Button("Open Loupe's settings") { ClipboardSettingsLink.open() }
                    .font(.caption.weight(.semibold))
                    .accessibilityIdentifier("clip.hint.open")
            }
        }
        .padding(10)
        .background(Palette.warnSoft, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("clip.hint")
    }
}

/// Loupe's own page in Settings (iOS has no public link to a deeper page): Paste from Other Apps, and
/// Keyboards → Loupe → Allow Full Access, are both on it.
enum ClipboardSettingsLink {
    @MainActor static func open() {
        guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
        UIApplication.shared.open(url)
    }
}

/// The banner's Details: the full verdict card, as Check a link shows it.
struct ClipboardVerdictSheet: View {
    let verdict: LinkVerdict
    let done: () -> Void

    var body: some View {
        NavigationStack {
            ScrollView {
                VerdictCard(verdict: verdict).padding(16)
                Text("Checked from the clipboard. Loupe kept the address without the part after \"?\" in recent checks" +
                     (verdict.level.flagged ? ", and the website name in Spotted." : "."))
                    .font(.caption).foregroundStyle(Palette.inkSoft).padding(.horizontal, 16)
            }
            .neonGround()
            .navigationTitle("What you copied")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done", action: done).accessibilityIdentifier("clip.sheet.done") } }
        }
    }
}
