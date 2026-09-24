package dev.loupe.kit.settings

import dev.loupe.persistence.JsonValue
import dev.loupe.templates.UserJudgment

/**
 * Model settings (schema v1): how Laya is used, the same keys, types, enums, ranges and defaults as
 * Loupe Station's `engine_settings.json` (docs/MODEL-SETTINGS.md), plus the iPhone's own features.
 *
 * Keys are addressed flat, as Station's PUT `reset` list addresses them: `global.routing`,
 * `features.scan.use_laya`. The document on disk is `{"version":1,"settings":{"global":{…},
 * "features":{…}}}`.
 *
 * Every default reproduces what the app did before the settings existed, except `memory_mode`
 * (Station's `balanced`, an owner decision; the phone used to keep Laya loaded, which is `full`).
 */
enum class SettingKind {
    BOOL,

    /** One of [SettingSpec.choices]. */
    ENUM,

    /** Null (follow the global value) or one of [SettingSpec.choices]. */
    NULLABLE_ENUM,

    /** A number clamped to [SettingSpec.min]..[SettingSpec.max]. */
    NUMBER,

    /** A whole number clamped to the range. */
    INT,

    /** Null (off / current behaviour) or a number clamped to the range. */
    NULLABLE_NUMBER,

    /** Null (the feature's built-in limit), `"global"`, or a whole number clamped to the range. */
    TEXT_CHARS,
}

/** Whether a key does anything on this iPhone. */
enum class OnPhone {
    /** Read live by the feature. */
    APPLIES,

    /** Kept for parity with Station; this phone has nothing it could change (see [SettingSpec.note]). */
    DESKTOP_ONLY,
}

/** One key: its type, range and default, exactly as Station's schema v1 (or a mobile addition). */
data class SettingSpec(
    /** `global.<name>` or `features.<feature>.<name>`. */
    val key: String,
    /** `global` or the feature id. */
    val scope: String,
    val name: String,
    val kind: SettingKind,
    val default: JsonValue,
    val min: Double? = null,
    val max: Double? = null,
    val choices: List<String> = emptyList(),
    /** Applies at once with a load or unload (`memory_mode`) or at the next tick (`idle_unload_min`). */
    val reload: Boolean = false,
    val onPhone: OnPhone = OnPhone.APPLIES,
    /** True for keys the iPhone added (not in Station's schema v1). */
    val mobileOnly: Boolean = false,
    /** Why a key is desktop-only or how it maps on the phone, in one line. */
    val note: String = "",
) {
    val isGlobal: Boolean get() = scope == EngineSettings.GLOBAL

    /** The default as a plain value for Swift: Boolean, String, Double, or null. */
    val defaultBool: Boolean get() = (default as? JsonValue.Bool)?.value ?: false
    val defaultString: String? get() = (default as? JsonValue.Str)?.value
    val defaultNumber: Double? get() = (default as? JsonValue.Num)?.text?.toDoubleOrNull()
}

/** Per-feature ids: Station's five, then the phone's own. */
object Features {
    const val SCAN = "scan"
    const val EMAIL = "email"
    const val BROWSER = "browser"
    const val WATCHERS = "watchers"
    const val PLAYGROUND = "playground"
    const val JUDGMENTS = "judgments"
    const val FLIGHTS = "flights"
    const val GAME = "game"

    val STATION: List<String> = listOf(SCAN, EMAIL, BROWSER, WATCHERS, PLAYGROUND)
    val MOBILE: List<String> = listOf(JUDGMENTS, FLIGHTS, GAME)
    val ALL: List<String> = STATION + MOBILE

    /** Shown in the iPhone's Model settings (playground is desktop-only and listed as such). */
    val ON_PHONE: List<String> = listOf(JUDGMENTS, SCAN, EMAIL, BROWSER, WATCHERS, FLIGHTS, GAME)

