# Build Plan

The work required to build what [`PRODUCT.md`](PRODUCT.md) specifies. Nothing here is a
scope cut; the ordering is dependency, not release.

---

## 0. Platform and stack

**Android.** The locked spec needs message history from known senders (person impersonation),
notification arrival, broad filesystem access, and cross-app form filling. iOS grants none of
the first three and only a Safari-scoped version of the fourth. Building for iOS means shipping
an incomplete spec.

| Layer | Choice |
|---|---|
| Language / UI | Kotlin, Jetpack Compose |
| **Decision model** | **`knowledgator/gliclass-modern-base-v2.0`** — 151M, Apache-2.0, ModernBERT backbone |
| **Second backend** | **`Qwen/Qwen3-0.6B`** — Apache-2.0, decoder, scored by logits |
| Model runtime | ONNX Runtime Mobile (NNAPI / XNNPACK execution providers) |
| Ledger | SQLite via Room |
| Background | WorkManager, charging + idle constraints |
| OCR and image labels | ML Kit, on-device |
| Speech | on-device recognizer |
| Email | Gmail API over OAuth; IMAP for everything else |
| Form filling | `AutofillService` |
| Notifications | `NotificationListenerService` |
| Files | Storage Access Framework + MediaStore |

**Why that model.** GLiClass is a single-forward-pass classifier over candidate labels — which
*is* the Choice primitive. ModernBERT backbone, encoder, non-autoregressive, 151M. Apache-2.0,
and the authors state it was trained on synthetic and licensed data permitting commercial use.
It is the cleanest licence in the entire survey, and it is the same base the openJev derivative
was built on.

We fine-tune it on our own fixtures and fit our own calibration — which is what A6 required
anyway, and which means **we depend on nobody's calibration claim.** That is the product's own
argument applied to its own foundations.

**Why that second backend.** A decoder scored by its logits against our encoder scoring labels:
architecturally different, which is what makes the agreement check in §6 of the spec meaningful.
Two similar architectures would fail alike. Apache-2.0, verified at source, and it is the same
base NanoJev used — so we get the diversity NanoJev offered without its licence problem. We
implement the logit-scoring ourselves; the method is a technique, not protected expression.

**Rejected, and why — see [`LICENSING.md`](LICENSING.md).** openJev-verdict-2.0: checkpoint
unobtainable, LICENSE a truncated Apache text GitHub classifies as `NOASSERTION`. simple-jev: no
repository licence. **NanoJev: its GitHub repository is MIT, but the weights and dataset on
Hugging Face declare no licence at all** — and a code licence does not carry to weights.
Laya-MLX is Apache-2.0 but Apple Silicon only.

**No hosted backend, ever.** Not an omission. A network call breaks the offline guarantee the
product rests on.

**Model delivery.** Weights are not in the base APK. Play Asset Delivery, or fetched on first
run. A ~150M model at int8 is a few hundred megabytes — normal for a mobile app, fatal for an
APK.

**Browser reach.** Chrome for Android does not support extensions. Form filling and the fraud
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
on it. Mitigating: ModernBERT is a standard architecture with mature export support, and
GLiClass scores labels in one forward pass rather than through bespoke decoding.

**A2 · Backend interface and the second implementation.** One interface; the GLiClass-based
model and a logit-scored Qwen3-0.6B behind it.
*Accept:* the same fixture set runs on both; a fidelity suite shows identical selected answers
across precisions, and repeated calls show no memory growth.

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
| B1 | Files, Downloads, PDFs | SAF; text extraction; no vision stage needed |
| B2 | Email | Gmail OAuth and IMAP; already text; richest outcome signal |
| B3 | Spreadsheets and CSV | row plus column context |
| B4 | Photos and screenshots | MediaStore + ML Kit OCR and labels |
| B5 | Calendar and contacts | contacts also feed the impersonation watcher |
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
how agreement has moved. **Never a single system accuracy number.**

**D3 · Threshold slider.** Backed by A8. Shows what a change would have done before committing.

**D4 · Baseline runner.** Every judgment carries a dumb baseline and the app reports which wins.

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
| 1 | ONNX export of the classification head may not be clean | Prove in A1 before anything is built on it; a second backend in A2 de-risks it |
| 2 | Model size versus APK limits | Play Asset Delivery or first-run fetch; never in the base APK |
| 3 | Battery and thermal cost of retroactive sweeps | Charging-constrained, throttled, cancellable, progress visible |
| 4 | ~~SMS default-handler review~~ | **Closed.** Not building messaging; impersonation runs on email and contacts |
| 5 | Accessibility and Play policy | Build flag; Play build cooperative-only |
| 6 | Judgments that do not beat their baseline | Expected for some. Milestone 6 exists to find them, and the honest answer is to ship the baseline |
| 7 | Prompt wording moving results as much as the algorithm | Wording is a controlled variable, and criteria text is hashed into every ledger row |
| 8 | The second backend is weak zero-shot | Untuned Qwen3-0.6B scores poorly on decision tasks. Both backends are fine-tuned on our fixtures; its votes do not count until it is |
| 9 | **Name collision.** `LOUPE` is a crowded mark. Registrations exist for jewellery-trade software (Atelier Technology), sports-card retail (Loupe Tech LLC) and a CRM (Apex); Mysk ships an iOS privacy app called Loupe | None is a consumer personal-data or fraud-detection app, but a crowded mark is a weak mark, and the Mysk app is adjacent on privacy and mobile. **Clear the mark in the target jurisdictions, and check Play Store and domain availability, before any spend on branding, the listing or the domain.** Decision taken with this known |

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
