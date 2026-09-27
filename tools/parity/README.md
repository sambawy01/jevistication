# Cross-platform parity corpus

`corpus.json` is a list of inputs and the **mechanical** answers every Loupe platform must give for
them, byte for byte: the JVM (desktop), Android (every API level from 29), iOS (Kotlin/Native), and
Loupe Station (Python, `~/laya-studio`). It exists because the platforms' regex engines and Unicode
data disagree (docs/ANDROID-PLAN.md, "Known parity gaps", B1 and B2), and a unit test on one platform
cannot see another's answer.

Where it runs:

| Where | How | Engine exercised |
|---|---|---|
| JVM | `./gradlew :loupe-kit:jvmTest` (`ParityCorpusTest`, commonTest) | `java.util.regex` |
| iOS simulator | `./gradlew :loupe-kit:iosSimulatorArm64Test` (the same commonTest) | Kotlin/Native's regex, Foundation |
| Android unit tests | `./gradlew :loupe-kit:testDebugUnitTest` (the same commonTest, host JVM) | the Android variant of the shared code |
| Android device | `tools/parity/run-device.sh` (emulator or phone; `APK=...` to reuse a built debug APK) | ICU, ART, the phone's Unicode |
| Loupe Station | to be added there: read this file, run each case, compare | Python `re`, `unicodedata` |

The evaluator is `kotlin/dev/loupe/parity/ParityCorpus.kt`, compiled into loupe-kit's commonTest and
into `:android-app`'s debug build only (`ParityCorpusMain`, started by `run-device.sh` through
`app_process`; nothing of it ships in a release APK or in LoupeKit).

## Format

```json
{
  "format": "loupe-parity-corpus",
  "version": 1,
  "unicode": "16.0.0",
  "note": "...",
  "cases": [
    {"id": "date-iso-arabic-indic", "fn": "date.find", "input": "Expires ...", "args": {...}, "expect": ...}
  ]
}
```

* `unicode`: the Unicode version of Loupe's pinned data (`PortableText.UNICODE_VERSION`); a runner
  whose data is another version must fail, not guess.
* Each case: a unique `id`, the function `fn`, the `input` string, optional `args`, and the `expect`ed
  answer as JSON. Answers are compared as JSON values (object keys unordered, arrays ordered).
* Strings are plain JSON; non-ASCII characters are written as `\uXXXX` escapes (supplementary ones as
  surrogate pairs) so invisible characters (NBSP, U+202F, U+0307) stay visible in review.
* The file is language-neutral: no Kotlin or Python syntax, and every function below is defined by
  what it returns, so Station can implement it in Python.

## Functions

Space characters (`SP`) are exactly: tab, LF, VT, FF, CR, space, U+00A0, U+2007, U+2009, U+202F.
Digits are ASCII `0-9`, Arabic-Indic U+0660-0669 and Persian U+06F0-06F9, all read as numbers; no
other script's digits are. "Letter or number" is general category L, Nd, Nl or No in the pinned data.

