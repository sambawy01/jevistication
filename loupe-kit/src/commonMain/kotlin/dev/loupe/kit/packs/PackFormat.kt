package dev.loupe.kit.packs

import dev.loupe.persistence.JsonText
import dev.loupe.persistence.JsonValue

/*
 * Preset packs: importable JSON bundles of Laya questions, for anything specific to one business.
 *
 * PROVENANCE: ported from the owner's Loupe Station repository (`~/laya-studio`,
 * `laya_studio/packs.py` and the question rules of `laya_studio/schemas.py` (ChoiceQuestion,
 * ScoreQuestion, NoulQuestion, PredictRequest), commit ea7697a4f78e9a648ba49fc8bf9d13226c26cb0e).
 * The pack format (`laya-preset-pack`, version 1), every limit (512 000 bytes, 100 presets, 60 /
 * 80 / 300 characters, 20 questions, 2-20 options, 600-character instructions, 100-character labels,
 * 1 000-character descriptions), the slug / preset-id / question-id patterns, `slugify`, the
 * "strip our own namespace" rule, the Arabic `translations` block and the error locations
 * (`presets.2.questions.team`) are copied. Station's messages are kept where Station writes them;
 * where Station relays a pydantic message, the message here is Loupe's own wording (the location
 * is the same). Lengths count code points, as Python does.
 *
 * What Loupe adds on top (PackJudgments.kt): every question also passes the C2 lint before it can
 * become a judgment. A pack that Station refuses is refused here, whole; a question Station accepts
 * but the lint refuses is shown in the preview and not added.
 */

/** One problem, located like Station's 422s: `presets.0.questions.team: must be ...`. */
data class PackProblem(val loc: List<String>, val msg: String) {
    val line: String get() = (loc.joinToString(".").ifEmpty { "pack" }) + ": " + msg
}

/** A Station question, normalised. [options] are the choice labels, the score levels, or empty for yes/no. */
data class PackQuestion(
    val id: String,
    /** "choice", "score" or "noul" (Laya's yes/no). */
    val type: String,
    val instructions: String,
    val options: List<String>,
    /** Choice: label -> description (null when none). Noul: "true"/"false" -> description. */
    val descriptions: Map<String, String?>,
    /** The question exactly as it was in the pack, for export back out. */
    val raw: JsonValue,
)

data class PackPreset(
    val id: String,
    val name: String,
    val description: String,
    val stateKey: String?,
    val sampleText: String,
    val model: String,
    val questions: List<PackQuestion>,
    /** "ar" -> {"name": ..., "description": ...}. */
    val translations: Map<String, Map<String, String>>,
)

data class Pack(
    val name: String,
    val slug: String,
    val description: String,
    val presets: List<PackPreset>,
) {
    val questionCount: Int get() = presets.sumOf { it.questions.size }

    /** The normalised pack as Station stores it (re-importable anywhere). */
    fun toJson(): JsonValue.Obj = JsonValue.obj(
        "format" to JsonValue.Str(PackFormat.FORMAT),
        "version" to JsonValue.num(PackFormat.VERSION),
        "name" to JsonValue.Str(name),
        "slug" to JsonValue.Str(slug),
        "description" to JsonValue.Str(description),
        "presets" to JsonValue.Arr(presets.map { p ->
            val fields = linkedMapOf<String, JsonValue>(
                "id" to JsonValue.Str(p.id),
                "name" to JsonValue.Str(p.name),
                "description" to JsonValue.Str(p.description),
                "state_key" to JsonValue.str(p.stateKey),
                "sample_text" to JsonValue.Str(p.sampleText),
                "model" to JsonValue.Str(p.model),
                "questions" to JsonValue.Obj(LinkedHashMap(p.questions.associate { it.id to it.raw })),
            )
            if (p.translations.isNotEmpty()) {
                fields["translations"] = JsonValue.Obj(LinkedHashMap(p.translations.mapValues { (_, v) ->
                    JsonValue.Obj(LinkedHashMap(v.mapValues { JsonValue.Str(it.value) })) as JsonValue
                }))
            }
            JsonValue.Obj(fields)
        }),
    )

    fun toText(): String = JsonText.pretty(toJson())
}

/** Validation result: the normalised pack, or every problem found (Station's PackError). */
sealed class PackParse {
    class Valid(val pack: Pack) : PackParse()

    class Invalid(val problems: List<PackProblem>) : PackParse() {
        val lines: List<String> get() = problems.map { it.line }
    }
}

