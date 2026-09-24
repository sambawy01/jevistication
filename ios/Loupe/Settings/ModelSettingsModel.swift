import Combine
import Foundation
import LoupeKit

/// One row of Model settings as the screen shows it: the current value, the default, the one-line
/// trade-off and whether Reset has anything to do.
struct SettingRow: Identifiable, Equatable {
    let key: String
    var id: String { key }
    let scope: String
    let name: String
    let title: String
    let tradeOff: String
    let valueText: String
    let defaultText: String
    let isDefault: Bool
    /// False for keys kept only for parity with Loupe Station (the control is shown disabled).
    let appliesOnPhone: Bool
    /// An extra line: how the key maps on iPhone, or when it applies.
    let note: String?
}

/// A section: "Everywhere", or one feature.
struct SettingSection: Identifiable, Equatable {
    let scope: String
    var id: String { scope }
    let title: String
    let hint: String
    let rows: [SettingRow]
}

/// The Model settings screen's view model: rows built from LoupeKit's schema and the current values,
/// in the phone's language, and the actions (set, reset one, restore all). Everything it writes goes
/// through `ModelSettingsService`, which persists and applies it.
@MainActor
final class ModelSettingsModel: ObservableObject {
    let service: ModelSettingsService

    private var forward: AnyCancellable?

    init(service: ModelSettingsService) {
        self.service = service
        forward = service.objectWillChange.sink { [weak self] _ in self?.objectWillChange.send() }
    }

    var settings: EngineSettings { service.settings }

    static var specsByScope: [String: [SettingSpec]] {
        Dictionary(grouping: EngineSettings.companion.SPECS, by: \.scope)
    }

    /// Global first, then the phone's features in their order (the Playground last, desktop-only).
    var sections: [SettingSection] {
        let g = EngineSettings.companion.GLOBAL
        var out = [SettingSection(scope: g, title: MS.t("global"), hint: MS.t("globalHint"), rows: rows(g))]
        for f in Features.shared.ON_PHONE + [Features.shared.PLAYGROUND] {
            out.append(SettingSection(scope: f, title: MS.t("feat.\(f)"), hint: MS.t("feat.\(f).hint"), rows: f == Features.shared.PLAYGROUND ? [] : rows(f)))
        }
        return out
    }

    func rows(_ scope: String) -> [SettingRow] {
        EngineSettings.companion.specs(scope: scope).map(row)
    }

    func row(_ key: String) -> SettingRow? { EngineSettings.companion.spec(key: key).map(row) }

    func row(_ spec: SettingSpec) -> SettingRow {
        let value = settings.value(key: spec.key)
        return SettingRow(
            key: spec.key, scope: spec.scope, name: spec.name,
            title: title(spec),
            tradeOff: tradeOff(spec),
            valueText: Self.text(spec, value, settings),
            defaultText: Self.text(spec, spec.default_, EngineSettings.companion.DEFAULTS),
            isDefault: settings.isDefault(key: spec.key),
            appliesOnPhone: spec.onPhone == .applies,
            note: note(spec))
    }

    private func title(_ spec: SettingSpec) -> String {
        spec.isGlobal ? MS.t("g.\(spec.name)") : MS.t("fk.\(spec.name)")
    }

    /// The plain-language trade-off (speed vs accuracy vs memory and battery).
    private func tradeOff(_ spec: SettingSpec) -> String {
        if spec.isGlobal {
            var s = MS.t("g.\(spec.name).desc")
            if spec.name == "memory_mode" { s += " " + MS.t("mem.desc.\(settings.memoryMode)") }
            return s
        }
        let specific = "f.\(spec.scope).\(spec.name)"
        if MS.has(specific) { return MS.t(specific) }
        if spec.name == "text_chars", !Features.shared.usesLayaOnPhone(feature: spec.scope) { return MS.t("f.text_chars.none") }
        return MS.t("f.\(spec.name)")
    }

