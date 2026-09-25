# Plan: fine-tuning Laya to fly Riverflight

Status: draft, 2026-09-25. Plan only: no code has changed. Implementation waits until the motion-aware
collision words (`Prediction`, `PathText.describe`) that are being added now have landed and been
measured, because they are part of the model's input.

## 0. Where we start

| Fact | Value | Source |
|---|---|---|
| Question | "Which way is safest?" over `left` / `straight` / `right`, each described by `PathText` (PathAhead words, soon with collision prediction) | `Pilots.kt`, `Observation.kt` |
| Test seeds 1–20, progressive, 90 s, 4-tick charged delay, safety net on | Laya **495** mean rows, baseline **543**, gates-only (no model) **418** | BUILD.md 2026-09-25 |
| Dev seeds 101–112 | Laya 563, hold-course 545, baseline 521: the order flips, so **20 seeds cannot resolve a gap of about 50 rows** | same |
| Mirror probe | 43% (baseline 100%) | same |
| Word bias | divided out by content-free passes (2 extra passes per option set, cached) | `ModelPilot.prior` |
| Latency | P50 21 ms / P95 26 ms on an M4 CPU, 29 tokens mean, 44 max. **Not measured on an iPhone.** | same |
| Decision cadence (progressive) | L1 6 ticks (100 ms), L2 5 (83 ms), L3 4 (67 ms), **L4–5 3 (50 ms)**, **L6+ 2 (33 ms)** | `Difficulty.decisionInterval` |
| The model | `convaiinnovations/laya-multilingual`: mmBERT-base encoder (22 layers, 768 wide, 110.3M layer params + 196.6M embedding) plus a decision head (2 transformer layers 14.2M, scorer 0.6M, type embedding, act head 0.2M): **15.0M head parameters out of 321.9M** | `model.safetensors` header |
| How Laya was trained | Upstream (Convai), not by us: head trained from scratch with RLCD, 15,987 updates, 4.97 h, 1 GPU (`rl_agent_config.json`). **No training code exists in this repo, in Loupe Station or in the `laya` pip package.** Only the forward pass exists, and it has a `detach_encoder` flag (frozen-encoder head training) | `laya/common.py` `DecisionModel` |
| Phone files | One fused INT8 graph `laya-multilingual-choice.int8.onnx` (383.9 MB, qtype fixed to choice, the 22 `mlp.Wo` kept FP32) + `tokenizer.json` (34.4 MB), on Supabase at `models/laya/v1/`, pinned in `models.json` and `LayaModelStore` | `tools/export-laya-onnx.py` |
| Station files | Its own export (`tools/export_laya_onnx.py`): full contract (qtype, marker_mask), graph + `.onnx.data`, fp32/fp16/int8, fp16 default, pinned in `laya_studio/onnx_models.json` | `~/laya-studio` |
| Shared identity | The phone pins `laya-multilingual@052592a1` and Station pins `laya@1c5edc17/multilingual`, but both resolve to the **same `model.safetensors` (SHA-256 `9d628fd9…`) and `tokenizer.json` (`609d8f4c…`)**. Parity means this weights hash, not the ONNX bytes. | HF cache, both export scripts |
| Ledger | Rows carry `criteriaHash` (the wording) but **no model id**. Calibration cannot tell that the model changed. | `engine/…/Ledger.kt` |

## 1. Goal and success bar

"Laya flies well" means all of the following. Checkpoints are chosen on the dev seeds, and the test
seeds are used once, at the end.

