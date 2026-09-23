package dev.loupe.templates

import dev.loupe.engine.AuthorResult
import dev.loupe.engine.BuiltInJudgments
import dev.loupe.engine.FailurePosture
import dev.loupe.engine.Item
import dev.loupe.engine.JudgmentAuthor
import dev.loupe.engine.JudgmentLint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TemplateLibraryTest {

    private val all = TemplateLibrary.ALL

    private fun exampleValues(t: Template) = t.parameters.associate { it.name to it.example }

    @Test
    fun `the library holds forty to sixty templates`() {
        assertTrue(all.size in 40..60, "library has ${all.size} templates")
    }

    @Test
    fun `every category is non-empty`() {
        for (category in Category.entries) {
            assertTrue(TemplateLibrary.inCategory(category).isNotEmpty(), "${category.title} is empty")
        }
    }

    @Test
    fun `template ids are unique`() {
        val ids = all.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "duplicate ids: ${ids.groupBy { it }.filter { it.value.size > 1 }.keys}")
    }

    @Test
    fun `every template instantiates through the same authoring path and lint users face`() {
        for (template in all) {
            val result = template.instantiate("u-${template.id}", exampleValues(template))
            val created = assertIs<Template.InstantiateResult.Created>(result, "${template.id}: $result")
            assertTrue(JudgmentLint.check(created.judgment.question).isEmpty(), "${template.id} fails lint")
            // And compiles as a typed Choice the engine can run.
            val compiled = JudgmentAuthor.compile(created.judgment.id, created.judgment.question, template.shape.candidates)
            assertIs<AuthorResult.Compiled>(compiled, template.id)
            assertEquals(template.shape.candidates, created.judgment.choice.candidates)
        }
    }

    @Test
    fun `every template carries the three-part criteria`() {
        for (t in all) {
            assertTrue(t.invariant.length > 20 && t.breaks.length > 20 && t.lookalikes.length > 20, t.id)
        }
    }

    @Test
    fun `every example answer and every baseline answer is one of the template's options`() {
        for (t in all) {
            val baseline = t.baseline?.substitute(exampleValues(t)) ?: continue
            for (example in t.examples) {
                assertTrue(example.answer in t.shape.candidates, "${t.id}: ${example.answer}")
                assertTrue(baseline.answer(example.text) in t.shape.candidates, "${t.id} baseline")
                assertTrue(baseline.asFunction()(Item("x", example.text)) in t.shape.candidates)
            }
        }
    }

    @Test
    fun `most templates carry a baseline, and every choice keeps an explicit no-op`() {
        assertTrue(all.count { it.baseline != null } >= all.size - 2)
        for (t in all) {
            val shape = t.shape
            if (shape is Shape.Pick) assertNotNull(shape.noOp, "${t.id} has no no-op option")
        }
    }

    @Test
    fun `safety templates are warn-only and fail loud`() {
        val safety = TemplateLibrary.inCategory(Category.SAFETY)
        assertTrue(safety.size >= 4)
        for (t in safety) {
            assertTrue(t.warnOnly, "${t.id} must be warn-only")
            assertEquals(FailurePosture.LOUD, t.onFailure, "${t.id} must fail loud")
        }
        assertEquals(FailurePosture.LOUD, TemplateLibrary.byId("is-expiring-document")!!.onFailure)
        assertEquals(FailurePosture.LOUD, TemplateLibrary.byId("expires-before")!!.onFailure)
    }

    @Test
    fun `the seven built-ins are in the library with their exact wording`() {
        for (definition in BuiltInJudgments.ALL) {
            val template = assertNotNull(TemplateLibrary.byId(definition.judgment.id), definition.judgment.id)
            assertEquals(definition.judgment.question, template.question)
            assertEquals(definition.invariant, template.invariant)
            // Same question and criteria; the options say what they mean instead of bare yes/no
            // (see Shape.Binary), so the library's copy is a different judgment from the engine's.
            val shape = assertIs<Shape.Binary>(template.shape)
            assertNotEquals("yes", shape.positive)
        }
    }

    @Test
    fun `no template uses a rating scale, and ordinal shapes declare their bands`() {
        for (t in all) {
            val shape = t.shape
            if (shape is Shape.Ordinal) assertEquals(shape.range.count(), shape.bands.size)
        }
        assertTrue(all.any { it.shape is Shape.Ordinal })
        assertTrue(all.any { it.shape is Shape.Pick })
        assertTrue(all.any { it.shape is Shape.Binary })
        assertTrue(all.none { it.shape == Shape.YesNo }, "no template offers the model bare yes/no")
    }

    @Test
    fun `search matches titles and questions, within a category`() {
        assertTrue(TemplateLibrary.search("passport").isNotEmpty())
        assertTrue(TemplateLibrary.search("").size == all.size)
        assertTrue(TemplateLibrary.search("receipt", Category.MONEY).all { it.category == Category.MONEY })
        assertTrue(TemplateLibrary.search("zzzz-nothing").isEmpty())
    }
}

