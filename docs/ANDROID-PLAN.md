# Loupe for Android — plan

Status: **in development, in parallel with iOS** (owner, 2026-09-26). The Loupe Station session builds it on branch `android` (a worktree on /Volumes/Sambawy); changes to shared modules merge to `main` only after review and a green iOS suite.
*History:* from 2026-09-25 to 2026-09-26 Android was on hold until every iPhone feature worked on a real device; the owner lifted the hold on 2026-09-26.
It follows the iOS route (epic #6 / #7): the same Kotlin Multiplatform core, a native UI and the same
model as the iPhone app and Loupe Station, aiming at the same answers. The gaps found at A0 are
closed; see "Known parity gaps" below.

## Decisions

| Topic | Decision |
|---|---|
| Package name | `com.loupeai.android` (Android package names cannot contain `-`) |
| UI | Jetpack Compose, dark neon theme matching `ios/Loupe/Design/Theme.swift` |
| Shared code | Add an `androidTarget()` to `engine`, `templates`, `persistence`, `sources-common`, `game`, `backend-laya-common`, `loupe-kit`. No logic forks: anything the iPhone does in Kotlin, Android reuses as is |
| Model runtime | ONNX Runtime for Android (`onnxruntime-android`), the **same INT8 graph, tokenizer and SHA-256 pins** as iOS and Station; a new `backend-onnx-android` module mirroring `backend-onnx-ios` |
| Tokenizer | The Kotlin tokenizer used on iOS (no JNI Rust library), so the tokens match byte for byte |
| Model delivery | The consented download from the same hosted URL, verified by SHA-256, as on iOS |
| Min SDK | Android 10 (API 29); target the current API level Play requires |
| Rules first | `rules_first` on, the same gates as iOS (duplicates, transaction evidence) |
| Online helpers | As PRODUCT.md §4a: fetch-only, off by default, labelled Online, bring your own key |

## Platform mapping (from BUILD.md §0, Android column)

| Feature | Android API |
|---|---|
| Files (B1) | Storage Access Framework, persisted URI permissions, share target |
| Mail (B2) | Gmail REST with `gmail.readonly` via Google Sign-In / AppAuth (Android OAuth client); IMAP with an app password as on iOS |
| Photos (B4) | MediaStore + ML Kit text recognition (on-device) |
| Calendar, contacts (B5) | CalendarContract, ContactsContract |
| Notifications (B8) | NotificationListenerService (opt-in; iOS cannot do this) |
| Web tab | WebView + the same helper templates |
| Passive mode (F1) | WorkManager with charging, idle and thermal constraints |
| Actions (E1–E4) | AutofillService, App Actions, share intents |
| Game (F5) | Compose Canvas or SurfaceView driving `:game`; haptics via `VibrationEffect`, fired once per event (the iOS fix) |
| Mascot | Filament or SceneView glTF, one geometry per instance (the iOS double-free lesson) |

## Milestones

| # | What | Done when |
|---|---|---|
| A0 | Android targets in the KMP modules, `:android-app` skeleton, CI builds an APK | `./gradlew check` green with Android unit tests. **Done 2026-09-26** (branch `android`; record below) |
| A1 | `backend-onnx-android` + parity | The 34+8 parity vectors match iOS and JVM exactly on an emulator and on a real device; p50/p95 latency and memory recorded on a mid-range phone |
| A2 | Model download with consent + SHA-256 | Download, verify, resume, delete from settings |
| A3 | Now, Judgments, Unsure queue, Ledger, Model settings | Same screens and strings as iOS; UI tests on an emulator |
| A4 | Sources B1, B2, B4, B5 (+ B8 notifications) | Each source scans the sample and a real device |
| A5 | Watchers, sweep, passive mode (WorkManager) | Runs in the background under constraints; battery measured |
| A6 | Web tab and helpers | Same templates as iOS |
| A7 | Game and mascot | 60 fps on a mid-range device, gates identical to iOS |
| A8 | Third-party notices for the Android set | BUILD.md risk 11 closed for Android |
| A9 | Play internal testing release | Signed AAB on the internal track; privacy policy, data-safety form |

## How to build Android

On the Mac mini (paths are the owner's; any Android SDK works):

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=/Volumes/Sambawy/toolchains/android-sdk ANDROID_SDK_ROOT=$ANDROID_HOME
export GRADLE_USER_HOME=/Volumes/Sambawy/gradle-home      # keeps Gradle caches off the internal disk
echo "sdk.dir=$ANDROID_HOME" > local.properties           # gitignored; one per checkout

./gradlew :android-app:assembleDebug        # android-app/build/outputs/apk/debug/android-app-debug.apk
./gradlew check                             # JVM + iOS simulator + Android unit tests + Android lint
tools/android-regex-check/check.sh          # every shared regex literal compiled by Android's ICU, on a device
tools/parity/run-device.sh                  # the cross-platform parity corpus on a device (debug APK)
tools/android-api-check/check.py            # no API above minSdk 29 in our compiled Android classes
python3 tools/unicode/gen_unicode_tables.py # regenerate the pinned Unicode table (engine), from the UCD
```

The SDK needs `platforms;android-36`, `build-tools;35.0.0` and `platform-tools`; the emulator smoke
test also needs `emulator` and `system-images;android-35;google_apis;arm64-v8a`:

```sh
export ANDROID_AVD_HOME=/Volumes/Sambawy/android-avd
avdmanager create avd -n loupe-a0-api35 -k "system-images;android-35;google_apis;arm64-v8a" -d pixel_6
emulator -avd loupe-a0-api35 -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect &
adb wait-for-device && adb install -r android-app/build/outputs/apk/debug/android-app-debug.apk
adb shell am start -W -n com.loupeai.android/.MainActivity && adb exec-out screencap -p > home.png
adb emu kill
```

**Build choices.** AGP **8.9.3**: API 36 (Play's target level for new apps and updates since
2026-08-31) needs AGP 8.9.1 or newer, and 8.9 needs Gradle 8.11.1 or newer (the wrapper is 8.14.3).
Kotlin 2.1.0 is tested by JetBrains up to AGP 8.7.2 only; the gate below is the evidence that 8.9.3
works with it. `compileSdk`/`targetSdk` 36, `minSdk` 29, Java 17 bytecode for Android. The app uses
Jetpack Compose from the 2024.12.01 BOM (Compose 1.7, the line Compose Multiplatform 1.7.3 is built
on) and `activity-compose` 1.9.3; newer AndroidX lines are built with newer Kotlin.

**Source sets.** Each shared module has an `androidTarget()`. Where the JVM actuals already suit
Android, they moved from `jvmMain` to a `jvmCommonMain` source set shared by `jvm` and `android`
(persistence, sources-common, game, loupe-kit), so there is one implementation, not two. They now use
only APIs Android has at API 29 (`Paths.get`, `Collectors.toList`, a strict UTF-8 decode in place of
`Files.readString`, which Android lacks). The engine's Android actuals follow iOS instead of the JVM:
the portable IDNA, script table and number formatter, and the embedded Public Suffix List, so these
do not follow the phone's Unicode data or locale (`"%.2f"` prints Arabic-Indic digits on an
Arabic-locale phone). SHA-256 (`MessageDigest`) and the regex engine come from the platform; NFKC and
IDNA no longer do (pinned Unicode 16.0 data), and shared regexes spell their classes out so ICU,
the JDK and Kotlin/Native read them alike (see "Known parity gaps", closed).

**Builds without an Android SDK.** `settings.gradle.kts` looks for an SDK as the Android Gradle
Plugin does: `sdk.dir` in `local.properties`, else `ANDROID_HOME`, else `ANDROID_SDK_ROOT`; the
first one set decides, and a path that is not a directory counts as no SDK. Without one it
prints `WARNING: Android SDK not found (...): Android targets skipped`, leaves out `:android-app`,
and the shared modules apply neither the Android library plugin nor `androidTarget()`; `./gradlew
check` then builds and tests the JVM and iOS targets as before (the iOS session's Mac, a Linux box).
`LOUPE_REQUIRE_ANDROID=1` turns the skip into a build failure; CI sets it, so CI never skips Android
silently. *IDE sync:* Android Studio / IntelliJ sync with the environment the IDE was started with,
so with no `local.properties` and no `ANDROID_HOME` in that environment the project syncs without
the Android targets and `:android-app`; Android Studio writes `local.properties` with `sdk.dir` on
first open, after which a re-sync picks Android up.

**Traps found at A0.**

1. *Android's regex engine is ICU, not the JDK's.* `Regex("""\{([a-z][a-z0-9_]*)}""")` (templates,
   `Template.kt` and `Baseline.kt`) compiles on the JDK and Kotlin/Native but throws
   `PatternSyntaxException` on Android: ICU rejects a bare `}`. The app crashed on first launch in the
   emulator while every unit test was green, because Android unit tests run on the host JDK. Fixed by
   escaping it (`\}`, the same pattern everywhere). `tools/android-regex-check/check.sh` compiles all
   138 statically known regex literals on a device (133 before `Baseline.Pattern(…)` literals were
   included), with the flags Kotlin passes for their `RegexOption`s; 10 built at run time were
   reviewed by hand (`Regex.escape` → `\Q…\E`, which ICU accepts; digit prefixes; `\d{4}`; the
   user's own baseline patterns from `judgments.json`, validated at load). `extract.py` finds
   `Regex(…)`, `"…".toRegex(…)`, `Pattern.compile(…)` and `Baseline.Pattern(…)` in code (not comments or strings) in the shared modules' `commonMain`,
   `jvmCommonMain` and `androidMain` and in `:android-app`. A regex that compiles is not yet one that
   matches the same text: see "Known parity gaps", B1.
2. *Lint does not look at `jvmCommonMain`.* A planted `Path.of` (API 34) passed `lint`. The
   bytecode check `tools/android-api-check/check.py` catches it (it reads every class our Android
   build compiled and looks each JDK/Android reference up in the SDK's `api-versions.xml`: method
   and field references on their owner, and class references, i.e. `is`/`as`, catch clauses, class
   literals and supertypes, on the class). CI runs it after the build.

**API note for iOS (A0 fix).** `Baseline.Pattern` now compiles its regex when it is constructed and
throws `IllegalArgumentException` with the pattern and the engine's reason; the new
`Baseline.Pattern.problem(pattern)` (`String?`, null when the pattern compiles) is new public API in
LoupeKit, additive only: in Swift `BaselinePattern.companion.problem(pattern:)` (header checked).
Swift callers need no change. The constructor threw on a bad pattern before too (without
`@Throws`, so a Swift caller should ask `problem(pattern:)` first rather than construct blindly).

## Known parity gaps

**B1 and B2 are closed** (2026-09-27, branch `parity`; record below). Android, iOS and the JVM now give
the same mechanical answers, pinned by a shared corpus (`tools/parity/corpus.json`, 374 cases) that
runs in `commonTest` (JVM, iOS simulator, Android unit tests) and on the API 29 and API 35 emulators
(`tools/parity/run-device.sh`). Loupe Station is to be made to pass the same file (its format:
`tools/parity/README.md`). Every answer that changed for the iPhone or the desktop is listed in
docs/BUILD.md, "Parity B1/B2: changed answers". No gap is open.

The measurements that opened them, kept for the record (`/Volumes/Sambawy/loupe-android-evidence/a0-fix/parity-probe-*.txt`;
the Kotlin/Native column was measured at the fix, `parity-probe-kotlin-native-ios-sim.txt`):

| Probe | JDK 21 | Kotlin/Native (iOS) | Android API 29 | Android API 35 |
|---|---|---|---|---|
| `\d` finds Arabic-Indic `٣` (U+0663) | no | no | **yes** | **yes** |
| `\s` finds NBSP (U+00A0) | no | no | **yes** | **yes** |
| `\w` finds `é` (U+00E9) | no | no | **yes** | **yes** |
| `caf\b` finds a boundary inside `café` | yes | **no** | **no** | **no** |
| `\p{Alpha}` finds `é` | no | no | **yes** | **yes** |
| IGNORE_CASE `ss` finds `ß` (and `ß` finds `SS`) | no | no | **yes** | **yes** |
| IGNORE_CASE `i` finds `İ` (U+0130), and back | yes | yes | **no** | **no** |
| NFKC of U+1E030 | `а` (U+0430) | (Foundation) | unchanged | `а` |

**B1, how it was closed (owner's decisions).** Shared main sources no longer use `\d \w \s \b \D \W
\S \B \p{..}` or any case-insensitive flag; `tools/android-regex-check/lint.py` fails `check` if one
comes back (allowlist with a justification per entry). The engine's `Rx` spells the classes out and
`PortableText` does the rest in code:

- *Digits:* ASCII, Arabic-Indic (U+0660–0669) and Persian (U+06F0–06F9) digits are numbers on every
  platform (owner's decision): text is digit-folded (`PortableText.foldDigits`, same length) before
  dates, amounts and IDs are parsed; no engine `\d`, no `toInt` on other scripts.
- *Spaces:* one set everywhere, `Rx.SP`: ASCII whitespace + U+00A0, U+2007, U+2009, U+202F. Machine
  syntax (MIME headers, mbox, RFC 2047, HTML tags, DMARC records) uses `Rx.ASCII_SP`, as before.
- *Words:* ASCII rules use `[A-Za-z0-9_]` boundaries written as lookarounds (the JDK's `\b`, now also
  on iOS); keyword rules use letters and numbers from the pinned Unicode data (`containsWord`);
  `\p{..}` classes became code or a same-length "shadow" string (`PortableText.shadow`, `Rx.LN`).
- *Case:* no IGNORE_CASE or `(?i)` (on ICU even a pure-ASCII pattern folds `ss`/`ß` and `st`/`ﬆ`):
  text is folded with the pinned simple fold `lower(upper(c))` (`PortableText.fold`, same length;
  `İ`→`i`, `ı`→`i`, `ß` stays `ß`), or ASCII case is spelled out (`[hH][rR][eE][fF]`) where a captured
  value must keep its case. User baselines (`Baseline.Pattern` from judgments.json) are compiled
  through `PortableRegex.translate`, which does the same to a pattern and refuses what it cannot
  make portable (`\p{..}`, `\X`, ...).
- *No leading lookbehinds:* Kotlin/Native evaluates a lookbehind in O(position), so the portable
  `\b` before a word is checked in code (`BoundedRegex`) or consumed (translated baselines); a
  performance test bounds the patterns on 50,000 characters on every target.

**B2, how it was closed.** One Unicode table for every platform, generated from the Unicode **16.0**
UCD by `tools/unicode/gen_unicode_tables.py` (sources and SHA-256 recorded in the generated
`UnicodeDataTable.kt`; verified at generation against NormalizationTest.txt and the NFKC_Casefold
property, and in a jvmTest against the JDK on every code point it knows). 16.0 is the newest version
both browsers implement: Chrome stable (155) ships ICU 78.2, Unicode 17.0; Apple's latest published
ICU is 76.1, Unicode 16.0, and 16.0 mappings are unchanged in 17.0 by the stability policy. IDNA
(the engine's registrable-domain lookup and the site check's host mapping), NFKC/NFKD, case folding
and the letter/number/mark categories all read it; `java.net.IDN`, `java.text.Normalizer`, ICU and
Foundation are no longer used for them; the site check and the registrable-domain lookup share one
mapping, UTS #46 non-transitional as browsers resolve it (fix loop 2). Phishing signals read how a host is written (docs/PHISHING-FORMULA.md §4, §6.2):
`disguised_host` (stand-in letters such as `ｐａｙｐａｌ.com`, which maps to paypal.com; mail senders
written with non-ASCII are never known or trusted) and `unicode_drift_host` (a character that IDNA 2003
nameprep, passing unassigned characters through, and the pinned mapping map differently: about 5,600
code points, not new scripts or emoji).

*Platform Unicode data still read* (not regex, not a parity gap for characters older than Unicode 11):
18 `Char.isLetter / isLetterOrDigit / isWhitespace / digitToIntOrNull` calls in shared code, listed by
`python3 tools/android-regex-check/lint.py --platform-data` and counted on every run (text-presence
heuristics, ZIP path checks, IBAN letters, DNS wire labels already ASCII-checked). Hosts' IP checks now
take ASCII digits only.

## A0 record (2026-09-26)

Evidence lives in `/Volumes/Sambawy/loupe-android-evidence/` (logs, screenshots, logcat).

| Gate (from `clean`, `--no-build-cache`) | Result | Time |
|---|---|---|
| `./gradlew check` | green | 2 min 7 s (origin/main without Android, fresh clone: 2 min 31 s) |
| `./gradlew :android-app:assembleDebug` | green | 6 s after `check` |
| `./gradlew :loupe-kit:assembleLoupeKitDebugXCFramework` | green (backend-onnx-ios skipped, as without ios-native/build.sh) | 26 s |
| `tools/android-regex-check/check.sh` (API 35 emulator) | 133 compiled, 0 rejected | — |
| `tools/android-api-check/check.py` | 661 classes, 0 problems at minSdk 29 | — |
| Android lint, all eight Android modules | no issues | (part of `check`) |

| Tests in `check` | JVM | iOS simulator | Android debug | Android release |
|---|---|---|---|---|
| engine | 294 | 289 | 294 | 294 |
| templates | 30 | 30 | 30 | 30 |
| persistence | 9 | 7 | 7 | 7 |
| sources-common | 39 | 39 | 39 | 39 |
| game | 109 (5 skipped: no model) | 90 | 90 | 90 |
| backend-laya-common | 4 | 4 | 4 | 4 |
| loupe-kit | 212 | 212 | 212 | 212 |
| android-app | — | — | 6 | 6 |
| **total** | 697 | 671 | 682 | 682 |

Android unit tests run the whole `commonTest` suite on the host JVM against the Android variants
(`androidUnitTest` depends on `commonTest`), plus `engine`'s `PlatformAndroidTest` (the Android
actuals: SHA-256 vectors, the embedded PSL against its recorded SHA-256, IDNA, scripts, ASCII
digits under an `ar-EG` locale) and the app's `HomeSummaryTest` and `PaletteTest`. JVM-only
suites (engine `PlatformParityTest`, game's model tests, persistence `GsonParityTest`) stay in
`jvmTest`.

**Emulator** (Pixel 6 AVD, `android-35` `google_apis` arm64): the debug APK installs, launches cold
in about 0.8 s, logs `shared engine ok: 55 templates, PSL 2026-09-21_18-50-07_UTC, links [safe,
caution, danger]`, and logcat has no crash. Screenshots `a0-home.png`, `a0-home-scrolled.png`; the
R8 release build, signed with the debug key for the test only, runs the same (`a0-home-release.png`).

**Size.** Debug APK 25.34 MB (25,339,789 bytes; AGP stores debug APKs uncompressed and unshrunk).
Release APK with R8 and resource shrinking: **1.9 MB** (unsigned). No model, no native code beyond
Compose's 10 KB `libandroidx.graphics.path.so`, no dependency beyond Compose, `activity-compose` and
the shared modules.

**CI.** `.github/workflows/ci.yml` now also runs on pushes to `android`; its Linux job's
`./gradlew build` (with `LOUPE_REQUIRE_ANDROID=1`) builds, tests and lints the Android modules, then
`tools/android-api-check/check.py` checks the compiled classes at minSdk 29, and the debug APK is
uploaded as the `loupe-android-debug-apk` artifact. The `android-emulator` job runs
`tools/android-regex-check/check.sh` on an API 29 emulator (reactivecircus/android-emulator-runner),
nightly and on manual dispatch, not on every push.

**Memory: whole-file reads.** `PlatformFiles.readText` (persistence, `jvmCommonMain`) reads the file
into a byte array, decodes it strictly into a `CharBuffer` and copies that into a `String`: at the
peak about five times the file's size in memory (N bytes + 2N for the chars + up to 2N for the
string). The ledger and corrections files are read whole this way, as on desktop. That is fine at A0
sizes; before A5 (passive mode, a growing ledger) it should read line by line, and
`SourceFs.readBytes` likewise holds each source file whole.

**Debug signing certificate** (this Mac's `~/.android/debug.keystore`), for the Google OAuth Android
client (item 2 below): SHA-1 `E1:70:ED:F1:BC:46:F8:29:E8:E3:D8:F0:1E:49:3B:60:53:F3:E0:65`.

**Warnings left.** Gradle: `Retrieving attribute with a null key` appears only once AGP is
applied (it is not on origin/main; not traced further); the other two notices (`addCandidate` from the
XCFramework block, `Task.project at execution time`) are on origin/main too. AGP 8.9.3 is above
Kotlin 2.1.0's tested AGP range (see Build choices). The app uses the platform's default fonts;
Rajdhani and JetBrains Mono (iOS) are not bundled yet. `./gradlew build` (not part of the gate)
fails on macOS at the root `:commonizeNativeDistribution` ("no repositories are defined"); origin/main
fails the same way, and CI runs `build` on Linux, where that task does not exist. A root
`repositories { mavenCentral() }` gets past that task, but `build` then fails at
`:sources-common:compileCommonMainKotlinMetadata`: `RegexOption.DOT_MATCHES_ALL` in `Text.kt`
(commonMain) is not in the common standard library. That failure is independent of Android (it
happens with the Android targets skipped too) and is shared code, so it is left for a shared fix and
the root block was not added.

**Left for later milestones.** No Android `actual` is stubbed: every `expect` has a full
implementation. The phone's own PDF and image readers (`PlatformExtractors`) come with A4; A0's unit
tests use the desktop readers on the host JVM, as `jvmTest` does. The API check runs in CI after the
build; the regex check needs a device, so it runs in CI's nightly emulator job, and by hand.

## A0 fix record (2026-09-26, after review)

Evidence: `/Volumes/Sambawy/loupe-android-evidence/a0-fix/`.

| Gate | Result |
|---|---|
| `./gradlew clean check --no-build-cache`, with the SDK | green, 2 min 20 s; 2,852 tests, 0 failed, 24 skipped (no model) |
| the same in a copy with no SDK (`ANDROID_HOME`/`ANDROID_SDK_ROOT` unset, no `local.properties`) | green, 1 min 50 s; the warning line printed; 1,476 JVM and iOS-simulator tests, 0 failed, 24 skipped |
| the same copy with `LOUPE_REQUIRE_ANDROID=1` | fails at settings, as intended |
| `:android-app:assembleDebug assembleRelease` | green; debug 25,339,789 bytes, release 1,871,827 bytes (unsigned) |
| `:loupe-kit:assembleLoupeKitDebugXCFramework` | green, 30 s |
| `tools/android-api-check/check.py` (api-versions.xml of platform 36 and of 35) | 663 classes, 0 problems at minSdk 29 |
| `tools/android-regex-check/check.sh`, API 29 (`google_apis` arm64-v8a) and API 35 emulators | 133 compiled, 0 rejected, on both; after the second round (with `Baseline.Pattern(…)` literals, IGNORE_CASE) 138 compiled, 0 rejected, on both, `NEEDS_REPLY` included |
| the failure path: a planted `Regex("""\{([a-z]+)}""")` on API 29 | `FAIL … Syntax error in regexp pattern near index 11`, `138 compiled, 1 rejected`, exit status 1 (the plant was removed) |
| Launch smoke test: debug on API 29 and 35, R8 release (signed with the debug key for the test) on 35 | cold start 0.5 to 0.8 s, `shared engine ok … links [safe, caution, danger]`, no crash in logcat |

The merged manifest (`aapt2 dump xmltree`, debug and release) keeps `androidx.startup`'s lifecycle
and profile-installer initializers and no longer has `EmojiCompatInitializer`, so nothing asks Google
Play services' font provider for an emoji font; emoji use the phone's system font.

## Parity record (2026-09-27, branch `parity`)

Evidence: `/Volumes/Sambawy/loupe-android-evidence/parity/`. Before = 79e79c7 (the A0 fix), measured the same way.

| Gate | Result |
|---|---|
| `./gradlew clean check --no-build-cache`, with the SDK | green, 2 min 50 s; 2,962 tests, 0 failed, 24 skipped (no model) |
| the same in a clone with no SDK | green, 3 min 10 s; the warning printed; 1,534 JVM and iOS-simulator tests (1,476 before), 0 failed; with `LOUPE_REQUIRE_ANDROID=1` it fails at settings |
| `:loupe-kit:assembleLoupeKitDebugXCFramework`, release XCFramework, `:android-app:assembleRelease` | green |
| `tools/parity/run-device.sh`, API 29 and API 35 emulators | 182 cases, 0 differ, on both (a planted wrong answer: `1 differ`) |
| `tools/android-regex-check/check.sh`, API 29 and API 35 | 126 compiled, 0 rejected, on both (138 before: splits, keyword and word regexes became code) |
| `tools/android-regex-check/lint.py` (in `check`) | 0 findings; a planted `Regex("""\d+""", IGNORE_CASE)` fails `:engine:portableRegexLint` with both findings |
| `tools/android-api-check/check.py` | 681 classes, 0 problems at minSdk 29 |

| Tests | JVM | iOS simulator | Android debug unit |
|---|---|---|---|
| engine | 294 → 317 | 289 → 306 | 294 → 311 |
| loupe-kit | 212 → 221 | 212 → 221 | 212 → 221 |
| all KMP modules | 702 → 734 | 676 → 702 | 681 → 707 (+ 7 app) |

**Size.** Release APK 1,871,827 → 1,953,747 bytes (+80 KB; `classes.dex` +77 KB, of which the
Unicode table's strings are about 70 KB; the APK stores dex uncompressed, the table deflates to
21 KB). LoupeKit arm64 (release): +257 KB of code and data per slice (the table's strings, UTF-16 in
Kotlin/Native: +155 KB `__DATA,__const`; code +87 KB), +96 KB DWARF (not shipped in an App Store
binary). The table covers NFC/NFD/NFKC/NFKD, NFKC_Casefold, simple case folding and lowercase,
general-category groups and the Unicode 3.2 drift set.

**Fix loop after review (2026-09-27, same branch).** Evidence: `.../loupe-android-evidence/parity-fix/`.
Mail senders written with non-ASCII are never known or trusted and new `disguised_host` signals read
stand-in letters (a spoofed `"PayPal" <service@ｐａｙｐａｌ.com>` went danger 85 → safe 0 → danger 85);
drift was redefined (879,549 → 5,598 code points; Burmese, Malayalam, emoji, new CJK and Arabic
Extended are clean; no longer an impostor code); `PortableRegex.translate` decodes escapes and keeps
class ranges; the card-cue search reads a short window (6.6 s → 0.6 s on 48,000 characters on the iOS
simulator); one IDNA mapping. Gates: `clean check` green, 2,982 tests (2,962 before), 0 failed; no-SDK
clone green, 1,544 tests; corpus 332 cases, 0 differ on API 29 and 35 and the iOS simulator; ICU check
125 compiled, 0 rejected on both; API check 685 classes, 0 problems; the performance test checks each
pattern is linear (n vs 4n) on every target. Release APK unchanged (1,953,747 bytes); LoupeKit arm64
release +59 KB over the first parity build.

**Fix loop 2 (2026-09-27, same branch).** Evidence: `.../loupe-android-evidence/parity-fix2/`. Loupe now
judges the host the browser opens: UTS #46 non-transitional (new engine `Uts46`, verified on the
1,214 error-free IdnaTestV2.txt answers; 37 URLs equal Chrome 153's `new URL().host`, `browser/`),
WHATWG host parsing for http(s) (backslash, percent-decoding, `。．｡`); the IDNA 2003 reading only
feeds `deviation_host` (10) and `deviation_known_host` (45, impostor). Stand-ins are code points whose
NFKC is not themselves; senders are read literally too, and none scores below origin/main
(HostReadingTest). Gates: `clean check` green, 3,018 tests (2,982 before), 0 failed; no-SDK clone
green, 1,562 tests; corpus 374 cases, 0 differ on API 29, API 35, the iOS simulator and the JVM (a
planted wrong answer fails on the iOS simulator: `ios-planted-fault.log`); ICU check 126 compiled,
0 rejected on both; API check 687 classes, 0 problems; the card-cue fuzz (20,000 texts, seed 7)
matches the pre-fix output line for line. Release APK 1,970,131 bytes (+16 KB: the UTS #46 and joining
tables); LoupeKit arm64 release +181 KB.

**Fix loop 3 (2026-09-27, same branch).** Evidence: `.../loupe-android-evidence/parity-fix3/`. URLs are
split as WHATWG splits them: C0 controls and spaces stripped at both ends; a special scheme always has an
authority, so a mail link `https:\\x`, `https:/x`, `https:x` or `HTTPS:x` is judged as host x (and offered
for the online check; only a bare host gets `http://`); userinfo ends at the last `@` (brackets there are
ordinary characters); a host no browser opens (`%40`, `%00`, `%09`, `%20`) is `unreadable_url` /
`link_unreadable` (30), never safe-empty; `https://evil.com\@paypal.com` keeps `userinfo_in_url`. The
ideographic, full-width and halfwidth full stops in a sender or reply address are
`sender_disguised_domain` / `reply_to_impostor` and never trusted. To stay at or above origin/main,
`disguised_host` and `link_disguised` are 45 and `disguised_host` is an impostor code; `．`, `｡` and a
percent-encoded host are stand-ins. The round-3 probes: 308 rows (61 of them B1/B2/B5/S1), none below
origin/main. corpus.json is now an input of every loupe-kit test task (a corpus-only edit re-runs them).
Gates: `clean check` green, 3,030 tests (3,018 before), 0 failed; no-SDK clone green, 1,568 tests
(1,562), `LOUPE_REQUIRE_ANDROID=1` refuses; corpus 446 cases (374), 0 differ on API 29, API 35, the iOS
simulator and the JVM (a planted wrong answer fails on the iOS simulator: `ios-planted-fault.log`); ICU
check 126 compiled, 0 rejected on both; API check 688 classes, 0 problems; every performance pattern
linear. Release APK 1,970,131 bytes (unchanged); LoupeKit arm64 release 30,249,896 bytes (+42 KB).

