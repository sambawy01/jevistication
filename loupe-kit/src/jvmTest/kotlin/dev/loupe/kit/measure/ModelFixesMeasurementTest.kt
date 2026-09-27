package dev.loupe.kit.measure

import dev.loupe.backend.onnx.HuggingFaceSubwordEncoder
import dev.loupe.backend.onnx.LayaPrompt
import dev.loupe.backend.onnx.LayaSpecialTokens
import dev.loupe.backend.onnx.LayaTokenizer
import dev.loupe.backend.onnx.OnnxBackend
import dev.loupe.backend.onnx.TensorNames
import dev.loupe.engine.DecisionEngine
import dev.loupe.engine.FailurePosture
import dev.loupe.engine.Judgment
import dev.loupe.engine.TextState
import dev.loupe.kit.mail.EMAIL_CASES
import dev.loupe.kit.packs.PackFormat
import dev.loupe.kit.packs.PackParse
import dev.loupe.kit.watchers.EXAMPLE_PACK
import dev.loupe.kit.watchers.SAMPLE_DIR
import dev.loupe.kit.watchers.TEST_TMP
import dev.loupe.kit.watchers.sampleReaders
import dev.loupe.persistence.JsonValue
import dev.loupe.sources.common.SourceRoot
import dev.loupe.sources.common.SourceScanner
import dev.loupe.sources.common.SourceType
import dev.loupe.templates.Shape
import dev.loupe.templates.Template
import dev.loupe.templates.TemplateLibrary
import dev.loupe.templates.UserJudgment
import kotlinx.datetime.TimeZone
import org.junit.Assume.assumeTrue
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The owner-approved model fixes of 2026-09-27 (#2 absence phrasing, #3 at most 10 options, #7 plain
 * opposites), measured with the real multilingual model — **gated** on `models/` (skipped, not failed,
 * without the weights), like `ReceiptGateMeasurementTest` and `PilotMeasurementTest`.
 *
 * It scores every labelled item the repository has, with the **live** template library, so running
 * it before and after a wording change measures that change on the same items:
 *
 * - `templates`: every template's worked examples (the answer each example states).
 * - `sample`: the 45 synthetic sample items with the hand labels of `sample-labels.tsv`
 *   (is-receipt, phishing, needs-reply).
 * - `sample+`, `mail+`: labels written on 2026-09-27 for this measurement (below) — the sample and
 *   Station's 22 realistic emails against the seven #7 templates and `urgency`. Marked apart because
 *   the person measuring wrote them.
 * - `mail`: Station's 22 realistic emails (`EMAIL_CASES`) with their phishing truth, against `phishing`.
 * - `receipt-gate`: the six hand-written documents of the receipt-gate measurement, against is-receipt.
 * - `golden`, `criteria`, `score-rev`: the pinned model fixtures, with the answer each case was
 *   written to have (their files pin probabilities, not labels; the labels here are the obvious ones).
 * - `pack`: the example pack's sample texts (English, Egyptian Arabic, Franco-Arabic) against its
 *   own questions, labelled here.
 *
 * Per question type (noul = two options, choice, score) and language it prints accuracy, ECE (10
 * equal-width bins on the top probability), and the share of wrong answers at ≥ 0.9 and ≥ 0.99; and
 * fits per-type temperatures (2-fold cross-validated) as a cross-check on Station's reported values.
 * Nothing is shipped from here. Invented, small data: a direction, not an accuracy claim.
 */
class ModelFixesMeasurementTest {

    private val root = File(SAMPLE_DIR.substringBefore("/sources-desktop/"))
    private val models = File(System.getProperty("loupe.models.dir") ?: File(root, "models").path)
    private val tokenizer = File(models, "laya-multilingual/tokenizer/tokenizer.json")
    private val graph = File(models, "laya-multilingual-onnx/laya-multilingual-choice.int8.onnx")

    /** One labelled question on one text. [probs] is filled in by the run. */
    internal class Case(
        val set: String,
        val id: String,
        val lang: String,
        val type: String,
        val judgment: Judgment.Choice,
        val text: String,
        val label: String,
    ) {
        var probs: List<Double> = emptyList()
        val top: Int get() = probs.indices.maxBy { probs[it] }
        val conf: Double get() = probs[top]
        val correct: Boolean get() = judgment.candidates[top] == label
    }

