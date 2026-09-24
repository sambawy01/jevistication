# Build Plan

The work required to build what [`PRODUCT.md`](PRODUCT.md) specifies. Nothing here is a
scope cut; the ordering is dependency, not release.

---

## 0. Platform and stack

**iOS first; Android second.** *Changed 2026-09-23, on the owner's decision (epic #6). Until
then this section read "Android", and the reason is still true:* the locked spec needs message
history from known senders (person impersonation), notification arrival, broad filesystem
access, and cross-app form filling, and iOS grants none of the first three and only a
Safari-scoped version of the fourth. The owner chose iOS anyway, knowing that. So the iPhone app
ships a **reduced spec**, and this is what the platform takes away:

| iOS does not allow | What iOS offers instead | Spec capability, on iOS |
|---|---|---|
| Reading the SMS inbox | An unknown-sender Message Filter extension, barred from the network | SMS was already not built (risk 4); the filter slot is the only SMS surface |
| Reading other apps' notifications | Nothing | **Triage on arrival** for notifications: **not possible** |
| Broad filesystem access | Files / document picker, the share sheet, PhotoKit | Files arrive when the user picks or shares them; photos through PhotoKit |
| Filling forms in other apps | Safari (a Safari web extension) and a Credential Provider extension | **Form filling: limited** to Safari and credential-shaped fields |
| Message history from known senders | Nothing outside our own app | **Person impersonation: email and contacts only** — already decided under risk 4 |

Nothing above is worked around. Android stays the second platform: where its row below differs,
it is kept, because it is still what the Android build would use.

| Layer | iOS (leads) | Android (second) |
|---|---|---|
| Language / UI | Swift, SwiftUI app shell | Kotlin, Jetpack Compose |
| Engine and templates | **Kotlin Multiplatform** (`jvm`, `iosArm64`, `iosSimulatorArm64`), exported as the XCFramework **`LoupeKit`** | the same modules, JVM/Android |
| **Decision model** | **`convaiinnovations/laya-multilingual`** — 322M, Apache-2.0 weights, mmBERT-base backbone. *Cleared for shipping by owner decision 2026-09-23; training-data notes in `LICENSING.md`* | same |
| **Second backend** | **`Qwen/Qwen3-0.6B`** — Apache-2.0, decoder, scored by logits | same |
| Model runtime | ONNX Runtime iOS | ONNX Runtime Mobile (NNAPI / XNNPACK execution providers) |
| Tokenizer | Hugging Face `tokenizers` Rust crate built for iOS, called through a C FFI, offline only — the same crate DJL wraps, so ids match | DJL `ai.djl.huggingface:tokenizers`, offline mode enforced |
| Ledger | not decided (the desktop app uses plain files) | SQLite via Room |
| Background | Background Tasks: `BGProcessingTask`, requiring charging | WorkManager, charging + idle constraints |
| OCR and image labels | Vision, on-device | ML Kit, on-device |
| Speech | on-device recognizer | on-device recognizer |
| Photos, calendar, contacts | PhotoKit, EventKit, Contacts | MediaStore, CalendarProvider, Contacts |
| Email | Gmail API over OAuth; IMAP for everything else | same |
| Form filling | Safari web extension + Credential Provider only | `AutofillService` |
| Notifications | none — iOS does not expose them | `NotificationListenerService` |
| Files | Files / document picker + share sheet | Storage Access Framework + MediaStore |
| Online sources | opt-in, fetch-only helper — see `PRODUCT.md` §4a and risk 14 | same rules |

**What is not known yet, stated plainly.** Laya's latency on an iPhone is **unmeasured** (risk 13);
the model is **untuned** and loses to keyword baselines on the synthetic sample. The KMP port
(child 2) and Laya on the iOS simulator (child 3: ORT iOS + the Rust tokenizer, same answers as the
JVM on every fixture) are done; latency on a physical iPhone is not measured yet.

**Why that model.** *Changed 2026-09-23, on the owner's decision that GLiClass's performance is
not comparable.* Laya is a typed decision model: a bidirectional encoder plus a small head that
scores every option at its own `<mask>` marker and softmaxes per question — the Choice primitive
in one forward pass, non-autoregressive, no decoding. The multilingual checkpoint (mmBERT-base,
322M, 1,024-token context, 256 of it for the question and options) reads 100+ languages, where
an English-only model's confidence collapses without warning. Weights are Apache-2.0 at a pinned
revision. The export is proven: one ONNX graph matching the authors' own PyTorch to 2.6e-6 in
probability on 34 questions — see the progress log.

What it costs, stated plainly. **It is about twice GLiClass's size** — 322M against 151M
parameters, with a 256k-token vocabulary whose embedding alone is 197M — so the INT8 graph is
384 MB and the FP32 one 1.29 GB. **Its on-device latency is unmeasured**: the only numbers are
desktop-CPU ones, and at the full 1,024-token context a single question took over a second on an
Apple M4 under ONNX Runtime. **Its card says to keep a choice under ~20 options**, because options
share the 256-token head budget. And **its training data is only partly published and the part
that is includes non-commercial sources** — recorded in [`LICENSING.md`](LICENSING.md) and filed
as risk 12. GLiClass's authors stated their data permits commercial use; Laya's do not say.
Risk 12 was closed 2026-09-23 by owner decision: Laya is cleared for shipping under its
Apache-2.0 licence, with the training-data notes kept in `LICENSING.md`.

We fine-tune it on our own fixtures and fit our own calibration — which is what A6 required
anyway, and which means **we depend on nobody's calibration claim.** That is the product's own
argument applied to its own foundations. It matters more with Laya than it did before: the
checkpoint ships with temperature `[1, 1, 1]` and its card calls it systematically over-confident.

**Why that second backend.** *Unchanged by the switch to Laya, which is also an encoder.* A decoder
scored by its logits against our encoder scoring labels:
architecturally different, which is what makes the agreement check in §6 of the spec meaningful.
Two similar architectures would fail alike. Apache-2.0, verified at source, and it is the same
base NanoJev used — so we get the diversity NanoJev offered without its licence problem. We
implement the logit-scoring ourselves; the method is a technique, not protected expression.

**Rejected, and why — see [`LICENSING.md`](LICENSING.md).** openJev-verdict-2.0: checkpoint
unobtainable, LICENSE a truncated Apache text GitHub classifies as `NOASSERTION`. simple-jev: no
repository licence. **NanoJev: its GitHub repository is MIT, but the weights and dataset on
Hugging Face declare no licence at all** — and a code licence does not carry to weights.
*Laya-MLX was listed here as "Apple Silicon only". That was wrong:* Laya-MLX is an MLX runtime
port, and the port is what is Apple-only. The model family is now the primary (above).

**No hosted backend, ever.** Not an omission. A network call breaks the offline guarantee the
product rests on.

**Model delivery.** Weights are not in the app binary. On iOS, fetched on first run from a pinned
URL with a SHA-256 check, after explicit consent — a one-time, labelled network use. On Android,
Play Asset Delivery or the same first-run fetch. Laya's INT8 graph is 384 MB (plus a 34 MB tokenizer) — heavy for a mobile download but
normal for an asset pack, and fatal for an APK.

**Browser reach.** *On iOS* (leads): a Safari web extension, inside the same app bundle, for the
fraud check and form filling in Safari only; search and compare runs in the app, and web results
come through the Web tab's online sources (`PRODUCT.md` §4a). *On Android* (second): Chrome for Android does not support extensions. Form filling and the fraud
check reach Chrome through `AutofillService`, which sees focused form structure and fires at the
moment that matters — the instant before you type. Search-and-compare runs in an in-app WebView.
**No Firefox extension**: one codebase, and Autofill already covers where Android users are.

---

## 1. Engine core

Everything depends on this track. Built first, in this order.

**A0 · Licence verification at source. — DONE 2026-09-22.** Every model and dataset read from
its Hugging Face frontmatter, not from a badge. Two candidates were eliminated by this check
after being chosen. Results in [`LICENSING.md`](LICENSING.md).
*Standing:* re-verify at the exact revision shipped, and record it. This work item reopens for
any new artifact.

**A1 · Model runtime.** Fine-tune the base on our fixtures; export to ONNX; integrate ONNX
Runtime Mobile; wire Choice, Score and Noul through one call interface.
*Accept:* p50 and p95 latency measured on a real mid-range device, not an emulator; a Choice
over 200 candidates returns a normalised distribution.
*Risk:* export of the classification head is the first thing to prove before anything is built
on it. **Proven on desktop 2026-09-23** for Laya: encoder and head export as one graph that
matches the authors' PyTorch (FP32 34/34, max probability error 2.6e-6), and it runs from Kotlin
through `OnnxBackend`. The device half of the acceptance is untouched.
*Note on the 200-candidate criterion:* Laya will return a normalised distribution over 200
candidates (each is cut to 4 tokens to fit), so the letter of the criterion is reachable — but its
authors report accuracy falling off sharply past ~20 options, so the spirit is not. Large label
sets need shortlisting or splitting into a coarse and a fine question.

**A2 · Backend interface and the second implementation.** One interface; the Laya model and a
logit-scored Qwen3-0.6B behind it.
*Accept:* the same fixture set runs on both; a fidelity suite shows identical selected answers
across precisions, and repeated calls show no memory growth.
*Note (2026-09-23):* Laya's INT8 graph does **not** strictly meet "identical selected answers
across precisions" — it flips one of 34 parity questions, a near-tie. Whether a near-tie flip
counts against the criterion is a decision to make with real fixtures, not by redefining it.
The opt-in `int8-partial` graph (2026-09-23) has the same one near-tie flip.

**A3 · Mechanical extractors and the text-state pipeline.** Hash, MIME, EXIF, dedup, regex,
dates, domain and certificate facts, OCR presence. Every source is normalised to text state.
*Accept:* measured share of fixture items resolved without consulting the model. That share is
a tracked number, because raising it is how §6 of the spec gets easier.

**A4 · Judgment type and response validation.** Typed judgments; strict validation rejecting
wrong type, unknown chosen key, key-count mismatch, any probability outside [0,1].
*Accept:* fuzzed malformed responses never throw; each judgment declares its failure posture
(open, loud, or null-action) and honours it.

**A5 · Ledger.** Append-only rows: state, questions, full distribution, **propensity**, action,
correction, judgment version, criteria hash.
*Accept:* a decision writes a row carrying a propensity. This is non-negotiable and cannot be
retrofitted — a deterministic log makes every later counterfactual biased.
*Note:* criteria wording is hashed into the row. Changing a judgment's wording invalidates
calibration fitted against the old text.

**A6 · Recalibrator.** Isotonic or Platt, fitted per judgment × source × option-count.
*Accept:* corrected probabilities beat raw on ECE on a held-out calibration split, never test.

**A7 · Policy runner.** Pure and total. Reads calibrated answers, never raw ones.
*Accept:* property tests over malformed and missing input; the type system prevents a policy
reading an uncalibrated probability.

**A8 · Counterfactual engine.** IPS, SNIPS and doubly robust over logged propensities.
*Accept:* replay 1,000 logged decisions against a candidate threshold and report the delta with
confidence intervals.

---

## 2. Sources

Each source is: permission flow, incremental sync, extractor, state builder, fixture set.
Ordered by breadth of value per unit of work.

| | Source | Notes |
|---|---|---|
| B1 | Files, Downloads, PDFs | SAF; text extraction; no vision stage needed. *iPhone (2026-09-23, child 7):* document picker + security-scoped bookmarks, share sheet "Send to Loupe" |
| B2 | Email | Gmail OAuth and IMAP; already text; richest outcome signal. *iPhone (child 7):* read-only IMAP with an app password, labelled Online; OAuth built but gated on owner client IDs |
| B3 | Spreadsheets and CSV | row plus column context |
| B4 | Photos and screenshots | MediaStore + ML Kit OCR and labels. *iPhone (child 7):* PhotoKit + Vision OCR on device (no scene labels yet) |
| B5 | Calendar and contacts | contacts also feed the impersonation watcher. *iPhone (child 7):* EventKit + Contacts; the address book now feeds `WatcherRun` |
| B6 | Voice memos | on-device transcription |
| B7 | SMS | not built — see below |
| B8 | Notifications | listener service |
| B9 | Web pages | WebView and autofill context |

*Accept, each:* items sync incrementally, produce text state, and appear in a judgment's results
with a ledger row.

**B7 is not built.** Becoming default SMS handler means shipping send, receive and conversation
UI plus a core-functionality justification at review — a messaging app we have no other reason
to build. **Person impersonation therefore runs on email and contacts**, where display-name
spoofing is the identical attack and needs no permission fight: a familiar name over an
unfamiliar address, a fake delivery notice, a fake bank mail. SMS extends it only if messaging
is ever built for its own sake.

---

## 3. Judgments and the watchers

**C1 · Built-in library.** Receipts, needs-reply, unsubscribe candidates, duplicates, stale,
expiring, junk. Authored to the three-part template: the invariant, what breaks it, what looks
similar but does not.

**C2 · Plain-language authoring.** A written question compiles to typed questions with
candidate criteria. Lint rejects text-judge phrasing — "rate 1–10", "explain why".
*Accept:* a judgment written by someone who has not read the source produces a working, scored
classifier.

**C3 · The five watchers.** Each needs its own extractors, criteria and fixtures:

- **Expiry radar** — document-type judgment plus date extraction plus rule arithmetic
- **Recurring-money census** — merchant and cadence detection across email, statements, store receipts
- **Term-change detection** — comparison against the previous version of the same document
- **Person impersonation** — contact matching, channel history, writing-pattern comparison
- **Site fraud** — mechanical origin facts first, then the identity-consistency judgment

*Accept, each:* fires on a real corpus, reports its own selective accuracy, and is measured
against its dumb baseline.

---

## 4. Interaction

**D1 · The uncertain queue.** Active-learning selection plus a random audit arm, so the labelled
set is not biased to hard cases.

**D2 · Visible calibration.** Per judgment: selective accuracy, coverage, how often it declined,
how agreement has moved. **Never a single system accuracy number.** *Owner override, recorded
2026-09-24: the owner keeps Me's "Agrees with you X% over N corrections" line (pooled over corrected
model answers, gated at 10 corrections) despite this rule; per-judgment figures stay on each Measure
screen.*

**D3 · Threshold slider.** Backed by A8. Shows what a change would have done before committing.

**D4 · Baseline runner.** Every judgment carries a dumb baseline and the app reports which wins.
*Decided 2026-09-24 (owner, matching Loupe Station): the baseline also answers **automatically** —
a judgment on **Auto** (the default) is answered by its keyword baseline once the baseline is strictly
more accurate on at least 30 of the user's corrections under the current wording (a tie keeps the
model; no harness snapshot ships here, so corrections alone decide). Such rows are logged
`mechanical:auto-baseline` with the model's answer kept alongside (`LedgerRow.modelDistribution`), so
corrections keep measuring both. The old "use the baseline" switch became a manual override: Auto /
Always baseline (`mechanical:baseline`, model not asked) / Always Laya.*

**D5 · Actions.** Preview, undo, and the rule that uncertain items queue rather than act.

---

## 5. Actuation

**E1 · Autofill service** — context-driven field decisions and the refusal gate.
**E2 · Cooperative app actuation** — App Intents, deep links, prefill up to the confirm.
**E3 · Accessibility service** — behind the build flag; Play build excludes it.
**E4 · Search and compare** — WebView, set selection over many candidates in one call.

*Accept:* autofill refuses a form asking for a credential, an ID number, or something the page
has no business requesting, and says why.

---

## 6. Learning and operations

**F1 · Passive mode** — WorkManager, charging and idle constrained, thermally throttled.
**F2 · Retroactive sweep** — a new judgment applied across full history, with progress and cancel.
**F3 · Overnight fine-tune** — ledger exported as a decision-question dataset with soft targets.
**F4 · Export** — judgments, calibration and ledger as portable files.
**F5 · The game** — the model deciding many times a second with probability bars visible, offline.
*Built on desktop 2026-09-23* as an original river shooter (working name *Riverflight*, a
placeholder): `:game` (pure Kotlin, depends only on `:engine`) and `:game-desktop` (Compose Desktop,
`./gradlew :game-desktop:run`). Mechanical-first legal moves, a safety override, a hand-off
threshold, a baseline autopilot and a headless model-vs-baseline run. *Accept* (proposed): the
model decides at ≥10 Hz with bars visible **on a phone**, offline, and the game reports honestly
whether it beats the baseline. The desktop half is met (10 decisions/s, ~65 ms P50); the phone half
is not measured, and untuned it does not beat the baseline — see the progress log.

---

## 7. Measurement harness

Runs alongside everything and gates it. Not a track that finishes.

- Fixture corpus split **by source group, never by item**
- Wording held identical across compared arms
- Errors counted as wrong; no retries; receipts never overwritten
- A dedicated calibration split, distinct from dev and test
- Per-judgment reporting: selective accuracy, coverage, reliability bins, ECE, Brier,
  and the baseline comparison

---

## 8. Proving milestones

The checkpoints that say the thing is real. Each is a demonstration, not a document.

1. **It runs.** The model answers a typed question on a real phone, at a measured latency.
2. **It decides.** One judgment, one source, end to end, writing a ledger row with a propensity.
3. **It learns.** Corrections retrain the calibrator and ECE improves on a held-out split.
4. **It shows its work.** The slider reports a real counterfactual from real logged history.
5. **It notices.** A watcher fires on real personal data and catches something true.
6. **It is honest.** A judgment loses to its dumb baseline and the app says so.
7. **It is local.** Airplane mode, full function.

Milestone 6 matters most. Upstream, a model-selected compaction lost to "keep the last 24k
characters" and the authors shipped the plain tail. Being able to discover that about our own
judgments is the difference between this product and a confident guess.

---

## 9. Build risks

