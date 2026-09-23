import SwiftUI

/// "Get the on-device model": what it is, how big, that it is one download and then offline, and a
/// Download button that does nothing until the user taps it (PRODUCT.md §4a).
struct LayaModelView: View {
    @ObservedObject private var model = LayaModel.shared
    @EnvironmentObject private var web: WebModel

    private var sizeText: String {
        ByteCountFormatter.string(fromByteCount: LayaModelSource.totalBytes, countStyle: .file)
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                HStack(alignment: .top) {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("Laya, on this phone").font(Typeface.display(28)).foregroundStyle(Palette.ink)
                        Text("The model that ranks offers against your priorities. It runs here, never on a server.")
                            .foregroundStyle(Palette.inkSoft)
                    }
                    Spacer()
                    MascotView(state: mascot, size: 64)
                }
                VStack(alignment: .leading, spacing: 10) {
                    fact("arrow.down.circle", "One download, about \(sizeText)", "The model (384 MB, INT8) and its tokenizer (34 MB). Use Wi-Fi.")
                    fact("wifi.slash", "Then it works offline", "Everything after the download runs on this phone. Your priorities and offers are never sent anywhere for ranking.")
                    fact("checkmark.shield", "Checked before use", "Each file must match its SHA-256 fingerprint, or it is deleted and not used.")
                    fact("hand.raised", "Only when you ask", "Nothing downloads until you tap Download. You can remove it any time.")
                }
                .card()
                statusCard
            }
            .padding(16)
        }
        .background(Palette.ground.ignoresSafeArea())
        .navigationTitle("On-device model")
        .navigationBarTitleDisplayMode(.inline)
        .accessibilityIdentifier("model.screen")
        .onAppear { model.refresh() }
    }

    private var mascot: MascotState {
        switch model.status {
        case .ready: return .found
        case .downloading, .checking: return .scanning
        case .failed: return .shrug
        case .notInstalled: return .greeting
        }
    }

    @ViewBuilder private var statusCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            switch model.status {
            case .ready:
                Label("Installed and ready", systemImage: "checkmark.circle.fill").foregroundStyle(Palette.mint)
                    .accessibilityIdentifier("model.ready")
                if web.response != nil, web.layaRanked == nil {
                    Button("Rank the current results with Laya") { web.rerank() }
                }
                Button("Remove the model", role: .destructive) { model.remove() }
            case .checking:
                ProgressView("Checking the files…")
            case .downloading(let p):
                ProgressView(value: p) { Text("Downloading… \(Int(p * 100))%") }.tint(Palette.blue)
                Button("Cancel", role: .cancel) { model.cancelDownload() }
            case .failed(let m):
                Label(m, systemImage: "exclamationmark.triangle.fill").foregroundStyle(Palette.red).font(.footnote)
                downloadButton
            case .notInstalled:
                downloadButton
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .card()
    }

    @ViewBuilder private var downloadButton: some View {
        if LayaModelSource.configured != nil {
            Button { model.startDownload() } label: {
                Label("Download (\(sizeText))", systemImage: "arrow.down.circle.fill").frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .accessibilityIdentifier("model.download")
        } else {
            VStack(alignment: .leading, spacing: 6) {
                Label("Model URL not configured", systemImage: "link.badge.plus").font(.headline).foregroundStyle(Palette.amber)
                Text("This build has no download host for the model. For development, side-load it with ios-native/sideload-models.sh, then come back here.")
                    .font(.footnote).foregroundStyle(Palette.inkSoft)
                Text("ios-native/sideload-models.sh dev.loupe.app booted")
                    .font(Typeface.mono(11)).textSelection(.enabled)
            }
            .accessibilityIdentifier("model.notConfigured")
            Button("Check again") { model.refresh() }.font(.footnote.weight(.semibold))
        }
    }

    private func fact(_ symbol: String, _ title: String, _ text: String) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: symbol).foregroundStyle(Palette.blue).frame(width: 22)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.subheadline.weight(.semibold)).foregroundStyle(Palette.ink)
                Text(text).font(.footnote).foregroundStyle(Palette.inkSoft)
            }
        }
    }
}