    private fun typeOf(shape: Shape): String = when (shape) {
        Shape.YesNo, is Shape.Binary -> "noul"
        is Shape.Pick -> "choice"
        is Shape.Ordinal -> "score"
    }

    private fun instantiate(id: String): UserJudgment {
        val t = TemplateLibrary.byId(id) ?: error("no template $id")
        val values = t.parameters.associate { it.name to it.example }
        return (t.instantiate("m-$id", values) as Template.InstantiateResult.Created).judgment
    }

    // ------------------------------------------------------------------ labels written for this run

    /**
     * The sample against the #7 templates and `urgency`, written 2026-09-27 by the person measuring,
     * against each template's three-part criteria. `+` the positive option, `-` the negative, a digit
     * an urgency band, `?` left out (ambiguous under the criteria). Columns: impostor-sender,
     * claims-brand (brand = PayPal, the template's example), is-junk, unsubscribe-candidate,
     * refetchable-download, superseded-version, urgency.
     */
    private val sampleExtra = """
        documents/bank/northbank-statement-2026-08.pdf	-	-	-	-	-	-	0
        documents/bills/brightside-plumbing-invoice-INV-2207.txt	-	-	-	-	-	-	?
        documents/downloads/kettle-kx200-manual.pdf	-	-	-	-	+	-	0
        documents/health/riverside-surgery-appointment.txt	-	-	-	-	-	-	?
        documents/home/boiler-service-certificate.txt	-	-	-	-	-	-	0
        documents/home/tenancy-agreement-signed.md	-	-	-	-	-	-	0
        documents/identity/driving-licence-SPECIMEN.txt	-	-	-	-	-	-	0
        documents/identity/passport-scan-SPECIMEN.txt	-	-	-	-	-	-	?
        documents/insurance/home-insurance-renewal-2025.pdf	-	-	-	-	-	-	0
        documents/insurance/home-insurance-renewal-2026.pdf	-	-	-	-	-	-	?
        documents/personal/birthday-card-from-grandma.txt	-	-	-	-	-	-	0
        documents/receipts/cafe-luna-2026-09-02.txt	-	-	-	-	-	-	0
        documents/receipts/fresh-basket-2026-08-14 (copy).txt	-	-	-	-	-	-	0
        documents/receipts/fresh-basket-2026-08-14.txt	-	-	-	-	-	-	0
        documents/receipts/homeware-direct-invoice-kettle.txt	-	-	-	-	-	-	0
        documents/travel/casa-azul-booking.html	-	-	-	-	-	-	0
        documents/travel/itinerary-lisbon.json	-	-	-	-	-	-	0
        documents/work/expenses-2026-q3.csv	-	-	-	-	-	-	?
        documents/work/meeting-notes-2026-09-12.md	-	-	-	-	-	-	?
        documents/work/project-plan-FINAL.md	-	-	-	-	-	-	0
        documents/work/project-plan-v1.md	-	-	-	-	-	+	0
        mail/inbox/brightside-reminder.eml	-	-	-	-	-	-	?
        mail/inbox/council-tax-bill.eml	-	-	-	-	-	-	?
        mail/inbox/insurer-renewal.eml	-	-	-	-	-	-	0
        mail/inbox/mum-new-number.eml	+	-	-	-	-	-	?
        mail/inbox/mum-photos.eml	-	-	-	-	-	-	0
        mail/inbox/mum-sunday-lunch.eml	-	-	-	-	-	-	?
        mail/inbox/northline-flight-booking.eml	-	-	-	-	-	-	0
        mail/inbox/phishing-paypal.eml	+	+	?	-	-	-	?
        mail/inbox/priya-handover.eml	-	-	-	-	-	-	2
        mail/inbox/skyport-schedule-change.eml	-	-	-	-	-	-	?
        mail/subscriptions-2026.mbox#1	-	-	-	-	-	-	0
        mail/subscriptions-2026.mbox#2	-	-	-	-	-	-	0
        mail/subscriptions-2026.mbox#3	-	-	-	-	-	-	0
        mail/subscriptions-2026.mbox#4	-	-	-	-	-	-	0
        mail/subscriptions-2026.mbox#5	-	-	-	-	-	-	0
        mail/subscriptions-2026.mbox#6	-	-	-	-	-	-	0
        mail/subscriptions-2026.mbox#7	-	-	-	-	-	-	0
        mail/subscriptions-2026.mbox#8	-	-	-	-	-	-	0
        mail/subscriptions-2026.mbox#9	-	-	-	-	-	-	?
        mail/subscriptions-2026.mbox#10	-	-	-	-	-	-	?
        mail/subscriptions-2026.mbox#11	-	-	-	+	-	-	0
        mail/subscriptions-2026.mbox#12	-	-	-	+	-	-	0
        mail/subscriptions-2026.mbox#13	-	-	?	+	-	-	0
        mail/subscriptions-2026.mbox#14	-	-	-	-	-	-	?
    """.trimIndent()

