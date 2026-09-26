package dev.loupe.agent

import dev.loupe.kit.review.ReviewProposal
import dev.loupe.persistence.JsonValue

/** What one agent run produced. */
data class AgentRun(
    /** The queue proposals to submit. Empty when nothing survived, or nothing was proposed. */
    val proposals: List<ReviewProposal>,
    /** The actions those proposals stand for, in the same order. */
    val actions: List<PreparedAction>,
    /** What the guard allowed and refused. */
    val report: GuardReport,
    /** What left the device, for the agent's log and the ledger. */
    val egress: EgressRecord,
    /** Set when the call itself failed. [proposals] is then empty. */
    val failure: AgentFailure? = null,
) {
    val ok: Boolean get() = failure == null
}

/** What the caller should do next. */
sealed interface AgentStep {
    /**
     * Perform this call and hand the answer back to [AgentSession.receive].
     *
     * The module does no I/O, so this is how a request happens: the platform posts [call] and
     * returns. It may be the first call or the one repair turn.
     */
    data class Send(val call: AgentCall) : AgentStep

    /** Nothing more to send. */
    data class Done(val run: AgentRun) : AgentStep
}

/**
 * One item's trip through the agent tier: gate → prompt → provider → parse → never list → queue.
 *
 * A session is a small state machine rather than a suspending function, and deliberately so. It
 * keeps this module free of a coroutines dependency and of sockets, which means the whole tier —
 * the prompt, the parse, the guard, the proposals — is testable with no network, no clock and no
 * fakes beyond a string of JSON. The platform supplies the transport and nothing else.
 *
 * **Never throws.** Every entry point contains its own failures and reports them as an
 * [AgentFailure] on an [AgentRun], the same contract the engine's A4 boundary keeps for a
 * misbehaving backend. A provider that answers with nonsense, a truncated body, an empty object or
 * a megabyte of HTML produces a failed run, never an exception out of a screen's event handler.
 */
class AgentSession internal constructor(
    private val endpoint: AgentEndpoint,
    private val apiKey: String?,
    private val model: String,
    private val provider: String,
    private val item: AgentItem,
    private val evidence: AgentEvidence,
    private val question: String,
    private val atIso: String,
) {
    private var messages: List<AgentMessage> = emptyList()
    private var attempt: Int = 0
    private var tokensIn: Int = 0
    private var tokensOut: Int = 0

    /** True once the repair turn has been spent. */
    var repaired: Boolean = false
        private set

    /** The first call, or null when one cannot be built (which [AgentRunner] has already ruled out). */
    fun start(): AgentCall? {
        messages = listOf(AgentMessage("system", AgentWire.schemaInstruction(ActionPlanWorkflow.schema))) +
            ActionPlanWorkflow.messages(item, evidence, question)
        return call()
    }

    /**
     * Reads one answer.
     *
     * A malformed answer buys exactly one repair turn — the model is told what was wrong and asked
     * again — and then the run fails. One turn, because a model that cannot produce the shape twice
     * will not produce it on the fifth attempt, and each attempt is the user's money.
     */
    fun receive(response: AgentHttpResponse): AgentStep = runCatching { step(response) }
        .getOrElse { t ->
            AgentStep.Done(
                failed(
                    AgentFailure.BadResponse(
                        "The provider's answer could not be read: " +
                            AgentWire.redact(t.message ?: "unknown error", apiKey),
                    ),
                ),
            )
        }

    private fun step(response: AgentHttpResponse): AgentStep {
        attempt++
        val completion = AgentWire.readCompletion(response, model, apiKey).getOrElse { t ->
            val failure = (t as? AgentWire.WireError)?.failure
                ?: AgentFailure.BadResponse("The provider's answer could not be read.")
            return AgentStep.Done(failed(failure))
        }
        tokensIn += completion.tokensIn
        tokensOut += completion.tokensOut

        val obj = AgentWire.extractJson(completion.content)
        val problems = if (obj == null) listOf("it is not valid JSON") else ActionPlanWorkflow.validate(obj)

        if (problems.isNotEmpty()) {
            if (repaired) {
                return AgentStep.Done(failed(AgentFailure.InvalidJson(problems.take(5).joinToString("; "))))
            }
            repaired = true
            messages = messages + listOf(
                AgentMessage("assistant", completion.content.take(8_000)),
                AgentMessage(
                    "user",
                    "That reply was invalid: " + problems.take(8).joinToString("; ") +
                        ". Reply again with only the corrected JSON object.",
                ),
            )
            val repair = call() ?: return AgentStep.Done(failed(AgentFailure.Config("The agent tier is off.")))
            return AgentStep.Send(repair)
        }
        return AgentStep.Done(finished(obj!!))
    }

    private fun call(): AgentCall? = AgentWire.call(
        endpoint = endpoint,
        apiKey = apiKey,
        model = model,
        provider = provider,
        messages = messages,
        maxTokens = ActionPlanWorkflow.MAX_TOKENS,
        sentCharacters = ActionPlanWorkflow.sentCharacters(item),
    )

    /** The run for a well-formed answer: parse, run the never list, and queue what survives. */
    private fun finished(obj: JsonValue.Obj): AgentRun {
        val proposed = ActionPlanWorkflow.actions(obj, evidence, ActionOrigin.Provider(provider, endpoint.host))
        val report = ActionGuard.checkAll(proposed)
        val proposals = AgentReview.proposals(report.allowed, atIso)
        return AgentRun(
            proposals = proposals,
            actions = report.allowed,
            report = report,
            egress = EgressRecord(
                atIso = atIso,
                itemId = item.itemId,
                judgmentId = evidence.judgmentId,
                provider = provider,
                host = endpoint.host,
                model = model,
                sentCharacters = ActionPlanWorkflow.sentCharacters(item),
                tokensIn = tokensIn,
                tokensOut = tokensOut,
                outcome = EgressRecord.Outcome.OK,
                actionsPrepared = report.allowed.size,
                actionsRefused = report.blocked.size,
                refusedRules = report.blocked.map { it.rule.code },
            ),
        )
    }

    /**
     * A failed run for a failure that happened outside [receive] — a transport that could not
     * reach the provider, or a session that could not start. Internal so that [runTo] can report
     * one in the same shape as everything else.
     */
    internal fun failedRun(failure: AgentFailure): AgentRun = failed(failure)

    private fun failed(failure: AgentFailure): AgentRun = AgentRun(
        proposals = emptyList(),
        actions = emptyList(),
        report = GuardReport(emptyList(), emptyList()),
        egress = EgressRecord(
            atIso = atIso,
            itemId = item.itemId,
            judgmentId = evidence.judgmentId,
            provider = provider,
            host = endpoint.host,
            model = model,
            sentCharacters = ActionPlanWorkflow.sentCharacters(item),
            tokensIn = tokensIn,
            tokensOut = tokensOut,
            outcome = EgressRecord.Outcome.FAILED,
            problem = AgentWire.redact(failure.message, apiKey),
        ),
        failure = failure,
    )
}

