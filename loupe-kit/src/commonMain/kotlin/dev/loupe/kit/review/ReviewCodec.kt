package dev.loupe.kit.review

import dev.loupe.persistence.JsonValue

/** The Review queue's two files: items as one JSON document, the log as JSON lines. */
internal object ReviewCodec {
    const val VERSION: Int = 1

    private fun map(m: Map<String, String>): JsonValue = JsonValue.Obj(LinkedHashMap(m.mapValues { JsonValue.Str(it.value) as JsonValue }))

    private fun map(v: JsonValue?): Map<String, String> =
        (v as? JsonValue.Obj)?.fields?.mapValues { it.value.asString } ?: emptyMap()

    private fun optStr(v: JsonValue?): String? = if (v == null || v.isNull) null else v.asString

    fun encodeItems(items: List<ReviewItem>): JsonValue = JsonValue.obj(
        "version" to JsonValue.num(VERSION),
        "items" to JsonValue.Arr(items.map(::encodeItem)),
    )

    fun decodeItems(v: JsonValue): List<ReviewItem> {
        val o = v.asObj
        require(o["version"]?.asInt == VERSION) { "unknown review file version" }
        return o["items"]!!.asArr.items.map(::decodeItem)
    }

    private fun encodeItem(i: ReviewItem): JsonValue = JsonValue.obj(
        "seq" to JsonValue.num(i.seq),
        "id" to JsonValue.Str(i.id),
        "feature" to JsonValue.Str(i.feature),
        "kind" to JsonValue.Str(i.kind),
        "title" to JsonValue.Str(i.title),
        "source_key" to JsonValue.Str(i.sourceKey),
        "input_summary" to JsonValue.Str(i.inputSummary),
        "proposal" to map(i.proposal),
        "original_proposal" to map(i.originalProposal),
        "action" to JsonValue.obj("type" to JsonValue.Str(i.actionType), "params" to map(i.actionParams)),
        "status" to JsonValue.Str(i.status),
        "edited" to JsonValue.Bool(i.edited),
        "created_at" to JsonValue.Str(i.createdAt),
        "updated_at" to JsonValue.Str(i.updatedAt),
        "decided_at" to JsonValue.str(i.decidedAt),
        "decision_note" to JsonValue.str(i.decisionNote),
        "apply_result" to map(i.applyResult),
        "attempts" to JsonValue.num(i.attempts),
        "submitted_by" to JsonValue.Str(i.submittedBy),
    )

    private fun decodeItem(v: JsonValue): ReviewItem {
        val o = v.asObj
        val action = o["action"]!!.asObj
        return ReviewItem(
            seq = o["seq"]!!.asInt, id = o["id"]!!.asString, feature = o["feature"]!!.asString, kind = o["kind"]!!.asString,
            title = o["title"]!!.asString, sourceKey = o["source_key"]!!.asString, inputSummary = o["input_summary"]?.asString ?: "",
            proposal = map(o["proposal"]), originalProposal = map(o["original_proposal"]),
            actionType = action["type"]!!.asString, actionParams = map(action["params"]),
            status = o["status"]!!.asString, edited = o["edited"]?.asBoolean ?: false,
            createdAt = o["created_at"]!!.asString, updatedAt = o["updated_at"]!!.asString,
            decidedAt = optStr(o["decided_at"]), decisionNote = optStr(o["decision_note"]),
            applyResult = map(o["apply_result"]), attempts = o["attempts"]?.asInt ?: 0,
            submittedBy = o["submitted_by"]?.asString ?: "",
        )
    }

    fun encodeLog(e: ReviewLogEntry): JsonValue = JsonValue.obj(
        "seq" to JsonValue.num(e.seq),
        "at" to JsonValue.Str(e.at),
        "item_id" to JsonValue.Str(e.itemId),
        "event" to JsonValue.Str(e.event),
        "actor" to JsonValue.Str(e.actor),
        "status" to JsonValue.str(e.status),
        "note" to JsonValue.str(e.note),
        "details" to map(e.details),
    )

    fun decodeLog(v: JsonValue): ReviewLogEntry {
        val o = v.asObj
        return ReviewLogEntry(
            o["seq"]!!.asInt, o["at"]!!.asString, o["item_id"]!!.asString, o["event"]!!.asString, o["actor"]!!.asString,
            optStr(o["status"]), optStr(o["note"]), map(o["details"]),
        )
    }
}
