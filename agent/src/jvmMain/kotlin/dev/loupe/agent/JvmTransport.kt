package dev.loupe.agent

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * The desktop's transport: `java.net.http`, and nothing else.
 *
 * No third-party HTTP client, for the same reason the rest of the project has almost no
 * dependencies — this is a few dozen lines against the JDK, and every dependency in the path of
 * the user's mail is a dependency someone has to audit.
 *
 * The settings that matter here are the paranoid ones:
 * - **`Redirect.NEVER`.** The endpoint rules decide where a request may go. A 30x to another host
 *   would send the user's key somewhere they never named, so a redirect is an answer, not a detour.
 * - **No cookie handler.** Nothing about this call should be remembered between calls.
 * - **HTTP/1.1.** Not for speed; for one fewer negotiation with a provider whose gateway may be
 *   anything, and because the answer is one small JSON body.
 * - **A timeout on the request and on the connect**, because a phone or a laptop waiting forever on
 *   a rate-limited provider is the worst failure mode of the lot.
 */
class JvmAgentTransport(
    private val timeout: Duration = Duration.ofSeconds(60),
    private val maxRetries: Int = AgentRetry.MAX_RETRIES,
    private val sleep: (Double) -> Unit = { s -> Thread.sleep((s * 1000).toLong()) },
    client: HttpClient? = null,
) : AgentTransport {
    private val client: HttpClient = client ?: HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofSeconds(15))
        .version(HttpClient.Version.HTTP_1_1)
        .build()

    override fun post(call: AgentCall): AgentHttpResponse {
        var attemptsMade = 0
        while (true) {
            val response = send(call)
            if (!AgentRetry.shouldRetry(response.status, attemptsMade + 1, maxRetries)) return response
            sleep(AgentRetry.delaySeconds(attemptsMade, response.retryAfterSeconds))
            attemptsMade++
        }
    }

    private fun send(call: AgentCall): AgentHttpResponse {
        val builder = HttpRequest.newBuilder(URI.create(call.url))
            .timeout(timeout)
            .POST(HttpRequest.BodyPublishers.ofString(call.body))
        for ((name, value) in call.headers) builder.header(name, value)
        val http = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        return AgentHttpResponse(
            status = http.statusCode(),
            body = http.body() ?: "",
            retryAfterSeconds = http.headers().firstValue("Retry-After").orElse(null)?.toDoubleOrNull(),
        )
    }
}