**Fix loop 4 (2026-09-27, same branch).** Evidence: `.../loupe-android-evidence/parity-fix4/`. Legitimate
mail stays safe. A URL in running text ends at ideographic, full-width or Arabic punctuation and `|`,
and a `。．｡` in a text URL's host is a dot only before a listed TLD (the CJK rows scored up to 80
danger); `unreadable_url` / `link_unreadable` need an explicit web scheme and a non-empty, non-dot host
with a forbidden code point (DEL included; `[name]` too), so relative hrefs, template tags and empty
hosts are never flagged; a relative href (anything not starting with a letter or digit, a no-break
space included) opens no host; a stand-in is a code point whose NFKC differs from its NFC (plus the
Kelvin, Ohm and Ångström signs, ignorables and CONTEXTJ), so canonical-only Indic and Greek letters are
safe; a percent-encoded host is a stand-in only when it decodes to a known or brand domain, and is not
counted again on an unreadable host; `file:` hosts follow WHATWG. Every test resource read from disk
is a test-task input (a planted fault in phishing-vectors.json fails; a sample-dir edit re-runs
loupe-kit and sources-common). Round-4 legitimate set: 181 rows, none higher than origin/main except
eight ß/ς/ZWNJ names with the intended deviation note (+10, safe); new LegitimateMailTest pins it.
Round-3 set: 308 rows, none lower. Gates: `clean check` green, 3,038 tests (3,030), 0 failed; no-SDK
1,572 (1,568); corpus 518 cases (446), 0 differ on API 29, API 35, the iOS simulator and the JVM
(planted fault fails on iOS); ICU 127 compiled, 0 rejected on both; API check 688 classes, 0
problems; performance linear. Release APK 1,970,131 bytes (unchanged); LoupeKit arm64 30,313,336
bytes (+63 KB).

