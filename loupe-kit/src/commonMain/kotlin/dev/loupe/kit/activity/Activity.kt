package dev.loupe.kit.activity

import dev.loupe.persistence.JsonText
import dev.loupe.persistence.JsonValue
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.round

/**
 * Activity: the long jobs running on the phone, for the live run views and the Activity dock.
 *
 * The same job model as Loupe Station's `laya_studio/activity` (README "Live run view", the contract): the
 * same field names, kinds, stage keys, loop stage ids, counters, gates, shares, decision log and cost of
 * asking. The phone adds a few kinds and catalog keys of its own ([ActivityNames.MOBILE_KINDS],
 * docs/LIVE-RUN-VIEW.md).
 *
 * What a job carries is deliberately narrow: kinds, catalog keys (`act.*`), numbers and short identifiers
 * (a model, a watcher id, a question id, a choice label). [Activity.clean] drops every other value, so a
 * subject, a sender, a file name, a path or any text can never reach a job even if a caller passes one by
 * mistake. The phone is stricter than Station in one place: a value that ends like a file name
 * (`.pdf`, `.jpeg`) is dropped too.
 *
 * Threading: a registry is confined to one thread (the app's main actor). Workers on other threads hop
 * there before they report; nothing here blocks.
 */
object ActivityNames {
    /** Station's kinds, in Station's order (`KINDS` in laya_studio/activity). */
    val STATION_KINDS: List<String> = listOf(
        "scan", "email_run", "watchers", "model_load", "ocr", "ollama_pull", "feeds", "llm_job", "calibration", "mail_history",
    )

    /** The phone's own kinds (docs/LIVE-RUN-VIEW.md). */
    val MOBILE_KINDS: List<String> = listOf("judgments", "sort", "inbox", "flights", "game")
    val KINDS: List<String> = STATION_KINDS + MOBILE_KINDS

    val STATES: List<String> = listOf("running", "done", "error", "cancelled")

    /** Station's views, then the phone's screens that own a job. */
    val STATION_VIEWS: List<String> = listOf("playground", "scan", "email", "watchers", "review", "protection", "measure", "setup", "engine")
    val MOBILE_VIEWS: List<String> = listOf("now", "judgments", "flights", "sources", "assist", "game")
    val VIEWS: List<String> = STATION_VIEWS + MOBILE_VIEWS

    val COUNTERS: List<String> = listOf("read", "decisions", "flagged", "to_you", "tokens")
    val GATES: List<String> = listOf("accepted", "uncertain", "flagged", "skipped")
    val SOURCES: List<String> = listOf("laya", "rule", "baseline", "personal")
    val MODELS: List<String> = listOf("english", "multilingual")
    val STEP_STATES: List<String> = listOf("pending", "running", "done", "error", "skipped")

    const val HISTORY: Int = 10
    const val DECISIONS: Int = 12
    const val RATE_WINDOW_S: Double = 15.0
}

/** A catalog key with its parameters (numbers, booleans, short identifiers only). */
data class ActMessage(val key: String, val params: Map<String, Any?>) {
    /** A parameter as text for filling `{name}` in the catalog line. */
    fun param(name: String): String? = when (val v = params[name]) {
        null -> null
        is Double -> if (v == round(v) && abs(v) < 1e15) v.toLong().toString() else v.toString()
        is Float -> v.toDouble().let { if (it == round(it)) it.toLong().toString() else it.toString() }
        else -> v.toString()
    }

    val paramNames: List<String> get() = params.keys.toList()

    fun json(): JsonValue.Obj = JsonValue.obj("key" to JsonValue.Str(key), "params" to Activity.paramsJson(params))
}

