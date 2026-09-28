import LoupeKit
import SwiftUI

/// Home → Money: Guard's Subscriptions section on its own screen (phase 1 hosts today's view; §6.2's redesign is
/// step 4). A merchant opens `GuardRoute.subscription`, registered at Home's root.
struct SubscriptionsScreen: View {
    let openReads: () -> Void
    @ObservedObject var watchers: WatchersService = .shared
    @ObservedObject var sources: SourcesService = .shared

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                GuardNotice(watchers: watchers)
                if let summary = watchers.summary {
                    SubscriptionsSection(census: summary.census, findings: summary.findings,
                                         coverage: GuardCoverage.from(sources), openSources: openReads)
                } else {
                    TrackingLoading(text: "The watchers are reading your sources")
                }
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 32)
        }
        .neonGround()
        .navigationTitle("Subscriptions")
        .navigationBarTitleDisplayMode(.inline)
    }
}

/// Home → Documents: Guard's Expiring soon timeline on its own screen (§6.3's redesign is step 4).
struct ExpiringScreen: View {
    let openReads: () -> Void
    @ObservedObject var watchers: WatchersService = .shared
    @ObservedObject var sources: SourcesService = .shared
    @ObservedObject private var readiness = ModelReadiness.shared
    @ObservedObject private var settings = ModelSettingsService.shared

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                GuardNotice(watchers: watchers)
                if let summary = watchers.summary {
                    ExpirySection(rows: summary.expiries, ruleName: summary.ruleName, itemsChecked: Int(summary.itemsChecked),
                                  half: GuardModel.modelHalf(modelRan: summary.modelRan, modelReady: readiness.isReady,
                                                             turnedOff: !settings.useLaya(Features.shared.WATCHERS)),
                                  coverage: GuardCoverage.from(sources), openSources: openReads)
                } else {
                    TrackingLoading(text: "The watchers are reading your sources")
                }
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 32)
        }
        .neonGround()
        .navigationTitle("Expiring")
        .navigationBarTitleDisplayMode(.inline)
    }
}

struct TrackingLoading: View {
    let text: String

    var body: some View {
        HStack(spacing: 10) {
            ProgressView()
            Text(text).font(.subheadline).foregroundStyle(Palette.inkSoft)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .card()
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("tracking.loading")
    }
}
