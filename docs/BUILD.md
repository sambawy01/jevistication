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
| 10 | **Approximate public suffix list.** The engine ships a small built-in set of multi-label public suffixes, not the real Public Suffix List | Getting eTLD+1 wrong is a correctness bug in the fraud check, not a cosmetic one: it decides whether `paypal.secure-login.com` reads as PayPal or as `secure-login.com`. The suffix set is a parameter at every call site, so the real list drops in without touching callers. **Load the real PSL, with a refresh path, before the fraud check ships.** |

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
  rule enforces the same fact: this is a classifier and there is no generative model, so a question
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

---

## Where the build stands

As of 2026-09-22. Everything below was built headless: JVM Kotlin, no model weights, no Android
SDK, no device. CI runs the full suite on every push to `main`.

### Built and green

| Track | State |
|---|---|
| A0 Licence verification | Done (2026-09-22, see `LICENSING.md`) |
| A2 Backend **interface** | Done — `Backend`; no implementation can exist headless |
| A3 Mechanical extractors, text state | **Complete** — hash, dedup, MIME, dates, origin facts, OCR-presence, `TextState` |
| A4 Judgment type and validation | **Complete** — Choice, Bool and Score; strict validation, failure postures, never throws |
| A5 Ledger | Complete — append-only, propensity required, criteria hash |
| A6 Recalibrator | Complete — temperature scaling fitted by NLL; ECE, Brier, reliability bins |
| A7 Policy runner | Complete — pure, total, calibrated-only by construction |
| A8 Counterfactual engine | Complete — IPS, SNIPS, seeded bootstrap intervals, threshold replay |
| C1 Judgment library | Complete — seven built-ins on the three-part template |
| C2 Plain-language authoring | Complete — lint plus compilation to a typed judgment |
| C3 The five watchers | **Mechanical halves complete** — expiry, recurring money, term change, impersonation, site fraud |
| D1 Uncertain queue | Complete — margin ranking plus random audit arm |
| D2 Visible calibration | Complete — per judgment, no aggregate possible |
| D3 Threshold slider | Complete — counterfactual preview over logged rows |
| D4 Baseline runner | Complete — inside `Harness` |
| F4 Export | Complete — lossless ledger, judgments, calibration |
| §7 Measurement harness | Complete — group-wise splits, selective accuracy, coverage, ECE, Brier, baseline |
| Engine wiring | `DecisionEngine` composes the §8 architecture end to end |

### Blocked, and on what

Nothing below is deferred by choice; each needs something this environment does not have.

| Blocked | Needs |
|---|---|
| **A1** model runtime, fine-tune, latency | The 151M weights, ONNX export, and a real mid-range device — its acceptance criterion is a measurement on hardware |
| **A2** the two real backends | Model weights and a runtime |
| **B1–B9** every source | Android APIs: SAF, MediaStore, ML Kit, Gmail OAuth, calendar, contacts, notifications, WebView |
| **D5** actions, preview, undo | UI |
| **E1–E4** actuation | Android `AutofillService`, App Intents, accessibility, WebView |
| **F1** passive mode | WorkManager, charging and thermal constraints |
| **F2** retroactive sweep | Sources to sweep |
| **F3** overnight fine-tune | Model weights and a GPU |
| **F5** the game | UI |
| Real measurement | **A labelled fixture corpus.** Every number the harness produces today comes from synthetic fixtures; the machinery is proven, the numbers are not real |

### Proving milestones

| | Milestone | State |
|---|---|---|
| 1 | It runs | Blocked — needs a device |
| 2 | It decides | **Wired and tested** end to end with a stub backend; needs a real source and model to count |
| 3 | It learns | Machinery built (fit beats baseline on ECE in a test); needs real corrections |
| 4 | It shows its work | Machinery built (`replayThreshold`, `ThresholdSlider`); needs real logged history |
| 5 | It notices | Watchers' mechanical halves built; needs real personal data |
| 6 | It is honest | **Runs as a test** — a judgment that ties its baseline is reported as not beating it |
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