object Activity {
    // Short identifiers and labels only: letters (any script), digits, _ . : + - and up to three words. No "/",
    // "\", "@", quotes or brackets, so a path, an address or a sentence never fits (Station's `safe_token`).
    // Kotlin/Native's regex has no \\p{L}, so a word character is anything but space, punctuation that builds a
    // path, an address or a sentence, and the colon (allowed only inside a word): letters of any script pass.
    private const val CH = "[^\\s/\\\\@\"'`<>(){}\\[\\]:,;=?!#$%&*|~^]"
    private const val WORD = "$CH+(?::$CH+)*"
    private val TOKEN = Regex("^$WORD(?: $WORD){0,2}$")
    private val KEY = Regex("^act\\.[A-Za-z0-9_.]{1,60}$")
    private val PARAM_NAME = Regex("^[a-z][a-z0-9_]{0,30}$")

    /** The phone's extra rule: something that ends like a file name (`scan.pdf`, `IMG_1.HEIC`) is not an identifier. */
    private val FILE_LIKE = Regex("\\.[A-Za-z][A-Za-z0-9]{1,4}$")
    const val TOKEN_MAX: Int = 40

    /** [v] as a short identifier or label, or null when it is not one. */
    fun safeToken(v: Any?): String? {
        if (v !is String) return null
        val s = v.trim()
        if (s.isEmpty() || s.length > TOKEN_MAX || !TOKEN.matches(s) || FILE_LIKE.containsMatchIn(s)) return null
        return s
    }

    /** A catalog key (`act.…`), or null. */
    fun key(k: String?): String? = k?.takeIf { KEY.matches(it) }

    private fun num(v: Any?): Double? = when (v) {
        is Boolean -> null
        is Int -> v.toDouble()
        is Long -> v.toDouble()
        is Short -> v.toDouble()
        is Byte -> v.toDouble()
        is Float -> v.toDouble().takeIf { it.isFinite() }
        is Double -> v.takeIf { it.isFinite() }
        is Number -> v.toDouble().takeIf { it.isFinite() }
        else -> null
    }

    /** Keep numbers, booleans, null and short identifiers; drop everything else. */
    fun clean(params: Map<String, Any?>?): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        for ((k, v) in params ?: emptyMap()) {
            if (!PARAM_NAME.matches(k)) continue
            when {
                v == null || v is Boolean -> out[k] = v
                num(v) != null -> {
                    val n = num(v)!!
                    out[k] = if (v is Int || v is Long || v is Short || v is Byte) n.toLong().toDouble() else round4(n)
                }
                else -> safeToken(v)?.let { out[k] = it }
            }
        }
        return out
    }

    fun message(key: String?, params: Map<String, Any?>? = null): ActMessage? = key(key)?.let { ActMessage(it, clean(params)) }

    internal fun round4(v: Double): Double = round(v * 10_000) / 10_000
    internal fun round3(v: Double): Double = round(v * 1_000) / 1_000
    internal fun round1(v: Double): Double = round(v * 10) / 10

    fun numJson(v: Double?): JsonValue = when {
        v == null -> JsonValue.Null
        v == round(v) && abs(v) < 1e15 -> JsonValue.Num(v.toLong().toString())
        else -> JsonValue.Num(v.toString())
    }

    fun paramsJson(params: Map<String, Any?>): JsonValue.Obj {
        val m = LinkedHashMap<String, JsonValue>()
        for ((k, v) in params) {
            m[k] = when (v) {
                null -> JsonValue.Null
                is Boolean -> JsonValue.Bool(v)
                is Double -> numJson(v)
                is String -> JsonValue.Str(v)
                else -> JsonValue.Null
            }
        }
        return JsonValue.Obj(m)
    }

    /**
     * A question id that is safe to carry. A user's judgment id is a slug of its wording (`j-is-this-a-receipt`),
     * which is text, so the phone carries an opaque `j:` + 8 hex digits instead; the owning screen maps it back
     * on the device. Station's catalogue question ids (`needs_reply`, `is_phishing`) pass unchanged.
     */
    fun questionId(id: String): String = if (id in CATALOG_QUESTIONS) id else "j:" + hash8(id)

    /** Station's catalogue question ids (`act.q.*`). */
    val CATALOG_QUESTIONS: Set<String> = setOf("category", "needs_reply", "urgency", "is_spam", "is_phishing", "business", "sensitive", "draft")

    /** FNV-1a 32-bit, as 8 hex digits: stable across JVM and iOS. */
    fun hash8(s: String): String {
        var h = 0x811C9DC5u
        for (b in s.encodeToByteArray()) {
            h = h xor (b.toUByte().toUInt())
            h *= 0x01000193u
        }
        return h.toString(16).padStart(8, '0')
    }
}

