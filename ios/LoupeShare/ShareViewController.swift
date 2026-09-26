import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// "Send to Loupe" (epic #7 child 7): the share sheet's way into Loupe. It copies what was shared —
/// files, images, links, text — into the App Group inbox. It uses no network; the app reads the
/// inbox (the `shared` source) the next time it opens. CSVs, mail files, ZIP archives, text and links
/// go to the Inbox's waiting folder instead (child 15): the app imports them as one batch when it opens.
///
/// Browsing protection's fast path (2026-09-26): a shared web link (from Messages, WhatsApp, Mail…)
/// is checked right here by the phishing formula, on this phone (the mechanical checks and the lists
/// the app already downloaded; nothing is sent), and its verdict is shown in the sheet. The verdict
/// is kept in Check a link's recent checks, and a suspicious or dangerous one in the Spotted log.
final class ShareViewController: UIViewController {
    private let label = UILabel()

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground
        label.text = "Sending to Loupe…"
        label.font = .preferredFont(forTextStyle: .headline)
        label.numberOfLines = 0
        label.textAlignment = .center
        label.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(label)
        NSLayoutConstraint.activate([
            label.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            label.centerYAnchor.constraint(equalTo: view.centerYAnchor),
            label.leadingAnchor.constraint(greaterThanOrEqualTo: view.leadingAnchor, constant: 24),
        ])
        Task { await receive() }
    }

    private func receive() async {
        guard let inbox = SharedInbox.groupFolder() else {
            finish("Loupe could not reach its inbox (this build has no App Group). Nothing was sent.", ok: false)
            return
        }
        let providers = (extensionContext?.inputItems as? [NSExtensionItem] ?? []).flatMap { $0.attachments ?? [] }
        var sent = 0
        var links: [String] = []
        for p in providers {
            let (ok, link) = await drop(p, into: inbox)
            if ok { sent += 1 }
            if let link, links.count < 3, !links.contains(link) { links.append(link) }
        }
        let verdicts = await Task.detached(priority: .userInitiated) { Self.check(links) }.value
        if !verdicts.isEmpty {
            show(verdicts, sent: sent)
            return
        }
        finish(sent == 0 ? "Nothing Loupe can read was shared." : "Sent \(sent) item\(sent == 1 ? "" : "s") to Loupe. It reads them next time you open it.", ok: sent > 0)
    }

    /// The fast path: on-device verdicts for the shared links, kept in recent checks and (when
    /// flagged) the Spotted log.
    nonisolated private static func check(_ links: [String]) -> [LinkVerdict] {
        let recent = RecentChecksStore()
        let log = SpottedLog()
        var out: [LinkVerdict] = []
        for link in links {
            guard case .success(let input) = LinkInput.normalize(link) else { continue }
            let v = DeviceLinkCheck.verdict(input, origin: .shared)
            recent.add(v)
            if log.record(v) != nil { ProtectionGroup.post(ProtectionGroup.spottedChanged) }
            out.append(v)
        }
        return out
    }

    /// Whether the item was stored, and the web link it carried (a URL, or the first link in text).
    private func drop(_ p: NSItemProvider, into inbox: URL) async -> (Bool, String?) {
        // PDFs and images: copy the bytes in.
        for type in [UTType.pdf, .image] where p.hasItemConformingToTypeIdentifier(type.identifier) {
            if let ok = await copyFile(p, type: type, into: inbox) { return (ok, nil) }
        }
        // A link, or a file handed over by URL.
        if p.hasItemConformingToTypeIdentifier(UTType.url.identifier),
           let url = try? await p.loadItem(forTypeIdentifier: UTType.url.identifier) as? URL {
            if url.isFileURL { return ((try? SharedInbox.drop(file: url, into: Self.target(for: url, inbox))) != nil, nil) }
            let ok = (try? SharedInbox.drop(text: "Link shared to Loupe: \(url.absoluteString)\n", title: url.host ?? "Link",
                                            into: SharedInbox.importFolder(in: inbox))) != nil
            let web = ["http", "https"].contains(url.scheme?.lowercased() ?? "")
            return (ok, web ? url.absoluteString : nil)
        }
        // Text: kept as a .txt file.
        if p.hasItemConformingToTypeIdentifier(UTType.plainText.identifier),
           let text = try? await p.loadItem(forTypeIdentifier: UTType.plainText.identifier) as? String, !text.isEmpty {
            let ok = (try? SharedInbox.drop(text: text, title: "Shared text", into: SharedInbox.importFolder(in: inbox))) != nil
            return (ok, Self.firstLink(in: text))
        }
        // Any other file.
        if p.hasItemConformingToTypeIdentifier(UTType.data.identifier) {
            return (await copyFile(p, type: .data, into: inbox) ?? false, nil)
        }
        return (false, nil)
    }

    nonisolated private static func firstLink(in text: String) -> String? {
        guard text.count <= 20_000, case .success(let n) = LinkInput.normalize(text), !n.addedScheme || text.contains("www.") else { return nil }
        return n.url
    }

    /// nil when the provider has no file of [type]; otherwise whether the copy worked.
    private func copyFile(_ p: NSItemProvider, type: UTType, into inbox: URL) async -> Bool? {
        await withCheckedContinuation { cont in
            _ = p.loadFileRepresentation(forTypeIdentifier: type.identifier) { url, _ in
                // The URL is only valid inside this callback: copy now.
                guard let url else { cont.resume(returning: nil); return }
                cont.resume(returning: (try? SharedInbox.drop(file: url, into: Self.target(for: url, inbox))) != nil)
            }
        }
    }

    /// The Inbox's waiting folder for CSVs, mail files and archives; the `shared` folder otherwise.
    nonisolated private static func target(for file: URL, _ inbox: URL) -> URL {
        SharedInbox.goesToInbox(file) ? SharedInbox.importFolder(in: inbox) : inbox
    }

    private func show(_ verdicts: [LinkVerdict], sent: Int) {
        label.isHidden = true
        let host = UIHostingController(rootView: ShareVerdictView(verdicts: verdicts, sent: sent) { [weak self] in
            self?.extensionContext?.completeRequest(returningItems: nil)
        })
        addChild(host)
        host.view.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(host.view)
        NSLayoutConstraint.activate([
            host.view.topAnchor.constraint(equalTo: view.topAnchor),
            host.view.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            host.view.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            host.view.trailingAnchor.constraint(equalTo: view.trailingAnchor),
        ])
        host.didMove(toParent: self)
    }

    private func finish(_ message: String, ok: Bool) {
        label.text = message
        DispatchQueue.main.asyncAfter(deadline: .now() + (ok ? 0.8 : 2.0)) { [weak self] in
            self?.extensionContext?.completeRequest(returningItems: nil)
        }
    }
}