    private func note(_ spec: SettingSpec) -> String? {
        switch spec.name {
        case "routing": return MS.t("route.phone")
        case "bias_correction": return MS.t("bias.phone")
        case "memory_mode", "idle_unload_min": return MS.t("applies.\(spec.name)")
        default: break
        }
        if spec.onPhone == .desktopOnly { return MS.t("desktopOnly") }
        if spec.mobileOnly && spec.scope == Features.shared.GAME && spec.name != "use_laya" && spec.name != "routing" && spec.name != "text_chars" {
            return MS.t("mobileOnly")
        }
        return nil
    }

    /// An enum choice as the screen says it.
    static func choiceLabel(_ spec: SettingSpec, _ choice: String) -> String {
        switch spec.name {
        case "memory_mode": return MS.t("mem.\(choice)")
        case "bias_correction": return MS.t("bias.\(choice)")
        default: return MS.t("route.\(choice)")
        }
    }

    /// A value as the screen says it.
    static func text(_ spec: SettingSpec, _ value: JsonValue, _ settings: EngineSettings) -> String {
        let num = (value as? JsonValueNum).flatMap { Double($0.text) }
        let str = (value as? JsonValueStr)?.value
        switch spec.kind {
        case .bool_:
            return MS.t((value as? JsonValueBool)?.value == true ? "v.on" : "v.off")
        case .enum_:
            return choiceLabel(spec, str ?? "")
        case .nullableEnum:
            guard let str else { return MS.t("route.follow", ["route": MS.t("route.\(settings.routing)")]) }
            return MS.t("route.\(str)")
        case .textChars:
            if str == EngineSettings.companion.TEXT_GLOBAL { return MS.t("text.global") + " · " + MS.t("unit.chars", ["n": MS.number(Double(settings.textCharsMultilingual))]) }
            if let num { return MS.t("unit.chars", ["n": MS.number(num)]) }
            if let n = Features.shared.builtInChars(feature: spec.scope) { return MS.t("text.builtinN", ["n": MS.number(Double(truncating: n))]) }
            return MS.t("text.builtin")
        case .nullableNumber:
            guard let num else { return MS.t(spec.name == "max_decisions_per_s" ? "v.noCap" : "v.featureRule") }
            if spec.name == "max_decisions_per_s" { return MS.t("unit.perS", ["n": MS.number(num)]) }
            return String(format: "%.2f", num)
        default:
            guard let num else { return "—" }
            switch spec.name {
            case "idle_unload_min": return num == 0 ? MS.t("unit.never") : MS.t("unit.min", ["n": MS.number(num)])
            case "content_budget_s", "time_limit_s": return MS.t("unit.s", ["n": MS.number(num)])
            case "queue_size", "ocr_max_pages": return MS.t("unit.pages", ["n": MS.number(num)])
            default: return MS.t("unit.chars", ["n": MS.number(num)])
            }
        }
    }

    // MARK: Steps for the steppers

    /// The stepper's increment for a number key.
    static func step(_ spec: SettingSpec) -> Double {
        switch spec.name {
        case "accept_confidence": return 0.05
        case "idle_unload_min", "time_limit_s", "queue_size", "ocr_max_pages": return 1
        case "content_budget_s": return 5
        case "max_decisions_per_s": return 0.5
        default: return 100   // characters
        }
    }

    /// Where a nullable number starts when it is switched on.
    static func startValue(_ spec: SettingSpec) -> Double {
        switch spec.name {
        case "accept_confidence": return 0.6
        case "max_decisions_per_s": return 5
        default: return spec.min?.doubleValue ?? 0
        }
    }

    // MARK: Actions

    func setBool(_ key: String, _ on: Bool) { service.setBool(key, on) }
    func setChoice(_ key: String, _ value: String?) { service.setString(key, value) }
    func setNumber(_ key: String, _ value: Double?) { service.setNumber(key, value) }
    func setTextChars(_ key: String, mode: String, value: Int) { service.setTextChars(key, mode: mode, value: value) }
    func reset(_ key: String) { service.reset(key) }
    func restoreAll() { service.restoreAll() }

    func number(_ key: String) -> Double? { settings.number(key: key)?.doubleValue }
    func bool(_ key: String) -> Bool { settings.bool(key: key) }
    func string(_ key: String) -> String? { settings.string(key: key) }
    func textCharsMode(_ feature: String) -> String { settings.textCharsMode(feature: feature) }
    var anyChanged: Bool { !settings.changed.isEmpty }
}