| # | Bar | Number |
|---|---|---|
| G1 | Beats the baseline on **test seeds 1–20** (progressive, 90 s, 4-tick delay, safety net on) | Mean rows ≥ **600** (baseline 543). The seed-paired bootstrap 95% CI of (Laya − baseline) has its lower bound > 0. Ahead on ≥ 12 of 20 seeds. |
| G2 | Holds on a fresh block, run once | Seeds **1001–1100**, same settings: Laya − baseline > 0 with the CI above 0. This is needed because 20 seeds flipped order between dev and test last time. |
| G3 | Flies by reading, not by the net | Safety-net ticks per 100 rows ≤ baseline's (29.4). Mirror probe ≥ 90%. Flies a predicted hit when a clear way was offered less often than baseline+sensor does. |
| G4 | Laya's other jobs do not regress | Base graph answers **identical** to v1 INT8: golden 34/34 and criteria 8/8, the same argmax and max \|Δp\| = 0 on the JVM. The 42 Diagnostics questions on the device agree with the JVM as before. |
| G5 | Fits the cadence on a phone | The whole decision (observation with prediction, encoder, head) on the owner's iPhone has **P95 ≤ 50 ms** (the L4–5 interval), with a 33 ms P50 as the goal for L6+. The measurement is then re-run with the charged delay set to the device P50 in ticks. |
| G6 | Honest comparison | The same table also shows **baseline+sensor** (the rules pilot given the same collision words) and the **teacher**, so a win is visibly Laya's and not the words'. |

## 2. The core decision: one shared model or a game head

| Option | What changes | Other jobs | Download | Phone runtime | Station |
|---|---|---|---|---|---|
| A. Fine-tune the one shared Laya | All 322M weights | Can forget. Every judgment's answers move, so golden/criteria/Diagnostics fixtures and the Station harness all need re-baselining. Every judgment's calibration restarts, and **the ledger has no model id**, so it would first need one or old and new rows would mix silently | 384 MB again for everyone | One graph, as today | Must re-export fp32/fp16/int8, re-pin, re-measure and restart calibrations on the same day |
| B. A second full model for the game | A copy of all weights | None | +384 MB | Two 384 MB graphs in memory | None |
| **C. Game head on the frozen shared encoder (recommended)** | 15M new head weights. Encoder and base head untouched | **None by construction**: same weights, same logits | One-time graph re-export (below) + **~15 MB INT8 / ~60 MB FP32** head | Encoder once + a small head graph. Adds about 11% compute in the worst case | None required. Its answers stay identical |
| D. A tiny non-Laya model (an MLP on PathAhead features) | Everything | None | < 1 MB | < 1 ms | None |

**Recommendation: C.** Why:

- It cannot regress G4, because the shared weights (`9d628fd9…`) do not change, so no calibration restarts on the phone or in Station.
- The input is short and nearly categorical (three phrases). A task head with two full transformer layers over the encoder's token states has the capacity for it.
- It matches Station's own training plan (`2026-09-25-model-training-plan.md`, rung 2 "heads on frozen Laya", with per-question model choice). The same trainer could serve both.
- It keeps a ladder. If head-only stalls, unfreeze the top 2 encoder layers (about +10M parameters, a game file of about 25 MB INT8), still without touching the shared file (see the v2 graph below).
- D is rejected on product grounds: the game exists to show Laya deciding. It stays as a diagnostic (§3, "ceiling").

**The one real cost of C.** The v1 graph does not expose the encoder's output. The v2 graph is **the
v1 INT8 file with extra graph outputs added** (the final hidden states, plus the hidden states after
encoder layer 20 so a later top-2 head needs no further re-download). This is a graph edit with
`onnx`: the weights, nodes and existing outputs are byte-identical, so the answers are provably
identical, and G4 checks it anyway. Its SHA-256 still changes, so it is a new file under `laya/v2/`.
The same edit applies to `int8-partial`.

## 3. Labels

### Teacher: search in the simulator, not imitation

| Source | Ceiling | Cost | Verdict |
|---|---|---|---|
| Imitate `BaselinePilot` | The baseline itself (543). Laya would learn its blind spots, e.g. it cannot see crossing boats | Free | Only as warm-up data, never as the target |
| **Rollout teacher** | Whatever the search finds | CPU only, deterministic | **Use** |

**Teacher, concretely.** For a state `s` where the gates leave 2–3 ways:

1. For each offered way `a` (with the gun as `ModelPilot.gates` sets it), copy the world
   (`World.copy()`). Fly `a` exactly as the game would: charged delay 4 ticks, held for the level's
   interval, safety override on.
