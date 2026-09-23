package dev.loupe.kit.site

import platform.Foundation.NSString
import platform.Foundation.decomposedStringWithCompatibilityMapping
import platform.Foundation.precomposedStringWithCompatibilityMapping

@Suppress("CAST_NEVER_SUCCEEDS")
internal actual fun nfkc(s: String): String = (s as NSString).precomposedStringWithCompatibilityMapping

@Suppress("CAST_NEVER_SUCCEEDS")
internal actual fun nfkd(s: String): String = (s as NSString).decomposedStringWithCompatibilityMapping