**Fix loop 5 (2026-09-27, same branch).** Evidence: `.../loupe-android-evidence/parity-fix5/`. Links are read as
browsers and linkifiers read them, checked by a **differential host-parity fuzz** (`tools/parity/fuzz/`,
`HostParityFuzzTest` in commonTest): 3,600 generated hrefs and text lines, pinned with Chrome 153's
hosts (a web client's https base and Apple Mail's x-msg base) and linkify-it 5.0.0's and
NSDataDetector's links. The fuzz found 1,483 cases at 9e777be where Loupe judged another host and said
safe; there are 0 now, on the JVM, Android and the iOS simulator. 58 more are "over-judged": the browser
opens no host, but Loupe judges the one written in the href (scheme-less, behind a no-break space).
Hrefs: HTML character references decoded (full WHATWG table, `tools/html/gen_entities.py`), `<a>`
tokenised whole (no `data-href`, quoted `>`, unclosed anchors), tabs and newlines out of the scheme
first, scheme-relative `\\` `/\` `\/` `///`, invalid ports and `file:` credentials unreadable, a trailing
`。．｡` disguised, the host of an unreadable link still judged. Text: userinfo behind a stop and `＠`,
pieces after a cut, stand-in dots through several labels, and `link_stitched` (30) for a brand address
run into another domain. Criteria: 459 attack rows (rounds 3 and 5) none lower than origin/main, 192
legitimate rows (rounds 4 and 5) none higher except the eight +10 deviation notes; AttackRowsTest and
LegitimateMailTest pin them. Gates: `clean check` green, 3,046 tests (3,038), 0 failed; no-SDK 1,576
(1,572); corpus 791 cases (518), 0 differ on API 29, API 35, the iOS simulator and the JVM (one API 29
run segfaulted in app_process after printing "0 differ"; three reruns exited 0); planted faults in
corpus.json and pinned.json both fail on iOS; ICU 129 compiled, 0 rejected; API check 690 classes,
0 problems; performance linear (new row: unclosed anchors). Release APK 1,970,131 bytes (unchanged);
LoupeKit arm64 30,467,736 bytes (+154 KB, mostly the HTML entity table).