    /**
     * The built-in text limit (characters) when `text_chars` is null, or null where Laya reads no
     * text on the phone today (the privacy check, mail triage and site checks are rules only).
     */
    fun builtInChars(feature: String): Int? = when (feature) {
        JUDGMENTS, WATCHERS -> 4_000   // DecisionEngine.DEFAULT_STATE_BUDGET
        FLIGHTS -> 480                  // FlightState.BUDGET
        GAME -> 600                     // ModelPilot.STATE_BUDGET
        else -> null
    }

    /** Features whose runs on the phone ask Laya today (the others are rules only). */
    fun usesLayaOnPhone(feature: String): Boolean = feature in setOf(JUDGMENTS, WATCHERS, FLIGHTS, GAME)
}

/**
 * What one run of one feature reads from the settings, resolved: the feature's own value where it
 * sets one, the global one otherwise. Consumers take this (a snapshot) at the start of a run.
 */
data class RunPolicy(
    val feature: String,
    val useLaya: Boolean,
    /** What the settings ask for (`auto`, `english`, `multilingual`). */
    val requestedRouting: String,
    /** What the phone runs: always `multilingual`, the only checkpoint it ships. */
    val routing: String,
    val acceptConfidence: Double?,
    val useCalibration: Boolean,
    val rulesFirst: Boolean,
    val baselineSwitch: Boolean,
    /** Characters of text per item, or null for the feature's built-in limit. */
    val textChars: Int?,
) {
    /** The text budget: the setting's, or [builtIn] when the feature uses its own. */
    fun budget(builtIn: Int): Int = textChars ?: builtIn

    /**
     * The acceptance threshold. A per-question [override] (a threshold set on Measure) wins; then
     * `accept_confidence`; then the feature's [builtIn] rule — the old behaviour when it is null.
     */
    fun threshold(builtIn: Double, override: Double? = null): Double = override ?: acceptConfidence ?: builtIn

    /**
     * For a judgment: its threshold counts as set on Measure when it differs from the starting
     * threshold of its shape; otherwise `accept_confidence` (when set) replaces the starting one.
     */
    fun thresholdFor(judgment: UserJudgment): Double {
        val start = UserJudgment.defaultThreshold(judgment.shape)
        val override = judgment.threshold.takeIf { it != start }
        return threshold(judgment.threshold, override)
    }

    companion object {
        /** The policy the defaults give [feature]: what every consumer did before the settings. */
        fun defaults(feature: String): RunPolicy = EngineSettings.DEFAULTS.policy(feature)
    }
}

/**
 * One immutable snapshot of every key's value. Read it with the typed accessors or [policy];
 * change it through [EngineSettingsStore].
 */
class EngineSettings internal constructor(internal val values: Map<String, JsonValue>) {

    fun value(key: String): JsonValue = values[key] ?: spec(key)?.default ?: JsonValue.Null

    fun bool(key: String): Boolean = (value(key) as? JsonValue.Bool)?.value ?: false

    fun string(key: String): String? = (value(key) as? JsonValue.Str)?.value

    fun number(key: String): Double? = (value(key) as? JsonValue.Num)?.text?.toDoubleOrNull()

    fun isDefault(key: String): Boolean = value(key) == spec(key)?.default

    /** Keys whose value differs from the default. */
    val changed: List<String> get() = SPECS.map { it.key }.filter { !isDefault(it) }

    // --- global ---
    val routing: String get() = string("global.routing") ?: ROUTING_AUTO
    val memoryMode: String get() = string("global.memory_mode") ?: MEMORY_BALANCED
    val idleUnloadMin: Double get() = number("global.idle_unload_min") ?: 10.0
    val acceptConfidence: Double? get() = number("global.accept_confidence")
    val useCalibration: Boolean get() = bool("global.use_calibration")
    val rulesFirst: Boolean get() = bool("global.rules_first")
    val baselineSwitch: Boolean get() = bool("global.baseline_switch")
    val textCharsEnglish: Int get() = number("global.text_chars_english")?.toInt() ?: 1_400
    val textCharsMultilingual: Int get() = number("global.text_chars_multilingual")?.toInt() ?: 2_400