/// The verdict in the share sheet: level, website name, the reasons, and what was (not) sent.
struct ShareVerdictView: View {
    let verdicts: [LinkVerdict]
    let sent: Int
    let done: () -> Void

    private func color(_ l: ProtectionLevel) -> Color {
        switch l {
        case .dangerous: return Color(red: 0.94, green: 0.27, blue: 0.27)
        case .suspicious: return Color(red: 0.96, green: 0.62, blue: 0.04)
        case .safe: return Color(red: 0.06, green: 0.73, blue: 0.51)
        }
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    ForEach(verdicts) { v in
                        VStack(alignment: .leading, spacing: 8) {
                            Label(v.title, systemImage: v.level == .safe ? "checkmark.shield" : "exclamationmark.shield.fill")
                                .font(.title3.weight(.bold))
                                .foregroundStyle(color(v.level))
                                .accessibilityIdentifier("share.verdict.\(v.level.rawValue)")
                            Text(v.siteLine).font(.system(.subheadline, design: .monospaced)).textSelection(.enabled)
                            if !v.reasons.isEmpty {
                                Text(v.reasonsTitle).font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                                ForEach(Array(v.reasons.prefix(4).enumerated()), id: \.offset) { _, r in
                                    Text("• " + r.text).font(.callout)
                                }
                            }
                            Text("Checked on this iPhone. " + v.privacyLine).font(.caption).foregroundStyle(.secondary)
                        }
                        .padding(14)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(color(v.level).opacity(0.12), in: RoundedRectangle(cornerRadius: 14))
                    }
                    Text(sent > 0 ? "Also sent to Loupe: it keeps this check in Guard → Protection." : "Loupe keeps this check in Guard → Protection.")
                        .font(.footnote).foregroundStyle(.secondary)
                }
                .padding(18)
            }
            .navigationTitle("Loupe checked this link")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Done", action: done).accessibilityIdentifier("share.done") }
            }
        }
    }
}
