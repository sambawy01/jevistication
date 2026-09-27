package dev.loupe.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class JudgmentAuthorTest {

    private fun rulesFor(question: String) = JudgmentLint.check(question).map { it.rule }.toSet()

    @Test
    fun `accepts a plain decidable question`() {
        assertTrue(JudgmentLint.check("Is this a receipt?").isEmpty())
        assertTrue(JudgmentLint.check("Does this need a reply?").isEmpty())
    }

    @Test
    fun `rejects a numeric rating scale`() {
        assertTrue("rating-scale" in rulesFor("Rate the urgency 1-10"))
        assertTrue("rating-scale" in rulesFor("Score this out of 100"))
        assertTrue("rating-scale" in rulesFor("On a scale, how urgent is this?"))
    }

    @Test
    fun `rejects asking for an explanation`() {
        assertTrue("asks-for-prose" in rulesFor("Is this urgent, and explain why"))
        assertTrue("asks-for-prose" in rulesFor("Describe this document"))
        assertTrue("asks-for-prose" in rulesFor("Summarise the attachment"))
    }

    @Test
    fun `rejects asking the engine to produce text`() {
        assertTrue("asks-to-write" in rulesFor("Write a reply to this"))
        assertTrue("asks-to-write" in rulesFor("Draft a response"))
    }

    @Test
    fun `rejects two questions in one`() {
        assertTrue("multiple-questions" in rulesFor("Is this a receipt? Is it a duplicate?"))
    }

    private fun absent(question: String) = JudgmentLint.absence(question).any { it.rule == "absence-phrasing" }

    @Test
    fun `rejects absence phrasing in English - with a presence suggestion`() {
        for (q in listOf(
            "Is the signature missing?", "Does it lack a date?", "Is the invoice number absent?",
            "Is there no due date?", "Was it sent without a receipt?", "Does this not have a total?",
            "Doesn't it mention a refund?", "Isn't there a reference number?", "Does it fail to state an amount?",
            "Does the form omit the address?", "Is nothing owed?", "Are none of the items paid?",
        )) assertTrue(absent(q), q)
        val finding = JudgmentLint.absence("Is the signature missing?").single()
        assertTrue("presence question" in finding.message && "Does it show a signature?" in finding.message, finding.message)
    }

    @Test
    fun `rejects absence phrasing in Arabic - Egyptian Arabic and Franco-Arabic`() {
        for (q in listOf(
            "هل التوقيع ناقص؟", "هل ينقصه تاريخ؟", "هل لا يوجد رقم فاتورة؟", "هل لا توجد تفاصيل الدفع؟",
            "هل وصلت بدون إيصال؟", "هل الرقم مفقود؟", "هل المستند خالٍ من التوقيع؟", "هل يفتقر إلى تاريخ؟",
            "هل ليس فيه مبلغ؟", "هل التاريخ غير موجود؟", "هل هناك عدم وجود للتوقيع؟",
            "هل الطلب وصل من غير الصوص؟", "هل مفيش رقم أوردر؟", "هل الرسالة بلا عنوان؟", "وبدون فاتورة؟",
            "el order na2es?", "mafeesh raqam order?", "wesel men gheir el sauce?", "el fatoura mesh mawgooda?",
        )) assertTrue(absent(q), q)
    }

    @Test
    fun `accepts presence questions - including the words that only look like absence`() {
        for (q in listOf(
            "Is this a receipt or proof of purchase?", "Does it show a signature?", "Is this from a no-reply address?",
            "Is this bulk mail I no longer read?", "Is this a yes or no question?", "Does this mention a refund?",
            "How soon does this need my attention, where 0 is never, 1 is this month, 2 is this week and 3 is today?",
            "هل يظهر توقيع؟", "هل يحتوي على رقم فاتورة؟", "هل هذا بلاغ عن مشكلة؟", "هل البلاغ عاجل؟",
            "ماذا نقصد بهذه الرسالة؟", "Does `message` mention a packaging problem such as an open box, leak or spill?",
        )) assertTrue(!absent(q), q)
    }

    @Test
    fun `the absence rule is separate from the plain-language lint`() {
        // Web and flight questions carry wishes ("no layovers") and do not take the absence rule;
        // templates, written judgments and packs do (TemplateLibrary, JudgmentDraft, JudgmentBook).
        assertTrue(JudgmentLint.check("Does this flight offer fit these priorities: no layovers?").isEmpty())
        assertTrue(absent("Does this flight offer fit these priorities: no layovers?"))
    }

    @Test
    fun `no built-in judgment asks an absence question`() {
        assertTrue(BuiltInJudgments.ALL.none { absent(it.judgment.question) })
    }

    @Test
    fun `rejects an empty question`() {
        assertTrue("empty" in rulesFor("   "))
    }

    @Test
    fun `infers yes-no candidates for a yes-no question`() {
        val result = JudgmentAuthor.compile("r", "Is this a receipt?")
        val compiled = assertIs<AuthorResult.Compiled>(result)
        assertEquals(listOf("yes", "no"), compiled.judgment.candidates)
    }

    @Test
    fun `requires explicit candidates when the question is not yes-no`() {
        val result = JudgmentAuthor.compile("k", "Which category does this belong to?")
        val rejected = assertIs<AuthorResult.Rejected>(result)
        assertTrue(rejected.findings.any { it.rule == "no-candidates" })
    }

    @Test
    fun `accepts a non-yes-no question when candidates are supplied`() {
        val result = JudgmentAuthor.compile(
            "k",
            "Which category does this belong to?",
            candidates = listOf("bill", "receipt", "statement"),
        )
        val compiled = assertIs<AuthorResult.Compiled>(result)
        assertEquals(3, compiled.judgment.candidates.size)
    }

    @Test
    fun `reports invalid candidates rather than throwing`() {
        val result = JudgmentAuthor.compile("k", "Is this a receipt?", candidates = listOf("only"))
        val rejected = assertIs<AuthorResult.Rejected>(result)
        assertTrue(rejected.findings.any { it.rule == "invalid-candidates" })
    }

    @Test
    fun `a compiled judgment is immediately usable by the engine`() {
        // C2's acceptance: someone who has not read the source gets a working scored classifier.
        val compiled = assertIs<AuthorResult.Compiled>(
            JudgmentAuthor.compile("is-urgent", "Is this urgent?"),
        )
        val engine = DecisionEngine(
            backend = Backend.ofMasses { _, _ -> mapOf("yes" to 0.9, "no" to 0.1) },
            threshold = Probability.of(0.7),
        )
        val outcome = engine.decide(compiled.judgment, Item("m1", "server is down"))
        assertEquals("yes", (outcome.decision as Decision.Act).label)
    }
}