    private val extraColumns = listOf(
        "impostor-sender", "claims-brand", "is-junk", "unsubscribe-candidate", "refetchable-download", "superseded-version", "urgency",
    )

    /** Station's emails against impostor-sender, claims-brand (PayPal) and unsubscribe-candidate; same author and date. */
    private val mailExtra = mapOf(
        "google-alert" to "---", "godaddy-receipt" to "---", "cib-maintenance" to "---", "cib-maintenance-ar" to "---",
        "cloudflare-event" to "--+", "jomashop-sale" to "--+", "customer-catering" to "---", "supplier-invoice" to "---",
        "paypal-phish" to "++-", "m365-phish" to "+--", "cib-phish-ar" to "+--", "dhl-phish" to "+--",
        "lottery-spam" to "?--", "customer-complaint-arz" to "---", "customer-order-es" to "---", "newsletter-fr" to "--+",
        "linkedin-social" to "--+", "aramex-shipping" to "---", "partner-meeting" to "---", "canva-reset" to "---",
        "tax-reminder-ar" to "---", "stripe-payout" to "---",
    )

    /** The answer each pinned fixture case was written to have (`?`: no single right answer; `#i`: the i-th option). */
    private val fixtureLabels = mapOf(
        "en-billing-3" to "billing", "en-receipt-2" to "yes", "en-oneword-2" to "no", "en-sentiment-5" to "2",
        "en-intent-10" to "alarm", "en-doc-6" to "passport", "en-phish-2" to "yes", "en-long-4" to "a subscription charge",
        "en-longopts-3" to "#0", "en-masklit-2" to "yes", "en-reply-3" to "yes, soon", "en-spam-2" to "spam", "de-cancel-4" to "Vertrag kündigen",
        "de-short-2" to "ja", "fr-support-4" to "technique", "es-urgent-3" to "alta", "pt-receipt-2" to "sim",
        "it-topic-5" to "sport", "nl-intent-3" to "thermostaat", "ru-billing-3" to "биллинг", "pl-sentiment-3" to "negatywny",
        "tr-2" to "evet", "ar-intent-4" to "إلغاء الاشتراك", "he-2" to "כן", "hi-billing-3" to "billing", "ja-2" to "はい",
        "zh-topic-4" to "经济", "ko-intent-3" to "알람", "th-2" to "ใช่", "vi-3" to "đặt lại mật khẩu", "mixed-8" to "?",
        "en-json-4" to "notify user", "en-7" to "utility bill", "en-9" to "insurance",
        "crit-receipt-yes" to "a receipt or proof of purchase", "crit-receipt-quote" to "not a receipt",
        "crit-phishing" to "a scam or phishing attempt", "crit-mixed-3" to "billing", "crit-ordinal-4" to "3",
        "crit-long-desc" to "a receipt or proof of purchase", "crit-shrink-12" to "?", "crit-forged-mask" to "yes",
        "rev-urgency-en-today" to "3", "rev-urgency-en-none" to "0", "rev-urgency-ar" to "3", "rev-triage-arz" to "2",
        "rev-triage-franco" to "3",
    )

    /** The example pack's sample texts against its own questions; `?` left out. */
    private val packLabels = mapOf(
        "complaint-triage" to mapOf("team" to "delivery", "frustration" to "3", "refund_requested" to "+", "wants_callback" to "+", "packaging_issue" to "+"),
        "review-sentiment" to mapOf("sentiment" to "positive", "mentions_food_quality" to "+", "mentions_delivery_speed" to "+", "would_order_again" to "+"),
        "order-note-tags" to mapOf("tag" to "allergy_or_dietary", "is_allergy_safety_critical" to "+"),
        "review-triage" to mapOf("sentiment" to "mixed", "topic" to "packaging", "needs_reply" to "+", "wants_compensation" to "-", "toxic" to "-"),
        "dm-intent" to mapOf("intent" to "order_status", "order_problem" to "+", "is_franco_arabic" to "+"),
        "social-post-gate" to mapOf("mentions_amount" to "+", "mentions_dine_in" to "+", "makes_promise" to "+", "health_claim" to "-", "personal_data" to "-", "tone" to "?"),
        "gmail-triage" to mapOf("category" to "delivery_platform", "urgency" to "2", "needs_reply" to "+", "is_phishing" to "-"),
    )
    private val packLang = mapOf("complaint-triage" to "arz", "review-triage" to "arz", "dm-intent" to "franco")