/**
 * The agent tier's front door.
 *
 * It holds the config and the gate and hands out sessions. It cannot itself send anything, and a
 * runner built from a config that is off or incomplete refuses to open a session at all — the off
 * switch is not a branch inside the request path, it is the absence of an [AgentEndpoint].
 */
class AgentRunner(
    private val config: AgentConfig,
    private val hasKey: Boolean,
    /**
     * What the person has paid for. [AgentTier.FREE] by default, which already includes every local
     * feature; only the AI assistant needs [AgentTier.ASSISTANT]. An entitlement that defaults to
     * the paid tier is a bug waiting to be a refund.
     */
    private val tier: AgentTier = AgentTier.FREE,
) {
    /**
     * Whether a provider can be asked: the tier allows it, the config is on and complete, and (for
     * a hosted provider) a key is held.
     */
    val readiness: AgentReadiness
        get() = if (!tier.allows(AgentCapability.ASK_PROVIDER)) {
            AgentReadiness.NeedsSetup("The AI assistant is not included in your plan.")
        } else {
            config.readiness(hasKey)
        }

    val isReady: Boolean get() = readiness.isReady

    /** Whether the device may prepare actions on its own. Free: it needs no provider and costs nothing. */
    val canActOnDevice: Boolean get() = tier.allows(AgentCapability.ACT_ON_DEVICE)

    /** One line for the settings screen. */
    val statusLine: String
        get() = if (!tier.allows(AgentCapability.ASK_PROVIDER)) {
            "Off · not included in your plan"
        } else {
            config.statusLine(hasKey)
        }

    /**
     * Prepared actions from what the engine already knows, with no network involved.
     *
     * The same never list as the provider-backed path, because the rules are about what Loupe puts
     * in front of a person and not about who wrote it. Returns nothing at all when the tier does
     * not include on-device actions.
     */
    fun planLocally(actions: List<PreparedAction>): GuardReport {
        if (!canActOnDevice) return GuardReport(emptyList(), emptyList())
        // An on-device plan that somehow carried a provider origin would be mislabelled, so it is
        // refused here rather than shown with an Online badge it did not earn.
        val (onDevice, online) = actions.partition { it.origin == ActionOrigin.OnDevice }
        val report = ActionGuard.checkAll(onDevice)
        if (online.isEmpty()) return report
        return GuardReport(
            allowed = report.allowed,
            blocked = report.blocked + online.map {
                GuardVerdict.Blocked(
                    NeverRule.LABELLED,
                    "an on-device plan cannot contain an action from ${it.origin.label}",
                )
            },
        )
    }

    /** The gate over a batch of local decisions. Makes no request; safe to call when the tier is off. */
    fun sift(items: List<GateInput>): GateResult = AgentGate.sift(items)

    /**
     * A session for one gated item, or null when the tier cannot be used.
     *
     * [apiKey] is passed per call rather than held, so the key lives in the Keychain for as long as
     * possible and never sits in a long-lived object that might be logged or serialised.
     */
    fun open(
        item: AgentItem,
        evidence: AgentEvidence,
        apiKey: String? = null,
        question: String = "",
        atIso: String = "",
    ): AgentSession? {
        if (!tier.allows(AgentCapability.ASK_PROVIDER)) return null
        val endpoint = config.endpoint(hasKey) ?: return null
        if (endpoint.kind.needsKey && apiKey.isNullOrBlank()) return null
        if (item.itemId != evidence.itemId) return null
        return AgentSession(
            endpoint = endpoint,
            apiKey = apiKey,
            model = config.model.trim(),
            provider = config.providerName,
            item = item,
            evidence = evidence,
            question = question,
            atIso = atIso,
        )
    }

    /**
     * The record for an item the tier did not touch — the gate skipped it, or the tier is off.
     *
     * A skip is worth recording for the same reason a call is: *"409 never left this device"* is
     * only a claim if the 409 are counted.
     */
    fun notSent(itemId: String, judgmentId: String, atIso: String = ""): EgressRecord =
        EgressRecord.notSent(atIso, itemId, judgmentId)

    companion object {
        /** A runner that can never send anything: the shipped default. */
        fun off(): AgentRunner = AgentRunner(AgentConfig(), hasKey = false, tier = AgentTier.FREE)
    }
}

/** Convenience for a caller that has one proposal list to submit. */
fun AgentRun.proposalsOrEmpty(): List<ReviewProposal> = if (ok) proposals else emptyList()
