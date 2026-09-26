# Loupe for Android — plan

Status: **in development, in parallel with iOS** (owner, 2026-09-26). The Loupe Station session builds it on branch `android` (a worktree on /Volumes/Sambawy); changes to shared modules merge to `main` only after review and a green iOS suite.
*History:* from 2026-09-25 to 2026-09-26 Android was on hold until every iPhone feature worked on a real device; the owner lifted the hold on 2026-09-26.
It follows the iOS route (epic #6 / #7): the same Kotlin Multiplatform core, a native UI, the same
model and the same answers as the iPhone app and Loupe Station.

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
the portable IDNA, script table and number formatter, and the embedded Public Suffix List, so every
phone gives the same answers whatever its Unicode data or locale (`"%.2f"` prints Arabic-Indic digits
on an Arabic-locale phone). Only SHA-256 (`MessageDigest`) and NFKC (`java.text.Normalizer`) come
from the platform.

**Traps found at A0.**

1. *Android's regex engine is ICU, not the JDK's.* `Regex("""\{([a-z][a-z0-9_]*)}""")` (templates,
   `Template.kt` and `Baseline.kt`) compiles on the JDK and Kotlin/Native but throws
   `PatternSyntaxException` on Android: ICU rejects a bare `}`. The app crashed on first launch in the
   emulator while every unit test was green, because Android unit tests run on the host JDK. Fixed by
   escaping it (`\}`, the same pattern everywhere). `tools/android-regex-check/check.sh` compiles all
   133 statically known regex literals of the shared modules on a device; 10 built at run time were
   reviewed by hand (`Regex.escape` → `\Q…\E`, which ICU accepts; digit prefixes; `\d{4}`).
2. *Lint does not look at `jvmCommonMain`.* A planted `Path.of` (API 34) passed `lint`. The
   bytecode check `tools/android-api-check/check.py` catches it (it reads every class our Android
   build compiled and looks each JDK/Android reference up in the SDK's `api-versions.xml`).

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

**Size.** Debug APK 25.8 MB (AGP stores debug APKs uncompressed and unshrunk; 8.7 MB deflated).
Release APK with R8 and resource shrinking: **1.9 MB** (unsigned). No model, no native code beyond
Compose's 10 KB `libandroidx.graphics.path.so`, no dependency beyond Compose, `activity-compose` and
the shared modules.

**CI.** `.github/workflows/ci.yml` now also runs on pushes to `android`; its Linux job's
`./gradlew build` builds, tests and lints the Android modules, and the debug APK is uploaded as the
`loupe-android-debug-apk` artifact.

**Debug signing certificate** (this Mac's `~/.android/debug.keystore`), for the Google OAuth Android
client (item 2 below): SHA-1 `E1:70:ED:F1:BC:46:F8:29:E8:E3:D8:F0:1E:49:3B:60:53:F3:E0:65`.

**Warnings left.** Gradle: `Retrieving attribute with a null key` appears only once AGP is
applied (it is not on origin/main; not traced further); the other two notices (`addCandidate` from the
XCFramework block, `Task.project at execution time`) are on origin/main too. AGP 8.9.3 is above
Kotlin 2.1.0's tested AGP range (see Build choices). The app uses the platform's default fonts;
Rajdhani and JetBrains Mono (iOS) are not bundled yet. `./gradlew build` (not part of the gate)
fails on macOS at the root `:commonizeNativeDistribution` ("no repositories are defined"); origin/main
fails the same way, and CI runs `build` on Linux, where that task does not exist.

**Left for later milestones.** No Android `actual` is stubbed: every `expect` has a full
implementation. The phone's own PDF and image readers (`PlatformExtractors`) come with A4; A0's unit
tests use the desktop readers on the host JVM, as `jvmTest` does. The regex and API checks are
scripts run by hand, not yet part of `check` or CI.

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
