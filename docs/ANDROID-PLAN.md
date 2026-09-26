# Loupe for Android — plan

Status: plan, 2026-09-25. **On hold until the iPhone app's features all work on a real device** (owner, 2026-09-25): Android then reuses proven code instead of chasing a moving target.
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

## Early users and the paid tier (AGENT.md §4)

Everything local is free; only the AI assistant is paid. Early users keep the local features free for
life if they are ever charged for, decided by the shared `EarlyUserPolicy` (one cutoff constant, one
pure function) in `:agent`.

- **No `AppTransaction` on Play.** iOS reads the App Store's own first-purchase date, verified on the
  device. Google Play has no equivalent a free app can read. `PackageManager.firstInstallTime` and the
  Install Referrer's install timestamp both describe *this* install and reset on reinstall, so neither
  can stand in for it.
- **So Android records early-user status itself**, as `FirstInstall.Recorded`:
  1. *On first run* (the default): write the day to app storage that Android backup restores on a new
     phone. Cheap and serverless, but it trusts the device clock on that first run and is lost if the
     person clears data or backup is off. It must ship in the first public Android release; a record
     written after the cutoff would make an early iPhone user a late Android one.
  2. *Tied to an account*: store the day against the person's account, so it survives any reinstall.
     Stronger, but it needs somewhere to keep it (for example a small entitlement record next to the
     assistant's key-issuing service, AGENT.md §4c), and never any mail content.
- **Billing.** The assistant is a Play Billing subscription; top-up credits are consumables. Fees
  checked 2026-09-26: in the EEA, UK and US since 30 June 2026, subscriptions are 10% + 5% billing
  fee, other products 10% + 5% for new installs and 25% + 5% for existing ones; elsewhere 15% on
  subscriptions.
- **AI-generated content.** Play requires an in-app way to report or flag offensive AI output without
  leaving the app. Every provider-written action gets a "Report this" action.

## Milestones

| # | What | Done when |
|---|---|---|
| A0 | Android targets in the KMP modules, `:android-app` skeleton, CI builds an APK | `./gradlew check` green with Android unit tests |
| A1 | `backend-onnx-android` + parity | The 34+8 parity vectors match iOS and JVM exactly on an emulator and on a real device; p50/p95 latency and memory recorded on a mid-range phone |
| A2 | Model download with consent + SHA-256 | Download, verify, resume, delete from settings |
| A3 | Now, Judgments, Unsure queue, Ledger, Model settings | Same screens and strings as iOS; UI tests on an emulator |
| A4 | Sources B1, B2, B4, B5 (+ B8 notifications) | Each source scans the sample and a real device |
| A5 | Watchers, sweep, passive mode (WorkManager) | Runs in the background under constraints; battery measured |
| A6 | Web tab and helpers | Same templates as iOS |
| A7 | Game and mascot | 60 fps on a mid-range device, gates identical to iOS |
| A8 | Third-party notices for the Android set | BUILD.md risk 11 closed for Android |
| A9 | Play internal testing release | Signed AAB on the internal track; privacy policy, data-safety form |

## Needed from the owner

1. A Google Play developer account (one-time $25).
2. A Google OAuth **Android** client in the existing Cloud project ("Loupe Station" consent screen, Testing), with package `com.loupeai.android` and the **SHA-1 of the signing certificate**. The debug SHA-1 is generated at A0 and the upload key's at A9; the Play App Signing SHA-1 is added after the first upload.
3. A real Android phone for A1/A5/A7 measurements (a mid-range device is more telling than a flagship).

## Risks

- The 384 MB model on low-memory phones: measure at A1, keep `int8-partial` as a fallback; never load two sessions.
- Gmail restricted scope: `gmail.readonly` needs Google's verification before public release (same as iOS).
  Saving drafts into Gmail would need `gmail.compose` or `gmail.modify`, another restricted-scope review;
  until then drafts are copied or shared from the app (AGENT.md §6).
- Background limits vary by manufacturer: passive mode must tolerate being killed and resume.
