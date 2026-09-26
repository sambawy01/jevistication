package dev.loupe.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentProviderTest {
    @Test
    fun `the shipped default is off and cannot build an endpoint`() {
        val c = AgentConfig()
        assertEquals(AgentReadiness.Off, c.readiness(hasKey = true))
        assertNull(c.endpoint(hasKey = true), "an off config must not yield an endpoint")
        assertEquals("Off", c.statusLine(hasKey = true))
    }

    @Test
    fun `a hosted provider needs a key - and says so`() {
        val c = readyConfig()
        val r = c.readiness(hasKey = false)
        assertTrue(r is AgentReadiness.NeedsSetup && r.problem == "Add your key.", "got $r")
        assertNull(c.endpoint(hasKey = false))
        assertNotNull(c.endpoint(hasKey = true))
    }

    @Test
    fun `a hosted provider must be https`() {
        val bad = AgentConfig.checkBaseUrl("http://api.deepseek.com/v1", AgentProviderKind.OPENAI_COMPATIBLE)
        assertTrue(bad is AgentConfig.UrlCheck.Bad && bad.problem.contains("https"), "got $bad")
    }

    @Test
    fun `ollama may be plain http only on the local network`() {
        val ok = AgentConfig.checkBaseUrl("http://192.168.1.20:11434/v1", AgentProviderKind.OLLAMA)
        assertTrue(ok is AgentConfig.UrlCheck.Good)
        assertEquals("192.168.1.20", ok.endpoint.host)

        for (host in listOf("10.0.0.5", "172.16.3.4", "172.31.255.1", "localhost", "box.local", "127.0.0.1")) {
            assertTrue(AgentProviderKind.OLLAMA.let {
                AgentConfig.checkBaseUrl("http://$host:11434/v1", it) is AgentConfig.UrlCheck.Good
            }, "$host should count as local")
        }
        // 172.32 is outside the private range, and 8.8.8.8 is plainly not local.
        for (host in listOf("172.32.0.1", "8.8.8.8", "ollama.example.com")) {
            assertTrue(
                AgentConfig.checkBaseUrl("http://$host:11434/v1", AgentProviderKind.OLLAMA) is AgentConfig.UrlCheck.Bad,
                "$host must not count as local",
            )
        }
    }

    @Test
    fun `a url carrying a credential - a query or a fragment is refused`() {
        val cases = listOf(
            "https://user:pass@api.deepseek.com/v1",
            "https://api.deepseek.com/v1?key=abc",
            "https://api.deepseek.com/v1#frag",
            "https://api.deepseek.com/v1 with a space",
            "not-a-url",
            "https://",
            "",
        )
        for (u in cases) {
            assertTrue(
                AgentConfig.checkBaseUrl(u, AgentProviderKind.OPENAI_COMPATIBLE) is AgentConfig.UrlCheck.Bad,
                "\"$u\" must be refused",
            )
        }
    }

    @Test
    fun `the trailing slash is dropped so the chat url has exactly one`() {
        val good = AgentConfig.checkBaseUrl("https://api.deepseek.com/v1///", AgentProviderKind.OPENAI_COMPATIBLE)
        assertTrue(good is AgentConfig.UrlCheck.Good)
        assertEquals("https://api.deepseek.com/v1/chat/completions", good.endpoint.chatUrl)
    }

    @Test
    fun `the provider name falls back to the host`() {
        assertEquals("DeepSeek", readyConfig().providerName)
        assertEquals("api.deepseek.com", readyConfig().copy(name = "  ").providerName)
        assertEquals("your provider", AgentConfig().providerName)
    }

    @Test
    fun `a model name is required`() {
        val r = readyConfig().copy(model = "  ").readiness(hasKey = true)
        assertTrue(r is AgentReadiness.NeedsSetup && r.problem == "Set a model name.", "got $r")
    }

    @Test
    fun `a ready config reports on - its provider - and Online`() {
        assertEquals("On · DeepSeek · Online", readyConfig().statusLine(hasKey = true))
    }
}
