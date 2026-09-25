package dev.loupe.agent

/** What a tier is allowed to do. */
enum class AgentCapability {
    /** Judge, surface and explain. What the free product does today. */
    EXPLAIN,

    /**
     * Act on what the on-device engine already knows: reminders, deadlines, renewals, the
     * subscription census. No network, no provider, no cost per use.
     */
    ACT_ON_DEVICE,

    /** Ask a provider to draft or plan. Sends the item's text off the device, and costs money. */
    ASK_PROVIDER,
}

/**
 * The tier ladder.
 *
 * Three rather than two, and the middle one is the interesting one. Reminders need no model at all:
 * `ExpiryRadar`, `RecurringMoney` and `TermChangeDetector` already exist, and a reminder is a
 * decision the engine already made plus a scheduled local notification. Zero calls, nothing leaving
 * the device, and probably the single feature most people would pay for.
 *
 * That matters commercially as much as technically. [LOCAL] has near-zero marginal cost per user, so
 * it can be a flat price; only [CONNECTED] has real cost of goods and needs metering. Putting
 * reminders behind the provider-backed tier would have meant charging per call for something that
 * costs nothing to run.
 *
 * The default is [FREE]. An entitlement that defaults to the paid tier is a bug waiting to be a
 * refund.
 */
enum class AgentTier(val code: String, private val capabilities: Set<AgentCapability>) {
    /** Judge, surface, explain. Today's product. */
    FREE("free", setOf(AgentCapability.EXPLAIN)),

    /** Acts on what the engine already knows, entirely on the device. */
    LOCAL("local", setOf(AgentCapability.EXPLAIN, AgentCapability.ACT_ON_DEVICE)),

    /** Adds drafting and planning through the user's own provider. */
    CONNECTED(
        "connected",
        setOf(AgentCapability.EXPLAIN, AgentCapability.ACT_ON_DEVICE, AgentCapability.ASK_PROVIDER),
    ),
    ;

    fun allows(capability: AgentCapability): Boolean = capability in capabilities

    /** Everything this tier may do, for a settings screen that lists it honestly. */
    val allowed: Set<AgentCapability> get() = capabilities

    companion object {
        fun parse(code: String): AgentTier? = entries.firstOrNull { it.code == code }
    }
}