**Fix loop 6 (2026-09-27, same branch).** Evidence: `.../loupe-android-evidence/parity-fix6/`.
- **Mail HTML is tokenised in full:** every tag, comment, bogus comment and raw-text element by the WHATWG rules.
  - An anchor hidden in another tag's open quote or in a comment is found; one in script, style, textarea, xmp or template text is not.
  - `<svg><a xlink:href>` is a link, and relative hrefs resolve against the first `<base href>`.
  - All 41 round-6 HTML files give Chrome DOMParser's hrefs, except a Blink quirk in one query (`&copy=` before a later reference).
- **`link_stitched`** needs the stop inside the first host, so link lists (`www.instagram.com/bistrocloud|www.bistrocloud.com`) are safe again.
- **The text rescan** is one forward pass: `https://a.com|x|x…` took 25–44 s on the iOS simulator and now takes about 120 ms.
- **The fuzz oracle** counts "no host" only when both bases give none, and Apple Mail's host only for an href with its own scheme.
  - Two regressions the loop-5 oracle missed now fail: backslash not scheme-relative (67 cases), `https:host` read as relative (494).
  - `oracle.py` removes its temporary Chrome profile.
- **Criteria:** r3+r5 459 attack rows none lower and r4+r5 192 legitimate rows none higher (8 accepted +10 notes).
  - 157 attack and 240 legitimate rows are pinned in AttackRowsTest and LegitimateMailTest, including round 6's stitch lines, `<base>` newsletters and extra attacks.
  - Two adversarial files (`data-href` only, an anchor swallowed by an open quote) score lower than origin/main because origin/main read a link Chrome does not have.