/** How a job reads the time: Unix seconds for the fields, a monotonic clock for the rate. */
interface ActivityClock {
    fun unix(): Double
    fun mono(): Double
}

/** A job's Cancel, when it has one. */
fun interface ActivityCancel {
    fun cancel()
}

/** The registry's change signal (the app redraws). */
fun interface ActivityListener {
    fun changed(version: Long)
}

data class ActProgress(val done: Double, val total: Double?)

data class ActDecision(val seq: Int, val q: String?, val a: String?, val c: Double?, val src: String, val model: String?) {
    fun json(): JsonValue.Obj = JsonValue.obj(
        "seq" to JsonValue.num(seq), "q" to JsonValue.str(q), "a" to JsonValue.str(a), "c" to Activity.numJson(c),
        "src" to JsonValue.Str(src), "model" to JsonValue.str(model),
    )
}

data class ActStep(
    val id: String,
    val key: String?,
    val params: Map<String, Any?>,
    val state: String,
    val done: Double?,
    val total: Double?,
    val result: ActMessage?,
) {
    fun json(): JsonValue.Obj = JsonValue.obj(
        "id" to JsonValue.Str(id), "key" to JsonValue.str(key), "params" to Activity.paramsJson(params),
        "state" to JsonValue.Str(state), "done" to Activity.numJson(done), "total" to Activity.numJson(total),
        "result" to (result?.json() ?: JsonValue.Null),
    )
}

/** One job as the views see it: Station's `Job.public()`, field for field. */
data class JobSnapshot(
    val id: String,
    val kind: String,
    val view: String?,
    val ref: String?,
    val title: ActMessage,
    val state: String,
    val stage: ActMessage?,
    val progress: ActProgress?,
    val rate: Double?,
    val etaS: Double?,
    val expectedS: Double?,
    val startedAt: Double,
    val finishedAt: Double?,
    val elapsedS: Double,
    val cancellable: Boolean,
    val cancelRequested: Boolean,
    val counters: Map<String, Int>,
    val gates: Map<String, Int>,
    val sharesModel: Map<String, Int>,
    val sharesSource: Map<String, Int>,
    val decisions: List<ActDecision>,
    val seq: Int,
    val model: String?,
    val steps: List<ActStep>,
    val meta: Map<String, Any?>,
    val result: ActMessage?,
) {
    val running: Boolean get() = state == "running"

    fun counter(name: String): Int = counters[name] ?: 0
    fun gate(name: String): Int = gates[name] ?: 0

    /** done / total in 0...1, or null while the job cannot say. */
    val fraction: Double?
        get() {
            val p = progress ?: return null
            val t = p.total ?: return null
            if (t <= 0) return null
            return (p.done / t).coerceIn(0.0, 1.0)
        }

    fun json(): JsonValue.Obj {
        fun ints(m: Map<String, Int>) = JsonValue.Obj(LinkedHashMap(m.mapValues { JsonValue.num(it.value) }))
        return JsonValue.obj(
            "id" to JsonValue.Str(id), "kind" to JsonValue.Str(kind), "view" to JsonValue.str(view), "ref" to JsonValue.str(ref),
            "title" to title.json(), "state" to JsonValue.Str(state), "stage" to (stage?.json() ?: JsonValue.Null),
            "progress" to (progress?.let { JsonValue.obj("done" to Activity.numJson(it.done), "total" to Activity.numJson(it.total)) } ?: JsonValue.Null),
            "rate" to Activity.numJson(rate), "eta_s" to Activity.numJson(etaS), "expected_s" to Activity.numJson(expectedS),
            "started_at" to Activity.numJson(startedAt), "finished_at" to Activity.numJson(finishedAt), "elapsed_s" to Activity.numJson(elapsedS),
            "cancellable" to JsonValue.Bool(cancellable), "cancel_requested" to JsonValue.Bool(cancelRequested),
            "counters" to ints(counters), "gates" to ints(gates),
            "shares" to JsonValue.obj("model" to ints(sharesModel), "source" to ints(sharesSource)),
            "decisions" to JsonValue.Arr(decisions.map { it.json() }), "seq" to JsonValue.num(seq), "model" to JsonValue.str(model),
            "steps" to JsonValue.Arr(steps.map { it.json() }), "meta" to Activity.paramsJson(meta), "result" to (result?.json() ?: JsonValue.Null),
        )
    }

    fun jsonText(): String = JsonText.compact(json())
}

