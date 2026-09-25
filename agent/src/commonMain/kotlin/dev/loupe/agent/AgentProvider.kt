package dev.loupe.agent

/**
 * The agent tier's provider settings: **the user's own** OpenAI-compatible endpoint, or their own
 * Ollama on the local network.
 *
 * This is the shared-Kotlin port of the iPhone's `AssistConfig.swift` (epic #7 child 16), which in
 * turn came from Loupe Station's `llm/providers.py`. It lives here, in common code, because the
 * agent tier runs on the desktop as well and the two must not drift: one set of URL rules, one
 * off-by-default gate, tested once.
 *
 * Two rules from [docs/PRODUCT.md] §4a are structural rather than advisory:
 *
 * - **Off by default.** A default-constructed [AgentConfig] has [AgentConfig.enabled] false, and
 *   [AgentConfig.readiness] refuses everything until the user has filled it in. No request can be
 *   built from a config that is not ready, because [AgentChat] takes an [AgentEndpoint] and the
 *   only way to get one is through [AgentConfig.endpoint], which returns null when it is not.
 * - **Your keys stay yours.** No key appears in this type. The key is held by the platform
 *   (iOS Keychain, the desktop's store) and handed to [AgentChat] per call, so a config can be
 *   logged, exported or shown on screen without leaking one.
 */
enum class AgentProviderKind(val code: String) {
    /**
     * Any HTTPS endpoint speaking `POST {base}/chat/completions` — DeepSeek, an open-weight model
     * on Together or Fireworks, OpenAI, OpenRouter. A key is required.
     */
    OPENAI_COMPATIBLE("openAICompatible"),

    /** Ollama on the user's own network (`http://192.168.1.20:11434/v1`). No key, no internet. */
    OLLAMA("ollama"),
    ;

    /** True when a call to this kind of provider needs an API key. */
    val needsKey: Boolean get() = this == OPENAI_COMPATIBLE

    /** True when a call to this kind of provider leaves the user's own network. */
    val leavesTheNetwork: Boolean get() = this == OPENAI_COMPATIBLE

    companion object {
        fun parse(code: String): AgentProviderKind? = entries.firstOrNull { it.code == code }
    }
}

/**
 * A base URL that passed [AgentConfig.checkBaseUrl]. It is the only thing [AgentChat] will call, so
 * a URL that failed the rules cannot reach the network by any path through this module.
 */
data class AgentEndpoint(
    /** The base, with no trailing slash: `https://api.deepseek.com/v1`. */
    val base: String,
    val scheme: String,
    val host: String,
    val kind: AgentProviderKind,
) {
    /** The chat-completions URL this endpoint posts to. */
    val chatUrl: String get() = "$base/chat/completions"
}

/** Why a config cannot be used yet, or that it can. */
sealed interface AgentReadiness {
    data object Ready : AgentReadiness

    /** The tier is off. Nothing is built, nothing is sent. */
    data object Off : AgentReadiness

    /** Something is missing or malformed; [problem] is the one line to show the user. */
    data class NeedsSetup(val problem: String) : AgentReadiness

    val isReady: Boolean get() = this is Ready
}

/**
 * The agent tier's settings. Holds no secret, so it is safe to persist as plain JSON and to show.
 *
 * @param enabled the user's switch. False is the shipped default and means no request is ever built.
 * @param model the provider's model name, e.g. `deepseek-chat`. Required: there is no default,
 *   because guessing one would send a request the user did not choose.
 * @param name what the **Online** label says; falls back to the host.
 */