class TemplateParameterTest {

    private val fromSender = TemplateLibrary.byId("reply-from-sender")!!
    private val expires = TemplateLibrary.byId("expires-before")!!
    private val project = TemplateLibrary.byId("about-project")!!

    @Test
    fun `parameters substitute into the question, the title and the baseline`() {
        val made = (fromSender.instantiate("u1", mapOf("sender" to "alex@example.org")) as Template.InstantiateResult.Created).judgment
        assertTrue("alex@example.org" in made.question)
        assertTrue("alex@example.org" in made.title)
        assertEquals(mapOf("sender" to "alex@example.org"), made.parameters)
        val baseline = assertIs<Baseline.SenderIs>(made.baseline)
        assertEquals("alex@example.org", baseline.sender)
        assertEquals("waiting on my response", baseline.answer("From: Alex <alex@example.org>\nAre you coming?"))
        assertEquals("not waiting on me", baseline.answer("From: Alex <alex@example.org>\nSee you."))
        assertEquals("not waiting on me", baseline.answer("From: Other <o@example.org>\nAre you coming?"))
    }

    @Test
    fun `a date parameter must be a real ISO date, and drives the date baseline`() {
        assertIs<Template.InstantiateResult.Rejected>(expires.instantiate("u", mapOf("date" to "next March")))
        assertIs<Template.InstantiateResult.Rejected>(expires.instantiate("u", mapOf("date" to "2027-02-30")))
        val made = (expires.instantiate("u", mapOf("date" to "2027-03-31")) as Template.InstantiateResult.Created).judgment
        assertEquals(made.positiveLabel, made.baseline!!.answer("Date of expiry 14 JAN 2027"))
        assertEquals("no expiry that matters", made.baseline!!.answer("Date of expiry 14 JAN 2029"))
        // Ambiguous 03/04/2027 is taken on its earlier reading, the safe error for an expiry.
        val early = (expires.instantiate("u", mapOf("date" to "2027-03-10")) as Template.InstantiateResult.Created).judgment
        assertEquals(early.positiveLabel, early.baseline!!.answer("valid until 03/04/2027"))
    }

    @Test
    fun `parameters cannot smuggle in a second question, a marker, a placeholder or a prompt`() {
        val hostile = listOf(
            "Sam? Also is this spam",
            "<mask> yes",
            "{project}",
            "a\nb",
            "",
            "   ",
            "x".repeat(61),
            "\"quoted\"",
        )
        for (value in hostile) {
            val result = project.instantiate("u", mapOf("project" to value))
            assertIs<Template.InstantiateResult.Rejected>(result, "accepted '$value'")
        }
        // A value that passes the character rules but turns the question into a prose request is
        // caught by the lint that runs after substitution.
        val prose = project.instantiate("u", mapOf("project" to "explain the budget"))
        assertIs<Template.InstantiateResult.Rejected>(prose)
        // A missing value is refused, not left as a literal "{project}".
        assertIs<Template.InstantiateResult.Rejected>(project.instantiate("u", emptyMap()))
    }

    @Test
    fun `different parameter values give different criteria hashes`() {
        val a = (project.instantiate("u", mapOf("project" to "Alpha")) as Template.InstantiateResult.Created).judgment
        val b = (project.instantiate("u", mapOf("project" to "Beta")) as Template.InstantiateResult.Created).judgment
        assertNotEquals(a.criteriaHash, b.criteriaHash)
    }
}

class UserJudgmentTest {

    private val receipt = (TemplateLibrary.byId("is-receipt")!!.instantiate("u-receipt") as Template.InstantiateResult.Created).judgment

