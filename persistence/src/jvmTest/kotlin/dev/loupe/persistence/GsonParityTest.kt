package dev.loupe.persistence

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser as GsonParser
import com.google.gson.JsonPrimitive
import dev.loupe.templates.JudgmentDraft
import dev.loupe.templates.Template
import dev.loupe.templates.TemplateLibrary
import dev.loupe.templates.UserJudgment
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The files written by common code are byte-identical to what the desktop wrote with Gson, so a
 * desktop home directory and an iPhone one are the same format. Gson is a test-only dependency
 * here (the desktop already ships it; docs/LICENSING.md).
 */
class GsonParityTest {
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    @Test
    fun judgmentsFileMatchesGsonPrettyPrinting() {
        val judgments = TemplateLibrary.ALL.map { t ->
            (t.instantiate("j-${t.id}", t.parameters.associate { it.name to it.example }) as Template.InstantiateResult.Created).judgment
        } + (JudgmentDraft(question = "Which \"pile\" <b>\u2028", options = listOf("keep", "bin", "not sure")).compile("j-mine") as UserJudgment.EditResult.Edited).judgment
        val ours = JudgmentCodec.encodeFile(judgments)
        val viaGson = gson.toJson(JsonArray().apply { judgments.forEach { add(toGson(JudgmentCodec.encode(it))) } })
        assertEquals(viaGson, ours)
        // And Gson reads ours back to the same tree.
        assertEquals(GsonParser.parseString(viaGson), GsonParser.parseString(ours))
    }

    @Test
    fun compactMatchesGsonToString() {
        val v = JsonValue.obj(
            "s" to JsonValue.Str("tab\t nl\n cr\r \u0001 \u2028 \u2029 <&> \"q\" \\ é"),
            "n" to JsonValue.Null,
            "b" to JsonValue.Bool(false),
            "a" to JsonValue.Arr(listOf(JsonValue.num(1), JsonValue.num(0.5), JsonValue.obj())),
        )
        assertEquals(toGson(v).toString(), JsonText.compact(v))
    }

    private fun toGson(v: JsonValue): JsonElement = when (v) {
        JsonValue.Null -> JsonNull.INSTANCE
        is JsonValue.Bool -> JsonPrimitive(v.value)
        is JsonValue.Num -> JsonPrimitive(v.text.toIntOrNull() ?: v.text.toDouble())
        is JsonValue.Str -> JsonPrimitive(v.value)
        is JsonValue.Arr -> JsonArray().apply { v.items.forEach { add(toGson(it)) } }
        is JsonValue.Obj -> JsonObject().apply { v.fields.forEach { (k, x) -> add(k, toGson(x)) } }
    }
}
