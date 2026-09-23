import SwiftUI

struct WebTabView: View {
    @EnvironmentObject private var web: WebModel
    @State private var showSettings = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) { content }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 12)
            }
            .background(Palette.ground.ignoresSafeArea())
            .navigationTitle("Web")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button { showSettings = true } label: { Image(systemName: "gearshape") }
                        .accessibilityLabel("Web settings")
                        .accessibilityIdentifier("web.settings")
                }
            }
            .sheet(isPresented: $showSettings) { WebSettingsSheet().environmentObject(web) }
            .navigationDestination(isPresented: Binding(
                get: { web.searchState == .results },
                set: { if !$0 { web.searchState = .idle } })) {
                ResultsView()
            }
        }
        .task {
            if web.autoSearch && web.searchState == .idle { await web.search() }
        }
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
