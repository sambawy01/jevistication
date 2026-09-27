package dev.loupe.kit.settings

/**
 * Which language group an input belongs to, for the calibration prior and trust: `en`, `ar`,
 * `franco`, `es-fr` or `other` — the groups of Loupe Station's `model_calibration.json`.
 *
 * PROVENANCE: ported from Loupe Station (`~/laya-studio`, `laya_studio/langgroup.py`, commit
 * 0ff886d). [isFranco] and [francoEvidence] are the module's deterministic Franco-Arabic test, word
 * for word (the digit-word rule, the not-Franco exclusions and the word list are copied). Station
 * reads the script and the Latin language from laya's own detector (`laya.lang.analyse`), which
 * the phone does not have; [of] replaces it with a script count and a small stop-word vote for
 * English, Spanish/French and other Latin languages. Arabic script (MSA or Egyptian) is `ar`;
 * Franco-Arabic — Egyptian Arabic in Latin letters and digits ("3ayez a3raf", "law sama7t") —
 * is `franco`, which the calibration file marks low-trust: its answers always go to a person.
 *
 * Pure Kotlin, no model: the same answer for the same text on every platform.
 */
object LangGroup {
    const val EN: String = "en"
    const val AR: String = "ar"
    const val FRANCO: String = "franco"
    const val ES_FR: String = "es-fr"
    const val OTHER: String = "other"
    val GROUPS: List<String> = listOf(EN, AR, FRANCO, ES_FR, OTHER)

    private val TOKEN = Regex("[a-z0-9']+")
    private val VOWEL = Regex("[aeiou]")
    private val LETTER = Regex("[a-z]")

    // Digits that stand for Arabic letters: 2 hamza, 3 ain, 5 kha, 6 ta, 7 ha, 9 sad. Exactly one, 3-14 characters.
    private val DIGIT_WORD = Regex("^[a-z']*[235679][a-z']*$")

    // Letters-and-digits tokens that are English or technical, never Franco (units, ordinals, times, formats).
    private val NOT_FRANCO = Regex(
        "^(\\d+(st|nd|rd|th|am|pm|k|m|g|gb|mb|kb|tb|kg|km|cm|mm|ml|x|s|h|d|y|hz|khz|mhz|ghz|mp|px|pt|v|w|kw|kwh|p|fps)" +
            "|(mp|m4|h|x|b|p|a|f|e|g|s|t|v|w|u|q|r)\\d+[a-z]?|[a-z]{1,3}\\d{2,}|\\d+[a-z]{1,2}\\d+|utf\\d+|sha\\d+|md5|win\\d+" +
            "|covid\\d+|b2b|b2c|p2p|g2g|3d|2d|4k|5g|4g|3g|2fa|mp3|mp4|a4|a3|a5|b5)$",
    )

    // Common Egyptian Arabic words in Latin letters that are not English words (Station's list, verbatim).
    private val FRANCO_WORDS: Set<String> = """
        ezayak ezayek ezayko ezay 3ayez 3ayza 3ayzeen 3awez 3awza momken sama7t bta3 bta3ak bta3ek bta3y bta3i bta3na
        bta3etko bta3tak 3andoko 3andak 3andek 3andi 3andena mesh msh mish lessa lesa dlwa2ty delwa2ty dilwa2ti keda kda
        basha shokran shukran gedan geddan 7aga 7aga geneh gneh feen fein howa heya e7na ehna enta enti ento yomeen bokra
        bukra naharda nahrda tamam ahlan habibi yalla inshallah insha2allah khalas ba3d ba3den 2abl ma3a ma3ana ma3ak 3ala
        3al fel lel elly elli 3ashan 3shan 3alashan leeh leih wala walla ya3ni yaani ma3lesh ma3lesh mafeesh mafish
        gedid gedida gedeed 3amel 3amla 3amlin ne7gez a7gez te2olly te2olulna te2dar a3raf ne3raf etba3at wesel wesselna weselsh wesselsh
    """.trim().split(Regex("\\s+")).toSet()

