import SwiftUI

enum AppTab: String, CaseIterable {
    case now, judgments, web, sources, me
}

struct RootView: View {
    @State var initialTab: AppTab
    @State private var selection: AppTab = .now

    var body: some View {
        TabView(selection: $selection) {
            NowView()
                .tabItem { Label("Now", systemImage: "scope") }
                .tag(AppTab.now)
            PlaceholderTab(title: "Judgments",
                           message: "Questions you write in plain language, run on everything you own. They need sources to run on.",
                           symbol: "list.bullet.rectangle")
                .tabItem { Label("Judgments", systemImage: "list.bullet.rectangle") }
                .tag(AppTab.judgments)
            WebTabView()
                .tabItem { Label("Web", systemImage: "globe") }
                .tag(AppTab.web)
            PlaceholderTab(title: "Sources",
                           message: "Photos, mail and files on this phone. Nothing is connected yet.",
                           symbol: "externaldrive")
                .tabItem { Label("Sources", systemImage: "externaldrive") }
                .tag(AppTab.sources)
            MeView()
                .tabItem { Label("Me", systemImage: "person") }
                .tag(AppTab.me)
        }
        .onAppear { selection = initialTab }
    }
}

struct PlaceholderTab: View {
    let title: String
    let message: String
    let symbol: String

    var body: some View {
        NavigationStack {
            ScrollView {
                HonestEmptyState(title: "Nothing here yet", message: message, symbol: symbol)
                    .padding(.top, 24)
            }
            .background(Palette.ground.ignoresSafeArea())
            .navigationTitle(title)
        }
    }
}
