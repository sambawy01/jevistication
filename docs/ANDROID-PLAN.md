# Loupe for Android — plan

Status: **in development, in parallel with iOS** (owner, 2026-09-26). The Loupe Station session builds it on branch `android` (a worktree on /Volumes/Sambawy); changes to shared modules merge to `main` only after review and a green iOS suite.
*History:* from 2026-09-25 to 2026-09-26 Android was on hold until every iPhone feature worked on a real device; the owner lifted the hold on 2026-09-26.
It follows the iOS route (epic #6 / #7): the same Kotlin Multiplatform core, a native UI and the same
model as the iPhone app and Loupe Station, aiming at the same answers. Where Android does not give
them yet is listed under "Known parity gaps" below.

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
tools/android-api-check/check.py            # no API above minSdk 29 in our compiled Android classes
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
Arabic-locale phone). SHA-256 (`MessageDigest`) and NFKC (`java.text.Normalizer`) come from the
platform, and so does the regex engine: those are where Android's answers can still differ (see
"Known parity gaps").

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

Android does **not** yet give the iPhone's and Loupe Station's answers for every input on every
phone. Two gaps are known and measured; their fixes wait on a cross-platform decision (they change
shared regex semantics or the NFKC implementation, which the iOS app and Station share), so A0 only
records them. Measurements: `/Volumes/Sambawy/loupe-android-evidence/a0-fix/parity-probe-*.txt`
(the same `ParityProbe` class run on the host JDK 21 and, through `app_process`, on the API 29 and
API 35 emulators).

**B1. Regex character classes and case folding.** Android's `java.util.regex` is ICU, whose `\d`,
`\s`, `\w`, `\b`, `\p{…}` and case-insensitive matching are Unicode-aware; the JDK's are ASCII unless
`UNICODE_CHARACTER_CLASS` is set, and Kotlin/Native's regex follows the JDK's rules (reported by
review; not re-measured here). The same pattern therefore compiles on all three but matches
different text:

| Probe | JDK 21 | Android API 29 | Android API 35 |
|---|---|---|---|
| `\d` finds Arabic-Indic `٣` (U+0663) | no | **yes** | **yes** |
| `\s` finds NBSP (U+00A0) | no | **yes** | **yes** |
| `\w` finds `é` (U+00E9) | no | **yes** | **yes** |
| `caf\b` finds a boundary inside `café` | yes | **no** | **no** |
| `\p{Alpha}` finds `é` | no | **yes** | **yes** |
| IGNORE_CASE `ss` finds `ß` (and `ß` finds `SS`) | no | **yes** | **yes** |
| IGNORE_CASE `i` finds `İ` (U+0130), and back | yes | **no** | **no** |

So on a phone, dates written in Arabic-Indic digits, amounts after a no-break space, accented words
at a `\b` and German or Turkish case pairs can match where the iPhone and Station do not, or the
reverse. Affected shared code (regexes using those classes or IGNORE_CASE on user text):
`engine/.../DateFacts.kt`, `engine/.../TermChange.kt`, `loupe-kit/.../watchers/WatcherRun.kt`,
`loupe-kit/.../privacy/Evidence.kt`, `loupe-kit/.../mail/Classify.kt`,
`sources-common/.../CsvRows.kt`, `sources-common/.../Mime.kt` (and user baselines,
`Baseline.Pattern`, which are IGNORE_CASE). The regex check proves only that every pattern
**compiles** on ICU. Options for the decision: spell the ASCII classes out (`[0-9]`, `[ \t\n\x0B\f\r]`,
`[A-Za-z0-9_]`) in shared code so every engine agrees, or deliberately adopt Unicode classes
everywhere (`UNICODE_CHARACTER_CLASS` on the JDK; not available on Kotlin/Native).

**B2. NFKC and IDNA data.** NFKC on Android is the device's ICU (`java.text.Normalizer`), whose
Unicode version rises with the API level, so the same string can normalise differently on two
phones: U+1E030 (MODIFIER LETTER CYRILLIC SMALL A, Unicode 15) becomes `а` (U+0430) under JDK 21
and on API 35, but stays unchanged on API 29 (ICU of Unicode 11). NFKC feeds the IDNA step
(`idnaToAscii`) and loupe-kit's homograph check (`Hosts`, `nfkc`). The JDK's `java.net.IDN`,
which the desktop's IDNA is pinned against, implements IDNA 2003 over Unicode **3.2**; the portable
port Android and iOS use follows it, but its NFKC step comes from the platform, so characters newer
than Unicode 3.2 can take different paths. Options: ship one NFKC table (generated from the JDK's
data, as the script table is) for every platform, or pin the answer to Unicode 3.2 and reject newer
code points in hosts, as IDNA 2003 does.

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

## Kids' chat protection (approved 2026-09-26, not started)

*BACKLOG.md BL-16. Android first; behind the hard requirements below.*

**What.** On the child's phone, game, Discord and messaging chats are screened for grooming and
gift-card lures with four `Noul`s: *asks to move to a private chat*, *offers in-game currency or a
gift card*, *asks for photos or location*, *says to keep it secret*. The parent's alert carries no
content: the category, the confidence and the time only.

**Reading.** The same routes as the message scam check: notification previews in the Play build;
the visible chat through an `AccessibilityService`. **Decided (owner, 2026-09-26): allowed in the
Play build for this feature only**, declared to Google as parental control, a scoped exception in
PRODUCT.md §7. Conditions: on only in kids' mode on the child's device; a persistent, non-dismissible
"Loupe protection is on" notification while it runs; the `isMonitoringTool` / parental-control and
accessibility-use declarations, with a prominent in-app disclosure and consent screen before it is
turned on; it reads only the configured chat and game apps, stores no text, and sends only
content-free alerts through the encrypted relay below. Outside kids' mode the Play build stays
cooperative-only.

**Hard requirements, before any build.**

1. **Consent and transparency.** The child knows it is on, in age-appropriate words; no hidden mode.
2. **Google Play.** The stalkerware policy: the `isMonitoringTool` manifest flag, a persistent
   notification while monitoring, prominent disclosure, the parental-control declaration, and the
   AccessibilityService declaration.
3. **iOS.** Check Apple's Screen Time APIs (FamilyControls, ManagedSettings, DeviceActivity) for
   anything usable; they are not known to expose message content, so iOS is probably controls only.
4. **Legal review.** Children's data; Egypt's Personal Data Protection Law (151/2020); COPPA and
   GDPR-K if sold outside Egypt.
5. **A measured false-positive rate** on a labelled set before any launch claim.

**Parent alerts: an end-to-end encrypted relay (owner decision, 2026-09-26).** A scoped exception in
PRODUCT.md §4: for this feature only, "no server can read anything" replaces "no Loupe server".

- The child's device encrypts each alert to the paired parent device's public key; pairing is in
  person (for example, a QR code carrying a key exchange).
- The payload is content-free: category, confidence, timestamp and at most the app name. Never
  message text.
- Transport: FCM (and APNs for an iPhone parent), or a minimal Loupe relay, carrying only ciphertext
  it cannot read and storing nothing beyond short-lived delivery queues.
- Obligations: the privacy policy discloses the relay; key rotation and unpairing; replay
  protection (for example, a per-pair counter or nonce checked on the parent device); and it is
  stated that the relay necessarily sees metadata (timing and device tokens).

**Milestone.** A10, after A9 (the first Play release) and the legal review: *done when* the four
questions run on a fixture set with measured precision and false-positive rate, the Play
declarations are filed, the consent screens have been reviewed, and the encrypted relay passes
pairing, unpairing, key-rotation and replay tests with no plaintext at the relay.