/**
 * One running job. Every method is safe to call in any state and never throws: a progress report never breaks
 * the work it reports on. Call it on the registry's thread.
 */
class ActivityJob internal constructor(
    private val reg: ActivityRegistry,
    val id: String,
    val kind: String,
    val title: ActMessage,
    val view: String?,
    val ref: String?,
    private val cancelFn: ActivityCancel?,
    expectedS: Double?,
) {
    var state: String = "running"
        internal set
    val startedAt: Double = reg.clock.unix()
    internal var finishedAt: Double? = null
    private var stageMsg: ActMessage? = null
    private var done: Double? = null
    private var total: Double? = null
    private val expected: Double? = expectedS?.takeIf { it.isFinite() }
    var cancelRequested: Boolean = false
        private set
    private val counters = LinkedHashMap<String, Int>().apply { ActivityNames.COUNTERS.forEach { put(it, 0) } }
    private val gates = LinkedHashMap<String, Int>().apply { ActivityNames.GATES.forEach { put(it, 0) } }
    private val byModel = LinkedHashMap<String, Int>().apply { ActivityNames.MODELS.forEach { put(it, 0) } }
    private val bySource = LinkedHashMap<String, Int>().apply { ActivityNames.SOURCES.forEach { put(it, 0) } }
    private val decisions = ArrayDeque<ActDecision>()
    var seq: Int = 0
        private set
    private var model: String? = null
    private val steps = LinkedHashMap<String, ActStep>()
    private val meta = LinkedHashMap<String, Any?>()
    private var result: ActMessage? = null
    private val samples = ArrayDeque<Pair<Double, Double>>()

    val cancellable: Boolean get() = cancelFn != null
    val running: Boolean get() = state == "running"

    private fun touch() = reg.bump()

    fun stage(key: String?, params: Map<String, Any?>? = null): ActivityJob {
        stageMsg = Activity.message(key, params)
        touch()
        return this
    }

    /** [stage] without parameters (Swift sees no Kotlin default arguments). */
    fun stageKey(key: String?): ActivityJob = stage(key, null)

    fun progress(done: Double?, total: Double?): ActivityJob {
        setProgress(done, total)
        touch()
        return this
    }

    private fun setProgress(d: Double?, t: Double?) {
        if (t != null && t.isFinite()) total = max(0.0, t)
        if (d != null && d.isFinite() && d != done) {
            done = max(0.0, d)
            samples.addLast(reg.clock.mono() to done!!)
            while (samples.size > 64) samples.removeFirst()
        }
    }

    /** Progress it cannot say (fetching, listing files): `progress` becomes null. */
    fun indeterminate(): ActivityJob {
        done = null; total = null; samples.clear()
        touch()
        return this
    }

    fun advance(n: Double): ActivityJob {
        setProgress((done ?: 0.0) + n, null)
        touch()
        return this
    }

    fun count(name: String, n: Int): ActivityJob {
        if (name in counters) {
            counters[name] = (counters[name] ?: 0) + n
            touch()
        }
        return this
    }

    fun gate(name: String, n: Int): ActivityJob {
        if (name in gates) {
            gates[name] = (gates[name] ?: 0) + n
            if (name == "flagged") counters["flagged"] = (counters["flagged"] ?: 0) + n
            touch()
        }
        return this
    }

    fun useModel(m: String?): ActivityJob {
        if (m in ActivityNames.MODELS) {
            model = m
            touch()
        }
        return this
    }

    /** A number already in `meta`, or null. */
    fun metaNumber(name: String): Double? = meta[name] as? Double

    fun setMeta(values: Map<String, Any?>): ActivityJob {
        meta.putAll(Activity.clean(values))
        touch()
        return this
    }

    /**
     * One answered question: its id, the answer's label and confidence, who answered. Only identifiers and
     * numbers are kept.
     */
    fun decision(question: String?, answer: String?, confidence: Double?, src: String, model: String?, tokens: Int): ActivityJob {
        val q = Activity.safeToken(question)
        val a = answer?.let { Activity.safeToken(it) }
        val c = confidence?.takeIf { it.isFinite() }
        seq += 1
        counters["decisions"] = (counters["decisions"] ?: 0) + 1
        val s = if (src in ActivityNames.SOURCES) src else "laya"
        bySource[s] = (bySource[s] ?: 0) + 1
        val m = model?.takeIf { it in ActivityNames.MODELS }
        if (m != null) {
            byModel[m] = (byModel[m] ?: 0) + 1
            this.model = m
        }
        if (tokens > 0) counters["tokens"] = (counters["tokens"] ?: 0) + tokens
        decisions.addLast(ActDecision(seq, q, a, c?.let { Activity.round3(it) }, s, m))
        while (decisions.size > ActivityNames.DECISIONS) decisions.removeFirst()
        touch()
        return this
    }

    /** A sub-step (one watcher of a watcher run, one list of a refresh): created on first use. */
    fun step(
        id: String,
        key: String?,
        state: String?,
        done: Double?,
        total: Double?,
        result: String?,
        resultParams: Map<String, Any?>?,
        params: Map<String, Any?>?,
    ): ActivityJob {
        val sid = Activity.safeToken(id) ?: return this
        val old = steps[sid] ?: ActStep(sid, null, emptyMap(), "pending", null, null, null)
        steps[sid] = old.copy(
            key = if (key != null) Activity.key(key) else old.key,
            params = if (params != null) old.params + Activity.clean(params) else old.params,
            state = if (state != null && state in ActivityNames.STEP_STATES) state else old.state,
            done = done?.takeIf { it.isFinite() } ?: old.done,
            total = total?.takeIf { it.isFinite() } ?: old.total,
            result = if (result != null) Activity.message(result, resultParams) else old.result,
        )
        touch()
        return this
    }

    /** [step] with only its state and numbers (Swift). */
    fun stepState(id: String, key: String?, state: String?, done: Double?, total: Double?): ActivityJob =
        step(id, key, state, done, total, null, null, null)

    fun finish(status: String, resultKey: String?, params: Map<String, Any?>?) {
        reg.finish(this, if (status in ActivityNames.STATES.drop(1)) status else "done", Activity.message(resultKey, params))
    }

    fun requestCancel(): Boolean {
        val c = cancelFn ?: return false
        if (!running) return false
        cancelRequested = true
        try {
            c.cancel()
        } catch (_: Throwable) {
            // a failing cancel never breaks the registry
        }
        touch()
        return true
    }

    internal fun completeWith(status: String, res: ActMessage?) {
        state = status
        finishedAt = reg.clock.unix()
        result = res
        if (status == "done" && total != null) done = total
    }

    private fun rateEta(): Pair<Double?, Double?> {
        val now = reg.clock.mono()
        val pts = samples.filter { now - it.first <= ActivityNames.RATE_WINDOW_S }
        val d = done
        if (pts.size < 2 || d == null) return null to null
        val (t0, d0) = pts.first()
        val (_, d1) = pts.last()
        val span = now - t0
        if (span < 1.0 || d1 <= d0) return (if (span >= 3.0) 0.0 else null) to null
        val rate = (d1 - d0) / span
        val t = total
        val eta = if (t != null && t > 0 && rate > 0 && t >= d) (t - d) / rate else null
        return Activity.round3(rate) to eta?.let { Activity.round1(it) }
    }

    fun snapshot(): JobSnapshot {
        val (rate, eta) = rateEta()
        val prog = if (total != null || done != null) ActProgress(done ?: 0.0, total) else null
        val end = finishedAt ?: reg.clock.unix()
        return JobSnapshot(
            id = id, kind = kind, view = view, ref = ref, title = title, state = state, stage = stageMsg, progress = prog,
            rate = rate, etaS = eta, expectedS = expected, startedAt = Activity.round3(startedAt),
            finishedAt = finishedAt?.let { Activity.round3(it) }, elapsedS = Activity.round1(max(0.0, end - startedAt)),
            cancellable = cancellable && running, cancelRequested = cancelRequested,
            counters = LinkedHashMap(counters), gates = LinkedHashMap(gates),
            sharesModel = LinkedHashMap(byModel), sharesSource = LinkedHashMap(bySource),
            decisions = decisions.toList(), seq = seq, model = model, steps = steps.values.toList(),
            meta = LinkedHashMap(meta), result = result,
        )
    }
}

