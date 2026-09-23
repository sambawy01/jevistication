import SwiftUI

/// "Get the on-device model": what it is, how big, that it is one download and then offline, and a
/// Download button that shows the consent screen first (PRODUCT.md §4a). Nothing is requested
/// until the user agrees there, and never while the manifest's host is empty.
struct LayaModelView: View {
    @ObservedObject private var model = LayaModel.shared
    @EnvironmentObject private var web: WebModel
    @State private var showConsent = false
    @State private var confirmRemove = false

    private var sizeText: String { DeliveryError.bytes(model.downloadBytes) }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                HStack(alignment: .top) {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("Laya, on this phone").font(Typeface.display(28)).foregroundStyle(Palette.ink)
                        Text("The model behind your judgments, the watchers and flight ranking. It runs here, never on a server.")
                            .foregroundStyle(Palette.inkSoft)
                    }
                    Spacer()
                    MascotView(state: mascot, size: 64)
                }
                VStack(alignment: .leading, spacing: 10) {
                    fact("arrow.down.circle", "One download, about \(sizeText)", "The model and its tokenizer. Use Wi-Fi unless you allow mobile data.")
                    fact("wifi.slash", "Then it works offline", "Everything after the download runs on this phone.")
                    fact("checkmark.shield", "Checked before use", "Each file must match its SHA-256 fingerprint, or it is deleted and not used.")
                    fact("hand.raised", "Only when you agree", "Nothing downloads until you agree on the next screen. You can remove it any time.")
                }
                .card()
                statusCard
                if let m = model.manifest, m.variants.count > 1 { variantCard(m) }
                if let problem = model.manifestProblem {
                    Label(problem, systemImage: "exclamationmark.triangle.fill").font(.footnote).foregroundStyle(Palette.red).card()
                }
            }
            .padding(16)
        }
        .background(Palette.ground.ignoresSafeArea())
        .navigationTitle("On-device model")
        .navigationBarTitleDisplayMode(.inline)
        .accessibilityIdentifier("model.screen")
        .onAppear { model.refresh() }
        .sheet(isPresented: $showConsent) {
            ModelConsentView(bytes: model.downloadBytes, variantTitle: model.variant?.title ?? "",
                             hostConfigured: model.hostConfigured, allowsCellular: $model.allowsCellular) {
                model.grantConsent()
                model.startDownload()
                showConsent = false
            }
        }
        .confirmationDialog("Remove the model from this iPhone?", isPresented: $confirmRemove, titleVisibility: .visible) {
            Button("Remove (\(sizeText))", role: .destructive) { model.remove() }
        } message: {
            Text("Judgments, the watchers' model half and Laya ranking stop until you download it again. Your data stays.")
        }
    }

    private var mascot: MascotState {
        switch model.status {
        case .ready: return .found
        case .downloading, .checking, .verifying: return .scanning
        case .failed: return .shrug
        case .notInstalled, .paused: return .greeting
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
                Button("Remove the model", role: .destructive) { confirmRemove = true }
                    .accessibilityIdentifier("model.remove")
            case .checking:
                ProgressView("Checking the files…")
            case .verifying(let f):
                ProgressView("Checking \(f)…")
            case .downloading(let p):
                ProgressView(value: p) { Text("Downloading… \(Int(p * 100))%") }.tint(Palette.blue)
                    .accessibilityIdentifier("model.progress")
                HStack {
                    Button("Pause") { model.pauseDownload() }.accessibilityIdentifier("model.pause")
                    Spacer()
                    Button("Cancel", role: .cancel) { model.cancelDownload() }
                }
            case .paused(let p):
                ProgressView(value: p) { Text("Paused at \(Int(p * 100))%") }.tint(Palette.inkSoft)
                HStack {
                    Button("Resume") { model.startDownload() }.buttonStyle(.borderedProminent)
                        .accessibilityIdentifier("model.resume")
                        .disabled(!model.hostConfigured)
                    Spacer()
                    Button("Cancel", role: .cancel) { model.cancelDownload() }
                }
            case .failed(let m):
                Label(m, systemImage: "exclamationmark.triangle.fill").foregroundStyle(Palette.red).font(.footnote)
                    .accessibilityIdentifier("model.error")
                downloadButton
            case .notInstalled:
                downloadButton
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .card()
    }

    @ViewBuilder private var downloadButton: some View {
        if model.hostConfigured {
            Button { if model.hasConsent { model.startDownload() } else { showConsent = true } } label: {
                Label("Download (\(sizeText))", systemImage: "arrow.down.circle.fill").frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .accessibilityIdentifier("model.download")
            Toggle("Allow mobile data", isOn: $model.allowsCellular).font(.footnote)
                .accessibilityIdentifier("model.cellular")
        } else {
            VStack(alignment: .leading, spacing: 6) {
                Label("Model host not configured", systemImage: "link.badge.plus").font(.headline).foregroundStyle(Palette.amber)
                Text("This build has no download host for the model, so it cannot download it and makes no request. For development, side-load it with ios-native/sideload-models.sh, then come back here.")
                    .font(.footnote).foregroundStyle(Palette.inkSoft)
                Text("ios-native/sideload-models.sh dev.loupe.app booted")
                    .font(Typeface.mono(11)).textSelection(.enabled)
            }
            .accessibilityElement(children: .combine)
            .accessibilityIdentifier("model.notConfigured")
            HStack {
                Button("What the download involves") { showConsent = true }
                    .font(.footnote.weight(.semibold))
                    .accessibilityIdentifier("model.whatItInvolves")
                Spacer()
                Button("Check again") { model.refresh() }.font(.footnote.weight(.semibold))
            }
        }
    }

    private func variantCard(_ m: ModelManifest) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Which model").font(.subheadline.weight(.semibold)).foregroundStyle(Palette.ink)
            ForEach(m.variants) { v in
                Button { model.select(variant: v.id) } label: {
                    HStack(alignment: .top, spacing: 10) {
                        Image(systemName: v.id == model.variantID ? "largecircle.fill.circle" : "circle")
                            .foregroundStyle(Palette.blue)
                        VStack(alignment: .leading, spacing: 2) {
                            Text("\(v.title) · \(DeliveryError.bytes(v.totalBytes))").font(.footnote.weight(.semibold)).foregroundStyle(Palette.ink)
                            Text(v.detail).font(.caption).foregroundStyle(Palette.inkSoft)
                        }
                    }
                }
                .buttonStyle(.plain)
                .disabled(isBusy)
                .accessibilityIdentifier("model.variant.\(v.id)")
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .card()
    }

    private var isBusy: Bool {
        switch model.status {
        case .downloading, .verifying, .checking: return true
        default: return false
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

/// The consent screen: size, one time, what goes online and what never does. Agreeing is the only
/// way a download starts; while the host is empty the agree button is disabled and says why.
struct ModelConsentView: View {
    let bytes: Int64
    let variantTitle: String
    let hostConfigured: Bool
    @Binding var allowsCellular: Bool
    let onAgree: () -> Void
    @Environment(\.dismiss) private var dismiss

    static func lines(size: String) -> [String] {
        [
            "Loupe will download Laya's model files once: \(size).",
            "This is a one-time download. After it, Laya runs entirely on this iPhone.",
            "Only the model files are fetched. Nothing about you, your files, mail, photos or judgments is sent — nothing else goes online.",
            "Each file is checked against its SHA-256 fingerprint before it is used; a file that does not match is deleted.",
            "The files stay on this iPhone, are not backed up to iCloud, and can be removed from this screen at any time.",
        ]
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    Text("Download the on-device model?").font(Typeface.display(26)).foregroundStyle(Palette.ink)
                    if !variantTitle.isEmpty {
                        Text(variantTitle).font(.footnote.weight(.semibold)).foregroundStyle(Palette.inkSoft)
                    }
                    ForEach(Array(Self.lines(size: DeliveryError.bytes(bytes)).enumerated()), id: \.offset) { i, line in
                        HStack(alignment: .top, spacing: 10) {
                            Image(systemName: ["arrow.down.circle", "clock.arrow.circlepath", "lock.shield", "checkmark.shield", "trash"][i])
                                .foregroundStyle(Palette.blue).frame(width: 22)
                            Text(line).foregroundStyle(Palette.ink)
                        }
                        .accessibilityElement(children: .combine)
                        .accessibilityIdentifier("consent.line.\(i)")
                    }
                    Toggle("Allow mobile data for this download", isOn: $allowsCellular)
                        .accessibilityIdentifier("consent.cellular")
                    if hostConfigured {
                        Button { onAgree() } label: {
                            Text("Download \(DeliveryError.bytes(bytes))").frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.borderedProminent)
                        .accessibilityIdentifier("consent.agree")
                    } else {
                        Text("This build has no model host configured, so nothing can be downloaded yet.")
                            .font(.footnote).foregroundStyle(Palette.amber)
                            .accessibilityIdentifier("consent.notConfigured")
                    }
                    Button("Not now") { dismiss() }.frame(maxWidth: .infinity)
                        .accessibilityIdentifier("consent.decline")
                }
                .padding(20)
            }
            .background(Palette.ground.ignoresSafeArea())
            .accessibilityIdentifier("consent.screen")
        }
    }
}
