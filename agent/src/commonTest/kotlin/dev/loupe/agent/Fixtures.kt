package dev.loupe.agent

import dev.loupe.engine.Probability

/** A gated decision, at a confidence that clears the default bar. */
internal fun evidence(
    itemId: String = "item-1",
    judgmentId: String = "j-renewal",
    label: String = "renewal",
    confidence: Double = 0.94,
    bar: Double = AgentGate.DEFAULT_BAR.value,
): AgentEvidence = AgentEvidence(
    itemId = itemId,
    judgmentId = judgmentId,
    label = label,
    confidence = Probability.of(confidence),
    bar = Probability.of(bar),
)

internal fun remind(
    text: String = "Your domain renews on the 2nd",
    whenIso: String = "2026-10-02",
    because: String = "the email says \"renews on 2 October\"",
    provider: String = "DeepSeek",
    e: AgentEvidence = evidence(),
) = PreparedAction.Remind(evidence = e, whenIso = whenIso, text = text, because = because, provider = provider)

internal fun note(
    headline: String = "This asks for your password",
    detail: String = "The message asks you to reply with your sign-in details.",
    provider: String = "DeepSeek",
    e: AgentEvidence = evidence(),
) = PreparedAction.NoteFinding(evidence = e, headline = headline, detail = detail, provider = provider)

internal fun draft(
    body: String = "Hello,\n\nThank you for your message. [confirm the date]\n\nBest regards",
    subject: String = "Re: Your order",
    provider: String = "DeepSeek",
    e: AgentEvidence = evidence(),
) = PreparedAction.DraftReply(
    evidence = e,
    to = "sender@example.com",
    subject = subject,
    body = body,
    language = "English",
    needsInfo = listOf("Which date can you confirm?"),
    notes = "",
    provider = provider,
)

internal fun event(
    subject: String = "Passport renewal appointment",
    startIso: String = "2026-11-03T09:30",
    provider: String = "DeepSeek",
    e: AgentEvidence = evidence(),
) = PreparedAction.CalendarEvent(
    evidence = e,
    startIso = startIso,
    endIso = null,
    subject = subject,
    location = null,
    because = "the letter gives 3 November at 9:30",
    provider = provider,
)

/** A ready hosted config, and the key the platform would hold for it. */
internal fun readyConfig(): AgentConfig = AgentConfig(
    enabled = true,
    kind = AgentProviderKind.OPENAI_COMPATIBLE,
    baseUrl = "https://api.deepseek.com/v1",
    model = "deepseek-chat",
    name = "DeepSeek",
)

internal const val TEST_KEY = "sk-test-0123456789abcdefghij"

/** A provider answer carrying [content] as the assistant message. */
internal fun completion(content: String, tokensIn: Int = 800, tokensOut: Int = 120): AgentHttpResponse {
    val escaped = content
        .replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
    return AgentHttpResponse(
        status = 200,
        body = """{"model":"deepseek-chat","choices":[{"message":{"role":"assistant","content":"$escaped"},
            "finish_reason":"stop"}],"usage":{"prompt_tokens":$tokensIn,"completion_tokens":$tokensOut}}"""
            .trimIndent().replace("\n", ""),
    )
}

/** An item the gate has let through. */
internal fun item(
    text: String = "Your domain example.com renews on 2 October 2026. The fee is 12 USD.",
    subject: String = "Renewal notice",
) = AgentItem(
    itemId = "item-1",
    kind = "email",
    sender = "Billing <billing@registrar.example>",
    subject = subject,
    text = text,
    todayIso = "2026-09-25",
)