/** The whole registry at one moment: Station's `GET /api/activity`. */
data class ActivitySnapshot(
    val running: List<JobSnapshot>,
    val finished: List<JobSnapshot>,
    val serverTime: Double,
    val reference: CostReference,
    val version: Long,
) {
    fun json(): JsonValue.Obj = JsonValue.obj(
        "running" to JsonValue.Arr(running.map { it.json() }), "finished" to JsonValue.Arr(finished.map { it.json() }),
        "server_time" to Activity.numJson(Activity.round3(serverTime)), "reference" to reference.json(),
    )

    fun jsonText(): String = JsonText.compact(json())

    /** The newest job a screen owns: its running one, else its latest finished one. */
    fun latest(view: String): JobSnapshot? =
        running.lastOrNull { it.view == view } ?: finished.firstOrNull { it.view == view }
}

class ActivityRegistry(val clock: ActivityClock, private val history: Int = ActivityNames.HISTORY) {
    private val jobs = LinkedHashMap<String, ActivityJob>()
    private val done = ArrayDeque<ActivityJob>()
    private var ids = 0
    var version: Long = 0
        private set
    private val listeners = mutableListOf<ActivityListener>()

    /** The cost-of-asking price (Model settings); the app sets it when the settings change. */
    var reference: CostReference = CostReference.DEFAULT