    // --- per feature ---
    fun useLaya(feature: String): Boolean = bool("features.$feature.use_laya")

    /** Null: the feature follows `global.routing`. */
    fun featureRouting(feature: String): String? = string("features.$feature.routing")

    /** The raw `text_chars`: null (built-in), `"global"`, or a number. */
    fun textCharsMode(feature: String): String = when (val v = value("features.$feature.text_chars")) {
        is JsonValue.Str -> TEXT_GLOBAL
        is JsonValue.Num -> TEXT_CUSTOM
        else -> TEXT_BUILTIN
    }

    fun textCharsCustom(feature: String): Int? = number("features.$feature.text_chars")?.toInt()

    /**
     * Characters per item for [feature], or null for its built-in limit. `"global"` reads the
     * multilingual limit: the phone ships only the multilingual checkpoint, so every text goes to it
     * (`text_chars_english` is kept for parity and is unused here).
     */
    fun textChars(feature: String): Int? = when (textCharsMode(feature)) {
        TEXT_GLOBAL -> textCharsMultilingual
        TEXT_CUSTOM -> textCharsCustom(feature)
        else -> null
    }

    val readContent: Boolean get() = bool("features.scan.read_content")
    val contentBudgetS: Double get() = number("features.scan.content_budget_s") ?: 60.0
    val browserTimeLimitS: Double get() = number("features.browser.time_limit_s") ?: 5.0
    val browserQueueSize: Int get() = number("features.browser.queue_size")?.toInt() ?: 2

    /** `features.game.max_decisions_per_s`: null = no cap (every decision the session asks for). */
    val gameMaxDecisionsPerS: Double? get() = number("features.game.max_decisions_per_s")

    /** What the routing asked for runs as on the phone: always the multilingual checkpoint. */
    fun effectiveRouting(feature: String): String = PHONE_ROUTING

    fun policy(feature: String): RunPolicy = RunPolicy(
        feature = feature,
        useLaya = useLaya(feature),
        requestedRouting = featureRouting(feature) ?: routing,
        routing = effectiveRouting(feature),
        acceptConfidence = acceptConfidence,
        useCalibration = useCalibration,
        rulesFirst = rulesFirst,
        baselineSwitch = baselineSwitch,
        textChars = textChars(feature),
    )

    /** The `settings` object: `{"global":{…},"features":{…}}`, keys in schema order. */
    fun settingsJson(): JsonValue.Obj {
        val global = LinkedHashMap<String, JsonValue>()
        val features = LinkedHashMap<String, JsonValue>()
        for (s in SPECS) {
            if (s.isGlobal) {
                global[s.name] = value(s.key)
            } else {
                val f = features.getOrPut(s.scope) { JsonValue.Obj(LinkedHashMap()) } as JsonValue.Obj
                f.fields[s.name] = value(s.key)
            }
        }
        return JsonValue.obj("global" to JsonValue.Obj(global), "features" to JsonValue.Obj(features))
    }

    /** The document on disk: `{"version":1,"settings":{…}}`. */
    fun documentJson(): JsonValue.Obj = JsonValue.obj("version" to JsonValue.num(VERSION), "settings" to settingsJson())

    override fun equals(other: Any?): Boolean = other is EngineSettings && other.values == values
    override fun hashCode(): Int = values.hashCode()
    override fun toString(): String = "EngineSettings(changed=$changed)"

