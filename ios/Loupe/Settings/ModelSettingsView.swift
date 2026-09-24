import LoupeKit
import SwiftUI

/// Me → Model settings: how Laya is used, the same settings as Loupe Station's Model settings.
/// A "Everywhere" section and one per feature; each row shows its value, its default, a one-line
/// trade-off and Reset to default. Restore all defaults asks first. English or Arabic (right to left).
struct ModelSettingsView: View {
    @ObservedObject var service: ModelSettingsService
    @ObservedObject private var laya = LayaModel.shared
    @StateObject private var model: ModelSettingsModel
    /// A feature to scroll to (from a "Laya was off" banner's Turn it on).
    var focus: String?
    @State private var confirmRestore = false
    @Environment(\.dismiss) private var dismiss
    var showsDone = false

    init(service: ModelSettingsService = .shared, focus: String? = nil, showsDone: Bool = false) {
        self.service = service
        self.focus = focus
        self.showsDone = showsDone
        _model = StateObject(wrappedValue: ModelSettingsModel(service: service))
    }

    var body: some View {
        ScrollViewReader { proxy in
            List {
                Section {
                    Text(MS.t("intro")).font(.footnote).foregroundStyle(Palette.inkSoft)
                    if let notice = service.notice {
                        Text(notice).font(.footnote).foregroundStyle(Palette.ink)
                            .accessibilityIdentifier("settings.notice")
                    }
                }
                ForEach(model.sections) { section in
                    Section {
                        if section.scope == Features.shared.PLAYGROUND {
                            Text(MS.t("desktopOnly")).font(.footnote).foregroundStyle(Palette.inkSoft)
                        }
                        ForEach(section.rows) { row in
                            SettingRowView(model: model, row: row)
                        }
                        if section.scope == EngineSettings.companion.GLOBAL {
                            Text(laya.isLoaded ? MS.t("loadedNow") : MS.t("unloadedNow"))
                                .font(.caption).foregroundStyle(Palette.inkSoft)
                                .accessibilityIdentifier("settings.loaded")
                        }
                    } header: {
                        Text(section.title)
                    } footer: {
                        Text(section.hint)
                    }
                    .id(section.scope)
                }
                Section {
                    Button(MS.t("restoreAll"), role: .destructive) { confirmRestore = true }
                        .disabled(!model.anyChanged)
                        .accessibilityIdentifier("settings.restoreAll")
                } footer: {
                    Text(MS.t("noEnv"))
                }
            }
            .scrollContentBackground(.hidden)
            .background(Palette.ground.ignoresSafeArea())
            .navigationTitle(MS.t("title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                if showsDone {
                    ToolbarItem(placement: .confirmationAction) {
                        Button(MS.t("done")) { dismiss() }.accessibilityIdentifier("settings.done")
                    }
                }
            }
            .confirmationDialog(MS.t("restoreConfirm"), isPresented: $confirmRestore, titleVisibility: .visible) {
                Button(MS.t("restoreAll"), role: .destructive) { model.restoreAll() }
                    .accessibilityIdentifier("settings.restoreAll.confirm")
                Button(MS.t("cancel"), role: .cancel) {}
            }
            .onAppear {
                if let focus { DispatchQueue.main.async { withAnimation { proxy.scrollTo(focus, anchor: .top) } } }
            }
        }
        .environment(\.layoutDirection, MS.direction)
    }
}

/// One setting: title, control, "Now / default", the trade-off, Reset.
struct SettingRowView: View {
    @ObservedObject var model: ModelSettingsModel
    let row: SettingRow

