package dev.loupe.kit.packs

import dev.loupe.kit.watchers.EXAMPLE_PACK
import dev.loupe.persistence.JsonText
import dev.loupe.persistence.JsonValue
import dev.loupe.persistence.PlatformFiles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Ported from Loupe Station's `tests/test_packs.py` (ea7697a): the Bistro Cloud example pack and
 * the validation cases. The store / HTTP cases have no phone equivalent (packs become judgments);
 * the phone's import is covered in PackJudgmentsTest and the iOS tests.
 */
class PackFormatTest {
    private fun bistroText(): String = PlatformFiles.readText(EXAMPLE_PACK)!!

    private fun valid(v: JsonValue): Pack = assertIs<PackParse.Valid>(PackFormat.validate(v)).pack

    private fun invalid(v: JsonValue): PackParse.Invalid = assertIs<PackParse.Invalid>(PackFormat.validate(v))

    private fun obj(text: String): JsonValue.Obj = JsonValue.parse(text).asObj

    private fun miniPack(): JsonValue.Obj = obj(
        """{"format": "laya-preset-pack", "version": 1, "name": "Acme Shop", "description": "Acme's rules",
           "presets": [{"id": "wf-brand-gate", "name": "Acme post check", "state_key": "draft",
                        "sample_text": "New mugs are in!",
                        "questions": {"mentions_mugs": {"type": "noul", "instructions": "Does `draft` mention mugs?"}}}]}""",
    )

    private fun JsonValue.Obj.with(key: String, v: JsonValue): JsonValue.Obj = JsonValue.Obj(LinkedHashMap(fields).also { it[key] = v })

    private fun JsonValue.Obj.preset0(edit: (JsonValue.Obj) -> JsonValue.Obj): JsonValue.Obj {
        val ps = this["presets"]!!.asArr.items.toMutableList()
        ps[0] = edit(ps[0].asObj)
        return with("presets", JsonValue.Arr(ps))
    }

    @Test
    fun bistroPackValidates() {
        val pack = assertIs<PackParse.Valid>(PackFormat.parse(bistroText())).pack
        assertEquals("bistro-cloud", pack.slug)
        assertEquals("Bistro Cloud", pack.name)
        assertEquals(
            listOf("complaint-triage", "review-sentiment", "order-note-tags", "review-triage", "dm-intent", "social-post-gate", "gmail-triage"),
            pack.presets.map { it.id },
        )
        assertTrue(pack.presets.all { it.translations["ar"]?.get("name")?.isNotEmpty() == true })
    }

    @Test
    fun bistroPackKeepsTheHouseRules() {
        val pack = assertIs<PackParse.Valid>(PackFormat.parse(bistroText())).pack
        val presets = pack.presets.associateBy { it.id }
        val gate = presets["social-post-gate"]!!.questions.map { it.id }.toSet()
        assertTrue(setOf("mentions_amount", "mentions_dine_in").all { it in gate })
        val complaint = presets["complaint-triage"]!!.questions.associateBy { it.id }
        assertEquals(setOf("kitchen", "delivery", "packaging", "app_or_payment", "other"), complaint["team"]!!.options.toSet())
        assertEquals(4, complaint["frustration"]!!.options.size)
        assertTrue("delivery_platform" in presets["gmail-triage"]!!.questions.first { it.id == "category" }.options)
    }

    @Test
    fun validateRejectsBadPacks() {
        val bad = invalid(obj("""{"format": "something-else", "version": 2, "name": "", "presets": []}"""))
        val locs = bad.problems.map { it.loc }.toSet()
        assertTrue(listOf(listOf("format"), listOf("version"), listOf("name"), listOf("presets")).all { it in locs }, "$locs")
        invalid(JsonValue.Arr(emptyList()))
        assertIs<PackParse.Invalid>(PackFormat.parse("{not json"))
    }

