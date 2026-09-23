package dev.loupe.kit.site

import java.text.Normalizer

internal actual fun nfkc(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFKC)

internal actual fun nfkd(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFKD)
