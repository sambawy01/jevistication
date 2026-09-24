import SwiftUI

struct WebSettingsSheet: View {
    @EnvironmentObject private var web: WebModel
    @Environment(\.dismiss) private var dismiss
    @State private var confirmRemove = false

    var body: some View {
        NavigationStack {
            Form {
                NeonSection {
                    Toggle("Online helper", isOn: $web.helperEnabled)
                        .accessibilityIdentifier("settings.helper")
                    Toggle("Flights", isOn: $web.flightsEnabled)
                        .disabled(!web.helperEnabled)
                        .accessibilityIdentifier("settings.flights")
                } footer: {
                    Text("With the helper off, the Web tab says so and nothing else in Loupe changes. Only flight searches ever go online.")
                }
                NeonSection {
                    if web.hasKey {
                        Button("Remove key", role: .destructive) { confirmRemove = true }
                            .accessibilityIdentifier("settings.removeKey")
                    } else {
                        Text("No Duffel key on this phone.").foregroundStyle(Palette.inkSoft)
                    }
                } header: { Text("Duffel key") } footer: {
                    Text("Stored in the Keychain, this device only. Removing it deletes it from the Keychain.")
                }
            }
            .neonList()
            .navigationTitle("Web settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
            .confirmationDialog("Remove the Duffel key from this phone?", isPresented: $confirmRemove, titleVisibility: .visible) {
                Button("Remove key", role: .destructive) { web.removeKey() }
            }
        }
    }
}
