package dev.loupe.agent

import dev.loupe.engine.Decision
import dev.loupe.engine.Probability
import java.io.File

/**
 * A command-line way to point the agent tier at a real provider and read exactly what happens.
 *
 * `./gradlew :agent:demo --args="path/to/message.txt"`, with the provider in the environment:
 *
 * ```
 * LOUPE_AGENT_BASE_URL=https://api.deepseek.com/v1
 * LOUPE_AGENT_MODEL=deepseek-chat
 * LOUPE_AGENT_KEY=...            # read from the environment, never a flag: a flag is in `ps`
 * LOUPE_AGENT_NAME=DeepSeek      # optional, what the Online label says
 * LOUPE_AGENT_KIND=openai|ollama # optional, default openai
 * ```
 *
 * It exists because a tier whose behaviour you can only read about is a tier nobody can judge. It
 * prints the send preview first and waits for `yes`, so the first thing it proves is that nothing
 * leaves without a confirmation — then the egress record, then each prepared action, then every
 * never-list refusal by rule. Nothing is written anywhere: no ledger, no queue, no files.
 */
object AgentDemo {
    @JvmStatic
    fun main(args: Array<String>) {
        val kind = when (System.getenv("LOUPE_AGENT_KIND")?.lowercase()) {
            "ollama" -> AgentProviderKind.OLLAMA
            else -> AgentProviderKind.OPENAI_COMPATIBLE
        }
        val config = AgentConfig(
            enabled = true,
            kind = kind,
            baseUrl = System.getenv("LOUPE_AGENT_BASE_URL").orEmpty(),
            model = System.getenv("LOUPE_AGENT_MODEL").orEmpty(),
            name = System.getenv("LOUPE_AGENT_NAME").orEmpty(),
        )
        val key = System.getenv("LOUPE_AGENT_KEY")?.takeIf { it.isNotBlank() }
        // The demo stands in for a subscriber on the top tier; the tier gate itself is tested, not
        // demonstrated, because there is nothing to watch about a refusal.
        val runner = AgentRunner(config, hasKey = key != null || !kind.needsKey, tier = AgentTier.CONNECTED)
        if (!runner.isReady) {
            println("The agent tier is not usable: ${runner.statusLine}")
            println("Set LOUPE_AGENT_BASE_URL, LOUPE_AGENT_MODEL and LOUPE_AGENT_KEY.")
            return
        }

        val text = args.firstOrNull()?.let { File(it).takeIf(File::isFile)?.readText() }
            ?: SAMPLE.also { println("No readable file given; using the built-in sample message.\n") }

        // The gate, standing in for a real judgment run: this is what the on-device engine would
        // have concluded. Everything downstream is the real thing.
        val decision = Decision.Act("renewal_notice", Probability.of(0.93))
        val verdict = AgentGate.consider("demo-1", "j-renewal", decision)
        val evidence = when (verdict) {
            is GateVerdict.Skipped -> {
                println("The gate skipped it: ${verdict.reason}")
                return
            }

            is GateVerdict.Eligible -> verdict.evidence
        }

        val item = AgentItem(
            itemId = "demo-1",
            kind = "email",
            sender = System.getenv("LOUPE_AGENT_FROM").orEmpty(),
            subject = System.getenv("LOUPE_AGENT_SUBJECT").orEmpty(),
            text = text,
            todayIso = java.time.LocalDate.now().toString(),
        )
        val session = runner.open(item, evidence, key, question = "Is this worth acting on?", atIso = nowIso())
            ?: run {
                println("Could not open a session: ${runner.statusLine}")
                return
            }

        var confirmed = false
        val run = session.runTo(JvmAgentTransport()) { call ->
            if (!confirmed) {
                println("=".repeat(78))
                println("About to send to ${call.provider} (${call.host}), model ${call.model}")
                println("${call.sentCharacters} characters of this message; headers ${call.safeHeaders}")
                println("=".repeat(78))
                println(call.preview)
                println("=".repeat(78))
                print("Send this? [yes/N] ")
                if (readlnOrNull()?.trim()?.lowercase() != "yes") {
                    println("Nothing was sent.")
                    throw IllegalStateException("not confirmed")
                }
                confirmed = true
            } else {
                println("\n(repair turn: asking again with the problems)")
            }
        }

        println()
        println("--- what left this device ---")
        println(run.egress.line)
        if (!run.ok) {
            println("Failed: ${run.failure?.message}")
            return
        }
        println()
        println("--- prepared, waiting for approval (${run.proposals.size}) ---")
        run.proposals.forEachIndexed { i, p ->
            println("${i + 1}. [${p.kind}] ${p.title}")
            println("   ${p.inputSummary}")
            p.proposal.forEach { (k, v) -> println("   $k = ${v.replace("\n", "\\n").take(200)}") }
            println("   approving runs: ${p.actionType}")
        }
        if (run.report.blocked.isNotEmpty()) {
            println()
            println("--- refused by the never list (${run.report.blocked.size}) ---")
            run.report.blocked.forEach { println("- ${it.rule.code}: ${it.detail}\n  (${it.rule.promise})") }
        }
        println()
        println("Nothing was queued, scheduled, sent or saved: this is a demo.")
    }

    private fun nowIso(): String = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC)
        .withNano(0).toString()

    private val SAMPLE = """
        From: Billing <billing@registrar.example>
        Subject: Your domain example.com expires in 7 days

        Hello,

        Your registration for example.com expires on 2 October 2026. The renewal fee is 12 USD.
        If you do not renew before that date the domain will be suspended and may be released.

        You can renew from your account page. Reply to this message if you need an invoice for
        your records.

        Registrar Billing
    """.trimIndent()
}
