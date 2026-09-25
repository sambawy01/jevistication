package dev.loupe.agent

import dev.loupe.persistence.JsonValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentWireTest {
    private val endpoint =
        (AgentConfig.checkBaseUrl("https://api.deepseek.com/v1", AgentProviderKind.OPENAI_COMPATIBLE)
            as AgentConfig.UrlCheck.Good).endpoint

    @Test
    fun `the request body is the Swift side's bytes, with keys sorted`() {
        val body = AgentWire.requestBody(listOf(AgentMessage("user", "hi")), "deepseek-chat", 1_200)
        assertEquals(
            """{"max_tokens":1200,"messages":[{"content":"hi","role":"user"}],"model":"deepseek-chat",""" +
                """"response_format":{"type":"json_object"},"stream":false,"temperature":0.2}""",
            body,
        )
    }

    @Test
    fun `json mode can be turned off for a provider that rejects it`() {
        val body = AgentWire.requestBody(listOf(AgentMessage("user", "hi")), "m", 10, json = false)
        assertTrue(!body.contains("response_format"), body)
    }

    @Test
    fun `the schema instruction names json, which DeepSeek needs to honour json mode`() {
        assertTrue(AgentWire.schemaInstruction("{}").contains("JSON"), "the word json must appear")
    }

    @Test
    fun `no endpoint means no call, which is the off switch`() {
        assertNull(AgentWire.call(null, TEST_KEY, "m", "p", listOf(AgentMessage("user", "x")), 10))
    }

    @Test
    fun `a hosted provider with no key cannot build a call`() {
        assertNull(AgentWire.call(endpoint, null, "m", "p", listOf(AgentMessage("user", "x")), 10))
        assertNull(AgentWire.call(endpoint, "  ", "m", "p", listOf(AgentMessage("user", "x")), 10))
    }

    @Test
    fun `a call carries the key in the header and never in the preview or the safe headers`() {
        val call = AgentWire.call(
            endpoint, TEST_KEY, "deepseek-chat", "DeepSeek",
            listOf(AgentMessage("system", "rules"), AgentMessage("user", "the item")), 100, sentCharacters = 7,
        )
        assertNotNull(call)
        assertEquals("https://api.deepseek.com/v1/chat/completions", call.url)
        assertEquals("Bearer $TEST_KEY", call.headers["Authorization"])
        assertEquals("Bearer [redacted]", call.safeHeaders["Authorization"])
        assertTrue(!call.preview.contains(TEST_KEY), "the preview must not carry the key")
        assertEquals("[system]\nrules\n\n[user]\nthe item", call.preview)
        assertEquals(7, call.sentCharacters)
    }

    @Test
    fun `ollama needs no key`() {
        val local = (AgentConfig.checkBaseUrl("http://192.168.1.20:11434/v1", AgentProviderKind.OLLAMA)
            as AgentConfig.UrlCheck.Good).endpoint
        val call = AgentWire.call(local, null, "qwen3", "Ollama", listOf(AgentMessage("user", "x")), 10)
        assertNotNull(call)
        assertTrue(!call.headers.containsKey("Authorization"))
    }

    @Test
    fun `a completion is read out of the OpenAI shape`() {
        val c = AgentWire.readCompletion(completion("""{"actions":[]}"""), "fallback").getOrThrow()
        assertEquals("""{"actions":[]}""", c.content)
        assertEquals("deepseek-chat", c.model)
        assertEquals(800, c.tokensIn)
        assertEquals(120, c.tokensOut)
    }

    @Test
    fun `a non-json answer says the base URL may be wrong, which is what it usually means`() {
        val r = AgentWire.readCompletion(AgentHttpResponse(200, "<html>404</html>"), "m")
        val f = (r.exceptionOrNull() as AgentWire.WireError).failure
        assertTrue(f is AgentFailure.BadResponse && f.message.contains("OpenAI-compatible"), f.message)
    }

    @Test
    fun `json of the wrong shape is reported, not crashed on`() {
        for (body in listOf("""{"ok":true}""", """{"choices":[]}""", """{"choices":[{}]}""")) {
            val r = AgentWire.readCompletion(AgentHttpResponse(200, body), "m")
            assertTrue(r.isFailure, "\"$body\" should fail")
        }
    }

    @Test
    fun `the http errors keep the taxonomy the phone already uses`() {
        assertTrue(AgentWire.httpFailure(401, "") is AgentFailure.Auth)
        assertTrue(AgentWire.httpFailure(403, "") is AgentFailure.Auth)
        assertTrue(AgentWire.httpFailure(404, "") is AgentFailure.NotFound)
        assertTrue(AgentWire.httpFailure(429, "") is AgentFailure.RateLimited)
        assertTrue(AgentWire.httpFailure(500, "") is AgentFailure.Http)
    }

    @Test
    fun `an error body's message is lifted out of the provider's json envelope`() {
        val f = AgentWire.httpFailure(401, """{"error":{"message":"Invalid API key","type":"auth"}}""")
        assertTrue(f.message.contains("Invalid API key"), f.message)
    }

    @Test
    fun `retryable failures are the ones worth trying again`() {
        assertTrue(AgentWire.httpFailure(429, "").retryable)
        assertTrue(AgentWire.httpFailure(503, "").retryable)
        assertTrue(!AgentWire.httpFailure(401, "").retryable)
        assertTrue(!AgentWire.httpFailure(400, "").retryable)
        assertEquals(setOf(429, 500, 502, 503, 504), AgentWire.RETRY_STATUS)
    }

    @Test
    fun `an echoed key never survives redaction`() {
        val leaked = "Bad key $TEST_KEY supplied, header was Bearer $TEST_KEY"
        val out = AgentWire.redact(leaked, TEST_KEY)
        assertTrue(!out.contains(TEST_KEY), out)
        // And with no key to hand, a bearer token or a key-shaped string is still taken out.
        assertTrue(!AgentWire.redact("Authorization: Bearer abcdef123456", null).contains("abcdef123456"))
        assertTrue(!AgentWire.redact("key_0123456789abcdef leaked", null).contains("0123456789abcdef"))
    }

    @Test
    fun `an error message never carries the key`() {
        val f = AgentWire.httpFailure(401, """{"error":{"message":"key $TEST_KEY is invalid"}}""", TEST_KEY)
        assertTrue(!f.message.contains(TEST_KEY), f.message)
    }

    @Test
    fun `a fenced or wrapped object is still found`() {
        val fenced = "```json\n{\"actions\":[]}\n```"
        assertNotNull(AgentWire.extractJson(fenced))
        assertNotNull(AgentWire.extractJson("Sure! {\"actions\":[]} Hope that helps."))
        assertNotNull(AgentWire.extractJson("""{"actions":[]}"""))
        assertNull(AgentWire.extractJson("no object here"))
        assertNull(AgentWire.extractJson(""))
    }

    @Test
    fun `extracted json is the object, not a guess at it`() {
        val o = AgentWire.extractJson("""{"actions":[{"type":"note","headline":"x"}]}""")
        assertNotNull(o)
        assertEquals(1, (o["actions"] as JsonValue.Arr).items.size)
    }
}
