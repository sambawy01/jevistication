package dev.loupe.kit.site

import dev.loupe.engine.PortableText

/*
 * NFKC and NFKD from Loupe's pinned Unicode data (the engine's PortableText), the same on every
 * platform. They were `java.text.Normalizer` on the JVM and Android and NSString on iOS, whose
 * Unicode versions differ (docs/ANDROID-PLAN.md, "Known parity gaps", B2).
 */

/** Unicode NFKC normalisation, pinned ([PortableText.UNICODE_VERSION]). */
internal fun nfkc(s: String): String = PortableText.nfkc(s)

/** Unicode NFKD normalisation, pinned ([PortableText.UNICODE_VERSION]). */
internal fun nfkd(s: String): String = PortableText.nfkd(s)