2. Continue for **H = 360 ticks (6 s)** with a continuation policy π, taking the best of K cheap
   policies: {baseline, baseline+sensor, gates-only hold}. Optionally, one more level of search over
   the next decision's ways first. The max over policies is a sound lower bound on the value of `a`.
3. Score `Q(s,a) = 1000·alive(H) + ticksSurvived + 2·fuel%(H) + 0.1·score(H) − 5·overrideTicks`.
   Distance is not a separate term, because the river scrolls at a fixed speed and ticks survived
   already measure distance. The fuel term covers what lies beyond the horizon. The override
   penalty rewards reading over leaning on the net.
4. Target: the set B of ways within ε = 5 of max Q. The label is uniform over B. If B is every way
   (a "tie" state), keep it at weight 0.2 with a uniform target (it teaches "no preference"). Keep
   decisive states at weight 1.

This needs one new piece of code: a `Rollout` helper that runs `GameSession`'s tick loop (delay,
interval, override) over a `World.copy()`, because `GameSession` only starts from a seed. A test
checks it against `GameSession` on the same actions.

**First check the teacher is worth it (go/no-go).** Fly the teacher as a pilot on the dev seeds. If
it does not beat the baseline by a wide margin (≥ 100 rows), distilling it cannot give G1: stop and
report. **Be honest about what this means:** the teacher, or baseline+sensor, is itself a shippable
rules pilot that may beat any distilled Laya. A fine-tuned Laya is Laya learning to imitate a search.

**Ceiling from the words.** The option texts are discrete. Count the distinct (left, straight,
right, fuel-scene) text tuples, and fit a lookup table or small MLP from tuple to teacher label. Its
held-out agreement is the most **any** model reading these words can reach. If it is low, the words
drop what the teacher uses (e.g. a channel beyond 10 rows, or fuel far away), and the fix is the
words, not the model. Report it beside Laya's agreement.

**Cost, stated honestly.** "Millions of states for free" is true in money and not in time. The
teacher costs about 3 ways × K 3 × 360 ticks ≈ 3,200 world steps per state, plus the override's
per-tick look-ahead inside each rollout (up to 6 × 20 steps a tick) and `Prediction` (≈ 540 steps
per observation). That is roughly 50k–200k steps per labelled state. The step cost is unmeasured:
**M0 measures it.** At an assumed 2–5 µs a step, this is 0.1–1 s per state per core, or about
40k–400k states an hour on the M4's 10 cores. A few hundred thousand states is one night. Millions
would need a rented CPU box or a cheaper override inside rollouts.

### State distribution (DAgger)

| Round | States from | Labelled by |
|---|---|---|
| 0 | Train seeds flown by baseline, baseline+sensor, gates-only, **current Laya**, and the teacher, each with ε = 0.15 random offered ways, so recovery states appear | Teacher |
| 1–3 | Train seeds flown by the **latest game head** (JVM, ONNX), with a β-mix of teacher control of 0.5, then 0.25, then 0 | Teacher |

All rounds are aggregated. Stop when dev-seed rows stop improving. Only states where the model would
actually be asked (≥ 2 ways after the gates) are kept.

### Splits, levels, balance

| Split | Seeds | Use |
|---|---|---|
| Train | 10,000–19,999 | Labels (about 500 seeds a round is enough) |
| Dev | 101–112 (already used for wording) + 200–299 | Early stopping, checkpoint and hyperparameter choice |
| Test | **1–20** | Once, for G1. Never trained on or selected on |
| Confirmation | 1001–1100 | Once, for G2 |

- **Levels.** The mean level reached is about 3, so L4–9 states are rare but decide the endgame. Oversample them with `Difficulty.rush` (starts at L4) runs, targeting at least 30% of states from L4 or higher. Report accuracy by level.
- **Class balance.** Do not reweight by label, because that would push the model off `straight`. Instead, (1) mirror augmentation (swap the left and right descriptions and labels, keeping the served option order), which makes left and right exactly balanced and targets the 43% mirror probe, and (2) weight by decisiveness. Report per-label recall and the confusion matrix.
- **Volume target.** About 200k decisive plus 100k tie states after the rounds, ×2 with mirroring.