data class AgentConfig(
    val enabled: Boolean = false,
    val kind: AgentProviderKind = AgentProviderKind.OPENAI_COMPATIBLE,
    val baseUrl: String = "",
    val model: String = "",
    val name: String = "",
) {
    /** The name the **Online** label shows: the user's own, else the host, else a neutral phrase. */
    val providerName: String
        get() {
            val n = name.trim()
            if (n.isNotEmpty()) return n.take(60)
            return hostOf(baseUrl) ?: "your provider"
        }

    /**
     * Whether this config may be used, given whether the platform holds a key.
     *
     * [hasKey] is passed in rather than read here because the key lives in the Keychain and this
     * type deliberately cannot see it.
     */
    fun readiness(hasKey: Boolean): AgentReadiness {
        if (!enabled) return AgentReadiness.Off
        if (model.trim().isEmpty()) return AgentReadiness.NeedsSetup("Set a model name.")
        if (model.trim().length > MAX_MODEL) return AgentReadiness.NeedsSetup("That model name is too long.")
        when (val u = checkBaseUrl(baseUrl, kind)) {
            is UrlCheck.Bad -> return AgentReadiness.NeedsSetup(u.problem)
            is UrlCheck.Good -> Unit
        }
        if (kind.needsKey && !hasKey) return AgentReadiness.NeedsSetup("Add your key.")
        return AgentReadiness.Ready
    }

    /**
     * The endpoint to call, or null when [readiness] is not [AgentReadiness.Ready].
     *
     * This is the single gate: every request in this module starts from an [AgentEndpoint], and
     * this is the only function that makes one.
     */
    fun endpoint(hasKey: Boolean): AgentEndpoint? {
        if (!readiness(hasKey).isReady) return null
        return (checkBaseUrl(baseUrl, kind) as? UrlCheck.Good)?.endpoint
    }

    /** One line for the settings screen. */
    fun statusLine(hasKey: Boolean): String = when (val r = readiness(hasKey)) {
        AgentReadiness.Off -> "Off"
        is AgentReadiness.NeedsSetup -> "Needs setup: ${r.problem}"
        AgentReadiness.Ready -> "On · $providerName · Online"
    }

    sealed interface UrlCheck {
        data class Good(val endpoint: AgentEndpoint) : UrlCheck

        data class Bad(val problem: String) : UrlCheck
    }

    companion object {
        const val MAX_URL: Int = 300
        const val MAX_MODEL: Int = 200

        /**
         * Station's `check_base_url`, tightened for a phone and ported from `AssistConfig.swift`:
         *
         * - at most [MAX_URL] characters, no space or control character;
         * - a scheme and a non-empty host, no user name or password (the key field is for that),
         *   no query and no fragment;
         * - a hosted provider must be `https` — a key must never cross the network in the clear;
         * - Ollama may be plain `http`, but only to a host on the user's own network, so "local
         *   model" cannot quietly become a call to someone else's server.
         *
         * Hand-parsed rather than through a URL type: there is no `java.net.URI` in common code,
         * and the rules are a small fixed list that is clearer read than assembled from a parser's
         * accidents.
         */
        fun checkBaseUrl(raw: String, kind: AgentProviderKind): UrlCheck {
            var s = raw.trim()
            while (s.endsWith("/")) s = s.dropLast(1)
            if (s.isEmpty()) return UrlCheck.Bad("Set the base URL.")
            if (s.length > MAX_URL) return UrlCheck.Bad("The base URL is too long.")
            if (s.any { it.code < 33 || it.code == 0x7f }) {
                return UrlCheck.Bad("The base URL must not contain spaces or control characters.")
            }
            if (s.contains('?') || s.contains('#')) return UrlCheck.Bad("The base URL must not contain ? or #.")

            val sep = s.indexOf("://")
            if (sep <= 0) return UrlCheck.Bad("The base URL is not a valid URL.")
            val scheme = s.substring(0, sep).lowercase()
            if (scheme.isEmpty() || !scheme.all { it in 'a'..'z' || it in '0'..'9' || it == '+' || it == '-' || it == '.' }) {
                return UrlCheck.Bad("The base URL is not a valid URL.")
            }
            val rest = s.substring(sep + 3)
            val authority = rest.substringBefore('/')
            if (authority.isEmpty()) return UrlCheck.Bad("The base URL is not a valid URL.")
            if (authority.contains('@')) {
                return UrlCheck.Bad("The base URL must not contain a user name or password; use the key field.")
            }
            val host = hostOfAuthority(authority) ?: return UrlCheck.Bad("The base URL is not a valid URL.")

            when (kind) {
                AgentProviderKind.OPENAI_COMPATIBLE ->
                    if (scheme != "https") return UrlCheck.Bad("A hosted provider must use https://.")

                AgentProviderKind.OLLAMA -> {
                    if (scheme != "http" && scheme != "https") return UrlCheck.Bad("Use http:// or https://.")
                    if (!isLocalNetwork(host)) {
                        return UrlCheck.Bad(
                            "Ollama must be on your own network (a 10.x, 172.16-31.x or 192.168.x address, " +
                                "or a .local name).",
                        )
                    }
                }
            }
            return UrlCheck.Good(AgentEndpoint(base = s, scheme = scheme, host = host, kind = kind))
        }

        /** The host of a base URL, or null when it has none. Used for the display name. */
        fun hostOf(raw: String): String? {
            val s = raw.trim()
            val sep = s.indexOf("://")
            if (sep < 0) return null
            val authority = s.substring(sep + 3).substringBefore('/')
            if (authority.contains('@')) return null
            return hostOfAuthority(authority)
        }

        private fun hostOfAuthority(authority: String): String? {
            // A bracketed IPv6 literal keeps its brackets; anything else splits at the last colon.
            if (authority.startsWith("[")) {
                val close = authority.indexOf(']')
                if (close < 0) return null
                val inner = authority.substring(1, close)
                return if (inner.isEmpty()) null else authority.substring(0, close + 1).lowercase()
            }
            val host = authority.substringBefore(':')
            if (host.isEmpty()) return null
            val port = authority.substringAfter(':', "")
            if (port.isNotEmpty() && (port.length > 5 || !port.all { it in '0'..'9' })) return null
            return host.lowercase()
        }

        /**
         * True for a host on the machine or the local network: localhost, a `.local` name, or a
         * private / loopback / link-local IPv4 address.
         */
        fun isLocalNetwork(host: String): Boolean {
            val h = host.lowercase()
            if (h == "localhost" || h.endsWith(".local") || h.endsWith(".localhost")) return true
            if (h == "[::1]") return true
            val parts = h.split('.')
            if (parts.size != 4) return false
            val n = parts.map { it.toIntOrNull() ?: return false }
            if (n.any { it !in 0..255 }) return false
            return when {
                n[0] == 10 -> true
                n[0] == 127 -> true
                n[0] == 192 && n[1] == 168 -> true
                n[0] == 169 && n[1] == 254 -> true
                n[0] == 172 && n[1] in 16..31 -> true
                else -> false
            }
        }
    }
}