    /** The receipt-gate measurement's six hand-written documents (ReceiptGateMeasurementTest), with its labels. */
    private val receiptGate: List<Triple<String, String, Boolean>> = listOf(
        Triple("stroller", "File: Bugaboo Donkey 5 Mono complete stroller.pdf\n\nBugaboo Donkey 5 Mono complete stroller\nBlack / Grey Melange\n4.8 (212 reviews)\n£1,199.00\nPay in 3 interest-free payments of £399.67 with Klarna.\nColour: Black\nAdd to basket\nIn stock – Free delivery on orders over £50\nProduct details\nConverts from a mono to a side-by-side duo stroller in 3 easy steps. 2-year warranty.\nSpecifications\nWeight 11.2 kg.\nCustomer reviews\nYou may also like\nBugaboo Butterfly £399.00", false),
        Triple("stroller-ar", "File: عربة أطفال.pdf\n\nعربة أطفال بوغابو دونكي\n٤٫٨ (٢١٢ تقييمات)\n٤٬٩٩٩ ريال\nأضف إلى السلة\nاشتر الآن\nمتوفر في المخزون — توصيل مجاني\nتفاصيل المنتج", false),
        Triple("till-receipt", "File: receipt-2026-08-14.pdf\n\nJohn Lewis & Partners, Oxford Street\nReceipt No. 8841-2210-77\n14/08/2026 13:42\nBugaboo Donkey 5 Mono      £1,199.00\nTOTAL                      £1,199.00\nPaid by VISA ************4412", true),
        Triple("receipt-ar", "File: فاتورة-متجر.pdf\n\nمتجر النور\nفاتورة ضريبية مبسطة\nرقم الفاتورة: 20931\nالإجمالي: ١٣٫٠٠ ريال\nطريقة الدفع: مدى\nتم الدفع", true),
        Triple("order", "Subject: Order confirmation #A1029384\n\nThank you for your order. Amount charged £59.99 to your card ending in 1111.", true),
        Triple("invoice-due", "File: inv.pdf\n\nInvoice INV-2207 from Brightside Plumbing. Amount due £180.00 by 30 September 2026.", false),
    )

    /** Two-option wordings compared on the same items, positive first; the first pair is main's. */
    private val ARMS: List<Pair<String, List<Pair<String, String>>>> = listOf(
        "phishing" to listOf(
            "a scam or phishing attempt" to "an ordinary message",
            "a scam or phishing attempt" to "not a scam or phishing attempt",
            "a phishing attempt" to "not a phishing attempt",
        ),
        "impostor-sender" to listOf(
            "someone pretending to be someone else" to "consistent with its sender",
            "someone pretending to be someone else" to "not someone pretending to be someone else",
            "an impostor" to "not an impostor",
        ),
        "claims-brand" to listOf(
            "claims to come from that brand" to "does not claim to be that brand",
            "claims to come from that brand" to "does not claim to come from that brand",
            "claims to be that brand" to "does not claim to be that brand",
        ),
        "is-junk" to listOf(
            "worthless to keep" to "worth keeping",
            "worthless to keep" to "not worthless to keep",
            "junk" to "not junk",
        ),
        "unsubscribe-candidate" to listOf(
            "bulk mail I no longer read" to "mail worth keeping",
            "bulk mail I no longer read" to "not bulk mail I no longer read",
            "bulk mail" to "not bulk mail",
        ),
        "refetchable-download" to listOf(
            "a download I could fetch again" to "personal or one-off",
            "a download I could fetch again" to "not a download I could fetch again",
            "a public download" to "not a public download",
        ),
        "superseded-version" to listOf(
            "an earlier, superseded version" to "a current or unique version",
            "an earlier, superseded version" to "not an earlier, superseded version",
            "a superseded version" to "not a superseded version",
            "an earlier version" to "not an earlier version",
        ),
    )

    // ------------------------------------------------------------------ building the cases