- **Gates:**
  - `clean check` green, 3,046 tests, 0 failed; no-SDK 1,576.
  - Corpus 916 cases (791), 0 differ on API 29, API 35, the iOS simulator and the JVM.
  - Planted faults in corpus.json and pinned.json fail on iOS.
  - ICU 129 compiled, 0 rejected; API check 691 classes, 0 problems.
  - Performance linear; the check is now the median of five with `t(4n) < 12 t(n) + 250 ms`.
- **Sizes:** release APK unchanged; LoupeKit arm64 30,475,560 bytes.

## Needed from the owner

1. A Google Play developer account (one-time $25).
2. A Google OAuth **Android** client in the existing Cloud project ("Loupe Station" consent screen, Testing), with package `com.loupeai.android` and the **SHA-1 of the signing certificate**. The debug SHA-1 is generated at A0 and the upload key's at A9; the Play App Signing SHA-1 is added after the first upload.
3. A real Android phone for A1/A5/A7 measurements (a mid-range device is more telling than a flagship).

## Risks

- The 384 MB model on low-memory phones: measure at A1, keep `int8-partial` as a fallback; never load two sessions.
- Gmail restricted scope: `gmail.readonly` needs Google's verification before public release (same as iOS).
- Background limits vary by manufacturer: passive mode must tolerate being killed and resume.