| | Risk | Response |
|---|---|---|
| 0 | ~~Third-party weight licences are not what badges claim~~ | **Verified 2026-09-22.** Three candidates eliminated; see `LICENSING.md`. Reopens for any new artifact |
| 1 | ~~ONNX export of the classification head may not be clean~~ | **Proven on desktop 2026-09-23** for Laya: FP32 matches upstream PyTorch 34/34. INT8 needs a mixed recipe (see progress log); the device half remains |
| 2 | Model size versus APK limits | Play Asset Delivery or first-run fetch; never in the base APK |
| 3 | Battery and thermal cost of retroactive sweeps | Charging-constrained, throttled, cancellable, progress visible |
| 4 | ~~SMS default-handler review~~ | **Closed.** Not building messaging; impersonation runs on email and contacts |
| 5 | Accessibility and Play policy | Build flag; Play build cooperative-only |
| 6 | Judgments that do not beat their baseline | Expected for some. Milestone 6 exists to find them, and the honest answer is to ship the baseline |
| 7 | Prompt wording moving results as much as the algorithm | Wording is a controlled variable, and criteria text is hashed into every ledger row |
| 8 | The second backend is weak zero-shot | Untuned Qwen3-0.6B scores poorly on decision tasks. Both backends are fine-tuned on our fixtures; its votes do not count until it is |
| 9 | **Name collision.** `LOUPE` is a crowded mark. Registrations exist for jewellery-trade software (Atelier Technology), sports-card retail (Loupe Tech LLC) and a CRM (Apex); Mysk ships an iOS privacy app called Loupe | None is a consumer personal-data or fraud-detection app, but a crowded mark is a weak mark, and the Mysk app is adjacent on privacy and mobile. **Clear the mark in the target jurisdictions, and check Play Store and domain availability, before any spend on branding, the listing or the domain.** Decision taken with this known |
| 10 | ~~Approximate public suffix list~~ | **Closed 2026-09-23.** The engine now bundles the real Mozilla PSL (snapshot `2026-09-21_18-50-07_UTC`, SHA-256 pinned and tested), implements the full algorithm — wildcards, exceptions, IDN, both sections — and passes the official test vectors. Refreshed at build time by `tools/update-psl.sh`; never fetched at runtime. Reopens if the snapshot goes stale before a release |
| 11 | ~~Third-party attribution is unshipped~~ (ONNX Runtime, DJL and the Rust crates in `libtokenizers`, Compose/skiko/Skia, the desktop sources, PDFBox's bundled fonts and data) | **Closed 2026-09-23.** `./gradlew :loupe-desktop:generateThirdPartyNotices` writes `loupe-desktop/src/main/resources/THIRD_PARTY_NOTICES.txt`, packaged in the app jar, from the resolved runtime jars and POMs, 213 pinned Rust crates and the reviewed tables in `third-party/`; `check` and `ThirdPartyNoticesTest` fail when it is stale. Never patch Eigen (MPL-2.0, file-level). **The Android component set is still unverified** — the AAR is a different native build and needs its own run when an Android module exists |
| 12 | ~~Laya's training data is only partly published, and the published part includes non-commercial sources. The authors' own benchmark flags as "in training" `Tobi-Bueck/customer-support-tickets` (CC-BY-NC-4.0) and MS MARCO (Microsoft: non-commercial research only), plus LGPL-3.0, CC-BY-SA-3.0, `unknown` and undeclared sources; the full mix is not published. The tokenizer is Gemma 2's, whose Terms of Use may or may not reach it~~ | **Closed 2026-09-23 by owner decision.** The weights are Apache-2.0 and the owner has cleared Laya for shipping ("we searched and its Apache2.0"). The training-data evidence — NC sources named in the authors' benchmark, the unpublished full mix, the Gemma 2 tokenizer terms — stays recorded in `LICENSING.md` as the basis the owner weighed. Optional follow-up, not a blocker: ask the authors for the multilingual checkpoint's full training mix |
| 13 | **On-device cost of Laya is unmeasured.** 384 MB INT8 (357 MB opt-in `int8-partial`; SmoothQuant and static calibration tried 2026-09-23 and rejected — the full 58 MB saving costs answers), 256k vocabulary; on a desktop M4 CPU a 1,024-token question took ~1.1 s (INT8, ORT), a short one ~45 ms. A phone CPU is slower. DJL's Android native AAR also lags its Java API (0.33.0 vs 0.38.0). Spec claims in `PRODUCT.md` §3 that rest on the old ~150M, 7–25 ms figure and are now unverified: the per-frame live capture gate, "tens of milliseconds is imperceptible" on arrival triage, a retroactive sweep "in minutes", the game deciding "many times a second" (**on a desktop M4 CPU the game now measures 10 decisions/s at ~62–66 ms P50, ~80 ms P95**, with ~106-token questions; a phone is still unmeasured). The desktop app's sweeps measured a median **73–99 ms per item** on the sample (a light machine) and **125–152 ms** with the machine's load average near 20 — desktop latency depends on what else is running, and a phone shares its CPU with everything | A1 measures it on a real mid-range device before anything depends on the number. Keep states short — latency scales with tokens, and most judgments do not need 1,024. If the Android AAR does not match, pin DJL to 0.33.0 or build the JNI library ourselves |
| 14 | **Online sources do not work offline.** A proposed Composio bridge (email, calendar, social media) runs through Composio's cloud and needs the network and third-party OAuth; the Web tab's flight search (2026-09-23) fetches through our helper. Neither works in airplane mode, and both send something off the device. | **Governed by the online helper rules, `PRODUCT.md` §4a** (decided 2026-09-23): offline by default, fetch-only, never judge, send the minimum, labelled "Online" with source and fetch time, an off switch, fully usable offline with them off; user keys in the Keychain, never stored or logged server-side. The flight helper is `sambawy01/loupe-web-helper` (private), deployed on Railway. **Composio is still undecided** and, if built, is bound by the same rules. Not in the build order until the owner decides |

---

## Progress log

A dated record of work landed on `main`. Spec-track items above are marked **DONE** only when
their acceptance criteria are met; entries here record increments toward them.

- **2026-09-22 — Engine project and CI stood up.** Kotlin/JVM Gradle build (`engine` module,
  JDK 21, JUnit 5), the Gradle wrapper pinned to 8.14.3, and a GitHub Actions workflow that runs
  `./gradlew build` on every push and pull request to `main`. First engine primitive landed:
  `Probability`, the validated `[0,1]` value type that A4's response validation and A7's policy
  runner both depend on. This starts the CI-testable engine core (A3–A8). The Android app, the
  ONNX model runtime (A1) and the second backend (A2) are deferred until a device or the Android
  SDK is available, since neither an APK nor on-phone latency can be exercised in the headless CI
  environment.
- **2026-09-22 — `Distribution` primitive.** A normalised distribution over candidate labels,
  validated on construction (non-empty, every mass a valid `Probability`, masses sum to 1 within
  tolerance) with `argmax` for the selected answer. This is what a Choice returns (A1) and what
  the ledger stores in full (A5). CI green on the scaffold before this landed.
- **2026-09-22 — A4 (started): typed `Judgment.Choice` with response validation.** A sealed
  `Judgment` type; the `Choice` variant validates a raw model response into a `Distribution` over
  exactly its candidates, rejecting unknown labels, missing candidates, and malformed masses —
  A4's stated failure cases. Bool and Score variants follow.
- **2026-09-22 — A5 (started): append-only ledger.** `LedgerRow` records a decision's full
  distribution and a required propensity, plus a `criteriaHash` fingerprint of the judgment
  wording (added to `Judgment`); `Ledger` appends and reads rows with no mutate/remove API. Added
  `Distribution.getValue` for the selected-label lookup. Tests cover "a decision writes a row
  carrying a propensity", append ordering, snapshot isolation, and criteria-hash sensitivity to
  wording.
- **2026-09-22 — A6/A7 (started): the calibrated decision path.** `Recalibrator` (A6) maps a raw
  `Distribution` to a `CalibratedDistribution`; its constructor is `internal`, so a recalibrator
  is the only way to obtain one — making "the policy never reads a raw probability" a compile-time
  guarantee. Ships the `Identity` recalibrator as the honest baseline a fit must beat on ECE. The
  pure, total `Policy.decide` (A7) acts on the top calibrated label at/above a threshold and
  otherwise abstains to the uncertain queue (§4's "never acts when unsure"). Fitting isotonic/Platt
  calibration and the A8 counterfactual engine come next.
- **2026-09-22 — A6: fitted recalibration and ECE.** `Calibration.ece` computes expected
  calibration error over equal-width confidence bins on the top-mass label. `TemperatureScaling`
  is a one-parameter recalibrator (masses raised to `1/T` and renormalised — the probability-space
  form of dividing logits by `T`), fitted by deterministic coarse-to-fine NLL minimisation over a
  bounded range. Because scaling is monotone it never changes which label wins, only how sure the
  engine claims to be, which is what the A7 threshold reads. **A6's acceptance criterion now runs
  as a test:** on a synthetic model claiming 0.99 while right 70% of the time, the fitted
  recalibrator softens to ≈0.70 and beats the identity baseline on ECE (0.29 → <0.05). Fitting on
  real data still needs a fixture corpus (see open questions). 44 tests green.
- **2026-09-22 — A8: counterfactual engine.** `OffPolicy.estimate` computes IPS and SNIPS over
  logged propensities, reporting **support** and Kish effective sample size beside every estimate —
  the honest caveat, since an importance-weighted number only sees rows where the candidate agrees
  with what was logged. `OffPolicy.replayThreshold` replays a ledger against a candidate threshold
  (the same rule `Policy.decide` applies live) and reports the delta against observed reward with a
  seeded bootstrap percentile interval, so a given ledger and threshold always report the same
  interval. This is what D3's slider reads. `Policy.ABSTAIN` and `Policy.actionOf` added so an
  abstention is recordable and replayable. **A8's acceptance criterion runs as a test:** 1,000
  logged decisions replayed against a candidate threshold, delta positive with the interval
  bracketing it. The corpus is logged by an *exploring* policy, because off-policy evaluation needs
  the propensity variation A5 exists to capture. 54 tests green.
- **2026-09-22 — A3 (started): text-state pipeline and mechanical dedup.** `TextState.build` fits
  sources into a character budget under the spec's §8 rule: each item is kept **verbatim**,
  **truncated with an explicit marker**, or **removed**, and the state reports which — there is
  deliberately no "summarised" case, and a test asserts the surviving text is always a genuine
  prefix of the original. `Mechanical`/`MechanicalStats` expresses mechanical-first resolution and
  tracks the share of items answered without consulting the model, which is A3's acceptance
  criterion as a measured number. `ContentHash` and `Dedup` do exact byte-identical duplicate
  detection by SHA-256 (near-duplicates stay a model-side judgment). Still to come in A3: domain
  and certificate facts, date extraction, MIME sniffing, OCR-presence. 72 tests green.
- **2026-09-22 — A3: origin facts for the fraud check.** `OriginFacts` computes the mechanical,
  unforgeable half of the site-fraud signal: host extraction, registrable domain (eTLD+1), punycode
  and non-ASCII hosts, single-label **mixed-script homograph** detection, cross-origin form posts,
  and brand-versus-origin comparison. The brand test compares against the registrable domain's own
  label rather than the host string, because the attack is precisely putting the brand elsewhere in
  the name — `paypal.secure-login.com` contains "paypal" and belongs to `secure-login.com`; a test
  pins that case. Nothing here reads page content, honouring §4's rule that content can only add
  suspicion, never raise trust. The built-in public suffix set is an approximation and is now
  recorded as **risk 10**. 83 tests green.
- **2026-09-22 — A3: date extraction and validity arithmetic.** `DateFacts` finds ISO, numeric and
  textual dates and does the arithmetic the expiry radar needs (`daysUntil`, and `expiresWithin`
  for rules like "Schengen requires six months of validity"). **Ambiguity is surfaced, not
  guessed:** `03/04/2026` is two different dates, so a match reports `ambiguous` plus the
  `alternate` reading, and a judgment receiving one should treat it as uncertain rather than pick.
  Dates that name no real day (`2026-02-30`) are skipped rather than coerced. Identifying *what a
  document is* stays a judgment; finding its dates does not. 95 tests green.
- **2026-09-22 — the engine wired end to end.** `Backend` (A2's interface) returns a *raw*
  label-to-mass response rather than a validated `Distribution`, so a backend cannot bypass the A4
  boundary by handing over something already well-formed; there is no hosted implementation, since
  a network call would break the offline guarantee. `DecisionEngine` composes the §8 architecture:
  source text → text state → **mechanical checks first** → model → recalibrator → policy → ledger.
  Two properties are structural, not conventional: an item a mechanical check answers never reaches
  the model (a test asserts the backend is not called), and *every* decision — mechanical, acted or
  abstained — writes a row carrying its propensity. Adds optional ε-exploration: a deterministic
  engine logs propensity 1 everywhere, and a ledger with no propensity variation can only evaluate
  candidates that agree with what was already done, so exploration is what makes A8 answer anything
  interesting — a test replays an explored ledger through `OffPolicy`. This is the shape of proving
  milestone 2, "It decides"; the milestone itself still needs a real source and the real model.
  107 tests green.
- **2026-09-22 — §7 measurement harness.** `Calibration` gains reliability bins and a multiclass
  **Brier** score, reported beside ECE because a model can be perfectly calibrated and still
  useless — always saying 0.5 on a coin flip calibrates perfectly and tells you nothing.
  `Fixtures.splitByGroup` splits **by source group, never by item**: two photos of one receipt or
  two emails in one thread are not independent, and splitting them across arms leaks the answer and
  reports an accuracy the model will not reproduce; calibration is kept distinct from test so §9's
  "never fit on test" holds structurally. `Harness.evaluate` produces a per-judgment
  `JudgmentReport` — coverage, selective accuracy, accuracy at full coverage, abstention rate, ECE,
  Brier, reliability bins, and the dumb-baseline comparison — with **no aggregate across
  judgments**, since a single system accuracy would hide both numbers it averaged. The baseline is
  compared at *equal coverage*, the only honest comparison. **Milestone 6 runs as a test:** a
  judgment that merely ties its dumb baseline is reported as not beating it. 121 tests green.
- **2026-09-22 — D1: the uncertain queue.** `Distribution.margin` (gap between the top two masses)
  gives the selection signal; `UncertainQueue.select` ranks unreviewed decisions by `1 - margin` and
  mixes in a seeded **random audit arm drawn from confident decisions**. The audit arm is not
  padding: a labelled set drawn only from what the model found hard can never contain the case it
  is confidently and quietly wrong about, so drift in the easy majority would go unmeasured — it is
  what makes the corrections a usable calibration and fine-tuning set rather than a pile of edge
  cases. Rows already carrying a correction are skipped. D4's baseline comparison already ships
  inside `Harness`. 130 tests green.
- **2026-09-22 — C1/C2: judgment library and plain-language authoring.** `JudgmentLint` refuses
  text-judge phrasing — rating scales ("rate 1–10", "out of 100"), requests for prose
  ("explain why", "describe", "summarise"), requests to *write*, and two questions in one. Every
  rule enforces the same fact: this is a classifier and judgments never depend on a generative model, so a question
  asking for an explanation describes a product that was never built, and catching it at authoring
  time beats discovering it as a judgment that scores badly for invisible reasons.
  `JudgmentAuthor.compile` infers yes/no candidates for a yes/no question and *refuses to guess*
  otherwise. `BuiltInJudgments` ships the seven the spec names, each authored to the three-part
  template (invariant / what breaks it / what merely resembles it) — and **each is constructed
  through the same authoring path users take, so a test asserts the library passes the lint it
  holds users to.** C2's acceptance runs as a test: a compiled judgment is immediately usable by
  the engine. 147 tests green.
- **2026-09-22 — C3 (2 of 5): expiry radar and recurring-money census.** `ExpiryRadar` composes the
  split the spec insists on: deciding *what a document is* is a judgment the model makes; finding
  the date and comparing it to a `ValidityRule` is arithmetic the model never touches. Two safety
  choices are pinned by tests — the **latest** date on a document is the expiry (issue dates come
  first), and an **ambiguous** date resolves to the **earlier** reading, because warning early about
  a passport is recoverable and warning late is not. An item the engine abstains on raises nothing:
  an uncertain document type is no basis for telling someone their visa is lapsing.
  `RecurringMoney.census` groups charges by merchant and classifies cadence from the median gap
  (weekly/monthly/quarterly/annual, else **irregular** rather than an invented schedule), with
  `dormant()` for the still-charging-but-unused list. Amounts are `Long` minor units — money is
  never a `Double` in a thing that sums thousands of rows. Remaining watchers: term-change,
  impersonation, site-fraud composition. 165 tests green.
- **2026-09-22 — C3 (4 of 5): impersonation and site fraud; a real bug found.** `Impersonation`
  raises mechanical signals only — a known contact's display name over an address they never write
  from, a near-miss domain by edit distance, punycode or mixed scripts in the address, and a first
  sighting of an address. This is the watcher nothing else can build: a messaging app sees one
  channel and not the contact's history across the others. `SiteFraud` composes the origin facts
  into a `FraudAssessment` that **has no `isSafe`** — §4 forbids an all-clear, so an empty signal
  list is reported as "these checks found nothing, which is not an all-clear", and a test asserts
  the wording never says safe, legitimate or trusted.
  **Bug caught by these tests:** `java.net.URI` returns a *null host* for a non-ASCII authority, so
  `OriginFacts.host` was discarding homograph URLs as unparseable — silently dropping the exact
  attack the fraud check exists to catch. `host()` now falls back to manual authority extraction
  (stripping userinfo and port) and validates against a hostname pattern that allows unicode
  letters. Pinned by regression tests. 186 tests green.
- **2026-09-22 — C3 complete (5 of 5): term-change detection.** `TermChangeDetector` extracts
  labelled amounts from two versions of the same document and reports what moved, plus terms added
  and removed. A **currency marker is required**, deliberately: matching bare numbers would turn
  every policy number and date in a statement into a "term". Labels key on their last two words, so
  "Your monthly premium" and "Monthly premium" agree, while a label reworded beyond that reads as a
  removal plus an addition — the honest result, since we cannot know they are the same term. The
  spec's own example runs as a test: a premium going £450.00 → £553.50 is reported as **+23%**.
  All five watchers now have their mechanical halves. 196 tests green.
- **2026-09-22 — A3 mechanical extractors complete.** `MimeFacts` sniffs content type by leading
  signature, and **magic bytes beat the extension, always**: a file named `.pdf` that begins with
  `PK` is a zip whatever it claims, and a pipeline trusting the name is one rename away from
  feeding a judgment something it cannot read. `extensionLies()` reports that disagreement
  directly. `OcrFacts.hasUsableText` gates on whether OCR produced enough text, enough of it
  letters, to judge on — an image yielding three stray marks must reach the uncertain queue rather
  than get a confident answer about noise. A3's extractor set (hash, dedup, MIME, dates, domain and
  certificate-adjacent origin facts, OCR-presence, text state) is now complete. 209 tests green.
- **2026-09-22 — D2/D3: visible calibration and the threshold slider.** `ThresholdSlider.preview`
  counts what a candidate threshold would have changed about decisions *already logged* — how many
  more or fewer items acted on — and grounds the cost half only in items the user actually
  corrected. Where no correction covers the affected items it reports **"the cost is unknown"**
  rather than producing a confident figure from nothing, which is the same discipline the engine
  applies to its own answers. `VisibleCalibration` reports agreement, ECE, reliability bins and the
  specific **overconfident bins** — per judgment, with **no API to average across judgments at
  all**, because "is this a receipt" at 94% and "is this urgent" at 61% have no meaningful mean and
  a single system number would hide both. 221 tests green.
- **2026-09-22 — F4: export.** `Export` writes judgments, calibration and decision history as
  portable files. The ledger exports **losslessly** — full distribution and propensity, not just
  the chosen label — because these files are what an overnight fine-tune reads and what every later
  counterfactual is computed from; an export keeping only the answer would be a record of what
  happened with the reason thrown away. JSON Lines for the ledger, so a long history appends
  without rewriting and a truncated file still parses to its last complete line. The JSON emitter
  is hand-rolled and adds **no dependency**: licence provenance is a documented concern here, and
  correct escaping plus locale-independent number formatting is a small thing to own outright.
  233 tests green.

- **2026-09-23 — Risk 10 closed: the real Public Suffix List.** The built-in approximation — 28
  two-label suffixes — is gone, and the engine now reads a pinned snapshot of Mozilla's list
  (`public_suffix_list.dat`, VERSION `2026-09-21_18-50-07_UTC`) from its own resources, once, on
  first use. `registrableDomain` implements the whole algorithm rather than "last two labels, or
  three": normal rules, wildcards (`*.ck`), exceptions (`!www.ck`, which beat the wildcard), the
  implicit `*` rule for an unlisted TLD, case, one trailing dot, IP literals and bare hosts
  (no registrable domain), and IDN — labels are matched in their IDNA ASCII form and the answer is
  returned in the form it was given, so a homograph host stays visibly itself. **Both sections are
  used by default, deliberately:** the PRIVATE section is where `github.io` and `blogspot.com`
  live, and `paypal.github.io` belongs to whoever owns that account, not to GitHub — treating it
  as `github.io` would lend a tenant the host's name. `PublicSuffix.ICANN` exists for the rare
  caller that wants registry semantics. The rule set is still a plain `Set<String>` parameter at
  every call site, so no caller changed. **The engine stays offline:** the refresh path is
  `tools/update-psl.sh`, a developer script that re-downloads, sanity-checks (section markers,
  VERSION and COMMIT lines, rule count) and re-hashes; a test fails if the file and its recorded
  SHA-256 disagree. The official upstream test vectors (CC0) run as a test, all 77 passing. MPL-2.0,
  bundled unmodified; recorded in `THIRD_PARTY_NOTICES.md` and `LICENSING.md`. 376 tests green.

- **2026-09-23 — Epic #7 child G: the game on iPhone.** `:game` is now Kotlin Multiplatform (jvm,
  iosArm64, iosSimulatorArm64) with the rules unchanged; JVM-only pieces moved to `jvmMain`
  (`AsyncDecider`, `Match.report`), and `GameClock` is an expect object (System.nanoTime / the
  kernel's monotonic clock). `ParityTest` (commonTest) digests river rows, whole baseline sessions
  (world bits, legal sets, decisions every tick) and `Match` episodes as raw IEEE-754 bits and pins
  the same golden values on JVM and the iOS simulator: identical. The deterministic tests
  (Simulation, Mechanics, StateText) moved to commonTest and run on both (test names lost their
  commas: Kotlin/Native rejects them). New `HostedDecider` lets a host run the pilot on its own
  thread; `GameSessions` gives Swift default-free factories. `:game` is exported through LoupeKit.
  iOS: `ios/Loupe/Game/` — SpriteKit `RiverScene` (pooled nodes, pixel-snapped, nearest-sampled
  original pixel art in the app palette), `GameController` (fixed 60 Hz ticks from accumulated time),
  `PilotScheduler` (Laya's `decide` on one shared serial background queue, result handed back on
  main; never blocks a frame), touch mapping (`TouchSteering`: drag steers toward the finger with a
  deadband, hold fires, tap pauses; auto-fire toggle). Watch mode flies the same `ModelPilot` as
  the desktop, shows raw (uncalibrated) bars, decisions/s, p50 latency and a scoreboard against the
  baseline on the same seed in lockstep; without the model the baseline flies with a note and a link
  to Me → Laya model. Entry: Play card on Now, Game section in Me (still 5 tabs), first-launch
  onboarding offering "Watch Laya fly". Pause on background, reduced motion (system or in-app: few
  sparks, no shake), VoiceOver labels on the HUD, haptics on hits (toggle). Measured on the
  iPhone 17 Pro Max simulator (M-series Mac, debug LoupeKit): 58–60 fps in both modes; Laya
  7–9.5 decisions/s at p50 76–86 ms. Tests: 2 new common test classes (on both platforms), XCTest
  `GameInputTests` (7) and `PilotSchedulingTests` (6, fake Backend), UI `GameUITests` (2). Real-device
  fps and latency still need an iPhone.

- **2026-09-23 — Epic #7 child 4: the Unsure queue and measurement on iPhone.** New shared
  `dev.loupe.kit.measure.JudgmentMeasure` (loupe-kit common) ports the desktop `Analysis` D1-D4
  selection: the queue across **all** judgments (`UncertainQueue.select` over every judgment's
  current-wording rows, audit share 0.2, seed 11, only items still scanned), answers as
  `CorrectionRecord`s keyed by item + criteria hash, undo as an appended retraction, the D2 summary
  with the desktop's gates (agreement from 10, ECE/Brier/reliability bins from 30; mechanical and
  unusable rows excluded), the D3 preview over logged rows ("N more acted on · you would have
  rescued M", or "unknown"), D4 through `Harness.evaluate` with the replay backend, and Me's pooled
  line (from 10 corrections, else how many more). New `UserJudgment.useBaseline` (codec: written
  only when on; not in the criteria hash): when set, `JudgmentSweep` answers by the baseline as a
  mechanical row (`resolvedBy` = `baseline`), so it never counts as model evidence. The desktop has
  no such switch yet. iOS: `UnsureQueueView` (one button per option, Skip, Undo; mascot thinking
  while items wait, `found` after an answer), `MeasureView` (from Results), "Needs you: N" on Now
  and My judgments, Me's agreement line; Loupe Station's text-safe ok/warn/danger tokens and its
  queue wording. Tests: `JudgmentMeasureTest` (5, JVM + iOS sim, mirrors the desktop's one-loop
  test), XCTest `UnsureQueueTests` (4, fake model), UI `UnsureQueueUITests` (answer an item, the
  count drops; DEBUG `-LoupeQueueDemo` seeds a throwaway ledger with a stand-in scorer).
  *Note:* D2 says there is no overall accuracy; the Me line is the owner's request, pooled only over
  corrected model answers and gated, with per-judgment figures on each Measure screen.

- **2026-09-24 — Issue #8: Laya 0.3.20 (Station leads, the phone follows), score levels highest
  first, bias_correction and OCR settings.** Reference package pinned to `laya @ 23a17522` (v0.3.20).
  The PyPI wheel (sha256 `6039e802…`, Station's) is byte-identical to that commit's `laya/`.
  Checkpoint unchanged (`9d628fd9…`). The upstream diff touches `build_sequence` (a tokenizer-side
  48-token cap, reuse of `state_ids`, the empty-room left-truncation fix) and the head
  (training-only checkpointing, a RoPE config shim that is a no-op on transformers 5). **Graph and
  sequences unchanged:** regenerated golden 34/34 and criteria 8/8 input ids are identical, with
  torch probabilities within 1e-8, so the ONNX files are **not** re-exported and the SHA-256 pins in
  `LayaModelStore`/`models.json` stand. Parity against the new reference: FP32 34/34 and 8/8
  (max Δp 2.5e-6); INT8 33/34 (the same `en-sentiment-5` near-tie) and 8/8; int8-partial 33/34 and
  8/8. The export stays FP32 on CPU (0.3.20's fp16 autocast is MPS/CUDA only at 5+ rows; Station
  forces fp32). **Tokenizer:** new `tools/make-laya-tokenizer-fixture.py` writes `tokenizer.json`
  with 21 en/ar/arz/Franco/es/fr/mixed strings; DJL reproduces 21/21 (iOS test added).
  **`REVERSE_SCORE_ON`** (Station, upstream #131): `LayaRuntimeRules` in `backend-laya-common`
  sends score levels highest first and maps them back in `ChoiceScoring`, via
  `Judgment.Choice.ordinal`, which is not in the criteria hash. The fixture `score-reversed.json`
  (5 cases) matches FP32 5/5 and INT8 5/5. **Settings:** `global.bias_correction` (off; iPhone runs
  off only), `features.scan.ocr` / `ocr_max_pages` (Vision OCR of Photos and scanned PDFs; the
  sample scan stays without OCR). **Re-measure:** `docs/LAYA-UPGRADE-MEASURE.md`. Synthetic data:
  only `urgency` changes (21/45 sample answers; agreement with the keyword rule 15 → 10), and Laya
  beats its keyword baseline on the examples for 6 of 55 templates. No accuracy claim.
- **2026-09-24 — Google Safe Browsing moved to API v5 local-list mode (v4 shuts down 2027-03-31).**
  `ios/Loupe/Online/SafeBrowsing.swift`: `hashLists:batchGet` for `se-4b`/`mw-4b`/`uws-4b` (Rice-delta
  decoding, removals then additions, SHA-256 checksum, `minimumWaitDuration`), v5 URL canonicalisation
  and host-suffix/path-prefix expressions (eTLD+1 from the pinned PSL), `hashes:search` only for
  uncached local prefix hits, results cached per prefix for `cacheDuration`. Key sent as
  `X-Goog-Api-Key`, never in the URL; §4a rules unchanged. Shapes checked against Google's v5
  discovery document. `SafeBrowsingV5Tests` (Google's canonicalisation vectors and Rice example).
- **2026-09-24 — Model settings on the iPhone, matching Loupe Station (schema v1).** Owner "Go":
  both apps expose Laya's parameters the same way, after Station's Folder Scan ran with Laya
  silently off. Shared `dev.loupe.kit.settings`: `EngineSettings` (Station's keys, types, enums,
  ranges and defaults, plus feature ids `judgments`, `flights`, `game` and the key
  `features.game.max_decisions_per_s`), validation and clamping, reset / reset_all, Station's GET/PUT
  bodies, `engine_settings.json` in Application Support via `:persistence`, and `ModelMemory`
  (`memory_mode` full / balanced / low as Laya's load-unload policy, `idle_unload_min` on a 30 s tick,
  reload on next use, never under a decision). Consumers read a `RunPolicy` at each run:
  `JudgmentSweep` (use_laya off → rules only, `mechanical:laya-off`, no model needed;
  accept_confidence, text_chars, rules_first, baseline_switch), `SweepCoordinator`, `WatcherRun`,
  `FlightJudge`, `PrivacyCheck` (read_content), the game pilot's budget and a decisions-per-second
  cap. iOS: Me → Model settings (global + per-feature rows with value, default, trade-off, Reset;
  Restore all with confirmation; EN + AR, RTL, Station's Arabic reused), and "Laya was off for this
  run — answers come from rules only. Turn it on" on each feature's screen. Defaults equal the old
  behaviour except `memory_mode` (balanced, owner decision; the phone used to keep Laya loaded).
  No env/MDM on iOS. Full key list and mapping: `docs/MODEL-SETTINGS.md`. Tests:
  `EngineSettingsTest`, `SettingsConsumersTest`, `ModelMemoryTest` (JVM + iOS simulator),
  `LoupeTests/ModelSettingsTests.swift`, `LoupeUITests/ModelSettingsUITests.swift`.
- **2026-09-24 — One phishing formula, automatic baseline, online phishing checks (owner decisions
  A–D).** Uncommitted pending review.
  - **A · One phishing / site formula** for Loupe and Loupe Station, written down in
    [`PHISHING-FORMULA.md`](PHISHING-FORMULA.md) with 54 shared test vectors
    ([`phishing-vectors.json`](phishing-vectors.json), run on the JVM and the iOS simulator by
    `PhishingFormulaTest`). It replaces the six "Station vs engine" differences logged with children 11
    and 12: (1) brand list first, the engine's name check only for unlisted brands (Microsoft on
    `live.com` is now plainly safe); (2) an IDN is a signal only when brand-confusable or mixed-script
    (Station's `punycode` 10 / `sender_punycode` 10 and the engine's `punycode-host` removed); (3) the
    engine's per-label script check, on the decoded label; (4) cross-domain posts strong (30) for
    password **or card** forms, weak (`form_posts_elsewhere` 5) otherwise; (5) the full pinned PSL
    for host control, the 12 non-PSL shared-hosting names add `shared_hosting` 10, and online facts use
    the ICANN-only registrable domain (none under a PRIVATE suffix); (6) contact impersonation feeds
    the email score (`contact_homograph_domain` 60, `contact_lookalike_domain` 45,
    `contact_name_other_address` 30). Implemented once in loupe-kit (`SiteSignals`, `SiteScoring`,
    `OnlineSignals`, `Phishing`); the engine keeps a thin API (`SiteFraud` facts, `OriginFacts.unicodeHost`,
    public `Impersonation.levenshtein`). `SiteCheckResult` now carries **one** verdict (the
    side-by-side engine result is gone from the kit, iOS Mail triage and the desktop watchers screen);
    the fraud watcher reports caution/danger verdicts. Before/after on the sample and every fixture is
    in PHISHING-FORMULA.md §9: the sample's fake PayPal stays danger 100; the only sample change is
    "Mum" from a new address (safe → caution 30, not flagged).
  - **B · Automatic baseline** (see D4 above): shared `AutoBaseline` (verdict, `resolve`),
    `BaselineMode` on `UserJudgment` (codec: written only when not Auto; a legacy `useBaseline: true`
    reads as Always baseline), `JudgmentSweep` / `SweepCoordinator` / the desktop sweep switch by
    themselves, D4 counts automatic rows through the kept model answer. iOS Measure: "Who answers"
    Auto / Always baseline / Always Laya with the verdict line; desktop Baseline screen: the same card.
  - **C · Online phishing checks on iPhone** (PRODUCT.md §4a): `ios/Loupe/Online/` — domain facts
    client for the helper's `POST /v1/domain-facts` (404 → "online checks not available yet", 429 →
    back off, `sources: []` never "checked", 6 h in-memory cache, a fresh random install id per
    request), OpenPhish / keyless PhishTank lists downloaded and matched on the phone, Google Safe
    Browsing Update API v4 with the user's key from the Keychain (hash prefixes only). Me → Online
    phishing checks (all off by default); Mail triage shows an "Online" status line and labels every
    online reason with source and time. The helper route may not be deployed yet: built against fakes.
  - **D · Docs:** this entry, D2's owner override (Me's "Agrees with you X%" line stays), D4's
    automatic baseline, PRODUCT.md §4a (the helper's in-memory 6 h per-install cache; the online
    phishing checks' rules).
  - **Tests:** `PhishingFormulaTest` (vectors + PSL modes, helper parsing, ages, feeds, gates, labels),
    `AutoBaselineTest` (30-correction gate, strict win, tie keeps Laya, kept model answer, ledger
    round-trip, overrides, legacy file, the coordinator switching), updated `SiteSignalsTest`,
    `SiteFraudTest`; XCTest `OnlineChecksTests` (zero requests when off, only ICANN domains sent,
    404, 429, empty sources, lists, Safe Browsing prefixes only) with a fake `URLProtocol`.

- **2026-09-24 — Epic #7 child 17: the drone mascot, an alternate to the robot.** On the owner's
  "Go" for epic #7 children 10–17. Uncommitted pending review.
  - **Ported from Loupe Station** (`~/laya-studio` `laya_studio/static/js/mascot.js` + `static/mascot.css`
    at `ea7697a`): the SVG orb-drone's geometry (18-unit orb, 30×13 visor, pill eyes, beacon, seam,
    27×7.5 orbit ring tilted −9°), colours (`--m-*`, light and dark), timings (4.8 s float, 1.4 s
    thinking, .7 s hop, .9 s flash, 2.6–6.8 s blinks, 30% glances, EYE_X/Y) and the reduced-motion stills.
  - `ios/Loupe/Design/Mascot/`: `MascotKind.swift` (setting, key `mascot.kind`, default robot),
    `DroneRig.swift` (pure: `DroneLook.of` mapping, `DroneRig` state machine), `DroneScene.swift`
    (SceneKit model + visor face renderer). `MascotView` reads the setting; `MascotSCNView` now drives
    a `MascotDriver` (RobotDriver / DroneDriver), so the pause rule is shared. Stills and the no-Metal
    PNG (`MascotDrone`, rendered from the app by `DroneRigTests`) cover the drone.
  - Mapping: idle→idle; greeting→booting then happy ("powered up"); watching→idle with eyes and body
    following `lookAt`; scanning→thinking plus booting's visor scan line; thinking→thinking; found→happy
    with flash; happy→happy (smaller hop); empty→unsure; tap→happy reaction 2.2 s. Station's `error`
    has no Loupe state and is unused.
  - Tests: `DroneRigTests` (mapping, colours, hop/bob keyframes, Reduce Motion stills, blinks and
    tap, face key, kind setting default/persist/unknown, pause rule with the drone, view built for
    both kinds, VoiceOver hidden); `MascotKindUITests` (switch to Drone in Me, persists across relaunch).
  - Performance: `-LoupeMascotGallery -LoupeMascotKind drone -LoupeMascotFPS` holds 60.0 fps with
    eight live drones at 170 pt (iPhone 17 Pro Max simulator). Contact sheet beside Station's
    browser-rendered moods: `~/.gstack/projects/sambawy01-jevistication/designs/ios-v1/13-drone-states.png`.

- **2026-09-24 — Epic #7 child 16: Workflows — reply drafts and a second opinion (opt-in writing
  assistant).** On the owner's "Go" for epic #7 children 10–17. Uncommitted pending review.
  - **Ported from Loupe Station** (`~/laya-studio` at `ea7697a`): `workflows/email_reply.py` (BASE_RULES,
    OUTPUT_RULES, reply schema, tag neutralising, `thread_subject`, `clean_body`), `workflows/second_opinion.py`
    (SYSTEM_PROMPT, question block, per-question schema, off-option answers fall back to Laya's),
    `llm/client.py` (OpenAI-compatible `POST {base}/chat/completions`, JSON mode + schema instruction,
    one repair turn, retry on 429/5xx/connection errors with Retry-After, typed errors, `redact`,
    `extract_json`), `llm/providers.py` (`check_base_url`), `mail/drafts.py` (`reply_subject`),
    `static/js/{assist,llm-settings}.js` (flows, wording). Swift only, in `ios/Loupe/Assist/`.
    **Not ported:** the server-side key store, public URL and log redaction; the Laya steps
    (`workflows/laya_steps.py` injection guard, reply planner, brand gate — they need Station's workflow
    templates; the draft says it was not checked by Laya); `actions.py` `save_email_draft` (Loupe never
    writes into a mailbox); DeepSeek model listing / connection test.
  - **Rules as built** (PRODUCT §4, §4a): off by default — unconfigured or switched off, `isReady` is
    false, no request is built (tests assert zero requests). Provider: an HTTPS OpenAI-compatible
    endpoint + model + key (BYOK, Keychain `WhenUnlockedThisDeviceOnly`), or Ollama on the local
    network (private IPv4 / `.local`, no key). Every call shows an Online badge with the provider's
    name and a preview of exactly the messages sent (the target email's sender, subject and own text
    — the quoted thread cut, ≤ 7 200 chars; or one item's text ≤ 6 000 with the question); the user taps
    "Send to <provider>" before each call. Ephemeral URLSession (no cookies, no cache).
  - **Drafts:** Mail triage → "Draft a reply" (not offered on suspected phishing) → labelled **Draft**,
    queued in Review as an `email_reply` item (new shared feature/kind in `ReviewRegistry`, action
    `none`); Approve (in the sheet or in Review) records it; then Copy, or Open in Mail
    (`MFMailComposeViewController`, pre-filled — the user taps Send). Loupe never sends, saves or files it.
  - **Second opinion:** judgment result → item → "Ask <provider> for a second opinion (Online)" →
    "Second opinion: agrees / disagrees — answer — reason" beside Laya's answer. **Display only and not
    logged** (the simpler honest option): no ledger row, correction, calibration or queue change;
    kept in memory for the session. Station's `disagreements.jsonl` is not ported.
  - **Tests:** `LoupeTests/AssistTests.swift` (20, fake `URLProtocol`, no provider contacted): gating
    (off by default, no key, disabled, unconfirmed → zero requests; remove forgets the key), URL rules,
    prompt construction and minimisation (quoted thread absent from the request body; tags
    neutralised; caps), request shape (POST, Bearer, `response_format`, preview == body), parse,
    repair turn, 401/404/5xx/429/timeout/unreachable/bad JSON, key redaction, second opinion writes
    nothing to the ledger directory, a draft waits in Review and approval only records it;
    `LoupeUITests/AssistUITests.swift` (Me shows Writing assistant Off with the switch off; DEBUG
    `-LoupeFakeAssistant` → Mail triage → Draft a reply → preview → Send → a Draft appears → Approve → Copy).
  - **PRODUCT.md note (not edited):** §4a says "There is no AI on any server — not ours, not a
    vendor's", while §4 (changed in `b05f996`) allows "a provider with your own key". Built per §4:
    the assistant is the user's own provider, never Loupe's, opt-in, and never judges; §4a's line may
    want a clarifying clause.

- **2026-09-24 — Epic #7 child 15: the Items inbox (CSV, .eml / .mbox, ZIP archives, share-sheet
  imports).** On the owner's "Go" for epic #7 children 10–17. Uncommitted pending review.
  - **Ported from Loupe Station** (`~/laya-studio` at `ea7697a`, the `laya_studio/items` package and
    `tests/test_items.py`; `static/js/sources.js` for the removable-imports list): new common code in
    `:sources-common` — `CsvRows` (Station's `csvimport.py`: UTF-8/BOM/UTF-16/Windows-1252 decoding,
    delimiter sniffed among `, ; tab |`, "no header row" when row 1 holds a date or an amount, the
    English + Arabic header names, mapping by values as fallback, `parse_amount` / `parse_number` with
    1,234.56 / 1.234,56 / parentheses / Arabic-Indic digits / currency markers, debit–credit
    direction, the row identity "normalised content + occurrence"); `Inbox` (batches, manifest,
    remove; Station's `uploads.safe_name`, 20 MB CSV cap and 100 000-row cap); `ZipReader` + a
    pure-Kotlin `Inflate` / `Crc32` (no dependency) with **Station's archive limits** from
    `scan/content.py` (more than 10 000 members refuses the archive; a member of 4 MB+ claiming a
    ratio over 200 is skipped) plus: a member inflating past its declared size is abandoned, 50 MB per
    member, 512 MB per archive, path traversal / absolute / drive-letter / backslash / `..` names
    refused, symbolic links (Unix `S_IFLNK`) and other non-regular members refused, encrypted, ZIP64
    and non-deflate members not read, archives inside archives not opened, `__MACOSX` and dot files
    skipped silently. `InboxFs` (expect/actual) is the only writer: exclusive create, no-follow,
    0600, inside `<home>/inbox-batches/` only. `SourceLibrary.forget`, `DateOrigin.CSV_COLUMN`,
    `PhoneSourceIds.INBOX` added.
  - **Phone changes vs Station:** no server, uploads or SQLite item store — each import is a batch
    cached as its own `SourceLibrary` source (`inbox-<id>`) with a manifest (`sources/inbox.json`);
    CSV rows are imported with the detected mapping (no mapping editor yet) and **every** non-empty
    row becomes an item (Station keeps only statement rows) — rows with a date and an amount also
    carry `amount_minor`, `currency`, `merchant`, `direction`; `.eml` / `.mbox` go through the existing
    `SourceScanner` / `MimeParser` instead of Station's `archive.py`; the same statement imported
    twice is marked `duplicateOf` (Station: one item), and the recurring-money watcher skips the
    marked rows.
  - **Flow:** every item has source id `inbox` and the fact `imported` ("Imported · Files ·
    statement.csv · 2026-09-24"), shown as the item's source in results and on Open item; they reach
    judgments, the sweep, watchers (new: `WatcherRun.charges` reads Inbox rows' statement facts), the
    privacy check and mail triage through `SourcesService.items()`. Sources → Inbox: switch, Import
    files… (Files picker), Paste text or a link, each import with counts (items, CSV rows, emails,
    skipped, already imported), its items and skip reasons, Remove (its items, cache and Loupe's copy;
    originals untouched). Share sheet: CSV / TSV / EML / MBOX / ZIP files, text and links now go to a
    hidden `.import` folder in the App Group inbox and become one "Share sheet" batch when the app
    opens; PDFs, images and other files still go to Files' "Send to Loupe" as in child 7.
  - **Tests:** `CsvRowsTest` (Station's CSV cases: name/value mapping, Arabic `;` statement,
    header-less file, month-first, amount forms, sniffing, decoding), `ZipReaderTest` (malicious
    fixtures built byte by byte: traversal, absolute, drive, backslash, symlink, ratio bomb, lying
    size, member count, archive total, encrypted, CRC, nested, unsupported method, truncated),
    `InboxTest`, and loupe-kit `InboxChargesTest` — all on the JVM and the iOS simulator;
    `LoupeTests/InboxTests.swift` (import → items / judgeable / removal; ZIP with mail reaches mail
    triage, traversal refused; share-sheet waiting folder collected once; paste + switch);
    `LoupeUITests/InboxUITests.swift` (`-LoupeFixtures -LoupeInboxDemo` imports a statement CSV; its
    three rows show as items, a row opens with its column context, the import is removed).
- **2026-09-24 — Epic #7 children 13 and 14: the Review queue (approve / reject / retry) and preset
  packs.** On the owner's "Go" for epic #7 children 10–17. Uncommitted pending review.
  - **Review queue, ported from Loupe Station** (`~/laya-studio` at `ea7697a`,
    `review/{service,store,registry}.py`, `static/js/review*.js`): new `loupe-kit` package
    `dev.loupe.kit.review` — `ReviewQueue` (pending → approved → applied | failed → retry → approved;
    pending | failed → rejected with a required reason; every step one status change plus one row in
    the append-only log; approve with edits, validated, `edited` when they differ; Station's limits,
    event names, actor strings, attempts, newest-first listing with a cursor, per-status counts; 404 /
    409 / 413 / 422 kept as refusal codes), `ReviewRegistry` (kinds as flat text fields with limits;
    actions with their kinds, params, own `validate` and `reversible`), `ReviewProducers`, and
    `ReviewCodec`. **Phone changes:** no agent keys, rate limits or HTTP routes (only the app's own
    checks submit, as Station's in-process `submit_proposal`); a `source_key` per proposal, never
    queued twice (the checks re-run on every scan; a rejected proposal does not come back); the app
    runs the action between `approve` and `finish` / `fail`; a sixth status `undone` for reversible
    actions; storage is `review-items.json` (atomic replace) + `review-log.jsonl` (appended, synced,
    never rewritten) beside the decision ledger instead of SQLite. Verdict actions also append to the
    ledger's corrections log.
  - **What is proposed** (nothing runs until approved): privacy check → remove the extra copy of a
    duplicate, only for copies the app can reach (picked Files, Send to Loupe; the sample, mail and
    photos stay suggest-only), held for Undo; mail triage → confirm phishing for an undecided flagged
    message (corrections `mail-phishing`, Undo = retraction); watchers → keep an unanswered finding on
    Now as confirmed (Undo = retraction); judgments → add a pack question as a judgment
    (`judgment.add`, Station's `save_preset`; the C2 lint runs again at approval, an edit that breaks
    it is refused; an approval never replaces a judgment of yours). Retry re-runs the producing check,
    then the action.
  - **Preset packs, ported from Station** (`packs.py`, `schemas.py` question rules, `static/js/packs.js`):
    new `dev.loupe.kit.packs` — `PackFormat` validates exactly as Station (whole pack refused, every
    problem listed with Station's location, e.g. `presets.0.questions.q.score.criteria`; lengths in code
    points as Python counts them), `PackJudgments` maps each question to a judgment through the C2
    lint (noul → yes/no with the pack's true/false descriptions as options; choice → pick one; score →
    score bands), ids `j-<preset>` / `j-<preset>-<question>`, conflicts by id (skip / replace / keep
    both), and exports your judgments as a pack that imports back to the same ids.
    `examples/packs/bistro-cloud.json` is copied verbatim and bundled, labelled "Example pack: a
    delivery kitchen's own rules". Of its 29 questions, 14 become judgments; 15 are refused by the
    lint (13 yes/no questions without descriptions would be bare yes/no; one asks for an explanation;
    the social-post gate's questions ask the engine to produce text) — shown in the preview with the
    reasons. Station allows a score of 20 levels; the lint keeps 10.
  - **iOS:** `Loupe/Review/` — `ReviewService` (collects proposals whenever a check's summary
    changes, runs the approved action, Undo), `ReviewView` (grouped by check; Approve / Reject with a
    reason / Retry / Undo; select several and approve them at once; copy says it is actions, not the
    Unsure queue's labels); Now card "To review: N". `Loupe/Judgments/PacksService.swift` +
    `PackViews.swift` — Judgments → Packs: import from Files, "Open in Loupe" from the share sheet or
    Files (`CFBundleDocumentTypes` public.json, `onOpenURL`), try the example, export my judgments
    (share sheet); preview before adding (what will be added, what the lint refused and why, a
    conflict choice); "Add N judgments" or "Send to Review, to approve one by one". Privacy and mail
    runs requested while one is going now run again after it (a scan landing mid-run was missed).
  - **Tests:** `ReviewQueueTest` (14: `test_review.py`'s lifecycle, validation, size cap, edits,
    reason, fail → retry → applied, failed → rejected, filters and cursor; plus source keys, undo,
    judgment lint at approval, reopen from files with the log only growing), `PackFormatTest` (9:
    `test_packs.py`'s Bistro and validation cases, round-trip, code-point lengths),
    `PackJudgmentsTest` (4: the lint per question, conflicts, export → import); JVM + iOS simulator.
    XCTest `ReviewTests` (8: proposals from each check, approve privacy removes and Undo restores a
    real file, mail verdict in the ledger with Undo, reject not proposed again, fail → retry after the
    producer, batch approve, pack judgments approved one by one, relaunch) and `PacksTests` (4:
    example preview, conflicts, invalid pack, export → import round-trip). UI `ReviewPacksUITests`:
    approve a privacy-check proposal (a real duplicate pair in the throwaway inbox,
    `-LoupeFixtures -LoupeReviewDemo`) and undo it; import the example pack and see its judgments.

- **2026-09-23 — Epic #7 children 11 and 12: mail triage + phishing, and brand-lookalike site
  checks.** On the owner's "Go" for epic #7 children 10–17. Uncommitted pending review.
  - **Shared rules, ported from Loupe Station** (the owner's `~/laya-studio`, commit `ea7697a`):
    new `loupe-kit` packages `dev.loupe.kit.site` — `Brands` (`browser/brands.py`: 45 brands,
    shorteners, suspicious TLDs; Station's shared-hosting list from `psl.py`), `SiteSignals`
    (`browser/signals.py`: WEIGHTS, RISK_CODES, PHISHY_WORDS, confusables, skeleton, OSA look-alike
    rule, host/url/form/branding checks), `SiteScoring` (`browser/scoring.py`: 30/60 levels, Laya
    caps and gates as data, impostor/pressure login) and `SiteCheck` (Station's verdict **beside**
    the engine's `SiteFraud.assess`, neither changing the other); and `dev.loupe.kit.mail` —
    `Phishing` (`mail/phishing.py`: every weight, threshold 50/25/25, MAIL_BRANDS, freemail,
    trackers, service words, reason texts), `MailClassify` (`mail/classify.py` `triage_flags` /
    `label_plan` / label naming / transactional pattern, `mail/provider.py` categories, and the
    `wf-email-triage` keyword rules of `measure/baseline.py` declared as the engine's `Baseline`s),
    `MailTriage` (rows, sections, `sort_rows`, corrections `mail-phishing` / `mail-phishing-v1`:
    `safe` / `phishing`, Undo = retraction). Registrable domains come from the engine's pinned PSL
    (`OriginFacts.registrableDomain`), never Station's `psl.py`. No Composio, no model call.
  - **Phone deviation:** Station's classifier reads Laya's answers; on the phone the keyword rules
    answer every question (Station's "baseline answers" path), and the phishing-wording rule fills
    Laya's advisory slot (at most +20, only next to deterministic evidence; alone it never flags).
    Weak questions (category, urgency, is_phishing) keep Station's `weak` marking. The keyword rules
    run through a lookbehind-free matcher (0.5 s → <5 ms per email on the simulator), pinned to
    `Baseline.answer` by `MailClassifyParityTest` on every case and sample email.
  - **Station vs engine, both results kept visible, no engine threshold changed** *(superseded
    2026-09-24 by the one formula in PHISHING-FORMULA.md, which settles each difference below)*:
    (1) brand ownership — the engine's `brandMatchesOrigin` compares the registrable domain's first
    label with the brand's name, Station uses the brand's domain list (+ country domains), so e.g.
    "Microsoft" on `live.com` or "Google" on `youtube.com` is an engine `brand-origin-mismatch` but
    Station known-good; (2) punycode — the engine warns on any `xn--` host, Station scores a
    single-script IDN (`مثال.مصر`) 10 = safe and a homograph 60; (3) mixed scripts — Station named
    scripts by Unicode character names, the port uses the engine's script property (digits of another
    script no longer count as a script); (4) forms — the engine flags any cross-domain form action,
    Station only password forms (plus an https→http post); (5) PSL — `googleapis.com` and
    `withgoogle.com` are PRIVATE suffixes in the Mozilla list, so hosts under them are no longer
    Google's known-good, and 12 of Station's shared-hosting names (`wordpress.com`, `weebly.com`,
    `glitch.me`, `railway.app`, `loca.lt`, `serveo.net`, `godaddysites.com`, `000webhostapp.com`,
    `jimdosite.com`, `site123.me`, `strikingly.com`, `tilda.ws`) are not suffixes there — a customer
    site reads as a subdomain (`paypal.wordpress.com`: `brand_in_subdomain` 30, Station
    `brand_other_tld` 20); shared-hosting logins are still matched by name; (6) the engine's
    `Impersonation` look-alike (Levenshtein 1–2 against a contact's domains) and Station's brand
    look-alike (OSA, indels only for 5–7-letter tokens, ≤4 letters never) answer different questions
    and are not merged.
  - **iOS:** `Loupe/Mail/` — `MailTriageService` (runs `MailTriage` on `ModelWork` at sweep priority;
    re-reads each `.eml` for Reply-To, the topmost Authentication-Results and HTML link text; mbox
    messages fall back to the item's facts), `MailTriageView` (sections Phishing suspected / Spam /
    Needs reply / Urgent / By category; category, labels with "weak rule", the concrete signals,
    evidence score, link site checks; Open, Mark safe / Confirm phishing with Undo; "Links in your
    items" = site checks on web links in non-mail items such as links sent to Loupe). Now card "Mail
    triage: N possible phishing · M need a reply"; Sources → Mail section.
  - **Tests:** `SiteSignalsTest` (22: `test_browser.py`'s PSL, private host, homograph, look-alike,
    subdomain, IP, form, URL, brand-claim, shared-hosting, weight and scoring cases; the merge with
    `SiteFraud`; punycode), `PhishingTest` (10: `email_cases.py` verbatim + `test_phishing.py`'s
    evidence, reply-to, link, auth, trusted-sender, reason-text and provider-category cases),
    `MailTriageTest` (8: rows follow the evidence, labels, sort, the sample PayPal phishing with its
    signals, corrections, web-link items), `MailClassifyParityTest`; JVM + iOS simulator. XCTest
    `MailTriageTests` (5); UI `MailTriageUITests` (Now → Mail triage shows the PayPal phishing with
    its look-alike, display-name, link and urgent-language signals).
  - On the sample inbox: 1 phishing (`phishing-paypal.eml`: `sender_lookalike_brand` paypa1-secure →
    PayPal 45, `display_brand_mismatch` 40, `link_brand_in_subdomain` 30 → 100; urgent "within 24
    hours"; its link's site check `brand_in_subdomain`). Not ported: Station's trusted-senders
    setting (the rule is ported and tested, no UI yet), two-pass `is_important`, label writing to
    the mailbox.

- **2026-09-23 — Epic #7 child 10: privacy check (personal data, secrets, duplicate files).** On
  the owner's "Go" for epic #7 children 10–17. Uncommitted pending review.
  - **Shared rules, ported from Loupe Station** (the owner's `~/laya-studio`, commit `ea7697a`):
    new `loupe-kit` package `dev.loupe.kit.privacy` — `PiiRules`/`PiiCollector` (`pii_rules.py`),
    `SecretRules`/`SecretCollector` (`secret_rules.py`), `Duplicates` (`dupes.py` + the duplicates
    half of `planner.py`), `NameHints` (the `id_document` row of `rules.py` `KEYWORDS`). Rule ids,
    labels, tables, regexes, thresholds, `SEVERITY` (secret 3, personal 3, business 2, duplicate 1),
    masking (first character only for personal data; four characters for long secrets) and
    placeholder / entropy false-positive handling copied verbatim. Duplicates reuse the engine's
    `ContentHash` (SHA-256, already on every `SourceItem`) instead of BLAKE2b; keep suggestion =
    oldest, then shortest path. `PrivacyCheck` groups findings (secrets, IDs/passports, cards,
    IBANs, phone/email = contact lists, payroll, duplicates); "mark safe" is a `CorrectionRecord`
    (`privacy` / `privacy-v1`, label `safe`; Undo appends a retraction).
  - **Deviation, deliberate:** the station raises personal/name findings only in *unsafe* places
    (Downloads, Desktop, synced folders) and business data only outside a business folder. The phone
    has no such places, so every enabled source is checked as if exposed. Only the `id_document`
    name row is used (bank/contract/tax names are ordinary documents). Contact cards are skipped (the
    address book is not a leak). Raw `email`/`phone` hits are not findings, as in the station.
  - **iOS:** `Loupe/Privacy/` — `PrivacyService` (runs `PrivacyCheck` on `ModelWork` at sweep
    priority; no model), `PrivacyView` (groups, masked previews, item, Open / Mark safe; for picked
    Files and Send-to-Loupe items Delete (confirmation; held in `Application Support/Loupe/privacy-held`
    so Undo works until the screen closes, then removed) and Move to a chosen folder (Undo); Photos
    delete through `PHAssetChangeRequest` with the system prompt; sample, mail, calendar
    suggest-only). Now card "Privacy check: N findings"; Sources section link. Nothing is logged.
  - **Regex:** the station's leading lookbehinds run through `GuardedRegex` (see Traps); hostile-text case 1.5 s on the iOS simulator, 0.1 s on the JVM.
  - **Tests:** `PrivacyRulesTest` (5: the station's `test_pii_rules_unit`, `test_secret_rules_unit`,
    per-file secret cases, masking, hostile-text linearity — bound 5 s for Kotlin/Native),
    `PrivacyCheckTest` (4: sample IDs + duplicate receipts, size-then-content grouping, signals to
    findings with no raw value anywhere, mark safe), JVM + iOS simulator; XCTest `PrivacyTests` (5:
    sample, ledger correction + undo, delete/undo/commit and move/undo in a temp directory, no secret
    in the ledger or findings); UI `PrivacyUITests` (Now → Privacy check shows the SPECIMEN passport
    and licence and the duplicate receipt).
  - On the sample: 2 ID documents (by name: the SPECIMEN passport number `000000000` has no letter
    prefix, so the station's content rule does not fire) and 1 duplicate group (the fresh-basket
    receipt and its copy).

- **2026-09-23 — Epic #7 child 9: model delivery and device verification, built to the owner's
  blockers.** On the owner's "Go" for epic #7. Uncommitted pending review.
  - **Manifest.** `ios/Loupe/Resources/Laya/models.json`: variants `int8` (default; tokenizer
    34,363,188 B `609d8f4c…` + graph 383,883,281 B `8b994315…`) and `int8-partial` (opt-in; graph
    357,361,791 B `03d732c3…`), and `source.host` (**empty**) + `urlTemplate`. Parsed strictly
    (64-hex pins, plain file names, non-opt-in default). LoupeKit's `LayaModelStore` gained the same
    variants (`VARIANTS`, `filesFor`, `LayaModelStore(dir, variant)`), and `LayaOnPhone` the
    variant-aware `open`/`missing`/`fileNames` plus `scoreCase` (Kotlin exceptions stay in Kotlin);
    an XCTest checks the Swift manifest and the Kotlin pins agree.
  - **Delivery.** Consent screen before any request (size, one-time, only the model files go
    online, fingerprint check, not backed up, removable); consent is recorded per variant and size
    and withdrawn by Remove. Background `URLSession` download tasks (two session identifiers:
    Wi-Fi-only, and mobile data when the user allows it), progress, Pause/Resume via resume data kept
    on disk (survives a quit; a relaunch hands finished files over through
    `handleEventsForBackgroundURLSession`), free-space precheck (remaining + 200 MB against
    important-usage capacity), streaming SHA-256 in a staging folder then an atomic `rename` into
    `Application Support/Loupe/laya-multilingual`, excluded from backup. With the host empty the
    screen says "Model host not configured" and nothing is requested. Adapted from Loupe Station's
    `ModelStore.swift` (provenance in ios/README.md).
  - **Diagnostics** (Me, DEBUG or a `LOUPE_DIAGNOSTICS` TestFlight build): the 34 golden + 8
    criteria questions through the real backend on the phone, N timed passes after a warm-up;
    per-question agreement with the pinned JVM INT8 answers and max |Δp|, p50/p95 latency by token
    bucket, peak `phys_footprint`, thermal samples, battery %/h estimate, device and iOS version;
    JSON via the share sheet. Expectations bundled DEBUG-only (`laya-diagnostics.json`, generated by
    `ios/scripts/make-diagnostics-fixtures.py`, checked against the JVM fixtures by a test).
  - **Device readiness.** `project.yml` reads `DEVELOPMENT_TEAM` from `ios/Config/Signing.xcconfig`,
    which includes the git-ignored `Signing.local.xcconfig` (example checked in);
    `ios/scripts/device-build.sh` builds Debug for `generic/platform=iOS` and installs with
    `devicectl`, failing clearly with no team, the example team, no LoupeKit device slice or no
    device; `--diagnostics-archive` makes the TestFlight archive. Owner steps in ios/README.md
    ("On your iPhone").
  - **Not done, and why:** no download has run against a real host (none exists), nothing has run
    on an iPhone, and milestone 1's phone half (risk 13) is still unmeasured — the Diagnostics JSON
    from the owner's iPhone is what closes it.
- **2026-09-23 — Epic #7 child 8: the 3D mascot.** `ios/Loupe/Design/Mascot/`: `MascotRig.swift`
  (pure Swift: `MascotState` idle/greeting/watching/scanning/thinking/found/happy/empty, the
  prototype's per-pose channel keyframes, frame-rate independent easing `1-e^(-dt·rate)`, blink,
  idle glances and waves, one-shot greeting/found/happy that settle, tap → wave + happy),
  `MascotScene.swift` (SceneKit node rig from primitives + custom supersphere helmet and lathe egg
  torso; white clearcoat PBR, lavender bands, blue soles, near-black visor, cyan chest slot and
  antenna tips, blob contact shadow, studio environment; template built once, cloned per view),
  `MascotFace.swift` (CoreGraphics face texture, redrawn only when the quantised face changes),
  `MascotView.swift` (the one `MascotView(state:size:lookAt:)`; SCNView paused when offscreen,
  hidden, backgrounded or settled under Reduce Motion; cached still renders below 40 pt; reference
  PNG fallback without Metal; accessibilityHidden). Every mascot site now uses it; the game HUD
  gained one (scanning while Laya flies). Measured: 60.0 fps (`-LoupeMascotFPS`) on the iPhone 17
  Pro Max simulator on Now and in the game HUD. Tests: `MascotRigTests` (mapping, keyframes,
  blending, settle/tap, Reduce Motion). Contact sheet: designs/ios-v1/12-mascot-states.png.
  Device fps/thermal unmeasured (needs an iPhone).
- **2026-09-23 — iPhone phone sources (epic #7, child 7).** Five sources on the iPhone, each a
  `SourceItem` producer into the same `SourceLibrary` cache the sample uses, so Judgments, the
  watchers and the sort read them unchanged. Each is **off by default**; turning it on in Sources is
  the only place its iOS permission is asked; off removes its items (and, for Mail, makes no request).
  **Photos** — PhotoKit (limited-library aware, "Choose more photos"), ImageIO metadata from the
  bytes, text by Vision `VNRecognizeTextRequest` (`.accurate`, language correction, on-device),
  screenshots flagged; incremental: unread photos newest first (300 a scan, "scan again to
  continue"), edits via the persistent change token, deletions dropped; iCloud-only originals are
  not downloaded and are listed as skipped. **Files** — `UIDocumentPickerViewController` for files
  and folders, security-scoped bookmarks persisted (`sources/bookmarks.json`), re-scanned on every
  open through the common scanner; plus the **Share Extension "Send to Loupe"** (`LoupeShare`),
  which copies shared files, links and text into the App Group `group.dev.loupe.app` inbox read as
  the `shared` source. **Calendar** — EventKit read-only (iOS 17 full access): title, times,
  attendees, organiser, recurrence, one item per occurrence, a year back to a year ahead.
  **Contacts** — CNContactStore: names, emails, phones as CONTACT items; the shared `WatcherRun`
  now merges the address book into the contacts it infers, so a known name from an unknown address
  is flagged without any mail history (contacts are not judged: `SweepCoordinator` and
  `judgeableItems()` leave them out). **Mail** — a small read-only IMAP client in Swift over
  Network.framework TLS (993, no STARTTLS, no dependencies): EXAMINE, `UID SEARCH`, `UID FETCH …
  BODY.PEEK[]<0.262144>`, a command allowlist (ported as spec from Loupe Station's `mail/imap.py`),
  incremental by UIDVALIDITY/UID, the newest 200 on first sync; each message is kept as a `.eml`
  and read by the common scanner and MIME parser, then labelled **Online** with host and fetch time
  (§4a). App passwords live in the Keychain (`WhenUnlockedThisDeviceOnly`). Google/Microsoft OAuth
  (ASWebAuthenticationSession, PKCE S256, `state`, XOAUTH2, refresh) is built but **gated**: the
  client IDs are Info.plist keys that ship empty, and the screen says "Needs a Google/Microsoft
  OAuth client ID" — owner-blocked. Loupe Station's classify/phishing logic was **not** ported:
  this child is ingestion only (child 11 owns mail triage and phishing); its Composio connectors were
  not touched (risk 14). Shared: `PhoneItems` (photo/event/contact builders, `labelOnline`, merge),
  `ItemKind.EVENT`/`CONTACT` and `DateOrigin.PHOTO_CREATED`/`EVENT_START` appended (desktop kinds
  still map by name). Tests: `PhoneItemsTest` (7, JVM + iOS sim), `AddressBookWatcherTest` (3),
  XCTest `PhoneSourcesTests` (22: fakes for PhotoKit/EventKit/Contacts, bookmark persistence, IMAP
  reader + client against recorded fixture transcripts, OAuth PKCE (RFC 7636 vector) and the gate,
  Keychain), UI `SourcesUITests` (every row present and off, Mail labelled Online, sample still
  48 items; Mail screen shows the OAuth gate). No test touches the network or a real service.
  **Trap found:** `"\r\n"` is one Swift `Character`, so a `Character` check for CR/LF in an IMAP
  quoted string misses it — check `unicodeScalars`.

- **2026-09-24 — Verify every item a result names (owner rule).** **Owner rule, 2026-09-24,
  confirmed by the owner: any result that refers to a file must give the user a way to verify
  it — the extracted evidence, or a way to open the file.** Prompted by Loupe Station: "Personal
  data: 1 payment card number" on IMG_1507.HEIC (OCR) that could not be checked, a garbled chip
  ("payment card number ×1 (2… (payment card number))") and the photo filed as "Code / Tech".
  Audited and fixed on the iPhone: privacy check, Now's watcher findings, mail triage (messages
  and site checks on links), judgment results (row and detail), the Unsure queue, the Review queue
  (watcher proposals find their item through the finding) and the Inbox (through the shared item sheet). Each row
  shows the item (thumbnail, name, source/folder, dated with its origin) and `ItemActions`: open
  the original (PhotoKit asset; Files, the Send to Loupe inbox and the sample through Quick Look —
  documents and pictures only, never scripts, code, HTML or executables), Share, and for mail
  "Open in Mail" (`message://` with the Message-ID read from the `.eml` on tap) or the headers to
  find it. Privacy findings add **Show where**: shared `PrivacyEvidence` re-derives each match on
  tap — masked value (`•••• •••• •••• 1234` + brand; IBAN, ID, passport, phone, email masked alike),
  a masked context line and the checks it passed — and for a picture Vision runs again and the
  matching line's box is drawn over it. Nothing is stored: the evidence lives in view state only
  (XCTest checks the container and defaults). Shared rules: `CardRules` — OCR'd text (photos,
  anything whose text fact says OCR) needs Luhn + 13–19 digits + a known IIN and must not be part
  of a longer digit run, a date/time, a phone number, an IMEI, a tracking/order number or hex, and
  needs a card word nearby (card, visa, mastercard, amex, exp, expiry, valid thru, CVV, بطاقة, …) or
  strict 4-4-4-4 / 4-6-5 grouping; files keep Station's rule. Chips are one clean format
  (`Payment card ×1`; previews no longer nest the label). An OCR'd photo's text now starts
  "Photo (text recognised): name", so a judgment or category question reads a picture, not code.
  Tests: `EvidenceTest` (9: false/true positives in Latin and Arabic script, masking, evidence,
  chips, the OCR receipt photo; JVM + iOS sim), XCTest `ItemReferenceTests` (7: open policy, open
  plans with fakes, resolver, evidence box, no persistence), UI `PrivacyShowWhereUITests` (a
  rendered test-card photo, DEBUG `-LoupeFixtures -LoupePrivacyPhotoDemo`: masked value and the
  box). Not done: a model-level test that Laya no longer answers "code" for a receipt photo (needs
  the model; gated); scanned-PDF OCR is not yet marked in its text; Station's own evidence shape
  and masking format, when they arrive, may change the wording.

---
- **2026-09-24 — Phishing formula v1.2 ported to mobile; Phishing.Database; Show-where aligned (owner
  decisions A, B, C; not yet committed).** `loupe-kit` `dev.loupe.kit.site`: `SiteContext` (tiers,
  payment processors + `embeds`, the card-form-to-processor rule-4 exception, facts / not-counted),
  `Dns` (wire format, DNS facts, Spamhaus DBL / SURBL / URIBL with test points cached 1 h; error codes
  never "listed"), list strength (URL 60, host 45, domain 30 capped at 59, 60 when corroborated),
  `PhishingLists` (Station's `feeds.normalize`, `shared_hosts.json` copied verbatim from Station
  4cb9026 into `loupe-kit/data/` and generated into Kotlin, Phishing.Database index). All 51 v1.2
  vectors pass on JVM and iOS sim; the 57 v1.1 vectors pass with `online-feed-host` → caution 45 and
  `online-feed-domain` → caution 30 (override table, as Station's `V12_CHANGES`; the v1.1 file stays
  byte-identical). iOS: DNS via `res_9_nsend` (system resolver, no DoH), Phishing.Database downloader
  (conditional GET, 3 s gaps, sanity check, atomic swap, last good copy; NEW hourly, ACTIVE 1–168 h,
  default 6), OpenPhish now off by default, DNS facts and blocklists off by default; link checks show
  "Warning signs", "Reassuring facts", "Also noticed (not counted)". Privacy: Station's `card_check`,
  `mask_value`, `masked_context` and row shape; Station's 10 true / 15 false card fixtures and the
  IMG_1507 case pass. MIT notice for Phishing.Database in Me → Licences and THIRD_PARTY_NOTICES.md.
  Not verified: DNS answers on a real device (tests use fakes).

## Where the build stands

As of 2026-09-23. Everything below was built on JVM Kotlin: no iOS or Android build, no device.
Where the tables below say "Android", read "the phone": since 2026-09-23 Loupe **leads on iOS**
(§0), so these tracks are blocked on the iOS equivalents first — see *The iPhone app (epic #6)*
below. Since
2026-09-23 there is also a **desktop app** (`:loupe-desktop`) that runs the engine over real folders
and mail exports with the real model. CI runs the full suite on every push to `main`, **without
model weights** — the tests that need them skip there. The Laya weights exist only on the machine
that ran the export spike.

### Built and green

| Track | State |
|---|---|
| A0 Licence verification | Done (2026-09-22, see `LICENSING.md`) |
| A2 Backend interface | Done — `Backend` |
| A2 **first implementation** | Done — `OnnxBackend` in `backend-onnx`, real ONNX Runtime inference. **Runs the real Laya graph** with `LayaPrompt` + the DJL tokenizer, matching upstream PyTorch (gated tests, local only). The second (Qwen) backend still needs weights |
| A1 export half | **Proven on desktop** — Laya encoder + head as one ONNX graph, FP32 and INT8 (384 MB; opt-in 357 MB `int8-partial` since 2026-09-23 — SmoothQuant/static calibration tried, negative), parity recorded. Device latency not measured |
| A3 Mechanical extractors, text state | **Complete** — hash, dedup, MIME, dates, origin facts (on the real Public Suffix List since 2026-09-23), OCR-presence, `TextState` |
| A4 Judgment type and validation | **Complete** — Choice, Bool and Score; strict validation, failure postures, never throws |
| A5 Ledger | Complete — append-only, propensity required, criteria hash, `resolvedBy` (model / mechanical:&lt;check&gt; / unusable) |
| A6 Recalibrator | Complete — temperature scaling fitted by NLL; ECE, Brier, reliability bins |
| A7 Policy runner | Complete — pure, total, calibrated-only by construction |
| A8 Counterfactual engine | Complete — IPS, SNIPS, seeded bootstrap intervals, threshold replay |
| C1 Judgment library | Complete — seven built-ins on the three-part template, grown into a **template library of 55** in ten categories (`:templates`), browsable in the desktop app |
| C2 Plain-language authoring | Complete — lint plus compilation to a typed judgment; **desktop UI with live lint** ("Write your own") |
| C3 The five watchers | **Mechanical halves complete** — expiry, recurring money, term change, impersonation, site fraud. **Run over scanned items in the desktop app**; the expiry radar's model half runs when Laya is loaded |
| D1 Uncertain queue | Complete — margin ranking plus random audit arm; **desktop UI**, one keystroke per answer |
| D2 Visible calibration | Complete — per judgment, no aggregate possible; **desktop UI**, gated on correction counts |
| D3 Threshold slider | Complete — counterfactual preview over logged rows; **desktop UI** |
| D4 Baseline runner | Complete — inside `Harness`; **desktop UI** on the user's corrected items |
| F2 Retroactive sweep | **Built on desktop** — background, single model thread, progress, items/s, median latency, cancel; skips items already judged under the current wording |
| F4 Export | Complete — lossless ledger, judgments, calibration; **desktop UI** |
| Desktop stand-ins for B1/B2 | `:sources-desktop`: folders and mail exports (`.mbox`/`.eml`), read-only; text from plain text, Markdown, CSV, JSON, HTML, email (mime4j) and PDF (PDFBox); images metadata only (no OCR) |
| F5 The game | **Desktop and iPhone simulator built** (epic #7 G) — `:game` + `:game-desktop` (Compose Desktop). Runs the real Laya on this CPU at 10 decisions/s; untuned it loses to the baseline. Needs a phone and a fine-tune |
| §7 Measurement harness | Complete — group-wise splits, selective accuracy, coverage, ECE, Brier, baseline |
| Engine wiring | `DecisionEngine` composes the §8 architecture end to end |

### Blocked, and on what

Nothing below is deferred by choice; each needs something this environment does not have.

| Blocked | Needs |
|---|---|
| **A1** model runtime, fine-tune, latency | A real mid-range device — its acceptance criterion is a measurement on hardware. The weights and the ONNX export now exist (desktop), fine-tuning needs a labelled corpus and a GPU |
| **A2** the second backend | Qwen3-0.6B weights. Laya runs; the runtime and the interface exist |
| **B1–B9** every source | Android APIs: SAF, MediaStore, ML Kit, Gmail OAuth, calendar, contacts, notifications, WebView. *On the iPhone (epic #7 child 7, simulator):* B1 files (picker, bookmarks, share sheet), B2 mail (IMAP with an app password), B4 photos (PhotoKit + Vision OCR) and B5 calendar and contacts are built; B3 spreadsheets are read as CSV files only; B6 voice memos, B8 notifications (iOS offers none) and B9 are not built |
| **B2 OAuth mail** (Gmail API / Outlook) | **Owner:** a Google OAuth client ID (iOS type) and a Microsoft Entra app registration (public client, IMAP.AccessAsUser.All), put in `ios/project.yml` as `LoupeGoogleOAuthClientID` / `LoupeMicrosoftOAuthClientID`. Empty today, so the app offers IMAP with an app password instead |
| **"Send to Loupe" on a device** | The App Group `group.dev.loupe.app` registered on the owner's Apple developer account (the simulator runs it unsigned) |
| **D5** actions, preview, undo | Android. The desktop app shows **preview only** and never changes a file; undo exists for corrections |
| **E1–E4** actuation | Android `AutofillService`, App Intents, accessibility, WebView |
| **F1** passive mode | Android: WorkManager, charging and thermal constraints. iPhone: built on the simulator (epic #7 child 6); battery/thermal on a device needs a physical iPhone |
| **F2** on a phone | Android sources and WorkManager constraints (risk 3). Built on the desktop and the iPhone simulator (epic #7 child 6); device cost unmeasured |
| **F3** overnight fine-tune | Model weights and a GPU |
| **F5** the game, on a phone | An Android build and a device. The desktop game runs (`:game-desktop`); the Compose UI is written to move |
| Real measurement | **A labelled fixture corpus.** Every number the harness produces today comes from synthetic fixtures; the machinery is proven, the numbers are not real. The Laya parity numbers measure agreement with upstream, not accuracy. The desktop app now *collects* labels — every correction is one — so the corpus can start with the owner's own files |

### The iPhone app (epic #6)

Decided 2026-09-23 (#6, "Go" from the owner). Children, in order; 2 before 3 and 5, 4 in parallel
after 1, 6 needs 3, 4 and 5.

| # | Child | State |
|---|---|---|
| 1 | Record the iOS decision and the online helper rules in `PRODUCT.md` / `BUILD.md` | Done 2026-09-23 (this) |
| 2 | Port `engine` + `templates` to Kotlin Multiplatform (`jvm`, `iosArm64`, `iosSimulatorArm64`), XCFramework `LoupeKit` | **Done 2026-09-23** — see progress log |
| 3 | Laya on iOS: ONNX Runtime iOS + HF `tokenizers` for iOS behind `Backend`; parity against the JVM fixtures; latency on a real iPhone | **Done 2026-09-23 except device latency** — simulator parity 34/34 + 8/8 identical to JVM INT8; phone latency needs a physical iPhone. See progress log |
| 4 | Helper `sambawy01/loupe-web-helper` on Railway: `POST /v1/flights/search` (Duffel), schema v1 | **Deployed** — https://loupe-web-helper-production.up.railway.app |
| 5 | SwiftUI app shell — tabs Now, Judgments, Web, Sources, Me — with `LoupeKit` | **Done 2026-09-23** (simulator) |
| 6 | Web tab: Flights — key onboarding, search, results ranked on device, "Online" labels, off switch | **Done on the simulator 2026-09-23, Laya wired in** — ranked by Laya with the rule baseline alongside; model download host not configured (side-load for dev); Duffel end to end needs a test key |
| 7 | Later phases, each its own spec: hotels / trains / price watch; shopping compare and price-drop; link and site safety check; research (read and rank pages); concerts, festivals and sports events | Not specified |

**Blocked on:** an **Apple developer account** (signing, a device build, TestFlight), a
**physical iPhone** (child 3's latency — milestone 1's phone half — and anything on device), and a
**Duffel test key** (child 6 end to end; the helper holds no key of its own).

### iPhone feature parity (epic #7)

| # | Child | State |
|---|---|---|
| G | Game on iPhone | **Done 2026-09-23 (simulator)** — KMP `:game`, JVM/iOS parity identical, SpriteKit at 60 fps, Laya live at ~9 decisions/s; device fps/latency need an iPhone |
| 1 | Ledger on iOS + F4 export | **Done 2026-09-23 (simulator)** — `:persistence` (common), Laya decisions logged as `web:duffel` model rows, Me → Export my data |
| 2 | Sample source + extractors on iOS | **Done 2026-09-23 (simulator)** — `:sources-common` (common model, text/HTML/MIME/mbox extractors, scanner, cache), PDFKit/ImageIO actuals, parity with the desktop scanner on every sample item; Sources tab with the labelled sample (48 items) |
| 3 | Judgments tab | **Done 2026-09-23 (simulator)** — shared `dev.loupe.kit.judgments` (JudgmentBook, JudgmentSweep, JudgmentResults); Library (55 templates, 10 categories, search, detail, Use this), Write your own (live lint, yes/no · score · pick, bare yes/no refused, criteria-in-prompt off by default), My judgments with counts, per-judgment Results over the sample with Laya (off main, cancellable) or "Model not installed" |
| 4 | Unsure queue + measurement | **Done 2026-09-23 (simulator)** — shared `JudgmentMeasure` (queue across judgments with audit arm, corrections keyed by criteria hash, D2 gates 10/30, D3 preview, D4 via Harness, "use the baseline"); Unsure queue, Measure screen, Needs you on Now, Me agreement line |
| 5 | Watchers on Now | **Done 2026-09-23 (simulator)** — orchestration moved to shared `dev.loupe.kit.watchers.WatcherRun` (desktop delegates, its tests unchanged; no rule or threshold changed); `WatcherFindings` (findings with evidence, verdicts as corrections, subscriptions census); Now hero shows the top finding, findings list with Confirm / Dismiss / Not relevant / Open item, census, mascot `found` on new findings, all sample findings labelled Sample |
| 6 | Retroactive sweep + passive mode | **Done 2026-09-23 (simulator)** — shared `SweepCoordinator` + `ModelLane` (one model thread; foreground > game > sweep; preempt between items, resume); every judgment × every enabled source, skipping items judged under the current criteria hash, then the watchers; progress/items/s/median/cancel; BGProcessingTask "Sort while charging" (off by default, external power, no network, expiry checkpoints, thermal `.serious`+ and Low Power Mode stop it); Me → Run now; notification + Now card from real counts. **Battery/thermal on a device is owner-blocked** (needs an iPhone) |
| 7 | Phone sources | **Done 2026-09-23 (simulator)** — Photos (PhotoKit, limited-aware, Vision OCR on device, screenshots, change tokens), Files (document picker + persisted security-scoped bookmarks, rescan on open) + Share Extension "Send to Loupe" (App Group inbox), Calendar (EventKit), Contacts (feed the impersonation watcher), Mail (read-only IMAP over TLS, app password in the Keychain, UIDVALIDITY/UID incremental, labelled Online); each off by default with its own permission prompt only on enable; Sources rows with permission state, counts, last scan, errors with recovery text. **Owner-blocked:** Google/Microsoft OAuth client IDs (flow built, gated on empty config); App Group on a device (needs the developer account) |
| 8 | 3D mascot | **Done 2026-09-23 (simulator)** — native SceneKit rigged robot ported from the web prototype (geometry, PBR clearcoat materials, rig, pose keyframes); animated visor face texture; 8 states with eased blending; tap waves; pauses offscreen/background; Reduce Motion static poses; still renders under 40 pt; reference PNG if no Metal. 60 fps measured on the simulator (Now, game HUD) |
| 9 | Model delivery + device verification | **Built to owner blockers 2026-09-23 (simulator)** — `models.json` manifest (int8 default, int8-partial opt-in, sizes + SHA-256 pinned, host EMPTY → "not configured"); consent screen; background URLSession download (Wi-Fi unless mobile data allowed, pause/resume with resume data, disk-space precheck, streaming SHA-256, atomic move, no backup, remove); Me → Diagnostics (42 parity questions on device vs JVM INT8, p50/p95 by tokens, peak phys_footprint, thermal, battery, JSON export); `Config/Signing.xcconfig` + git-ignored local Team ID; `ios/scripts/device-build.sh`. **Owner-blocked:** model host, Apple developer account (Team ID, App Group), an iPhone to run Diagnostics |
| 10 | Privacy check | **Done 2026-09-23 (simulator)** — Loupe Station's PII, secret and duplicate rules ported verbatim to shared `dev.loupe.kit.privacy` (rule ids, severities, masking, false-positive handling; duplicates on the engine's `ContentHash`); Now card + Sources link → findings by type with masked previews, Open, Mark safe (ledger correction, Undo), Delete / Move with Undo for reachable Files items, PhotoKit delete with the system prompt; everything else suggest-only |
| 11 | Mail triage + phishing | **Done 2026-09-23 (simulator)** — Loupe Station's `mail/phishing.py` evidence and `mail/classify.py` labels ported verbatim to shared `dev.loupe.kit.mail` (weights, thresholds, tables, reason texts; the `wf-email-triage` keyword rules answer on the phone, no model, no Composio); Now card + Sources → Mail: sections (phishing suspected, spam, needs reply, urgent, by category), concrete signals, Mark safe / Confirm phishing (ledger corrections, Undo), Open; the sample's `phishing-paypal.eml` flagged with its signals |
| 12 | Brand-lookalike site checks | **Done 2026-09-23 (simulator)** — Station's `browser/brands.py` list and `signals.py`/`scoring.py` weights in shared `dev.loupe.kit.site`, on the engine's pinned PSL, shown beside the engine's `SiteFraud` (thresholds unchanged; six differences recorded in the Progress log); on every mail link and on web links in other items |
| 13 | Review queue | **Done 2026-09-24 (simulator)** — Loupe Station's review state machine, append-only log, limits and registry ported to shared `dev.loupe.kit.review` (files beside the ledger); proposals from the privacy check (remove a reachable duplicate copy), mail triage (confirm phishing), watchers (keep a finding) and pack judgments; Now card "To review: N"; approve (one or batch), reject with a reason, retry (re-runs the check), undo where reversible; nothing runs without approval; separate from the Unsure queue (labels) |
| 14 | Preset packs | **Done 2026-09-24 (simulator)** — Station's `laya-preset-pack` format validated exactly as Station in shared `dev.loupe.kit.packs`, plus the C2 lint per question; import from Files / "Open in Loupe" / the bundled, labelled Bistro Cloud example; preview before adding; same-id conflicts skip / replace / keep both; export your judgments as a pack (round-trips) |
| 15 | Items inbox | **Done 2026-09-24 (simulator)** — Station's `items/csvimport.py` rules in shared `CsvRows` (sniffing, header detection, English/Arabic names, amounts); Sources → Inbox: import CSV (a row per item with column context), `.eml` / `.mbox`, ZIP (pure-Kotlin reader with Station's bomb limits plus traversal / symlink / nested refusals), pasted or shared text and links; each batch listed with counts and removable; items labelled "Imported · origin · name · date" and read by judgments, sweep, watchers, privacy check and mail triage |
| 16 | Workflows: reply drafts, second opinion | **Done 2026-09-24 (simulator)** — Station's `email_reply` / `second_opinion` prompts and `llm/client.py` in Swift (`ios/Loupe/Assist/`); opt-in writing assistant, off by default (BYOK OpenAI-compatible HTTPS or local-network Ollama, key in Keychain); Online label + exact preview + confirm before each call; drafts labelled Draft, wait in Review, copied or opened in Mail by the user, never sent by Loupe; second opinion shown beside Laya's answer, never changes or logs a decision |
| 17 | Drone mascot (alternate) | **Done 2026-09-24 (simulator)** — Loupe Station's orb-drone (`mascot.js` / `mascot.css` @ `ea7697a`) rebuilt natively in SceneKit behind the same `MascotView(state:)` (`MascotKind` robot \| drone; no call site changed): orb, glass visor face texture, pill / ^ ^ eyes, beacon, seam, tilted orbit ring, Station's light/dark mood colours, hover bob, hop, halo breathe/flash, dashed spinning ring. Station's six moods mapped onto our eight states (table in `DroneRig.swift`); same pause rules, Reduce Motion stills, VoiceOver hidden, bundled `MascotDrone` PNG fallback. Me → Appearance → Mascot: Robot (default) / Drone, persisted, live. 60 fps with eight live drones on the simulator |

**Epic #7 is complete on the simulator** except these owner-blocked items: an iPhone (device fps/latency, battery/thermal for passive mode, Diagnostics run), an Apple developer account (Team ID, App Group for the Share Extension on a device), the model host for delivery, and Google/Microsoft OAuth client IDs for mail/calendar sign-in.

**Issue #8 (Laya upgrade):** steps 0–3, 5 (score reversal) and 6 are done on the desktop/simulator (2026-09-24). Step 4 (neutral A/B labels) was dropped: Station measured it worse. Step 7 (Core ML) is still open.

### Proving milestones

| | Milestone | State |
|---|---|---|
| 1 | It runs | **Half there** — the real model answers typed questions from Kotlin on a desktop; the milestone needs a phone and a measured latency |
| 2 | It decides | **Met on the desktop** — real files and mail, the real model, a ledger row with a propensity per decision (desktop app). The phone half is pending |
| 3 | It learns | Machinery built (fit beats baseline on ECE in a test). The desktop app shows a held-out temperature fit once a judgment has 60 corrections; needs real corrections |
| 4 | It shows its work | **Runs on real logged history** in the desktop app's threshold screen; needs the owner's own history to count |
| 5 | It notices | Watchers fire on the synthetic sample (passport, +23% premium, "Mum" on a new domain, fake PayPal); needs real personal data |
| 6 | It is honest | **Shown in the app** — on the synthetic sample, untuned Laya loses to its keyword baseline and the Baseline screen says so; also runs as a test |
| 7 | It is local | Structurally true of the engine: no network call exists anywhere in it |

- **2026-09-22 — A4: failure postures and the never-throws guarantee.** A4's acceptance criterion
  is that *fuzzed malformed responses never throw* and that *each judgment declares its failure
  posture and honours it* — neither was true, so a single malformed answer would abort a sweep over
  a whole library. `FailurePosture` (OPEN / LOUD / NULL_ACTION) is now declared per judgment and
  carried on `Decision.Unusable`, **not chosen by the calling code**, so a judgment cannot be made
  quietly permissive by whoever happens to invoke it. The engine catches both validation failure
  and a backend that throws outright — a backend is an untrusted component: a bad export, a
  truncated model file, a future implementation with a bug. An unusable response still writes a
  ledger row, marked with its `failure` reason and carrying a **flat** distribution, which is the
  honest shape of having no view; the harness counts it as wrong per §7 but keeps it out of the
  calibration metrics rather than scoring a placeholder as an opinion. The expiry judgment declares
  **LOUD**, because quiet failure there is indistinguishable from "your passport is fine".
  A 500-iteration fuzz over negative, NaN, infinite, empty and unknown-label responses runs as a
  test. Two earlier tests asserted the old throwing contract and were updated to the new one.
  242 tests green.
- **2026-09-22 — A4 complete: `Bool` and `Score` judgments.** `Bool` is a named shape over a
  two-candidate `Choice`, not a separate mechanism — it validates and scores through the identical
  path and only spares callers from inventing their own label for "yes". `Score` is the one worth
  the type: because the backend is a classifier, a score is a **distribution over ordinal bins**,
  never a free-form number the model writes out, and `expectedValue` reads the whole distribution
  rather than only its peak. That is precisely why C2's lint refuses "rate 1–10" while a `Score`
  with a declared range is fine — the difference is a bounded range the answer must land inside,
  which can be validated, calibrated and scored like anything else. Both inherit the strict
  validation and failure containment. **Track A (A0, A2 interface, A3–A8) is now complete except
  A1/A2's implementations, which need the model weights and a device.** 254 tests green.
- **2026-09-23 — Laya replaces GLiClass; export and Kotlin path proven on desktop (spike).** On
  the owner's decision, `convaiinnovations/laya-multilingual` (322M, Apache-2.0 weights, pinned
  at revision `052592a1`) replaces GLiClass as the primary. `tools/export-laya-onnx.py` loads it
  with the **authors' own `laya` package** (pinned commit `c7527708`) so the reference is their
  forward pass, and exports encoder + head as one graph answering one Choice question per call:
  `input_ids`, `attention_mask`, `marker_pos` → per-option `logits` (plus the unused
  `act_logits`), opset 18, dynamic sequence and option counts; the question type is fixed to
  `choice` inside the graph because upstream also changes the prompt text by type. **Parity on
  34 questions** (2–10 options, 16 languages and 9 scripts, one state 3× past the context):
  **FP32 34/34 argmax, max probability error 2.6e-6.** ORT's default INT8 recipe flipped 4/34
  with errors up to 0.56; a per-group sweep traced the damage to the 22 feed-forward output
  projections, whose GeGLU inputs have outliers a per-tensor scale cannot hold. The shipped INT8
  keeps those 22 matrices in FP32: **33/34, the one flip a near-tie (reference margin 0.008),
  max error 0.093**. Sizes: FP32 1.29 GB, INT8 384 MB (naive INT8 325 MB), safetensors 644 MB.
  Export 10–13 s, quantisation 11–19 s. **Desktop CPU latency, Apple M4, ORT 1.20, batch 1 —
  not a phone:** short questions (17–106 tokens) INT8 P50 42–46 ms / P95 85–92 ms, FP32 P50
  58–60 ms; a full 1,024-token question INT8 P50 ~1.04–1.11 s, FP32 ~1.26–1.36 s (PyTorch with
  Apple's BLAS: 27 ms and 0.36 s). On the Kotlin side, `LayaPrompt` rebuilds upstream's
  `build_sequence` byte for byte — over-length state is cut to its prefix as upstream does and
  reported, too many options throws — and the tokenizer is the Hugging Face Rust library through
  DJL, never hand-rolled. `OnnxBackend` gained a question argument on `Tokenizer` and an optional
  marker input. **Tests: 281, of which 6 are gated on the weights** and skip without them; run
  locally with the weights, all pass — DJL reproduces all 194 upstream token segments exactly,
  and the JVM path reproduces the Python parity numbers. None of this is an accuracy number: the
  fixture measures agreement with upstream, not correctness, and there is still no labelled
  corpus. Training-data provenance was open at the time (risk 12, since closed by owner decision 2026-09-23); on-device cost is unmeasured (risk 13).
- **2026-09-23 — F5 (desktop half): the demo game, an original river shooter.** On the owner's
  "Go". Working name ***Riverflight***, a placeholder not cleared as a mark. **Why not Tetris:**
  *Tetris Holding v. Xio* (D.N.J. 2012) held a clone copying Tetris's look to infringe copyright and
  trade dress, and a placement is a choice among up to ~40 options against Laya's ~20. The genre is
  free, a name and a look are not, so nothing here uses "River Raid", Activision's name, or its
  sprites or palette. **Provenance:** the river generator adapts the MIT prototype
  `joaoneto/river-raid-2k` (`5148ace`, © 2017 João Neto) — its Perlin function and its
  bank-width-from-noise / island idea; its notice is kept in `Noise.kt` and `THIRD_PARTY_NOTICES.md`.
  Its sprites were not used (its player imitates the original's silhouette). Shooting, fuel,
  scoring, collisions, bridges and game over are new. Two modules:
  **`:game`** (pure Kotlin, runtime dependency `:engine` only) and **`:game-desktop`** (Compose
  Multiplatform 1.7.3 on the existing Kotlin 2.1.0 — no Kotlin upgrade; licences in `LICENSING.md`).
  *Design.* A seeded, fixed-timestep (60 Hz) world; the river's walls move ≤1 column per row against
  the plane's 2, channels ≥5 columns, so it is always passable (a test walks 3,000 rows on six
  seeds). **Mechanical first:** each decision's legal set comes from exact look-ahead on a copy of
  the world — a move is offered only if holding it for the 12 ticks a decision is held leaves some
  move that survives 12 more; low on fuel, shots that would destroy the depot ahead are removed;
  with one legal move the model is not asked. A **safety override** re-checks the held move every
  tick and replaces a fatal one, recorded and flashed on screen. **One question, not two:** a
  single `Judgment.Choice` over at most six labels ("steer left", "steer left and shoot", "hold
  course", …), because Laya scores all options in one forward pass; "hold course" is the no-op and
  is offered whenever it is safe. Failure posture null-action: a backend that throws or fails
  validation yields "hold course", counted, never an exception. Decisions run at 10 Hz off the
  sim tick — lockstep with a fixed charged delay headless, a background thread live. Probabilities
  are raw model output and labelled so. A hand-off threshold passes an unsure decision to the
  keyboard. Text state sample: `fuel 75%, gun reloading. water 4 left, 9 right. far ahead water 8
  left to 1 left and 5 right to 12 right. boat 7 ahead 4 left moving right. heli 16 ahead 12 right
  moving left. fuel depot 25 ahead center.` — over 360 real states **51 tokens mean, 81 max**; the
  whole Laya sequence 106 mean, 137 max.
  *Measured, Apple M4 CPU, Laya INT8, ORT 1.20 — not a phone:* 200 back-to-back decisions **P50
  61.7–64.3 ms, P95 73.6–75.8 ms, 15.5–16 decisions/s** (two runs); the live window holds **10.0
  decisions/s** (its cap) at P50 ~66 ms, P95 ~83 ms, with no failures.
  *Model vs baseline, 10 seeds × 60 s, same charged delay (4 ticks, 67 ms):* **with the override
  (the product configuration) the baseline wins — score 10,100 vs 7,380, rows 4,174 vs 3,146,
  deaths 1/10 vs 9/10.** Eight of the model's nine deaths are fuel: untuned, it never steers for a
  depot and runs dry at row 280, the distance a full tank lasts. It also leans on the safety net —
  3,216 overrides against the baseline's 1,475. Its mean top raw probability is ~0.48 over up to
  six options. *With the override off* the order flips on score (5,900 vs 4,330) with both nearly
  always dying (10/10 vs 9/10): the baseline's rules crash early on several seeds, while the legal
  set keeps the model out of the banks until its fuel runs out. Neither number is flattering, and
  neither is an accuracy: the planned fix is fine-tuning on baseline-flown states (the baseline is
  cheap and deterministic, so it can label thousands). Tests: `:game` 35 (3 gated on the weights);
  whole build 316, run with and without `models/`.

- **2026-09-23 — The desktop app: a template library, desktop sources, and Loupe on real files.**
  On the owner's "Go". Three modules. **`:templates`** (pure Kotlin over `:engine`, no third-party
  code) grows the seven built-ins into **55 templates in ten categories** — money, documents and
  deadlines, email, subscriptions, files, photos, work, travel, safety and fraud, personal — each
  with a typed shape (two descriptive options, a choice with an explicit no-op, or an ordinal score
  whose bands the question names), the three-part criteria, a failure posture (LOUD for expiry,
  deadlines, bills and every safety judgment, which are also warn-only), source kinds, a declarative
  dumb baseline (keyword, keyword map, pattern, date-before, sender, constant — `asFunction()` is the
  `(Item) -> String` `Harness` already takes) and worked examples. Five are parameterised
  ("Messages from {sender} that need a reply", "Documents that expire before {date}", …); values are
  validated per kind and the substituted question is linted again, so a parameter cannot smuggle in a
  second question, a `<mask>`, a placeholder or a prose request. Using a template creates a
  `UserJudgment` through `JudgmentAuthor.compile`; rewording changes the criteria hash, and every
  calibration number is computed only over decisions under the current hash, so it resets honestly.
  **`:sources-desktop`** reads folders and mail exports **read-only** (links not followed, a test
  checks every byte and timestamp afterwards), mechanical facts first (SHA-256 dedup, magic-bytes MIME
  over the extension, dates with their origin, sender, links), text from plain text, Markdown, CSV,
  JSON, HTML (tags stripped), email through **Apache James mime4j** (headers, multipart, charsets,
  `.mbox` framing) and PDF text layers through **Apache PDFBox**; images give EXIF and dimensions
  only through **metadata-extractor** — no OCR, and an item with no text is never sent to the model.
  Every file not read is kept with its reason. It ships a **synthetic sample dataset** (48 items:
  receipts and a byte-identical copy, SPECIMEN passport and licence, insurance renewals 450.00 →
  553.50, a statement, subscription receipts in an mbox, a trial warning, a price rise, a phishing
  email, a "new phone" impersonation, and files that must be skipped). **`:loupe-desktop`** is the
  Compose Desktop app, `./gradlew :loupe-desktop:run`: Sources, Library (browse, search, detail,
  write your own with live lint), Judgments (F2 sweep on one model thread with progress, items/s,
  median latency, ETA and cancel), Results (raw probabilities labelled uncalibrated, filter, sort,
  search, mechanical facts, open read-only, one-click corrections, actions **preview only**),
  Uncertain queue (D1, keyboard: digits answer, Enter agrees, U undoes), Calibration (D2, per judgment
  only; agreement from 10 corrections, ECE and reliability from 30, a held-out temperature fit from
  60), Threshold (D3), Baseline (D4 through `Harness` with a replay backend on the user's corrected
  items), Census, Watchers (the five mechanical halves over scanned items; the expiry radar's model
  half when Laya is loaded), Export (F4) and Watch it think (the Riverflight window, borrowing the
  loaded model). Persistence: plain files in `~/Library/Application Support/Loupe` — the ledger
  **appended in `Export`'s own lossless JSON Lines**, corrections appended (undo is a retraction
  record), judgments and sources written atomically; no SQLite. `LedgerRow` gained an `itemId`
  (emitted by `Export` only when present) so a decision can be tied back to what it was about.
  *What the real model did.* Swept over the sample (45 items with text) on five templates it ran at a
  median **73–99 ms per item** on a lightly loaded M4 CPU and **125–152 ms** with the machine's load
  average near 20; no answer was unusable, and the watchers' model half flagged the SPECIMEN passport
  (expires 2027-01-14, 113 days, inside the six-month rule). **Its answers were poor, and the app
  says so.** With bare yes/no options it largely ignored the question: two unrelated yes/no questions
  gave near-identical answers, leaning "yes" on anything with money in it. Against labels written
  for the synthetic items (a check on invented data, not an accuracy number), descriptive options
  raised agreement on all three judgments tried — receipt 26 → 31, phishing 16 → 25, needs-reply
  10 → 23 of 45 — and every one still lost to its keyword baseline (43–44 of 45, baselines written
  knowing the data, which flatters them). So the templates now offer two options that say what they
  mean (`Shape.Binary`); a warn-only template's negative option is what the model reads, never what
  the user sees. In the app, with 39 receipt items corrected, the Baseline screen reads model 64.1%
  against baseline 94.9% and says the model is not winning — milestone 6, on screen. The sample's
  "SYNTHETIC SAMPLE DATA" banner moved from each file's first line to its last, because as an opening
  line it made receipts read as newsletters. Fine-tuning (A1/F3) on corrections is the fix this
  points at; the app now collects exactly those corrections. Tests: **371 across the build**, 10 of them gated on the weights —
  `:templates` 20, `:sources-desktop` 16, `:loupe-desktop` 19 (1 gated: a real-Laya sweep of the
  sample on five templates), `:engine` 254, `:backend-onnx` 27 (6 gated), `:game` 35 (3 gated).
  `./gradlew clean build` passes with `models/` present (371 run) and with it moved aside (361 run,
  10 skipped).

- **2026-09-23 — A5: mechanical rows are marked in the ledger.** On the owner's "Go". `LedgerRow`
  gained `resolvedBy`, a sealed `ResolvedBy`: `Model`, `Mechanical(check)` (the check's name from
  `Mechanical.Resolved.by`, e.g. `exact-duplicate`) or `Unusable` (the model was asked and its answer
  failed validation). A row must agree with itself — `Unusable` if and only if it carries a `failure`.
  A correction is not a resolver: it is a label about a row and changes nothing about what answered
  it. `DecisionEngine` sets it on every path. `Export` writes it on every line as `"model"`,
  `"unusable"` or `"mechanical:<check>"`; the desktop ledger file is that same format, so it
  round-trips there and through F4. **Legacy lines** (no field) load as `Unusable` when they carry a
  failure and otherwise as `Model` — a mechanical row written before today cannot be told from a
  sure model row by its contents, so the desktop no longer guesses from the item's `duplicateOf`.
  Where it matters, model figures now say explicitly that they count model rows only: D2
  calibration (`VisibleCalibration`, desktop agreement, selective accuracy, ECE, the held-out fit)
  counts mechanical rows as decisions and reports them, never as corrections; the §7 `Harness`
  takes an optional mechanical check and keeps mechanically answered fixtures out of `n`, every model
  figure and the baseline (reported as `mechanical`); D4 on desktop drops them before the replay; the
  D3 slider and A8 threshold replay treat them as ungoverned (the replay takes the logged action for
  every non-model row); the D1 queue never queues them. Results says "Answered by rule:
  exact-duplicate"; Calibration notes how many decisions a rule answered. Tests: 376 → **386**
  across the build, `./gradlew check` green.

- **2026-09-23 — A2/A3: the judgment is told when its input was cut.** On the owner's "Go".
  `Backend.score` now returns `Scored` — the raw masses plus `modelContext: Extent?`, the state
  tokens the model read of how many the state encoded to, set only when it cut. `OnnxBackend` fills
  it from `TokenizedInput.stateTokens`/`stateTokensKept`, which `LayaTokenizer` copies from
  `LayaSequence`; `Backend.ofMasses` wraps a masses-only lambda for fakes and replays. `TextState`
  reports its own character-budget cut as `budgetCut` (kept of total characters; `StateItem` now
  records `sourceLength`). `DecisionEngine` combines the two into `Truncation(textBudget,
  modelContext)` on every model and unusable row — cut by the text budget and cut by the model
  context are recorded separately, since a dense 4,000-character state can pass the first and
  still lose its tail to Laya's ~760 state tokens. **Policy** (`Policy.onCutInput`): an answer about
  part of an item is not an answer about the item, so under `NULL_ACTION` and `LOUD` an `Act` on a
  cut input becomes an `Abstain` carrying the cut — the item queues, with propensity 1 and no
  exploration; under `OPEN` the decision stands and the cut is only recorded. Uncut inputs are
  untouched. `Export` writes `"truncation"` when known (`{}` is known whole, each key a cut with
  `kept`, `total`, `unit`); **legacy lines**, and mechanical rows, have none and load as unknown
  (`truncation == null`), which `LedgerRow.truncated` reads as false. The desktop applies the same
  rule to `DecisionView.acted` (so the queue, coverage and agreement agree with the engine), replays
  the logged context cut in D4, and Results shows "Input was cut: read first N of M characters (text
  budget), then first N of M tokens (model context)." Tests: 386 → **401** across the build,
  including a gated Laya test that a dense ~3,500-character state reaches the ledger cut by the
  model context and a short one does not; `./gradlew check` green.

- **2026-09-23 — Risk 11 closed: third-party notices generated and shipped.**
  `./gradlew :loupe-desktop:generateThirdPartyNotices` (script: `gradle/third-party-notices.gradle.kts`)
  walks `:loupe-desktop:runtimeClasspath` — the union of every shipping module's, test
  configurations excluded — and writes `loupe-desktop/src/main/resources/THIRD_PARTY_NOTICES.txt`
  (~1 MB, packaged in the app jar) plus a manifest, `third-party/notices.lock`. Five sections: 51
  Java libraries (licence from the POM, following `<parent>`; every `LICENSE`/`NOTICE`/`COPYING`/
  `ThirdPartyNotices` file in the jar reproduced verbatim — including ONNX Runtime's 345 KB
  notices and PDFBox's external components — and the canonical SPDX text where a jar ships none);
  16 native components the jars' own notices miss (Skia and its ICU, HarfBuzz, libpng, expat,
  libjpeg-turbo, libwebp, zlib and OFL font inside `libskiko`; libffi in JNA; the Rust standard
  library, GCC runtime and winpthreads DLLs in DJL); **213 Rust crates** linked into
  `libtokenizers`, pinned in `third-party/tokenizers-crates.tsv` from DJL v0.38.0's own
  `Cargo.lock` (normal dependencies, all four shipped targets) with each crate's licence files
  committed under `third-party/texts/` — DJL ships no crate notices, so
  `tools/third-party/refresh-tokenizers-crates.py` (the one networked step, run by hand) derives
  them; and the non-Maven items (PSL, river-raid-2k, Laya and mmBERT weights). Deterministic: sorted,
  no timestamps or paths, and platform suffixes (`-macos-arm64`, `-linux-x64`) normalised so a Mac
  and the Linux CI produce the same bytes. **Staleness** is caught twice: `checkThirdPartyNotices`
  (wired into `check`) regenerates in memory and diffs both files; `ThirdPartyNoticesTest` (5
  tests) independently re-reads the resolved jars and fails when a runtime artifact is missing
  from the lock or no longer resolves, when a jar's licence files changed, when a jar carrying
  native code has no reviewed row in `third-party/native-components.tsv` (keyed by exact version,
  so any bump forces a review), or when a crate whose source path is embedded in any of the four
  `libtokenizers` binaries is absent from the pinned list at that version; each failure names the
  task to run. Offline: jars and POMs come from Gradle's cache (a first run on a fresh machine
  fetches POMs through normal resolution; `--offline` works after), no model weights needed. **Not
  sourced automatically:** copyright lines for jars that ship no licence file (ORT, XMPCore — in
  `third-party/maven-overrides.tsv`), the native-component rows (from the earlier `strings`
  review) and the asset rows, all hand-reviewed tables; the OFL font inside `libskiko` is still
  unnamed. `option-ext` (a `dirs` dependency in `libtokenizers`) is MPL-2.0 — file-level, shipped
  unmodified, the Eigen rule. Tests: 401 → **406** across the build; `./gradlew check` green.

- **2026-09-23 — Criteria in the prompt: built, default off, pending real corrections.** On the
  owner's "Go" (hand-off item 5). **Channel:** upstream's descriptive options. `Judgment.Choice`
  gained `descriptions` (label → text, default empty); `LayaPrompt.build` takes one per option and
  renders it exactly as upstream `render_options` does for `{label: description}` —
  `"label: description"`, `None`/`""` meaning bare. **Budget, mirrored not improved:** upstream gives
  a description no budget of its own; it is part of the option text, so the whole
  `" label: description"` is capped at 48 tokens and shrinks with the other options to
  `max(4, (256 − 16) / k)` when they overflow the 256-token head. What was cut is no longer silent:
  `LayaSequence.optionTokens/optionTokensKept` → `TokenizedInput.optionCut` → `Scored.optionCriteria`
  → `Truncation.optionCriteria` on the ledger row, exported as `"optionCriteria"` and shown in
  Results. It is **not** an input cut (`isCut`/`truncated` unchanged, the cut-input policy does not
  fire): the item was read whole; the criteria were shortened. `Tokenizer.encodeDescribed` refuses
  descriptions in any tokenizer that cannot show them rather than dropping them. Descriptions enter
  the criteria hash only when present, so every existing judgment keeps its hash and its answers.
  **Derivation** (`UserJudgment.optionCriteria`): a two-option judgment's positive option reads the
  invariant and its negative option what breaks it; a score's values read their bands; a multi-way
  pick has no per-option criteria and gets none; an unwritten `(not written yet)` is never sent.
  **Opt-in per judgment** (`UserJudgment.criteriaInPrompt`, persisted only when on, **default off**);
  turning it on changes the hash, so calibration restarts, and the app says so. **Parity:**
  `tools/make-laya-criteria-fixture.py` (run `USE_TF=0 tools/.venv/bin/python
  tools/make-laya-criteria-fixture.py`; needs the checkpoint and the exported graphs, exports
  nothing) writes `backend-onnx/src/test/resources/laya/criteria.json` — 8 descriptive-option
  questions through upstream's own `Agent._to_internal` → `build_sequence` → forward pass, checked
  against `Agent.predict`: two- to twelve-option, mixed bare/described/`""`, a score with bands, a
  description past the 48-token cap, twelve described options forcing the head shrink, and a
  description forging `<mask>`. The ungated `LayaCriteriaPromptTest` rebuilds all 8 token for token
  from recorded segments; gated, DJL reproduces every segment, the Kotlin prompt every sequence, and
  the graphs match upstream PyTorch — **FP32 8/8 argmax, max |Δp| 1.1e-6; INT8 8/8, max |Δp| 0.114**
  (the forged-mask near-tie; same tolerance as the bare-label cases). **Measurement hook:**
  `Analysis.compareCriteria` re-runs the model both ways over labelled items through `Harness`
  (accuracy at full coverage, ECE, Brier, coverage, baseline, and how many items had their criteria
  cut); the Baseline screen runs it on the user's corrected items ("Compare with vs without") beside
  the on/off switch. **On the synthetic sample** (gated `CriteriaMeasurementTest`, real INT8 Laya,
  45 text items, hand labels in `loupe-desktop/src/test/resources/sample-labels.tsv` written by the
  person measuring — invented data, not an accuracy number):

  | judgment | accuracy without → with | ECE | Brier (multi-class, 0–2) | keyword baseline |
  |---|---|---|---|---|
  | is-receipt | 31/45 → 31/45 (68.9%) | 0.187 → 0.226 | 0.443 → 0.494 | 95.6% |
  | phishing | 21/45 → **6/45** (46.7% → 13.3%) | 0.435 → 0.811 | 0.901 → 1.590 | 97.8% |
  | needs-reply | 23/45 → **39/45** (51.1% → 86.7%) | 0.278 → 0.127 | 0.716 → 0.248 | 95.6% |

  No criteria were cut on any item. One judgment unchanged (and slightly worse calibrated), one much
  better, one much worse — phishing with its invariant attached calls almost everything a scam. Every
  arm still loses to its keyword baseline. That is why the default stays **off**: the effect is
  large and judgment-specific, so it is a per-judgment switch to be decided by that judgment's own
  corrections, not a global change. Tests: 406 → **427** across the build, 14 of them gated on the weights
  (4 new: DJL/prompt and FP32/INT8 parity on `criteria.json`, and the sample measurement); `./gradlew
  check` green with `models/` present, and the gated ones skip cleanly with it moved aside.

- **2026-09-23 — A better INT8 (hand-off item 2): SmoothQuant and static calibration fail; a
  partial-Wo INT8 passes, opt-in.** On the owner's "Go". `tools/quantise-laya-wo.py` (new, beside
  the export script) starts from the existing FP32 graph and measures every variant against both
  committed fixtures (`golden.json` 34 + `criteria.json` 8 questions; "flips" = selected answer
  differs from the FP32 graph, "max Δp" = largest per-option probability difference). Calibration
  set, fixed order, seed 0: 39 sample-data texts (`.eml`, the `.mbox` messages, `.md`, `.csv`,
  `.html`, `.json`) × 3 app questions (receipt / needs a reply / phishing), plus the 34 parity
  sequences — 163 sequences. ORT 1.20.1, onnx 1.18.0, numpy 2.2.6. Latency: short questions
  (≤128 tokens), batch 1, Apple M4 CPU, 60 runs, load average at the time in brackets.

  | Recipe | Wo quantised | Size | Flips (golden + criteria) | Max Δp | P50 / P95 short |
  |---|---|---|---|---|---|
  | **Shipped INT8** (Wo FP32) | 0/22 | 383.9 MB | 1 + 0 (en-sentiment-5, margin 0.008) | 0.114 | 43.9 / 84.3 ms (3.4) |
  | SmoothQuant α=0.5 + dynamic | 22 | 325.5 MB | 3 + 1 | 0.297 | 42.5 / 82.9 ms (4.4) |
  | SmoothQuant α=0.65 + dynamic | 22 | 325.5 MB | 0 + 1 (crit-shrink-12, margin 0.04) | 0.259 | 44.9 / 87.6 ms (5.3) |
  | SmoothQuant α=0.8 + dynamic | 22 | 325.5 MB | 2 + 0 | 0.170 | 41.0 / 76.4 ms (4.3) |
  | Static QDQ Wo, no smoothing | 22 | 325.6 MB | 6 + 2 | 0.963 | 44.6 / 86.6 ms (4.3) |
  | Static QDQ Wo + smooth α=0.5 / 0.65 / 0.8 | 22 | 325.6 MB | 5 / 7 / 5 | 0.643 / 0.818 / 0.679 | 43.9–53.3 ms P50 (4.3–5.7) |
  | **Partial: greedy Wo subset** | 11 (layers 10, 13–21) | **357.4 MB** | **1 + 0** (the same near-tie) | **0.111** | 43.4 / 85.5 ms (4.1) |

  **SmoothQuant** (scales folded exactly into the gate half of each `Wi`, checked: FP32 output
  unchanged to <1e-4, then per-channel dynamic INT8 of everything) saves the full 58 MB but at best
  doubles the max error; α=0.65 keeps every golden answer yet flips a criteria case. **Static
  calibration is worse**: ORT's static quantiser has no per-channel *activation* scales, so a
  calibrated per-tensor scale is fixed on every input instead of fitted per input, and confident
  answers flip (margins up to 0.98). **Per-layer sweep**: quantising any one of layers 0–5, 9, 11
  or 12 alone already exceeds today's error (layer 11 alone: 5 flips); the late layers are
  tolerant. Greedily adding layers in order of least damage while flips ≤ 1 and max Δp ≤ 0.1144
  kept 11. **Acceptance met, by the letter:** same flips, max Δp 0.111 vs 0.114, 26.5 MB (7%)
  smaller, same latency. **Caveat that decides the default:** the 11 layers were *chosen on these
  same parity questions*, so the parity result is in-sample and flatters it; the 0.003 error margin
  is noise. So the new file ships **alongside, opt-in**, and the default stays the INT8 it was:
  `-Dloupe.laya.variant=int8-partial` (a fixed set of names; `./gradlew run -PlayaVariant=int8-partial`
  in both desktop apps). Switch the default only after it holds on held-out labelled items.
  `laya-multilingual-choice.int8-partial.onnx`: 357,361,791 bytes, SHA-256
  `03d732c31f7da991c6d5b1b816032431de67cc7e0080b17ed63a9053e41be973` (not byte-reproducible —
  pin by parity). Full numbers in `tools/laya-wo-report.json`. Quantising one variant takes
  ~20 s; the 22-layer sweep plus greedy pass ~20 min, and was once killed for memory on this 16 GB
  machine — the script caches per-layer results and resumes. Tests: 427 → **428** (1 gated:
  `int8-partial` parity on both fixtures); `./gradlew check` green with `models/` present.

- **2026-09-23 — Decisions: Loupe leads on iOS; online helper rules; a Web tab (epic #6).** On the
  owner's "Go"; docs only, no code. **Platform:** iOS first, Android second — §0 rewritten with what
  iOS does not allow and what that costs the spec (notification triage not possible, form filling
  limited to Safari and the Credential Provider, impersonation on email and contacts only). The
  engine and templates move to Kotlin Multiplatform behind an XCFramework `LoupeKit`; ONNX Runtime
  iOS and the HF `tokenizers` crate built for iOS replace the JVM runtime on the phone; Vision,
  PhotoKit, EventKit, Contacts and `BGProcessingTask` replace their Android counterparts.
  **Online helper rules** are now `PRODUCT.md` §4a, and risk 14 points at them. **Web tab** (tabs:
  Now, Judgments, Web, Sources, Me), flights first. **The helper is deployed:** `sambawy01/loupe-web-helper`
  (private) on Railway at `https://loupe-web-helper-production.up.railway.app`, `POST
  /v1/flights/search` via Duffel, schema v1 — fetch-only, no AI, the user's Duffel key sent per
  request and never stored or logged. None of the iPhone app exists yet; iPhone latency is
  unmeasured and the model is untuned.


---
- **2026-09-23 — Epic #6 child 2: `engine` and `templates` are Kotlin Multiplatform; `LoupeKit`
  XCFramework builds.** On the owner's "Go". Targets `jvm`, `iosArm64`, `iosSimulatorArm64`;
  sources in `commonMain`, JVM seams in `jvmMain`, iOS seams in `iosMain`; all former tests in
  `commonTest`. New module `loupe-kit` (umbrella, exports both) —
  `./gradlew :loupe-kit:assembleLoupeKitXCFramework` writes
  `loupe-kit/build/XCFrameworks/{debug,release}/LoupeKit.xcframework` (static); a Swift file linked
  against the simulator slice ran on a simulator and read the PSL version. Replacements, each
  pinned: `java.time` → `kotlinx-datetime` 0.6.1 (engine's public date types are now
  `kotlinx.datetime.LocalDate`; `loupe-desktop` converts at its boundary); SHA-256 → expect/actual
  (`MessageDigest` / CommonCrypto `CC_SHA256`); `java.net.URI` → the manual host extraction alone
  (it was already the fallback; a jvmTest checks it against the old URI code on 34 URLs);
  `IDN.toASCII` stays on the JVM, iOS uses a common RFC 3492 punycode + nameprep approximation
  (NFKC via Foundation) checked against the JDK on every non-ASCII PSL label; `Character.UnicodeScript`
  stays on the JVM, iOS uses a table generated from JDK 21 by `tools/GenUnicodeScripts.java`,
  checked on every code point; `String.format("%.Nf")` stays on the JVM, iOS uses a portable
  HALF_UP formatter checked against `Formatter` on ~29k values. PSL on iOS: a Kotlin constant
  generated at build time from the same `.dat` (no NSBundle — K/N frameworks carry no resources and
  the app would have to ship the file separately); the SHA-256 test now runs on both targets over
  the bytes each actually loads. Seeded randomness unchanged (`kotlin.random` everywhere);
  `DeterminismPinTest` pins exploration, A8 bootstrap intervals, the audit arm and fixture splits
  to values captured on the JVM before the port, and passes on iOS. Tests: JVM 440 (was 428; +12
  new), iOS engine 288 + templates 22; the 5 `PlatformParityTest` cases are JVM-only because the
  JDK is their oracle. CI: iOS targets are declared only on macOS hosts, so Linux CI runs every JVM
  test and never touches Kotlin/Native; a manual-dispatch `ios` job on `macos-15` runs the iOS
  tests and the XCFramework (10x Linux minute cost, hence not on every push). `kotlinx-datetime` is
  Apache-2.0 and is in the regenerated third-party notices.

- **2026-09-23 — Epic #6 child 3: Laya runs on iOS behind `Backend`; simulator parity identical
  to the JVM.** On the owner's "Go". Three pieces:
  - **Shared prompt.** `LayaPrompt`, `LayaSequence`, `LayaTokenizer`, `SubwordEncoder`,
    `TokenizedInput`, `Tokenizer`, `TensorNames` moved from JVM-only `backend-onnx` into a new KMP
    module `backend-laya-common` (jvm + iOS, same package, code unchanged), plus `ChoiceScoring`
    (the marker checks before a run and the Double softmax after), which the JVM `OnnxBackend` now
    calls too. JVM behaviour unchanged: the 41 `backend-onnx` tests, including the gated Laya ones,
    pass as before.
  - **Tokenizer.** `ios-native/tokenizers-ffi`: a five-function C ABI over Hugging Face
    `tokenizers` **=0.21.4** with `onig` 6.5.1 — the exact versions inside DJL 0.38.0's
    `libtokenizers` (read from its binary) — `default-features = false` (no `http`: no network
    code compiled in), encode with `add_special_tokens=false`, truncation and padding cleared on
    load. Rust toolchain pinned (1.98.1, `rust-toolchain.toml`), `Cargo.lock` committed,
    `IPHONEOS_DEPLOYMENT_TARGET=14.0` to match Kotlin/Native. `ios-native/build.sh` builds
    `LoupeTokenizers.xcframework` (device + simulator) and fetches the official ORT iOS package
    (`pod-archive-onnxruntime-c-1.20.0.zip`, SHA-256 pinned) into `ios-native/build/` (gitignored).
  - **Inference.** New module `backend-onnx-ios` (iOS only; included by `settings.gradle.kts` on
    macOS once `ios-native/build.sh` has run): cinterop on the ORT C API and the tokenizer header,
    both static libraries embedded in the klib. `RustSubwordEncoder` (twin of
    `HuggingFaceSubwordEncoder`, checks the special-token ids), `OrtLayaBackend : Backend` (CPU,
    default session options as on the JVM), `LayaModelStore` (expects `tokenizer.json` and the INT8
    graph in `Library/Application Support/Loupe/laya-multilingual`, streams SHA-256 through
    CommonCrypto and refuses anything not matching the pins — the tokenizer hash from
    `export-laya-onnx.py`, the graph hash from `laya-export-report.json`). **No network:** the
    consented first-run download is the app layer's; `ios-native/sideload-models.sh <bundle-id>`
    copies the files onto a simulator for development (checked against the pins).

  Parity, `./gradlew :backend-onnx-ios:iosSimulatorArm64Test` (iPhone simulator, iOS 26.3, Apple
  silicon Mac mini), INT8 graph `8b994315…`, same fixtures the JVM reads:

  | Check | iOS simulator | JVM INT8 |
  |---|---|---|
  | Tokenizer segments identical to upstream (golden + criteria) | 239/239 | 239/239 |
  | Rebuilt input ids + marker positions | 42/42 | 42/42 |
  | Golden: same selected answer as JVM INT8 | **34/34**, max \|p − p_JVM\| 2.2e-16 | — |
  | Criteria: same selected answer as JVM INT8 | **8/8**, max \|p − p_JVM\| 0.0 | — |
  | Golden vs PyTorch | 33/34 (flips `en-sentiment-5`), max \|Δp\| 0.0929 | 33/34 (same case), 0.0929 |
  | Criteria vs PyTorch | 8/8, max \|Δp\| 0.114 | 8/8 |
  | Mean latency per item (simulator on a Mac, **not a phone**) | 82 ms golden, 87 ms criteria | — |

  **Phone latency: still unmeasured** — needs a physical iPhone (owner to provide); record model,
  iOS version and thermal state with it. Tests: JVM still 440; iOS simulator +11 (engine 288,
  templates 22, backend-onnx-ios 11). The device slice (`iosArm64`) compiles and links. Notices:
  the iOS app ships new native code, so `:backend-laya-common:generateIosThirdPartyNotices` writes
  `ios-native/THIRD_PARTY_NOTICES-ios.txt` (ORT 1.20.0 iOS, its ThirdPartyNotices, Rust std, 71
  crates from `third-party/ios-tokenizers-crates.tsv` — `refresh-tokenizers-crates.py --ios`),
  checked by `check` and cross-checked against the crate paths in `libloupe_tokenizers.a`. Not yet
  done: `LoupeKit` does not export `backend-onnx-ios` (the app shell, child 5, decides how it links
  it); the app must bundle the iOS notices file.

- **2026-09-23 — Epic #6: Laya wired into the iPhone app.** On the owner's "yes". Uncommitted
  pending review.
  - **Linking.** `LoupeKit` now exports `backend-onnx-ios` and `backend-laya-common` when
    `settings.gradle.kts` included them (i.e. after `ios-native/build.sh`). The static framework
    already **embeds** ORT and the Rust tokenizer (cinterop `-staticLibrary`), so the app links
    `LoupeKit.xcframework` alone — linking the two native xcframeworks again would duplicate
    symbols. `loupe-kit/src/iosLaya` holds `LayaOnPhone`, a Swift-safe door (Kotlin exceptions
    must never cross into Swift) to find, verify and open the model.
  - **Ranking.** Common Kotlin `dev.loupe.kit.flights`: `FlightState` (offer → compact
    `TextState`, 480-char budget, price/airline/legs/bags/terms in that order so a cut drops terms
    first), `FlightPriorities` (the user's text through `JudgmentAuthor` as a two-option Choice,
    "fits" / "does not fit" with descriptions — the bare yes/no trap), `FlightJudge` (validate →
    identity recalibration → `Policy.decide` at the two-option default threshold 0.80 →
    `Policy.onCutInput`; not acting = **unsure**, the uncertain queue's rule; a broken backend is
    unusable and sorts last, never throws). Swift `LayaRanker: OfferRanker` runs it off the main
    thread with progress and cancellation between offers; `RuleBasedRanker` always runs as the
    baseline. Results say "Ranked by: Laya (on this phone)", with a toggle to the rule ranking, a
    line when the two disagree on #1, and a rules-only note linking to the model screen when Laya
    is missing, fails or refuses the text. Mascot: scanning while ranking, found when done,
    thinking beside unsure offers.
  - **Model on the phone.** Me → Laya model → "Get the on-device model": ~418 MB (384 MB INT8 +
    34 MB tokenizer), one download then offline, SHA-256 checked before use, nothing until the user
    taps. **`LayaModelSource.baseURL` is empty** — the repo records no host for the export — so the
    screen says "Model URL not configured" and points at `ios-native/sideload-models.sh`.
    Downloads go to a temp file, are hashed against the pin, excluded from backup.
  - **Notices / Release.** The app bundles `THIRD_PARTY_NOTICES-ios.txt` and both font OFL texts,
    shown under Me → Licences; fonts added to `THIRD_PARTY_NOTICES.md` and `LICENSING.md`. Release
    excludes `flights-lis-lhr.json` (`EXCLUDED_SOURCE_FILE_NAMES`); a Release build's bundle was
    checked to lack it and the launch-argument strings.
  - **Real model on the fixture** (simulator, side-loaded, priorities "nonstop, under £200, not
    before 7am, 1 checked bag"): Laya `05` 0.73, `02` 0.69, `03` 0.55, `01` 0.52, `04` 0.39, `06`
    0.36 — **all six unsure** (none reaches 0.80); rules `02 03 04 06 05 01`. Laya's #1 is the
    £241.80 BA offer the price cap rules out; the untuned model does not read "under £200" against
    a price. The screen says so (disagreement line). 0.59 s for 6 offers on the simulator.
  - Tests: JVM 450 (+10 `FlightJudgeTest`), iOS simulator Kotlin 331; Xcode 33 unit (1 gated,
    skips without the model) + 2 UI, green.
- **2026-09-23 — Risk 12 closed by owner decision; Loupe Station is the shipping desktop app.**
  Docs only. The owner ("we searched and its Apache2.0") cleared
  `convaiinnovations/laya-multilingual` for shipping under its Apache-2.0 licence and treats
  risk 12 as an early assessment now closed. The training-data evidence — NC sources named in the
  authors' benchmark, the unpublished full mix, the Gemma 2 tokenizer terms — stays in
  `LICENSING.md` as the basis the owner weighed; asking the authors remains an optional
  follow-up. Separately: **Loupe Station** (repo `sambawy01/loupe-station`, Python/Swift, bundle
  id `com.loupe-ai.desktop`) is now the shipping desktop app. `loupe-desktop` in this repo stays
  as the reference implementation whose watchers, measurement stack and 55 templates Loupe
  Station is porting — **mirror any change to watcher logic or thresholds there.** The
  never-list change `b05f996` (writing is opt-in and approval-gated) was made from that session.
- **2026-09-23 — Epic #7 child 6: retroactive sweep and passive mode on iPhone (F1, F2).** New
  shared `dev.loupe.kit.sweep` (loupe-kit common, no new dependency): `ModelLane` — claims at
  `FOREGROUND` > `GAME` > `SWEEP`, thread-safe, an idle signal when the last claim above a sweep goes
  — and `SweepCoordinator`, which runs `JudgmentSweep` for every judgment over every item of every
  enabled source (skipping items already decided under the judgment's current criteria hash, as the
  desktop does), then `WatcherRun`; one progress across judgments (done/total, items/s, median model
  latency from a timing wrapper around the backend), stops between items on cancel, BGTask expiry,
  heat, Low Power Mode or a higher claim (`StopReason`), always handing rows over before returning,
  so running again resumes with no item judged twice; `SortSummary` ("N sorted, M need you" — M are
  answers below threshold or unusable). Tests (JVM + iOS simulator): skip-already-judged and
  rewording, cancel/expiry then resume exactly once, preemption by a foreground claim mid-sweep and
  resume on release, the priority order, a failing judgment not stopping the others. iOS: every model
  call now runs on one serial queue (`ModelWork`): the Results sweep, the watchers and flight ranking
  claim `FOREGROUND`, the game claims `GAME` while Laya flies (released on close and in deinit), the
  pilot's executor moved onto the same queue. `SortService` (Run now, progress, cancel, thermal
  `.serious`/`.critical` and Low Power Mode gating before and during a run, auto-resume after
  preemption with counts carried across the pause, last run persisted), `BackgroundSorter`
  (BGProcessingTask `dev.loupe.app.sort`, `requiresExternalPower`, no network, registered in
  `LoupeApp.init`, rescheduled each run, expiration handler stops at the next item, completes
  unsuccessfully when stopped so iOS offers another window), a local notification (permission asked
  only when the setting is turned on), Me → Sorting (toggle **off by default**, Run now, progress,
  Cancel, last run) and a Now card. XCTests drive the BG handler through a fake scheduler/task and
  the gating through fake conditions; a UI test runs Me → Run now on the sample with a stand-in
  scorer. How to fire the task in the simulator debugger is in `ios/README.md`. **Not measured:**
  battery drain and thermal behaviour on a real iPhone, and how often iOS grants the window — risk
  3's device half, owner-blocked on a physical iPhone. Traps: the sweep's latency moves with load
  (see risk 13), so items/s on the simulator is not a phone number.

- **2026-09-23 — Epic #7 child 1: the ledger on iOS, and export.** New KMP module `:persistence`
  (jvm + iOS, no new dependency): a small common JSON reader/writer that matches Gson byte for byte
  (compact and pretty; `GsonParityTest`), `LedgerCodec` / `CorrectionCodec` / `JudgmentCodec` moved
  out of `loupe-desktop`'s `Persistence.kt`, `LedgerStore` (append + fsync; judgments via synced temp
  file + rename; POSIX on iOS, java.nio on the JVM), `DataExport` (the desktop's four F4 files) and
  `PhoneLedger`, the Swift face (open, append, rows by judgment, stats, export; `@Throws`). The
  desktop `Store`, `Analysis.correctionIndex/effectiveRows` and `export` now delegate to it; desktop
  tests unchanged and green. `FlightJudge.decide` returns each offer's `LedgerRow`: `resolvedBy`
  model (unusable with a flat distribution on failure), propensity 1, truncation, item
  `web:duffel:<offer id>` — the source lives in the item id, `LedgerRow` gained no field. Rule
  rankings log nothing. iOS: `LedgerService` in Application Support/Loupe (temp under XCTest and
  `-LoupeFixtures`); Me shows "Decisions logged: N · stored only on this iPhone" and "Export my
  data" (zip via `NSFileCoordinator .forUploading`, share sheet). The iPhone export also measures
  judgments with rows but no saved definition (the flight priorities); the desktop does not.
- **2026-09-23 — Epic #7 child 2: the sample source and extractors on iOS.** New KMP module
  `:sources-common` (jvm + iOS, no new dependency): the `SourceItem` model, `PlainText` / `HtmlText`
  / `Links` ported from `:sources-desktop`, a small common MIME reader (folded headers, RFC 2047
  words, nested `multipart/`, quoted-printable, base64, UTF-8 / ISO-8859-1 / Windows-1252 /
  US-ASCII), `Mbox.split` (the desktop's `^From \S+.*\d{4}$` rule, so the "From " Trap behaves the
  same), `SourceScanner` (rule-for-rule port: kinds, `MimeFacts` "extension lies", skip reasons,
  `ContentHash`, duplicate tiebreak, truncation marker) and `SourceLibrary` (per-source scan cache +
  on/off, JSON through `:persistence`, atomic writes). PDF text and image metadata are a
  `PlatformExtractors` the host passes: PDFKit/ImageIO in Swift (`ios/Loupe/Sources/AppleExtractors.swift`),
  PDFBox/metadata-extractor on the JVM (`DesktopPlatformExtractors`). `ParityTest` in `:sources-desktop`
  holds the common scanner to the desktop `Scanner` on every sample item (ids, text, facts, hashes,
  dates, email facts, duplicates, skips — all equal) and the common MIME parser to mime4j on all 24
  sample messages. **Documented differences:** on the phone, ids carry a `sample:documents/` /
  `sample:mail/` prefix plus the relative path (the app container path changes across installs);
  PDF text comes from PDFKit, whose spacing and line breaks can differ from PDFBox (same words; the
  XCTests check content, pages, creation date, producer); image facts arrive from Swift unordered;
  raw 8-bit headers are read as UTF-8. The app bundles the sample verbatim as a folder `sample`,
  scans it off the main thread with progress on first launch, and caches it in Application
  Support/Loupe/sources; the Sources tab shows "Sample data — not from your phone" (on by default,
  switchable off), 48 items, skipped/duplicate/no-text counts, last scan and "Scan again", with
  Photos/Files/Mail/Calendar/Contacts listed as "Coming in child 7". Tests: 14 common tests (JVM + iOS
  simulator), 2 parity tests, 7 XCTests (PDFKit, ImageIO incl. a written EXIF/GPS JPEG, full sample
  scan, service cache and switch-off), 1 UI test.

- **2026-09-23 — Epic #7 child 3: the Judgments tab on iOS.** New shared code in `:loupe-kit`
  (`dev.loupe.kit.judgments`, common, no new dependency), because the desktop's judgment logic lives
  in `loupe-desktop` over `:sources-desktop` items: `JudgmentBook` (the desktop's `j-<slug>` ids,
  C1 "Use this" through `Template.instantiate`, C2 through `JudgmentDraft` plus a score shape over
  named bands; refuses bare `yes`/`no` options and blank ones — the Trap below; criteria-in-prompt
  default off, with the desktop's calibration-restart notice), `JudgmentSweep` (the controller's F2
  loop without threads: mechanical first, rows handed over every 16, cancel between items, never
  throws) and `JudgmentResults` (current wording only, latest per item; acted / unsure / could not
  judge, "answered by rule", the cut notes, warn-only "no signal"). `PhoneLedger.correctionIndex()`
  added. `SweepObserver`'s methods are `onSweepProgress`/`onSweepRows`: sharing
  `ScanObserver.onProgress`'s selector made Kotlin/Native mangle it in Swift. LoupeKit's `Category`
  cannot be spelled in Swift (the `LoupeKit` class shadows the module), so the Library keys
  categories by name. iOS: `Loupe/Judgments/` — `JudgmentsService` (list persisted as
  `loupe-judgments.json` through the ledger store; sweeps on a background queue; rows appended to
  the ledger as they come), Library, template detail, Write your own, Results (progress, items/s,
  median call, cancel; confidence bars against the threshold; unsure markers; item detail with the
  raw distribution). No model → "Model not installed" and nothing is judged. DEBUG
  `-LoupeJudgmentDemo <template>` / `-LoupeLibrary`. Tests: 10 common (JVM + iOS simulator), 9
  XCTests (fake backend, asserts off-main), 1 UI test (Library → Use this → My judgments).
  Screenshots `designs/ios-v1/07-judgment-results.png`, `08-library.png`.

## Hand-off

State as of 2026-09-23, for whoever picks this up — including a session with no memory of how it
got here. Track-by-track status is in **Where the build stands** above; this is what to do next,
and what will bite.

### What changed most recently

**Loupe leads on iOS** (2026-09-23, epic #6). §0 now records what iOS does not allow and what that
costs the spec; `PRODUCT.md` §4a holds the online helper rules; the Web tab starts with flights. The
fetch-only helper is deployed (`sambawy01/loupe-web-helper`, Railway). **Next:** child 2, the Kotlin
Multiplatform port of `engine` + `templates` — buildable here, no device needed. Children 3, 5 and 6
need an Apple developer account and a physical iPhone; 6 also a Duffel test key. See *The iPhone
app (epic #6)* above.

The change before it:

**The desktop app** (branch `desktop-app`): `:templates` (55 templates), `:sources-desktop`
(read-only folders and mail exports, mime4j, PDFBox, metadata-extractor, a synthetic sample
dataset) and `:loupe-desktop` (twelve screens over the real engine and Laya). Run it with
`./gradlew :loupe-desktop:run` (JDK 21); `./gradlew :loupe-desktop:snapshot -Pout=<dir>` renders
every screen to PNG over the sample in a throwaway home. It keeps what it learns in
`~/Library/Application Support/Loupe`. **The finding that matters most:** untuned, Laya loses to
dumb keyword baselines on every judgment measured on the sample, and ignored the question with bare
yes/no options — see the progress log. The app is built to show exactly that, per judgment.

The change before it:

**The demo game (F5), desktop half**, on branch `game-riverflight`: `:game` and `:game-desktop`,
an original river shooter flown by Laya with its raw probability bars, a safety override, a
hand-off slider and a baseline it currently loses to. Run it with
`./gradlew :game-desktop:run` (JDK 21); `./gradlew :game-desktop:match -Pseeds=1,2,3 -Pseconds=60`
for the headless comparison; `./gradlew :game-desktop:snapshot -Pout=<dir> [-Pcompare]` renders the
real UI to PNG off-screen. Numbers are in the progress log. Next for it: fine-tune on baseline-flown
states, an Android module, and a phone latency number.

The earlier change, still the base of everything:

**Laya replaced GLiClass as the primary model**, on the owner's decision, in a feasibility spike
on branch `laya-onnx-spike` (not yet reviewed or merged when this was written). The spike proved
the parts that could be proven on a desktop: the export (`tools/export-laya-onnx.py`), parity with
the authors' own PyTorch, the prompt format (`LayaPrompt`), a real tokenizer (DJL), and the whole
path running from Kotlin through `OnnxBackend` into `DecisionEngine`. Numbers are in the progress
log and in `tools/laya-export-report.json`.

To reproduce locally: `uv venv --python 3.12 tools/.venv`, install `tools/requirements-laya.txt`,
run `USE_TF=0 tools/.venv/bin/python tools/export-laya-onnx.py` (it downloads ~680 MB into the
gitignored `models/`, checks the hashes, exports and runs parity in a few minutes), then
`./gradlew build` with a **JDK 21** (`JAVA_HOME=/opt/homebrew/opt/openjdk@21/...` on the spike
machine; Gradle 8.14.3 does not run on JDK 25). With `models/` present the six gated tests run
instead of skipping.

DJL is the project's second runtime dependency set (nine artifacts, all Apache-2.0 or MIT, JNA
by choice of its dual licence); `LICENSING.md` has the check.

### The thing that has not changed, and matters most

**There is still no labelled corpus, so there are still no accuracy numbers.** There is now a
real model, which makes it easier to forget the other half. The Laya parity numbers measure
*agreement with upstream PyTorch*, not correctness — a graph can match its reference perfectly
and be wrong about every receipt. If an accuracy figure from this repo is ever quoted, check it
came from labelled fixtures.

### Open, and decided by someone other than the next session

- ~~**Risk 12, Laya's training data.** Not cleared for shipping.~~ **Closed 2026-09-23 by owner
  decision** — cleared for shipping under Apache-2.0. Optional follow-up, not a blocker: ask the
  authors for the multilingual checkpoint's full training mix. `LICENSING.md` keeps the evidence.
- ~~**The Gemma 2 tokenizer question** — part of risk 12.~~ Weighed in the owner's 2026-09-23 decision.

### Buildable here, right now

1. ~~**Close risk 11** — now including DJL and the Rust crates in `libtokenizers`.~~ Done
   2026-09-23 — `:loupe-desktop:generateThirdPartyNotices`; see the Progress log.
2. ~~**A better INT8.**~~ Done 2026-09-23, **partly**: SmoothQuant and static calibration tried,
   negative (every variant flips more answers or doubles the error). A partial recipe — 11 of the
   22 `mlp.Wo` quantised — holds parity and saves 26.5 MB, shipped **opt-in**
   (`loupe.laya.variant=int8-partial`) because its layers were picked on the parity set. Next step,
   if wanted: validate it on held-out labelled items, then flip the default. See the Progress log.
3. ~~**Surface token truncation.**~~ Done 2026-09-23 — `Scored`, `Truncation` on `LedgerRow`; see
   the Progress log.
4. **A fixture corpus**, and **hardening** (property tests for calibration and off-policy maths).
   The desktop app now produces labels: every correction is one, keyed by item and criteria hash,
   exported losslessly. The owner's own files are the cheapest corpus there is.
5. ~~**Put the criteria in front of the model.**~~ Built 2026-09-23, **default off, pending real
   corrections** — `UserJudgment.criteriaInPrompt`, parity case in `criteria.json`, and the
   Baseline screen's "Compare with vs without". On the synthetic sample it moved needs-reply
   51% → 87%, phishing 47% → 13%, receipt unchanged; see the Progress log. What remains is the
   owner's corrections deciding it judgment by judgment.
6. **Fine-tune on corrections** (A1/F3). The sample result says the untuned model does not beat
   keywords; whether a head fine-tuned on a few hundred corrections does is the next real question.
7. ~~**Mark mechanical rows in the ledger.**~~ Done 2026-09-23 — `LedgerRow.resolvedBy`; see the
   Progress log.

### Blocked, and on precisely what

| Blocked | Needs |
|---|---|
| Epic #6 child 3's device latency; children 5, 6 | An Apple developer account and a physical iPhone; child 6 also a Duffel test key |
| A1 on-device latency | A real device — an iPhone first — the export and runtime path exist |
| A1/F3 fine-tuning | A labelled corpus and a GPU |
| A2's second backend | Qwen3-0.6B weights and its own export |
| B1–B9 every source | Android APIs: SAF, MediaStore, ML Kit, Gmail OAuth, contacts, notifications. iPhone B1/B2 (IMAP)/B4/B5 built on the simulator (epic #7 child 7); OAuth mail needs the owner's client IDs |
| D5, E1–E4, F1 | Android UI, autofill, accessibility, WorkManager |
| F5 on a phone | An Android module and a device; the game logic (`:game`) needs no change |
| F2 on a phone | Android sources, WorkManager (built on the desktop) |
| Any real measurement | A labelled corpus **and** a model — the model half now exists |

### Traps — each of these was hit or narrowly avoided


- **2026-09-23 — Epic #7 child 5: the watchers on Now.** The desktop's watcher orchestration
  (`loupe-desktop` `core/Watchers.kt`) moved verbatim to loupe-kit common as
  `dev.loupe.kit.watchers.WatcherRun` over `:sources-common` items; the desktop now adapts its items
  and delegates (new dependency `loupe-desktop` → `:loupe-kit`; its `WatchersTest` unchanged and
  green). No watcher rule or threshold changed (Loupe Station mirrors these). New `WatcherFindings`
  (presentation only): one finding per impersonated sender, per fraud-flagged email, per breaching
  expiry candidate (LOUD: shown even when the model calls it something else), per moved term, per
  monthly merchant silent >45 days; evidence is the items' own lines ("Annual premium: £450.00 →
  £553.50 (+23%)", "Date of expiry: 14 JAN 2027"); verdicts are `CorrectionRecord`s under
  `watcher:<id>` / `watcher-v1`, keyed by finding; census with a monthly total. iOS: `Loupe/Now/`
  (`WatchersService`, finding/census/item views); the expiry model half runs when Laya is installed.
  Tests: `WatcherRunTest` (5, JVM with PDFBox + iOS simulator with PDFKit), `WatchersTests` (5
  XCTests), `NowFindingsUITests` (1). Note the sample's real numbers are £450 → £553.50.
- **A leading lookbehind is quadratic in Kotlin/Native's regex.** The station's `(?<!\d)…` PII
  patterns took 376 s on 50,000 characters on the iOS simulator; simply dropping the lookbehind and
  checking the previous character after `find` made ASSIGN's possessive prefix quadratic on the
  JVM instead. `GuardedRegex` (privacy) tries only the positions the lookbehind allows, each with
  an anchored `matchAt`: same results, linear on both.
- **Kotlin/Native regex has no `\p{N}`.** It throws "No such character class" at class init
  (surfacing as `FileFailedToInitializeException`). Write `\p{Nd}\p{Nl}\p{No}`; `\p{L}` works.
- **Test names with `,` or `()` do not compile for iOS.** Backticked names are fine on the JVM but
  Kotlin/Native rejects those characters; the port rewrote 26 names (`, ` → ` - `).
- **`YES` / `NO` are Objective-C macros.** A Kotlin `const val YES` breaks the LoupeKit header for
  every Swift importer; `Judgment.Bool.YES/NO` carry `@ObjCName("yesLabel"/"noLabel")`. Proving
  the XCFramework *links* means compiling Swift against it, not just assembling it.
- **KMP modules have `jvmTest`, not `test`.** `./gradlew :engine:test` no longer exists; use
  `:engine:jvmTest` or `check`. Keep `kotlinx-datetime` at 0.6.1 while Kotlin is 2.1.0.
- **iOS targets are gated on the host OS** in `engine`, `templates` and `loupe-kit` build files;
  on Linux those tasks simply do not exist.

- **Bare yes/no options make Laya ignore the question.** On the sample, "is this a receipt?" and
  "is this phishing?" got near-identical yes/no answers. Give a two-option judgment two options that
  say what they mean (`Shape.Binary`); measure any change on corrected items.
- **Showing Laya the criteria is not uniformly better.** On the sample the same mechanism took
  needs-reply from 51% to 87% and phishing from 47% to 13%. Never flip `criteriaInPrompt` on for
  every judgment at once; measure each on its own corrections ("Compare with vs without").
- **A banner on a document's first line colours the whole classification.** The sample's
  "SYNTHETIC SAMPLE DATA" header made receipts read as newsletters; it now sits at the foot.
- **PDFBox writes `~/.pdfbox.cache`** unless `pdfbox.fontcache` names a folder that **already
  exists** — it silently falls back to the home directory otherwise. The app and the test tasks
  create the folder first. A test run that leaves `~/.pdfbox.cache` behind means this regressed.
- **`List<Path> + path` concatenates the path's name elements**, because `Path` is
  `Iterable<Path>`. Export returned eleven "files" before this was caught; use `+ listOf(path)`.
- **A body line starting "From " splits an mbox message.** The sample's price-rise email did, until
  it was reworded; real exports escape it as `>From`, hand-made ones may not.
- **An organisation's second address is not impersonation.** CloudBox writing from `support@` and
  `receipts@` raised "known name, unknown address"; the app drops that signal when the new address
  is on the contact's own non-webmail domain (on gmail.com it stands).
- **Load averages move desktop latency a lot.** The same sweep measured 73–99 ms and 125–152 ms per
  item on the same machine an hour apart. Quote a latency with the conditions it was taken under.
- **Compose strong skipping hides live state.** Kotlin 2.x skips a composable whose arguments are
  the same instances, and the game mutates its session in place — so a panel that did not read the
  frame counter showed "waiting for the first decision" while the model was flying. Every composable
  that shows simulation state takes and reads `frame`.
- **`screencapture` fails from an agent session** ("could not create image from display"). Use
  `:game-desktop:snapshot`, which renders the same `App` through an off-screen `ImageComposeScene`.
- **Do not bump Compose Multiplatform past 1.7.x** without moving Kotlin: 1.7.3 is the line that
  matches the repo's Kotlin 2.1.0 Compose compiler plugin.
- **The game's look-ahead is exact only because the world is deterministic.** Anything that adds
  randomness after generation (enemy AI with its own rng, wall-clock reads) breaks the legal set,
  the override and the model-vs-baseline comparison at once. Draw it from the river's seed, in row
  order, or not at all.

- **The Rust `onig` C objects in the simulator slice claim iOS 26.2** despite
  `IPHONEOS_DEPLOYMENT_TARGET=14.0` (the sim target reads another variable); ld warns when the app
  links. Harmless on this simulator, to fix in `build.sh`.
- **LoupeKit has no x86_64 simulator slice.** A Release simulator build fails to link unless
  `EXCLUDED_ARCHS[sdk=iphonesimulator*] = x86_64` (set in `ios/project.yml`).
- **Kotlin/Native links cinterop static libraries only as `lib*.a`.** ORT's iOS framework binary
  (`onnxruntime`, no extension) embedded fine and then left `_OrtGetApiBase` undefined at link.
  `ios-native/build.sh` copies it to `onnxruntime-lib/<slice>/libonnxruntime.a` (arm64-thinned).
- **Rust/cc objects default to the SDK's iOS version** (26.x) and ld warns against Kotlin/Native's
  14.0 floor; `build.sh` sets `IPHONEOS_DEPLOYMENT_TARGET=14.0`.
- **The iOS tokenizer must be the DJL one.** `tokenizers =0.21.4`, `onig` 6.5.1, feature `onig`
  (not `fancy-regex`), `default-features = false`. Another version or regex engine can change ids
  silently; the 239-segment iOS test is what catches it.
- **Do not hand-roll a tokenizer.** Settled: `ai.djl.huggingface:tokenizers:0.38.0` resolves (the
  coordinates tried earlier were an older guess). It reproduces all 194 upstream token segments.
- **DJL truncates to 512 tokens by default.** `HuggingFaceTokenizer` defaults to
  `LONGEST_FIRST` truncation at a 512 `modelMaxLength`, which would silently cut the state before
  Laya's own budget applies. `HuggingFaceSubwordEncoder` turns truncation, padding and special
  tokens off; the over-length fixture case would catch a regression.
- **DJL phones home.** `newInstance` calls `Ec2Utils.callHome` (EC2 metadata probe, telemetry),
  and the native loader can download for GPU flavours — unless offline mode is on. The encoder
  sets it and refuses to load if it did not take. Never construct a `HuggingFaceTokenizer`
  directly; go through `HuggingFaceSubwordEncoder.open`.
- **The legacy ONNX exporter bakes the sequence length.** `torch.onnx.export(dynamo=False)`
  produced a graph that loaded and then failed on any input of another length (Reshape in the
  head's `MultiheadAttention`). The script uses the `torch.export` exporter. Parity across many
  lengths is what catches this — never check an export on one input.
- **torch 2.8's exporter runs a no-op opset conversion that fails** on graphs still holding
  local functions. The script skips it only when target and native opset are equal (18).
- **`quantize_dynamic` refuses the exported graph** because of stale `value_info` shape hints;
  the script clears them on a copy first.
- **Naive INT8 changes answers.** ORT's default recipe flipped 4/34 questions. The damage is in
  the 22 `mlp.Wo` matrices; keep them FP32 (the script does, and asserts it found 22).
- **SmoothQuant and static calibration do not rescue `mlp.Wo`.** Tried 2026-09-23: 2–8 flips or
  2–8× the error. Only the late layers (10, 13–21) quantise safely. ORT's static quantiser has no
  per-channel activation scales — do not expect "static per-channel" from it.
- **The ONNX files are not byte-reproducible.** Two exports of the same checkpoint gave identical
  outputs and different SHA-256s. Pin an export by its parity result, and record the hash of the
  file you actually ship.
- **The build cache replays test results across the weights appearing.** With
  `org.gradle.caching=true`, a gated test that passed locally was restored as "passed" after the
  weights were removed. `models/` is now a declared test input; keep it that way.
- **Upstream `laya.Agent` edits the checkpoint directory.** `_fix_tokenizer_config` rewrites
  `tokenizer/tokenizer_config.json` in place (not `tokenizer.json`, whose hash is what we pin).
- **transformers is pinned to 5.0.0**, the version that saved `encoder/config.json` — it uses the
  v5 schema (`rope_parameters`, `layer_types`). Loading under 4.x was not tried; do not assume it
  reads the sliding-window rope settings the same way.
- **Do not patch Eigen.** It is MPL-2.0, file-level weak copyleft. Unmodified it ships fine;
  modified, we owe the source of every file touched.
- **Do not re-run the copyleft scare.** A keyword scan of `ThirdPartyNotices.txt` trips on `GNU`,
  `Affero` and `non-commercial`. All four hits are documented false positives in `LICENSING.md`.
  DJL's Windows-only `libstdc++`/`libgcc_s` DLLs are GPL with the runtime exception — also fine,
  and never on Android.
- **`onnx-backend` will always look unmerged** to `git branch --merged`, because PR #1 was
  squashed. The content is in `392e25e`.

### Network access

The spike machine reached `huggingface.co` and its LFS CDN directly. The earlier cloud
environment did not: its proxy denied `CONNECT` before TLS, so a Hugging Face token could not
help, and only the environment's network policy (allowing `huggingface.co` **and**
`cdn-lfs.huggingface.co` / `*.hf.co`) opens it. If the next session runs somewhere like that, the
gated tests will skip and the export script cannot fetch the weights.