    /** (digit words, Franco words, Latin word tokens) in [text]. */
    fun francoEvidence(text: String): Triple<Int, Int, Int> {
        val tokens = TOKEN.findAll(text.lowercase()).map { it.value.trim('\'') }.filter { it.isNotEmpty() }.toList()
        val digitWords = tokens.count { t ->
            t.length in 3..14 && DIGIT_WORD.matches(t) && VOWEL.containsMatchIn(t) && !NOT_FRANCO.matches(t)
        }
        val lexicon = tokens.count { it in FRANCO_WORDS }
        val words = tokens.count { LETTER.containsMatchIn(it) }
        return Triple(digitWords, lexicon, words)
    }

    /**
     * Franco-Arabic: at least two Franco signals, one of them a digit word, or three Franco words;
     * and not a vanishing share of a long English text (8% of its words or more).
     */
    fun isFranco(text: String): Boolean {
        if (text.isEmpty()) return false
        val (digitWords, lexicon, words) = francoEvidence(text)
        val hits = digitWords + lexicon
        if (hits < 2 || (digitWords == 0 && lexicon < 3)) return false
        return words == 0 || hits.toDouble() / words >= 0.08
    }

    /** The calibration language group of [text]. */
    fun of(text: String): String {
        var arabic = 0
        var latin = 0
        var other = 0
        for (c in text) {
            if (!c.isLetter()) continue
            when {
                c in '؀'..'ۿ' || c in 'ݐ'..'ݿ' || c in 'ࢠ'..'ࣿ' ||
                    c in 'ﭐ'..'﷿' || c in 'ﹰ'..'﻿' -> arabic++
                c in 'a'..'z' || c in 'A'..'Z' || c in 'À'..'ɏ' -> latin++
                else -> other++
            }
        }
        // laya's routing reads the dominant script: Arabic first, any other non-Latin script is "other".
        if (arabic > 0 && arabic >= latin && arabic >= other) return AR
        if (other > latin) return OTHER
        if (isFranco(text)) return FRANCO
        return latinLanguage(text)
    }

    private val EN_WORDS = setOf(
        "the", "and", "is", "are", "to", "of", "you", "your", "for", "this", "that", "with", "it", "on", "have", "be",
        "will", "please", "we", "our", "from", "was", "not", "has", "can", "my", "i",
    )
    private val ES_FR_WORDS = setOf(
        "el", "los", "las", "que", "y", "por", "para", "con", "una", "es", "su", "del", "al", "gracias", "hola", "pedido",
        "le", "les", "des", "et", "est", "pour", "avec", "une", "vous", "nous", "du", "au", "sur", "pas", "ce", "qui",
        "bonjour", "merci", "la", "de", "en", "un", "à", "où", "está", "también", "très",
    )
    private val OTHER_LATIN_WORDS = setOf(
        "der", "die", "das", "und", "ist", "nicht", "ich", "sie", "het", "een", "niet", "van", "il", "di", "che", "non",
        "per", "não", "você", "uma", "och", "är", "jest", "nie", "się", "bir", "ve", "bu", "için", "và", "của", "là",
    )
    private val WORD = Regex("[\\p{L}']+")

    /** English, Spanish/French or another Latin language, by a stop-word vote; English when nothing votes. */
    private fun latinLanguage(text: String): String {
        var en = 0
        var esFr = 0
        var other = 0
        for (m in WORD.findAll(text.lowercase())) {
            val w = m.value.trim('\'')
            if (w in EN_WORDS) en++
            if (w in ES_FR_WORDS) esFr++
            if (w in OTHER_LATIN_WORDS) other++
        }
        return when {
            esFr > en && esFr >= other -> ES_FR
            other > en && other > esFr -> OTHER
            else -> EN
        }
    }
}