    private fun posNeg(j: UserJudgment, mark: String): String? = when (mark) {
        "+" -> j.shape.candidates[0]
        "-" -> j.shape.candidates[1]
        else -> null
    }

    internal fun cases(): List<Case> {
        val out = mutableListOf<Case>()

        // Templates: every worked example.
        for (t in TemplateLibrary.ALL) {
            val j = instantiate(t.id)
            t.examples.forEachIndexed { i, ex ->
                out += Case("templates", "${t.id}#$i", "en", typeOf(j.shape), j.choice, ex.text, ex.answer)
            }
        }

        // The sample, as the phone's scanner reads it.
        val items = SourceScanner(sampleReaders(), TimeZone.UTC).scan(
            listOf(
                SourceRoot("sample", SourceType.FOLDER, "$SAMPLE_DIR/documents", "sample:documents/"),
                SourceRoot("sample", SourceType.MAIL_EXPORT, "$SAMPLE_DIR/mail", "sample:mail/"),
            ),
        ).items.filter { it.hasText }.associateBy { it.id.removePrefix("sample:") }
        val repoLabels = File(root, "loupe-desktop/src/test/resources/sample-labels.tsv").readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split('\t') }
        assertEquals(45, repoLabels.size, "the 45 labelled sample items")
        val repoJudgments = listOf("is-receipt", "phishing", "needs-reply").map(::instantiate)
        for (row in repoLabels) {
            val item = items[row[0]] ?: error("sample item ${row[0]} not scanned; have ${items.keys}")
            repoJudgments.forEachIndexed { k, j ->
                out += Case("sample", "${j.templateId}:${row[0]}", "en", "noul", j.choice, item.text, posNeg(j, row[k + 1])!!)
            }
        }
        val extraJudgments = extraColumns.map(::instantiate)
        for (line in sampleExtra.lines()) {
            val row = line.split('\t')
            val item = items[row[0]] ?: error("sample item ${row[0]} not scanned")
            extraJudgments.forEachIndexed { k, j ->
                val mark = row[k + 1]
                val label = if (j.shape is Shape.Ordinal) mark.takeIf { it != "?" } else posNeg(j, mark)
                if (label != null) out += Case("sample+", "${j.templateId}:${row[0]}", "en", typeOf(j.shape), j.choice, item.text, label)
            }
        }

        // Station's realistic emails.
        val phishing = instantiate("phishing")
        val mailJ = listOf("impostor-sender", "claims-brand", "unsubscribe-candidate").map(::instantiate)
        assertEquals(EMAIL_CASES.map { it.id }.toSet(), mailExtra.keys)
        for (c in EMAIL_CASES) {
            val text = "From: ${c.sender}\nSubject: ${c.subject}\n\n${c.body}"
            val lang = when {
                c.id.endsWith("-arz") -> "arz"
                c.id.endsWith("-ar") -> "ar"
                c.id.endsWith("-es") || c.id.endsWith("-fr") -> "other"
                else -> "en"
            }
            out += Case("mail", "phishing:${c.id}", lang, "noul", phishing.choice, text, posNeg(phishing, if (c.phishing) "+" else "-")!!)
            mailExtra.getValue(c.id).forEachIndexed { k, mark ->
                posNeg(mailJ[k], mark.toString())?.let { out += Case("mail+", "${mailJ[k].templateId}:${c.id}", lang, "noul", mailJ[k].choice, text, it) }
            }
        }

        // The receipt-gate documents.
        val receipt = instantiate("is-receipt")
        for ((id, text, yes) in receiptGate) {
            out += Case("receipt-gate", "is-receipt:$id", if (id.endsWith("-ar")) "ar" else "en", "noul", receipt.choice, text, posNeg(receipt, if (yes) "+" else "-")!!)
        }

