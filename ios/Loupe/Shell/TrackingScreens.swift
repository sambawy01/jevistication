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
                    TrackingNotYet(what: "your subscriptions", sources: sources, watchers: watchers, openReads: openReads)
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
                    TrackingNotYet(what: "which documents expire soon", sources: sources, watchers: watchers, openReads: openReads)
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

/// Run now for the tracking screens: the same action as Protection's Run now (`guard.runNow`), a full run through
/// `RunCoordinator` (visible and cancellable on Home). A manual run is allowed under O-4 (nothing heavy starts by
/// itself at open).
enum TrackingRun {
    @MainActor static func runNow() {
        RunCoordinator.shared.runNow(reason: .manual)
    }
}

/// No summary yet: "Loading the last results…" while the saved ones are read back; "reading" while a run, a scan or the
/// watchers really go; otherwise not checked yet, with Run now (or, with no source on, the way to turn one on).
struct TrackingNotYet: View {
    let what: String
    @ObservedObject var sources: SourcesService
    @ObservedObject var watchers: WatchersService
    let openReads: () -> Void
    /// Running or not (changes only at a run's start and end).
    @ObservedObject private var runStatus = RunCoordinator.shared.status

    var body: some View {
        if !watchers.loaded {
            TrackingLoading(text: "Loading the last results…")
        } else if watchers.running || sources.scanning || runStatus.running {
            TrackingLoading(text: "The watchers are reading your sources")
        } else {
            VStack(alignment: .leading, spacing: 10) {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Not checked yet").font(.subheadline.weight(.semibold)).foregroundStyle(Palette.ink)
                    Text("Run a check to see \(what).").font(.footnote).foregroundStyle(Palette.inkSoft)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .accessibilityElement(children: .combine)
                if GuardCoverage.from(sources).anyOn {
                    CardAction(title: "Run now", symbol: "arrow.clockwise", hue: Palette.cyan) { TrackingRun.runNow() }
                        .accessibilityLabel("Run the check now")
                        .accessibilityIdentifier("tracking.run")
                } else {
                    CardAction(title: "Open What Loupe reads", symbol: "externaldrive.fill.badge.plus", hue: Palette.cyan, action: openReads)
                        .accessibilityIdentifier("tracking.connect")
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .card()
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("tracking.notYet")
        }
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
