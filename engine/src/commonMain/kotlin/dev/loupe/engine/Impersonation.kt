package dev.loupe.engine

import kotlin.math.min

/** A person the user knows, and the addresses they legitimately write from. */
data class Contact(val name: String, val addresses: Set<String>)

/** A message as received: the name it displays, the address behind it, and its text. */
data class Message(val displayName: String, val address: String, val text: String)

/** Why a message looks like it is not from who it says. */
enum class ImpersonationReason {
    /** The display name is a known contact's, but the address is not one of theirs. */
    KNOWN_NAME_UNKNOWN_ADDRESS,

    /** The domain is a near-miss of one the contact really uses. */
    LOOKALIKE_DOMAIN,

    /**
     * The address's domain mixes writing systems in one label (checked on the decoded form).
     * Punycode alone is not a signal (docs/PHISHING-FORMULA.md rule 2): a single-script
     * international domain is someone's ordinary address.
     */
    HOMOGRAPH_IN_ADDRESS,

    /** Nothing has ever arrived from this address before. */
    FIRST_CONTACT_FROM_ADDRESS,
}

/** One signal raised against a message. */
data class ImpersonationSignal(val reason: ImpersonationReason, val detail: String)

/**
 * Person impersonation (C3) — the sibling of site fraud, and the more important of the two.
 *
 * This is the watcher nothing else can build: a messaging app sees one channel and does not see
 * your contacts' history across the others. Everything here is mechanical — name against address,
 * address against history, domain against the domains a contact really uses — so it produces
 * *signals*, never a verdict. Whether the message reads like the person is the judgment, and per
 * §4 it can only add suspicion.
 */
object Impersonation {

    /** Checks [message] against known [contacts] and the addresses seen in [history]. */
    fun check(
        message: Message,
        contacts: List<Contact>,
        history: List<Message> = emptyList(),
    ): List<ImpersonationSignal> {
        val signals = mutableListOf<ImpersonationSignal>()
        val domain = domainOf(message.address)

        val contact = contacts.firstOrNull { it.name.equals(message.displayName, ignoreCase = true) }

        if (contact != null && message.address.lowercase() !in contact.addresses.map { it.lowercase() }) {
            signals += ImpersonationSignal(
                ImpersonationReason.KNOWN_NAME_UNKNOWN_ADDRESS,
                "'${message.displayName}' is a known contact, but ${message.address} is not an " +
                    "address they write from",
            )

            val knownDomains = contact.addresses.mapNotNull { domainOf(it) }.toSet()
            if (domain != null && domain !in knownDomains) {
                val nearest = knownDomains.minByOrNull { levenshtein(domain, it) }
                if (nearest != null && levenshtein(domain, nearest) in 1..2) {
                    signals += ImpersonationSignal(
                        ImpersonationReason.LOOKALIKE_DOMAIN,
                        "$domain is a near-miss of $nearest",
                    )
                }
            }
        }

        if (domain != null && OriginFacts.hasMixedScripts(domain)) {
            signals += ImpersonationSignal(
                ImpersonationReason.HOMOGRAPH_IN_ADDRESS,
                "$domain mixes writing systems",
            )
        }

        if (history.isNotEmpty() &&
            history.none { it.address.equals(message.address, ignoreCase = true) }
        ) {
            signals += ImpersonationSignal(
                ImpersonationReason.FIRST_CONTACT_FROM_ADDRESS,
                "nothing has arrived from ${message.address} before",
            )
        }

        return signals
    }

    private fun domainOf(address: String): String? =
        address.substringAfter('@', "").lowercase().takeIf { it.isNotEmpty() }

    /** Edit distance, used only to spot a near-miss domain. */
    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = min(min(current[j - 1] + 1, previous[j] + 1), substitution)
            }
            previous = current
        }
        return previous[b.length]
    }
}
