package dev.loupe.kit.measure

import dev.loupe.backend.onnx.HuggingFaceSubwordEncoder
import dev.loupe.backend.onnx.LayaPrompt
import dev.loupe.backend.onnx.LayaSpecialTokens
import dev.loupe.backend.onnx.LayaTokenizer
import dev.loupe.backend.onnx.OnnxBackend
import dev.loupe.backend.onnx.TensorNames
import dev.loupe.engine.DecisionEngine
import dev.loupe.engine.TextState
import dev.loupe.kit.settings.CalibrationFile
import dev.loupe.kit.settings.GatesFile
import dev.loupe.kit.settings.LangGroup
import dev.loupe.kit.settings.ModelPrior
import dev.loupe.kit.watchers.MODEL_PRIOR_DIR
import dev.loupe.kit.watchers.SAMPLE_DIR
import dev.loupe.kit.watchers.TEST_TMP
import org.junit.Assume.assumeTrue
import java.io.File
import java.time.LocalDate
import java.util.Locale
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The fitting tool for the phone's own calibration prior and confidence gates (`loupe-kit/data/
 * model-prior/`), **gated** on `models/` like the other real-model tests.
 *
 * It scores every labelled question of [ModelFixesMeasurementTest] with the phone's graph (INT8,
 * the fixed-choice export) — the checkpoint's own temperatures are 1.0, so `ln p` is the raw logit
 * up to a constant — groups each text with [LangGroup] as the phone does, and fits with
 * [CalibrationFit] (Loupe Station's method: temperatures on raw logits, 5-fold CV grouped by text,
 * Station's minimum-n rules, the jeval cost sweep with Station's placeholder costs). It prints
 * before / after (5-fold out of sample) per type and language, and writes both files
 * to the test scratch directory; with `-Ploupe.fit.write=true` it writes them into
 * `loupe-kit/data/model-prior/`, which the build embeds (`generateModelPrior`) and the iOS app
 * bundles. Otherwise it checks the shipped files still match what this fit produces.
 */
class ModelPriorFitTest {
    private val root = File(SAMPLE_DIR.substringBefore("/sources-desktop/"))
    private val models = File(System.getProperty("loupe.models.dir") ?: File(root, "models").path)
    private val tokenizer = File(models, "laya-multilingual/tokenizer/tokenizer.json")
    private val graph = File(models, "laya-multilingual-onnx/laya-multilingual-choice.int8.onnx")

    @Test
    fun `fits the phone's calibration prior and gates on its own graph`() {
        assumeTrue("Laya tokenizer not present at $tokenizer; skipping", tokenizer.isFile)
        assumeTrue("Laya INT8 graph not present at $graph; skipping", graph.isFile)

        val cases = ModelFixesMeasurementTest().cases()
        val encoder = HuggingFaceSubwordEncoder.openLayaMultilingual(tokenizer.toPath())
        val backend = OnnxBackend.open(graph.toPath(), LayaTokenizer(LayaPrompt(encoder, LayaSpecialTokens.MULTILINGUAL)), TensorNames.LAYA)
        val rows = try {
            cases.map { c ->
                val m = c.judgment.validate(backend.score(c.judgment, TextState.build(listOf(c.id to c.text), DecisionEngine.DEFAULT_STATE_BUDGET)).masses)
                val p = c.judgment.candidates.map { m.getValue(it).value }
                val type = ModelPrior.typeOf(c.judgment)
                val y = c.judgment.candidates.indexOf(c.label)
                // Station's order for a yes/no: [z_no, z_yes], class 1 = yes (our first option).
                val (raw, yy) = if (type == ModelPrior.NOUL) listOf(ln(p[1].coerceAtLeast(1e-300)), ln(p[0].coerceAtLeast(1e-300))) to (if (y == 0) 1 else 0)
                else p.map { ln(it.coerceAtLeast(1e-300)) } to y
                CalibrationFit.Row(type, LangGroup.of(c.text), c.text.hashCode().toString(16) + ":" + c.text.length, raw, yy, ModelPrior.isPhishing(c.judgment.id, type))
            }
        } finally {
            backend.close()
            encoder.close()
        }

        // Temperatures only (b = 0), so calibration never changes an answer. Station's method would
        // allow a yes/no bias here (n >= 100, and it wins out of sample), but on these labels it
        // learns their base rate — about four in five answers are the negative option, most of them
        // labels written by the person measuring — not the model's miscalibration: it moved every
        // yes/no line towards "no" (b = -1.46). Shown below for the record, not shipped.
        val withBias = CalibrationFit.fitAll(rows, allowBias = true)
        val fit = CalibrationFit.fitAll(rows, allowBias = false)
        val cv = CalibrationFit.crossValidated(rows, fit)
        val gates = CalibrationFit.fitGates(rows, cv)
        val report = StringBuilder()
        fun say(s: String) { println("[model-prior] $s"); report.appendLine(s) }

        say("rows ${rows.size}; groups ${rows.groupingBy { it.lang }.eachCount()}; types ${rows.groupingBy { it.type }.eachCount()}")
        say("== selection")
        fit.selection.forEach { say("  $it") }
        say("== fitted: " + fit.types.entries.joinToString("; ") { (t, c) -> String.format(Locale.ROOT, "%s T=%.4f b=%.4f (%s, n=%d)", t, c.t, c.b, c.method, c.n) } +
            fit.languages.entries.joinToString("") { (lg, cs) -> cs.entries.joinToString("") { (t, c) -> String.format(Locale.ROOT, "; %s/%s T=%.4f", t, lg, c.t) } })
        say("== not shipped: with a bias allowed (Station's rule) noul would be " + withBias.types.getValue(ModelPrior.NOUL).let { String.format(Locale.ROOT, "%s T=%.4f b=%.4f, cv NLL %s", it.method, it.t, it.b, it.cvNll) })
        say("== before (identity) -> after (5-fold out of sample) -> after (in sample)")
        fun line(label: String, idx: List<Int>) {
            if (idx.isEmpty()) return
            val before = CalibrationFit.metrics(idx.map { i -> rows[i].raw.let { CalibrationFit.softmax(it) } to rows[i].y })
            val after = CalibrationFit.metrics(idx.map { i -> cv.getValue(i) to rows[i].y })
            val ins = CalibrationFit.metrics(idx.map { i ->
                val (t, b) = CalibrationFit.paramsFor(fit, rows[i].type, rows[i].lang)
                CalibrationFit.apply(rows[i].raw, rows[i].type, t, b) to rows[i].y
            })
            say(String.format(Locale.ROOT, "%-14s n=%4d  acc %5.1f%%  ECE %.3f -> %.3f (%.3f)  wrong>=0.9 %5.1f%% -> %5.1f%%  wrong>=0.99 %5.1f%% -> %5.1f%%%s",
                label, before.n, before.acc * 100, before.ece, after.ece, ins.ece, before.wrong90 * 100, after.wrong90 * 100, before.wrong99 * 100, after.wrong99 * 100,
                if (before.n < 30) "  (n too small to conclude)" else ""))
            assertEquals(before.acc, ins.acc, "a temperature (b = 0) never changes which answer wins: $label")
        }
        for (type in CalibrationFile.TYPES) {
            line(type, rows.indices.filter { rows[it].type == type })
            for (lg in LangGroup.GROUPS) line("  $type/$lg", rows.indices.filter { rows[it].type == type && rows[it].lang == lg })
        }
        line("phishing", rows.indices.filter { rows[it].phishing })
        // What the app feels: answers at or above the starting thresholds (yes/no 0.80, choice and score
        // 0.60) act; the rest wait for the user. Calibration moves no answer, only which side of the line it is.
        say("== answers at or above the starting threshold (act) -> the rest wait for you; before -> after (in sample)")
        for ((type, threshold) in listOf(ModelPrior.NOUL to 0.80, ModelPrior.CHOICE to 0.60, ModelPrior.SCORE to 0.60)) {
            val idx = rows.indices.filter { rows[it].type == type }
            fun acting(d: (Int) -> List<Double>) = idx.filter { d(it).max() >= threshold }
            val before = acting { CalibrationFit.softmax(rows[it].raw) }
            val after = acting { i -> CalibrationFit.paramsFor(fit, type, rows[i].lang).let { (t, b) -> CalibrationFit.apply(rows[i].raw, type, t, b) } }
            fun acc(a: List<Int>, d: (Int) -> List<Double>) = if (a.isEmpty()) Double.NaN else a.count { i -> d(i).indices.maxBy { d(i)[it] } == rows[i].y } * 100.0 / a.size
            say(String.format(Locale.ROOT, "%-8s >= %.2f: %d/%d (%.0f%%, %.1f%% right) -> %d/%d (%.0f%%, %.1f%% right)", type, threshold,
                before.size, idx.size, before.size * 100.0 / idx.size, acc(before) { CalibrationFit.softmax(rows[it].raw) },
                after.size, idx.size, after.size * 100.0 / idx.size, acc(after) { i -> CalibrationFit.paramsFor(fit, type, rows[i].lang).let { (t, b) -> CalibrationFit.apply(rows[i].raw, type, t, b) } }))
        }
        say("== gates (Station's placeholder costs)")
        for ((k, g) in gates) say("  $k: act ${g.act} confirm ${g.confirm} n=${g.n} ${g.languages}  [${g.notes.joinToString("; ")}]")

        val created = LocalDate.now().toString()
        val calText = CalibrationFit.calibrationJson(fit, created, "1.0", "ModelFixesMeasurementTest's labelled sets (${rows.size} answers; see docs/BUILD.md 2026-09-27)")
        val gatesText = CalibrationFit.gatesJson(gates, created, "1.0", "1.0")
        // Both parse with the readers the app uses.
        val file = CalibrationFile.parse(calText)
        GatesFile.parse(gatesText)
        assertTrue(file.lowTrust(ModelPrior.CHECKPOINT, LangGroup.FRANCO))

        val scratch = File(TEST_TMP, "model-prior").apply { mkdirs() }
        File(scratch, "model_calibration.json").writeText(calText)
        File(scratch, "model_gates.json").writeText(gatesText)
        File(scratch, "fit-report.txt").writeText(report.toString())
        val shipped = File(MODEL_PRIOR_DIR)
        if (System.getProperty("loupe.fit.write") == "true") {
            File(shipped, "model_calibration.json").writeText(calText)
            File(shipped, "model_gates.json").writeText(gatesText)
            say("written to ${shipped.path}")
        } else {
            fun strip(s: String) = s.lines().filterNot { "\"created\"" in it }.joinToString("\n")
            val same = strip(File(shipped, "model_calibration.json").readText()) == strip(calText) &&
                strip(File(shipped, "model_gates.json").readText()) == strip(gatesText)
            say(if (same) "the shipped files match this fit" else "NOTE: the shipped files differ from this fit (written to ${scratch.path}); refit with -Ploupe.fit.write=true")
        }
    }
}
