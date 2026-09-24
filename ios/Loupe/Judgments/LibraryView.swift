import SwiftUI
import LoupeKit

/// The library's browse state: a category (or all) and a search, over LoupeKit's `TemplateLibrary`.
@MainActor
final class LibraryModel: ObservableObject {
    struct CategoryInfo: Hashable { let name: String; let title: String; let count: Int }

    @Published var query = ""
    /// The selected category's Kotlin enum name, or nil for all. (LoupeKit's `Category` cannot be
    /// spelled in Swift: the module's `LoupeKit` class shadows the module name.)
    @Published var category: String?

    /// The ten categories in library order, with their template counts.
    let categories: [CategoryInfo] = {
        var seen: [String: CategoryInfo] = [:]
        var order: [String] = []
        for t in TemplateLibrary.shared.ALL {
            let c = t.category
            if seen[c.name] == nil { order.append(c.name) }
            seen[c.name] = CategoryInfo(name: c.name, title: c.title, count: (seen[c.name]?.count ?? 0) + 1)
        }
        return order.compactMap { seen[$0] }
    }()

    var total: Int { TemplateLibrary.shared.ALL.count }

    var matches: [Template] {
        let selected = category.flatMap { name in TemplateLibrary.shared.ALL.first { $0.category.name == name }?.category }
        return TemplateLibrary.shared.search(query: query, category: selected)
    }
}

struct LibraryView: View {
    @ObservedObject var service: JudgmentsService
    @StateObject private var model = LibraryModel()

