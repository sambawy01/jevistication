package dev.loupe.desktop

import dev.loupe.desktop.core.LoupeController
import dev.loupe.desktop.core.ModelState
import dev.loupe.desktop.core.Store
import dev.loupe.engine.DecisionEngine
import dev.loupe.engine.Item
import dev.loupe.engine.Mechanical
import dev.loupe.engine.Probability
import dev.loupe.game.desktop.ModelLoader
import dev.loupe.game.desktop.ModelStatus
import dev.loupe.sources.Scanner
import dev.loupe.templates.Shape
import dev.loupe.templates.TemplateLibrary
import dev.loupe.templates.TransactionEvidence
import dev.loupe.templates.UserJudgment
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The transaction-evidence gate and a sharpened wording for `is-receipt`, measured with the real
 * Laya model. **Gated** on `models/`. Items: the synthetic sample's text items with their hand
 * labels (`sample-labels.tsv`), plus six hand-written documents modelled on the owner's report — a
 * shop product page saved as a PDF (EN and AR), a till receipt (EN and AR), an order email and an
 * invoice. Prints the numbers recorded in `docs/BUILD.md`; asserts only that every arm ran on every
 * item. Invented data with labels by the person measuring: a direction, not an accuracy claim.
 */
class ReceiptGateMeasurementTest {

    @TempDir
    lateinit var tmp: Path

    private val extra: List<Triple<String, String, Boolean>> = listOf(
        Triple("extra/stroller.pdf", """File: Bugaboo Donkey 5 Mono complete stroller.pdf

Bugaboo Donkey 5 Mono complete stroller
Black / Grey Melange
4.8 (212 reviews)
£1,199.00
Pay in 3 interest-free payments of £399.67 with Klarna.
Colour: Black
Add to basket
In stock – Free delivery on orders over £50
Product details
Converts from a mono to a side-by-side duo stroller in 3 easy steps. 2-year warranty.
Specifications
Weight 11.2 kg.
Customer reviews
You may also like
Bugaboo Butterfly £399.00""", false),
        Triple("extra/stroller-ar.pdf", """File: عربة أطفال.pdf

عربة أطفال بوغابو دونكي
٤٫٨ (٢١٢ تقييمات)
٤٬٩٩٩ ريال
أضف إلى السلة
اشتر الآن
متوفر في المخزون — توصيل مجاني
تفاصيل المنتج""", false),
        Triple("extra/till-receipt.pdf", """File: receipt-2026-08-14.pdf

John Lewis & Partners, Oxford Street
Receipt No. 8841-2210-77
14/08/2026 13:42
Bugaboo Donkey 5 Mono      £1,199.00
TOTAL                      £1,199.00
Paid by VISA ************4412""", true),
        Triple("extra/receipt-ar.pdf", """File: فاتورة-متجر.pdf

متجر النور
فاتورة ضريبية مبسطة
رقم الفاتورة: 20931
الإجمالي: ١٣٫٠٠ ريال
طريقة الدفع: مدى
تم الدفع""", true),
        Triple("extra/order.eml", "Subject: Order confirmation #A1029384\n\nThank you for your order. Amount charged £59.99 to your card ending in 1111.", true),
        Triple("extra/invoice-due.pdf", "File: inv.pdf\n\nInvoice INV-2207 from Brightside Plumbing. Amount due £180.00 by 30 September 2026.", false),
    )

    @Test
    fun `measures the receipt gate and the sharpened wording`() {
        val dir = ModelLoader.modelsDir()
        assumeTrue(Files.isRegularFile(ModelLoader.graphPath(dir)) && Files.isRegularFile(ModelLoader.tokenizerPath(dir)), "Laya not present under $dir; skipping")
        val status = ModelLoader.load(dir, fallback = "")
        assumeTrue(status is ModelStatus.Ready, "Laya failed to load: $status")
        val loaded = (status as ModelStatus.Ready).model

        val labels = javaClass.getResourceAsStream("/sample-labels.tsv")!!.bufferedReader().readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split('\t') }
            .associate { it[0] to (it[1] == "+") }

        val app = LoupeController(Store(tmp), Scanner(zone = ZoneOffset.UTC), { LocalDate.of(2026, 9, 23) }) {
            ModelState.Ready(loaded.backend, "Laya INT8", loaded)
        }
        try {
            runBlocking { app.start().join(); app.loadSampleData().join() }
            while (app.model is ModelState.Loading) Thread.sleep(10)
            val sample = app.scan.items.filter { it.hasText }.map { Triple(it.id.substringAfter("sample-data/"), it.text, labels.getValue(it.id.substringAfter("sample-data/"))) }
            val all = sample + extra

            val current = (TemplateLibrary.byId("is-receipt")!!.instantiate("j-is-receipt") as dev.loupe.templates.Template.InstantiateResult.Created).judgment
            val sharp = current.copy(
                question = "Is this a receipt: a record that a payment was completed?",
                shape = Shape.Binary("a record of a completed payment", "not a payment record"),
            )
            val engine = DecisionEngine(loaded.backend, Probability.of(current.threshold))

            fun arm(name: String, j: UserJudgment, gate: Boolean) {
                val pos = j.shape.candidates[0]
                var right = 0
                var ruled = 0
                var ruledWrong = 0
                var unsure = 0
                val notes = mutableListOf<String>()
                for ((id, text, yes) in all) {
                    val rule = if (gate) TransactionEvidence.ruleAnswer(j, text) else null
                    val out = engine.decide(j.choice, Item(id, text)) { if (rule != null) Mechanical.Resolved(rule, TransactionEvidence.CHECK) else Mechanical.Deferred }
                    val top = out.row.distribution.argmax
                    val pYes = out.row.distribution.getValue(pos).value
                    if (rule != null) { ruled++; if (yes) ruledWrong++ }
                    else if (out.row.distribution.getValue(top).value < j.threshold) unsure++
                    if ((top == pos) == yes) right++
                    if (id.startsWith("extra/")) notes += String.format(Locale.ROOT, "%s p(yes)=%.2f%s", id.removePrefix("extra/"), pYes, if (rule != null) " (rule)" else "")
                }
                println(String.format(Locale.ROOT, "[receipt-gate] %-22s acc %2d/%d (%.1f%%)  by rule %2d (wrong %d)  unsure (to queue) %2d", name, right, all.size, right * 100.0 / all.size, ruled, ruledWrong, unsure))
                println("[receipt-gate]   " + notes.joinToString("; "))
            }
            arm("current, model only", current, gate = false)
            arm("current + gate", current, gate = true)
            arm("sharpened, model only", sharp, gate = false)
            arm("sharpened + gate", sharp, gate = true)
            assertEquals(labels.size + extra.size, all.size)
        } finally {
            app.close()
        }
    }
}
