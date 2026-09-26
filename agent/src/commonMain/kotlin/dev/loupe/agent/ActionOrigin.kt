package dev.loupe.agent

/**
 * Who prepared an action, and therefore whether it must be labelled **Online**.
 *
 * docs/PRODUCT.md §4a says *"Labelled every time. Every result that came from the network says
 * Online, names its source, and shows when it was fetched."* That is easy to keep by hand and easy
 * to lose by hand, so it is a type: an action cannot exist without saying where it came from, and
 * the label is derived rather than written.
 *
 * The distinction is the whole point of the free on-device actions. A reminder that Loupe's own
 * code worked out from a date in a document went nowhere and must not carry an Online badge —
 * labelling it one would be a false admission, and users who see "Online" on something that never left the device
 * stop believing the badge at all. A draft a provider wrote must carry it, always.
 */
sealed interface ActionOrigin {
    /** What to show beside the action. */
    val label: String

    /** True when preparing this action sent something off the device. */
    val isOnline: Boolean

    /**
     * Prepared on the device, by Loupe's own code, from what the engine already knew.
     *
     * No network, no provider, no cost, and free. Nothing to label Online, because nothing
     * went anywhere.
     */
    data object OnDevice : ActionOrigin {
        override val label: String get() = "Loupe, on this device"
        override val isOnline: Boolean get() = false
    }

    /**
     * Written by a provider over the network.
     *
     * Carries both the display [name] and the [host] it actually went to, because "which server"
     * is a question the user is entitled to an answer to, and a display name alone cannot answer it.
     */
    data class Provider(val name: String, val host: String) : ActionOrigin {
        override val label: String get() = "$name (Online)"
        override val isOnline: Boolean get() = true
    }
}