## 4. Input format: train exactly what is served

- The same `Judgment.Choice`: `ModelPilot.QUESTION`, labels from `ModelPilot.way`, descriptions from `PathText.describe` (with the collision prediction), scene from `PathText.scene`, built by `ModelPilot.judgment`.
- **Tokens come from Kotlin, not Python.** The data exporter (JVM) runs `LayaPrompt.build` and writes `input_ids` and `marker_pos` to JSONL, with the rendered text alongside for inspection. The trainer never re-tokenises, so there is no Python-side tokenizer drift.
- **Encoder states come from the served INT8 graph.** The encoder is frozen, so its hidden states are computed once with ONNX Runtime on the **v2 INT8 graph** (the exact serving bytes) and cached to disk (fp16, about 50 tokens × 768 × 2 B ≈ 77 KB a state, about 45 GB for 600k states, on the external disk). The head is then trained on exactly what the phone will feed it, which removes the FP32-to-INT8 train/serve gap for the encoder.
- **Length budget.** Today 29 tokens mean and 44 max. The collision words will add some (to be measured in M0). `LayaPilotTest` already asserts that nothing is cut, with ≤ 200 tokens a sequence and ≤ 120 of state. The 256-token head budget is far away. Keep those asserts.
- **Freeze the words.** Record `PathTextTest`'s golden digest in the head's metadata. A test fails if the words change without a new head. Any wording change means relabel and retrain.
- **Word-bias division.** A trained head should not need it. Ablate it (§6). If it is dropped, the two content-free passes go too, and the bars show the head's raw share, labelled "Laya's answer, trained for this game; not calibrated".

## 5. Training

| Item | Plan |
|---|---|
| Where | **The owner's M4 Mac (16 GB, MPS) for head-only.** Caching encoder states: about 15–40 min per 300k states (ORT CPU, batched). Training the head: about 10–20 min per run on MPS (15M parameters). Estimates, measured in M0. A rented GPU is needed only for the ladder's full fine-tune (322M, AdamW ≈ 5 GB of state, about 6 h a run on the Mac if it fits at all). One A100 or H100 at about $2–3/h does it in under 1 h, so **< $50 total**. The game data holds nothing private. |
| Base checkpoint | `laya-multilingual` at `052592a1` (safetensors `9d628fd9…`). **The head is initialised from the base head**, so round 0 starts exactly at today's pilot. |
| Head-only hyperparameters (start) | AdamW lr 1e-4 (sweep 3e-5 / 1e-4 / 3e-4), wd 0.01, batch 256, 3 epochs, 5% warmup then cosine, dropout 0.1, gradient clip 1.0, FP32. Loss: soft-target cross-entropy to the teacher's B-set target, times the state weight. Choose by dev-state loss, then by **dev-seed flying**. |
| Ladder, if head-only stalls on dev | (1) Unfreeze encoder layers 21–22, lr 2e-5, fed by the v2 graph's layer-20 output: no new shared file. (2) Only then a full fine-tune, with a KL-to-base regulariser on judgment prompts. That is option A, and it needs the owner's sign-off (§8). |
| Trainer | New `tools/game-head/` in this repo (Python, pinned requirements beside `requirements-laya.txt`). It loads `DecisionModel` from the `laya` package at the pinned commit, so the head's forward pass is upstream's code. |
| Export | The head as its own ONNX graph: inputs `hidden [1, seq, 768]`, `attention_mask`, `marker_pos`; output `logits`. qtype is fixed to choice inside, as today. FP32, then INT8 with the existing recipe (ORT dynamic, per-channel). |
| Check after quantising | On held-out dev states: INT8 head vs FP32 head argmax agreement ≥ 99.5% on decisive states, and dev-seed rows within noise. If not, ship the FP32 head (60 MB). Also evaluate on `int8-partial`'s encoder states, since the head was trained on `int8`. |
| Parity | (a) JVM ↔ PyTorch reference on 200 dev states: the same argmax and max \|Δp\| ≤ 1e-3 (FP32 head). (b) JVM ↔ iOS: add a **game set of 30 states** to Diagnostics, next to the unchanged 42, with the same agreement rule. (c) Station: the base answers are identical by construction. The game head's safetensors, the reference script and the 30-state fixture are published so Station can verify the head if it ever loads it. |

