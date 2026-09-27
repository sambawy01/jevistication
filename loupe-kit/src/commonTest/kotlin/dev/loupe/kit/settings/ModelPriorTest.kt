package dev.loupe.kit.settings

import dev.loupe.engine.DecisionEngine
import dev.loupe.engine.Distribution
import dev.loupe.engine.FailurePosture
import dev.loupe.engine.Item
import dev.loupe.engine.Judgment
import dev.loupe.engine.Probability
import dev.loupe.engine.Scored
import dev.loupe.engine.TextState
import dev.loupe.kit.measure.CalibrationFit
import kotlin.math.abs
import kotlin.math.exp
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The calibration prior, the gates and Franco detection (Station's rules, the phone's files). JVM and iOS simulator. */
class ModelPriorTest {

    // ------------------------------------------------------------------ language groups (langgroup.py)

    @Test
    fun francoIsDetectedByDigitWordsAndItsWordList() {
        for (t in listOf(
            "el order gali bared w na2es el drink. law sama7t 3ayez a3raf hat3amlo eh.",
            "ya basha, 3andoko delivery le Maadi? w el menu feeh vegetarian options?",
            "shokran gedan 3al service el naharda, kol 7aga kanet tamam!",
            "salam, ana 3amel order men sa3a w lessa ma weselsh, el order rakamo 5521. momken te2olly howa fein?",
            "law sama7t ab3atly el report ennaharda darury, el system wa2ef",
        )) {
            assertTrue(LangGroup.isFranco(t), t)
            assertEquals(LangGroup.FRANCO, LangGroup.of(t), t)
        }
        for (t in listOf(
            "Meet me on the 3rd floor at 5pm, bring the mp3 and the 4k screen.", "Your code is a1b2c3 and the key xk2f9aq.",
            "hello world", "", "Your order has shipped. Tracking 1Z999AA10123456784.",
        )) assertFalse(LangGroup.isFranco(t), t)
        // One quoted Franco phrase in a long English email is not enough (hits under 8% of its words).
        val long = "Thank you for your message about the delivery schedule for next week. " .repeat(6) + "3ayez a3raf"
        assertFalse(LangGroup.isFranco(long))
        assertEquals(Triple(3, 4, 5), LangGroup.francoEvidence("3ayez a3raf el 7aga keda"))
    }

    @Test
    fun scriptAndLatinLanguageGroups() {
        assertEquals(LangGroup.AR, LangGroup.of("الطلب وصل ناقص صنفين ومحدش بيرد على التليفون"))
        assertEquals(LangGroup.AR, LangGroup.of("عميلنا العزيز، تم إيقاف بطاقتك مؤقتاً. Visit http://x.example"))
        assertEquals(LangGroup.EN, LangGroup.of("Your invoice is due on Friday. Please pay by bank transfer."))
        assertEquals(LangGroup.ES_FR, LangGroup.of("Hola, ¿pueden enviarme el catálogo y los precios para un pedido de camisetas?"))
        assertEquals(LangGroup.ES_FR, LangGroup.of("Découvrez notre nouvelle collection : des coussins et des bougies pour vous."))
        assertEquals(LangGroup.OTHER, LangGroup.of("Ihre Lieferung kommt morgen und ist nicht versichert, die Rechnung folgt."))
        assertEquals(LangGroup.OTHER, LangGroup.of("二重に請求されました。返金をお願いします。"))
        assertEquals(LangGroup.EN, LangGroup.of("12345"))
    }

    // ------------------------------------------------------------------ the calibration file

    private val station = """{"format": "loupe-calibration", "format_version": 1, "version": "t1",
        "checkpoints": {"multilingual": {"types": {"noul": {"T": 2.0, "b": 0.5}, "choice": {"T": 4.0}, "score": {"T": 1.0}},
            "languages": {"en": {"choice": {"T": 2.0}}}, "low_trust_languages": ["franco"]}}}"""

