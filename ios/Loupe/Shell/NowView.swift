import SwiftUI

struct NowView: View {
    @EnvironmentObject private var launcher: GameLauncher
    @ObservedObject var service: JudgmentsService = .shared
    @State private var showQueue = false
    @State private var opened = false

    var body: some View {
        NavigationStack {
            content
                .navigationDestination(isPresented: $showQueue) { UnsureQueueView(service: service) }
                .toolbar(.hidden, for: .navigationBar)
        }
        .task {
            service.load()
            service.refreshLedger()
            #if DEBUG
            if LaunchOptions.current.queueDemo { await seedWhenScanned() }
            #endif
            if !opened, LaunchOptions.current.openScreen == "queue" { opened = true; showQueue = true }
        }
    }

    #if DEBUG
    /// Waits (briefly) for the sample scan, then seeds the fixture queue.
    private func seedWhenScanned() async {
        for _ in 0..<100 where service.sampleItems().isEmpty { try? await Task.sleep(nanoseconds: 100_000_000) }
        if service.rows.isEmpty { await service.seedFixtureQueue() }
    }
    #endif

    private var hasDecisions: Bool { !service.rows.isEmpty }

    private var content: some View {
        ScrollView {
            VStack(spacing: 20) {
                HeroBand {
                    VStack(alignment: .leading, spacing: 14) {
                        HStack(alignment: .bottom) {
                            Image("LogoLight")
                                .resizable().scaledToFit()
                                .frame(height: 34)
                                .accessibilityLabel("Loupe")
                            Spacer()
                            MascotView(state: .idle, size: 92)
                        }
                        .padding(.top, 16)
                        HStack {
                            Circle().fill(Palette.mint).frame(width: 8, height: 8)
                            Text("On this phone. Nothing leaves it.")
                                .font(.subheadline)
                                .foregroundStyle(.white.opacity(0.9))
                            Spacer()
                            Text("0 bytes out")
                                .font(Typeface.mono(12))
                                .foregroundStyle(.white.opacity(0.85))
                        }
                        Divider().overlay(.white.opacity(0.15))
                        HStack(spacing: 0) {
                            stat("Needs you", hasDecisions ? "\(service.needsYou)" : nil)
                            stat("Matches today")
                            stat("Sources")
                        }
                    }
                }
                if hasDecisions {
                    Button { showQueue = true } label: { NeedsYouCard(count: service.needsYou) }
                        .buttonStyle(.plain)
                        .padding(.horizontal, 16)
                        .accessibilityIdentifier("now.needsYou")
                }
                PlayCard { launcher.open($0) }
                if !hasDecisions {
                HonestEmptyState(
                    title: "Nothing to judge yet",
                    message: "Now fills in once Loupe can read the photos, mail and files on this phone. Until then there is nothing to count, so it shows nothing.",
                    symbol: "photo.on.rectangle")
                }
            }
            .padding(.bottom, 24)
        }
        .background(Palette.ground.ignoresSafeArea())
        .scrollBounceBehavior(.basedOnSize)
    }

    private func stat(_ label: String, _ value: String? = nil) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label).font(.caption).foregroundStyle(.white.opacity(0.7))
            // An em dash, not a zero: there is no data yet, and zero would be a claim.
            Text(value ?? "—").font(Typeface.display(26)).foregroundStyle(Palette.cyan)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}