    var body: some View {
        VStack(spacing: 8) {
            HStack {
                Image(systemName: "magnifyingglass").foregroundStyle(Palette.inkSoft)
                TextField("Search \(model.total) templates", text: $model.query)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .accessibilityIdentifier("library.search")
                if !model.query.isEmpty {
                    Button { model.query = "" } label: { Image(systemName: "xmark.circle.fill") }
                        .foregroundStyle(Palette.inkSoft)
                        .accessibilityLabel("Clear search")
                }
            }
            .padding(10)
            .background(Palette.card, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            .padding(.horizontal, 16)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 6) {
                    chip("All", model.total, model.category == nil) { model.category = nil }
                    ForEach(model.categories, id: \.self) { c in
                        chip(c.title, c.count, model.category == c.name) { model.category = c.name }
                    }
                }
                .padding(.horizontal, 16)
            }
            ScrollView {
                LazyVStack(spacing: 10) {
                    Text("Templates are starting points: using one makes a judgment you own and can reword.")
                        .font(.caption).foregroundStyle(Palette.inkSoft).frame(maxWidth: .infinity, alignment: .leading)
                    if model.matches.isEmpty {
                        Text("No template matches \"\(model.query)\". Try Write your own.")
                            .foregroundStyle(Palette.inkSoft).padding(.top, 24)
                    }
                    ForEach(model.matches, id: \.id) { t in
                        NavigationLink(value: JudgmentRoute.template(t.id)) { row(t) }
                            .buttonStyle(.plain)
                            .accessibilityIdentifier("library.template.\(t.id)")
                    }
                }
                .padding(.horizontal, 16).padding(.bottom, 16)
            }
        }
    }

    private func chip(_ title: String, _ n: Int, _ on: Bool, _ tap: @escaping () -> Void) -> some View {
        Button(action: tap) {
            HStack(spacing: 4) {
                Text(title).font(.subheadline.weight(on ? .semibold : .regular))
                Text("\(n)").font(Typeface.mono(11)).opacity(0.7)
            }
            .padding(.horizontal, 12).padding(.vertical, 7)
            .foregroundStyle(on ? .white : Palette.ink)
            .background(on ? Palette.blue : Palette.card, in: Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(on ? .isSelected : [])
    }

    private func row(_ t: Template) -> some View {
        let inUse = JudgmentBook.shared.usesOf(templateId: t.id, existing: service.judgments) > 0
        return VStack(alignment: .leading, spacing: 5) {
            HStack(alignment: .firstTextBaseline) {
                Text(t.title).font(.headline).foregroundStyle(Palette.ink)
                Spacer()
                if t.warnOnly { Pill(text: "warn-only", color: Palette.amber) }
                if inUse { Pill(text: "in use", color: Palette.mint, symbol: "checkmark") }
            }
            Text(t.question).font(.subheadline).foregroundStyle(Palette.inkSoft).lineLimit(2)
            Text("\(t.category.title) · \(JudgmentResults.shared.shapeName(shape: t.shape))")
                .font(Typeface.mono(11)).foregroundStyle(Palette.inkSoft)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .card()
    }
}

/// One template: its answers, its three-part criteria, its baseline and posture, and "Use this".
struct TemplateDetailView: View {
    @ObservedObject var service: JudgmentsService
    let templateId: String
    @State private var values: [String: String] = [:]
    @State private var problems: [String] = []
    @State private var added: String?

    private var template: Template? { TemplateLibrary.shared.byId(id: templateId) }

    var body: some View {
        ScrollView {
            if let t = template {
                VStack(alignment: .leading, spacing: 14) {
                    VStack(alignment: .leading, spacing: 6) {
                        Caption(text: t.category.title)
                        Text(t.title).font(Typeface.display(28)).foregroundStyle(Palette.ink)
                        Text(t.question).font(.body.weight(.medium)).foregroundStyle(Palette.blue)
                        if let note = t.desktopNote { Text(note).font(.footnote).foregroundStyle(Palette.inkSoft) }
                    }
                    section("Answers") { answers(t) }
                    section("What it means") {
                        fact("True when", t.invariant)
                        fact("False when", t.breaks)
                        fact("Looks like it, isn't", t.lookalikes)
                        Text("The model reads the question and the options. These three parts are for you and for checking its answers; a judgment can opt in to show them to the model.")
                            .font(.caption).foregroundStyle(Palette.inkSoft)
                    }
                    section("How it behaves") {
                        fact("If the model fails", JudgmentResults.shared.postureText(p: t.onFailure))
                        fact("Written for", t.sources.map { ($0 as SourceKind).title }.sorted().joined(separator: ", "))
                        fact("Baseline", t.baseline?.description_ ?? "none")
                        Text("The dumb baseline is what the model is measured against on your corrections.")
                            .font(.caption).foregroundStyle(Palette.inkSoft)
                        if let m = t.mechanical { fact("Mechanical first", m.description_) }
                    }
                    section("Examples") {
                        ForEach(Array(t.examples.enumerated()), id: \.offset) { _, e in
                            VStack(alignment: .leading, spacing: 3) {
                                Text(e.text).font(Typeface.mono(12)).foregroundStyle(Palette.inkSoft)
                                Text("→ \(e.answer)").font(.subheadline.weight(.semibold))
                                Text(e.why).font(.caption).foregroundStyle(Palette.inkSoft)
                            }
                            .padding(10).frame(maxWidth: .infinity, alignment: .leading)
                            .background(Palette.ground.opacity(0.6), in: RoundedRectangle(cornerRadius: 10))
                        }
                    }
                    if !t.parameters.isEmpty { section("Fill in") { parameters(t) } }
                    use(t)
                }
                .padding(16)
            }
        }
        .neonGround()
        .navigationTitle("Template")
        .navigationBarTitleDisplayMode(.inline)
    }

    @ViewBuilder private func answers(_ t: Template) -> some View {
        switch t.shape {
        case let s as ShapeBinary:
            Text("Two options, each saying what it means (the model reads them):").font(.footnote).foregroundStyle(Palette.inkSoft)
            bullet(s.positive); bullet(s.negative)
            if t.warnOnly { Text("Warn-only: the second answer is shown as \"no signal\", never as safe.").font(.caption).foregroundStyle(Palette.amber) }
        case let s as ShapePick:
            ForEach(s.candidates, id: \.self) { c in bullet(c == s.noOp ? "\(c)  (nothing here applies)" : c) }
        case let s as ShapeOrdinal:
            ForEach(Array(zip(s.candidates, s.bands)), id: \.0) { v, b in bullet("\(v) — \(b)") }
        default:
            bullet("yes"); bullet("no")
        }
    }

    @ViewBuilder private func parameters(_ t: Template) -> some View {
        ForEach(t.parameters, id: \.name) { p in
            VStack(alignment: .leading, spacing: 4) {
                TextField(p.label, text: Binding(get: { values[p.name] ?? "" }, set: { values[p.name] = $0 }), prompt: Text(p.example))
                    .textFieldStyle(.roundedBorder)
                    .accessibilityIdentifier("template.param.\(p.name)")
                if let v = values[p.name], !v.isEmpty, let why = p.validate(value: v) {
                    Text(why).font(.caption).foregroundStyle(Palette.amber)
                }
            }
        }
    }

    @ViewBuilder private func use(_ t: Template) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Button {
                switch service.useTemplate(t.id, values: values) {
                case .success(let id): added = id; problems = []
                case .failure(let r): problems = r.reasons
                }
            } label: {
                Label(added == nil ? "Use this" : "Added to My judgments", systemImage: added == nil ? "plus.circle.fill" : "checkmark.circle.fill")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.neonPrimary)
            .controlSize(.large)
            .disabled(added != nil)
            .accessibilityIdentifier("template.use")
            Text("Creates a judgment you own. Rewording it later restarts its calibration.")
                .font(.caption).foregroundStyle(Palette.inkSoft)
            ForEach(problems, id: \.self) { Text($0).font(.footnote).foregroundStyle(Palette.amber) }
        }
    }

    private func section<C: View>(_ title: String, @ViewBuilder _ content: () -> C) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Caption(text: title)
            content()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .card()
    }

    private func fact(_ label: String, _ text: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label).font(.caption.weight(.semibold)).foregroundStyle(Palette.inkSoft)
            Text(text).font(.subheadline).foregroundStyle(Palette.ink).fixedSize(horizontal: false, vertical: true)
        }
    }

    private func bullet(_ text: String) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 6) {
            Text("•").foregroundStyle(Palette.blue)
            Text(text).font(Typeface.mono(13)).foregroundStyle(Palette.ink)
        }
    }
}
