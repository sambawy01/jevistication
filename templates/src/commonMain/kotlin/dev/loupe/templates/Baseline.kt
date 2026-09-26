package dev.loupe.templates

import dev.loupe.engine.DateFacts
import dev.loupe.engine.Item
import kotlinx.datetime.LocalDate

/**
 * The dumb version of a judgment (D4): a keyword, a pattern, a date rule or a sender rule that
 * answers the same question with no model at all.
 *
 * `Harness.evaluate` takes a baseline as a bare `(Item) -> String`. This type is the declarative
 * form behind that function, for two reasons a lambda cannot serve: a baseline has to be **shown**
 * to the user ("the model is being compared against: contains 'receipt' or 'total paid'"), and it
 * has to be **stored** with the user's judgment and survive a restart. [asFunction] hands the
 * harness exactly the shape it already takes, so nothing about how a baseline is scored changes.
 *
 * Every baseline returns one of its judgment's candidates, always; a test holds each template to
 * that. It reads only the item's text — the same text the model reads, headers included — so the
 * comparison is like for like.
 */
sealed interface Baseline {

    /** One line for the screen: what the dumb version actually does. */
    val description: String

    /** The label this baseline gives [text]. */
    fun answer(text: String): String

    /** Every label this baseline can return, so a template can be checked against its candidates. */
    val labels: Set<String>

    /** The shape `Harness.evaluate` takes. */
    fun asFunction(): (Item) -> String = { item -> answer(item.text) }

    /** This baseline with `{name}` placeholders replaced from [values]. */
    fun substitute(values: Map<String, String>): Baseline

    /** This baseline answering [labels]`[x]` wherever it answered `x` (labels not in the map are kept). */
    fun relabel(labels: Map<String, String>): Baseline

    /**
     * [whenFound] if any of [keywords] occurs in the text (case-insensitive, on word boundaries),
     * else [otherwise].
     */
    data class Keyword(
        val keywords: List<String>,
        val whenFound: String,
        val otherwise: String,
    ) : Baseline {
        init {
            require(keywords.isNotEmpty()) { "a keyword baseline needs at least one keyword" }
            require(keywords.all { it.isNotBlank() }) { "keywords must not be blank" }
        }

        private val regex by lazy { wordRegex(keywords) }

        override val description: String
            get() = "'$whenFound' if the text mentions " +
                keywords.joinToString(" or ") { "\"$it\"" } + ", otherwise '$otherwise'"

        override fun answer(text: String): String =
            if (regex.containsMatchIn(text)) whenFound else otherwise

        override val labels: Set<String> get() = setOf(whenFound, otherwise)

        override fun substitute(values: Map<String, String>): Baseline =
            copy(keywords = keywords.map { fill(it, values) })

        override fun relabel(labels: Map<String, String>): Baseline =
            copy(whenFound = labels[whenFound] ?: whenFound, otherwise = labels[otherwise] ?: otherwise)
    }

    /**
     * The first rule whose keywords occur wins; [otherwise] when none does. The Choice-shaped
     * keyword baseline: "flight" for a text saying "boarding pass", and so on.
     */
    data class KeywordMap(
        val rules: List<Rule>,
        val otherwise: String,
    ) : Baseline {
        data class Rule(val keywords: List<String>, val label: String) {
            init {
                require(keywords.isNotEmpty() && keywords.all { it.isNotBlank() }) {
                    "a keyword rule needs non-blank keywords"
                }
            }
        }

        init {
            require(rules.isNotEmpty()) { "a keyword map needs at least one rule" }
        }

        private val compiled by lazy { rules.map { wordRegex(it.keywords) to it.label } }

        override val description: String
            get() = rules.joinToString("; ") { rule ->
                "'${rule.label}' for " + rule.keywords.joinToString("/") { "\"$it\"" }
            } + "; otherwise '$otherwise'"

        override fun answer(text: String): String =
            compiled.firstOrNull { (regex, _) -> regex.containsMatchIn(text) }?.second ?: otherwise

        override val labels: Set<String> get() = rules.map { it.label }.toSet() + otherwise

        override fun substitute(values: Map<String, String>): Baseline =
            copy(rules = rules.map { it.copy(keywords = it.keywords.map { k -> fill(k, values) }) })

        override fun relabel(labels: Map<String, String>): Baseline =
            copy(rules = rules.map { it.copy(label = labels[it.label] ?: it.label) }, otherwise = labels[otherwise] ?: otherwise)
    }