| `fn` | Answer |
|---|---|
| `digits.fold` | the input with Arabic-Indic and Persian digits replaced by ASCII |
| `int.parse` | the integer, or `null` unless the input is an optional sign and digits (any mix of the three scripts) |
| `space.split` | the non-empty runs between `SP` characters |
| `case.fold` | per code point, simple lowercase of simple uppercase (UnicodeData.txt fields 12 and 13): `İ`→`i`, `ı`→`i`, `ß` stays, `ς`→`σ`; same length |
| `case.lower` | simple lowercase per code point, plus `İ` → `i` U+0307; no final-sigma rule |
| `match.form` | `digits.fold` then `case.fold`: the form every keyword and baseline rule matches |
| `keyword.contains` | `args.keyword` occurs in `match.form(input)` (keyword also in match form) with no letter or number immediately before or after it |
| `nfc` `nfd` `nfkc` `nfkd` | the Unicode normal forms |
| `nfkc.casefold` | toNFKC_Casefold (NFKC_CF of each code point, then NFC) |
| `unicode32.drift` | the non-ASCII code points (as `U+XXXX`, first occurrence order) that were unassigned in Unicode 3.2 or whose IDNA mapping differs between RFC 3491 nameprep on 3.2 and the pinned mapping (below) |
| `idna.label` | the engine's IDNA ToASCII of one label: RFC 3454 table B.1 removed, then `nfkc.casefold`; ASCII result as is (≤ 63), else `xn--` + Punycode (≤ 63); `null` when the result starts with `xn--` or is too long |
| `url.host` | the host of a URL (`https://` assumed), `case.lower`ed, trailing dot removed; `null` unless it is letters/numbers then letters/numbers/`.-_` |
| `host.registrable` | the registrable domain under the pinned Mozilla PSL (ICANN + PRIVATE), in the form given |
| `host.mixed_scripts` | a label mixes Unicode scripts (Han+Kana and Han+Hangul allowed) |
| `date.find` | every date found: `text` (as written), `date` (ISO), `pattern` (`iso`, `numeric-dmy`/`-mdy`, `textual-dmy`/`-mdy`), `ambiguous`, `alternate`; `args.dayFirst` (default true) |
| `date.cell` | a CSV cell's date (`YYYYMMDD`, `D/M/YY` too), or `null` |
| `number.parse` | minor units of a written number (1,234.56 / 1.234,56 / `SP`-grouped / Arabic separators), `null` when ambiguous |
| `amount.parse` | `{minor, currency}` of a statement cell (markers like `ج.م`, `EGP`, `€`) |
| `terms.amounts` | labelled amounts (`label: £12.50`), key = last two words of the label, lowercased |
| `regex.translate` | a user's baseline regex made portable (`{"pattern": ...}`), or `{"error": true}` when it uses `\p{..}`, `\X`, `\R`, `\h`, `\v`, `\N`, or `\D \S \W \b \B` inside `[...]` |
| `baseline.pattern` | the translated pattern (input) finds a match in `match.form(args.text)` |
| `baseline.keyword` | any of `args.keywords` is found as `keyword.contains` finds it |
| `link.host` | the host a browser opens: WHATWG URL parsing for http(s) (`\\` is `/`, percent-decoded host, `。．｡` are dots) and UTS #46 **non-transitional** ToASCII (`ß`, `ς`, ZWJ, ZWNJ kept). Every expected host was checked in Chrome 153 (`new URL(u).host`). Python's `idna` codec is IDNA 2003 / transitional and must not be used for this |
| `page.check` | as `link.check` for a page; `args.password` adds a password form, `args.knownGood` known-good domains |
| `link.host_signals` | the codes of the phishing formula's host checks (docs/PHISHING-FORMULA.md §4), in order |
| `link.check` | `{level, codes}`: the site check's level and the codes that carry weight, sorted |
| `mail.check` | `{level, codes}` of the mail phishing check for a From header (input), `args.body`, `args.replyTo`, `args.trusted`, `args.links` (`[[href, visible text], ...]`, the message's anchors); codes include the zero-weight `known_sender` / `trusted_sender` |
| `mail.link_targets` | the links of a message worth an online check (`Phishing.linkTargets`): input is the sender's registrable domain (`""` for none), `args.body`, `args.links` |
| `unicode.disguised` | the stand-in code points of a label (`U+XXXX`): NFKC is not itself, a default ignorable, or a joiner CONTEXTJ rejects |

## Adding a case

Add a line to `cases` (keep `id`s unique), run `./gradlew :loupe-kit:jvmTest --tests '*ParityCorpusTest'`,
then the iOS simulator and `run-device.sh` on the API 29 and newest emulators. A case whose answer
changes an existing platform's behaviour gets a line in docs/BUILD.md ("Parity B1/B2").