        // The pinned fixtures.
        fun fixture(file: String, set: String) {
            val cases = JsonValue.parse(File(root, "backend-onnx/src/test/resources/laya/$file").readText()).asObj["cases"]!!.asArr.items
            for (raw in cases) {
                val o = raw.asObj
                val id = o["id"]!!.asString
                val written = fixtureLabels[id] ?: error("no label for fixture case $id")
                if (written == "?") continue
                val candidates = o["candidates"]!!.asArr.items.map { it.asString }
                // "#i": the i-th option (for options too long to repeat here).
                val label = if (written.startsWith("#")) candidates[written.drop(1).toInt()] else written
                val descriptions = (o["descriptions"]?.takeUnless { it.isNull }?.asArr?.items ?: emptyList())
                    .mapIndexedNotNull { i, d -> d.takeUnless { it.isNull }?.asString?.takeIf { it.isNotBlank() }?.let { candidates[i] to it } }.toMap()
                val ordinal = set == "score-rev" || id == "crit-ordinal-4" || id == "en-sentiment-5"
                val type = when {
                    ordinal -> "score"
                    candidates.size == 2 -> "noul"
                    else -> "choice"
                }
                val lang = when {
                    id.startsWith("en-") || id.startsWith("crit-") -> "en"
                    id.startsWith("ar-") || id == "rev-urgency-ar" -> "ar"
                    id.endsWith("-arz") -> "arz"
                    id.endsWith("-franco") -> "franco"
                    id.startsWith("rev-urgency-en") -> "en"
                    else -> "other"
                }
                val j = Judgment.Choice("fx-$id", o["question"]!!.asString, candidates, FailurePosture.NULL_ACTION, descriptions, ordinal = ordinal)
                assertTrue(label in candidates, "$id: $label")
                out += Case(set, id, lang, type, j, o["state"]!!.asString, label)
            }
        }
        fixture("golden.json", "golden")
        fixture("criteria.json", "criteria")
        fixture("score-reversed.json", "score-rev")

