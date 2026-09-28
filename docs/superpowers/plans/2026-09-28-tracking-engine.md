# Tracking That Actually Works (subscriptions and expiry) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the subscription census and the expiry radar find Egyptian and English charges and documents from every source (mail, files, photo receipts, statement CSVs, calendar), in EGP / ج.م / جنيه / LE / L.E. and £ $ €, with Arabic-Indic digits, on a full timeline, and prove it on a labelled test set (recall ≥ 90 %, 0 false positives on everyday items, per language).

**Architecture:** A new shared-Kotlin package `dev.loupe.kit.tracking` in `:loupe-kit` holds the rules: one text form (`TrackingText`: digit fold, Arabic letter unification, whole-word patterns), money (`MoneyReader`), lexicons (`ChargeLexicon`, `MerchantHints`, `ExpiryLexicon`, `DocumentKinds`), dates (`TrackingDates`) and two extractors (`ChargeExtractor` with de-duplication, `ExpiryExtractor` with the full timeline). `WatcherRun` and `WatcherFindings` call them instead of their English-only regexes; the engine's `RecurringMoney`, `ExpiryRadar` and `DateFacts` are unchanged (rules first, model second, as today). A labelled fixture set under `loupe-kit/src/commonTest/fixtures/tracking/` and a measurement test gate the result on the JVM and the iOS simulator.

**Tech Stack:** Kotlin Multiplatform 2.1.0 (`commonMain` / `commonTest`, JVM + iosSimulatorArm64), kotlinx-datetime, `:sources-common` (`SourceItem`, `CsvRows`, `Inbox`, `PhoneItems`, `SourceFs`), `:engine` (`Charge`, `RecurringMoney`, `DateFacts`, `DateMatch`, `ValidityRule`). Swift only for the compile fixes the new constructor parameters need.

**Spec:** `docs/superpowers/specs/2026-09-28-loupe-home-ask-me-design.md` §7 (7.1 diagnosis, 7.2 required changes, 7.3 measured bar), §11 (the parity dependency). Mockup `docs/superpowers/specs/2026-09-28-mockups/tracking.html` shows where these rows land (UI is step 4, not this plan).

## Global Constraints

