import SwiftUI

/// "Get Laya": the first step of onboarding while the model is not on the phone, and the screen
/// every locked feature opens. Says in two sentences why Loupe needs its model, then offers the
/// consented background download (`LayaModel` + `ModelConsentView`, the same flow as
/// Me → Laya model). "Later" is allowed: the app opens with the features that need Laya locked,
/// and this step comes back on the next launch until the model is here. No Download button is
/// shown when the build has no model host; it says so instead.
struct GetLayaView: View {
    enum Context { case onboarding, sheet }
    var context: Context = .sheet
    /// Later / Close / Continue: leave this screen.
    var onDone: () -> Void

    @ObservedObject private var readiness = ModelReadiness.shared
    @ObservedObject private var model = LayaModel.shared
    @State private var showConsent = false

    private var sizeText: String { DeliveryError.bytes(model.downloadBytes) }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                HStack(alignment: .bottom) {
                    Image("LogoLight").resizable().scaledToFit().frame(height: 30).accessibilityLabel("Loupe")
                    Spacer()
                    MascotView(state: mascot, size: 84)
                }
                VStack(alignment: .leading, spacing: 8) {
                    Text("ON-DEVICE MODEL").font(Typeface.mono(11, weight: .medium)).tracking(0.8).foregroundStyle(Palette.cyan)
                    Text("Get Laya").font(Typeface.display(34)).foregroundStyle(Palette.ink)
                        .accessibilityAddTraits(.isHeader)
                    Text("Loupe needs Laya, its on-device model, to judge, sort and answer your questions. It is a one-time download of about \(sizeText) that runs on this iPhone, and nothing you have leaves the phone.")
                        .foregroundStyle(Palette.inkSoft)
                        .fixedSize(horizontal: false, vertical: true)
                        .accessibilityIdentifier("getLaya.explainer")
                }
                stateCard
                laterButton
            }
            .padding(24)
            .frame(maxWidth: 560)
            .frame(maxWidth: .infinity)
        }
        .neonGround()
        .accessibilityIdentifier("getLaya.screen")
        .onAppear { readiness.recheck() }
        .sheet(isPresented: $showConsent) {
            ModelConsentView(bytes: model.downloadBytes, variantTitle: model.variant?.title ?? "",
                             hostConfigured: readiness.hostConfigured, allowsCellular: $model.allowsCellular) {
                model.grantConsent()
                model.startDownload()
                showConsent = false
            }
        }
    }

    private var mascot: MascotState {
        switch readiness.state {
        case .ready: return .happy
        case .downloading, .verifying: return .scanning
        case .failed: return .shrug
        case .missing, .paused: return .greeting
        }
    }

    @ViewBuilder private var stateCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            switch readiness.state {
            case .ready:
                Label("Laya is on this iPhone and ready", systemImage: "checkmark.circle.fill")
                    .font(.headline).foregroundStyle(Palette.mint)
                    .accessibilityIdentifier("getLaya.ready")
                Button(action: onDone) {
                    Text(context == .onboarding ? "Start using Loupe" : "Done").frame(maxWidth: .infinity, minHeight: 44)
                }
                .buttonStyle(.neonPrimary)
                .accessibilityIdentifier("getLaya.continue")
            case .downloading(let p):
                ProgressView(value: p) { Text("Downloading Laya… \(Int(p * 100))%").foregroundStyle(Palette.ink) }
                    .tint(Palette.blue)
                    .accessibilityIdentifier("getLaya.progress")
                Text("It keeps downloading if you leave Loupe. Features that need Laya unlock when it finishes.")
                    .font(.footnote).foregroundStyle(Palette.inkSoft)
                Button("Pause") { model.pauseDownload() }
                    .frame(minHeight: 44)
                    .accessibilityIdentifier("getLaya.pause")
            case .paused(let p):
                ProgressView(value: p) { Text("Paused at \(Int(p * 100))%").foregroundStyle(Palette.ink) }.tint(Palette.inkSoft)
                Button { model.startDownload() } label: { Text("Resume").frame(maxWidth: .infinity, minHeight: 44) }
                    .buttonStyle(.neonPrimary)
                    .disabled(!readiness.hostConfigured)
                    .accessibilityIdentifier("getLaya.resume")
            case .verifying:
                ProgressView("Checking Laya's files…").foregroundStyle(Palette.ink)
            case .failed(let why):
                Label(why, systemImage: "exclamationmark.triangle.fill").font(.footnote).foregroundStyle(Palette.dangerText)
                    .accessibilityIdentifier("getLaya.error")
                downloadControls
            case .missing:
                downloadControls
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .card()
    }

    @ViewBuilder private var downloadControls: some View {
        if readiness.hostConfigured {
            Button { if model.hasConsent { model.startDownload() } else { showConsent = true } } label: {
                Label("Download Laya (\(sizeText))", systemImage: "arrow.down.circle.fill").frame(maxWidth: .infinity, minHeight: 44)
            }
            .buttonStyle(.neonPrimary)
            .accessibilityIdentifier("getLaya.download")
            Toggle("Allow mobile data", isOn: $model.allowsCellular)
                .font(.subheadline).foregroundStyle(Palette.ink)
                .frame(minHeight: 44)
                .accessibilityIdentifier("getLaya.cellular")
            Text("Wi-Fi only unless you allow mobile data. You agree to the download on the next screen; nothing is fetched before that.")
                .font(.footnote).foregroundStyle(Palette.inkSoft)
        } else {
            VStack(alignment: .leading, spacing: 6) {
                Label("This build cannot download Laya", systemImage: "link.badge.plus")
                    .font(.headline).foregroundStyle(Palette.warnText)
                Text("It has no model download host set, so there is nothing to download from and no request is made. Features that need Laya stay locked until its files are on this iPhone; they unlock by themselves when the files appear.")
                    .font(.footnote).foregroundStyle(Palette.inkSoft)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .accessibilityElement(children: .combine)
            .accessibilityIdentifier("getLaya.notConfigured")
            Button("Check again") { readiness.recheck() }
                .font(.subheadline.weight(.semibold))
                .frame(minHeight: 44)
                .accessibilityIdentifier("getLaya.checkAgain")
        }
    }

    @ViewBuilder private var laterButton: some View {
        if !readiness.isReady {
            VStack(spacing: 4) {
                Button(action: onDone) {
                    Text(laterTitle).font(.body.weight(.semibold)).frame(maxWidth: .infinity, minHeight: 44)
                }
                .foregroundStyle(Palette.ink)
                .accessibilityIdentifier("getLaya.later")
                Text(context == .onboarding
                     ? "Features that need Laya stay locked until it is here. Loupe asks again next time you open it."
                     : "Features that need Laya stay locked until it is here.")
                    .font(.caption).foregroundStyle(Palette.inkSoft).multilineTextAlignment(.center)
            }
            .frame(maxWidth: .infinity)
        }
    }

    private var laterTitle: String {
        if case .downloading = readiness.state { return "Continue while it downloads" }
        return context == .onboarding ? "Later" : "Not now"
    }
}