    companion object {
        const val VERSION: Int = 1
        const val FILE_NAME: String = "engine_settings.json"
        const val GLOBAL: String = "global"

        const val ROUTING_AUTO = "auto"
        const val ROUTING_ENGLISH = "english"
        const val ROUTING_MULTILINGUAL = "multilingual"
        val ROUTINGS: List<String> = listOf(ROUTING_AUTO, ROUTING_ENGLISH, ROUTING_MULTILINGUAL)

        /** The phone ships one checkpoint: Laya multilingual. */
        const val PHONE_ROUTING = ROUTING_MULTILINGUAL

        const val MEMORY_FULL = "full"
        const val MEMORY_BALANCED = "balanced"
        const val MEMORY_LOW = "low"
        val MEMORY_MODES: List<String> = listOf(MEMORY_FULL, MEMORY_BALANCED, MEMORY_LOW)

        /** [textCharsMode] values. */
        const val TEXT_BUILTIN = "builtin"
        const val TEXT_GLOBAL = "global"
        const val TEXT_CUSTOM = "custom"

        private fun b(v: Boolean) = JsonValue.Bool(v)
        private fun s(v: String) = JsonValue.Str(v)
        private fun n(v: Int) = JsonValue.num(v)
        private fun n(v: Double) = JsonValue.num(v)
        private val NULL = JsonValue.Null

        private fun global(name: String, kind: SettingKind, default: JsonValue, min: Double? = null, max: Double? = null,
                           choices: List<String> = emptyList(), reload: Boolean = false, onPhone: OnPhone = OnPhone.APPLIES, note: String = "") =
            SettingSpec("global.$name", GLOBAL, name, kind, default, min, max, choices, reload, onPhone, false, note)

        private fun feature(id: String, name: String, kind: SettingKind, default: JsonValue, min: Double? = null, max: Double? = null,
                            choices: List<String> = emptyList(), onPhone: OnPhone = OnPhone.APPLIES, mobileOnly: Boolean = false, note: String = "") =
            SettingSpec("features.$id.$name", id, name, kind, default, min, max, choices, false, onPhone, mobileOnly || id in Features.MOBILE, note)

        private fun common(id: String): List<SettingSpec> {
            val phone = if (id == Features.PLAYGROUND) OnPhone.DESKTOP_ONLY else OnPhone.APPLIES
            val textNote = when {
                id == Features.PLAYGROUND -> "The Playground is on Loupe Station only."
                !Features.usesLayaOnPhone(id) -> "Laya reads no text for this feature on iPhone yet (it runs on rules), so the limit changes nothing here."
                else -> ""
            }
            val textPhone = if (textNote.isEmpty()) phone else OnPhone.DESKTOP_ONLY
            return listOf(
                feature(id, "use_laya", SettingKind.BOOL, b(true), onPhone = phone,
                    note = if (id != Features.PLAYGROUND && !Features.usesLayaOnPhone(id))
                        "On iPhone this feature runs on rules today; off shows the rules-only banner on its screen." else ""),
                feature(id, "routing", SettingKind.NULLABLE_ENUM, NULL, choices = ROUTINGS, onPhone = textPhone,
                    note = "iPhone ships one model (multilingual): Automatic and Multilingual both run it; English is not on iPhone."),
                feature(id, "text_chars", SettingKind.TEXT_CHARS, NULL, 100.0, 20_000.0, onPhone = textPhone, note = textNote),
            )
        }

        /** Every key, in the document's order: global, then each feature (Station's, then the phone's). */
        val SPECS: List<SettingSpec> = buildList {
            add(global("routing", SettingKind.ENUM, s(ROUTING_AUTO), choices = ROUTINGS,
                note = "iPhone ships one model (multilingual): Automatic and Multilingual both run it; English is not on iPhone."))
            add(global("memory_mode", SettingKind.ENUM, s(MEMORY_BALANCED), choices = MEMORY_MODES, reload = true))
            add(global("idle_unload_min", SettingKind.NUMBER, n(10), 0.0, 1_440.0, reload = true))
            add(global("accept_confidence", SettingKind.NULLABLE_NUMBER, NULL, 0.05, 0.99))
            add(global("use_calibration", SettingKind.BOOL, b(true),
                note = "No calibration is fitted on iPhone yet, so on and off both use Laya's raw confidence today."))
            add(global("rules_first", SettingKind.BOOL, b(true)))
            add(global("baseline_switch", SettingKind.BOOL, b(true)))
            add(global("text_chars_english", SettingKind.INT, n(1_400), 200.0, 20_000.0, onPhone = OnPhone.DESKTOP_ONLY,
                note = "iPhone has no English model, so this limit is unused here."))
            add(global("text_chars_multilingual", SettingKind.INT, n(2_400), 200.0, 20_000.0))

            addAll(common(Features.SCAN))
            add(feature(Features.SCAN, "read_content", SettingKind.BOOL, b(true)))
            add(feature(Features.SCAN, "content_budget_s", SettingKind.NUMBER, n(60), 1.0, 600.0, onPhone = OnPhone.DESKTOP_ONLY,
                note = "iPhone reads each file once, when Sources scans it, within the extractor's own limits."))
            addAll(common(Features.EMAIL))
            addAll(common(Features.BROWSER))
            add(feature(Features.BROWSER, "time_limit_s", SettingKind.NUMBER, n(5), 1.0, 60.0, onPhone = OnPhone.DESKTOP_ONLY,
                note = "Site checks on iPhone are rules only; Laya does not read pages here."))
            add(feature(Features.BROWSER, "queue_size", SettingKind.INT, n(2), 0.0, 10.0, onPhone = OnPhone.DESKTOP_ONLY,
                note = "Site checks on iPhone are rules only; Laya does not read pages here."))
            addAll(common(Features.WATCHERS))
            addAll(common(Features.PLAYGROUND))
            addAll(common(Features.JUDGMENTS))
            addAll(common(Features.FLIGHTS))
            addAll(common(Features.GAME))
            add(feature(Features.GAME, "max_decisions_per_s", SettingKind.NULLABLE_NUMBER, NULL, 0.5, 60.0))
        }

        private val BY_KEY: Map<String, SettingSpec> = SPECS.associateBy { it.key }

        fun spec(key: String): SettingSpec? = BY_KEY[key]

        fun specs(scope: String): List<SettingSpec> = SPECS.filter { it.scope == scope }

        /** Keys that load or unload the model rather than apply to the next decision (Station's `reload`). */
        val RELOAD: List<String> = SPECS.filter { it.reload }.map { it.key }

        val DEFAULTS: EngineSettings = EngineSettings(SPECS.associate { it.key to it.default })

        /**
         * [raw] made valid for [spec], or null when it cannot be (wrong type, unknown choice).
         * Numbers are clamped to the range; whole-number keys are rounded first.
         */
        fun validate(spec: SettingSpec, raw: JsonValue): JsonValue? {
            fun clamp(v: Double, whole: Boolean): JsonValue {
                if (v.isNaN()) return spec.default
                var x = if (whole) kotlin.math.round(v) else v
                spec.min?.let { if (x < it) x = it }
                spec.max?.let { if (x > it) x = it }
                return number(x)
            }
            val num = (raw as? JsonValue.Num)?.text?.toDoubleOrNull()
            return when (spec.kind) {
                SettingKind.BOOL -> raw as? JsonValue.Bool
                SettingKind.ENUM -> (raw as? JsonValue.Str)?.takeIf { it.value in spec.choices }
                SettingKind.NULLABLE_ENUM -> if (raw is JsonValue.Null) raw else (raw as? JsonValue.Str)?.takeIf { it.value in spec.choices }
                SettingKind.NUMBER -> num?.let { clamp(it, false) }
                SettingKind.INT -> num?.let { clamp(it, true) }
                SettingKind.NULLABLE_NUMBER -> if (raw is JsonValue.Null) raw else num?.let { clamp(it, false) }
                SettingKind.TEXT_CHARS -> when {
                    raw is JsonValue.Null -> raw
                    raw is JsonValue.Str && raw.value == TEXT_GLOBAL -> raw
                    num != null -> clamp(num, true)
                    else -> null
                }
            }
        }

        /** A number as JSON text: whole numbers without a fraction (`10`, not `10.0`), as Station writes them. */
        fun number(v: Double): JsonValue =
            if (v == kotlin.math.floor(v) && kotlin.math.abs(v) < 1e15) JsonValue.Num(v.toLong().toString()) else JsonValue.num(v)
    }
}
