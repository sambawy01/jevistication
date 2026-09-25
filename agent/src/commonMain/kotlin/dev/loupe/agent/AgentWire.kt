package dev.loupe.agent

import dev.loupe.persistence.JsonText
import dev.loupe.persistence.JsonValue

/** One message on the wire. */
data class AgentMessage(val role: String, val content: String)

/**
 * A failed call, in the taxonomy the iPhone's `AssistError` already uses (itself Loupe Station's
 * `LLMError`). Every message is safe to show: the key is never in one — see [AgentWire.redact].
 */
sealed class AgentFailure(val message: String) {
    class Config(m: String) : AgentFailure(m)

    class NotConfirmed : AgentFailure("Nothing was sent: confirm what will be sent first.")

    class Auth(val status: Int, detail: String) :
        AgentFailure("The provider refused the key (HTTP $status)" + detail.suffix())

    class NotFound(detail: String) : AgentFailure(
        "Not found at the provider (HTTP 404): check the base URL and the model name" + detail.suffix(),
    )

    class RateLimited(detail: String) :
        AgentFailure("The provider is rate limiting requests (HTTP 429)" + detail.suffix())

    class Http(val status: Int, detail: String) :
        AgentFailure("The provider answered HTTP $status" + detail.suffix())

    class Unreachable(detail: String) : AgentFailure("Cannot reach the provider ($detail).")

    class BadResponse(m: String) : AgentFailure(m)

    class InvalidJson(m: String) : AgentFailure(
        "The model's answer did not match the expected format after one repair attempt: $m",
    )

    /** True when trying again later is worth doing. */
    val retryable: Boolean get() = this is RateLimited || this is Unreachable || (this is Http && status >= 500)

    companion object {
        private fun String.suffix(): String = if (isEmpty()) "." else ": $this"
    }
}

/** A raw HTTP answer, handed back by whatever performed the call. */
data class AgentHttpResponse(val status: Int, val body: String, val retryAfterSeconds: Double? = null)

/**
 * Exactly what one call will send, and where.
 *
 * The agent module performs **no I/O**. It builds this, the platform posts it (URLSession on iOS,
 * the desktop's HTTP client), and [AgentWire.readCompletion] reads the answer back. That is not
 * squeamishness about sockets: it is what makes [preview] honest. The text the user confirms is the
 * body that goes out, because there is no later step that could add to it.
 */
data class AgentCall(
    val url: String,
    val headers: Map<String, String>,
    val body: String,
    val provider: String,
    val host: String,
    val model: String,
    val messages: List<AgentMessage>,
    /** How many characters of the *item* this call carries, for the "N characters of this email" line. */
    val sentCharacters: Int,
) {
    val method: String get() = "POST"

    /** The exact text of every message, in order, as it goes into the body. */
    val preview: String get() = messages.joinToString("\n\n") { "[${it.role}]\n${it.content}" }

    /** The headers with the key taken out, for logging and for the egress record. */
    val safeHeaders: Map<String, String>
        get() = headers.mapValues { (k, v) -> if (k.equals("Authorization", true)) "Bearer [redacted]" else v }
}

/**
 * The OpenAI-compatible wire, as pure functions.
 *
 * Kept at deliberate parity with the iPhone's `ChatClient.swift` and, behind it, Loupe Station's
 * `llm/client.py`: the same body (`temperature` 0.2, `stream` false, `response_format` JSON mode),
 * the same schema instruction — DeepSeek needs the word "json" in the prompt to honour JSON mode —
 * the same statuses retried, the same error taxonomy, the same tolerance for a fenced answer, and
 * the same single repair turn. Three implementations that disagree about the wire would be three
 * different products; the parity test pins this one to the Swift one's bytes.
 */
object AgentWire {
    /** Statuses worth one retry. */
    val RETRY_STATUS: Set<Int> = setOf(429, 500, 502, 503, 504)

    const val MAX_RESPONSE_CHARS: Int = 4_000_000
    const val MAX_DETAIL_CHARS: Int = 300

    /** Station's `schema_instruction`. The word "json" must appear for DeepSeek's JSON mode. */
    fun schemaInstruction(schema: String): String =
        "Reply with one JSON object only: no prose, no code fence. It must match this JSON schema:\n$schema"

    /**
     * The request body, with keys sorted at every level so it matches the Swift side's
     * `JSONSerialization` with `.sortedKeys` byte for byte.
     */
    fun requestBody(messages: List<AgentMessage>, model: String, maxTokens: Int, json: Boolean = true): String {
        val fields = linkedMapOf<String, JsonValue>(
            "max_tokens" to JsonValue.num(maxTokens),
            "messages" to JsonValue.Arr(
                messages.map {
                    JsonValue.Obj(
                        linkedMapOf(
                            "content" to JsonValue.Str(it.content),
                            "role" to JsonValue.Str(it.role),
                        ),
                    )
                },
            ),
            "model" to JsonValue.Str(model),
        )
        if (json) {
            fields["response_format"] = JsonValue.Obj(linkedMapOf("type" to JsonValue.Str("json_object")))
        }
        fields["stream"] = JsonValue.Bool(false)
        fields["temperature"] = JsonValue.Num("0.2")
        return JsonText.compact(JsonValue.Obj(fields))
    }