- Shared Kotlin only: no UI change. Swift is touched only where a Kotlin constructor gained parameters (compile fixes, same values as before).
- No user-facing "Laya" in any string this plan adds (finding titles, evidence, `why` lines).
- Model prompts unchanged: no edit to templates, judgment wording, `DecisionEngine` inputs or the document-type template the radar's model half uses.
- `use_calibration` stays OFF by default; nothing here reads or writes it.
- Rules first, model second: every rule is mechanical and runs without the model; the radar's model half (`ExpiryRadar.scan`) keeps deciding the document type exactly as today.
- Money is never converted: amounts keep their currency; a merchant seen in two currencies is two census rows.
- The digit fold: Station's `parity` branch (`origin/parity`, 17 commits ahead of `main` on 2026-09-28, not merged) adds `dev.loupe.engine.PortableText.foldDigits`. Until it lands, `TrackingText.foldDigits` is the one local fold (it delegates to `CsvRows.normalizeDigits`, same contract: same length, ASCII + Arabic-Indic + Persian digits). When parity merges, change that one function body to `PortableText.foldDigits(s)`; nothing else changes. Regexes here use explicit `[0-9]`, never `\d`/`\w`/`\b` on folded text.
- `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home` for every Gradle command.
- DerivedData on `/Volumes/Sambawy/.loupe-agent-tmp/tracking-engine/DerivedData`; one simulator at a time (`iPhone 17 Pro Max`).
- Targeted tests while working (`:loupe-kit:jvmTest --tests …`, and `:loupe-kit:iosSimulatorArm64Test --tests …` where noted); the full iOS suite plus `./gradlew check` once, in the last task.
- Commit + push after the gate is green (the owner's rule): each task commits on the branch `tracking-engine` and pushes it at once; `main` moves only in the last task, after the full gate. Commit messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Stage paths explicitly.
- Kotlin/Native test names: camelCase function names here; never a comma inside a backticked name.
- Arabic RTL / Dynamic Type / ≥ 44 pt / VoiceOver: not applicable (no UI); evidence strings keep the document's own text verbatim so the UI can show them right-to-left.

## Review Focus

1. **"تنتهي بـ ٧٧٢٠" on a receipt is a card number, not an expiry.** Arabic receipts say the card "ends with" its last digits using the expiry verb; such a receipt must not land on the expiry timeline. Pinned by `ExpiryExtractorTest.cardEndingWordsOnAReceiptAreNotAnExpiry` (Task 6) and the Spotify fixtures (Task 8).
2. **One merchant, two currencies.** Netflix billed in EGP and in USD must be two rows ("Netflix (EGP)", "Netflix (USD)") and two monthly totals, never one sum. Pinned by `WatcherTrackingTest.aMerchantInTwoCurrenciesIsTwoRowsAndTwoTotals` (Task 7).
3. **Numbers that are not money.** Phone numbers, national ID numbers, reference codes, dates, "Red 1000 plan" and "SALE 50" must not become amounts. Pinned by `MoneyReaderTest.numbersThatAreNotMoneyAreIgnored` (Task 2).
4. **Promotions look like charges and expiries.** "Subscribe now for EGP 99/month", "اشترك الآن … بـ ١٢٠ جنيه" three months running, and "This offer expires 30/10/2026" must raise nothing. Pinned by `ChargeExtractorTest.promotionsCreditsAndRefundsAreNotCharges`, `ExpiryExtractorTest.anOfferThatExpiresIsNotADocument` and the negatives in Task 8.
5. **One charge seen twice vs two real charges.** A Netflix receipt email and its bank statement row (a day apart) are one charge with both references; two monthly charges of the same amount stay two; two same-day rows of one statement file stay two. Pinned by `ChargeExtractorTest.theSameChargeInMailAndStatementIsOneWithBothReferences` and `twoRowsOfOneStatementAreNotMerged` (Task 5).

## File Structure

| File | Responsibility |
|---|---|
| Create `loupe-kit/src/commonMain/kotlin/dev/loupe/kit/tracking/TrackingText.kt` | The digit fold (one function), the match form, whole-word pattern building |
| Create `.../tracking/MoneyReader.kt` | Amounts with their currency in free text; the amount a receipt is about; `lineAt` |
| Create `.../tracking/ChargeLexicon.kt` | Charge words, not-a-charge words, subscription words (EN / AR / Egyptian / Franco) |
| Create `.../tracking/MerchantHints.kt` | Known merchants and payment rails (InstaPay, Fawry); payee / service lines; canonical names |
| Create `.../tracking/ExpiryLexicon.kt` | Expiry words, promotion words, `DocumentKind`, `DocumentKinds` |
| Create `.../tracking/TrackingDates.kt` | `DateFacts` over folded text plus year-first and Arabic-month dates |
| Create `.../tracking/ChargeExtractor.kt` | `TrackedCharge`; charges from every item kind; de-duplication |
| Create `.../tracking/ExpiryExtractor.kt` | `ExpiryFind`, `ExpiryBucket`; the full expiry timeline |
| Modify `.../watchers/WatcherRun.kt` | Use the extractors; census per currency; new report fields |
| Modify `.../watchers/WatcherFindings.kt` | Census rows with currency and lines; expiry rows with their kind; totals per currency |
| Modify `loupe-kit/build.gradle.kts` | `TRACKING_FIXTURES` test path |
| Modify `ios/Loupe/Now/WatchersService.swift`, `ios/LoupeTests/GuardModelTests.swift` (+ any other `CensusRow(`/`ExpiryRow(` call) | Compile fixes for the new parameters |
| Create `loupe-kit/src/commonTest/kotlin/dev/loupe/kit/tracking/*Test.kt`, `TrackingItems.kt`, `TrackingFixtures.kt` | Unit tests, item builders, the fixture loader |
| Create `loupe-kit/src/commonTest/fixtures/tracking/items/*.txt`, `statement.csv` | The labelled set (§7.3) |

## Setup (once, before Task 1)

- [ ] **Branch**

```bash
cd /Users/bistrocloud/Documents/Loupe/jevistication
git switch main && git pull --ff-only
git switch -c tracking-engine
git branch -r --contains origin/parity | grep -q 'origin/main' && echo "parity merged: use PortableText.foldDigits in Task 1" || echo "parity not merged: local fold"
mkdir -p /Volumes/Sambawy/.loupe-agent-tmp/tracking-engine
```

If the check prints "parity merged", write `TrackingText.foldDigits` in Task 1 as `PortableText.foldDigits(s)` (import `dev.loupe.engine.PortableText`) instead of the `CsvRows` delegation; everything else is the same.

The Kotlin test command used below (only `--tests` changes):

```bash
cd /Users/bistrocloud/Documents/Loupe/jevistication && export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home && ./gradlew :loupe-kit:jvmTest --tests '<pattern>'
```

---

### Task 1: One text form for the trackers

**Files:**
- Create: `loupe-kit/src/commonMain/kotlin/dev/loupe/kit/tracking/TrackingText.kt`
- Test: `loupe-kit/src/commonTest/kotlin/dev/loupe/kit/tracking/TrackingTextTest.kt`

**Interfaces:**
- Produces: `object TrackingText { fun foldDigits(s: String): String; fun matchForm(s: String): String; fun phrase(p: String): String; fun words(entries: List<String>): Regex }`. Contract: both transforms keep the length; `words` entries are literal phrases, or raw lower-case regex after a `re:` prefix; Arabic phrases accept attached prefixes and pronoun suffixes.

- [ ] **Step 1: Write the failing test**

```kotlin
package dev.loupe.kit.tracking

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrackingTextTest {
    @Test
    fun foldsArabicAndPersianDigitsKeepingTheLength() {
        assertEquals("2028/03/14", TrackingText.foldDigits("٢٠٢٨/٠٣/١٤"))
        assertEquals("1403", TrackingText.foldDigits("۱۴۰۳"))
        val s = "المبلغ: ٦٩٫٩٩ ج.م"
        assertEquals(s.length, TrackingText.foldDigits(s).length)
    }

    @Test
    fun matchFormUnifiesArabicSpellingsCaseAndDigits() {
        assertEquals("صالحه حتي", TrackingText.matchForm("صالحة حتى"))
        assertEquals("اقامه", TrackingText.matchForm("إقامة"))
        assertEquals("تامين", TrackingText.matchForm("تأمين"))
        assertEquals("netflix 165", TrackingText.matchForm("NETFLIX ١٦٥"))
        val s = "تأمين إيجار آخر"
        assertEquals(s.length, TrackingText.matchForm(s).length)
    }

    @Test
    fun wordsMatchWholeWordsAndArabicAffixes() {
        val r = TrackingText.words(listOf("paid", "تم الدفع", "مبلغ", "re:expir[a-z]*"))
        assertTrue(r.containsMatchIn(TrackingText.matchForm("Amount paid: EGP 165")))
        assertFalse(r.containsMatchIn(TrackingText.matchForm("This invoice is unpaid")))
        assertTrue(r.containsMatchIn(TrackingText.matchForm("وتم الدفع بنجاح")))
        assertTrue(r.containsMatchIn(TrackingText.matchForm("تم تحويل بمبلغ ٣٥٠ جنيه")))
        assertFalse(r.containsMatchIn(TrackingText.matchForm("مبلغين")))
        assertTrue(r.containsMatchIn(TrackingText.matchForm("Date of EXPIRY")))
    }

    @Test
    fun phraseEscapesAndAllowsAnySpacing() {
        assertEquals("osn\\+", TrackingText.phrase("OSN+"))
        assertTrue(Regex(TrackingText.phrase("valid until")).containsMatchIn("valid   until"))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: the Kotlin test command with `--tests 'dev.loupe.kit.tracking.TrackingTextTest'`
Expected: FAIL, `Unresolved reference: TrackingText`.

- [ ] **Step 3: Write `TrackingText`**

```kotlin
package dev.loupe.kit.tracking

import dev.loupe.sources.common.CsvRows

/**
 * The text forms the trackers match against (spec 2026-09-28 §7.2). Both transforms keep the length, so an offset
 * found in a form is an offset in the original text too, and evidence can be cut from the original verbatim.
 */
object TrackingText {
    /** A letter or digit of [matchForm] text: ASCII letters and digits, and the Arabic letters U+0621..U+064A. */
    private const val WORD = "a-z0-9ء-ي"
    private const val META = ".^$*+?()[]{}|\\"

    /**
     * Arabic-Indic (U+0660..0669) and Persian (U+06F0..06F9) digits to ASCII `0-9`; same length.
     *
     * The one digit fold of the trackers. Station's `parity` branch adds `dev.loupe.engine.PortableText.foldDigits`
     * with the same contract; when it is on `main`, this body becomes `PortableText.foldDigits(s)` and nothing else
     * changes.
     */
    fun foldDigits(s: String): String = CsvRows.normalizeDigits(s)

    /**
     * Lower case, digits folded, and the Arabic letters written several ways brought to one: أ إ آ ٱ → ا, ى → ي,
     * ة → ه. Same length as [s].
     */
    fun matchForm(s: String): String {
        val folded = foldDigits(s)
        val out = CharArray(folded.length)
        for (i in folded.indices) {
            out[i] = when (val c = folded[i]) {
                'أ', 'إ', 'آ', 'ٱ' -> 'ا'
                'ى' -> 'ي'
                'ة' -> 'ه'
                else -> c.lowercaseChar()
            }
        }
        return out.concatToString()
    }

    /** [p] as a regex over [matchForm] text: normalised, metacharacters escaped, any run of spaces matching one or more. */
    fun phrase(p: String): String =
        matchForm(p).trim().split(Regex("\\s+")).joinToString("\\s+") { word ->
            buildString { for (c in word) { if (c in META) append('\\'); append(c) } }
        }

    /**
     * Whole-word alternatives over [matchForm] text: no letter or digit right before or after. An entry is a
     * literal phrase (see [phrase]) unless it starts with `re:`; then the rest is a regex already in the match form
     * (lower case; never an upper-case escape such as `\S`). A literal with Arabic letters may carry the attached
     * prefixes و ف, ب ل ك and ال in front and a pronoun or plural suffix after (بمبلغ, وتم الدفع, اشتراكك).
     */
    fun words(entries: List<String>): Regex {
        val alts = entries.map { e ->
            if (e.startsWith("re:")) {
                e.removePrefix("re:")
            } else {
                val body = phrase(e)
                if (body.any { it in 'ء'..'ي' }) "(?:و|ف)?(?:ب|ل|ك)?(?:ال)?$body(?:ك|كم|ه|ها|هم|ي|ات)?" else body
            }
        }
        return Regex("(?<![$WORD])(?:${alts.joinToString("|")})(?![$WORD])")
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: the Kotlin test command with `--tests 'dev.loupe.kit.tracking.TrackingTextTest'`
Expected: 4 tests PASS.

- [ ] **Step 5: Commit**

```bash
cd /Users/bistrocloud/Documents/Loupe/jevistication
git add loupe-kit/src/commonMain/kotlin/dev/loupe/kit/tracking/TrackingText.kt loupe-kit/src/commonTest/kotlin/dev/loupe/kit/tracking/TrackingTextTest.kt
git commit -m "kit: tracking text form (digit fold, Arabic spellings, whole words)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
git push -u origin tracking-engine
```

---

### Task 2: Money in EGP, ج.م, جنيه, LE, L.E. and £ $ €

**Files:**
- Create: `loupe-kit/src/commonMain/kotlin/dev/loupe/kit/tracking/MoneyReader.kt`
- Test: `loupe-kit/src/commonTest/kotlin/dev/loupe/kit/tracking/MoneyReaderTest.kt`

**Interfaces:**
- Consumes: `TrackingText.foldDigits`, `TrackingText.matchForm`, `TrackingText.words`, `CsvRows.parseNumber`.
- Produces: `data class Money(val minor: Long, val currency: String, val text: String, val start: Int)`; `object MoneyReader { fun find(text: String): List<Money>; fun best(text: String): Money?; fun code(token: String): String; fun lineAt(text: String, index: Int): String }`.

- [ ] **Step 1: Write the failing test**

```kotlin
package dev.loupe.kit.tracking

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MoneyReaderTest {
    private fun one(text: String): Pair<Long, String> = MoneyReader.find(text).single().let { it.minor to it.currency }

    @Test
    fun readsEgyptianPoundsWrittenEveryWay() {
        assertEquals(16500L to "EGP", one("Amount paid: EGP 165.00"))
        assertEquals(125000L to "EGP", one("Total amount paid: L.E. 1,250.00"))
        assertEquals(9900L to "EGP", one("Plan LE 99 a month"))
        assertEquals(35000L to "EGP", one("E£ 350"))
        assertEquals(35000L to "EGP", one("المبلغ: 350 جنيه"))
        assertEquals(6999L to "EGP", one("المبلغ المدفوع: ٦٩٫٩٩ ج.م"))
        assertEquals(22000L to "EGP", one("إجمالي المبلغ: ٢٢٠ جم"))
        assertEquals(150000L to "EGP", one("تم تحويل ١٬٥٠٠ جنيه"))
        assertEquals(4999L to "EGP", one("et5asam menha 49.99 geneh"))
    }

    @Test
    fun readsPoundsDollarsAndEuros() {
        assertEquals(99L to "USD", one("Total: $0.99"))
        assertEquals(999L to "GBP", one("You paid £9.99"))
        assertEquals(1250L to "EUR", one("€12.50 charged"))
        assertEquals(2000L to "GBP", one("20 جنيه إسترليني"))
        assertEquals(500L to "USD", one("US$ 5"))
    }

    @Test
    fun numbersThatAreNotMoneyAreIgnored() {
        for (t in listOf("Call 0100 123 4567", "رقم العملية: 88213345", "التاريخ: 05/06/2026", "Red 1000 plan",
                         "Your code 482913 expires", "SALE 50 today", "الرقم القومي: ٢٩٠٠١٠١٠١٢٣٤٥٦", "كود فوري: 9912-4455")) {
            assertEquals(emptyList(), MoneyReader.find(t), t)
        }
    }

    @Test
    fun theReceiptsAmountIsTheOneOnALabelledLine() {
        val text = "Subtotal EGP 150.00\nDelivery EGP 15.00\nTotal: EGP 165.00"
        assertEquals(16500L, MoneyReader.best(text)!!.minor)
        assertEquals(15000L, MoneyReader.best("Shoes EGP 150.00\nSocks EGP 15.00")!!.minor, "no label: the first amount")
        assertNull(MoneyReader.best("no amount here"))
    }

    @Test
    fun lineAtCutsTheLineVerbatim() {
        val text = "first\nالمبلغ: ٣٥٠ جنيه\nlast"
        val m = MoneyReader.find(text).single()
        assertEquals("المبلغ: ٣٥٠ جنيه", MoneyReader.lineAt(text, m.start))
        assertTrue(m.text.contains("٣٥٠"), "the match is the original text, digits as written")
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: the Kotlin test command with `--tests 'dev.loupe.kit.tracking.MoneyReaderTest'`
Expected: FAIL, `Unresolved reference: MoneyReader`.

- [ ] **Step 3: Write `MoneyReader`**

```kotlin
package dev.loupe.kit.tracking

import dev.loupe.sources.common.CsvRows

/** An amount written in text: minor units, its currency (ISO 4217), the matched text verbatim and where it starts. */
data class Money(val minor: Long, val currency: String, val text: String, val start: Int)

/**
 * Amounts with their currency (spec §7.2 "Money"): EGP / ج.م / جم / جنيه / LE / L.E. / E£ / geneh alongside £ $ €,
 * Arabic and Persian digits (through the fold), thousands separators in both scripts (`,` and `٬`) and both decimal
 * marks (`.` and `٫`). A number counts only next to a currency marker, so dates, phone numbers and reference codes
 * never read as money. Amounts keep their currency; nothing is converted.
 */
object MoneyReader {
    private const val NUM = "[0-9][0-9,٬]*(?:[.٫][0-9]{1,2})?"
    private const val BEFORE = "US\\$|E£|£|\\$|€|EGP|Egp|egp|USD|usd|EUR|eur|GBP|gbp|L\\.E\\.?|LE"
    private const val AFTER = "EGP|Egp|egp|USD|usd|EUR|eur|GBP|gbp|L\\.E\\.?|LE|" +
        "جنيه(?:ا|اً)?\\s+(?:إ|ا)سترليني|جنيهات|جنيه(?:ا|اً)?(?:\\s+مصري)?|ج\\.\\s?م\\.?|جم|" +
        "geneh|Geneh|gneh|genih|ginih|€|\\$|£"
    private val PREFIXED = Regex("(?<![A-Za-z])($BEFORE)\\s?($NUM)(?![0-9])")
    private val SUFFIXED = Regex("(?<![0-9.,٫٬])($NUM)\\s?($AFTER)(?![A-Za-zء-ي])")

    /** Lines that say what the amount is ("Total", "المبلغ", "إجمالي"): the receipt's amount is on one of them. */
    private val LABEL = TrackingText.words(listOf(
        "total", "amount", "paid", "charged", "billed", "debited",
        "المبلغ", "مبلغ", "الاجمالي", "اجمالي", "القيمة", "المدفوع", "mablagh",
    ))

    /** Every amount in [text], in order of appearance. */
    fun find(text: String): List<Money> {
        val t = TrackingText.foldDigits(text)
        val out = mutableListOf<Pair<Int, Money>>()
        for (m in PREFIXED.findAll(t)) {
            val minor = number(m.groupValues[2]) ?: continue
            val numberAt = m.range.first + m.value.indexOf(m.groupValues[2])
            out += numberAt to Money(minor, code(m.groupValues[1]), text.substring(m.range.first, m.range.last + 1), m.range.first)
        }
        for (m in SUFFIXED.findAll(t)) {
            val minor = number(m.groupValues[1]) ?: continue
            out += m.range.first to Money(minor, code(m.groupValues[2]), text.substring(m.range.first, m.range.last + 1), m.range.first)
        }
        return out.distinctBy { it.first }.map { it.second }.sortedBy { it.start }
    }

    /** The amount a receipt is about: the first on a labelled line, else the first at all; null without one. */
    fun best(text: String): Money? {
        val all = find(text)
        return all.firstOrNull { LABEL.containsMatchIn(TrackingText.matchForm(lineAt(text, it.start))) } ?: all.firstOrNull()
    }

    /** ISO 4217 for a written marker; the Egyptian ones (and a bare جنيه) are EGP. */
    fun code(token: String): String {
        val t = TrackingText.matchForm(token)
        return when {
            "سترليني" in t || t == "£" || t == "gbp" -> "GBP"
            t == "$" || t == "us$" || t == "usd" -> "USD"
            t == "€" || t == "eur" -> "EUR"
            else -> "EGP"
        }
    }

    /** The line of [text] that holds [index], verbatim, without its line break. */
    fun lineAt(text: String, index: Int): String {
        val start = if (index <= 0) 0 else text.lastIndexOf('\n', index - 1) + 1
        val end = text.indexOf('\n', index).let { if (it < 0) text.length else it }
        return text.substring(start, end)
    }

    private fun number(raw: String): Long? = CsvRows.parseNumber(raw.trimEnd(',', '٬', '.', '٫'))
}
```

- [ ] **Step 4: Run to verify it passes**

Run: the Kotlin test command with `--tests 'dev.loupe.kit.tracking.MoneyReaderTest'`
Expected: 5 tests PASS.

- [ ] **Step 5: Commit**

```bash
cd /Users/bistrocloud/Documents/Loupe/jevistication
git add loupe-kit/src/commonMain/kotlin/dev/loupe/kit/tracking/MoneyReader.kt loupe-kit/src/commonTest/kotlin/dev/loupe/kit/tracking/MoneyReaderTest.kt
git commit -m "kit: money in EGP, Arabic and English markers, Arabic digits and separators

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
git push
```

---

### Task 3: Charge words and Egyptian merchant hints

**Files:**
- Create: `loupe-kit/src/commonMain/kotlin/dev/loupe/kit/tracking/ChargeLexicon.kt`, `loupe-kit/src/commonMain/kotlin/dev/loupe/kit/tracking/MerchantHints.kt`
- Test: `loupe-kit/src/commonTest/kotlin/dev/loupe/kit/tracking/ChargeLexiconTest.kt`

**Interfaces:**
- Consumes: `TrackingText.words`, `TrackingText.matchForm`, `TrackingText.foldDigits`.
- Produces: `object ChargeLexicon { val CHARGE: Regex; val NOT_A_CHARGE: Regex; val SUBSCRIPTION: Regex }` (over match-form text); `data class MerchantHint(val canonical: String, val rail: Boolean, val pattern: Regex)`; `object MerchantHints { val ALL: List<MerchantHint>; fun merchant(text: String): String?; fun canonical(name: String): String; fun payee(text: String): String?; fun fromFirstLine(body: String): String? }`.

- [ ] **Step 1: Write the failing test**

```kotlin
package dev.loupe.kit.tracking

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChargeLexiconTest {
    private fun charge(t: String) = ChargeLexicon.CHARGE.containsMatchIn(TrackingText.matchForm(t))
    private fun notCharge(t: String) = ChargeLexicon.NOT_A_CHARGE.containsMatchIn(TrackingText.matchForm(t))
    private fun subscription(t: String) = ChargeLexicon.SUBSCRIPTION.containsMatchIn(TrackingText.matchForm(t))

    @Test
    fun chargeWordsInEnglishArabicEgyptianAndFranco() {
        for (t in listOf("We've charged your card", "Thank you for your payment", "Total amount paid", "This is a receipt for your payment",
                         "تم الدفع بنجاح", "تم خصم مبلغ ٩٩ جنيه", "تم سداد فاتورة", "تم تجديد اشتراكك", "تم تحويل ١٬٥٠٠ جنيه",
                         "اتخصم من الفيزا", "el visa et5asam menha", "dafa3t el fatoora")) {
            assertTrue(charge(t), t)
        }
        for (t in listOf("Subscribe now for just EGP 99", "Your bill is ready", "Please pay by 30/09", "اشترك الآن", "رقم مرجعي للدفع")) {
            assertFalse(charge(t), t)
        }
    }

    @Test
    fun notAChargeWords() {
        for (t in listOf("Your account was credited with EGP 15,000", "We have refunded $4.99", "Amount due: EGP 842",
                         "Subscribe now for just $9.99", "Flash sale: 30% off", "تم إيداع مبلغ", "طلب تحويل", "اشترك الآن في باقة",
                         "ادفع قبل الموعد", "momken te7wel 200 geneh", "eshtrek delwa2ty")) {
            assertTrue(notCharge(t), t)
        }
        assertFalse(notCharge("Amount paid: EGP 165.00"))
    }

    @Test
    fun subscriptionWords() {
        for (t in listOf("Monthly subscription", "Your membership", "اشتراك شهري", "باقة فليكس", "eshterak shahry", "fatoora el net")) {
            assertTrue(subscription(t), t)
        }
    }

    @Test
    fun knownMerchantsAndTheirSpellings() {
        assertEquals("Netflix", MerchantHints.merchant("Your Netflix payment receipt"))
        assertEquals("Netflix", MerchantHints.canonical("NETFLIX.COM"))
        assertEquals("Shahid", MerchantHints.canonical("SHAHID VIP"))
        assertEquals("Vodafone", MerchantHints.merchant("فاتورة فودافون"))
        assertEquals("Anghami", MerchantHints.merchant("le Anghami Plus"))
        assertEquals("Jumia Egypt", MerchantHints.canonical("Jumia Egypt"))
        assertNull(MerchantHints.merchant("Orange juice 25 EGP"), "orange the fruit is not Orange Egypt")
    }

    @Test
    fun weOnlyInCapitals() {
        assertEquals("WE", MerchantHints.merchant("WE\nتم دفع فاتورة الإنترنت الأرضي بنجاح"))
        assertNull(MerchantHints.merchant("we paid for dinner"))
        assertEquals("WE", MerchantHints.merchant("المصرية للاتصالات"))
    }

    @Test
    fun railsNameThePayeeOrTheService() {
        assertEquals("نادي الجزيرة", MerchantHints.merchant("InstaPay\nتم تحويل ١٬٥٠٠ جنيه\nالمستفيد: نادي الجزيرة"))
        assertEquals("Orange", MerchantHints.merchant("فوري\nإيصال سداد\nالخدمة: أورنج - فاتورة موبايل"))
        assertEquals("InstaPay", MerchantHints.merchant("InstaPay\nتم تحويل ٢٠٠ جنيه"))
    }

    @Test
    fun theFirstLineNamesAShopUnlessItIsGeneric() {
        assertEquals("Cafe Luna", MerchantHints.fromFirstLine("Cafe Luna\n2 flat whites £7.00\nPaid"))
        assertNull(MerchantHints.fromFirstLine("Receipt\nTotal £7.00"))
        assertNull(MerchantHints.fromFirstLine("إيصال\nالمبلغ ٥٠ جنيه"))
        assertNull(MerchantHints.fromFirstLine("0100 123 4567 88\nPaid"))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: the Kotlin test command with `--tests 'dev.loupe.kit.tracking.ChargeLexiconTest'`
Expected: FAIL, `Unresolved reference: ChargeLexicon`.

- [ ] **Step 3: Write the lexicon and the hints**

`ChargeLexicon.kt`:

```kotlin
package dev.loupe.kit.tracking

/**
 * Charge and subscription words (spec §7.2), over [TrackingText.matchForm] text: English, Arabic, Egyptian and
 * Franco. A charge needs a [CHARGE] word; a [NOT_A_CHARGE] word on the amount's line or the first line vetoes it.
 */
object ChargeLexicon {
    /** Something was paid, charged, deducted, renewed or transferred. */
    val CHARGE: Regex = TrackingText.words(listOf(
        "charged", "paid", "payment received", "payment successful", "payment confirmed", "payment confirmation",
        "receipt for", "has been renewed", "was renewed", "renewed", "billed", "debited", "deducted",
        "thank you for your payment", "your payment of", "purchase successful",
        "تم الدفع", "تم دفع", "تم سداد", "سداد فاتورة", "دفعت", "تم خصم", "خصم مبلغ", "اتخصم", "تم تحصيل", "تم تجديد",
        "اتجدد", "تم تحويل", "عملية ناجحة", "إيصال دفع", "إيصال سداد", "المبلغ المدفوع", "مدفوع", "مدفوعة",
        "et5asam", "etkhasam", "it5asam", "et5sam", "dafa3t", "dafa3na", "tam el daf3", "tam daf3", "etdafa3", "etgadad",
    ))

    /** Money that came in, came back, is still due, is only asked for, or is advertised. */
    val NOT_A_CHARGE: Regex = TrackingText.words(listOf(
        "refund", "refunded", "credited", "deposit", "deposited", "request to pay", "payment request", "amount due",
        "due date", "pay now", "subscribe now", "sign up", "offer", "re:[0-9]+% off",
        "استرداد", "مسترد", "تم إيداع", "إيداع", "طلب تحويل", "طلب دفع", "مستحق", "ادفع", "اشترك الآن", "اشترك دلوقتي",
        "عرض", "momken", "3ayez", "e3mel eshterak", "eshtrek delwa2ty",
    ))

    /** The text names a subscription or a recurring bill (kept as evidence; a cadence still needs three charges). */
    val SUBSCRIPTION: Regex = TrackingText.words(listOf(
        "subscription", "membership", "monthly plan", "renewal", "auto-renew", "plan",
        "اشتراك", "باقة", "تجديد", "شهري", "شهرية", "عضوية", "فاتورة",
        "eshterak", "eshtrak", "ba2a", "shahry", "fatoora", "tagdeed",
    ))
}
```

`MerchantHints.kt`:

```kotlin
package dev.loupe.kit.tracking

/** A known merchant (or a payment rail, whose merchant is on the payee or service line) and how it is written. */
data class MerchantHint(val canonical: String, val rail: Boolean, val pattern: Regex)

/**
 * The merchants people in Egypt pay most (spec §7.2), with their English, Arabic and colloquial spellings, and the
 * payment rails (InstaPay, Fawry) whose receipts name the real merchant on a payee or service line.
 */
object MerchantHints {
    private fun hint(canonical: String, vararg words: String, rail: Boolean = false) =
        MerchantHint(canonical, rail, TrackingText.words(words.toList()))

    val ALL: List<MerchantHint> = listOf(
        hint("Netflix", "netflix", "نتفليكس"),
        hint("Spotify", "spotify", "سبوتيفاي"),
        hint("Anghami", "anghami", "أنغامي", "انغامي"),
        hint("Shahid", "shahid", "re:شاهد\\s+vip"),
        hint("WATCH IT", "watch it", "watchit", "واتش إت"),
        hint("OSN+", "osn+", "osn plus"),
        hint("YouTube Premium", "youtube premium"),
        hint("iCloud", "icloud"),
        hint("Google One", "google one"),
        hint("Amazon Prime", "amazon prime", "prime video"),
        hint("WE", "telecom egypt", "we internet", "we home", "my we", "المصرية للاتصالات", "وي انترنت", "ماي وي"),
        hint("Vodafone", "vodafone", "فودافون"),
        hint("Orange", "orange egypt", "orange money", "orange mobile", "orange internet", "أورنج", "اورانج"),
        hint("e&", "etisalat", "e& egypt", "اتصالات مصر"),
        hint("InstaPay", "instapay", "انستاباي", "إنستاباي", rail = true),
        hint("Fawry", "fawry", "فوري", rail = true),
    )

    /** WE writes its name in capitals; "we" in a sentence is not the company. Matched on the folded original. */
    private val WE_CAPS = Regex("(?<![A-Za-z])WE(?![A-Za-z])")

    /** "Beneficiary: …", "المستفيد: …", "الخدمة: …" (over the match form). */
    private val PAYEE = Regex(
        "^\\s*(?:beneficiary|payee|recipient|paid to|transfer to|to|merchant|biller|service|" +
            "المستفيد|اسم المستفيد|الي|التاجر|الخدمه|الجهه|المفوتر)\\s*[:：]\\s*(.+)$",
    )

    private val GENERIC = TrackingText.words(listOf(
        "receipt", "invoice", "tax invoice", "payment receipt", "order confirmation", "thank you", "photo", "screenshot",
        "إيصال", "فاتورة", "شكرا",
    ))

    /** The merchant a text is about: a rail's payee or service, else the first known brand, else the rail itself. */
    fun merchant(text: String): String? {
        val form = TrackingText.matchForm(text)
        val rail = ALL.filter { it.rail }.mapNotNull { h -> h.pattern.find(form)?.let { it.range.first to h.canonical } }.minByOrNull { it.first }
        if (rail != null) payee(text)?.let { return canonical(it) }
        return firstBrand(text, form) ?: rail?.second
    }

    /** A written merchant name brought to its canonical form when it is a known brand ("NETFLIX.COM" → Netflix). */
    fun canonical(name: String): String {
        val t = name.trim()
        return firstBrand(t, TrackingText.matchForm(t)) ?: t
    }

    /** The value of the first payee or service line, verbatim, up to " - " ("أورنج - فاتورة موبايل" → "أورنج"). */
    fun payee(text: String): String? {
        for (line in text.lines()) {
            val m = PAYEE.find(TrackingText.matchForm(line)) ?: continue
            val start = m.range.last + 1 - m.groupValues[1].length
            val value = line.substring(start).trim().substringBefore(" - ").trim()
            if (value.isNotEmpty()) return value
        }
        return null
    }

    /** A shop's name on a receipt's first line (2-40 characters, a letter, at most 4 digits, not a generic word). */
    fun fromFirstLine(body: String): String? {
        val line = body.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
        if (line.length !in 2..40 || line.none { it.isLetter() } || line.count { it.isDigit() } > 4) return null
        if (GENERIC.containsMatchIn(TrackingText.matchForm(line))) return null
        return canonical(line)
    }

    private fun firstBrand(text: String, form: String): String? {
        val hits = ALL.filter { !it.rail }.mapNotNull { h -> h.pattern.find(form)?.let { it.range.first to h.canonical } }.toMutableList()
        WE_CAPS.find(TrackingText.foldDigits(text))?.let { hits += it.range.first to "WE" }
        return hits.minByOrNull { it.first }?.second
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: the Kotlin test command with `--tests 'dev.loupe.kit.tracking.ChargeLexiconTest'`
Expected: 7 tests PASS.

- [ ] **Step 5: Commit**

```bash
cd /Users/bistrocloud/Documents/Loupe/jevistication
git add loupe-kit/src/commonMain/kotlin/dev/loupe/kit/tracking/ChargeLexicon.kt loupe-kit/src/commonMain/kotlin/dev/loupe/kit/tracking/MerchantHints.kt loupe-kit/src/commonTest/kotlin/dev/loupe/kit/tracking/ChargeLexiconTest.kt
git commit -m "kit: charge and subscription words (EN, AR, Egyptian, Franco) and Egyptian merchant hints

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
git push
```

---

### Task 4: Expiry words, Egyptian document kinds and dates

**Files:**
- Create: `loupe-kit/src/commonMain/kotlin/dev/loupe/kit/tracking/ExpiryLexicon.kt`, `loupe-kit/src/commonMain/kotlin/dev/loupe/kit/tracking/TrackingDates.kt`
- Test: `loupe-kit/src/commonTest/kotlin/dev/loupe/kit/tracking/ExpiryLexiconTest.kt`

**Interfaces:**
- Consumes: `TrackingText`, `DateFacts.find`, `DateMatch`.
- Produces: `object ExpiryLexicon { val EXPIRY: Regex; val PROMO: Regex }`; `enum class DocumentKind(val id: String, val title: String)` with `CAR_LICENCE, DRIVING_LICENCE, NATIONAL_ID, PASSPORT, RESIDENCE, INSURANCE, CONTRACT, MEMBERSHIP, WARRANTY`; `object DocumentKinds { fun of(text: String): DocumentKind? }`; `object TrackingDates { fun find(text: String): List<DateMatch> }` (patterns `"ymd-slash"`, `"arabic-month"` besides `DateFacts`' own).

- [ ] **Step 1: Write the failing test**

```kotlin
package dev.loupe.kit.tracking

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExpiryLexiconTest {
    private fun expiry(t: String) = ExpiryLexicon.EXPIRY.containsMatchIn(TrackingText.matchForm(t))
    private fun promo(t: String) = ExpiryLexicon.PROMO.containsMatchIn(TrackingText.matchForm(t))

    @Test
    fun expiryWordsInEnglishArabicAndEgyptian() {
        for (t in listOf("Date of expiry 14 JAN 2031", "Valid until 15/05/2026", "This policy expires on 30/11/2026",
                         "4b. 12.03.2031", "البطاقة سارية حتى ٢٠٢٨/٠٣/١٤", "تاريخ الانتهاء: ١٢/١١/٢٠٢٦", "صالحة لغاية ٠٥/٠٢/٢٠٢٩",
                         "وينتهي العقد في ٣٠ يونيو ٢٠٢٧", "رخصة العربية بتخلص يوم 2026/12/01", "صالح لحد ٢٠٢٦/١٠/٠٥")) {
            assertTrue(expiry(t), t)
        }
        for (t in listOf("An Egyptian passport is valid for seven years", "You can renew it at any office", "يجب تجديد البطاقة")) {
            assertFalse(expiry(t), t)
        }
    }

    @Test
    fun promotionWords() {
        for (t in listOf("This offer expires 30/10/2026", "Flash sale: 30% off", "العرض ينتهي في", "تخفيضات نهاية الموسم")) {
            assertTrue(promo(t), t)
        }
        assertFalse(promo("This policy expires on 30/11/2026"))
    }

    @Test
    fun egyptianDocumentKinds() {
        assertEquals(DocumentKind.CAR_LICENCE, DocumentKinds.of("رخصة تسيير ملاكي"))
        assertEquals(DocumentKind.CAR_LICENCE, DocumentKinds.of("رخصة العربية بتخلص يوم"))
        assertEquals(DocumentKind.DRIVING_LICENCE, DocumentKinds.of("رخصة قيادة خاصة"))
        assertEquals(DocumentKind.DRIVING_LICENCE, DocumentKinds.of("Driving licence (scanned copy)"))
        assertEquals(DocumentKind.NATIONAL_ID, DocumentKinds.of("بطاقة تحقيق الشخصية"))
        assertEquals(DocumentKind.NATIONAL_ID, DocumentKinds.of("الرقم القومي: ٢٩٠٠١٠١٠١٢٣٤٥٦"))
        assertEquals(DocumentKind.PASSPORT, DocumentKinds.of("PASSPORT Type P"))
        assertEquals(DocumentKind.PASSPORT, DocumentKinds.of("جواز سفر"))
        assertEquals(DocumentKind.INSURANCE, DocumentKinds.of("Motor Insurance Policy"))
        assertEquals(DocumentKind.INSURANCE, DocumentKinds.of("وثيقة تأمين طبي"))
        assertEquals(DocumentKind.CONTRACT, DocumentKinds.of("عقد إيجار شقة سكنية"))
        assertEquals(DocumentKind.MEMBERSHIP, DocumentKinds.of("كارنيه عضوية"))
        assertEquals(DocumentKind.RESIDENCE, DocumentKinds.of("Residence permit"))
        assertNull(DocumentKinds.of("Read our privacy policy"))
    }

    @Test
    fun datesInArabicDigitsYearFirstAndArabicMonths() {
        val ymd = TrackingDates.find("البطاقة سارية حتى ٢٠٢٨/٠٣/١٤").single()
        assertEquals(LocalDate(2028, 3, 14), ymd.date)
        assertEquals("ymd-slash", ymd.pattern)
        assertEquals(LocalDate(2027, 6, 30), TrackingDates.find("ينتهي العقد في ٣٠ يونيو ٢٠٢٧").single().date)
        assertEquals(LocalDate(2031, 1, 14), TrackingDates.find("Date of expiry 14 JAN 2031").single().date)
        val dmy = TrackingDates.find("تاريخ الانتهاء: ١٢/١١/٢٠٢٦").single()
        assertEquals(LocalDate(2026, 11, 12), dmy.date)
        assertTrue(dmy.ambiguous)
        assertEquals(listOf(LocalDate(2021, 3, 15), LocalDate(2028, 3, 14)),
                     TrackingDates.find("Issued 2021/03/15, expires 2028/03/14").map { it.date })
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: the Kotlin test command with `--tests 'dev.loupe.kit.tracking.ExpiryLexiconTest'`
Expected: FAIL, `Unresolved reference: ExpiryLexicon`.

- [ ] **Step 3: Write the lexicon, the kinds and the dates**

`ExpiryLexicon.kt`:

```kotlin
package dev.loupe.kit.tracking

/**
 * Expiry words (spec §7.2), over [TrackingText.matchForm] text: English, Arabic and Egyptian (تنتهي، تاريخ الانتهاء،
 * صالحة حتى، صالح لغاية، الصلاحية، سارية حتى, and the colloquial بتخلص / لحد). [PROMO] marks an offer, whose
 * "expires" is not a document's.
 */
object ExpiryLexicon {
    val EXPIRY: Regex = TrackingText.words(listOf(
        "re:expir[a-z]*", "valid until", "valid till", "valid to", "valid thru", "valid through", "renewal date", "renew by", "re:4b\\.",
        "تنتهي", "ينتهي", "تاريخ الانتهاء", "انتهاء الصلاحية", "الصلاحية", "صالحة حتى", "صالح حتى", "صالحة لغاية", "صالح لغاية",
        "سارية حتى", "ساري حتى", "سارية لغاية", "صالحة لحد", "صالح لحد", "تاريخ التجديد", "ميعاد التجديد", "بتخلص", "هتخلص",
    ))

    val PROMO: Regex = TrackingText.words(listOf(
        "offer", "sale", "discount", "coupon", "promo", "promo code", "voucher", "deal", "re:[0-9]+% off",
        "عرض", "عروض", "كوبون", "تخفيض", "تخفيضات", "خصم",
    ))
}

/** What a dated document is, decided by rules from its words (the model's own judgment is the radar's model half). */
enum class DocumentKind(val id: String, val title: String) {
    CAR_LICENCE("car_licence", "car licence"),
    DRIVING_LICENCE("driving_licence", "driving licence"),
    NATIONAL_ID("national_id", "national ID"),
    PASSPORT("passport", "passport"),
    RESIDENCE("residence", "residence permit"),
    INSURANCE("insurance", "insurance policy"),
    CONTRACT("contract", "contract"),
    MEMBERSHIP("membership", "membership"),
    WARRANTY("warranty", "warranty"),
}

/** Egyptian and English document kinds, the most specific first (a car licence before a driving licence). */
object DocumentKinds {
    private val RULES: List<Pair<DocumentKind, Regex>> = listOf(
        DocumentKind.CAR_LICENCE to TrackingText.words(listOf("vehicle licence", "vehicle license", "car licence", "car license",
            "رخصة تسيير", "رخصة سيارة", "رخصة العربية", "رخصة مركبة")),
        DocumentKind.DRIVING_LICENCE to TrackingText.words(listOf("driving licence", "driving license", "driver's license", "driver license",
            "رخصة قيادة", "رخصة السواقة")),
        DocumentKind.NATIONAL_ID to TrackingText.words(listOf("national id", "national identity", "id card", "identity card",
            "بطاقة الرقم القومي", "الرقم القومي", "بطاقة تحقيق الشخصية", "تحقيق الشخصية", "البطاقة الشخصية")),
        DocumentKind.PASSPORT to TrackingText.words(listOf("passport", "جواز سفر", "جواز السفر", "باسبور")),
        DocumentKind.RESIDENCE to TrackingText.words(listOf("residence permit", "إقامة")),
        DocumentKind.INSURANCE to TrackingText.words(listOf("insurance", "وثيقة تأمين", "تأمين", "بوليصة")),
        DocumentKind.CONTRACT to TrackingText.words(listOf("contract", "lease", "tenancy", "agreement", "عقد")),
        DocumentKind.MEMBERSHIP to TrackingText.words(listOf("membership", "member card", "عضوية", "كارنيه")),
        DocumentKind.WARRANTY to TrackingText.words(listOf("warranty", "guarantee", "ضمان")),
    )

    fun of(text: String): DocumentKind? {
        val form = TrackingText.matchForm(text)
        return RULES.firstOrNull { it.second.containsMatchIn(form) }?.first
    }
}
```

`TrackingDates.kt`:

```kotlin
package dev.loupe.kit.tracking

import dev.loupe.engine.DateFacts
import dev.loupe.engine.DateMatch
import kotlinx.datetime.LocalDate

/**
 * Dates for the trackers: `DateFacts.find` over digit-folded text (so ٢٠٢٦ reads), plus the two forms Egyptian
 * documents use that `DateFacts` does not: year first with slashes or dots (٢٠٢٨/٠٣/١٤) and Arabic month names
 * (٣٠ يونيو ٢٠٢٧). Ambiguous numeric dates keep `DateFacts`' reading and alternate.
 */
object TrackingDates {
    private val YMD = Regex("(?<![0-9])([0-9]{4})[/.]([0-9]{1,2})[/.]([0-9]{1,2})(?![0-9])")
    private val AR_MONTHS: Map<String, Int> = mapOf(
        "يناير" to 1, "فبراير" to 2, "مارس" to 3, "ابريل" to 4, "مايو" to 5, "يونيو" to 6, "يونيه" to 6,
        "يوليو" to 7, "يوليه" to 7, "اغسطس" to 8, "سبتمبر" to 9, "اكتوبر" to 10, "نوفمبر" to 11, "ديسمبر" to 12,
    )
    private val AR_DMY = Regex("(?<![0-9])([0-9]{1,2})\\s+(${AR_MONTHS.keys.joinToString("|")})\\s+([0-9]{4})(?![0-9])")

    /** Every date in [text], in order of appearance, each date once. */
    fun find(text: String): List<DateMatch> {
        val folded = TrackingText.foldDigits(text)
        val form = TrackingText.matchForm(text)
        val found = mutableListOf<Pair<Int, DateMatch>>()
        for (m in DateFacts.find(folded)) found += folded.indexOf(m.text).coerceAtLeast(0) to m
        for (m in YMD.findAll(folded)) {
            val date = date(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) ?: continue
            found += m.range.first to DateMatch(text.substring(m.range.first, m.range.last + 1), date, "ymd-slash", ambiguous = false)
        }
        for (m in AR_DMY.findAll(form)) {
            val month = AR_MONTHS[m.groupValues[2]] ?: continue
            val date = date(m.groupValues[3].toInt(), month, m.groupValues[1].toInt()) ?: continue
            found += m.range.first to DateMatch(text.substring(m.range.first, m.range.last + 1), date, "arabic-month", ambiguous = false)
        }
        return found.sortedBy { it.first }.map { it.second }.distinctBy { it.date to it.alternate }
    }

    private fun date(y: Int, m: Int, d: Int): LocalDate? = runCatching { LocalDate(y, m, d) }.getOrNull()
}
```

- [ ] **Step 4: Run to verify it passes**

Run: the Kotlin test command with `--tests 'dev.loupe.kit.tracking.ExpiryLexiconTest'`
Expected: 4 tests PASS.

- [ ] **Step 5: Commit**

```bash
cd /Users/bistrocloud/Documents/Loupe/jevistication
git add loupe-kit/src/commonMain/kotlin/dev/loupe/kit/tracking/ExpiryLexicon.kt loupe-kit/src/commonMain/kotlin/dev/loupe/kit/tracking/TrackingDates.kt loupe-kit/src/commonTest/kotlin/dev/loupe/kit/tracking/ExpiryLexiconTest.kt
git commit -m "kit: expiry words (EN, AR, Egyptian), Egyptian document kinds, year-first and Arabic-month dates

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
git push
```

---

### Task 5: Charges from every source, counted once

**Files:**
- Create: `loupe-kit/src/commonMain/kotlin/dev/loupe/kit/tracking/ChargeExtractor.kt`
- Create (test helper): `loupe-kit/src/commonTest/kotlin/dev/loupe/kit/tracking/TrackingItems.kt`
- Test: `loupe-kit/src/commonTest/kotlin/dev/loupe/kit/tracking/ChargeExtractorTest.kt`

**Interfaces:**
- Consumes: `MoneyReader`, `ChargeLexicon`, `MerchantHints`, `TrackingDates`, `TrackingText`, `CsvRows.read`, `CsvRows.formatMinor`, `Charge`.
- Produces: `data class TrackedCharge(itemId: String, kind: ItemKind, merchant: String, date: LocalDate, amountMinor: Long, currency: String, line: String, saysSubscription: Boolean, alsoSeenIn: List<String> = emptyList()) { fun toCharge(): Charge }`; `object ChargeExtractor { const val SAME_CHARGE_DAYS = 3; fun extract(items: List<SourceItem>): List<TrackedCharge>; fun of(item: SourceItem): List<TrackedCharge>; fun dedupe(charges: List<TrackedCharge>): List<TrackedCharge> }`; test helper `internal object TrackingItems { email, image, file, event, csvRow }` (signatures below; Tasks 6-9 use them).

- [ ] **Step 1: Write the item builders and the failing test**

`TrackingItems.kt`:

```kotlin
package dev.loupe.kit.tracking

import dev.loupe.engine.ContentHash
import dev.loupe.sources.common.CsvRows
import dev.loupe.sources.common.DateOrigin
import dev.loupe.sources.common.EmailFacts
import dev.loupe.sources.common.ItemKind
import dev.loupe.sources.common.PhoneItems
import dev.loupe.sources.common.SourceItem
import kotlinx.datetime.LocalDate

/** Source items as the phone's sources produce them, for the tracking tests. */
internal object TrackingItems {
    /** An email: headers, a blank line, the body (the shape `WatcherRun` reads). [from] is `Name <address>`. */
    fun email(id: String, date: String, from: String, subject: String, body: String): SourceItem {
        val name = from.substringBefore('<').trim().ifEmpty { null }
        val address = from.substringAfter('<', "").substringBefore('>').ifEmpty { from.trim() }
        val d = LocalDate.parse(date)
        val text = "From: $from\nSubject: $subject\nDate: $date\n\n$body"
        return SourceItem(
            id = "mail:$id", sourceId = "mail", kind = ItemKind.EMAIL, path = "mail/$id.eml", messageIndex = null, name = subject,
            text = text, hasText = true, textTruncated = false, sizeBytes = text.length.toLong(), contentHash = ContentHash.of(text),
            mime = "message/rfc822", date = d, dateOrigin = DateOrigin.EMAIL_HEADER,
            email = EmailFacts(name, address, emptyList(), subject, d, emptyList(), emptyList()), facts = emptyMap(),
        )
    }

    /** A photo or screenshot with its on-device OCR text (the Photos source's own builder). */
    fun image(id: String, date: String, ocr: String): SourceItem =
        PhoneItems().photo(localId = id, name = "$id.jpg", ocrText = ocr, createdIso = date, takenIso = date, dimensions = null,
                           camera = null, hasLocation = false, screenshot = true, sizeBytes = ocr.length.toLong())

    /** A file (PDF, text, HTML) with the text its extractor read. */
    fun file(id: String, date: String, kind: ItemKind, text: String): SourceItem = SourceItem(
        id = "files:$id", sourceId = "files", kind = kind, path = "files/$id", messageIndex = null, name = id, text = text,
        hasText = true, textTruncated = false, sizeBytes = text.length.toLong(), contentHash = ContentHash.of(text),
        mime = when (kind) { ItemKind.PDF -> "application/pdf"; ItemKind.HTML -> "text/html"; else -> "text/plain" },
        date = LocalDate.parse(date), dateOrigin = DateOrigin.FILE_MODIFIED, email = null, facts = emptyMap(),
    )

    /** An all-day calendar event with notes (the Calendar source's own builder). */
    fun event(id: String, date: String, title: String, notes: String): SourceItem =
        PhoneItems().event(eventId = id, title = title, startIso = date, endIso = null, allDay = true, location = null,
                           calendar = "Personal", organizer = null, attendees = emptyList(), recurrence = null, notes = notes)

    /** One statement row as the Inbox imports it (statement facts read by CsvRows at import). */
    fun csvRow(id: String, date: String, merchant: String, minor: Long, currency: String, direction: String? = "debit"): SourceItem {
        val facts = linkedMapOf("row" to "1", "file" to "statement.csv", "amount_minor" to minor.toString(),
                                "amount" to CsvRows.formatMinor(minor) + " " + currency, "currency" to currency, "merchant" to merchant)
        if (direction != null) facts["direction"] = direction
        val text = "$date, $merchant, ${CsvRows.formatMinor(minor)} $currency"
        return SourceItem(
            id = "inbox:$id", sourceId = "inbox", kind = ItemKind.CSV, path = "inbox/statement.csv#$id", messageIndex = null, name = merchant,
            text = text, hasText = true, textTruncated = false, sizeBytes = text.length.toLong(), contentHash = ContentHash.of(text),
            mime = "text/csv", date = LocalDate.parse(date), dateOrigin = DateOrigin.CSV_COLUMN, email = null, facts = facts,
        )
    }
}
```

`ChargeExtractorTest.kt`:

```kotlin
package dev.loupe.kit.tracking

import dev.loupe.sources.common.ItemKind
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChargeExtractorTest {
    private val netflixMail = TrackingItems.email("n1", "2026-06-03", "Netflix <info@mailer.netflix.com>", "Your Netflix payment receipt",
        "Thanks for your payment. We've charged your card.\n\nAmount paid: EGP 165.00\nYour membership will renew on 3 July 2026.")

    @Test
    fun anEmailReceiptIsACharge() {
        val c = ChargeExtractor.of(netflixMail).single()
        assertEquals("Netflix", c.merchant)
        assertEquals(16500L, c.amountMinor)
        assertEquals("EGP", c.currency)
        assertEquals(LocalDate(2026, 6, 3), c.date)
        assertEquals("Amount paid: EGP 165.00", c.line)
        assertTrue(c.saysSubscription)
    }

    @Test
    fun photoReceiptsNameTheirMerchant() {
        val insta = TrackingItems.image("i1", "2026-06-01",
            "InstaPay\nتمت العملية بنجاح\nتم تحويل ١٬٥٠٠ جنيه\nالمستفيد: نادي الجزيرة\nالتاريخ: ٢٠٢٦/٠٦/٠١")
        val c = ChargeExtractor.of(insta).single()
        assertEquals("نادي الجزيرة", c.merchant)
        assertEquals(150000L, c.amountMinor)
        assertEquals(LocalDate(2026, 6, 1), c.date)
        val fawry = TrackingItems.image("f1", "2026-06-15",
            "فوري\nإيصال سداد\nالخدمة: أورنج - فاتورة موبايل\nإجمالي المبلغ: ٢٢٠ جم\nتم الدفع بنجاح يوم ١٥/٠٦/٢٠٢٦")
        assertEquals("Orange", ChargeExtractor.of(fawry).single().merchant)
        assertEquals(LocalDate(2026, 6, 15), ChargeExtractor.of(fawry).single().date)
    }

    @Test
    fun aPdfBillIsDatedByItsPaidLine() {
        val bill = TrackingItems.file("v1.pdf", "2026-06-21", ItemKind.PDF,
            "Vodafone Egypt\nBill period: 20/05/2026 - 19/06/2026\n\nTotal amount paid: L.E. 1,250.00\nPaid on 20/06/2026 by Vodafone Cash")
        val c = ChargeExtractor.of(bill).single()
        assertEquals("Vodafone", c.merchant)
        assertEquals(125000L, c.amountMinor)
        assertEquals(LocalDate(2026, 6, 20), c.date)
    }

    @Test
    fun aCalendarEventWithAnAmountIsACharge() {
        val gym = TrackingItems.event("g1", "2026-06-01", "Gym membership", "Paid the monthly membership: EGP 800")
        val c = ChargeExtractor.of(gym).single()
        assertEquals("Gym membership", c.merchant)
        assertEquals(80000L, c.amountMinor)
        assertEquals(LocalDate(2026, 6, 1), c.date)
    }

    @Test
    fun statementRowsAreChargesAndCreditsAreNot() {
        val shahid = ChargeExtractor.of(TrackingItems.csvRow("r1", "2026-06-10", "SHAHID VIP", 9999, "EGP")).single()
        assertEquals("Shahid", shahid.merchant)
        assertEquals(emptyList(), ChargeExtractor.of(TrackingItems.csvRow("r2", "2026-07-01", "SALARY TRANSFER", 1500000, "EGP", "credit")))
    }

    @Test
    fun theSameChargeInMailAndStatementIsOneWithBothReferences() {
        val row = TrackingItems.csvRow("r3", "2026-06-04", "NETFLIX.COM", 16500, "EGP")
        val all = ChargeExtractor.extract(listOf(row, netflixMail))
        val one = all.single()
        assertEquals(ItemKind.EMAIL, one.kind, "the receipt is kept; the statement row is a second reference")
        assertEquals(listOf(row.id), one.alsoSeenIn)
    }

    @Test
    fun twoMonthlyChargesStayTwo() {
        val july = TrackingItems.email("n2", "2026-07-03", "Netflix <info@mailer.netflix.com>", "Your Netflix payment receipt",
            "We've charged your card.\n\nAmount paid: EGP 165.00")
        assertEquals(2, ChargeExtractor.extract(listOf(netflixMail, july)).size)
    }

    @Test
    fun twoRowsOfOneStatementAreNotMerged() {
        val file = TrackingItems.file("talabat.csv", "2026-06-02", ItemKind.CSV,
            "talabat.csv\n\nDate,Merchant,Amount\n2026-06-01,Talabat,-120.00\n2026-06-01,Talabat,-120.00\n")
        assertEquals(2, ChargeExtractor.extract(listOf(file)).size)
    }

    @Test
    fun promotionsCreditsAndRefundsAreNotCharges() {
        val items = listOf(
            TrackingItems.email("p1", "2026-06-10", "Vodafone <offers@vodafone.com.eg>", "عرض خاص لك",
                "اشترك الآن في باقة فليكس ٧٠ بـ ١٢٠ جنيه شهرياً واستمتع بدقائق وإنترنت أكتر."),
            TrackingItems.email("p2", "2026-06-11", "StreamPlus <news@streamplus.example>", "Watch everything",
                "Subscribe now for just $9.99/month and watch everything."),
            TrackingItems.email("p3", "2026-09-01", "CIB <alerts@cibeg.example>", "Account credited",
                "Your account ending 4411 was credited with EGP 15,000.00 on 01/09/2026. Salary transfer."),
            TrackingItems.email("p4", "2026-08-02", "Uber <receipts@uber.example>", "Refund processed",
                "We have refunded $4.99 to your card for trip 8812."),
            TrackingItems.file("fawry-pending", "2026-09-20", ItemKind.TEXT,
                "فوري\nرقم مرجعي للدفع: 7788123\nالمبلغ المطلوب: ١٥٠ جنيه\nادفع قبل ٢٠٢٦/١٠/٠٥ من أي منفذ فوري"),
        )
        assertEquals(emptyList(), ChargeExtractor.extract(items))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: the Kotlin test command with `--tests 'dev.loupe.kit.tracking.ChargeExtractorTest'`
Expected: FAIL, `Unresolved reference: ChargeExtractor`.

- [ ] **Step 3: Write `ChargeExtractor`**

```kotlin
package dev.loupe.kit.tracking

import dev.loupe.engine.Charge
import dev.loupe.sources.common.CsvRows
import dev.loupe.sources.common.ItemKind
import dev.loupe.sources.common.SourceItem
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlin.math.abs

/** One charge, with where it was read: the item, the line, and any other item the same charge was seen in. */
data class TrackedCharge(
    val itemId: String,
    val kind: ItemKind,
    val merchant: String,
    val date: LocalDate,
    val amountMinor: Long,
    /** ISO 4217, or "" when the text named no currency. */
    val currency: String,
    /** The line the amount was read from, verbatim (the reference's "Read"). */
    val line: String,
    /** The text named a subscription or a recurring bill. */
    val saysSubscription: Boolean,
    /** Other items the same charge was seen in (a receipt email and its statement row). */
    val alsoSeenIn: List<String> = emptyList(),
) {
    fun toCharge(): Charge = Charge(merchant, date, amountMinor)
}

/**
 * Charges from every source (spec §7.2): email receipts, files (PDF, photo receipts, HTML, text), statement rows
 * (the Inbox's CSV rows, and whole CSV files read by `CsvRows`) and calendar events with an amount. Mechanical:
 * a charge needs an amount with its currency marker and a charge word, and no "not a charge" word on the amount's
 * line or the first line. The same charge seen in two items is kept once, with both references.
 */
object ChargeExtractor {
    /** Two sightings are one charge when merchant, currency and amount match and the dates are at most this far apart. */
    const val SAME_CHARGE_DAYS: Int = 3

    /** Which sighting is kept when a charge is seen twice: the richest evidence first. */
    private val PRIORITY = listOf(ItemKind.EMAIL, ItemKind.PDF, ItemKind.IMAGE, ItemKind.HTML, ItemKind.TEXT, ItemKind.MARKDOWN,
                                  ItemKind.JSON, ItemKind.EVENT, ItemKind.CSV)

    fun extract(items: List<SourceItem>): List<TrackedCharge> = dedupe(items.flatMap { of(it) })

    fun of(item: SourceItem): List<TrackedCharge> {
        if (item.duplicateOf != null) return emptyList()
        return when (item.kind) {
            ItemKind.CSV -> csv(item)
            ItemKind.CONTACT -> emptyList()
            ItemKind.EMAIL -> if (item.hasText) listOfNotNull(email(item)) else emptyList()
            else -> if (item.hasText) listOfNotNull(document(item)) else emptyList()
        }
    }

    private fun email(item: SourceItem): TrackedCharge? {
        val email = item.email ?: return null
        val body = item.text.substringAfter("\n\n", item.text)
        val read = email.subject.orEmpty() + "\n" + body
        val money = MoneyReader.best(body) ?: return null
        if (!isCharge(read, body, money)) return null
        val date = email.date ?: item.date ?: return null
        val merchant = MerchantHints.merchant(read)
            ?: email.fromName?.let { MerchantHints.canonical(it) }
            ?: email.fromAddress?.substringAfter('@')
            ?: return null
        return TrackedCharge(item.id, item.kind, merchant, date, money.minor, money.currency,
                             MoneyReader.lineAt(body, money.start).trim(), says(read))
    }

    private fun document(item: SourceItem): TrackedCharge? {
        val body = if (item.kind == ItemKind.IMAGE) item.text.substringAfter("\n\n", item.text) else item.text
        val money = MoneyReader.best(body) ?: return null
        if (!isCharge(body, body, money)) return null
        val date = (if (item.kind == ItemKind.EVENT) item.date else chargeDate(body, money)) ?: item.date ?: return null
        val merchant = MerchantHints.merchant(body)
            ?: (if (item.kind == ItemKind.EVENT) item.name.takeIf { it.isNotBlank() } else null)
            ?: MerchantHints.fromFirstLine(body)
            ?: return null
        return TrackedCharge(item.id, item.kind, merchant, date, money.minor, money.currency,
                             MoneyReader.lineAt(body, money.start).trim(), says(body))
    }

    private fun csv(item: SourceItem): List<TrackedCharge> {
        val minor = item.facts["amount_minor"]?.toLongOrNull()
        if (minor != null) {
            if (item.facts["direction"] == "credit" || minor == 0L) return emptyList()
            val raw = item.facts["merchant"] ?: item.facts["description"] ?: return emptyList()
            val date = item.date ?: return emptyList()
            val amount = item.facts["amount"] ?: CsvRows.formatMinor(minor)
            return listOf(TrackedCharge(item.id, item.kind, MerchantHints.canonical(raw), date, abs(minor),
                                        item.facts["currency"] ?: "", "$raw · $amount", false))
        }
        // A whole CSV file (a statement in Files): each debit row, all from this one item.
        val table = CsvRows.read(item.text.substringAfter("\n\n", item.text))
        return table.rows.mapNotNull { r ->
            val m = r.money ?: return@mapNotNull null
            if (m.direction == "credit" || m.amountMinor == 0L) return@mapNotNull null
            val raw = m.merchant ?: m.description ?: return@mapNotNull null
            TrackedCharge(item.id, item.kind, MerchantHints.canonical(raw), m.date, abs(m.amountMinor), m.currency ?: "",
                          r.cells.joinToString(", "), false)
        }
    }

    /** A charge word, and no "not a charge" word on the amount's line or the first line. */
    private fun isCharge(read: String, text: String, money: Money): Boolean {
        if (!ChargeLexicon.CHARGE.containsMatchIn(TrackingText.matchForm(read))) return false
        val first = read.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        val around = TrackingText.matchForm(MoneyReader.lineAt(text, money.start) + "\n" + first)
        return !ChargeLexicon.NOT_A_CHARGE.containsMatchIn(around)
    }

    /** The date on the amount's line, else on a line with a charge word, else the first date in the text. */
    private fun chargeDate(text: String, money: Money): LocalDate? {
        TrackingDates.find(MoneyReader.lineAt(text, money.start)).firstOrNull()?.let { return it.date }
        for (line in text.lines()) {
            if (ChargeLexicon.CHARGE.containsMatchIn(TrackingText.matchForm(line))) {
                TrackingDates.find(line).firstOrNull()?.let { return it.date }
            }
        }
        return TrackingDates.find(text).firstOrNull()?.date
    }

    private fun says(text: String): Boolean = ChargeLexicon.SUBSCRIPTION.containsMatchIn(TrackingText.matchForm(text))

    /**
     * One charge per sighting group: same merchant (case-insensitive), currency and amount, dates at most
     * [SAME_CHARGE_DAYS] apart, from different items. The richest sighting is kept (see [PRIORITY]); the others'
     * items go to [TrackedCharge.alsoSeenIn]. Rows of one statement file are never merged with each other.
     */
    fun dedupe(charges: List<TrackedCharge>): List<TrackedCharge> {
        val out = mutableListOf<TrackedCharge>()
        for ((_, group) in charges.groupBy { Triple(it.merchant.lowercase(), it.currency, it.amountMinor) }) {
            val kept = mutableListOf<TrackedCharge>()
            for (c in group.sortedWith(compareBy<TrackedCharge>({ it.date }, { PRIORITY.indexOf(it.kind) }, { it.itemId }))) {
                val i = kept.indexOfLast { k ->
                    k.itemId != c.itemId && c.itemId !in k.alsoSeenIn && abs(k.date.daysUntil(c.date)) <= SAME_CHARGE_DAYS
                }
                if (i < 0) { kept += c; continue }
                val k = kept[i]
                val (keep, other) = if (PRIORITY.indexOf(c.kind) < PRIORITY.indexOf(k.kind)) c to k else k to c
                kept[i] = keep.copy(alsoSeenIn = (keep.alsoSeenIn + other.itemId + other.alsoSeenIn).distinct())
            }
            out += kept
        }
        return out.sortedWith(compareBy<TrackedCharge>({ it.date }, { it.merchant }, { it.itemId }))
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: the Kotlin test command with `--tests 'dev.loupe.kit.tracking.ChargeExtractorTest'`
Expected: 9 tests PASS.

- [ ] **Step 5: Commit**

```bash
cd /Users/bistrocloud/Documents/Loupe/jevistication
git add loupe-kit/src/commonMain/kotlin/dev/loupe/kit/tracking/ChargeExtractor.kt loupe-kit/src/commonTest/kotlin/dev/loupe/kit/tracking/TrackingItems.kt loupe-kit/src/commonTest/kotlin/dev/loupe/kit/tracking/ChargeExtractorTest.kt
git commit -m "kit: charges from mail, files, photo receipts, statements and calendar, de-duplicated

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
git push
```

---

### Task 6: The full expiry timeline

**Files:**
- Create: `loupe-kit/src/commonMain/kotlin/dev/loupe/kit/tracking/ExpiryExtractor.kt`
- Test: `loupe-kit/src/commonTest/kotlin/dev/loupe/kit/tracking/ExpiryExtractorTest.kt`

**Interfaces:**
- Consumes: `ExpiryLexicon`, `DocumentKinds`, `TrackingDates`, `MoneyReader.lineAt`, `DateFacts.daysUntil`, `DateFacts.expiresWithin`, `ValidityRule`, `TrackingItems` (tests).
- Produces: `data class ExpiryFind(item: SourceItem, expiry: LocalDate, daysRemaining: Long, ambiguous: Boolean, breachesRule: Boolean, kind: DocumentKind?, line: String?)`; `enum class ExpiryBucket(val id: String) { OVERDUE, WEEK, MONTH, LATER; companion object { fun of(daysRemaining: Long): ExpiryBucket } }`; `object ExpiryExtractor { const val WINDOW = 60; fun find(items: List<SourceItem>, today: LocalDate, rule: ValidityRule): List<ExpiryFind>; fun of(item: SourceItem, today: LocalDate, rule: ValidityRule): ExpiryFind? }`.

- [ ] **Step 1: Write the failing test**

```kotlin
package dev.loupe.kit.tracking

import dev.loupe.engine.ValidityRule
import dev.loupe.sources.common.ItemKind
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExpiryExtractorTest {
    private val today = LocalDate(2026, 9, 28)
    private val rule = ValidityRule("six months of validity (e.g. Schengen passports)", 6)

    @Test
    fun anEgyptianNationalIdOnThePhotos() {
        val id = TrackingItems.image("id", "2026-03-02",
            "جمهورية مصر العربية\nبطاقة تحقيق الشخصية\nتاريخ الإصدار: ٢٠٢١/٠٣/١٥\nالبطاقة سارية حتى ٢٠٢٨/٠٣/١٤")
        val f = ExpiryExtractor.of(id, today, rule)!!
        assertEquals(LocalDate(2028, 3, 14), f.expiry)
        assertEquals(DocumentKind.NATIONAL_ID, f.kind)
        assertEquals("البطاقة سارية حتى ٢٠٢٨/٠٣/١٤", f.line)
        assertFalse(f.breachesRule)
    }

    @Test
    fun theDateAfterTheExpiryWordWinsOverTheLatest() {
        val policy = TrackingItems.file("policy.pdf", "2025-12-01", ItemKind.PDF,
            "Motor Insurance Policy\nPeriod of insurance: 01/12/2025 to 30/11/2026\nThis policy expires on 30/11/2026.\nNext review 2027/01/15")
        assertEquals(LocalDate(2026, 11, 30), ExpiryExtractor.of(policy, today, rule)!!.expiry)
    }

    @Test
    fun theTimelineKeepsEveryDateNear() {
        val passport = TrackingItems.image("pp", "2021-01-15", "PASSPORT\nDate of issue 15 JAN 2021\nDate of expiry 14 JAN 2031")
        val old = TrackingItems.file("licence.txt", "2016-05-15", ItemKind.TEXT, "Driving licence\nValid until 15/05/2026")
        val found = ExpiryExtractor.find(listOf(passport, old), today, rule)
        assertEquals(listOf("files:licence.txt", "photos:pp"), found.map { it.item.id }, "overdue first, years away kept")
        assertTrue(found[0].daysRemaining < 0)
        assertTrue(found[0].breachesRule, "the six-month rule stays as a highlight")
        assertFalse(found[1].breachesRule)
        assertTrue(found[1].daysRemaining > 366, "no one-year cut any more")
    }

    @Test
    fun cardEndingWordsOnAReceiptAreNotAnExpiry() {
        val receipt = TrackingItems.email("sp", "2026-06-12", "Spotify <no-reply@spotify.com>", "إيصال اشتراك Spotify Premium",
            "تم تجديد اشتراكك.\nالمبلغ المدفوع: ٦٩٫٩٩ ج.م\nتاريخ الدفع: ١٢/٠٦/٢٠٢٦\nطريقة الدفع: ماستركارد تنتهي بـ ٧٧٢٠")
        assertNull(ExpiryExtractor.of(receipt, today, rule))
    }

    @Test
    fun anOfferThatExpiresIsNotADocument() {
        val offer = TrackingItems.email("of", "2026-09-20", "Noon <deals@noon.example>", "Flash sale",
            "Flash sale! 30% off electronics.\nThis offer expires 30/10/2026. Use code SAVE30.")
        assertNull(ExpiryExtractor.of(offer, today, rule))
        val policyOffer = TrackingItems.file("renew.pdf", "2026-09-01", ItemKind.PDF,
            "Special renewal offer for your insurance policy\nYour policy expires on 30/11/2026")
        assertEquals(DocumentKind.INSURANCE, ExpiryExtractor.of(policyOffer, today, rule)!!.kind, "a document with an offer stays")
    }

    @Test
    fun anExpiryWordWithoutADateNearIsNothingUnlessTheDocumentIsKnown() {
        val otp = TrackingItems.file("otp.txt", "2026-09-27", ItemKind.TEXT, "Your code 482913 expires in 10 minutes. Do not share this code with anyone; your bank will never ask for it.\n\nSent 2026/09/27")
        assertNull(ExpiryExtractor.of(otp, today, rule))
        val lease = TrackingItems.file("lease.pdf", "2025-07-01", ItemKind.PDF,
            "Tenancy agreement\nThe tenancy expires at the end of the term agreed by both parties below, as signed.\nTerm: 01/07/2025 - 30/06/2027")
        assertEquals(LocalDate(2027, 6, 30), ExpiryExtractor.of(lease, today, rule)!!.expiry, "a known document takes its latest date")
    }

    @Test
    fun buckets() {
        assertEquals(ExpiryBucket.OVERDUE, ExpiryBucket.of(-1))
        assertEquals(ExpiryBucket.WEEK, ExpiryBucket.of(0))
        assertEquals(ExpiryBucket.WEEK, ExpiryBucket.of(7))
        assertEquals(ExpiryBucket.MONTH, ExpiryBucket.of(8))
        assertEquals(ExpiryBucket.MONTH, ExpiryBucket.of(30))
        assertEquals(ExpiryBucket.LATER, ExpiryBucket.of(31))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: the Kotlin test command with `--tests 'dev.loupe.kit.tracking.ExpiryExtractorTest'`
Expected: FAIL, `Unresolved reference: ExpiryExtractor`.

- [ ] **Step 3: Write `ExpiryExtractor`**

```kotlin
package dev.loupe.kit.tracking

import dev.loupe.engine.DateFacts
import dev.loupe.engine.DateMatch
import dev.loupe.engine.ValidityRule
import dev.loupe.sources.common.ItemKind
import dev.loupe.sources.common.SourceItem
import kotlinx.datetime.LocalDate

/** A dated document on the expiry timeline, with the rules' kind and the line the date was read from. */
data class ExpiryFind(
    val item: SourceItem,
    val expiry: LocalDate,
    val daysRemaining: Long,
    val ambiguous: Boolean,
    /** Inside the validity rule: a highlight on the timeline, and a finding. */
    val breachesRule: Boolean,
    val kind: DocumentKind?,
    val line: String?,
)

/** The timeline's groups (spec §7.2): overdue, 0-7 days, 8-30 days, later. */
enum class ExpiryBucket(val id: String) {
    OVERDUE("overdue"), WEEK("week"), MONTH("month"), LATER("later");

    companion object {
        fun of(daysRemaining: Long): ExpiryBucket = when {
            daysRemaining < 0 -> OVERDUE
            daysRemaining <= 7 -> WEEK
            daysRemaining <= 30 -> MONTH
            else -> LATER
        }
    }
}

/**
 * The expiry radar's mechanical half (spec §7.2): every item with an expiry word and its date, on a full timeline
 * (no one-year cut; the validity rule is a highlight, not a filter). The date is the first one within [WINDOW]
 * characters after an expiry word; without one, a document of a known kind takes its latest date, and anything
 * else is left out (a receipt's "تنتهي بـ ٧٧٢٠" is a card number). An offer's "expires" is left out unless the item
 * is a known kind of document. Ambiguous dates take the earlier reading, as `ExpiryRadar` does.
 */
object ExpiryExtractor {
    const val WINDOW: Int = 60

    fun find(items: List<SourceItem>, today: LocalDate, rule: ValidityRule): List<ExpiryFind> =
        items.mapNotNull { of(it, today, rule) }.sortedWith(compareBy<ExpiryFind>({ it.daysRemaining }, { it.item.id }))

    fun of(item: SourceItem, today: LocalDate, rule: ValidityRule): ExpiryFind? {
        if (!item.hasText || item.duplicateOf != null || item.kind == ItemKind.CONTACT) return null
        val text = item.text
        val form = TrackingText.matchForm(text)
        val words = ExpiryLexicon.EXPIRY.findAll(form).toList()
        if (words.isEmpty()) return null
        val kind = DocumentKinds.of(text)
        if (kind == null && ExpiryLexicon.PROMO.containsMatchIn(form)) return null
        var picked: DateMatch? = null
        var lineAt = words.first().range.first
        for (w in words) {
            val start = w.range.last + 1
            val d = TrackingDates.find(text.substring(start, minOf(text.length, start + WINDOW))).firstOrNull() ?: continue
            picked = d
            lineAt = w.range.first
            break
        }
        val chosen = picked ?: (if (kind != null) TrackingDates.find(text).maxByOrNull { it.date } else null) ?: return null
        val expiry = listOfNotNull(chosen.date, chosen.alternate).min()
        return ExpiryFind(
            item = item,
            expiry = expiry,
            daysRemaining = DateFacts.daysUntil(expiry, today),
            ambiguous = chosen.ambiguous,
            breachesRule = DateFacts.expiresWithin(expiry, today, rule.monthsRequired),
            kind = kind,
            line = MoneyReader.lineAt(text, lineAt).trim().ifEmpty { null },
        )
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: the Kotlin test command with `--tests 'dev.loupe.kit.tracking.ExpiryExtractorTest'`
Expected: 7 tests PASS.

- [ ] **Step 5: Commit**

```bash
cd /Users/bistrocloud/Documents/Loupe/jevistication
git add loupe-kit/src/commonMain/kotlin/dev/loupe/kit/tracking/ExpiryExtractor.kt loupe-kit/src/commonTest/kotlin/dev/loupe/kit/tracking/ExpiryExtractorTest.kt
git commit -m "kit: full expiry timeline with Egyptian document kinds; the six-month rule as a highlight

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
git push
```

---

### Task 7: The watchers use the new tracking (and say what they now do)

**Files:**
- Modify: `loupe-kit/src/commonMain/kotlin/dev/loupe/kit/watchers/WatcherRun.kt` (imports; `ExpiryCandidate`, `WatcherReport` fields; `run`; `expiryCandidates`; `charges`; new `trackedCharges`, `census`; remove `EXPIRY_WORDS`, `CHARGE_WORDS`, `AMOUNT`, `csvCharges`, `minor`; the object's doc bullets)
- Modify: `loupe-kit/src/commonMain/kotlin/dev/loupe/kit/watchers/WatcherFindings.kt` (`CensusRow`, `ExpiryRow` fields; `expiries`; `findings` expiry line; `census`; new `monthlyByCurrency`; remove `EXPIRY_LINE`)
- Modify (compile fixes): `ios/Loupe/Now/WatchersService.swift:264-269`, `ios/LoupeTests/GuardModelTests.swift:12-21`, and every other Swift `CensusRow(` / `ExpiryRow(` call found by the grep in Step 6
- Modify (copy that would now be false): `ios/Loupe/Guard/GuardSections.swift:183, 210-211`, `loupe-desktop/src/main/kotlin/dev/loupe/desktop/ui/OverviewScreens.kt:120, 124`
- Test: create `loupe-kit/src/commonTest/kotlin/dev/loupe/kit/tracking/WatcherTrackingTest.kt`; existing `watchers/WatcherRunTest.kt`, `watchers/InboxChargesTest.kt`, `settings/SettingsConsumersTest.kt` and `:loupe-desktop:test` must stay green

**Interfaces:**
- Consumes: `ChargeExtractor.extract`, `TrackedCharge`, `ExpiryExtractor.find`, `ExpiryFind`, `DocumentKind.id`.
- Produces: `ExpiryCandidate(..., documentKind: String? = null, line: String? = null)`; `WatcherReport(..., charges: List<TrackedCharge> = emptyList(), currencyOf: Map<String, String> = emptyMap())`; `WatcherRun.trackedCharges(items): List<TrackedCharge>`, `WatcherRun.census(charges, today): List<RecurringCharge>`, `WatcherRun.charges(items): List<Pair<SourceItem, Charge>>` (same signature as today); `CensusRow(..., verdict, currency: String = "", lines: List<String> = emptyList())`; `ExpiryRow(..., sample, documentKind: String? = null)`; `WatcherFindings.monthlyByCurrency(rows: List<CensusRow>): Map<String, Long>`.

- [ ] **Step 1: Write the failing test**

```kotlin
package dev.loupe.kit.tracking

import dev.loupe.kit.watchers.WatcherFindings
import dev.loupe.kit.watchers.WatcherRun
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WatcherTrackingTest {
    private val today = LocalDate(2026, 9, 28)

    private fun netflix(month: String, amount: String) = TrackingItems.email("nf-$month-${amount.filter { it.isLetter() }}", "2026-$month-03",
        "Netflix <info@mailer.netflix.com>", "Your Netflix payment receipt", "We've charged your card.\n\nAmount paid: $amount")

    @Test
    fun aMerchantInTwoCurrenciesIsTwoRowsAndTwoTotals() {
        val items = listOf("06", "07", "08").flatMap { m -> listOf(netflix(m, "EGP 165.00"), netflix(m, "USD 9.99")) }
        val report = WatcherRun.run(items, today, null)
        assertEquals(setOf("Netflix (EGP)", "Netflix (USD)"), report.recurring.map { it.merchant }.toSet())
        assertEquals("USD", report.currencyOf["Netflix (USD)"])
        val summary = WatcherFindings.summarise(report, items, emptySet())
        assertEquals(mapOf("EGP" to 16500L, "USD" to 999L), WatcherFindings.monthlyByCurrency(summary.census.rows))
        assertEquals(setOf("EGP", "USD"), summary.census.rows.map { it.currency }.toSet())
    }

    @Test
    fun censusRowsCarryTheirLinesAndEveryReference() {
        val mails = listOf("06", "07", "08").map { netflix(it, "EGP 165.00") }
        val rows = listOf("06", "07", "08").map { TrackingItems.csvRow("nf$it", "2026-$it-04", "NETFLIX.COM", 16500, "EGP") }
        val items = mails + rows
        val report = WatcherRun.run(items, today, null)
        assertEquals(3, report.chargesFound, "each charge once, though read twice")
        val row = WatcherFindings.summarise(report, items, emptySet()).census.rows.single()
        assertEquals("Netflix", row.merchant)
        assertEquals(3, row.occurrences)
        assertEquals("EGP", row.currency)
        assertEquals(items.map { it.id }.toSet(), row.itemIds.toSet())
        assertEquals(listOf("Amount paid: EGP 165.00", "Amount paid: EGP 165.00", "Amount paid: EGP 165.00"), row.lines)
    }

    @Test
    fun expiryRowsCarryTheRulesKindAndTheirLine() {
        val id = TrackingItems.image("id", "2026-03-02", "بطاقة تحقيق الشخصية\nالبطاقة سارية حتى ٢٠٢٨/٠٣/١٤")
        val report = WatcherRun.run(listOf(id), today, null)
        val row = WatcherFindings.summarise(report, listOf(id), emptySet()).expiries.single()
        assertEquals("national_id", row.documentKind)
        assertEquals("2028-03-14", row.expiryIso)
        assertEquals("البطاقة سارية حتى ٢٠٢٨/٠٣/١٤", row.line)
        assertTrue(row.findingKey == null, "years away: on the timeline, not a finding")
    }

    @Test
    fun theDesktopsChargePairsStillCome() {
        val mails = listOf("06", "07", "08").map { netflix(it, "EGP 165.00") }
        val pairs = WatcherRun.charges(mails)
        assertEquals(mails.map { it.id }, pairs.map { it.first.id })
        assertEquals(16500L, pairs.first().second.amountMinor)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: the Kotlin test command with `--tests 'dev.loupe.kit.tracking.WatcherTrackingTest'`
Expected: FAIL, `Unresolved reference: currencyOf` / `monthlyByCurrency`.

- [ ] **Step 3: Wire `WatcherRun`**

In `WatcherRun.kt` add the imports

```kotlin
import dev.loupe.kit.tracking.ChargeExtractor
import dev.loupe.kit.tracking.ExpiryExtractor
import dev.loupe.kit.tracking.TrackedCharge
```

replace

```kotlin
data class ExpiryCandidate(val item: SourceItem, val expiry: LocalDate, val daysRemaining: Long, val ambiguous: Boolean, val breachesRule: Boolean)
```

with

```kotlin
data class ExpiryCandidate(
    val item: SourceItem,
    val expiry: LocalDate,
    val daysRemaining: Long,
    val ambiguous: Boolean,
    val breachesRule: Boolean,
    /** The rules' document kind (`DocumentKind.id`), or null. The model's own judgment is `ExpiryAlert.documentType`. */
    val documentKind: String? = null,
    /** The line with the expiry word, verbatim. */
    val line: String? = null,
)
```

replace

```kotlin
    /** `features.watchers.use_laya` was off: the expiry radar's Laya half did not run (the banner). */
    val layaOff: Boolean = false,
)
```

with

```kotlin
    /** `features.watchers.use_laya` was off: the expiry radar's Laya half did not run (the banner). */
    val layaOff: Boolean = false,
    /** Every charge read, once each, named per currency: the census's evidence. */
    val charges: List<TrackedCharge> = emptyList(),
    /** The census merchant's currency (ISO 4217, or "" when none was written). */
    val currencyOf: Map<String, String> = emptyMap(),
)
```

replace the two doc bullets

```kotlin
 * - **Expiry radar** — the date arithmetic is mechanical and always runs; deciding *what a
```

(through the end of the **Recurring money** bullet, the line ending `… The merchant is the sender's name.`) with

```kotlin
 * - **Expiry radar** — the full timeline (`ExpiryExtractor`: English, Arabic and Egyptian expiry words, Egyptian
 *   document kinds, Arabic digits and months) is mechanical and always runs; deciding *what a document is* with the
 *   model runs only when a backend is passed. The six-month rule is a highlight, not a filter.
 * - **Recurring money** — charges from every source (`ChargeExtractor`: mail, files and photo receipts, statement
 *   rows, calendar events; EGP and £ $ €; English, Arabic, Egyptian and Franco words), each counted once; the census
 *   runs per currency.
```

delete the three constants

```kotlin
    private val EXPIRY_WORDS = Regex("""\b(expir\w*|valid until|valid to|valid thru|renewal date|4b\.)""", RegexOption.IGNORE_CASE)
    private val CHARGE_WORDS = Regex("""\b(charged|payment received|paid|receipt for)\b""", RegexOption.IGNORE_CASE)
    private val AMOUNT = Regex("""[£$€]\s?(\d[\d,]*(?:\.\d{2})?)""")
```

in `run(...)` replace

```kotlin
        val charges = charges(texty)
        val recurring = RecurringMoney.census(charges.map { it.second }, today)
```

with

```kotlin
        val charges = trackedCharges(texty)
        val recurring = census(charges, today)
```

and replace `            layaOff = !policy.useLaya,` with

```kotlin
            layaOff = !policy.useLaya,
            charges = charges,
            currencyOf = charges.associate { it.merchant to it.currency },
```

replace the whole `expiryCandidates` function (its doc line through its closing `}.sortedBy { it.daysRemaining }`) with

```kotlin
    /** Every item with an expiry word and its date, on the full timeline, soonest first (`ExpiryExtractor`). */
    fun expiryCandidates(items: List<SourceItem>, today: LocalDate, rule: ValidityRule): List<ExpiryCandidate> =
        ExpiryExtractor.find(items, today, rule).map { f ->
            ExpiryCandidate(f.item, f.expiry, f.daysRemaining, f.ambiguous, f.breachesRule, f.kind?.id, f.line)
        }
```

and replace the whole `charges` function, `csvCharges` and `minor` (from `    /** (item, charge) pairs from emails that record a payment and from money CSVs. */` through the end of `private fun minor(…) { … }`) with

```kotlin
    /**
     * Every charge from every source, once each (`ChargeExtractor`). A merchant billed in more than one currency is
     * named with the currency in each ("Netflix (EGP)", "Netflix (USD)"): amounts are never added across currencies.
     */
    fun trackedCharges(items: List<SourceItem>): List<TrackedCharge> {
        val charges = ChargeExtractor.extract(items)
        val multi = charges.groupBy { it.merchant }.filterValues { cs -> cs.map { it.currency }.toSet().size > 1 }.keys
        return charges.map { c ->
            if (c.merchant in multi) c.copy(merchant = "${c.merchant} (${c.currency.ifEmpty { "no currency" }})") else c
        }
    }

    /** The census, run per currency. */
    fun census(charges: List<TrackedCharge>, today: LocalDate): List<RecurringCharge> =
        charges.groupBy { it.currency }
            .flatMap { (_, cs) -> RecurringMoney.census(cs.map { it.toCharge() }, today) }
            .sortedByDescending { it.totalMinor() }

    /** (item, charge) pairs, for callers that take the engine's `Charge` (the desktop). */
    fun charges(items: List<SourceItem>): List<Pair<SourceItem, Charge>> {
        val byId = items.associateBy { it.id }
        return trackedCharges(items).mapNotNull { c -> byId[c.itemId]?.let { it to c.toCharge() } }
    }
```

- [ ] **Step 4: Wire `WatcherFindings`**

In `WatcherFindings.kt` replace

```kotlin
    /** The user's answer about this merchant (Confirm, or null); set-aside merchants leave the census. */
    val verdict: FindingVerdict? = null,
) {
```

with

```kotlin
    /** The user's answer about this merchant (Confirm, or null); set-aside merchants leave the census. */
    val verdict: FindingVerdict? = null,
    /** ISO 4217 of every amount in this row, or "" when the charges named none. Never converted. */
    val currency: String = "",
    /** The line each charge was read from, oldest first (the reference's "Read"). */
    val lines: List<String> = emptyList(),
) {
```

replace

```kotlin
    val line: String?,
    val sample: Boolean,
)

/** What Now shows from one watcher run. */
```

with

```kotlin
    val line: String?,
    val sample: Boolean,
    /** The rules' document kind (`DocumentKind.id`: national_id, passport, car_licence …), or null. */
    val documentKind: String? = null,
)

/** What Now shows from one watcher run. */
```

delete `    private val EXPIRY_LINE = Regex("""\b(expir\w*|valid until|valid to|valid thru|renewal date|4b\.)""", RegexOption.IGNORE_CASE)`; in `expiries(…)` replace

```kotlin
                line = c.item.text.lines().firstOrNull { EXPIRY_LINE.containsMatchIn(it) }?.trim(),
                sample = isSample(c.item),
```

with

```kotlin
                line = c.line,
                sample = isSample(c.item),
                documentKind = c.documentKind,
```

in `findings(…)` replace `            val line = c.item.text.lines().firstOrNull { EXPIRY_LINE.containsMatchIn(it) }?.trim()` with `            val line = c.line`; replace the whole `census(…)` function with

```kotlin
    fun census(report: WatcherReport, items: List<SourceItem>, isSample: (SourceItem) -> Boolean): SubscriptionCensus {
        val byId = items.associateBy { it.id }
        val byMerchant = report.charges.groupBy { it.merchant }.mapValues { (_, cs) -> cs.sortedBy { it.date } }
        val rows = report.recurring.map { rc ->
            val cs = byMerchant[rc.merchant].orEmpty()
            val ids = cs.flatMap { listOf(it.itemId) + it.alsoSeenIn }.distinct()
            CensusRow(
                merchant = rc.merchant,
                cadence = rc.cadence.name.lowercase(),
                occurrences = rc.occurrences,
                typicalMinor = rc.typicalAmountMinor,
                lastChargedIso = rc.lastCharged.toString(),
                daysSinceLastCharge = rc.daysSinceLastCharge,
                monthlyMinor = monthly(rc.cadence, rc.typicalAmountMinor),
                sample = ids.isNotEmpty() && ids.all { id -> byId[id]?.let(isSample) ?: false },
                itemIds = ids,
                nextExpectedIso = nextExpected(rc.cadence, rc.lastCharged, report.today)?.toString(),
                currency = report.currencyOf[rc.merchant] ?: cs.firstOrNull()?.currency ?: "",
                lines = cs.map { it.line },
            )
        }
        return SubscriptionCensus(
            rows = rows,
            monthlyTotalMinor = rows.sumOf { it.monthlyMinor ?: 0L },
            chargesFound = report.chargesFound,
            sample = rows.isNotEmpty() && rows.all { it.sample },
        )
    }

    /** The monthly total per currency (rows with a regular cadence): what the Money card shows, never one mixed sum. */
    fun monthlyByCurrency(rows: List<CensusRow>): Map<String, Long> =
        rows.filter { it.monthlyMinor != null }.groupBy { it.currency }.mapValues { (_, rs) -> rs.sumOf { it.monthlyMinor!! } }
```

- [ ] **Step 5: Run the Kotlin tests this touches**

Run: the Kotlin test command with `--tests 'dev.loupe.kit.tracking.*' --tests 'dev.loupe.kit.watchers.*' --tests 'dev.loupe.kit.settings.SettingsConsumersTest'`, then `cd /Users/bistrocloud/Documents/Loupe/jevistication && ./gradlew :loupe-desktop:test`
Expected: PASS. If `WatcherRunTest` fails because the sample now yields one more timeline row or charge (the full timeline, receipts from files), check the new row is a real one from the sample and update only that count with a comment naming the item; the planted findings (passport 113 days, Streamflix monthly ×4, CloudBox, the +23 % premium, the impostor, the fraud links) must not change.

- [ ] **Step 6: Swift compile fixes and the copy that would now be false**

`ios/Loupe/Now/WatchersService.swift`, in `CensusRow.withVerdict`, replace

```swift
                  sample: sample, itemIds: itemIds, nextExpectedIso: nextExpectedIso, verdict: verdict)
```

with

```swift
                  sample: sample, itemIds: itemIds, nextExpectedIso: nextExpectedIso, verdict: verdict,
                  currency: currency, lines: lines)
```

`ios/LoupeTests/GuardModelTests.swift`: in `expiry(…)` replace `line: nil, sample: false)` with `line: nil, sample: false, documentKind: nil)`, and in `sub(…)` replace `nextExpectedIso: monthly == nil ? nil : "2026-10-01", verdict: nil)` with `nextExpectedIso: monthly == nil ? nil : "2026-10-01", verdict: nil, currency: "", lines: [])`.

Then find every other construction:

```bash
cd /Users/bistrocloud/Documents/Loupe/jevistication && grep -rn "CensusRow(merchant:\|ExpiryRow(itemId:" ios --include='*.swift' | grep -v "currency:\|documentKind:"
```

Expected: no line. If the shell plan has landed, it prints `ios/LoupeTests/HomeModelTests.swift` and `ios/LoupeTests/MailScreenTests.swift`: in each `CensusRow(…)` call there, replace `verdict: nil)` with `verdict: nil, currency: "", lines: [])`, and in each `ExpiryRow(…)` call replace `sample: false)` with `sample: false, documentKind: nil)`; run the grep again until it prints nothing.

`ios/Loupe/Guard/GuardSections.swift:183`: replace `an expiry word near a date within a year, or already passed.` with `an expiry word near a date, however far away, or already passed.`; lines 210-211: replace `title: "No expiry dates within a year",` with `title: "No expiry dates found",` and `has an expiry word near a date in the next year.` with `has an expiry word near a date.`

`loupe-desktop/src/main/kotlin/dev/loupe/desktop/ui/OverviewScreens.kt:120`: replace `No document with an expiry word and a date within a year was found.` with `No document with an expiry word and a date was found.`; line 124: replace `charge(s) read from emails that say something was charged or paid, and from CSV files with merchant, date and amount columns.` with `charge(s) read from mail, files, photo receipts, statements and calendar events, each counted once.`

- [ ] **Step 7: Build the engine for iOS and run the Swift tests that construct these types**

```bash
cd /Users/bistrocloud/Documents/Loupe/jevistication && export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home && ./gradlew :loupe-kit:assembleLoupeKitDebugXCFramework
cd ios && xcodegen generate && xcodebuild test -project Loupe.xcodeproj -scheme Loupe -destination 'platform=iOS Simulator,name=iPhone 17 Pro Max' -derivedDataPath /Volumes/Sambawy/.loupe-agent-tmp/tracking-engine/DerivedData -only-testing:LoupeTests/GuardModelTests
```

Expected: `BUILD SUCCESSFUL`, then `** TEST SUCCEEDED **`.

- [ ] **Step 8: Commit**

```bash
cd /Users/bistrocloud/Documents/Loupe/jevistication
git add loupe-kit/src/commonMain/kotlin/dev/loupe/kit/watchers/WatcherRun.kt loupe-kit/src/commonMain/kotlin/dev/loupe/kit/watchers/WatcherFindings.kt loupe-kit/src/commonTest/kotlin/dev/loupe/kit/tracking/WatcherTrackingTest.kt ios/Loupe/Now/WatchersService.swift ios/LoupeTests/GuardModelTests.swift ios/Loupe/Guard/GuardSections.swift loupe-desktop/src/main/kotlin/dev/loupe/desktop/ui/OverviewScreens.kt
git commit -m "kit: the watchers track charges from every source per currency, and the full expiry timeline

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
git push
```

(Add `loupe-kit/src/commonTest/kotlin/dev/loupe/kit/watchers/WatcherRunTest.kt` to `git add` if Step 5 changed a count, and `ios/LoupeTests/HomeModelTests.swift ios/LoupeTests/MailScreenTests.swift` if Step 6 changed them.)

---

### Task 8: The labelled set and the measured bar (§7.3)

**Files:**
- Modify: `loupe-kit/build.gradle.kts` (the `TRACKING_FIXTURES` test path)
- Create: `loupe-kit/src/commonTest/fixtures/tracking/statement.csv`, `loupe-kit/src/commonTest/fixtures/tracking/items/*.txt` (73 files, by the script in Step 3)
- Create: `loupe-kit/src/commonTest/kotlin/dev/loupe/kit/tracking/TrackingFixtures.kt`, `loupe-kit/src/commonTest/kotlin/dev/loupe/kit/tracking/TrackingMeasureTest.kt`

**Interfaces:**
- Consumes: `TrackingItems.*`, `WatcherRun.trackedCharges`, `WatcherRun.run`, `WatcherReport.currencyOf`, `ExpiryCandidate.documentKind`, `Inbox`, `InboxFs`, `SourceFs`, `CsvRows.parseNumber`.
- Produces: `internal data class Fixture(id, lang, expect, item, merchant, currency, amountMinor, expiry, doc)`; `internal object TrackingFixtures { val TODAY: LocalDate; fun load(): List<Fixture>; fun parse(id: String, raw: String): Fixture; fun statementRows(): List<SourceItem> }`; the constant `TRACKING_FIXTURES` (package `dev.loupe.kit.watchers`, generated).

Fixture format (one item per `.txt`): `key: value` lines, a line `---`, then the item's text (an email's body, a photo's OCR, a file's text, an event's notes). Keys: `lang` (`en` English, `ar` Arabic, `eg` Egyptian Arabic, `franco` Arabic in Latin letters), `kind` (`email|image|pdf|text|html|event`), `date`, `from` and `subject` (email; `subject` is an event's title), `expect` (`charge|expiry|none`), and the labels `merchant`, `currency`, `amount` (charges), `expiry`, `doc` (expiries). "Today" for the set is 2026-09-28. Real-shaped, synthetic: no real person's data.

- [ ] **Step 1: The test path**

In `loupe-kit/build.gradle.kts` add under `val phishingDbFixtures = project.file("src/commonTest/fixtures/phishingdb")`:

```kotlin
// The tracking trackers' labelled set (spec 2026-09-28 §7.3): synthetic, real-shaped Egyptian and English items.
val trackingFixtures = project.file("src/commonTest/fixtures/tracking")
```

in `generateTestPaths` add `    inputs.property("trackingFixtures", trackingFixtures.absolutePath)` after `    inputs.property("phishingDbFixtures", phishingDbFixtures.absolutePath)`, and in the generated text add, before the `TEST_TMP` line,

```kotlin
                "internal const val TRACKING_FIXTURES: String = \"" + trackingFixtures.absolutePath.replace("\\", "/") + "\"\n" +
```

- [ ] **Step 2: Write the loader and the failing measurement test**

`TrackingFixtures.kt`:

```kotlin
package dev.loupe.kit.tracking

import dev.loupe.kit.watchers.TEST_TMP
import dev.loupe.kit.watchers.TRACKING_FIXTURES
import dev.loupe.sources.common.CsvRows
import dev.loupe.sources.common.Inbox
import dev.loupe.sources.common.InboxFs
import dev.loupe.sources.common.ItemKind
import dev.loupe.sources.common.NoPlatformExtractors
import dev.loupe.sources.common.SourceFs
import dev.loupe.sources.common.SourceItem
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.random.Random

/** One labelled item of the set. */
internal data class Fixture(
    val id: String,
    val lang: String,
    val expect: String,
    val item: SourceItem,
    val merchant: String?,
    val currency: String?,
    val amountMinor: Long?,
    val expiry: LocalDate?,
    val doc: String?,
)

internal object TrackingFixtures {
    val TODAY: LocalDate = LocalDate(2026, 9, 28)

    fun load(): List<Fixture> {
        val dir = "$TRACKING_FIXTURES/items"
        val names = SourceFs.list(dir)!!.filter { it.isRegularFile && it.name.endsWith(".txt") }.map { it.name }.sorted()
        return names.map { parse(it.removeSuffix(".txt"), SourceFs.readBytes("$dir/$it").decodeToString()) }
    }

    fun parse(id: String, raw: String): Fixture {
        val head = raw.substringBefore("\n---\n")
        val body = raw.substringAfter("\n---\n").trimEnd('\n')
        val meta = head.lines().filter { ':' in it }.associate { it.substringBefore(':').trim() to it.substringAfter(':').trim() }
        val date = meta.getValue("date")
        val item = when (val kind = meta.getValue("kind")) {
            "email" -> TrackingItems.email(id, date, meta.getValue("from"), meta.getValue("subject"), body)
            "image" -> TrackingItems.image(id, date, body)
            "pdf" -> TrackingItems.file("$id.pdf", date, ItemKind.PDF, body)
            "html" -> TrackingItems.file("$id.html", date, ItemKind.HTML, body)
            "text" -> TrackingItems.file("$id.txt", date, ItemKind.TEXT, body)
            "event" -> TrackingItems.event(id, date, meta.getValue("subject"), body)
            else -> error("$id: unknown kind $kind")
        }
        return Fixture(id, meta.getValue("lang"), meta.getValue("expect"), item, meta["merchant"], meta["currency"],
                       meta["amount"]?.let { CsvRows.parseNumber(it) }, meta["expiry"]?.let { LocalDate.parse(it) }, meta["doc"])
    }

    /** statement.csv imported through the Inbox, as the phone imports a bank export: one item per row (CsvRows). */
    fun statementRows(): List<SourceItem> {
        val home = "$TEST_TMP/tracking-inbox-" + Random.nextLong().toULong()
        InboxFs.createDirectories("$home/in")
        InboxFs.writeNew("$home/in/statement.csv", SourceFs.readBytes("$TRACKING_FIXTURES/statement.csv"))
        val inbox = Inbox("$home/home", NoPlatformExtractors, TimeZone.UTC, Inbox.Limits())
        inbox.importFiles(listOf("$home/in/statement.csv"), "statement.csv", "Files", 1_790_000_000_000L)
        val rows = inbox.items()
        InboxFs.deleteRecursively(home)
        return rows
    }
}
```

`TrackingMeasureTest.kt`:

```kotlin
package dev.loupe.kit.tracking

import dev.loupe.kit.watchers.WatcherRun
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The measured bar (spec 2026-09-28 §7.3): recall ≥ 90 % for charges, subscriptions and expiring documents on the
 * labelled set, in every language with at least three positives; 0 false positives on the everyday items. Each
 * run prints its numbers per language ("tracking recall · charges · ar: 6/6").
 */
class TrackingMeasureTest {
    private val today = TrackingFixtures.TODAY
    private val fixtures by lazy { TrackingFixtures.load() }
    private val statement by lazy { TrackingFixtures.statementRows() }
    private val items by lazy { fixtures.map { it.item } + statement }
    private val charges by lazy { WatcherRun.trackedCharges(items) }
    private val report by lazy { WatcherRun.run(items, today, null) }

    /** The subscriptions the set holds: the census's merchant name, its currency, and the language of its items. */
    private val subscriptions = listOf(
        Triple("Netflix", "EGP", "en"), Triple("Spotify", "EGP", "ar"), Triple("WE", "EGP", "eg"), Triple("Vodafone", "EGP", "en"),
        Triple("نادي الجزيرة", "EGP", "ar"), Triple("Orange", "EGP", "eg"), Triple("Anghami", "EGP", "franco"),
        Triple("Shahid", "EGP", "en"), Triple("Gym membership", "EGP", "en"), Triple("iCloud", "USD", "en"),
    )

    /** Prints and returns hits / total per language, plus "all". */
    private fun tally(tracker: String, rows: List<Pair<String, Boolean>>): Map<String, Pair<Int, Int>> {
        val byLang = rows.groupBy({ it.first }, { it.second }).mapValues { (_, hits) -> hits.count { it } to hits.size }
        for ((lang, r) in byLang.entries.sortedBy { it.key }) println("tracking recall · $tracker · $lang: ${r.first}/${r.second}")
        val all = rows.count { it.second } to rows.size
        println("tracking recall · $tracker · all: ${all.first}/${all.second}")
        return byLang + ("all" to all)
    }

    private fun assertBar(tracker: String, tally: Map<String, Pair<Int, Int>>) {
        for ((lang, r) in tally) {
            if (r.second >= 3) assertTrue(r.first * 10 >= r.second * 9, "$tracker recall ($lang): ${r.first}/${r.second}")
        }
    }

    @Test
    fun theSetCoversWhatTheSpecAsksFor() {
        val negatives = fixtures.filter { it.expect == "none" }
        assertTrue(negatives.size >= 30, "30+ everyday items, have ${negatives.size}")
        val merchants = fixtures.mapNotNull { it.merchant }.toSet()
        for (m in listOf("Netflix", "Spotify", "WE", "Vodafone", "Orange", "نادي الجزيرة")) assertTrue(m in merchants, m)
        assertTrue(fixtures.any { it.id.startsWith("instapay-") } && fixtures.any { it.id.startsWith("fawry-") }, "InstaPay and Fawry receipts")
        val docs = fixtures.mapNotNull { it.doc }.toSet()
        for (d in listOf("national_id", "car_licence", "passport", "insurance")) assertTrue(d in docs, d)
        assertTrue(statement.size >= 8, "the bank statement's rows, have ${statement.size}")
        assertEquals(setOf("en", "ar", "eg", "franco"), fixtures.map { it.lang }.toSet())
    }

    @Test
    fun chargeRecallIsAtLeastNinetyPercentInEveryLanguage() {
        val rows = fixtures.filter { it.expect == "charge" }.map { f ->
            f.lang to charges.any { c ->
                (c.itemId == f.item.id || f.item.id in c.alsoSeenIn) && c.merchant == f.merchant &&
                    c.currency == f.currency && c.amountMinor == f.amountMinor
            }
        }
        assertBar("charges", tally("charges", rows))
    }

    @Test
    fun subscriptionRecallIsAtLeastNinetyPercent() {
        val found = report.recurring.map { it.merchant to report.currencyOf[it.merchant] }.toSet()
        val t = tally("subscriptions", subscriptions.map { (m, currency, lang) -> lang to ((m to currency) in found) })
        val all = t.getValue("all")
        assertTrue(all.first * 10 >= all.second * 9, "subscriptions ${all.first}/${all.second}; the census found $found")
    }

    @Test
    fun expiryRecallIsAtLeastNinetyPercentInEveryLanguage() {
        val positives = fixtures.filter { it.expect == "expiry" }
        assertBar("expiry", tally("expiry", positives.map { f ->
            f.lang to report.expiryCandidates.any { it.item.id == f.item.id && it.expiry == f.expiry }
        }))
        assertBar("document kind", tally("document kind", positives.filter { it.doc != null }.map { f ->
            f.lang to report.expiryCandidates.any { it.item.id == f.item.id && it.documentKind == f.doc }
        }))
    }

    @Test
    fun everydayItemsRaiseNothing() {
        val negatives = fixtures.filter { it.expect == "none" }.map { it.item.id }.toSet()
        val credits = statement.filter { it.facts["direction"] == "credit" }.map { it.id }.toSet()
        val chargeFp = charges.filter { c -> c.itemId in negatives || c.itemId in credits || c.alsoSeenIn.any { it in negatives } }
        val expiryFp = report.expiryCandidates.filter { it.item.id in negatives }
        val censusFp = report.recurring.filter { rc -> charges.any { it.merchant == rc.merchant && it.itemId in negatives } }
        println("tracking false positives · charges ${chargeFp.size} · expiry ${expiryFp.size} · census ${censusFp.size} · of ${negatives.size} everyday items")
        assertEquals(emptyList(), chargeFp.map { it.itemId })
        assertEquals(emptyList(), expiryFp.map { it.item.id })
        assertEquals(emptyList(), censusFp.map { it.merchant })
    }
}
```

- [ ] **Step 3: Write the labelled set**

Run this script once from the repo root (it writes 73 item files and the statement; the three monthly copies of a receipt differ only in their dates):

```bash
set -e
cd /Users/bistrocloud/Documents/Loupe/jevistication
T=loupe-kit/src/commonTest/fixtures/tracking
D=$T/items
mkdir -p "$D"
ar() { case "$1" in 06) echo "٠٦";; 07) echo "٠٧";; 08) echo "٠٨";; esac; }
prev() { case "$1" in 06) echo "05";; 07) echo "06";; 08) echo "07";; esac; }

# ---------- Charges: three months of each subscription ----------
for m in 06 07 08; do
A=$(ar "$m"); P=$(prev "$m")

cat > "$D/netflix-en-2026-$m.txt" <<EOF
lang: en
kind: email
date: 2026-$m-03
from: Netflix <info@mailer.netflix.com>
subject: Your Netflix payment receipt
expect: charge
merchant: Netflix
currency: EGP
amount: 165.00
---
Hi Sam,

Thanks for your payment. We've charged your card for your Standard plan.

Amount paid: EGP 165.00
Billing date: 2026-$m-03
Payment method: Visa •••• 4821

The Netflix team
EOF

cat > "$D/spotify-ar-2026-$m.txt" <<EOF
lang: ar
kind: email
date: 2026-$m-12
from: Spotify <no-reply@spotify.com>
subject: إيصال اشتراك Spotify Premium
expect: charge
merchant: Spotify
currency: EGP
amount: 69.99
---
مرحباً،

تم تجديد اشتراكك في Spotify Premium Individual بنجاح.

المبلغ المدفوع: ٦٩٫٩٩ ج.م
تاريخ الدفع: ١٢/$A/٢٠٢٦
طريقة الدفع: ماستركارد تنتهي بـ ٧٧٢٠

شكراً لاستماعك.
EOF

cat > "$D/we-eg-2026-$m.txt" <<EOF
lang: eg
kind: image
date: 2026-$m-05
expect: charge
merchant: WE
currency: EGP
amount: 350.00
---
WE
تم دفع فاتورة الإنترنت الأرضي بنجاح
رقم الخط: 0223456789
المبلغ: 350 جنيه
التاريخ: 05/$m/2026
رقم العملية: 88213345
شكرا لاستخدامك ماي وي
EOF

cat > "$D/vodafone-en-2026-$m.txt" <<EOF
lang: en
kind: pdf
date: 2026-$m-21
expect: charge
merchant: Vodafone
currency: EGP
amount: 1250.00
---
Vodafone Egypt
Monthly bill · Red 1000 plan
Account 1029384756
Bill period: 20/$P/2026 - 19/$m/2026

Total amount paid: L.E. 1,250.00
Paid on 20/$m/2026 by Vodafone Cash

Thank you for choosing Vodafone.
EOF

cat > "$D/instapay-ar-2026-$m.txt" <<EOF
lang: ar
kind: image
date: 2026-$m-01
expect: charge
merchant: نادي الجزيرة
currency: EGP
amount: 1500.00
---
InstaPay
تمت العملية بنجاح
تم تحويل ١٬٥٠٠ جنيه
المستفيد: نادي الجزيرة
الحساب: ****4411
التاريخ: ٢٠٢٦/$A/٠١
الرقم المرجعي: 7781230019
EOF

cat > "$D/fawry-eg-2026-$m.txt" <<EOF
lang: eg
kind: image
date: 2026-$m-15
expect: charge
merchant: Orange
currency: EGP
amount: 220.00
---
فوري
إيصال سداد
الخدمة: أورنج - فاتورة موبايل
رقم الموبايل: 01212345678
إجمالي المبلغ: ٢٢٠ جم
تم الدفع بنجاح يوم ١٥/$A/٢٠٢٦
كود فوري: 9912-4455
EOF

cat > "$D/anghami-franco-2026-$m.txt" <<EOF
lang: franco
kind: text
date: 2026-$m-08
expect: charge
merchant: Anghami
currency: EGP
amount: 49.99
---
el visa et5asam menha 49.99 geneh le Anghami Plus
eshterak shahry, tagdeed kol shahr
EOF

cat > "$D/icloud-en-2026-$m.txt" <<EOF
lang: en
kind: email
date: 2026-$m-22
from: Apple <no_reply@email.apple.com>
subject: Your receipt from Apple
expect: charge
merchant: iCloud
currency: USD
amount: 0.99
---
Receipt

iCloud+ with 50 GB of storage
Monthly subscription

Billed to: Visa •••• 4821
Total: \$0.99

This is a receipt for your payment.
EOF

cat > "$D/gym-en-2026-$m.txt" <<EOF
lang: en
kind: event
date: 2026-$m-01
subject: Gym membership
expect: charge
merchant: Gym membership
currency: EGP
amount: 800.00
---
Paid the monthly membership: EGP 800
EOF
done

cat > "$D/jumia-en-2026-07.txt" <<'EOF'
lang: en
kind: email
date: 2026-07-19
from: Jumia Egypt <no-reply@jumia.com.eg>
subject: Your order has been paid
expect: charge
merchant: Jumia Egypt
currency: EGP
amount: 1299.00
---
Hello Sam,

Your order 331245 has been paid successfully.

Order total: EGP 1,299.00
Delivery: 21 - 23 July

Thank you for shopping with Jumia.
EOF

# ---------- Expiring documents ----------
cat > "$D/national-id-ar.txt" <<'EOF'
lang: ar
kind: image
date: 2026-03-02
expect: expiry
expiry: 2028-03-14
doc: national_id
---
جمهورية مصر العربية
بطاقة تحقيق الشخصية
محمد أحمد سامي
الرقم القومي: ٢٩٠٠١٠١٠١٢٣٤٥٦
تاريخ الإصدار: ٢٠٢١/٠٣/١٥
البطاقة سارية حتى ٢٠٢٨/٠٣/١٤
EOF

cat > "$D/car-licence-ar.txt" <<'EOF'
lang: ar
kind: image
date: 2025-11-20
expect: expiry
expiry: 2026-11-12
doc: car_licence
---
وزارة الداخلية
الإدارة العامة للمرور
رخصة تسيير ملاكي
رقم اللوحة: ق ط م ٤٥٢٣
الماركة: هيونداي
تاريخ الانتهاء: ١٢/١١/٢٠٢٦
EOF

cat > "$D/driving-licence-eg.txt" <<'EOF'
lang: eg
kind: image
date: 2026-02-10
expect: expiry
expiry: 2029-02-05
doc: driving_licence
---
رخصة قيادة خاصة
الاسم: سارة محمود
صالحة لغاية ٠٥/٠٢/٢٠٢٩
وحدة مرور مدينة نصر
EOF

cat > "$D/passport-en.txt" <<'EOF'
lang: en
kind: image
date: 2021-01-15
expect: expiry
expiry: 2031-01-14
doc: passport
---
ARAB REPUBLIC OF EGYPT
PASSPORT
Type P  Code EGY  Passport No. A12345678
Surname SAMI  Given names MOHAMED AHMED
Date of issue 15 JAN 2021
Date of expiry 14 JAN 2031
EOF

cat > "$D/passport-ar.txt" <<'EOF'
lang: ar
kind: image
date: 2023-07-02
expect: expiry
expiry: 2030-07-01
doc: passport
---
جمهورية مصر العربية
جواز سفر
الاسم: منى علي حسن
تاريخ الإصدار: ٢٠٢٣/٠٧/٠٢
تاريخ الانتهاء: ٢٠٣٠/٠٧/٠١
EOF

cat > "$D/insurance-en.txt" <<'EOF'
lang: en
kind: pdf
date: 2025-12-01
expect: expiry
expiry: 2026-11-30
doc: insurance
---
Misr Insurance
Motor Insurance Policy
Policy number: MI-2025-0098812
Insured: Mohamed Sami
Period of insurance: 01/12/2025 to 30/11/2026
Annual premium: EGP 9,400.00
This policy expires on 30/11/2026. Renew before that date to stay covered.
EOF

cat > "$D/insurance-ar.txt" <<'EOF'
lang: ar
kind: pdf
date: 2026-01-01
expect: expiry
expiry: 2026-12-31
doc: insurance
---
وثيقة تأمين طبي
شركة مصر للتأمين
اسم المؤمن عليه: منى علي
تبدأ الوثيقة في ٢٠٢٦/٠١/٠١
تنتهي الوثيقة في ٢٠٢٦/١٢/٣١
EOF

cat > "$D/contract-ar.txt" <<'EOF'
lang: ar
kind: pdf
date: 2025-07-01
expect: expiry
expiry: 2027-06-30
doc: contract
---
عقد إيجار شقة سكنية
الطرف الأول (المؤجر): أحمد فؤاد
الطرف الثاني (المستأجر): محمد سامي
مدة العقد سنتان تبدأ من ١ يوليو ٢٠٢٥
وينتهي العقد في ٣٠ يونيو ٢٠٢٧
القيمة الإيجارية الشهرية: ٨٬٠٠٠ جنيه
EOF

cat > "$D/driving-licence-en-overdue.txt" <<'EOF'
lang: en
kind: text
date: 2016-05-15
expect: expiry
expiry: 2026-05-15
doc: driving_licence
---
Driving licence (scanned copy)
Name: Mohamed Sami
Licence class: B
Valid until 15/05/2026
EOF

cat > "$D/membership-ar.txt" <<'EOF'
lang: ar
kind: image
date: 2025-10-20
expect: expiry
expiry: 2026-10-20
doc: membership
---
نادي الصيد المصري
كارنيه عضوية
رقم العضوية: ١٢٠٤٥
صالح حتى ٢٠٢٦/١٠/٢٠
EOF

cat > "$D/gym-card-eg.txt" <<'EOF'
lang: eg
kind: text
date: 2026-09-01
expect: expiry
expiry: 2026-10-05
doc: membership
---
كارنيه الجيم بتاعي صالح لحد ٢٠٢٦/١٠/٠٥ فكرني أجدده
EOF

cat > "$D/car-licence-note-eg.txt" <<'EOF'
lang: eg
kind: text
date: 2026-09-10
expect: expiry
expiry: 2026-12-01
doc: car_licence
---
رخصة العربية بتخلص يوم 2026/12/01 لازم أجددها
EOF

# ---------- Everyday items: must raise nothing ----------
cat > "$D/n01-grocery-list-en.txt" <<'EOF'
lang: en
kind: text
date: 2026-09-20
expect: none
---
Shopping list
- eggs
- milk 2L
- bread
- tomatoes 1 kg
EOF

cat > "$D/n02-recipe-en.txt" <<'EOF'
lang: en
kind: text
date: 2026-09-18
expect: none
---
Koshari for 4
2 cups rice
1 cup brown lentils
1 pack macaroni
Total cost about $12 at the market
EOF

cat > "$D/n03-meeting-notes-en.txt" <<'EOF'
lang: en
kind: text
date: 2026-09-15
expect: none
---
Project sync
Deadline moved to 2026-10-15
Owner: Sara
Next: review the draft
EOF

cat > "$D/n04-newsletter-promo-en.txt" <<'EOF'
lang: en
kind: email
date: 2026-09-10
from: StreamPlus <news@streamplus.example>
subject: Everything you love, one app
expect: none
---
Subscribe now for just $9.99/month and watch everything.
Cancel anytime.
EOF

cat > "$D/n05-offer-expires-en.txt" <<'EOF'
lang: en
kind: email
date: 2026-09-20
from: Noon <deals@noon.example>
subject: Flash sale
expect: none
---
Flash sale! 30% off electronics.
This offer expires 30/10/2026. Use code SAVE30.
EOF

cat > "$D/n06-otp-en.txt" <<'EOF'
lang: en
kind: text
date: 2026-09-27
expect: none
---
Your verification code is 482913. It expires in 10 minutes. Do not share it.
EOF

cat > "$D/n07-flight-itinerary-en.txt" <<'EOF'
lang: en
kind: pdf
date: 2026-09-01
expect: none
---
EgyptAir e-ticket
Cairo (CAI) to Luxor (LXR)
Departure 12/11/2026 08:30
Seat 14C · Economy
EOF

cat > "$D/n08-salary-credit-en.txt" <<'EOF'
lang: en
kind: email
date: 2026-09-01
from: CIB <alerts@cibeg.example>
subject: Account credited
expect: none
---
Your account ending 4411 was credited with EGP 15,000.00 on 01/09/2026. Salary transfer.
EOF

cat > "$D/n09-refund-en.txt" <<'EOF'
lang: en
kind: email
date: 2026-08-02
from: Uber <receipts@uber.example>
subject: Refund processed
expect: none
---
We have refunded $4.99 to your card for trip 8812.
EOF

cat > "$D/n10-bill-due-en.txt" <<'EOF'
lang: en
kind: email
date: 2026-09-16
from: North Cairo Electricity <billing@nced.example>
subject: Your September bill is ready
expect: none
---
Your bill for September is ready.
Amount due: EGP 842.00
Please pay by 30/09/2026.
EOF

cat > "$D/n11-birthday-invite-en.txt" <<'EOF'
lang: en
kind: text
date: 2026-09-25
expect: none
---
Omar's birthday party!
Saturday 17/10/2026 at 6 pm
Bring your swimsuit.
EOF

cat > "$D/n12-holiday-photo-en.txt" <<'EOF'
lang: en
kind: image
date: 2026-08-14
expect: none
---
SHARM EL SHEIKH
Welcome to Naama Bay
2026
EOF

cat > "$D/n13-school-timetable-en.txt" <<'EOF'
lang: en
kind: text
date: 2026-09-05
expect: none
---
Grade 5 timetable
Sunday: Maths, Arabic, Science
Monday: English, PE
EOF

cat > "$D/n14-passport-article-en.txt" <<'EOF'
lang: en
kind: html
date: 2026-09-12
expect: none
---
How long is an Egyptian passport valid?
An Egyptian passport is valid for seven years for adults. You can renew it at any passport office.
EOF

cat > "$D/n15-wifi-card-en.txt" <<'EOF'
lang: en
kind: image
date: 2026-07-01
expect: none
---
Guest Wi-Fi
Network: Home_5G
Password: nile2026river
EOF

cat > "$D/n16-car-service-en.txt" <<'EOF'
lang: en
kind: text
date: 2026-09-02
expect: none
---
Car service: oil change at 45,000 km. The garage opens at 9 am.
EOF

cat > "$D/n17-whatsapp-eg.txt" <<'EOF'
lang: eg
kind: text
date: 2026-09-26
expect: none
---
هنتقابل يوم ١٥/١٠ الساعة ٨ عند الكافيه
EOF

cat > "$D/n18-recipe-ar.txt" <<'EOF'
lang: ar
kind: text
date: 2026-09-03
expect: none
---
طريقة عمل المحشي
رز مصري كوبين
طماطم ٤ حبات
بقدونس وشبت
EOF

for m in 06 07 08; do
cat > "$D/n19-vodafone-promo-ar-2026-$m.txt" <<EOF
lang: ar
kind: email
date: 2026-$m-10
from: Vodafone <offers@vodafone.com.eg>
subject: عرض خاص لك
expect: none
---
اشترك الآن في باقة فليكس ٧٠ بـ ١٢٠ جنيه شهرياً واستمتع بدقائق وإنترنت أكتر.
EOF
done

cat > "$D/n22-offer-expiry-ar.txt" <<'EOF'
lang: ar
kind: email
date: 2026-09-21
from: كارفور <offers@carrefour.example>
subject: تخفيضات نهاية الموسم
expect: none
---
تخفيضات نهاية الموسم على الأجهزة
العرض ينتهي في ٢٠٢٦/١٠/٣٠
EOF

cat > "$D/n23-bank-deposit-eg.txt" <<'EOF'
lang: eg
kind: text
date: 2026-09-01
expect: none
---
تم إيداع مبلغ ١٥٬٠٠٠ جنيه في حسابك رقم ****4411 يوم ٠١/٠٩/٢٠٢٦
EOF

cat > "$D/n24-instapay-request-ar.txt" <<'EOF'
lang: ar
kind: image
date: 2026-09-22
expect: none
---
InstaPay
طلب تحويل
أحمد يطلب منك ٢٠٠ جنيه
قبول / رفض
EOF

cat > "$D/n25-fawry-pending-eg.txt" <<'EOF'
lang: eg
kind: image
date: 2026-09-20
expect: none
---
فوري
رقم مرجعي للدفع: 7788123
المبلغ المطلوب: ١٥٠ جنيه
ادفع قبل ٢٠٢٦/١٠/٠٥ من أي منفذ فوري
EOF

cat > "$D/n26-id-article-ar.txt" <<'EOF'
lang: ar
kind: html
date: 2026-09-11
expect: none
---
تجديد بطاقة الرقم القومي
يجب تجديد البطاقة كل سبع سنوات من تاريخ الإصدار.
EOF

cat > "$D/n27-exam-schedule-ar.txt" <<'EOF'
lang: ar
kind: text
date: 2026-09-14
expect: none
---
جدول الامتحانات
امتحان العربي يوم ٢٠٢٦/١٢/٢٠
امتحان الرياضيات يوم ٢٠٢٦/١٢/٢٣
EOF

cat > "$D/n28-chat-bill-eg.txt" <<'EOF'
lang: eg
kind: text
date: 2026-09-19
expect: none
---
الفاتورة غالية أوي الشهر ده، لازم نقلل النت
EOF

cat > "$D/n29-menu-eg.txt" <<'EOF'
lang: eg
kind: image
date: 2026-09-06
expect: none
---
كشري أبو طارق
كشري صغير ٤٥ جنيه
كشري كبير ٧٠ جنيه
أرز باللبن ٣٠ جنيه
EOF

cat > "$D/n30-chat-franco.txt" <<'EOF'
lang: franco
kind: text
date: 2026-09-24
expect: none
---
ana gay el sa3a 8 ba2olak, esta2alny 3and el bab
EOF

cat > "$D/n31-bill-complaint-franco.txt" <<'EOF'
lang: franco
kind: text
date: 2026-09-23
expect: none
---
el fatoora ghalya awi el shahr da, lazem nekallem WE
EOF

cat > "$D/n32-request-franco.txt" <<'EOF'
lang: franco
kind: text
date: 2026-09-22
expect: none
---
momken te7wel 200 geneh le Ahmed el naharda?
EOF

cat > "$D/n33-offer-franco.txt" <<'EOF'
lang: franco
kind: text
date: 2026-09-21
expect: none
---
el offer bta3 Vodafone by5las bokra, eshtrek delwa2ty b 99 geneh
EOF

# ---------- The bank statement (imported through the Inbox) ----------
cat > "$T/statement.csv" <<'EOF'
Date,Description,Debit,Credit,Currency
04/06/2026,NETFLIX.COM,165.00,,EGP
10/06/2026,SHAHID VIP,99.99,,EGP
01/07/2026,SALARY TRANSFER,,15000.00,EGP
04/07/2026,NETFLIX.COM,165.00,,EGP
10/07/2026,SHAHID VIP,99.99,,EGP
15/07/2026,CARREFOUR MAADI,1234.50,,EGP
04/08/2026,NETFLIX.COM,165.00,,EGP
10/08/2026,SHAHID VIP,99.99,,EGP
EOF

ls "$D" | wc -l
```

Expected: `73` (28 charges, 12 expiring documents, 33 everyday items).

- [ ] **Step 4: Run the measurement on the JVM**

Run: the Kotlin test command with `--tests 'dev.loupe.kit.tracking.TrackingMeasureTest'`
Expected: 5 tests PASS, and the log shows per-language lines, e.g. `tracking recall · charges · en: 13/13`, `… · ar: 6/6`, `… · eg: 6/6`, `… · franco: 3/3`, `tracking recall · subscriptions · all: 10/10`, `tracking recall · expiry · ar: 6/6`, `tracking false positives · charges 0 · expiry 0 · census 0 · of 33 everyday items`. A miss is fixed in the rule (Tasks 2-6, with a unit test for the case), never by editing a label or dropping a fixture.

- [ ] **Step 5: Run it on the iOS simulator (Kotlin/Native regex)**

Run: `cd /Users/bistrocloud/Documents/Loupe/jevistication && export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home && ./gradlew :loupe-kit:iosSimulatorArm64Test --tests 'dev.loupe.kit.tracking.*'`
Expected: PASS, the same numbers (Kotlin/Native's regex engine differs from the JVM's; this is where a lookbehind or a character class would show it).

- [ ] **Step 6: Commit**

```bash
cd /Users/bistrocloud/Documents/Loupe/jevistication
git add loupe-kit/build.gradle.kts loupe-kit/src/commonTest/fixtures/tracking loupe-kit/src/commonTest/kotlin/dev/loupe/kit/tracking/TrackingFixtures.kt loupe-kit/src/commonTest/kotlin/dev/loupe/kit/tracking/TrackingMeasureTest.kt
git commit -m "kit: the labelled tracking set (EN, AR, Egyptian, Franco) and the measured bar

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
git push
```

---

### Task 9: End to end, from a source item to the rows the UI shows

**Files:**
- Test: create `loupe-kit/src/commonTest/kotlin/dev/loupe/kit/tracking/TrackingEndToEndTest.kt`

**Interfaces:**
- Consumes: `TrackingFixtures.load/statementRows`, `WatcherRun.run`, `WatcherFindings.summarise`, `WatcherFindings.monthlyByCurrency`, `CensusRow.currency/lines/itemIds`, `ExpiryRow.documentKind/line/findingKey`, `ExpiryBucket.of`.

- [ ] **Step 1: Write the tests**

```kotlin
package dev.loupe.kit.tracking

import dev.loupe.kit.watchers.WatcherFindings
import dev.loupe.kit.watchers.WatcherRun
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * One tracker at a time, from the item a source yields, through `WatcherRun`, to the `WatcherFindings` rows the Money
 * and Documents screens show, with their references (spec §7.3 "source item → watcher → Home card → detail →
 * reference"; the Home card and detail are the shell and step 4).
 */
class TrackingEndToEndTest {
    private val today = TrackingFixtures.TODAY
    private val fixtures by lazy { TrackingFixtures.load().associateBy { it.id } }
    private fun items(vararg ids: String) = ids.map { fixtures.getValue(it).item }

    @Test
    fun aNetflixReceiptAndItsStatementRowAreOneChargeWithBothReferences() {
        val mails = items("netflix-en-2026-06", "netflix-en-2026-07", "netflix-en-2026-08")
        val statement = TrackingFixtures.statementRows()
        val all = mails + statement
        val summary = WatcherFindings.summarise(WatcherRun.run(all, today, null), all, emptySet())
        val netflix = summary.census.rows.single { it.merchant == "Netflix" }
        assertEquals(3, netflix.occurrences)
        assertEquals("monthly", netflix.cadence)
        assertEquals("EGP", netflix.currency)
        assertEquals(16500L, netflix.monthlyMinor)
        val netflixRows = statement.filter { it.facts["merchant"] == "NETFLIX.COM" }.map { it.id }
        assertEquals((mails.map { it.id } + netflixRows).toSet(), netflix.itemIds.toSet(), "every receipt and every statement row")
        assertEquals(3, netflix.lines.size)
        assertTrue(netflix.lines.all { "165.00" in it }, netflix.lines.toString())
        val shahid = summary.census.rows.single { it.merchant == "Shahid" }
        assertEquals(mapOf("EGP" to 16500L + shahid.monthlyMinor!!), WatcherFindings.monthlyByCurrency(summary.census.rows))
        assertTrue(summary.census.rows.none { it.merchant.contains("SALARY") || it.merchant.contains("CARREFOUR") })
    }

    @Test
    fun calendarPhotoAndFrancoChargesReachTheCensus() {
        val all = items("gym-en-2026-06", "gym-en-2026-07", "gym-en-2026-08", "we-eg-2026-06", "we-eg-2026-07", "we-eg-2026-08",
                        "anghami-franco-2026-06", "anghami-franco-2026-07", "anghami-franco-2026-08",
                        "icloud-en-2026-06", "icloud-en-2026-07", "icloud-en-2026-08")
        val rows = WatcherFindings.summarise(WatcherRun.run(all, today, null), all, emptySet()).census.rows.associateBy { it.merchant }
        assertEquals(setOf("Gym membership", "WE", "Anghami", "iCloud"), rows.keys)
        assertEquals(listOf("Paid the monthly membership: EGP 800"), rows.getValue("Gym membership").lines.distinct())
        assertTrue(rows.getValue("Gym membership").itemIds.all { it.startsWith("calendar:") })
        assertTrue(rows.getValue("WE").itemIds.all { it.startsWith("photos:") })
        assertEquals("USD", rows.getValue("iCloud").currency)
        assertEquals(99L, rows.getValue("iCloud").monthlyMinor)
    }

    @Test
    fun anEgyptianIdIsOnTheTimelineWithItsKindAndLine() {
        val all = items("national-id-ar")
        val row = WatcherFindings.summarise(WatcherRun.run(all, today, null), all, emptySet()).expiries.single()
        assertEquals("photos:national-id-ar", row.itemId)
        assertEquals("2028-03-14", row.expiryIso)
        assertEquals("national_id", row.documentKind)
        assertEquals("البطاقة سارية حتى ٢٠٢٨/٠٣/١٤", row.line)
        assertNull(row.findingKey, "years away: a timeline row, not a warning")
        assertEquals(ExpiryBucket.LATER, ExpiryBucket.of(row.daysRemaining))
    }

    @Test
    fun aCarLicenceInsideTheRuleIsAWarningWithTheLineItWasReadFrom() {
        val all = items("car-licence-ar")
        val summary = WatcherFindings.summarise(WatcherRun.run(all, today, null), all, emptySet())
        val row = summary.expiries.single()
        assertEquals("car_licence", row.documentKind)
        assertTrue(row.breachesRule)
        val finding = summary.findings.single { it.key == row.findingKey }
        assertEquals("تاريخ الانتهاء: ١٢/١١/٢٠٢٦", finding.evidence.first())
        assertEquals("photos:car-licence-ar", finding.itemId)
    }

    @Test
    fun theWholeSetSaysNothingItShouldNot() {
        val all = fixtures.values.map { it.item } + TrackingFixtures.statementRows()
        val summary = WatcherFindings.summarise(WatcherRun.run(all, today, null), all, emptySet())
        val words = summary.findings.flatMap { it.evidence + it.title + it.why }.map { it.lowercase() }
        assertTrue(words.none { "laya" in it }, "no user-facing Laya")
        assertTrue(words.none { w -> listOf("is safe", "legitimate", "trusted", "all clear", "all-clear").any { it in w } }, words.toString())
    }
}
```

- [ ] **Step 2: Run them (JVM, then the simulator)**

Run: the Kotlin test command with `--tests 'dev.loupe.kit.tracking.TrackingEndToEndTest'`, then `./gradlew :loupe-kit:iosSimulatorArm64Test --tests 'dev.loupe.kit.tracking.TrackingEndToEndTest'`
Expected: 5 tests PASS on both. (These tests pass against Tasks 5-8 as written; if one fails, the failure is a bug in those tasks: fix the rule there with a unit test, not the expectation here.)

- [ ] **Step 3: Commit**

```bash
cd /Users/bistrocloud/Documents/Loupe/jevistication
git add loupe-kit/src/commonTest/kotlin/dev/loupe/kit/tracking/TrackingEndToEndTest.kt
git commit -m "kit: end-to-end tracking tests from source item to census and timeline rows with references

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
git push
```

---

### Task 10: The full gate, then main

**Files:** none new.

- [ ] **Step 1: The full gate**

```bash
cd /Users/bistrocloud/Documents/Loupe/jevistication
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
./gradlew check
./gradlew :loupe-kit:assembleLoupeKitDebugXCFramework
cd ios && xcodegen generate && xcodebuild test -project Loupe.xcodeproj -scheme Loupe -destination 'platform=iOS Simulator,name=iPhone 17 Pro Max' -derivedDataPath /Volumes/Sambawy/.loupe-agent-tmp/tracking-engine/DerivedData
```

Expected: `BUILD SUCCESSFUL` (JVM, iOS simulator and, where an Android SDK is present, Android targets), then `** TEST SUCCEEDED **` for the whole Loupe scheme. `GuardScenarios` and `GuardUITests` read the sample's subscriptions and the passport: if the sample's census or timeline gained a real row, the UI tests' counts come from the screen, so they must still pass; fix any failure and run the full suite again.

- [ ] **Step 2: Move main**

```bash
cd /Users/bistrocloud/Documents/Loupe/jevistication
git switch main
git pull --ff-only
git merge --ff-only tracking-engine
git push origin main
git log --branches --not --remotes --oneline
```

Expected: the merge fast-forwards and the last command prints nothing. If `main` moved (the shell plan or parity landed first): `git switch tracking-engine && git rebase main`, redo Task 7 Step 6's grep (the shell's new Swift tests construct `CensusRow`/`ExpiryRow`), and if parity landed, switch `TrackingText.foldDigits` to `PortableText.foldDigits`; then re-run Step 1 in full and repeat this step.

---

## Self-review (done while writing)

- **Spec coverage (§7.2, §7.3):** charges from email, files (PDF, photo receipts, HTML, text), statement CSVs via `CsvRows` (Inbox rows and whole files) and calendar, de-duplicated (Tasks 5, 7); money in EGP / ج.م / جنيه / LE / L.E. / E£ and £ $ €, Arabic and Persian digits, both thousands separators, kept with its currency (Tasks 1, 2, 7); EN / AR / Egyptian / Franco charge and subscription words and Egyptian merchant hints (WE, Vodafone, Orange, e&, InstaPay, Fawry, Netflix, Spotify, Anghami, Shahid …) (Task 3); EN / AR / Egyptian expiry words and Egyptian document kinds (Task 4); the full timeline with overdue / 0-7 / 8-30 / later and the six-month rule as a highlight (Tasks 6, 7); rules first, model second, every row with its reference (item ids, lines, kinds) (Tasks 5-7, 9); the labelled set with Netflix, Spotify, WE, Vodafone, InstaPay and Fawry receipts and emails in EN and AR, a bank statement CSV, a National ID, car licences, passports, insurance policies and 33 everyday items, recall ≥ 90 % and 0 false positives, per language (Task 8); end-to-end source item → watcher → rows with references (Task 9).
- **Not in this plan (spec items outside "shared Kotlin, no UI"):** the Home cards, Subscriptions / Expiring screens and reminders (step 4 UI); the check on the owner's phone (a read-only pull plus on-screen confirmation, after this ships to the phone); BL-4's statement mapper (spec: "when it lands").
- **Placeholders:** none.
- **Type consistency:** `TrackedCharge`, `ExpiryFind`, `DocumentKind.id`, `ExpiryCandidate.documentKind/line`, `WatcherReport.charges/currencyOf`, `CensusRow.currency/lines`, `ExpiryRow.documentKind`, `WatcherRun.trackedCharges/census/charges`, `WatcherFindings.monthlyByCurrency`, `TrackingItems.email/image/file/event/csvRow` and `TrackingFixtures.TODAY/load/parse/statementRows` are used with the same names and shapes in every task.
