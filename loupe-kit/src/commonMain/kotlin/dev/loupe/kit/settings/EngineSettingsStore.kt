package dev.loupe.kit.settings

import dev.loupe.persistence.JsonText
import dev.loupe.persistence.JsonValue
import dev.loupe.persistence.PlatformFiles
import dev.loupe.persistence.StoreLock

/** What one change did: the keys whose value changed, and the inputs that were refused. */
data class SettingsChange(
    val changed: List<String>,
    val errors: List<String>,
    /** Of [changed], the keys that load or unload the model (`memory_mode`, `idle_unload_min`). */
    val reload: List<String>,
) {
    val ok: Boolean get() = errors.isEmpty()
}

/**
 * The settings file, `engine_settings.json`, in the app's Application Support directory, read once
 * and rewritten atomically on every change. Thread-safe: consumers read [current] (an immutable
 * snapshot) from any thread; changes are serialised.
 *
 * A file that is missing, unreadable, from a newer version or malformed in part never fails the
 * app: every key that cannot be read keeps its default, and numbers out of range are clamped.
 */
class EngineSettingsStore(directory: String?) {
    /** Null: in memory only (tests, a phone with no writable directory). */
    val path: String? = directory?.let { if (it.endsWith("/")) it + EngineSettings.FILE_NAME else it + "/" + EngineSettings.FILE_NAME }

    private val lock = StoreLock()
    private val listeners = mutableListOf<(EngineSettings, SettingsChange) -> Unit>()

    /** Why the file could not be read or written, if it could not; the values in use are still valid. */
    var problem: String? = null
        private set

    var current: EngineSettings = EngineSettings.DEFAULTS
        private set

    init {
        directory?.let { dir ->
            runCatching { PlatformFiles.createDirectories(dir) }
            current = load()
        }
    }

    private fun load(): EngineSettings {
        val p = path ?: return EngineSettings.DEFAULTS
        val text = runCatching { PlatformFiles.readText(p) }.getOrNull() ?: return EngineSettings.DEFAULTS
        return runCatching { parseDocument(text) }.getOrElse {
            problem = "engine_settings.json could not be read (${it.message}); using the defaults"
            EngineSettings.DEFAULTS
        }
    }

    /** Called after every change that changed something, on the changing thread. */
    fun onChange(listener: (EngineSettings, SettingsChange) -> Unit) = lock.withLock { listeners += listener }

    // --- typed setters (Swift's view) ---

    fun setBool(key: String, value: Boolean): SettingsChange = set(key, JsonValue.Bool(value))

    fun setString(key: String, value: String?): SettingsChange = set(key, JsonValue.str(value))

    fun setNumber(key: String, value: Double?): SettingsChange = set(key, value?.let { EngineSettings.number(it) } ?: JsonValue.Null)

    /** `text_chars`: [mode] is [EngineSettings.TEXT_BUILTIN], [EngineSettings.TEXT_GLOBAL] or [EngineSettings.TEXT_CUSTOM]. */
    fun setTextChars(key: String, mode: String, value: Int): SettingsChange = set(
        key,
        when (mode) {
            EngineSettings.TEXT_GLOBAL -> JsonValue.Str(EngineSettings.TEXT_GLOBAL)
            EngineSettings.TEXT_CUSTOM -> JsonValue.num(value)
            else -> JsonValue.Null
        },
    )

    fun set(key: String, value: JsonValue): SettingsChange = change(mapOf(key to value), emptyList(), resetAll = false)

    /** Station's `reset`: each key back to its default. */
    fun reset(keys: List<String>): SettingsChange = change(emptyMap(), keys, resetAll = false)

    fun reset(key: String): SettingsChange = reset(listOf(key))

    /** Station's `reset_all`: every key back to its default. */
    fun resetAll(): SettingsChange = change(emptyMap(), emptyList(), resetAll = true)

    /**
     * Station's PUT body: `{"global":{partial},"features":{"scan":{partial},…},"reset":[keys],
     * "reset_all":true}`. `reset_all` first, then `reset`, then the values.
     */
    fun put(body: String): SettingsChange {
        val root = runCatching { JsonValue.parse(body) as JsonValue.Obj }.getOrElse {
            return SettingsChange(emptyList(), listOf("the body is not a JSON object"), emptyList())
        }
        val errors = mutableListOf<String>()
        val sets = LinkedHashMap<String, JsonValue>()
        (root["global"] as? JsonValue.Obj)?.fields?.forEach { (k, v) -> sets["global.$k"] = v }
        (root["features"] as? JsonValue.Obj)?.fields?.forEach { (f, obj) ->
            if (obj is JsonValue.Obj) obj.fields.forEach { (k, v) -> sets["features.$f.$k"] = v } else errors += "features.$f: not an object"
        }
        val resets = (root["reset"] as? JsonValue.Arr)?.items?.mapNotNull { (it as? JsonValue.Str)?.value }.orEmpty()
        val all = (root["reset_all"] as? JsonValue.Bool)?.value == true
        val c = change(sets, resets, all)
        return c.copy(errors = errors + c.errors)
    }