    fun addListener(l: ActivityListener) {
        listeners += l
    }

    internal fun bump() {
        version += 1
        for (l in listeners.toList()) {
            try {
                l.changed(version)
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * Starts a job. An unknown [kind] is refused (null): kinds are a shared list, never free text.
     * [title] is a catalog key; when it is not one the kind's name is the title.
     */
    fun start(
        kind: String,
        title: String?,
        params: Map<String, Any?>?,
        view: String?,
        ref: String?,
        total: Double?,
        stage: String?,
        cancel: ActivityCancel?,
        expectedS: Double?,
    ): ActivityJob? {
        if (kind !in ActivityNames.KINDS) return null
        val t = Activity.message(title, params) ?: ActMessage("act.kind.$kind", emptyMap())
        ids += 1
        val jid = "$kind-$ids"
        val job = ActivityJob(
            this, jid, kind, t, view?.takeIf { it in ActivityNames.VIEWS },
            ref?.let { Activity.safeToken(it) }, cancel, expectedS,
        )
        if (total != null) job.progress(0.0, total)
        if (stage != null) job.stage(stage, null)
        jobs[jid] = job
        bump()
        return job
    }

    internal fun finish(job: ActivityJob, status: String, result: ActMessage?) {
        if (!job.running) return
        job.completeWith(status, result)
        jobs.remove(job.id)
        done.addFirst(job)
        while (done.size > history) done.removeLast()
        bump()
    }

    fun get(id: String): ActivityJob? = jobs[id] ?: done.firstOrNull { it.id == id }

    fun running(): List<ActivityJob> = jobs.values.toList()

    /** 404 unknown, 409 finished or not cancellable, 202 requested: Station's cancel answers. */
    fun cancel(id: String): Int {
        val job = get(id) ?: return 404
        if (!job.running || !job.cancellable) return 409
        job.requestCancel()
        return 202
    }

    fun snapshot(): ActivitySnapshot = ActivitySnapshot(
        running = jobs.values.map { it.snapshot() },
        finished = done.map { it.snapshot() },
        serverTime = clock.unix(),
        reference = reference,
        version = version,
    )

    /** Forget everything (tests). */
    fun reset() {
        jobs.clear()
        done.clear()
        bump()
    }
}

/**
 * "Cost of asking": what the decisions would have cost asked of a cloud model instead, against Laya on this
 * device (free). decisions × (tokens in × input price + tokens out × output price) / 1,000,000. Nothing is
 * ever called; it is an estimate from the Model settings `cost_*` keys (defaults: the reference list price).
 */
data class CostReference(
    val model: String,
    val inputPerMtok: Double,
    val outputPerMtok: Double,
    val tokensIn: Int,
    val tokensOut: Int,
    val source: String,
    val checked: String,
) {
    /** A value differs from the reference list price: the card says "your price from Model settings". */
    val custom: Boolean
        get() = inputPerMtok != DEFAULT.inputPerMtok || outputPerMtok != DEFAULT.outputPerMtok ||
            tokensIn != DEFAULT.tokensIn || tokensOut != DEFAULT.tokensOut

    val perDecision: Double get() = round((tokensIn * inputPerMtok + tokensOut * outputPerMtok) / 1_000_000 * 1e8) / 1e8

    fun cost(decisions: Int): Double = decisions * (tokensIn * inputPerMtok + tokensOut * outputPerMtok) / 1_000_000

    fun withPrices(inputPerMtok: Double, outputPerMtok: Double, tokensIn: Int, tokensOut: Int): CostReference =
        copy(inputPerMtok = inputPerMtok, outputPerMtok = outputPerMtok, tokensIn = tokensIn, tokensOut = tokensOut)

    fun json(): JsonValue.Obj = JsonValue.obj(
        "model" to JsonValue.Str(model), "input_per_mtok" to Activity.numJson(inputPerMtok),
        "output_per_mtok" to Activity.numJson(outputPerMtok), "tokens_in" to JsonValue.num(tokensIn),
        "tokens_out" to JsonValue.num(tokensOut), "source" to JsonValue.Str(source), "checked" to JsonValue.Str(checked),
        "custom" to JsonValue.Bool(custom), "per_decision" to Activity.numJson(perDecision),
    )

    companion object {
        /** Station's `REFERENCE`: Claude Sonnet 5 list price, checked 2026-09-24. */
        val DEFAULT: CostReference = CostReference(
            model = "Claude Sonnet 5", inputPerMtok = 2.0, outputPerMtok = 10.0, tokensIn = 700, tokensOut = 60,
            source = "https://docs.claude.com/en/docs/about-claude/pricing", checked = "2026-09-24",
        )

        /** The Model settings keys (global), as Station's engine_settings. */
        val SETTING_KEYS: List<String> = listOf("cost_input_per_mtok", "cost_output_per_mtok", "cost_tokens_in", "cost_tokens_out")
    }
}
