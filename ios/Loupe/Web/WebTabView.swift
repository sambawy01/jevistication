import SwiftUI

struct WebTabView: View {
    @EnvironmentObject private var web: WebModel
    @State private var showSettings = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) { library }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 12)
            }
            .neonGround()
            .navigationTitle("Web")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button { showSettings = true } label: { Image(systemName: "gearshape") }
                        .accessibilityLabel("Web settings")
                        .accessibilityIdentifier("web.settings")
                }
            }
            .sheet(isPresented: $showSettings) {
                WebSettingsSheet().environmentObject(web).environmentObject(web.library)
            }
            .navigationDestination(for: WebSector.self) { sector in
                if sector == .flights {
                    FlightsScreen()
                } else {
                    TemplateView(sector: sector)
                }
            }
            .navigationDestination(isPresented: Binding(
                get: { web.searchState == .results },
                set: { if !$0 { web.searchState = .idle } })) {
                ResultsView()
            }
        }
        .environmentObject(web.library)
        .environment(\.layoutDirection, MS.direction)
        .task {
            if web.autoSearch && web.searchState == .idle && WebBuild.flightsDevFlag { await web.search() }
        }
    }

    /// The template library (owner decision 2026-09-25): one card per source.
    @ViewBuilder private var library: some View {
        if !web.helperEnabled || !web.isOnline {
            OfflineCard(helperOff: !web.helperEnabled)
        }
        VStack(alignment: .leading, spacing: 6) {
            Text(WS.t("library.title")).font(Typeface.display(26)).foregroundStyle(Palette.ink)
            Text(WS.t("library.intro")).font(.footnote).foregroundStyle(Palette.inkSoft)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        ForEach(WebBuild.sectors()) { sector in
            NavigationLink(value: sector) { TemplateCard(sector: sector) }
                .buttonStyle(.plain)
                .accessibilityIdentifier("web.template.\(sector.rawValue)")
        }
        Text(WS.t("privacy")).font(.caption).foregroundStyle(Palette.inkSoft)
            .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// A template in the library: the source, its provider, how many questions, its switch state.
struct TemplateCard: View {
    let sector: WebSector
    @EnvironmentObject private var library: WebLibraryModel
    @EnvironmentObject private var web: WebModel

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: sector.symbol).font(.title2).foregroundStyle(Palette.cyan).frame(width: 34)
            VStack(alignment: .leading, spacing: 4) {
                HStack {
                    Text(sector.title).font(.headline).foregroundStyle(Palette.ink)
                    Spacer()
                    Pill(text: on ? "Online · \(sector.provider)" : WS.t("off"),
                         color: on ? Palette.cyan : Palette.inkSoft, symbol: on ? "globe" : "power")
                }
                if sector == .flights {
                    Text(WS.t("flights.devOnly")).font(.caption.weight(.semibold)).foregroundStyle(Palette.amber)
                        .accessibilityIdentifier("web.flights.devOnly")
                } else {
                    Text(WebCatalog.variants(sector).prefix(2).map { "“\($0.question(library.inputs))”" }.joined(separator: "  "))
                        .font(.caption).foregroundStyle(Palette.inkSoft).lineLimit(2)
                }
            }
            Image(systemName: MS.lang == .ar ? "chevron.left" : "chevron.right").foregroundStyle(Palette.inkSoft).padding(.top, 4)
        }
        .card()
    }

    private var on: Bool { sector == .flights ? web.flightsEnabled && web.helperEnabled : library.isEnabled(sector) && web.helperEnabled }
}

/// Flights: development only until Duffel grants permission. Sample offers in fixture mode, plus
/// the existing key flow, labelled.
struct FlightsScreen: View {
    @EnvironmentObject private var web: WebModel

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                Label(WS.t("flights.devOnly"), systemImage: "hammer")
                    .font(.footnote.weight(.semibold)).foregroundStyle(Palette.amber)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(10)
                    .background(Palette.warnSoft, in: RoundedRectangle(cornerRadius: 10))
                    .accessibilityIdentifier("web.flights.devBanner")
                content
            }
            .padding(.horizontal, 16).padding(.vertical, 12)
        }
        .neonGround()
        .navigationTitle(WS.t("sector.flights"))
        .navigationBarTitleDisplayMode(.inline)
    }

    @ViewBuilder private var content: some View {
        if !web.helperEnabled || !web.isOnline {
            OfflineCard(helperOff: !web.helperEnabled)
        } else if !web.flightsEnabled {
            InfoCard(symbol: "airplane", title: "Flights is off",
                     text: "Turn Flights on in Web settings to search. Nothing else in Loupe changes.")
        } else if !web.hasKey {
            KeyOnboardingView()
        } else {
            FlightSearchForm()
        }
    }
}

struct OfflineCard: View {
    var helperOff: Bool
    var body: some View {
        VStack(spacing: 12) {
            MascotView(state: .shrug, size: 80)
            Text(helperOff ? "Online helper is off" : "Offline")
                .font(Typeface.display(24)).foregroundStyle(Palette.ink)
            Text("Needs a connection. Everything else keeps working.")
                .multilineTextAlignment(.center).foregroundStyle(Palette.inkSoft)
                .accessibilityIdentifier("web.offline")
        }
        .frame(maxWidth: .infinity).card()
    }
}

struct InfoCard: View {
    let symbol: String, title: String, text: String
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Label(title, systemImage: symbol).font(.headline).foregroundStyle(Palette.ink)
            Text(text).foregroundStyle(Palette.inkSoft)
        }
        .frame(maxWidth: .infinity, alignment: .leading).card()
    }
}