## Message scam check (approved 2026-09-26, not started)

*Owner-approved idea, BACKLOG.md BL-1. Planning only until the owner says "Go"; it comes after A4.*

**What.** Loupe judges incoming WhatsApp and SMS messages on the phone and warns when one looks like
a scam. Egypt and MENA first: WhatsApp scams ("I'm your cousin, new number"), InstaPay and Vodafone
Cash transfer fraud (a request to send money to a wallet number or InstaPay address, a fake
"transfer received" screenshot or message), and fake delivery SMS (a courier name, a small fee, a
link). This is the headline Android feature: iOS cannot do it at all. Inspiration: JevBystander,
an Android accessibility app that reads the visible WeChat chat and returns typed answers without
taking any action.

**How it reads messages.**

| Route | Reach | Channel |
|---|---|---|
| `NotificationListenerService` (B8, already in A4) | The notification's sender and text (WhatsApp's `MessagingStyle` carries the recent messages of the thread); nothing when the user hides previews | Play build, opt-in in system settings |
| Accessibility, visible chat only | The open chat's text on screen, read when the user opens it | Direct build only, behind the build flag (PRODUCT.md §7) |
| Default SMS handler | Full SMS inbox | Not planned: PRODUCT.md §11 keeps SMS unbuilt, and taking over the SMS app is a large ask |

