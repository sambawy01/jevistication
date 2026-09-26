package dev.loupe.agent

/**
 * What a tier is allowed to do.
 *
 * Each capability says whether it needs a provider, and that one fact decides its price: anything
 * that runs on the device is free, and only what reaches an AI provider is paid (docs/AGENT.md §4).
 * A new capability has to answer the question when it is added, so it cannot land in the wrong tier
 * by being forgotten.
 */
enum class AgentCapability(
    /** True when using it sends something off the device to an AI provider, which costs money. */
    val needsProvider: Boolean,
) {
    /** Judge, surface and explain. */
    EXPLAIN(needsProvider = false),

    /**
     * Act on what the on-device engine already knows: reminders, deadlines, renewals, the
     * subscription list, term changes, dedup, the review queue. No network, no provider, no cost per
     * use.
     */
    ACT_ON_DEVICE(needsProvider = false),

    /**
     * The AI assistant: ask a provider to draft, plan or work through several steps. Sends the
     * item's text off the device, and costs money per call.
     */
    ASK_PROVIDER(needsProvider = true),
    ;

    /** Whether this capability sits behind the paid tier. Exactly the ones that need a provider. */
    val isPaid: Boolean get() = needsProvider
}

/**
 * The tiers: two, split by where the work happens.
 *
 * Everything local is free: judging and explaining, and all of what used to be the middle tier
 * (`LocalPlanner` reminders and renewals, `ExpiryRadar`, `RecurringMoney`, `TermChangeDetector`,
 * dedup and the review queue). None of it has a cost per user, and early users keep it free for life
 * (see [EarlyUserPolicy]).
 *
 * The one paid tier is the AI assistant, because it is the one thing with real cost of goods: every
 * call goes to a provider and is billed.
 *
 * What each tier allows is derived from [AgentCapability.needsProvider] rather than listed by hand,
 * so "local is free, provider-backed is paid" holds by construction.
 *
 * The default is [FREE]. An entitlement that defaults to the paid tier is a bug waiting to be a
 * refund.
 */
enum class AgentTier(
    val code: String,
    /** Whether this tier is sold. */
    val isPaid: Boolean,
) {
    /** Everything that runs on the device. */
    FREE("free", isPaid = false),

    /** Everything in [FREE], plus the AI assistant. The paid tier. */
    ASSISTANT("assistant", isPaid = true),
    ;

    fun allows(capability: AgentCapability): Boolean = !capability.needsProvider || isPaid

    /** Everything this tier may do, for a settings screen that lists it honestly. */
    val allowed: Set<AgentCapability> get() = AgentCapability.entries.filter(::allows).toSet()

    companion object {
        /**
         * The tier for a stored code, or null.
         *
         * The retired codes `"local"` and `"connected"` are deliberately not recognised: a stale code
         * must never grant the paid tier. The caller falls back to [FREE], which now includes
         * everything `"local"` did, and the paid tier comes only from a verified purchase.
         */
        fun parse(code: String): AgentTier? = entries.firstOrNull { it.code == code }
    }
}