    @Test
    fun theFileIsReadWithStationsLookupOrderAndLowTrust() {
        val f = CalibrationFile.parse(station)
        assertEquals("t1", f.version)
        assertEquals(CalibrationCell(2.0, 0.0, "multilingual/choice/en"), f.cell("multilingual", "choice", "en"))
        assertEquals(CalibrationCell(4.0, 0.0, "multilingual/choice"), f.cell("multilingual", "choice", "ar"))
        assertEquals(CalibrationCell(2.0, 0.5, "multilingual/noul"), f.cell("multilingual", "noul", null))
        assertEquals(CalibrationCell(1.0, 0.0, "identity"), f.cell("english", "noul", "en"))
        assertTrue(f.lowTrust("multilingual", "franco"))
        assertFalse(f.lowTrust("multilingual", "ar"))
        assertFalse(f.lowTrust("multilingual", null))
    }

    @Test
    fun invalidCalibrationFilesAreRefused() {
        for (bad in listOf(
            """{"format": "x"}""",
            """{"format": "loupe-calibration", "format_version": 2, "checkpoints": {"m": {"types": {}}}}""",
            """{"format": "loupe-calibration", "format_version": 1, "checkpoints": {}}""",
            """{"format": "loupe-calibration", "format_version": 1, "checkpoints": {"m": {"types": {"noul": {"T": 0.01}}}}}""",
            """{"format": "loupe-calibration", "format_version": 1, "checkpoints": {"m": {"types": {"noul": {"T": 1, "b": 21}}}}}""",
            """{"format": "loupe-calibration", "format_version": 1, "checkpoints": {"m": {"types": {"choice": {"T": 1, "b": 0.3}}}}}""",
            """{"format": "loupe-calibration", "format_version": 1, "checkpoints": {"m": {"types": {"maybe": {"T": 1}}}}}""",
            "{not json",
        )) assertFailsWith<ModelPriorError>(bad) { CalibrationFile.parse(bad) }
    }

    @Test
    fun theFormulaIsStationsOnFixedLogits() {
        // noul: p(yes) = sigmoid((z_yes - z_no) / T + b); the option order here is (yes, no).
        val zYes = 3.0
        val zNo = -1.0
        val p = ModelPrior.apply(softmax(listOf(zYes, zNo)), ModelPrior.NOUL, 2.0, 0.5)
        assertClose(1.0 / (1.0 + exp(-((zYes - zNo) / 2.0 + 0.5))), p[0])
        // choice: softmax(z / T)
        val z = listOf(2.0, 0.0, -1.0)
        assertEquals(softmax(z.map { it / 4.0 }).map { r(it) }, ModelPrior.apply(softmax(z), ModelPrior.CHOICE, 4.0, 0.0).map { r(it) })
        // the fitting tool's formula (Station's order [z_no, z_yes]) agrees
        assertClose(p[0], CalibrationFit.apply(listOf(zNo, zYes), ModelPrior.NOUL, 2.0, 0.5)[1])
    }

    @Test
    fun aTemperatureNeverChangesTheAnswerAndOffIsTheIdentity() {
        val rnd = Random(7)
        val file = CalibrationFile.parse(station.replace("\"b\": 0.5", "\"b\": 0.0"))
        val prior = ModelPrior.recalibrator(true, file)
        val off = ModelPrior.recalibrator(false, file)
        for (k in listOf(2, 3, 5, 10)) repeat(200) {
            val labels = (0 until k).map { "o$it" }
            val j = Judgment.Choice("q", "Which?", labels, ordinal = k == 5)
            val raw = Distribution.of(labels.zip(softmax(labels.map { rnd.nextDouble(-15.0, 15.0) })).toMap())
            val state = TextState.build(listOf("i" to if (rnd.nextBoolean()) "An English text about an invoice." else "3ayez a3raf el 7aga keda"), 1000)
            val c = prior.calibrate(raw, j, state)
            assertEquals(raw.argmax, c.argmax)
            assertEquals(raw, off.calibrate(raw, j, state).distribution)
        }
    }

    @Test
    fun theEngineCalibratesByTypeAndLanguageWhenUseCalibrationIsOn() {
        val file = CalibrationFile.parse(station)
        val j = Judgment.Choice("k", "Which team?", listOf("billing", "technical", "sales"), FailurePosture.NULL_ACTION)
        val backend = dev.loupe.engine.Backend { _, _ -> Scored(mapOf("billing" to 0.9, "technical" to 0.08, "sales" to 0.02)) }
        fun top(text: String, on: Boolean) = DecisionEngine(backend, Probability.of(0.5), recalibrator = ModelPrior.recalibrator(on, file))
            .decide(j, Item("i", text)).row.distribution.getValue("billing").value
        assertClose(0.9, top("I was charged twice.", on = false))
        // English: the en/choice cell (T = 2); Arabic: the type's (T = 4)
        assertClose(ModelPrior.apply(listOf(0.9, 0.08, 0.02), "choice", 2.0, 0.0)[0], top("I was charged twice for the invoice.", on = true))
        assertClose(ModelPrior.apply(listOf(0.9, 0.08, 0.02), "choice", 4.0, 0.0)[0], top("تم خصم المبلغ مرتين", on = true))
    }

