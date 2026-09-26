package dev.loupe.kit.measure

import dev.loupe.kit.watchers.FAST_DECISIONS_SAMPLE
import dev.loupe.persistence.PlatformFiles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FastDecisionsTest {
    /** The four committed rows: a binary head, a four-head row, a multi-label head, 28 labels. */
    private val sample: FastDomain by lazy {
        FastDecisions.parse("sample", assertNotNull(PlatformFiles.readText(FAST_DECISIONS_SAMPLE), "sample.jsonl"))
    }

    // ------------------------------------------------------------------ the real shape parses

    @Test
    fun `the committed sample parses into rows, heads and cases`() {
        assertEquals(4, sample.rowCount)
        assertEquals(8, sample.cases.size, "1 + 4 + 2 + 1 head-instances")
        assertEquals(
            listOf("should_handoff", "category", "action", "needs_reply", "is_phishing", "sentiment", "aspects", "intent"),
            sample.heads.map { it.task },
            "heads keep the order they first appear in",
        )
    }

    @Test
    fun `a head Loupe already ships is present and binary`() {
        val h = sample.head("is_phishing")
        assertEquals(listOf("yes", "no"), h.labels)
        assertTrue(!h.multiLabel)
        assertEquals("sample.is_phishing", h.id)
        assertEquals(listOf("no"), sample.cases("is_phishing").single().trueLabels)
    }

    @Test
    fun `a multi-label head is marked as one`() {
        val h = sample.head("aspects")
        assertTrue(h.multiLabel)
        assertEquals(6, h.labels.size)
        assertEquals(listOf("food", "service", "price", "ambiance", "wait", "cleanliness"), h.labels)
    }

    @Test
    fun `a twenty-eight label head keeps every candidate in order`() {
        val h = sample.head("intent")
        assertEquals(28, h.labels.size)
        assertEquals("order_status", h.labels.first())
        assertEquals("other", h.labels.last())
    }

    @Test
    fun `the item text survives, newlines and all`() {
        val email = sample.cases("is_phishing").single()
        assertTrue(email.text.contains("From: Lydia Harper"), email.text.take(60))
        assertTrue(email.text.contains('\n'), "the escaped newlines must decode")
        assertEquals("sample#1", email.itemId, "the second row")
    }

    // ------------------------------------------------------------------ into the harness's shapes

    @Test
    fun `a single-label head becomes a Choice the harness can run`() {
        val j = FastDecisions.judgment(sample.head("category"))
        assertEquals("sample.category", j.id)
        assertEquals("category?", j.question)
        assertEquals(8, j.candidates.size)
        assertTrue(j.criteriaHash.isNotBlank())
    }

    @Test
    fun `the question is the task name and nothing we wrote`() {
        // Wording is a controlled variable (§7). Terse on purpose: a fuller sentence would be our
        // prose, and any gain from it would be credited to the model.
        assertEquals("is phishing?", FastDecisions.questionFor(sample.head("is_phishing")))
        assertEquals("should handoff?", FastDecisions.questionFor(sample.head("should_handoff")))
        assertEquals("needs reply?", FastDecisions.questionFor(sample.head("needs_reply")))
    }

    @Test
    fun `a multi-label head has no Choice, and says why`() {
        val e = assertFailsWith<IllegalArgumentException> { FastDecisions.judgment(sample.head("aspects")) }
        assertTrue(e.message!!.contains("multi-label"), e.message!!)
        assertTrue(!FastDecisions.isHarnessable(sample.head("aspects")))
        assertTrue(FastDecisions.isHarnessable(sample.head("sentiment")))
    }

    @Test
    fun `fixtures carry the item, the gold label and their own source group`() {
        val f = FastDecisions.fixtures(sample, sample.head("intent")).single()
        assertEquals("sample#3", f.item.id)
        assertEquals("refund_request", f.trueLabel)
        assertEquals("sample#3", f.sourceGroup, "the dataset gives no grouping, so a row is its own group")
    }

    // ------------------------------------------------------------------------- the baselines

    @Test
    fun `the label-name baseline picks a label whose words are in the text`() {
        val h = sample.head("intent")
        val text = "I would like a refund request for my order"
        assertEquals(listOf("refund_request"), FastDecisions.lexicalPredict(h, text))
    }

    @Test
    fun `a text matching no label takes the first candidate, deterministically`() {
        val h = sample.head("intent")
        assertEquals(listOf("order_status"), FastDecisions.lexicalPredict(h, "zzz qqq"))
    }

    @Test
    fun `ties break alphabetically, so the figure is reproducible`() {
        val h = sample.head("category")
        // "billing" and "support" both score 1; "billing" sorts first.
        assertEquals(listOf("billing"), FastDecisions.lexicalPredict(h, "support and billing"))
    }

    @Test
    fun `a multi-label baseline returns every label it found`() {
        val h = sample.head("aspects")
        assertEquals(
            listOf("food", "service"),
            FastDecisions.lexicalPredict(h, "the food was cold and the service slow"),
        )
    }

    @Test
    fun `the majority answer is the most frequent gold set`() {
        val h = sample.head("is_phishing")
        assertEquals(listOf("no"), FastDecisions.majorityAnswer(sample, h))
    }

    // ---------------------------------------------------------------------------- the protocol

    private fun synthetic(domain: String, heads: Int, rows: Int, correctRows: Int): FastDomain {
        val jsonl = (0 until rows).joinToString("\n") { r ->
            val cs = (0 until heads).joinToString(",") { h ->
                val gold = if (r < correctRows) "a" else "b"
                """{"task":"t$h","true_label":["$gold"],"labels":["a","b"],"multi_label":false}"""
            }
            """{"input":"row $r","output":{"classifications":[$cs]}}"""
        }
        return FastDecisions.parse(domain, jsonl)
    }

    @Test
    fun `scoring is exact set match, per head`() {
        val d = synthetic("d", heads = 1, rows = 10, correctRows = 7)
        val s = FastDecisions.score(listOf(d)) { listOf("a") }
        assertEquals(1, s.perDomain.size)
        assertEquals(7, s.perDomain[0].correct)
        assertEquals(10, s.perDomain[0].total)
        assertEquals(0.7, s.average)
    }

    @Test
    fun `the average is the mean of domains and pooled weights heads, and they differ`() {
        // One head at 100%, four heads at 0%: the mean of domains is 50%, pooled is 20%. Quoting
        // one while implying the other is how a benchmark number drifts.
        val good = synthetic("good", heads = 1, rows = 10, correctRows = 10)
        val bad = synthetic("bad", heads = 4, rows = 10, correctRows = 0)
        val s = FastDecisions.score(listOf(good, bad)) { listOf("a") }
        assertEquals(0.5, s.average)
        assertEquals(10.0 / 50.0, s.pooled)
        assertEquals(50, s.heads)
    }

    @Test
    fun `a multi-label head scores zero for a partial answer`() {
        val jsonl = """{"input":"x","output":{"classifications":[
            {"task":"tags","true_label":["a","b"],"labels":["a","b","c"],"multi_label":true}]}}"""
            .replace("\n", "")
        val d = FastDecisions.parse("m", jsonl)
        assertEquals(0, FastDecisions.score(listOf(d)) { listOf("a") }.perDomain[0].correct)
        assertEquals(1, FastDecisions.score(listOf(d)) { listOf("b", "a") }.perDomain[0].correct, "order must not matter")
    }

    // ----------------------------------------------------------------------- malformed input

    @Test
    fun `a malformed row names itself`() {
        val bad = listOf(
            """{"output":{"classifications":[]}}""" to "'input' must be a string",
            """{"input":"x"}""" to "'output' must be an object",
            """{"input":"x","output":{}}""" to "'output.classifications' must be an array",
            """{"input":"x","output":{"classifications":[{"task":"t","labels":["a","b"],"multi_label":false}]}}"""
                to "'true_label' is required",
            """{"input":"x","output":{"classifications":[{"task":"t","true_label":"a","labels":["a","b"],"multi_label":false}]}}"""
                to "'true_label' must be an array of strings",
            """{"input":"x","output":{"classifications":[{"task":"t","true_label":["a"],"labels":"a","multi_label":false}]}}"""
                to "'labels' must be an array of strings",
            """{"input":"x","output":{"classifications":[{"task":"t","true_label":["a"],"labels":["a",7],"multi_label":false}]}}"""
                to "a label must be a string",
            """{"input":"x","output":{"classifications":[{"task":"t","true_label":[],"labels":["a","b"],"multi_label":false}]}}"""
                to "no gold label",
            """{"input":"x","output":{"classifications":[{"task":"t","true_label":["a"],"labels":["a","b"]}]}}"""
                to "'multi_label' must be a boolean",
        )
        for ((line, expect) in bad) {
            val e = assertFailsWith<IllegalArgumentException> { FastDecisions.parse("d", line) }
            assertTrue(e.message!!.contains("d row 0"), "should name the row: ${e.message}")
            assertTrue(e.message!!.contains(expect), "expected \"$expect\" in: ${e.message}")
        }
    }

    @Test
    fun `a gold label outside the candidate set is refused`() {
        val line = """{"input":"x","output":{"classifications":[
            {"task":"t","true_label":["z"],"labels":["a","b"],"multi_label":false}]}}""".replace("\n", "")
        val e = assertFailsWith<IllegalArgumentException> { FastDecisions.parse("d", line) }
        assertTrue(e.message!!.contains("not in the candidate set"), e.message!!)
    }

    @Test
    fun `a label set that changes between rows is refused`() {
        // It would mean a different judgment each row, which a Choice cannot represent and a
        // reader would never spot in a score.
        val jsonl = listOf(
            """{"input":"x","output":{"classifications":[{"task":"t","true_label":["a"],"labels":["a","b"],"multi_label":false}]}}""",
            """{"input":"y","output":{"classifications":[{"task":"t","true_label":["a"],"labels":["a","c"],"multi_label":false}]}}""",
        ).joinToString("\n")
        val e = assertFailsWith<IllegalArgumentException> { FastDecisions.parse("d", jsonl) }
        assertTrue(e.message!!.contains("label set differs"), e.message!!)
    }

    @Test
    fun `a head with one label is refused, because a Choice needs a choice`() {
        val line = """{"input":"x","output":{"classifications":[
            {"task":"t","true_label":["a"],"labels":["a"],"multi_label":false}]}}""".replace("\n", "")
        assertFailsWith<IllegalArgumentException> { FastDecisions.parse("d", line) }
    }

    // ------------------------------------------------------------------------ suite metadata

    @Test
    fun `the suite is the seventeen domains the dataset card lists`() {
        assertEquals(17, FastDecisions.DOMAINS.size)
        assertEquals(FastDecisions.DOMAINS.size, FastDecisions.DOMAINS.distinct().size)
        assertTrue("email_triage" in FastDecisions.DOMAINS)
        assertTrue("ticket_route" in FastDecisions.DOMAINS)
        assertEquals(100, FastDecisions.ROWS_PER_DOMAIN)
        assertEquals(2_900, FastDecisions.HEAD_INSTANCES)
    }

    @Test
    fun `the heads Loupe already ships are named, and belong to domains in the suite`() {
        assertEquals(listOf("email_triage.is_phishing", "ticket_route.contains_pii"), FastDecisions.LOUPE_HEADS)
        for (id in FastDecisions.LOUPE_HEADS) {
            assertTrue(id.substringBefore('.') in FastDecisions.DOMAINS, id)
        }
    }
}
