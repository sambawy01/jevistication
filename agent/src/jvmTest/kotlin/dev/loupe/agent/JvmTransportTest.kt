package dev.loupe.agent

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The transport against a real socket.
 *
 * The server is the JDK's own `com.sun.net.httpserver`, bound to loopback — so this is an honest
 * end-to-end test of the wire (headers, body, statuses, `Retry-After`) with no dependency and no
 * traffic leaving the machine. It is the test that says the DeepSeek path works, without a key.
 */
class JvmTransportTest {
    /** Runs [body] against a loopback server answering with [handler]. */
    private fun withServer(handler: (HttpExchange) -> Unit, body: (AgentEndpoint) -> Unit) {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val seen = mutableListOf<String>()
        server.createContext("/") { exchange ->
            seen += exchange.requestURI.path
            try {
                handler(exchange)
            } finally {
                exchange.close()
            }
        }
        server.start()
        try {
            // Loopback over plain HTTP is allowed for the local-provider kind, and only that kind.
            val url = "http://127.0.0.1:${server.address.port}/v1"
            val checked = AgentConfig.checkBaseUrl(url, AgentProviderKind.OLLAMA)
            assertTrue(checked is AgentConfig.UrlCheck.Good, "loopback must be a valid local endpoint: $checked")
            body(checked.endpoint)
        } finally {
            server.stop(0)
        }
    }

    private fun reply(exchange: HttpExchange, status: Int, json: String, retryAfter: String? = null) {
        val bytes = json.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        if (retryAfter != null) exchange.responseHeaders.add("Retry-After", retryAfter)
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.write(bytes)
    }

    private val answer =
        """{"model":"qwen3","choices":[{"message":{"role":"assistant","content":""" +
            """"{\"actions\":[{\"type\":\"note\",\"headline\":\"Renewal due\",\"detail\":\"2 October.\"}]}"}}],""" +
            """"usage":{"prompt_tokens":700,"completion_tokens":40}}"""