    @Test
    fun thePhishingReadingIsCalibratedWithTheYesNoCell() {
        val file = CalibrationFile.parse(station)
        val raw = 0.97
        val expected = ModelPrior.apply(listOf(raw, 1 - raw), ModelPrior.NOUL, 2.0, 0.5)[0]
        assertClose(expected, ModelPrior.calibratedPhishingProbability(raw, "Your account is limited, verify now", file = file))
        assertEquals(raw, ModelPrior.calibratedPhishingProbability(raw, "anything", useCalibration = false, file = file))
        assertFailsWith<IllegalArgumentException> { ModelPrior.calibratedPhishingProbability(1.5, "x") }
    }

    // ------------------------------------------------------------------ gates (test_gates.py)

    private val gates = GatesFile.parse(
        """{"format": "loupe-gates", "format_version": 1, "version": "g1", "checkpoints": {
            "multilingual": {"noul": {"act": 0.8, "confirm": 0.6, "languages": {"es-fr": {"act": 0.7}}}, "choice": {"act": null, "confirm": 0.5},
                             "phishing": {"act": 0.7, "confirm": 0.6}}}}""",
    )

    @Test
    fun bandsFixedRulesAndLanguageLines() {
        fun band(q: String, type: String, c: Double, lang: String? = null, low: Boolean = false) = ModelPrior.gate(q, type, c, lang, low, gates).band
        assertEquals(Band.ACT, band("needs_reply", "noul", 0.85))
        assertEquals(Band.CONFIRM, band("needs_reply", "noul", 0.7))
        assertEquals(Band.HUMAN, band("needs_reply", "noul", 0.55))
        assertEquals(Band.ACT, band("needs_reply", "noul", 0.75, "es-fr"))
        assertEquals(Band.CONFIRM, band("needs_reply", "noul", 0.75, "ar"))
        // null act = never
        assertEquals(Band.CONFIRM, band("kind", "choice", 0.99))
        // phishing never acts, whatever the file says; the cell is phishing
        val p = ModelPrior.gate("j-phishing", "noul", 0.99, "en", false, gates)
        assertEquals(Band.CONFIRM, p.band)
        assertNull(p.act)
        assertEquals("multilingual/phishing", p.cell)
        // a low-trust language (Franco) is always a person's
        val f = ModelPrior.gate("needs_reply", "noul", 0.99, "franco", true, gates)
        assertEquals(Band.HUMAN, f.band)
        assertEquals("low_trust_language", f.reason)
        // no file: a person answers
        assertEquals(Band.HUMAN, ModelPrior.gate("q", "noul", 0.99, "en", false, null).band)
    }

    @Test
    fun francoAnswersGoToAPersonEndToEnd() {
        val j = Judgment.Choice("j-needs-reply", "Is this waiting on a response from me?", listOf("waiting on my response", "not waiting on me"))
        val a = ModelPrior.calibrate(j, mapOf("waiting on my response" to 0.999, "not waiting on me" to 0.001),
            "law sama7t 3ayez a3raf el order fein", file = CalibrationFile.parse(station), gatesFile = gates)
        assertEquals(LangGroup.FRANCO, a.language)
        assertTrue(a.lowTrust)
        assertEquals(Band.HUMAN, a.gate.band)
    }

    @Test
    fun invalidGatesFilesAreRefused() {
        for (bad in listOf(
            """{"format": "x"}""",
            """{"format": "loupe-gates", "format_version": 1, "checkpoints": {"english": {"noul": {"act": 2}}}}""",
            """{"format": "loupe-gates", "format_version": 1, "checkpoints": {"english": {"noul": {"act": 0.5, "languages": {"ar": {"confirm": -1}}}}}}""",
        )) assertFailsWith<ModelPriorError>(bad) { GatesFile.parse(bad) }
    }

