import SwiftUI

/// Me → Licences: the notices shipped inside the app, read from the bundle as-is.
struct LicencesView: View {
    struct Notice: Identifiable { let id: String; let title: String; let file: String }
    static let notices = [
        Notice(id: "model", title: "The Loupe Decision Model: built on Laya by Convai (Apache-2.0)", file: "Loupe-Decision-Model-base"),
        Notice(id: "ios", title: "ONNX Runtime and the tokenizer (the decision model on iOS)", file: "THIRD_PARTY_NOTICES-ios"),
        Notice(id: "rajdhani", title: "Rajdhani font (SIL OFL 1.1)", file: "OFL-Rajdhani"),
        Notice(id: "jbmono", title: "JetBrains Mono font (SIL OFL 1.1)", file: "OFL-JetBrainsMono"),
        Notice(id: "phishingdb", title: "Phishing.Database list (MIT)", file: "Phishing.Database-MIT"),
    ]

    var body: some View {
        List(Self.notices) { n in
            NavigationLink(n.title) { NoticeText(notice: n) }
        }
        .neonList()
        .navigationTitle("Licences")
        .accessibilityIdentifier("licences.list")
    }
}

private struct NoticeText: View {
    let notice: LicencesView.Notice
    @State private var text: String?

    var body: some View {
        ScrollView {
            Text(text ?? "Loading…")
                .font(Typeface.mono(11))
                .textSelection(.enabled)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(16)
        }
        .navigationTitle(notice.title)
        .navigationBarTitleDisplayMode(.inline)
        .task {
            // The iOS notices file is ~0.5 MB; read it off the main thread.
            let name = notice.file
            text = await Task.detached {
                Bundle.main.url(forResource: name, withExtension: "txt")
                    .flatMap { try? String(contentsOf: $0, encoding: .utf8) } ?? "Notice file missing from this build."
            }.value
        }
    }
}
