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
| **Decision model** | **`convaiinnovations/laya-multilingual`** — 322M, Apache-2.0 weights, mmBERT-base backbone. *Training-data provenance open — risk 12* |
| **Second backend** | **`Qwen/Qwen3-0.6B`** — Apache-2.0, decoder, scored by logits |
| Model runtime | ONNX Runtime Mobile (NNAPI / XNNPACK execution providers) |
| Tokenizer | Hugging Face `tokenizers` (Rust) via DJL `ai.djl.huggingface:tokenizers`, offline mode enforced |
| Ledger | SQLite via Room |
| Background | WorkManager, charging + idle constraints |
| OCR and image labels | ML Kit, on-device |
| Speech | on-device recognizer |
| Email | Gmail API over OAuth; IMAP for everything else |
| Form filling | `AutofillService` |
| Notifications | `NotificationListenerService` |
| Files | Storage Access Framework + MediaStore |

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

**Model delivery.** Weights are not in the base APK. Play Asset Delivery, or fetched on first
run. Laya's INT8 graph is 384 MB (plus a 34 MB tokenizer) — heavy for a mobile download but
normal for an asset pack, and fatal for an APK.

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
| 11 | **Third-party attribution is unshipped.** The ONNX Runtime Android AAR bundles native libraries but contains no `LICENSE` and no `ThirdPartyNotices` at all; the desktop jar's notices cover ~85 components (MIT, BSD, ISC, Apache, Boost, zlib, MPL-2.0 Eigen, an Intel licence). **Since 2026-09-23 also DJL:** neither DJL jar ships a LICENSE or NOTICE, and nothing credits the Rust crates linked into `libtokenizers`. **Since 2026-09-23 also the desktop demo game:** no Compose, skiko or AndroidX runtime jar ships a LICENSE or NOTICE, and `libskiko` statically links Skia with ICU, HarfBuzz, libpng, expat, libjpeg-turbo, libwebp and zlib. **Since 2026-09-23 also the desktop app's sources:** mime4j, PDFBox and Commons ship their notices, but PDFBox bundles OFL-1.1 fonts, CC BY 4.0 Font Awesome shapes and Adobe-licensed data whose attribution must travel too, and neither metadata-extractor nor XMPCore ships a LICENSE at all | All of those require the notice to travel with the binary, and the shipping artifact supplies none of it. No copyleft or non-commercial obligation was found, so this is a compliance task, not a licence blocker. **Bundle a notices screen sourced upstream, and never patch Eigen — its MPL-2.0 is file-level copyleft.** Verify the Android component set separately; it is a different native build from the jar |
| 12 | **Laya's training data is only partly published, and the published part includes non-commercial sources.** The authors' own benchmark flags as "in training" `Tobi-Bueck/customer-support-tickets` (CC-BY-NC-4.0) and MS MARCO (Microsoft: non-commercial research only), plus LGPL-3.0, CC-BY-SA-3.0, `unknown` and undeclared sources; the full mix is not published. The tokenizer is Gemma 2's, whose Terms of Use may or may not reach it | Adopted for development on the owner's decision; **not cleared for shipping.** Close it by one of: the authors publishing a clean full mix, or confirming the NC sources are absent from the multilingual checkpoint; or training the head (or model) on data we can account for. Ask the authors first — it is the cheapest. Details in `LICENSING.md` |
| 13 | **On-device cost of Laya is unmeasured.** 384 MB INT8, 256k vocabulary; on a desktop M4 CPU a 1,024-token question took ~1.1 s (INT8, ORT), a short one ~45 ms. A phone CPU is slower. DJL's Android native AAR also lags its Java API (0.33.0 vs 0.38.0). Spec claims in `PRODUCT.md` §3 that rest on the old ~150M, 7–25 ms figure and are now unverified: the per-frame live capture gate, "tens of milliseconds is imperceptible" on arrival triage, a retroactive sweep "in minutes", the game deciding "many times a second" (**on a desktop M4 CPU the game now measures 10 decisions/s at ~62–66 ms P50, ~80 ms P95**, with ~106-token questions; a phone is still unmeasured). The desktop app's sweeps measured a median **73–99 ms per item** on the sample (a light machine) and **125–152 ms** with the machine's load average near 20 — desktop latency depends on what else is running, and a phone shares its CPU with everything | A1 measures it on a real mid-range device before anything depends on the number. Keep states short — latency scales with tokens, and most judgments do not need 1,024. If the Android AAR does not match, pin DJL to 0.33.0 or build the JNI library ourselves |
| 14 | **Composio integrations do not work offline.** A proposed Composio bridge (email, calendar, social media) runs through Composio's cloud and needs the network and third-party OAuth. It cannot work in airplane mode and sends data off the device, which conflicts with §1 and the never list in `PRODUCT.md`. | **Decide before integrating.** If built, it is an opt-in, clearly labelled online source, never a dependency of any judgment, watcher or core flow; everything must keep working with it off and the device offline, and the app must say when a result used online data. Not in the build order until the owner decides. |

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

---

## Where the build stands