Android 15 hides one-time-code notifications from listener apps; that is fine, since Loupe never
reads or fills codes (the never list).

**How it judges.** Mechanical first, then one small question, as everywhere else:

1. Links in the message go through the shared phishing formula and the downloaded lists
   (`loupe-kit` `site`, engine `SiteFraud`), the same verdict as Check a link on iOS.
2. Rules: a wallet number or InstaPay address plus a request to send money; a courier name plus a
   fee plus a link; a sender not in contacts claiming to be family (engine `Impersonation`, the
   address book as in `WatcherRun`); urgency wording in English and Arabic.
3. The Loupe Decision Model answers one `Choice` over the message text (for example *normal /
   asks for money or a code / impersonates someone / fake delivery or prize / unclear*) only when
   the rules have not settled it. Message text is data, never instructions (PRODUCT.md §8).

**What the user sees.** A Loupe notification with the reasons ("asks you to send money to a wallet
number; the sender is not in your contacts"), and an entry in the Spotted log. It warns and never
blesses: no "this message is safe". It never replies, blocks, deletes or opens anything, and it
takes no action in WhatsApp.

**Privacy.** Nothing leaves the phone. Messages are judged in memory and not stored; the Spotted log
keeps the verdict, the reasons and the sender's display name, not the text. Online phishing checks
apply only to links and only when the user has turned them on (PRODUCT.md §4a).

**What iOS offers instead.** iOS gives no app access to another app's messages or notifications. It
has: Send to Loupe from the share sheet (checks a shared link on the spot), the clipboard check
(`ios/Loupe/Clipboard/`), and the Loupe keyboard, which checks a copied or pasted link on the device
with no network code (`ios/Shared/Clipboard/KeyboardCheck.swift`). An SMS filter extension for
unknown senders is allowed by iOS but not built (PRODUCT.md §2, §11).

**Milestone.** A4b, after A4 (B8 notifications); the site checks come with the shared `loupe-kit` from A0:

| # | What | Done when |
|---|---|---|
| A4b | Message scam check over WhatsApp and SMS notifications; the visible-chat reader in the direct build | Warns on a fixture set of Egyptian scam messages (English, Arabic, Franco-Arabic) with measured precision and recall per rule and for the model question; no message text on disk; battery measured on a mid-range phone |

**Open questions.**

1. A labelled set of real Egyptian and MENA scam messages: where it comes from, and consent for it.
2. How well the model reads Egyptian Arabic and Franco-Arabic (Arabic written in Latin letters): unmeasured.
3. Whether the Play listing needs a declaration for notification access, and what the data-safety
   form says (to verify at A9).
4. Whether the accessibility reader is worth a direct-build channel on its own.
5. Warn on every message, or only above a threshold with a daily cap, so warnings stay rare enough
   to be read.

## Remote-access app warning (approved 2026-09-26, not started)

*BACKLOG.md BL-15. After A4b (it reuses the message check).*

**What.** Two parts, both warnings only:

1. **On messages:** one more `Noul` in A4b's pass, *tells you to install an app or share your
   screen* (AnyDesk-style "bank support" scams).
2. **On the phone:** a list of newly installed apps that hold powerful access, each sorted by a
   `Choice` over its store description: *remote control, SMS reader, loan app, utility*.

Loupe never uninstalls, disables or changes an app; a finding goes to the review queue with
"open the app's settings" as the only action, and the last button is the user's.

**How it finds the apps.**

| Access | API | Visibility cost |
|---|---|---|
| Accessibility services | `AccessibilityManager.getInstalledAccessibilityServiceList()` | None: it lists every installed service without `QUERY_ALL_PACKAGES` |
| SMS permissions, other apps | `PackageManager` permission queries | Needs package visibility: a `<queries>` element per intent, or `QUERY_ALL_PACKAGES` |
| Screen sharing | `MediaProjection` is granted per session, not held; the check is "remote control" apps by description, plus the message `Noul` | — |

**Play policy to check before building.** `QUERY_ALL_PACKAGES` is restricted to named use cases
(antivirus and security apps are among them; verify the current wording and whether Loupe qualifies),
and it needs a declaration. Prefer the narrow route (`<queries>` plus the accessibility list) if it
catches enough. Store descriptions must come without a network call, or through an opt-in Online
source (PRODUCT.md §4a).

## Kids' protection: BOND (approved 2026-09-26, updated 2026-09-27; Phase 0 first, no code)

*BACKLOG.md BL-16, which holds the full list of owner decisions. Behind the Phase 0 gate and the
hard requirements below.*

**Product (DECIDED, owner, 2026-09-26/27).** Kids' protection becomes a standalone, **paid** product
named **BOND**: a separate brand from Loupe, on the same engine and the same KMP code. It ships as a
**Child app** (Android first) and a **Parent app** (iOS and Android). The cloud is a **relay
only**: the child's phone judges chats on the device, and the cloud helper carries only end-to-end
encrypted, content-free alerts, plus pairing and billing. No chat text ever leaves the child's
device.

**What.** On the child's phone, game, Discord and messaging chats are screened for grooming and
gift-card lures with four `Noul`s: *asks to move to a private chat*, *offers in-game currency or a
gift card*, *asks for photos or location*, *says to keep it secret*. The parent's alert carries no
content: the category, the confidence and the time only. The parent can also see the child's
location on request (below).

**Reading.** The same routes as the message scam check: notification previews in the Play build;
the visible chat through an `AccessibilityService`. **Decided (owner, 2026-09-26): allowed in the
Play build for this feature only**, declared to Google as parental control, a scoped exception in
PRODUCT.md §7. Conditions: on only in kids' mode on the child's device; a persistent, non-dismissible
"BOND protection is on" notification while it runs; the `isMonitoringTool` / parental-control and accessibility-use
declarations, with a prominent in-app disclosure and consent screen before it is turned on; it
reads only the configured chat and game apps, stores no text, and sends only content-free alerts
through the encrypted relay below. **Widened (owner, 2026-09-27):** BOND may block, or cover with a
full-screen screen, only the apps and schedules the parent set, and nothing else; it never types,
taps, sends or reads beyond the configured chat apps, and never blocks calls, SOS or BOND itself.
Outside kids' mode the Play build stays cooperative-only.

**Compute budget on the child's phone (DECIDED, owner, 2026-09-27).**

- At most **1 model pass per incoming message**, and at most **30 passes a minute**.
- Only the configured apps, and only while the screen is on.
- A **keyword prefilter** runs first; only messages it flags reach the model.
- Battery is **measured on a mid-range phone before launch**.

**Location, only when the parent asks (DECIDED, owner, 2026-09-27; session design CONFIRMED).**
When the parent opens the map, the child's phone sends its location every **5 s** until the map
closes.

1. **Start.** The parent's request goes through the relay as a high-priority push (FCM high
   priority). The child's phone starts a foreground service of type `location` (Android 14,
   `FOREGROUND_SERVICE_LOCATION`) for that session only, and stops it when the session ends.
