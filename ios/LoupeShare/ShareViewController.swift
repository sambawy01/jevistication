import UIKit
import UniformTypeIdentifiers

/// "Send to Loupe" (epic #7 child 7): the share sheet's way into Loupe. It copies what was shared —
/// files, images, links, text — into the App Group inbox and closes. It reads nothing, judges nothing
/// and uses no network; the app reads the inbox (the `shared` source) the next time it opens.
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
        for p in providers {
            if await drop(p, into: inbox) { sent += 1 }
        }
        finish(sent == 0 ? "Nothing Loupe can read was shared." : "Sent \(sent) item\(sent == 1 ? "" : "s") to Loupe. It reads them next time you open it.", ok: sent > 0)
    }

    private func drop(_ p: NSItemProvider, into inbox: URL) async -> Bool {
        // PDFs and images: copy the bytes in.
        for type in [UTType.pdf, .image] where p.hasItemConformingToTypeIdentifier(type.identifier) {
            if let ok = await copyFile(p, type: type, into: inbox) { return ok }
        }
        // A link, or a file handed over by URL.
        if p.hasItemConformingToTypeIdentifier(UTType.url.identifier),
           let url = try? await p.loadItem(forTypeIdentifier: UTType.url.identifier) as? URL {
            if url.isFileURL { return (try? SharedInbox.drop(file: url, into: inbox)) != nil }
            return (try? SharedInbox.drop(text: "Link shared to Loupe: \(url.absoluteString)\n", title: url.host ?? "Link", into: inbox)) != nil
        }
        // Text: kept as a .txt file.
        if p.hasItemConformingToTypeIdentifier(UTType.plainText.identifier),
           let text = try? await p.loadItem(forTypeIdentifier: UTType.plainText.identifier) as? String, !text.isEmpty {
            return (try? SharedInbox.drop(text: text, title: "Shared text", into: inbox)) != nil
        }
        // Any other file.
        if p.hasItemConformingToTypeIdentifier(UTType.data.identifier) {
            return await copyFile(p, type: .data, into: inbox) ?? false
        }
        return false
    }

    /// nil when the provider has no file of [type]; otherwise whether the copy worked.
    private func copyFile(_ p: NSItemProvider, type: UTType, into inbox: URL) async -> Bool? {
        await withCheckedContinuation { cont in
            _ = p.loadFileRepresentation(forTypeIdentifier: type.identifier) { url, _ in
                // The URL is only valid inside this callback: copy now.
                guard let url else { cont.resume(returning: nil); return }
                cont.resume(returning: (try? SharedInbox.drop(file: url, into: inbox)) != nil)
            }
        }
    }

    private func finish(_ message: String, ok: Bool) {
        label.text = message
        DispatchQueue.main.asyncAfter(deadline: .now() + (ok ? 0.8 : 2.0)) { [weak self] in
            self?.extensionContext?.completeRequest(returningItems: nil)
        }
    }
}
