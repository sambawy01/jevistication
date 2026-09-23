import SwiftUI

struct FlightSearchForm: View {
    @EnvironmentObject private var web: WebModel

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack {
                Caption(text: "Flights")
                Spacer()
                Pill(text: "Online · Duffel", color: Palette.cyan, symbol: "globe")
            }
            HStack(spacing: 12) {
                airport("From", text: $web.form.origin, id: "search.from")
                Image(systemName: "arrow.right").foregroundStyle(Palette.inkSoft).padding(.top, 18)
                airport("To", text: $web.form.destination, id: "search.to")
            }
            DatePicker("Depart", selection: $web.form.departDate, in: Date()..., displayedComponents: .date)
            Toggle("Return", isOn: $web.form.returnTrip)
            if web.form.returnTrip {
                DatePicker("Back", selection: $web.form.returnDate, in: web.form.departDate..., displayedComponents: .date)
            }
            Stepper("Passengers: \(web.form.adults) adult\(web.form.adults == 1 ? "" : "s")", value: $web.form.adults, in: 1...9)
            Picker("Cabin", selection: $web.form.cabin) {
                ForEach(CabinClass.allCases) { Text($0.label).tag($0) }
            }
            Picker("Max stops", selection: $web.form.maxStops) {
                Text("Nonstop").tag(0); Text("1 stop").tag(1); Text("2 stops").tag(2)
            }
            .pickerStyle(.segmented)
            VStack(alignment: .leading, spacing: 6) {
                Text("Your priorities").font(.subheadline.weight(.semibold))
                TextField("nonstop, under £200, not before 7am, 1 checked bag", text: $web.form.priorities, axis: .vertical)
                    .lineLimit(2...4)
                    .padding(10)
                    .background(Palette.ground.opacity(0.6), in: RoundedRectangle(cornerRadius: 10))
                    .accessibilityIdentifier("search.priorities")
                Text("Ranked on this phone. The text never leaves it.")
                    .font(.footnote).foregroundStyle(Palette.inkSoft)
            }
            if case .failed(let e) = web.searchState { ErrorBanner(title: e.title, message: e.message) }
            Button {
                Task { await web.search() }
            } label: {
                HStack {
                    if web.searchState == .searching { ProgressView().tint(.white) }
                    Text(web.searchState == .searching ? "Searching…" : "Search flights")
                }
                .font(.headline).frame(maxWidth: .infinity).padding(.vertical, 6)
            }
            .buttonStyle(.borderedProminent)
            .disabled(web.searchState == .searching || !formValid)
            .accessibilityIdentifier("search.go")
        }
        .card()
    }

    private var formValid: Bool {
        SearchForm.isIATA(web.form.origin) && SearchForm.isIATA(web.form.destination)
    }

    private func airport(_ label: String, text: Binding<String>, id: String) -> some View {
        let bad = !text.wrappedValue.isEmpty && !SearchForm.isIATA(text.wrappedValue)
        return VStack(alignment: .leading, spacing: 4) {
            Text(label).font(.caption).foregroundStyle(Palette.inkSoft)
            TextField("LIS", text: Binding(
                get: { text.wrappedValue },
                set: { text.wrappedValue = String($0.uppercased().filter { $0.isLetter }.prefix(3)) }))
                .font(Typeface.display(30))
                .textInputAutocapitalization(.characters)
                .autocorrectionDisabled()
                .foregroundStyle(bad ? Palette.red : Palette.ink)
                .accessibilityIdentifier(id)
            Text(bad ? "3 letters" : "IATA code").font(.caption2).foregroundStyle(bad ? Palette.red : Palette.inkSoft)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}