    /** [whenFound] if the regular expression [pattern] matches anywhere, else [otherwise]. */
    data class Pattern(
        val pattern: String,
        val whenFound: String,
        val otherwise: String,
        /** What the pattern means, in words; a regex is not a description. */
        val meaning: String,
    ) : Baseline {
        init {
            // Compiled eagerly so a bad pattern fails when the template is defined, not mid-sweep.
            Regex(pattern)
        }

        private val regex by lazy { Regex(pattern, RegexOption.IGNORE_CASE) }

        override val description: String
            get() = "'$whenFound' if the text contains $meaning, otherwise '$otherwise'"

        override fun answer(text: String): String =
            if (regex.containsMatchIn(text)) whenFound else otherwise

        override val labels: Set<String> get() = setOf(whenFound, otherwise)

        override fun substitute(values: Map<String, String>): Baseline = this

        override fun relabel(labels: Map<String, String>): Baseline =
            copy(whenFound = labels[whenFound] ?: whenFound, otherwise = labels[otherwise] ?: otherwise)
    }

    /**
     * [whenFound] if the text carries any date before [before] (and not before [notBefore], when
     * given), else [otherwise]. The date arithmetic is `DateFacts`', the same extractor the expiry
     * radar uses; an ambiguous date counts on its **earlier** reading, the conservative error for
     * anything about a deadline.
     *
     * [before] may be a `{parameter}` until the template is instantiated.
     */
    data class DateBefore(
        val before: String,
        val whenFound: String,
        val otherwise: String,
    ) : Baseline {
        override val description: String
            get() = "'$whenFound' if the text contains a date before $before, otherwise '$otherwise'"

        override fun answer(text: String): String {
            val cutoff = runCatching { LocalDate.parse(before) }.getOrNull() ?: return otherwise
            val found = DateFacts.find(text).any { match ->
                listOfNotNull(match.date, match.alternate).min() < cutoff
            }
            return if (found) whenFound else otherwise
        }

        override val labels: Set<String> get() = setOf(whenFound, otherwise)

        override fun substitute(values: Map<String, String>): Baseline = copy(before = fill(before, values))

        override fun relabel(labels: Map<String, String>): Baseline =
            copy(whenFound = labels[whenFound] ?: whenFound, otherwise = labels[otherwise] ?: otherwise)
    }

    /**
     * [whenFound] if the message's `From:` header contains [sender] (case-insensitive), else
     * [otherwise]. Reads the header line the desktop sources write at the top of every email's text.
     */
    data class SenderIs(
        val sender: String,
        val whenFound: String,
        val otherwise: String,
        /** Also require a question mark in the text: the dumbest "needs a reply" there is. */
        val andAsksQuestion: Boolean = false,
    ) : Baseline {
        override val description: String
            get() = "'$whenFound' if the sender contains \"$sender\"" +
                (if (andAsksQuestion) " and the text contains a question mark" else "") +
                ", otherwise '$otherwise'"

        override fun answer(text: String): String {
            val from = text.lineSequence().firstOrNull { it.startsWith("From:", ignoreCase = true) }
                ?: return otherwise
            val matches = from.contains(sender, ignoreCase = true) && (!andAsksQuestion || '?' in text)
            return if (matches) whenFound else otherwise
        }

        override val labels: Set<String> get() = setOf(whenFound, otherwise)

        override fun substitute(values: Map<String, String>): Baseline = copy(sender = fill(sender, values))

        override fun relabel(labels: Map<String, String>): Baseline =
            copy(whenFound = labels[whenFound] ?: whenFound, otherwise = labels[otherwise] ?: otherwise)
    }

    /**
     * Always [label]. The dumbest baseline of all, and the right one where no rule is sensible:
     * a model that cannot beat "always say no" on a rare label is not earning its keep.
     */
    data class Constant(val label: String) : Baseline {
        override val description: String get() = "always '$label'"

        override fun answer(text: String): String = label

        override val labels: Set<String> get() = setOf(label)

        override fun substitute(values: Map<String, String>): Baseline = this

        override fun relabel(labels: Map<String, String>): Baseline = copy(label = labels[label] ?: label)
    }

    companion object {
        // The closing brace is escaped: Android's regex engine (ICU) rejects a bare `}`, which the JDK
        // and Kotlin/Native accept (tools/android-regex-check).
        private val PLACEHOLDER = Regex("""\{([a-z][a-z0-9_]*)\}""")

        /** Replaces `{name}` placeholders; an unknown one is left as written. */
        internal fun fill(text: String, values: Map<String, String>): String =
            PLACEHOLDER.replace(text) { match -> values[match.groupValues[1]] ?: match.value }

        /** Case-insensitive, whole-word alternation of literal keywords. */
        private fun wordRegex(keywords: List<String>): Regex =
            Regex(
                keywords.joinToString("|") { """(?<![\p{L}\p{Nd}\p{Nl}\p{No}])""" + Regex.escape(it) + """(?![\p{L}\p{Nd}\p{Nl}\p{No}])""" },
                RegexOption.IGNORE_CASE,
            )
    }
}
