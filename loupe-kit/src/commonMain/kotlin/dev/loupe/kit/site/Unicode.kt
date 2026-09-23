package dev.loupe.kit.site

/** Unicode NFKC normalisation. JVM: `java.text.Normalizer`; iOS: `NSString.precomposedStringWithCompatibilityMapping`. */
internal expect fun nfkc(s: String): String

/** Unicode NFKD normalisation. JVM: `java.text.Normalizer`; iOS: `NSString.decomposedStringWithCompatibilityMapping`. */
internal expect fun nfkd(s: String): String