    @Test
    fun `a whole run goes out over a socket and comes back as a proposal`() {
        var bodySeen = ""
        var contentType = ""
        var authorization: String? = "unset"
        withServer({ exchange ->
            bodySeen = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            contentType = exchange.requestHeaders.getFirst("Content-Type") ?: ""
            authorization = exchange.requestHeaders.getFirst("Authorization")
            reply(exchange, 200, answer)
        }) { endpoint ->
            val config = AgentConfig(
                enabled = true,
                kind = AgentProviderKind.OLLAMA,
                baseUrl = endpoint.base,
                model = "qwen3",
                name = "Ollama",
            )
            val run = AgentRunner(config, hasKey = false, tier = AgentTier.CONNECTED)
                .open(item(), evidence(), apiKey = null, atIso = "2026-09-25T10:00:00Z")!!
                .runTo(JvmAgentTransport(timeout = Duration.ofSeconds(10)))

            assertTrue(run.ok, run.failure?.message ?: "")
            assertEquals(1, run.proposals.size)
            assertEquals("agent_note", run.proposals[0].kind)
            assertEquals("Ollama (Online)", run.proposals[0].proposal["origin"])
            assertEquals(700, run.egress.tokensIn)
            assertEquals(40, run.egress.tokensOut)

            // The body is the one the wire built, and the local provider got no key.
            assertEquals("application/json", contentType)
            assertEquals(null, authorization, "a local provider must be sent no key")
            assertTrue(bodySeen.startsWith("""{"max_tokens":"""), bodySeen.take(60))
            assertTrue(bodySeen.contains(""""model":"qwen3""""), bodySeen.take(200))
            assertTrue(bodySeen.contains("renews on 2 October 2026"), "the item's text must be in the body")
        }
    }

    @Test
    fun `the key reaches the provider in the Authorization header and nowhere else`() {
        var authorization: String? = null
        var bodySeen = ""
        withServer({ exchange ->
            authorization = exchange.requestHeaders.getFirst("Authorization")
            bodySeen = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            reply(exchange, 200, answer)
        }) { endpoint ->
            val call = AgentWire.call(
                endpoint, TEST_KEY, "qwen3", "Ollama", listOf(AgentMessage("user", "hello")), 100,
            )
            assertNotNull(call)
            JvmAgentTransport().post(call)
            assertEquals("Bearer $TEST_KEY", authorization)
            assertTrue(!bodySeen.contains(TEST_KEY), "the key must not be in the body")
        }
    }

    @Test
    fun `a rate-limited answer is retried exactly once, after the provider's own delay`() {
        val calls = AtomicInteger()
        val slept = mutableListOf<Double>()
        withServer({ exchange ->
            if (calls.incrementAndGet() == 1) {
                reply(exchange, 429, """{"error":{"message":"slow down"}}""", retryAfter = "2")
            } else {
                reply(exchange, 200, answer)
            }
        }) { endpoint ->
            val call = AgentWire.call(endpoint, null, "qwen3", "Ollama", listOf(AgentMessage("user", "x")), 100)!!
            val response = JvmAgentTransport(sleep = { slept += it }).post(call)
            assertEquals(200, response.status)
            assertEquals(2, calls.get())
            assertEquals(listOf(2.0), slept, "the provider said 2 seconds")
        }
    }

    @Test
    fun `a second rate-limited answer is returned rather than retried again`() {
        val calls = AtomicInteger()
        withServer({ exchange ->
            calls.incrementAndGet()
            reply(exchange, 429, """{"error":{"message":"still limited"}}""")
        }) { endpoint ->
            val call = AgentWire.call(endpoint, null, "qwen3", "Ollama", listOf(AgentMessage("user", "x")), 100)!!
            val response = JvmAgentTransport(sleep = {}).post(call)
            assertEquals(429, response.status)
            assertEquals(2, calls.get(), "one attempt plus one retry, and no more")
            val failure = AgentWire.httpFailure(response.status, response.body)
            assertTrue(failure is AgentFailure.RateLimited, failure.message)
        }
    }

    @Test
    fun `a 401 from the provider is not retried and fails the run`() {
        val calls = AtomicInteger()
        withServer({ exchange ->
            calls.incrementAndGet()
            reply(exchange, 401, """{"error":{"message":"bad key"}}""")
        }) { endpoint ->
            val config = AgentConfig(enabled = true, kind = AgentProviderKind.OLLAMA, baseUrl = endpoint.base, model = "qwen3")
            val run = AgentRunner(config, hasKey = false, tier = AgentTier.CONNECTED).open(item(), evidence())!!
                .runTo(JvmAgentTransport(sleep = {}))
            assertEquals(1, calls.get(), "an auth failure is not a capacity problem")
            assertTrue(run.failure is AgentFailure.Auth, run.failure?.message ?: "")
            assertTrue(run.failure!!.message.contains("bad key"), run.failure!!.message)
        }
    }

    @Test
    fun `a provider answering html fails with the message that says what is wrong`() {
        withServer({ exchange ->
            val bytes = "<html><body>Not found</body></html>".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.write(bytes)
        }) { endpoint ->
            val config = AgentConfig(enabled = true, kind = AgentProviderKind.OLLAMA, baseUrl = endpoint.base, model = "qwen3")
            val run = AgentRunner(config, hasKey = false, tier = AgentTier.CONNECTED).open(item(), evidence())!!
                .runTo(JvmAgentTransport(sleep = {}))
            assertTrue(run.failure is AgentFailure.BadResponse, run.failure?.message ?: "")
            assertTrue(run.failure!!.message.contains("OpenAI-compatible"), run.failure!!.message)
        }
    }

    @Test
    fun `nothing is reachable when the tier is off, whatever the server would have said`() {
        val calls = AtomicInteger()
        withServer({ exchange ->
            calls.incrementAndGet()
            reply(exchange, 200, answer)
        }) { endpoint ->
            val off = AgentConfig(enabled = false, kind = AgentProviderKind.OLLAMA, baseUrl = endpoint.base, model = "qwen3")
            assertEquals(null, AgentRunner(off, hasKey = false, tier = AgentTier.CONNECTED).open(item(), evidence()))
            assertEquals(0, calls.get(), "an off tier must not make a request")
        }
    }

    @Test
    fun `a redirect is an answer, not a detour to another host`() {
        // A 30x to somewhere the user never named must never carry their key there.
        withServer({ exchange ->
            exchange.responseHeaders.add("Location", "https://elsewhere.example/v1/chat/completions")
            exchange.sendResponseHeaders(302, -1)
        }) { endpoint ->
            val call = AgentWire.call(endpoint, TEST_KEY, "qwen3", "Ollama", listOf(AgentMessage("user", "x")), 100)!!
            val response = JvmAgentTransport(sleep = {}).post(call)
            assertEquals(302, response.status, "the redirect must be returned, not followed")
        }
    }
}