    private var spec: SettingSpec? { EngineSettings.companion.spec(key: row.key) }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            if let spec { control(spec) }
            Text(MS.t("current", ["v": row.valueText]) + " · " + MS.t("default", ["v": row.defaultText]))
                .font(Typeface.mono(12)).foregroundStyle(Palette.inkSoft)
                .accessibilityIdentifier("settings.\(row.key).value")
            Text(row.tradeOff).font(.caption).foregroundStyle(Palette.inkSoft)
                .fixedSize(horizontal: false, vertical: true)
            if let note = row.note {
                Text(note).font(.caption2).foregroundStyle(row.appliesOnPhone ? Palette.inkSoft : Palette.warnText)
                    .fixedSize(horizontal: false, vertical: true)
            }
            if !row.isDefault {
                Button(MS.t("reset")) { model.reset(row.key) }
                    .font(.caption.weight(.semibold))
                    .buttonStyle(.borderless)
                    .accessibilityLabel(MS.t("resetAria", ["name": row.title]))
                    .accessibilityIdentifier("settings.\(row.key).reset")
            }
        }
        .padding(.vertical, 4)
        .opacity(row.appliesOnPhone ? 1 : 0.6)
    }

    @ViewBuilder
    private func control(_ spec: SettingSpec) -> some View {
        switch spec.kind {
        case .bool_:
            Toggle(row.title, isOn: Binding(get: { model.bool(row.key) }, set: { model.setBool(row.key, $0) }))
                .disabled(!row.appliesOnPhone)
                .accessibilityIdentifier("settings.\(row.key)")
        case .enum_, .nullableEnum:
            Picker(row.title, selection: Binding<String>(
                get: { model.string(row.key) ?? "" },
                set: { model.setChoice(row.key, $0.isEmpty ? nil : $0) })) {
                if spec.kind == .nullableEnum { Text(MS.t("v.followGlobal")).tag("") }
                ForEach(spec.choices, id: \.self) { c in
                    Text(spec.name == "memory_mode" ? MS.t("mem.\(c)") : MS.t("route.\(c)")).tag(c)
                }
            }
            .disabled(!row.appliesOnPhone)
            .accessibilityIdentifier("settings.\(row.key)")
        case .textChars:
            textChars(spec)
        case .nullableNumber:
            let on = model.number(row.key) != nil
            Toggle(row.title, isOn: Binding(get: { on }, set: { model.setNumber(row.key, $0 ? ModelSettingsModel.startValue(spec) : nil) }))
                .accessibilityIdentifier("settings.\(row.key)")
            if on { stepper(spec) }
        default:
            Text(row.title)
            stepper(spec).disabled(!row.appliesOnPhone)
        }
    }

    private func stepper(_ spec: SettingSpec) -> some View {
        let step = ModelSettingsModel.step(spec)
        let lo = spec.min?.doubleValue ?? 0
        let hi = spec.max?.doubleValue ?? 1e9
        return Stepper(row.valueText, value: Binding(
            get: { model.number(row.key) ?? lo },
            set: { model.setNumber(row.key, ($0 / step).rounded() * step) }), in: lo...hi, step: step)
            .font(.subheadline)
            .accessibilityIdentifier("settings.\(row.key).stepper")
    }

    @ViewBuilder
    private func textChars(_ spec: SettingSpec) -> some View {
        let mode = model.textCharsMode(spec.scope)
        Picker(row.title, selection: Binding<String>(
            get: { mode },
            set: { m in
                let n = model.number(row.key).map { Int($0) } ?? Features.shared.builtInChars(feature: spec.scope)?.intValue ?? 2_400
                model.setTextChars(row.key, mode: m, value: n)
            })) {
            Text(MS.t("text.builtin")).tag(EngineSettings.companion.TEXT_BUILTIN)
            Text(MS.t("text.global")).tag(EngineSettings.companion.TEXT_GLOBAL)
            Text(MS.t("text.custom")).tag(EngineSettings.companion.TEXT_CUSTOM)
        }
        .disabled(!row.appliesOnPhone)
        .accessibilityIdentifier("settings.\(row.key)")
        if mode == EngineSettings.companion.TEXT_CUSTOM {
            let lo = spec.min?.doubleValue ?? 100
            let hi = spec.max?.doubleValue ?? 20_000
            Stepper(row.valueText, value: Binding(
                get: { model.number(row.key) ?? lo },
                set: { model.setTextChars(row.key, mode: EngineSettings.companion.TEXT_CUSTOM, value: Int($0)) }), in: lo...hi, step: 100)
                .font(.subheadline)
                .accessibilityIdentifier("settings.\(row.key).stepper")
        }
    }
}

/// "Laya was off for this run — answers come from rules only. Turn it on": on a feature's screen
/// after a run with that feature's `use_laya` off. Turn it on opens Model settings at the feature.
struct LayaOffBanner: View {
    let feature: String
    @ObservedObject var service: ModelSettingsService = .shared
    @State private var showSettings = false

    var body: some View {
        if service.wasLayaOff(feature) {
            HStack(alignment: .top, spacing: 10) {
                Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(Palette.warnText)
                VStack(alignment: .leading, spacing: 6) {
                    Text(MS.t("banner.layaOff")).font(.subheadline).foregroundStyle(Palette.warnText)
                        .fixedSize(horizontal: false, vertical: true)
                    Button(MS.t("banner.turnOn")) { showSettings = true }
                        .font(.subheadline.weight(.semibold))
                        .accessibilityIdentifier("banner.layaOff.\(feature).turnOn")
                }
                Spacer(minLength: 0)
            }
            .padding(12)
            .background(Palette.warnSoft, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            .environment(\.layoutDirection, MS.direction)
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("banner.layaOff.\(feature)")
            .sheet(isPresented: $showSettings) {
                NavigationStack { ModelSettingsView(service: service, focus: feature, showsDone: true) }
            }
        }
    }
}