As of 2026-09-23. Everything below was built on JVM Kotlin: no Android SDK, no device. Since
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
| A1 export half | **Proven on desktop** — Laya encoder + head as one ONNX graph, FP32 and INT8, parity recorded. Device latency not measured |
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
| F5 The game | **Desktop half built** — `:game` + `:game-desktop` (Compose Desktop). Runs the real Laya on this CPU at 10 decisions/s; untuned it loses to the baseline. Needs a phone and a fine-tune |
| §7 Measurement harness | Complete — group-wise splits, selective accuracy, coverage, ECE, Brier, baseline |
| Engine wiring | `DecisionEngine` composes the §8 architecture end to end |

### Blocked, and on what

Nothing below is deferred by choice; each needs something this environment does not have.

| Blocked | Needs |
|---|---|
| **A1** model runtime, fine-tune, latency | A real mid-range device — its acceptance criterion is a measurement on hardware. The weights and the ONNX export now exist (desktop), fine-tuning needs a labelled corpus and a GPU |
| **A2** the second backend | Qwen3-0.6B weights. Laya runs; the runtime and the interface exist |
| **B1–B9** every source | Android APIs: SAF, MediaStore, ML Kit, Gmail OAuth, calendar, contacts, notifications, WebView |
| **D5** actions, preview, undo | Android. The desktop app shows **preview only** and never changes a file; undo exists for corrections |
| **E1–E4** actuation | Android `AutofillService`, App Intents, accessibility, WebView |
| **F1** passive mode | WorkManager, charging and thermal constraints |
| **F2** on a phone | Android sources and WorkManager constraints (risk 3). Built on the desktop |
| **F3** overnight fine-tune | Model weights and a GPU |
| **F5** the game, on a phone | An Android build and a device. The desktop game runs (`:game-desktop`); the Compose UI is written to move |
| Real measurement | **A labelled fixture corpus.** Every number the harness produces today comes from synthetic fixtures; the machinery is proven, the numbers are not real. The Laya parity numbers measure agreement with upstream, not accuracy. The desktop app now *collects* labels — every correction is one — so the corpus can start with the owner's own files |

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
  corpus. Training-data provenance is open (risk 12); on-device cost is unmeasured (risk 13).
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

---
## Hand-off

State as of 2026-09-23, for whoever picks this up — including a session with no memory of how it
got here. Track-by-track status is in **Where the build stands** above; this is what to do next,
and what will bite.

### What changed most recently

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

- **Risk 12, Laya's training data.** Not cleared for shipping. The cheapest step is asking the
  authors for the multilingual checkpoint's full training mix. `LICENSING.md` has the evidence.
- **The Gemma 2 tokenizer question** — part of risk 12.

### Buildable here, right now

1. **Close risk 11** — now including DJL and the Rust crates in `libtokenizers`. A generator that
   extracts notices from the resolved artifacts into a bundled resource, plus a test that fails
   when they are missing or stale.
2. **A better INT8.** SmoothQuant-style rescaling or static per-channel activation calibration
   might let the 22 FP32 `mlp.Wo` matrices quantise too (~60 MB saved) without the damage the
   naive recipe did. The parity harness already measures it.
3. ~~**Surface token truncation.**~~ Done 2026-09-23 — `Scored`, `Truncation` on `LedgerRow`; see
   the Progress log.
4. **A fixture corpus**, and **hardening** (property tests for calibration and off-policy maths).
   The desktop app now produces labels: every correction is one, keyed by item and criteria hash,
   exported losslessly. The owner's own files are the cheapest corpus there is.
5. **Put the criteria in front of the model.** The three-part criteria are shown and exported but
   Laya reads only the question and the options. Upstream's descriptive-option criteria
   (`{label: description}` in `build_sequence`) are the channel; it needs a `LayaPrompt` extension and
   a parity case, then a measurement on corrected items of whether it helps.
6. **Fine-tune on corrections** (A1/F3). The sample result says the untuned model does not beat
   keywords; whether a head fine-tuned on a few hundred corrections does is the next real question.
7. ~~**Mark mechanical rows in the ledger.**~~ Done 2026-09-23 — `LedgerRow.resolvedBy`; see the
   Progress log.

### Blocked, and on precisely what

| Blocked | Needs |
|---|---|
| A1 on-device latency | A real mid-range device — the export and runtime path exist |
| A1/F3 fine-tuning | A labelled corpus and a GPU |
| A2's second backend | Qwen3-0.6B weights and its own export |
| B1–B9 every source | Android APIs: SAF, MediaStore, ML Kit, Gmail OAuth, contacts, notifications |
| D5, E1–E4, F1 | Android UI, autofill, accessibility, WorkManager |
| F5 on a phone | An Android module and a device; the game logic (`:game`) needs no change |
| F2 on a phone | Android sources, WorkManager (built on the desktop) |
| Any real measurement | A labelled corpus **and** a model — the model half now exists |

### Traps — each of these was hit or narrowly avoided

- **Bare yes/no options make Laya ignore the question.** On the sample, "is this a receipt?" and
  "is this phishing?" got near-identical yes/no answers. Give a two-option judgment two options that
  say what they mean (`Shape.Binary`); measure any change on corrected items.
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