---

## Hand-off: the next step

Written 2026-09-22 for whoever picks this up next, including a fresh session with no memory of how
we got here. The state of every track is in **Where the build stands** above; this is only what
comes next and what will bite.

### The next piece of work

**`OnnxBackend`: a real implementation of the existing `Backend` interface.** Everything it plugs
into is built and green — `DecisionEngine` already composes the whole path, and the only reason
the engine has never seen a real model is that no implementation of `Backend` exists.

It must return the **raw** `Map<String, Double>` of label to mass. It must *not* return a
validated `Distribution`. That is the A4 boundary: the engine validates what a backend returns
precisely so a backend cannot hand over something already well-formed and skip the check. A
backend that pre-validates would quietly disable the guarantee.

### Why it is not done

The model weights could not be fetched. This environment's network policy denies
`huggingface.co` at the gateway:

```
kind:   connect_rejected
detail: gateway answered 403 to CONNECT (policy denial or upstream failure)
host:   huggingface.co:443
```

That is a deliberate access control, not a transient failure, and it was left alone rather than
worked around. The owner was asked to change the environment's network policy.

**When allowing it, allow the CDN too.** Model metadata comes from `huggingface.co`, but the
weight files themselves redirect to a separate LFS CDN — `cdn-lfs.huggingface.co` and `*.hf.co`.
Allowing only the API host produces a working metadata call and a failed download, which is a
confusing way to lose an hour.

### What was already established

- **ONNX Runtime for Java is on Maven Central**, latest `1.30.0` at time of writing.
- **PyPI works** (it bypasses the proxy), so a small synthetic ONNX graph can be generated locally
  to test the loading and tensor path without any weights at all.
- Disk headroom is ~30 GB. Not a constraint.
- The **tokenizer artifact is unresolved**: `ai/djl/huggingface/tokenizers` was not found at the
  coordinates tried. Settle this before designing around it.

### Two things to get right first

1. **Verify the licence at source.** ONNX Runtime would be this repository's *first runtime
   dependency* — everything so far has zero. A0's rule applies: read the licence from the artifact
   itself, not from a badge or from memory, and record it in [`LICENSING.md`](LICENSING.md).
2. **Weights are necessary but not sufficient for A1.** Its acceptance criterion is latency
   measured on a real mid-range device, not an emulator. A CPU backend running here makes the model
   *real*; it does not close A1.

### And the thing that stays true regardless

Every number this repository currently reports comes from **synthetic fixtures**. A real model does
not change that on its own — real measurement needs a real model *and* a labelled corpus. Until
both exist, the harness is proven machinery producing numbers that mean nothing about the world.
- **2026-09-22 — A2: a real `Backend` implementation (branch `onnx-backend`).** `OnnxBackend` runs
  ONNX Runtime inference behind the existing interface. It lives in a **separate `backend-onnx`
  module** so `:engine` keeps **zero runtime dependencies** — the offline core carries no
  third-party code. It returns the **raw** label-to-mass map, never a validated `Distribution`,
  because the engine validating a backend's output is the A4 boundary and handing back something
  well-formed would disable it. Tokenization is an interface, not a bound implementation: the
  export decides vocabulary and special tokens, so binding one tokenizer would tie the backend to
  one export. Softmax is computed in `Double` and shifted by the maximum, since `exp` of a large
  logit overflows in `Float` and these masses must normalise.
  **Failure is by exception, deliberately:** `DecisionEngine` catches it and applies the judgment's
  declared posture, so a mismatched export degrades one item rather than aborting a sweep — a test
  runs ten items alternating good and bad and asserts all ten are logged with five marked failed.
  Tested against a 352-byte synthetic ONNX graph (`tools/make-synthetic-onnx.py`), because the real
  weights are blocked by network policy. **The graph is not a model of anything** — it proves the
  loading, tensor, output and softmax path, nothing about accuracy. 264 tests green across both
  modules.