        // The example pack, as Station asks it: noul options are the true/false descriptions (or
        // bare yes/no), a score's levels are read as the descriptions of 1..n.
        val pack = (PackFormat.parse(File(EXAMPLE_PACK).readText()) as PackParse.Valid).pack
        for (p in pack.presets) {
            val labels = packLabels.getValue(p.id)
            for (q in p.questions) {
                val mark = labels[q.id] ?: error("no label for ${p.id}.${q.id}")
                if (mark == "?") continue
                val (j, label) = when (q.type) {
                    "noul" -> {
                        val t = q.descriptions["true"]?.takeIf { q.descriptions["false"] != null } ?: "yes"
                        val f = q.descriptions["false"]?.takeIf { q.descriptions["true"] != null } ?: "no"
                        Judgment.Choice("pk-${p.id}-${q.id}", q.instructions, listOf(t, f)) to (if (mark == "+") t else f)
                    }
                    "choice" -> Judgment.Choice(
                        "pk-${p.id}-${q.id}", q.instructions, q.options, FailurePosture.NULL_ACTION,
                        q.descriptions.filterValues { !it.isNullOrBlank() }.mapValues { it.value!! },
                    ) to mark
                    else -> Judgment.Choice(
                        "pk-${p.id}-${q.id}", q.instructions, q.options.indices.map { (it + 1).toString() }, FailurePosture.NULL_ACTION,
                        q.options.mapIndexed { i, level -> (i + 1).toString() to level }.toMap(), ordinal = true,
                    ) to mark
                }
                assertTrue(label in j.candidates, "${p.id}.${q.id}: $label")
                out += Case("pack", "${p.id}.${q.id}", packLang[p.id] ?: "en", q.type, j, p.sampleText, label)
            }
        }
        return out
    }

    // ------------------------------------------------------------------ metrics

    private data class Stats(val n: Int, val acc: Double, val ece: Double, val wrong90: Double, val wrong99: Double, val nll: Double, val wrong: Int)

    private fun scaled(p: List<Double>, t: Double): List<Double> {
        val logs = p.map { ln(it.coerceAtLeast(1e-12)) / t }
        val m = logs.max()
        val e = logs.map { exp(it - m) }
        val s = e.sum()
        return e.map { it / s }
    }

    private fun stats(cs: List<Case>, t: Double = 1.0): Stats {
        if (cs.isEmpty()) return Stats(0, 0.0, 0.0, 0.0, 0.0, 0.0, 0)
        val rows = cs.map { c ->
            val p = scaled(c.probs, t)
            val top = p.indices.maxBy { p[it] }
            Triple(p[top], c.judgment.candidates[top] == c.label, p[c.judgment.candidates.indexOf(c.label)])
        }
        val bins = Array(10) { mutableListOf<Pair<Double, Boolean>>() }
        for ((conf, ok, _) in rows) bins[minOf(9, (conf * 10).toInt())] += conf to ok
        val ece = bins.sumOf { b -> if (b.isEmpty()) 0.0 else b.size.toDouble() / rows.size * abs(b.count { it.second }.toDouble() / b.size - b.sumOf { it.first } / b.size) }
        val wrong = rows.filter { !it.second }
        return Stats(
            n = rows.size,
            acc = rows.count { it.second }.toDouble() / rows.size,
            ece = ece,
            wrong90 = if (wrong.isEmpty()) 0.0 else wrong.count { it.first >= 0.9 }.toDouble() / wrong.size,
            wrong99 = if (wrong.isEmpty()) 0.0 else wrong.count { it.first >= 0.99 }.toDouble() / wrong.size,
            nll = rows.sumOf { -ln(it.third.coerceAtLeast(1e-12)) } / rows.size,
            wrong = wrong.size,
        )
    }

    /** The temperature minimising NLL over [cs], by a log-spaced grid then a local refinement. */
    private fun fitT(cs: List<Case>): Double {
        var best = 1.0
        var bestNll = Double.MAX_VALUE
        var lo = ln(0.05)
        var hi = ln(200.0)
        repeat(4) {
            val step = (hi - lo) / 60
            for (i in 0..60) {
                val t = exp(lo + i * step)
                val nll = stats(cs, t).nll
                if (nll < bestNll) { bestNll = nll; best = t }
            }
            lo = ln(best) - 2 * (hi - lo) / 60
            hi = ln(best) + 2 * (hi - lo) / 60
        }
        return best
    }

    /** 2-fold CV: fit on one half, measure ECE on the other, both ways; pooled held-out ECE. */
    private fun crossValidated(cs: List<Case>): Pair<Double, Double> {
        val sorted = cs.sortedBy { (it.set + it.id + it.judgment.id).hashCode() }
        val a = sorted.filterIndexed { i, _ -> i % 2 == 0 }
        val b = sorted.filterIndexed { i, _ -> i % 2 == 1 }
        val ta = fitT(a)
        val tb = fitT(b)
        val heldA = stats(b, ta)
        val heldB = stats(a, tb)
        val ece = (heldA.ece * heldA.n + heldB.ece * heldB.n) / (heldA.n + heldB.n)
        val raw = stats(cs).ece
        return raw to ece
    }

    private fun line(label: String, s: Stats, note: String = ""): String = String.format(
        Locale.ROOT, "%-34s n=%4d  acc %5.1f%%  ECE %.3f  wrong %3d: ≥0.9 %5.1f%%  ≥0.99 %5.1f%%  NLL %.3f%s",
        label, s.n, s.acc * 100, s.ece, s.wrong, s.wrong90 * 100, s.wrong99 * 100, s.nll, if (note.isEmpty()) "" else "  $note",
    )

    @Test
    fun `measures the model on every labelled set, per type and language`() {
        assumeTrue("Laya tokenizer not present at $tokenizer; skipping", tokenizer.isFile)
        assumeTrue("Laya INT8 graph not present at $graph; skipping", graph.isFile)

        val cases = cases()
        val encoder = HuggingFaceSubwordEncoder.openLayaMultilingual(tokenizer.toPath())
        val backend = OnnxBackend.open(graph.toPath(), LayaTokenizer(LayaPrompt(encoder, LayaSpecialTokens.MULTILINGUAL)), TensorNames.LAYA)
        fun score(j: Judgment.Choice, id: String, text: String): List<Double> {
            val masses = j.validate(backend.score(j, TextState.build(listOf(id to text), DecisionEngine.DEFAULT_STATE_BUDGET)).masses)
            return j.candidates.map { masses.getValue(it).value }
        }
        // Wording arms for the #7 templates, on the same items: the wording on main (2026-09-27, before
        // the change), the shipped plain opposite, and the alternatives tried.
        val armResults = mutableListOf<Pair<String, Stats>>()
        try {
            for (c in cases) c.probs = score(c.judgment, c.id, c.text)
            for ((id, pairs) in ARMS) {
                val base = cases.filter { it.judgment.id == "m-$id" }
                for ((pos, neg) in pairs) {
                    val arm = base.map { c ->
                        val j = c.judgment.copy(candidates = listOf(pos, neg))
                        Case(c.set, c.id, c.lang, c.type, j, c.text, j.candidates[c.judgment.candidates.indexOf(c.label)])
                            .also { it.probs = score(j, c.id, c.text) }
                    }
                    val bySet = arm.groupBy { it.set }.entries.joinToString("  ") { (set, cs) -> "$set ${cs.count { it.correct }}/${cs.size}" }
                    armResults += "$id  [$pos | $neg]  ($bySet)" to stats(arm)
                }
            }
        } finally {
            backend.close()
            encoder.close()
        }

        val report = StringBuilder()
        fun say(s: String) { println("[model-fixes] $s"); report.appendLine(s) }

        say("items: ${cases.size} labelled questions; sets ${cases.groupingBy { it.set }.eachCount()}")
        say("")
        say("== by type (all sets)")
        for (type in listOf("noul", "choice", "score")) say(line(type, stats(cases.filter { it.type == type })))
        say("== by type, repo labels only (templates, sample, mail, receipt-gate)")
        val repo = cases.filter { it.set in setOf("templates", "sample", "mail", "receipt-gate") }
        for (type in listOf("noul", "choice", "score")) say(line(type, stats(repo.filter { it.type == type })))
        say("== by type and language")
        for (type in listOf("noul", "choice", "score")) for (lang in listOf("en", "ar", "arz", "franco", "other")) {
            val cs = cases.filter { it.type == type && it.lang == lang }
            if (cs.isNotEmpty()) say(line("$type/$lang", stats(cs), if (cs.size < 30) "(n too small to conclude)" else ""))
        }
        say("== by set")
        for (set in cases.map { it.set }.distinct()) say(line(set, stats(cases.filter { it.set == set })))

        say("== the judgments this change touches (per question)")
        val touched = listOf(
            "phishing", "impostor-sender", "claims-brand", "is-junk", "unsubscribe-candidate", "refetchable-download", "superseded-version", "urgency",
        )
        for (id in touched) {
            val cs = cases.filter { it.judgment.id == "m-$id" }
            val s = stats(cs)
            val j = cs.firstOrNull()?.judgment
            val pLabel = if (cs.isEmpty()) 0.0 else cs.sumOf { it.probs[it.judgment.candidates.indexOf(it.label)] } / cs.size
            say(line(id, s, String.format(Locale.ROOT, "mean p(label) %.3f; options %s", pLabel, j?.candidates)))
            for (lang in listOf("ar", "arz", "other")) {
                val l = cs.filter { it.lang == lang }
                if (l.isNotEmpty()) say(line("  $id/$lang", stats(l), "(n too small to conclude)"))
            }
        }

        say("== wording arms for the #7 templates (same items; first line = main before the change)")
        for ((name, st) in armResults) {
            say(name)
            say(line("", st))
        }

        say("== options per choice question")
        val choices = cases.filter { it.type == "choice" }.map { it.judgment }.distinctBy { it.id }
        for (j in choices.sortedBy { it.id }) say(String.format(Locale.ROOT, "%-44s %2d options", j.id, j.candidates.size))
        say("max options in a choice: ${choices.maxOf { it.candidates.size }}")

        say("== temperatures (fitted by NLL on all items; ECE raw -> 2-fold held-out), not shipped")
        for (type in listOf("noul", "choice", "score")) {
            val cs = cases.filter { it.type == type }
            val (raw, cv) = crossValidated(cs)
            say(String.format(Locale.ROOT, "%-12s n=%4d  T=%.2f  ECE %.3f -> %.3f (2-fold)", type, cs.size, fitT(cs), raw, cv))
        }
        for (type in listOf("noul", "choice")) {
            val cs = repo.filter { it.type == type }
            val (raw, cv) = crossValidated(cs)
            say(String.format(Locale.ROOT, "%-12s n=%4d  T=%.2f  ECE %.3f -> %.3f (2-fold)  repo labels only", type, cs.size, fitT(cs), raw, cv))
        }
        for (lang in listOf("en", "ar", "arz", "franco", "other")) {
            val cs = cases.filter { it.type == "noul" && it.lang == lang }
            if (cs.size >= 8) say(String.format(Locale.ROOT, "noul/%-7s n=%4d  T=%.2f%s", lang, cs.size, fitT(cs), if (cs.size < 30) "  (n too small to conclude)" else ""))
        }

        val out = File(System.getProperty("loupe.measure.out") ?: "$TEST_TMP/model-fixes-measurement.txt")
        out.parentFile.mkdirs()
        out.writeText(report.toString())
        say("written to ${out.path}")
        assertTrue(cases.all { it.probs.size == it.judgment.candidates.size && abs(it.probs.sum() - 1.0) < 1e-6 })
    }
}