## 6. Evaluation harness

Reuse `PilotMeasurementTest` / `Measure` (seeds, progressive, 90 s, 4-tick delay, threads). Add:

- a `game-head` variant (the new two-graph backend behind the same `Backend` interface, so `ModelPilot` itself does not change);
- a `teacher` variant;
- seed-paired bootstrap CIs.

**Table columns:** pilot · rows mean (median) · Δ vs baseline [95% CI] · seeds ahead/behind/tie ·
alive at 90 s · deaths (fuel/enemy/bank/bridge) · score · level · depots shot · refuel ticks ·
safety-net ticks per 100 rows · flew a predicted hit when a clear way was offered (%) ·
mirror-consistent (%) · teacher agreement on held-out states (%) · top-2 margin · latency P50/P95
on the M4 · latency P50/P95 on the iPhone.

**Rows:** baseline · baseline+sensor · gates-only · teacher (upper bound) · Laya v1 head (today) · Laya game head.

**Ablations** (dev seeds, except the final line on test):

| Ablation | Question it answers |
|---|---|
| Rollout teacher vs baseline imitation | Is the search worth it? |
| DAgger rounds 0 / 1 / 2 / 3 | Does on-policy data matter? |
| Mirror augmentation on / off | Does it fix the mirror probe? |
| Word-bias division on / off | Is it still needed? |
| Collision words on / off (PathAhead only) | Is the gain the words or the model? |
| Head-only vs top-2 layers | Is the frozen encoder enough? |
| INT8 vs FP32 head; `int8` vs `int8-partial` encoder | Does quantisation cost answers? |
| Safety net off | Does Laya fly by itself? |
| Words-only lookup/MLP ceiling | What is the most any model could get from these words? |

## 7. Shipping

| Item | Plan |
|---|---|
| New files | `laya-multilingual-choice.int8.v2.onnx` (v1 + hidden outputs, ≈ 384 MB). The same for `int8-partial` (≈ 357 MB). `laya-game-head-v1.int8.onnx` (≈ 15 MB, or 60 MB FP32). `tokenizer.json` is unchanged (same SHA). |
| Host | Supabase `models/laya/v2/` (uploaded by the owner). **`laya/v1/` is never overwritten or deleted**: rollback depends on it. |
| Pins | `models.json` (urlTemplate → `laya/v2/`, sizes, SHA-256s, a `game-head` file in each variant) **and** `LayaModelStore.VARIANTS`/`FILES`, in the same commit. `ModelDeliveryTests` already checks that the two agree, and its URL test moves to `laya/v2/`. |
| Code (after the other agent lands) | JVM `OnnxBackend` and iOS `OrtLayaBackend`: an optional second session for the head, and a `LayaGameBackend` implementing `Backend`. `GameSessions.modelDecider*` takes it. Judgments keep using the base logits from the same graph. |
| What an existing user sees | A consent screen for **one re-download of ≈ 384 MB + ≈ 15 MB**, over Wi-Fi by default. Disk precheck: the v1 files stay until v2 verifies, so about 400 MB extra while it downloads. **Until v2 is installed, the app keeps accepting the v1 pins**: judgments run on v1 and the game flies the v1 head (today's pilot). Nothing stops working. After v2 verifies, v1 is deleted. A binary delta (v1 → v2 differs only in the graph's output list) could cut this to about 15 MB. It is a separate 2–3 day item, worth it after launch. |
| Calibration | No judgment calibration restarts (identical logits). The game has none fitted. Its content-free priors are per process and recomputed or dropped. The results card and Watch copy name the head ("trained for this game"). |
| Model settings | Add `features.game.head`: `game` (default) \| `base`, documented in MODEL-SETTINGS.md as a phone-only key. This is also the instant user-side fallback. |
| Station | Nothing to change for its questions: its pins (`onnx_models.json`, revision `1c5edc17`) stay. Steps: (1) message the Station session with the v2 facts: same safetensors `9d628fd9…`, an extra graph output, a phone-only head file; (2) publish the head's safetensors, reference script and 30-state fixture under `laya/v2/`; (3) if Station later loads the head, it pins the same SHA. **If option A were ever chosen**, Station re-exports all three variants, re-pins, re-runs `tests/onnx_eval.py` and its measure snapshot, and restarts calibrations on the same day as the phone. |
| Rollback | (1) Runtime: `features.game.head = base`, or automatically if the head is missing or fails SHA. (2) Release: an app update that pins v1 again. v1 is still hosted, so no re-upload is needed. |
| Licence | The head is our weights trained on our simulator. The encoder stays Apache-2.0 upstream. Record it in LICENSING.md (the A0 standing rule: re-verify the pinned revision). |