2. **During the session.** A live end-to-end encrypted channel through the relay (for example, a
   WebSocket), not one push per update.
3. **Auto-stop** on the first of: the map closing; the Parent app going to the background or the
   parent's screen locking; a missed parent heartbeat (about 20 s); a **10-minute cap**, at which
   the parent is asked "keep watching?".
4. **Visible to the child.** While a session runs, the child's phone shows "Your parent is viewing
   your location".
5. **Child's phone off or offline.** The parent sees the last known location and its time.

Also decided: automatic **safe-place arrive / leave notices** (geofences) and **SOS** (sent at once,
then frequent updates until the parent stops it). All location is end-to-end encrypted; **no
location is stored on Loupe servers**; history exists only on the parent's phone. It needs "Allow
all the time" location (`ACCESS_BACKGROUND_LOCATION`, with Play's background-location declaration;
family safety is an accepted use) and a battery test on a mid-range phone.

**Screen time, app blocking and battery (DECIDED as features, owner, 2026-09-27).** Mechanical, no
model, so the compute budget above is unchanged.

- **Screen-time limits and app blocking** on the child's Android phone: daily limits per app or per
  category; schedules (bedtime, school hours); block an app; the child can ask for more time and
  the parent approves in one tap. Usage comes from `UsageStatsManager` (the `PACKAGE_USAGE_STATS`
  special access). Enforcement uses the existing accessibility service and/or a full-screen
  overlay. **Never blocked:** phone calls, SOS, and BOND itself.
  **Enforcement decided (owner, 2026-09-27):** within the widened PRODUCT.md §7 exception above
  (only the apps and schedules the parent set; no typing, tapping or sending).
- **The child's battery in the Parent app:** the current level and charging state, sent with the
  heartbeat and with each location or alert event, plus a low-battery alert (for example, at 15% or
  below). Read from `BatteryManager`; no extra wake-ups. It travels end-to-end encrypted like
  everything else on the relay.

**Under consideration (NOT decided).** A child SOS button; a teen scam / sextortion pack reusing
the scam engine; "what to say" guides per alert in Arabic and English; a weekly calm summary; a
family plan.

**Hard requirements, before any build.**

0. **Phase 0 has reported** (the gate below).
1. **Consent and transparency.** The child knows it is on, in age-appropriate words; no hidden mode.
   This includes the location notice in step 4 above.
2. **Google Play.** The stalkerware policy: the `isMonitoringTool` manifest flag, a persistent
   notification while monitoring, prominent disclosure, the parental-control declaration, the
   AccessibilityService declaration, the background-location declaration with the
   `FOREGROUND_SERVICE_LOCATION` foreground-service type, and the usage-access
   (`PACKAGE_USAGE_STATS`) disclosure for screen time.
3. **iOS.** For a Child app, check Apple's Screen Time APIs (FamilyControls, ManagedSettings,
   DeviceActivity) for anything usable; they are not known to expose message content, so an iOS
   Child app is probably controls only. The Parent app is iOS and Android.
4. **Legal review.** Children's data, now including precise location; Egypt's Personal Data
   Protection Law (151/2020); COPPA and GDPR-K if sold outside Egypt.
5. **A measured false-positive rate** on a labelled set before any launch claim.
6. **Battery measured on a mid-range phone** for the chat budget and for location sessions.

**The encrypted relay (owner decisions, 2026-09-26 and 2026-09-27).** The scoped exception in
PRODUCT.md §4, titled for BOND: "no server can read anything" replaces "no Loupe server". It covers
every BOND message: chat alerts, live location sessions, SOS, safe-place notices, battery status
and screen-time requests / approvals (plus pairing and billing). All are end-to-end encrypted to the
paired parent device; the relay stores no location or content, only short-lived delivery queues;
history lives only on the parent's phone.

- The child's device encrypts each alert and each location update to the paired parent device's
  public key; pairing is in person (for example, a QR code carrying a key exchange).
- The alert payload is content-free: category, confidence, timestamp and at most the app name.
  Never message text.
- Transport: FCM (and APNs for an iPhone parent), or a minimal relay, for alerts and session
  start; a live channel (for example, a WebSocket) through the relay for a location session. It
  carries only ciphertext it cannot read and stores nothing beyond short-lived delivery queues;
  it stores no location.
- Obligations: the privacy policy discloses the relay; key rotation and unpairing; replay
  protection (for example, a per-pair counter or nonce checked on the parent device); and it is
  stated that the relay can see session metadata (start, length, update rhythm, device tokens).

**Phase 0 gate (DECIDED, owner, 2026-09-27).** Validate before any MVP build:

- a landing page and a waitlist;
- parent interviews;
- a pricing test;
- school and carrier partnership talks.

**No BOND code (Child app, Parent app or relay) is written until Phase 0 has reported to the
owner** and the owner says "Go".

**Milestone.** A10, **gated on Phase 0**, then after A9 (the first Play release) and the legal
review: *done when* the four questions run on a fixture set with measured precision and
false-positive rate inside the compute budget; the Play declarations (parental control,
accessibility, background location) are filed; the consent screens have been reviewed; the
encrypted relay passes pairing, unpairing, key-rotation and replay tests with no plaintext at the
relay, for alerts and for location sessions; every location auto-stop (map closed, parent app
backgrounded or screen locked, missed heartbeat, 10-minute cap) is tested; and battery is measured
on a mid-range phone for chat screening and for a location session; screen-time limits and
blocking are tested to block only the parent's configured apps and schedules and never calls, SOS
or BOND itself; and the battery status and low-battery alert are tested.