    /**
     * The call for one request, or null when [endpoint] is null — that is, when the tier is off or
     * not set up. A null endpoint is the off switch, and it is unforgeable: only
     * [AgentConfig.endpoint] makes one.
     */
    fun call(
        endpoint: AgentEndpoint?,
        apiKey: String?,
        model: String,
        provider: String,
        messages: List<AgentMessage>,
        maxTokens: Int,
        sentCharacters: Int = 0,
    ): AgentCall? {
        if (endpoint == null || model.isBlank() || messages.isEmpty()) return null
        val key = apiKey?.takeIf { it.isNotBlank() }
        if (endpoint.kind.needsKey && key == null) return null
        val headers = linkedMapOf(
            "Content-Type" to "application/json",
            "Accept" to "application/json",
            "User-Agent" to "Loupe",
        )
        if (key != null) headers["Authorization"] = "Bearer $key"
        return AgentCall(
            url = endpoint.chatUrl,
            headers = headers,
            body = requestBody(messages, model, maxTokens),
            provider = provider,
            host = endpoint.host,
            model = model,
            messages = messages,
            sentCharacters = sentCharacters,
        )
    }

    /** What one completion returned. */
    data class Completion(val content: String, val model: String, val tokensIn: Int, val tokensOut: Int)

    /**
     * Reads a 2xx answer, or says why it cannot be read.
     *
     * Everything is defensive: a provider that answers HTML, or JSON of the wrong shape, is the
     * commonest way a mistyped base URL shows up, and the message has to say that rather than
     * "unexpected nil".
     */
    fun readCompletion(response: AgentHttpResponse, fallbackModel: String, apiKey: String? = null): Result<Completion> {
        if (response.status < 200 || response.status >= 300) {
            return Result.failure(WireError(httpFailure(response.status, response.body, apiKey)))
        }
        if (response.body.length > MAX_RESPONSE_CHARS) {
            return Result.failure(WireError(AgentFailure.BadResponse("The provider's answer is too large.")))
        }
        val root = runCatching { JsonValue.parse(response.body) }.getOrNull() as? JsonValue.Obj
            ?: return Result.failure(
                WireError(
                    AgentFailure.BadResponse(
                        "The provider did not answer with JSON (is the base URL an OpenAI-compatible API?).",
                    ),
                ),
            )
        val message = (root["choices"] as? JsonValue.Arr)?.items?.firstOrNull()
            ?.let { (it as? JsonValue.Obj)?.get("message") } as? JsonValue.Obj
            ?: return Result.failure(
                WireError(AgentFailure.BadResponse("The provider's answer has no choices[0].message.")),
            )
        val content = (message["content"] as? JsonValue.Str)?.value ?: ""
        val usage = root["usage"] as? JsonValue.Obj
        return Result.success(
            Completion(
                content = content,
                model = (root["model"] as? JsonValue.Str)?.value ?: fallbackModel,
                tokensIn = intOf(usage?.get("prompt_tokens")),
                tokensOut = intOf(usage?.get("completion_tokens")),
            ),
        )
    }

    private fun intOf(v: JsonValue?): Int = (v as? JsonValue.Num)?.text?.toDouble()?.toInt() ?: 0

    /** A non-2xx answer, in the same shape the Swift side reports. */
    fun httpFailure(status: Int, body: String, apiKey: String? = null): AgentFailure {
        var text = body.take(20_000)
        val obj = runCatching { JsonValue.parse(text) }.getOrNull() as? JsonValue.Obj
        if (obj != null) {
            val err = obj["error"]
            val m = when {
                err is JsonValue.Obj -> (err["message"] as? JsonValue.Str)?.value
                err is JsonValue.Str -> err.value
                else -> (obj["message"] as? JsonValue.Str)?.value
            }
            if (m != null) text = m
        }
        val detail = redact(text, apiKey).split(' ', '\t', '\n', '\r')
            .filter { it.isNotEmpty() }.joinToString(" ").take(MAX_DETAIL_CHARS)
        return when (status) {
            401, 403 -> AgentFailure.Auth(status, detail)
            404 -> AgentFailure.NotFound(detail)
            429 -> AgentFailure.RateLimited(detail)
            else -> AgentFailure.Http(status, detail)
        }
    }

    private val BEARER = Regex("""(?i)(bearer\s+)[A-Za-z0-9._~+/=\-]{6,}""")
    private val KEYISH = Regex("""\b(sk|ak|key)[-_][A-Za-z0-9._\-]{12,}""")

    /**
     * Station's `redact`, over anything about to be logged or shown: the key verbatim, a bearer
     * token, and any key-shaped string. The key must never reach the ledger, a log line or the
     * screen, whichever provider echoed it back.
     */
    fun redact(text: String, apiKey: String? = null): String {
        var out = text
        if (apiKey != null && apiKey.length >= 4) out = out.replace(apiKey, "[redacted]")
        out = BEARER.replace(out) { it.groupValues[1] + "[redacted]" }
        return KEYISH.replace(out, "[redacted]")
    }

    /**
     * Station's `extract_json`: the object the model meant, tolerating a ```json fence or a
     * sentence either side of it. A model told to answer with JSON usually does; a model that
     * wraps it is not worth a second call.
     */
    fun extractJson(text: String): JsonValue.Obj? {
        var s = text.trim()
        if (s.startsWith("```")) {
            s = s.dropWhile { it != '\n' }
            if (s.endsWith("```")) s = s.dropLast(3)
            s = s.trim()
        }
        (runCatching { JsonValue.parse(s) }.getOrNull() as? JsonValue.Obj)?.let { return it }
        val a = s.indexOf('{')
        val b = s.lastIndexOf('}')
        if (a < 0 || b <= a) return null
        return runCatching { JsonValue.parse(s.substring(a, b + 1)) }.getOrNull() as? JsonValue.Obj
    }

    /** Carries an [AgentFailure] through [Result]. */
    class WireError(val failure: AgentFailure) : Throwable(failure.message)
}