// MARK: - The locked state

/// A feature that needs Laya, while Laya is not ready: what is missing and one button to Get Laya.
/// Shown in place of the feature (never a feature that starts and then fails). Unlocks live.
struct NeedsLayaCard: View {
    /// Stable id for UI tests (`needsLaya.<feature>`).
    let feature: String
    /// What stays locked, in plain words ("Answering the Unsure queue").
    let what: String
    @ObservedObject private var readiness = ModelReadiness.shared
    @State private var showGetLaya = false

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .top, spacing: 12) {
                NeonIcon(name: "lock.fill", color: Palette.amber, size: 20)
                    .frame(width: 28)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 4) {
                    Text("Needs Laya, the on-device model").font(.headline).foregroundStyle(Palette.ink)
                        .fixedSize(horizontal: false, vertical: true)
                    Text("\(what) runs on Laya. \(ModelGate.detail(readiness.state, hostConfigured: readiness.hostConfigured))")
                        .font(.subheadline).foregroundStyle(Palette.inkSoft)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .accessibilityElement(children: .combine)
            if case .downloading(let p) = readiness.state {
                ProgressView(value: p).tint(Palette.blue).accessibilityHidden(true)
            }
            Button { showGetLaya = true } label: {
                Label(buttonTitle, systemImage: "arrow.down.circle.fill").frame(maxWidth: .infinity, minHeight: 44)
            }
            .buttonStyle(.neonPrimary)
            .accessibilityIdentifier("needsLaya.\(feature).get")
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .card()
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("needsLaya.\(feature)")
        .onAppear { readiness.recheck() }
        .sheet(isPresented: $showGetLaya) { GetLayaView(context: .sheet) { showGetLaya = false } }
    }

    private var buttonTitle: String {
        switch readiness.state {
        case .downloading, .paused, .verifying: return "See the download"
        default: return "Get Laya"
        }
    }
}

/// The compact form, for a line inside a card (Web answers, the game's banner): one button.
struct GetLayaButton: View {
    var title = "Get Laya"
    let id: String
    @State private var showGetLaya = false

    var body: some View {
        Button { showGetLaya = true } label: {
            Label(title, systemImage: "arrow.down.circle").frame(minHeight: 44)
        }
        .font(.footnote.weight(.semibold))
        .accessibilityIdentifier(id)
        .sheet(isPresented: $showGetLaya) { GetLayaView(context: .sheet) { showGetLaya = false } }
    }
}

/// `.requiresLaya(...)`: the content while Laya is ready, the locked card otherwise. The one
/// gate every model feature uses (decision: `ModelGate.decide`).
struct RequiresLaya: ViewModifier {
    let feature: String
    let what: String
    @ObservedObject private var readiness = ModelReadiness.shared

    func body(content: Content) -> some View {
        switch ModelGate.decide(needsModel: true, readiness.state) {
        case .open: content
        case .locked: NeedsLayaCard(feature: feature, what: what)
        }
    }
}

extension View {
    func requiresLaya(_ feature: String, what: String) -> some View {
        modifier(RequiresLaya(feature: feature, what: what))
    }
}