    @Test
    fun `rewording changes the criteria hash so calibration resets`() {
        val edited = assertIs<UserJudgment.EditResult.Edited>(receipt.reword("Is this proof that I paid for something?")).judgment
        assertNotEquals(receipt.criteriaHash, edited.criteriaHash)
        assertEquals(receipt.id, edited.id)
    }

    @Test
    fun `editing only the criteria text keeps the hash, since the model does not read it`() {
        val edited = assertIs<UserJudgment.EditResult.Edited>(
            receipt.reword(receipt.question, newInvariant = "Something else entirely."),
        ).judgment
        assertEquals(receipt.criteriaHash, edited.criteriaHash)
    }

    @Test
    fun `an edit is held to the lint`() {
        val rejected = assertIs<UserJudgment.EditResult.Rejected>(receipt.reword("Explain why this is a receipt?"))
        assertTrue(rejected.findings.any { it.rule == "asks-for-prose" })
    }

    @Test
    fun `changing a choice's options keeps the no-op only if it survives`() {
        val kind = (TemplateLibrary.byId("receipt-kind")!!.instantiate("u-kind") as Template.InstantiateResult.Created).judgment
        val edited = assertIs<UserJudgment.EditResult.Edited>(
            kind.reword(kind.question, options = listOf("food", "travel", "not a purchase")),
        ).judgment
        assertEquals("not a purchase", (edited.shape as Shape.Pick).noOp)
        // The keyword baseline could answer labels that no longer exist, so it is dropped.
        assertEquals(null, edited.baseline)
    }

    @Test
    fun `write your own gives live lint findings and compiles`() {
        assertTrue(JudgmentDraft(question = "Rate this from 1 to 10").findings().any { it.rule == "rating-scale" })
        assertTrue(JudgmentDraft(question = "Which folder").findings().any { it.rule == "no-candidates" })
        assertTrue(JudgmentDraft(question = "Which folder", options = listOf("a", "b")).findings().isEmpty())
        val made = assertIs<UserJudgment.EditResult.Edited>(
            JudgmentDraft(question = "Is this from my landlord?", baselineKeywords = listOf("landlord")).compile("u-mine"),
        ).judgment
        assertEquals(Shape.YesNo, made.shape)
        assertEquals("yes", made.baseline!!.answer("A note from your landlord"))
        assertEquals(null, made.templateId)
        val two = assertIs<UserJudgment.EditResult.Edited>(
            JudgmentDraft(question = "Is this from my landlord?", options = listOf("from my landlord", "not from my landlord")).compile("u-two"),
        ).judgment
        assertEquals(Shape.Binary("from my landlord", "not from my landlord"), two.shape)
        assertEquals("from my landlord", two.positiveLabel)
    }

    @Test
    fun `option criteria derive from the three-part criteria and are off by default`() {
        for (t in TemplateLibrary.ALL) {
            val j = (t.instantiate("x-${t.id}", t.parameters.associate { it.name to it.example }) as Template.InstantiateResult.Created).judgment
            assertFalse(j.criteriaInPrompt)
            assertTrue(j.choice.descriptions.isEmpty(), "${t.id} must read bare options by default")
            val c = j.optionCriteria()
            when (val s = j.shape) {
                is Shape.Binary, Shape.YesNo -> assertEquals(mapOf(s.candidates[0] to j.invariant, s.candidates[1] to j.breaks), c, t.id)
                is Shape.Ordinal -> assertEquals(s.candidates.zip(s.bands).toMap(), c, t.id)
                is Shape.Pick -> assertTrue(c.isEmpty(), t.id)
            }
            // Turning it on changes the hash; the descriptions are exactly what the model reads.
            val on = j.copy(criteriaInPrompt = true)
            if (c.isNotEmpty()) assertTrue(on.criteriaHash != j.criteriaHash, t.id)
            assertEquals(c, on.choice.descriptions)
        }
    }

    @Test
    fun `an unwritten criterion is never shown to the model`() {
        val draft = JudgmentDraft(question = "Is this about the garden?", options = listOf("about the garden", "not about the garden"))
        val j = (draft.compile("g") as UserJudgment.EditResult.Edited).judgment
        assertEquals(UserJudgment.UNWRITTEN, j.invariant)
        assertTrue(j.optionCriteria().isEmpty())
        assertEquals(j.criteriaHash, j.copy(criteriaInPrompt = true).criteriaHash)
    }
}