object PackFormat {
    const val FORMAT: String = "laya-preset-pack"
    const val VERSION: Int = 1
    const val MAX_PACK_BYTES: Int = 512_000
    const val MAX_PRESETS: Int = 100
    const val MAX_NAME_CHARS: Int = 60
    const val MAX_PRESET_NAME_CHARS: Int = 80
    const val MAX_DESCRIPTION_CHARS: Int = 300
    const val MAX_TEXT_CHARS: Int = 20_000

    // schemas.py
    const val MAX_QUESTIONS: Int = 20
    const val MAX_OPTIONS: Int = 20
    const val MAX_INSTRUCTIONS_CHARS: Int = 600
    const val MAX_LABEL_CHARS: Int = 100
    const val MAX_LEVEL_CHARS: Int = 1_000

    const val SLUG_PATTERN: String = "^[a-z0-9](?:[a-z0-9-]{0,38}[a-z0-9])?$"
    const val PRESET_ID_PATTERN: String = "^[a-z0-9][a-z0-9_-]{0,63}$"
    const val ID_PATTERN: String = "^[A-Za-z0-9_\\-]{1,64}$"
    val MODELS: List<String> = listOf("auto", "english", "multilingual")
    val TRANSLATION_LANGS: List<String> = listOf("ar")

    private val SLUG_RE = Regex(SLUG_PATTERN)
    private val PRESET_ID_RE = Regex(PRESET_ID_PATTERN)
    private val ID_RE = Regex(ID_PATTERN)