    @Test
    fun theSweepFollowsJevalsRulesByHand() {
        fun recs(spec: List<Triple<Double, Boolean, String>>) = spec.map { (c, ok, pred) ->
            CalibrationFit.Record(c, ok, pred, if (ok) pred else if (pred == "1") "0" else "1")
        }
        val rs = recs(listOf(Triple(0.95, true, "1"), Triple(0.9, false, "1"), Triple(0.6, true, "1"), Triple(0.8, true, "0")))
        val a = CalibrationFit.Costs(10.0, 1.0, 0.0)
        assertClose(11.0 / 4, CalibrationFit.cost("1", a, rs, 0.0))
        assertClose(3.0 / 4, CalibrationFit.cost("1", a, rs, 0.92))
        assertClose(1.0, CalibrationFit.cost("1", a, rs, 1.0))
        val many = recs(List(20) { Triple(0.99, true, "1") } + List(10) { Triple(0.7, false, "1") } + List(10) { Triple(0.6, true, "0") })
        assertEquals(0.99, CalibrationFit.sweep("1", a, many).threshold)
        val few = CalibrationFit.sweep("1", a, many.take(29))
        assertNull(few.threshold)
        assertTrue("below the 30" in few.reason)
    }

    @Test
    fun theFitRecoversATemperatureAndOnlyKeepsCellsThatEarnTheirPlace() {
        // Answers from a model 4x over-confident: logits scaled by 4 of well-calibrated ones.
        val rnd = Random(3)
        val rows = (0 until 400).map { i ->
            val d = rnd.nextDouble(-3.0, 3.0)
            val y = if (rnd.nextDouble() < 1.0 / (1.0 + exp(-d))) 1 else 0
            CalibrationFit.Row("noul", if (i % 5 == 0) "ar" else "en", "t$i", listOf(0.0, 4.0 * d), y)
        }
        val fit = CalibrationFit.fitAll(rows, allowBias = false)
        val t = fit.types.getValue("noul")
        assertEquals("temperature", t.method)
        assertTrue(abs(t.t - 4.0) < 0.6, "T=${t.t}")
        assertTrue(fit.languages.isEmpty(), "no language cell beats the type's out of sample: ${fit.selection}")
        val cv = CalibrationFit.crossValidated(rows, fit)
        assertEquals(rows.size, cv.size)
        // the ones the fit writes, read back by the app's reader
        val file = CalibrationFile.parse(CalibrationFit.calibrationJson(fit, "2026-09-27", "test", "synthetic"))
        assertClose(t.t, file.cell("multilingual", "noul", "en").temperature, 1e-4)
        GatesFile.parse(CalibrationFit.gatesJson(CalibrationFit.fitGates(rows, cv), "2026-09-27", "test", "test"))
    }

    // ------------------------------------------------------------------ the shipped files

    @Test
    fun theShippedFilesLoadAndKeepTheFixedRules() {
        assertNull(ModelPrior.loadError)
        val cal = ModelPrior.calibration
        assertTrue(cal.has(ModelPrior.CHECKPOINT))
        assertTrue(cal.lowTrust(ModelPrior.CHECKPOINT, LangGroup.FRANCO))
        for (type in CalibrationFile.TYPES) assertEquals(0.0, cal.cell(ModelPrior.CHECKPOINT, type, null).bias, "the phone ships temperatures only")
        val g = ModelPrior.gates!!
        assertNull(g.lines(ModelPrior.CHECKPOINT, ModelPrior.PHISHING, null).first, "phishing never acts")
        for (key in listOf("noul", "choice", "score")) {
            val (act, confirm) = g.lines(ModelPrior.CHECKPOINT, key, null)
            assertTrue(act == null || confirm == null || act >= confirm)
        }
    }

    private fun softmax(z: List<Double>): List<Double> {
        val m = z.max()
        val e = z.map { exp(it - m) }
        return e.map { it / e.sum() }
    }

    private fun r(x: Double) = kotlin.math.round(x * 1e9) / 1e9

    private fun assertClose(expected: Double, actual: Double, tol: Double = 1e-9) =
        assertTrue(abs(expected - actual) <= tol, "expected $expected, was $actual")
}