    /**
     * Station's GET body: `{"version":1,"settings":{…},"defaults":{…},"env":{},"reload":[…]}`.
     * `env` is always empty on iPhone: there is no environment or MDM override here.
     */
    fun get(): String = JsonText.compact(
        JsonValue.obj(
            "version" to JsonValue.num(EngineSettings.VERSION),
            "settings" to current.settingsJson(),
            "defaults" to EngineSettings.DEFAULTS.settingsJson(),
            "env" to JsonValue.obj(),
            "reload" to JsonValue.strings(EngineSettings.RELOAD),
        ),
    )

    /** The document as written to disk. */
    fun document(): String = JsonText.pretty(current.documentJson())

    private fun change(sets: Map<String, JsonValue>, resets: List<String>, resetAll: Boolean): SettingsChange {
        val (next, change, notify) = lock.withLock {
            val before = current
            val values = LinkedHashMap(before.values)
            val errors = mutableListOf<String>()
            if (resetAll) EngineSettings.SPECS.forEach { values[it.key] = it.default }
            for (key in resets) {
                val spec = EngineSettings.spec(key)
                if (spec == null) errors += "$key: unknown setting" else values[key] = spec.default
            }
            for ((key, raw) in sets) {
                val spec = EngineSettings.spec(key)
                if (spec == null) {
                    errors += "$key: unknown setting"
                    continue
                }
                val valid = EngineSettings.validate(spec, raw)
                if (valid == null) errors += "$key: ${describe(spec)}" else values[key] = valid
            }
            val next = EngineSettings(values)
            val changed = EngineSettings.SPECS.map { it.key }.filter { before.value(it) != next.value(it) }
            val change = SettingsChange(changed, errors, changed.filter { it in EngineSettings.RELOAD })
            if (changed.isNotEmpty()) {
                current = next
                persist(next)
            }
            Triple(next, change, if (changed.isEmpty()) emptyList() else listeners.toList())
        }
        notify.forEach { it(next, change) }
        return change
    }

    private fun persist(s: EngineSettings) {
        val p = path ?: return
        runCatching { PlatformFiles.writeAtomically(p, JsonText.pretty(s.documentJson()) + "\n") }
            .onSuccess { problem = null }
            .onFailure { problem = "engine_settings.json could not be saved (${it.message}); the change applies until the app quits" }
    }

    companion object {
        /** What a key accepts, for an error line. */
        fun describe(spec: SettingSpec): String = when (spec.kind) {
            SettingKind.BOOL -> "must be true or false"
            SettingKind.ENUM -> "must be one of ${spec.choices.joinToString()}"
            SettingKind.NULLABLE_ENUM -> "must be null or one of ${spec.choices.joinToString()}"
            SettingKind.NUMBER -> "must be a number from ${fmt(spec.min)} to ${fmt(spec.max)}"
            SettingKind.INT -> "must be a whole number from ${fmt(spec.min)} to ${fmt(spec.max)}"
            SettingKind.NULLABLE_NUMBER -> "must be null or a number from ${fmt(spec.min)} to ${fmt(spec.max)}"
            SettingKind.TEXT_CHARS -> "must be null, \"global\" or a whole number from ${fmt(spec.min)} to ${fmt(spec.max)}"
        }

        private fun fmt(v: Double?): String = v?.let { EngineSettings.number(it).asString } ?: "?"

        /**
         * A document (or a bare `settings` object) to settings: every known key read and validated,
         * anything missing or invalid at its default, unknown keys and features ignored.
         */
        fun parseDocument(text: String): EngineSettings {
            val root = JsonValue.parse(text) as? JsonValue.Obj ?: throw IllegalArgumentException("not a JSON object")
            val settings = (root["settings"] as? JsonValue.Obj) ?: root
            val values = LinkedHashMap<String, JsonValue>()
            val global = settings["global"] as? JsonValue.Obj
            val features = settings["features"] as? JsonValue.Obj
            for (spec in EngineSettings.SPECS) {
                val raw = if (spec.isGlobal) global?.get(spec.name) else (features?.get(spec.scope) as? JsonValue.Obj)?.get(spec.name)
                values[spec.key] = raw?.let { EngineSettings.validate(spec, it) } ?: spec.default
            }
            return EngineSettings(values)
        }
    }
}
