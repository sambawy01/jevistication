import SwiftUI

/// Browsing protection inside Guard → Protection (2026-09-26): the Safari card (a banner with one
/// button while it is off), Check a link, and the Spotted log. It registers its own navigation
/// destinations, so it only needs a NavigationStack above it.
struct ProtectionSectionContent: View {
    @ObservedObject var setup: SafariSetup = .shared
    @ObservedObject var store: ProtectionStore = .shared
    @State private var openCheck = false

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            SafariProtectionCard(setup: setup, store: store)
            NavigationLink(value: ProtectionRoute.checkLink) { checkLinkRow }
                .buttonStyle(.plain)
                .accessibilityIdentifier("protect.checkLink")
            NavigationLink(value: ProtectionRoute.spotted) { SpottedRow(store: store) }
                .buttonStyle(.plain)
        }
        .navigationDestination(for: ProtectionRoute.self) { route in
            switch route {
            case .checkLink: LinkCheckView(store: store)
            case .spotted: SpottedListView(store: store)
            case .spottedEntry(let id): SpottedDetailView(store: store, id: id)
            }
        }
        .navigationDestination(isPresented: $openCheck) { LinkCheckView(store: store) }
        .task {
            store.reload()
            await setup.refresh()
            #if DEBUG
            if LaunchOptions.current.openScreen == "linkcheck" && !openedAtLaunch { openedAtLaunch = true; openCheck = true }
            #endif
            // The lists Loupe for Safari reads: brought up to date when they are on (nothing with them off).
            await OnlineChecksService.shared.refreshListsForProtection()
        }
    }

    #if DEBUG
    /// `-LoupeOpen linkcheck` (DEBUG, screenshots): opens Check a link once at launch.
    @State private var openedAtLaunch = false
    #endif

    private var checkLinkRow: some View {
        HStack(spacing: 12) {
            NeonIcon(name: "link.badge.plus", color: Palette.cyan, size: 20).frame(width: 28)
            VStack(alignment: .leading, spacing: 2) {
                Text("Check a link").font(.subheadline.weight(.semibold)).foregroundStyle(Palette.ink)
                Text("Paste a link from a message: Loupe checks it on this iPhone, without opening it.")
                    .font(.caption).foregroundStyle(Palette.inkSoft)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 0)
            Image(systemName: "chevron.right").font(.caption).foregroundStyle(Palette.inkSoft).accessibilityHidden(true)
        }
        .frame(minHeight: 44)
        .card()
        .accessibilityElement(children: .combine)
    }
}