## 8. Milestones, risks, open questions

| M | Work | Effort | Exit |
|---|---|---|---|
| M0 | Freeze splits. Measure world-step cost and token lengths with the collision words. `Rollout` helper plus an equivalence test. Teacher and baseline+sensor as pilots on dev. Words-only ceiling | 2 d | **Go/no-go:** the teacher beats the baseline by ≥ 100 rows on dev, and the ceiling is well above Laya's current agreement |
| M1 | JVM exporter (states → ids, markers, text, Q values). Mirror augmentation. Round-0 data | 2–3 d | ≥ 200k labelled states, label stats reported |
| M2 | v2 graph (added outputs), head export, JVM two-graph backend, G4 identity check | 2–3 d | Golden 34/34 and criteria 8/8 identical to v1 |
| M3 | Encoder-state cache, head training, DAgger rounds 1–3 | 3–5 d (mostly compute) | Dev seeds clearly above the baseline |
| M4 | Test seeds 1–20 once, confirmation 1001–1100 once, ablations, BUILD.md entry | 1–2 d | G1–G3 and G6 pass or fail, recorded either way |
| M5 | iOS: pins, manifest, v1 fallback, two-session ORT, Diagnostics game set, settings key | 3–4 d | Simulator green, then device latency (G5) on the owner's iPhone |
| M6 | Owner uploads `laya/v2/`, release, Station message | ½ d + owner | The installed v2 verifies on a device |

About 3 weeks of agent time, plus owner steps (upload, device runs). Each milestone goes through the
review/test workflow before it is committed.

**Risks**

| Risk | Mitigation |
|---|---|
| The teacher barely beats the baseline (the gates and the net already fly most of the game: gates-only 418) | M0 go/no-go. Stop early and report |
| The words lack what the teacher uses | The words-only ceiling shows it. Fix the words (a new wording means a new head) |
| 20-seed noise decides the verdict | The G2 confirmation block and paired CIs |
| The words change after training (the other agent, or later) | The digest pin in the head's metadata, and a failing test |
| Device latency above 50 ms (plus the prediction's sim cost on the sim thread) | G5 measured before release. The existing `max_decisions_per_s` cap. The head adds ≤ 11% compute |
| Head trained on `int8` states, flown on `int8-partial` | Evaluate both. If they differ, train on a mix |
| 384 MB re-download annoys users | Build the delta patch before launch (owner question 2) |

**Open questions for the owner** (only the ones that are yours to decide)

1. **Is a game head "Laya flying"?** Laya's encoder plus a head trained for the game, with the shared model untouched. Or must the one shared model itself be fine-tuned (option A: every judgment's calibration restarts, and Station re-exports the same day)?
2. **Re-download:** accept one ≈ 400 MB re-download for current installs, or build the ~15 MB delta update first (+2–3 days)?
3. **Which comparison the game shows:** "beats the baseline" only, or also baseline+sensor and the teacher? Those are rules pilots that may beat a distilled Laya, and showing them is the honest version.
4. **Rented GPU:** allowed, with a cap of $50, only if head-only fails? No private data is involved.
5. **Station:** should Loupe Station load the game head too (e.g. in the Playground), or is it phone-only?