    @Test
    fun validateChecksQuestionsLikePredict() {
        val bad = miniPack().preset0 { it.with("questions", obj("""{"q": {"type": "score", "instructions": "How?", "criteria": ["one"]}}""")) }
        val e = invalid(bad)
        assertEquals(listOf("presets", "0", "questions"), e.problems.first().loc.take(3))
        invalid(miniPack().preset0 { it.with("questions", obj("""{"bad id!": {"type": "noul", "instructions": "x?"}}""")) })
        // pydantic's extra="forbid", the type discriminator, blank instructions, labels
        invalid(miniPack().preset0 { it.with("questions", obj("""{"q": {"type": "noul", "instructions": "x?", "extra": 1}}""")) })
        invalid(miniPack().preset0 { it.with("questions", obj("""{"q": {"type": "maybe", "instructions": "x?"}}""")) })
        invalid(miniPack().preset0 { it.with("questions", obj("""{"q": {"type": "noul", "instructions": "   "}}""")) })
        invalid(miniPack().preset0 { it.with("questions", obj("""{"q": {"type": "choice", "instructions": "x?", "criteria": ["a", "a"]}}""")) })
        invalid(miniPack().preset0 { it.with("questions", obj("""{"q": {"type": "choice", "instructions": "x?", "criteria": ["only"]}}""")) })
        invalid(miniPack().preset0 { it.with("questions", obj("""{"q": {"type": "noul", "instructions": "x?", "criteria": {"maybe": "?"}}}""")) })
        invalid(miniPack().preset0 { it.with("questions", obj("{}")) })
        val many = JsonValue.Obj(LinkedHashMap((0..20).associate { "q$it" to (obj("""{"type": "noul", "instructions": "x?"}""") as JsonValue) }))
        invalid(miniPack().preset0 { it.with("questions", many) })
        // a dict of labels may repeat nothing by construction and may carry null descriptions
        valid(miniPack().preset0 { it.with("questions", obj("""{"q": {"type": "choice", "instructions": "x?", "criteria": {"a": null, "b": "bee"}}}""")) })
    }

    @Test
    fun validateIdsNamesAndDuplicates() {
        val twice = miniPack().let { p ->
            val first = p["presets"]!!.asArr.items[0]
            p.with("presets", JsonValue.Arr(listOf(first, first)))
        }
        assertTrue(invalid(twice).lines.any { "duplicate" in it })
        invalid(miniPack().preset0 { it.with("id", JsonValue.Str("Has Spaces")) })
        val arabic = invalid(miniPack().with("name", JsonValue.Str("بيسترو")))
        assertTrue(arabic.lines.any { "slug" in it })
        assertEquals("bistro-ar", valid(miniPack().with("name", JsonValue.Str("بيسترو")).with("slug", JsonValue.Str("bistro-ar"))).slug)
        invalid(miniPack().with("slug", JsonValue.Str("../etc")))
        val first = miniPack()["presets"]!!.asArr.items[0]
        invalid(miniPack().with("presets", JsonValue.Arr(List(PackFormat.MAX_PRESETS + 1) { first })))
    }

    @Test
    fun validateStripsOwnNamespaceAndIgnoresExtraKeys() {
        val p = miniPack().preset0 {
            it.with("id", JsonValue.Str("acme-shop:wf-brand-gate")).with("source", JsonValue.Str("user"))
                .with("pack", JsonValue.Str("x")).with("junk", JsonValue.num(1))
        }
        val out = valid(p)
        assertEquals("wf-brand-gate", out.presets[0].id)
        assertEquals("acme-shop", out.slug)
        assertTrue("junk" !in JsonText.compact(out.toJson()))
    }

    @Test
    fun exportOfMyPresetsIsAValidPack() {
        val pack = obj(
            """{"format": "laya-preset-pack", "version": 1, "name": "My presets", "slug": "my-presets",
               "description": "Presets exported from Laya Studio.",
               "presets": [{"id": "ulm2x9k", "name": "Mine", "description": "Saved in this browser.", "state_key": null,
                            "sample_text": "", "model": "multilingual",
                            "questions": {"q": {"type": "noul", "instructions": "Is this spam?"}}}]}""",
        )
        assertEquals("multilingual", valid(pack).presets[0].model)
    }

    @Test
    fun normalisedPackRoundTrips() {
        val pack = assertIs<PackParse.Valid>(PackFormat.parse(bistroText())).pack
        val again = assertIs<PackParse.Valid>(PackFormat.parse(pack.toText())).pack
        assertEquals(pack.toJson(), again.toJson())
    }

    @Test
    fun sizeCapAndLengthsInCodePoints() {
        assertIs<PackParse.Invalid>(PackFormat.parse(" ".repeat(PackFormat.MAX_PACK_BYTES + 1)))
        // 60 emoji are 60 characters to Python (and here), though 120 UTF-16 units.
        valid(miniPack().with("name", JsonValue.Str("😀".repeat(60))).with("slug", JsonValue.Str("emoji")))
        invalid(miniPack().with("name", JsonValue.Str("😀".repeat(61))).with("slug", JsonValue.Str("emoji")))
    }
}