    fun slugify(name: String): String =
        name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).trim('-')

    fun isSlug(s: String): Boolean = SLUG_RE.matches(s)

    fun isPresetId(s: String): Boolean = PRESET_ID_RE.matches(s)

    /** Parses and validates a pack file's text: size cap, JSON, then [validate]. */
    fun parse(text: String): PackParse {
        val bytes = text.encodeToByteArray().size
        if (bytes > MAX_PACK_BYTES) {
            return PackParse.Invalid(listOf(PackProblem(emptyList(), "Pack too large ($bytes bytes, limit $MAX_PACK_BYTES).")))
        }
        val json = try {
            JsonValue.parse(text)
        } catch (e: IllegalArgumentException) {
            return PackParse.Invalid(listOf(PackProblem(emptyList(), "not valid JSON (${e.message})")))
        }
        return validate(json)
    }

    /** Station's `validate_pack`: the normalised pack, or every problem found. */
    fun validate(data: JsonValue): PackParse {
        val errors = mutableListOf<PackProblem>()
        if (data !is JsonValue.Obj) return PackParse.Invalid(listOf(PackProblem(emptyList(), "a pack must be a JSON object")))
        if ((data["format"] as? JsonValue.Str)?.value != FORMAT) {
            errors += PackProblem(listOf("format"), "must be '$FORMAT' (is this a Loupe Station preset pack?)")
        }
        if (!isOne(data["version"])) errors += PackProblem(listOf("version"), "must be $VERSION")
        val name = text(data["name"], listOf("name"), errors, MAX_NAME_CHARS)
        val desc = text(data["description"] ?: JsonValue.Str(""), listOf("description"), errors, MAX_DESCRIPTION_CHARS, required = false)
        var slug: JsonValue? = data["slug"]?.takeUnless { it is JsonValue.Null }
        if ((slug == null) && name != null) {
            val derived = slugify(name)
            if (derived.isEmpty()) {
                errors += PackProblem(listOf("slug"), "cannot be derived from the name: add a \"slug\" (lowercase letters, digits and -)")
                slug = null
            } else {
                slug = JsonValue.Str(derived)
            }
        }
        val slugText = (slug as? JsonValue.Str)?.value
        if (slug != null && (slugText == null || !SLUG_RE.matches(slugText))) {
            errors += PackProblem(listOf("slug"), "must match $SLUG_PATTERN")
        }
        val presets = data["presets"]
        val out = mutableListOf<PackPreset>()
        if (presets !is JsonValue.Arr || presets.items.isEmpty()) {
            errors += PackProblem(listOf("presets"), "must be a non-empty list")
        } else if (presets.items.size > MAX_PRESETS) {
            errors += PackProblem(listOf("presets"), "at most $MAX_PRESETS presets per pack")
        } else {
            val seen = mutableSetOf<String>()
            presets.items.forEachIndexed { i, raw ->
                var p = raw
                // An exported pack may carry namespaced ids ("acme-shop:review"): strip our own prefix.
                val pid = ((p as? JsonValue.Obj)?.get("id") as? JsonValue.Str)?.value
                if (p is JsonValue.Obj && pid != null && slugText != null && pid.startsWith("$slugText:")) {
                    val copy = LinkedHashMap(p.fields)
                    copy["id"] = JsonValue.Str(pid.substring(slugText.length + 1))
                    p = JsonValue.Obj(copy)
                }
                val rec = preset(p, i, errors) ?: return@forEachIndexed
                if (rec.id in seen) {
                    errors += PackProblem(listOf("presets", "$i", "id"), "duplicate preset id '${rec.id}'")
                    return@forEachIndexed
                }
                seen += rec.id
                out += rec
            }
        }
        if (errors.isNotEmpty()) return PackParse.Invalid(errors)
        return PackParse.Valid(Pack(name!!, slugText!!, desc ?: "", out))
    }

    private fun isOne(v: JsonValue?): Boolean = when (v) {
        is JsonValue.Num -> v.text.toDoubleOrNull() == 1.0
        is JsonValue.Bool -> v.value // Python: True == 1
        else -> false
    }

    /** Python's `len`: code points, not UTF-16 units. */
    internal fun codePoints(s: String): Int {
        var n = 0
        var i = 0
        while (i < s.length) {
            if (s[i].isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) i += 2 else i++
            n++
        }
        return n
    }

    private fun pyStrip(s: String): String = s.trim { it.isWhitespace() }

    private fun hasControl(v: String, singleLine: Boolean): Boolean =
        v.any { c -> (c.code < 32 && (singleLine || c !in "\n\r\t")) || c.code == 127 }

    private fun text(
        v: JsonValue?,
        loc: List<String>,
        errors: MutableList<PackProblem>,
        maxLen: Int,
        required: Boolean = true,
        singleLine: Boolean = true,
    ): String? {
        if ((v == null || v is JsonValue.Null) && !required) return null
        if (v !is JsonValue.Str) {
            errors += PackProblem(loc, "must be a string")
            return null
        }
        val s = pyStrip(v.value)
        if (required && s.isEmpty()) {
            errors += PackProblem(loc, "must not be empty")
            return null
        }
        if (codePoints(s) > maxLen) {
            errors += PackProblem(loc, "must be at most $maxLen characters")
            return null
        }
        if (hasControl(s, singleLine)) {
            errors += PackProblem(loc, "must not contain control characters")
            return null
        }
        return s
    }

    private fun preset(p: JsonValue, i: Int, errors: MutableList<PackProblem>): PackPreset? {
        val loc = listOf("presets", "$i")
        if (p !is JsonValue.Obj) {
            errors += PackProblem(loc, "must be an object")
            return null
        }
        val before = errors.size
        val pid = (p["id"] as? JsonValue.Str)?.value
        if (pid == null || !PRESET_ID_RE.matches(pid)) {
            errors += PackProblem(loc + "id", "must match $PRESET_ID_PATTERN (lowercase letters, digits, - and _)")
        }
        val name = text(p["name"], loc + "name", errors, MAX_PRESET_NAME_CHARS)
        val desc = text(p["description"] ?: JsonValue.Str(""), loc + "description", errors, MAX_DESCRIPTION_CHARS, required = false)
        var stateKeyV = p["state_key"]
        if (stateKeyV is JsonValue.Str && stateKeyV.value == "") stateKeyV = null
        if (stateKeyV is JsonValue.Null) stateKeyV = null
        val stateKey = (stateKeyV as? JsonValue.Str)?.value
        if (stateKeyV != null && (stateKey == null || !ID_RE.matches(stateKey))) {
            errors += PackProblem(loc + "state_key", "must match $ID_PATTERN or be null")
        }
        val sampleV = p["sample_text"] ?: JsonValue.Str("")
        val sample = when (sampleV) {
            is JsonValue.Null -> ""
            is JsonValue.Str -> sampleV.value
            else -> null
        }
        if (sample == null || codePoints(sample) > MAX_TEXT_CHARS) {
            errors += PackProblem(loc + "sample_text", "must be a string of at most $MAX_TEXT_CHARS characters")
        }
        val modelV = p["model"]
        val model = when {
            modelV == null || modelV is JsonValue.Null -> "auto"
            modelV is JsonValue.Str && modelV.value.isEmpty() -> "auto"
            modelV is JsonValue.Bool && !modelV.value -> "auto"
            modelV is JsonValue.Str -> modelV.value
            else -> null
        }
        if (model == null || model !in MODELS) errors += PackProblem(loc + "model", "must be one of ${MODELS.joinToString(", ")}")
        val questionsV = p["questions"]
        val questions = mutableListOf<PackQuestion>()
        if (questionsV !is JsonValue.Obj) {
            errors += PackProblem(loc + "questions", "must be an object of question id -> question")
        } else {
            // Exactly the rules /api/predict applies (PredictRequest), with the sample text standing
            // in for the request text (a placeholder when empty).
            predictQuestions(questionsV, loc, errors, questions)
        }
        val translations = linkedMapOf<String, Map<String, String>>()
        val tr = p["translations"]
        if (tr != null && tr !is JsonValue.Null) {
            if (tr !is JsonValue.Obj) {
                errors += PackProblem(loc + "translations", "must be an object like {\"ar\": {\"name\": ...}}")
            } else {
                for ((lang, vals) in tr.fields) {
                    val tloc = loc + listOf("translations", lang)
                    if (lang !in TRANSLATION_LANGS) {
                        errors += PackProblem(tloc, "only ${TRANSLATION_LANGS.joinToString(", ")} are supported")
                        continue
                    }
                    if (vals !is JsonValue.Obj || (vals.fields.keys - setOf("name", "description")).isNotEmpty()) {
                        errors += PackProblem(tloc, "must be an object with name and/or description")
                        continue
                    }
                    val o = linkedMapOf<String, String>()
                    if ("name" in vals.fields) text(vals["name"], tloc + "name", errors, MAX_PRESET_NAME_CHARS)?.let { o["name"] = it }
                    if ("description" in vals.fields) {
                        text(vals["description"], tloc + "description", errors, MAX_DESCRIPTION_CHARS)?.let { o["description"] = it }
                    }
                    translations[lang] = o.filterValues { it.isNotEmpty() }
                }
            }
        }
        if (errors.size > before) return null
        return PackPreset(pid!!, name!!, desc ?: "", stateKey, sample!!, model!!, questions, translations)
    }

    /** PredictRequest's `questions` validator and each question model (extra keys forbidden). */
    private fun predictQuestions(
        qs: JsonValue.Obj,
        loc: List<String>,
        errors: MutableList<PackProblem>,
        out: MutableList<PackQuestion>,
    ) {
        val qloc = loc + "questions"
        // Pydantic checks each question's model first, then the field validator on the whole map.
        val before = errors.size
        for ((qid, q) in qs.fields) question(qid, q, qloc + qid, errors)?.let { out += it }
        if (errors.size > before) return
        if (qs.fields.isEmpty()) {
            errors += PackProblem(qloc, "add at least one question")
        } else if (qs.fields.size > MAX_QUESTIONS) {
            errors += PackProblem(qloc, "at most $MAX_QUESTIONS questions per request")
        } else {
            qs.fields.keys.firstOrNull { !ID_RE.matches(it) }?.let {
                errors += PackProblem(qloc, "question id '${it.take(70)}' must match $ID_PATTERN")
            }
        }
    }

    private fun question(qid: String, q: JsonValue, loc: List<String>, errors: MutableList<PackProblem>): PackQuestion? {
        if (q !is JsonValue.Obj) {
            errors += PackProblem(loc, "must be an object with a type of choice, score or noul")
            return null
        }
        val type = (q["type"] as? JsonValue.Str)?.value
        if (type !in listOf("choice", "score", "noul")) {
            errors += PackProblem(loc, "type must be one of choice, score, noul")
            return null
        }
        val before = errors.size
        val tloc = loc + type!!
        (q.fields.keys - setOf("type", "instructions", "criteria")).forEach { errors += PackProblem(tloc + it, "extra fields are not permitted") }
        val instr = q["instructions"]
        var instructions: String? = null
        when {
            instr !is JsonValue.Str -> errors += PackProblem(tloc + "instructions", "must be a string")
            instr.value.isEmpty() -> errors += PackProblem(tloc + "instructions", "must have at least 1 character")
            codePoints(instr.value) > MAX_INSTRUCTIONS_CHARS ->
                errors += PackProblem(tloc + "instructions", "must have at most $MAX_INSTRUCTIONS_CHARS characters")
            instr.value.isBlank() -> errors += PackProblem(tloc + "instructions", "must not be blank")
            else -> instructions = instr.value
        }
        val crit = q["criteria"]
        val cloc = tloc + "criteria"
        val options = mutableListOf<String>()
        val descriptions = linkedMapOf<String, String?>()
        when (type) {
            "choice" -> when (crit) {
                is JsonValue.Obj -> {
                    var ok = true
                    for ((label, d) in crit.fields) {
                        when {
                            d is JsonValue.Null -> descriptions[label] = null
                            d is JsonValue.Str && codePoints(d.value) <= MAX_LEVEL_CHARS -> descriptions[label] = d.value
                            else -> {
                                errors += PackProblem(cloc + label, "must be a string of at most $MAX_LEVEL_CHARS characters, or null"); ok = false
                            }
                        }
                    }
                    if (ok) checkLabels(crit.fields.keys.toList(), unique = false, cloc, errors)
                    options += crit.fields.keys
                }
                is JsonValue.Arr -> {
                    val labels = crit.items.map { (it as? JsonValue.Str)?.value }
                    if (labels.any { it == null }) {
                        errors += PackProblem(cloc, "option labels must be non-empty strings")
                    } else {
                        checkLabels(labels.map { it!! }, unique = true, cloc, errors)
                        options += labels.map { it!! }
                    }
                }
                else -> errors += PackProblem(cloc, "must be {label: description-or-null} or a list of labels")
            }
            "score" -> when (crit) {
                is JsonValue.Arr -> {
                    var ok = true
                    crit.items.forEachIndexed { i, lv ->
                        val s = (lv as? JsonValue.Str)?.value
                        when {
                            s == null -> { errors += PackProblem(cloc + "$i", "must be a string"); ok = false }
                            s.isEmpty() -> { errors += PackProblem(cloc + "$i", "must have at least 1 character"); ok = false }
                            codePoints(s) > MAX_LEVEL_CHARS -> { errors += PackProblem(cloc + "$i", "must have at most $MAX_LEVEL_CHARS characters"); ok = false }
                            s.isBlank() -> { errors += PackProblem(cloc + "$i", "must not be blank"); ok = false }
                            else -> options += s
                        }
                    }
                    if (ok) {
                        if (crit.items.size < 2) errors += PackProblem(cloc, "a score question needs at least 2 levels")
                        else if (crit.items.size > MAX_OPTIONS) errors += PackProblem(cloc, "a score question can have at most $MAX_OPTIONS levels")
                    }
                }
                null -> errors += PackProblem(cloc, "field required")
                else -> errors += PackProblem(cloc, "must be a list of level descriptions, lowest first")
            }
            else -> when (crit) {
                null, is JsonValue.Null -> Unit
                is JsonValue.Obj -> {
                    (crit.fields.keys - setOf("true", "false")).forEach { errors += PackProblem(cloc + it, "extra fields are not permitted") }
                    for (k in listOf("true", "false")) {
                        val d = crit[k]
                        when {
                            d == null || d is JsonValue.Null -> Unit
                            d is JsonValue.Str && codePoints(d.value) <= MAX_LEVEL_CHARS -> if (d.value.isNotEmpty()) descriptions[k] = d.value
                            else -> errors += PackProblem(cloc + k, "must be a string of at most $MAX_LEVEL_CHARS characters, or null")
                        }
                    }
                }
                else -> errors += PackProblem(cloc, "must be {\"true\": ..., \"false\": ...}")
            }
        }
        if (type == "choice" && crit == null) errors += PackProblem(cloc, "field required")
        if (errors.size > before) return null
        return PackQuestion(qid, type, instructions!!, options, descriptions, q)
    }

    private fun checkLabels(labels: List<String>, unique: Boolean, loc: List<String>, errors: MutableList<PackProblem>) {
        when {
            labels.size < 2 -> errors += PackProblem(loc, "a choice question needs at least 2 options")
            labels.size > MAX_OPTIONS -> errors += PackProblem(loc, "a choice question can have at most $MAX_OPTIONS options")
            else -> {
                for (l in labels) {
                    if (l.isBlank()) { errors += PackProblem(loc, "option labels must be non-empty strings"); return }
                    if (codePoints(l) > MAX_LABEL_CHARS) {
                        errors += PackProblem(loc, "option label '${l.take(20)}...' is longer than $MAX_LABEL_CHARS characters"); return
                    }
                }
                if (unique && labels.distinct().size != labels.size) errors += PackProblem(loc, "choice option labels must be unique")
            }
        }
    }
}
