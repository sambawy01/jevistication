# Jevistication — Build Plan

Single source of truth. Every decision from the design discussion is folded in here.
If something isn't in this document, it is not in scope. If it is here and unchecked, it is
still owed.

Companion: `docs/ARCHITECTURE.md` (the why). This document is the what and the when.
Upstream work we build on is catalogued in §11.

---

## 0. Locked decisions

| # | Decision | Value | Rationale |
|---|---|---|---|
| D-01 | Language | **TypeScript / Node ≥ 20.12** | Decision SDKs are TS-native and infer answer types from the question schema, so a pack's questions produce compile-time-typed answers. Type safety *is* the product. |
| D-02 | Distribution | **`npm i -g jevistication`**, binary `jev` | No collision with `jev-router` (`jev-claude`/`jev-codex`/`jev-explain`). Also ships as a Claude Code plugin (§11.5) and a jev-skill catalog entry. |
| D-03 | Repo | **`sambawy01/jevistication`** | Clean history. Fleet Router v1 archived, not rewritten. |
| D-04 | Name | **Jevistication** | Settled. |
| D-05 | Version | **0.1.0 → 1.0.0 at M6** | 1.0.0 is earned when the pack API goes public and its schema is committed to. |
| D-06 | Ensemble | **Deleted** | Gain ∝ error *decorrelation*, not model count. Self-consistency on one model is correlated noise. |
| D-07 | Verification | **Kept, re-roled** | The safety net that makes routing *down* risk-free: worst case becomes a retry, not a bad answer. |
| D-08 | Mechanical > judgment | **Always** | Where success is mechanically checkable, run the check and ignore the model. This is also why test selection is dead (§8). |
| D-09 | Backend | **Backend-agnostic, local-capable** | *Revised.* Not hosted-only. A `DecisionBackend` interface with hosted and local implementations (§11.2–11.3). "No vendor lock-in" becomes true rather than aspirational, and egress stops being forced. |
| D-10 | Failure posture | **Per-pack: advisory fails open, destructive fails loud** | *Revised.* A routing pack degrades to the default tier. A pack that **deletes** state (compaction) or **permits** an action (tool gate) must never silently guess — it throws and the caller decides. Upstream compaction work (§11.5) fails loud deliberately; that is correct and our original blanket fail-open was wrong. |
| D-11 | Sequencing | **Local-first, CI packs later** | Local packs ship sooner; CI packs have better ground truth. |
| D-12 | Ported from v1 | `stats.py`, `calibrate.py`, `bandit.py` | Proven and expensive to re-derive. Ported behaviour-for-behaviour with tests translated first. Provider code is **not** ported — see D-14. |
| D-13 | Decision backends | **Jev (hosted) · NanoJev (local, any) · Laya-MLX (local, Apple Silicon)** | Three real implementations, none dominant (§11.2–11.4). Which backend suits which pack is an empirical question the ledger answers. |
| D-14 | LLM providers | **Vercel AI SDK** | 25+ providers, unified TS interface, normalized tool calling. Deletes `src/providers/` entirely and retires the plan's former highest risk. |
| D-15 | Recalibration | **Mandatory, per pack** | Raw confidence is measurably overconfident and task-dependent (§11.1). No threshold in `decide.ts` ever reads a raw probability. |
| D-16 | Propensity logging | **From the first decision** | Unbiased counterfactual replay requires logged propensities. A deterministic log cannot be replayed without bias, and this is unrecoverable retroactively. |

---

## 1. The product in one page

**Positioning.** Not another guardrail. **The measurement layer for agent policy.**

Every tool in this space ships deterministic rules — 39 built-in policies, deny-by-default
allowlists, regex gates. None of them can tell you their own false-positive rate. Meanwhile the
decision models everyone is thresholding on are **measurably overconfident**: independent
evaluation found a model claiming 0.98 confidence being right 89% of the time, with accuracy
ranging 55%–100% across tasks and *no universal threshold* (§11.1).

> **The model says 0.98. It's right 89% of the time. Jevistication is the layer that knows the
> difference.**

**The engine.** One loop, written once:

```
                  ┌──────────────── learn ────────────────┐
                  ↓                                       │
state → [Perceive] → [Recalibrate] → [Decide] → [Execute] → [Verify] → [Settle] → [Ledger]
        backend       per-pack        pure fn    adapter     mechanical  ship /    state, questions,
        7–500ms       corrector       total                  else model  escalate  distribution,
                                                                         / abstain propensity, outcome
```

**Decision Packs.** Every feature is a pack, not a code path:

```
Pack = state schema + typed questions + pure policy fn + outcome signal + fixtures
```

No outcome signal ⇒ not a pack.

**Three surfaces**, because a proxy only sees LLM traffic:

| Surface | Sees | Packs it can host |
|---|---|---|
| Proxy | LLM requests/responses | model routing |
| Hooks (`PreToolUse`/`PostToolUse`/`PreCompact`) | tool calls before they run | tool gate, compaction, result triage |
| Library / CLI | whatever you hand it | policy audit, everything else, user packs |

---

## 2. Module layout

```
src/
  engine/
    types.ts          PerceptionVector, Decision, Outcome, PackSpec
    perceive.ts       one backend call → typed answers
    recalibrate.ts    D-15 — per-pack isotonic/Platt corrector
    decide.ts         pure policy runner
    verify.ts         mechanical-first dispatcher
    settle.ts         ship / escalate / abstain
    ledger.ts         D1 — append-only log incl. propensities (D-16)
    replay.ts         D2 — off-policy evaluation (IPS / SNIPS / DR)
    calibrate.ts      threshold + corrector fitting
    stats.ts          McNemar, bootstrap CI, Wilson, ECE, Brier, NLL
    bandit.ts         Thompson — learns thresholds AND supplies propensities
  backends/
    base.ts           DecisionBackend interface
    jev.ts  nanojev.ts  laya.ts
    fidelity.test.ts  adapter ≡ reference on shared fixtures
  packs/
    registry.ts
    policy-audit/     flagship
    model-routing/  tool-gate/  another-pass/  compaction/  ...
  surfaces/
    proxy/  hooks/  cli/  lib.ts
  config/
```

There is no `src/providers/`. LLM calls go through the Vercel AI SDK (D-14).

---

## 3. Core contracts

### 3.1 PackSpec

```ts
interface PackSpec<Q extends Questions> {
  name: string;
  surface: "proxy" | "hook" | "cli" | "lib";
  trigger: string;
  failure: "open" | "loud";                       // D-10, per pack
  buildState: (ctx: unknown) => EntryType;
  questions: Q;
  decide: (a: Calibrated<Q>, c: PolicyContext) => Decision;  // PURE + TOTAL
  outcome: OutcomeSource;                         // REQUIRED — no outcome, no pack
  fixtures: string;                               // train / dev / calibration / test / OOD
  schemaVersion: number;
}
```

Three rules:

- **`decide` is pure and total.** No I/O, clock or randomness. This is what makes replay possible.
- **`decide` receives `Calibrated<Q>`, never raw answers.** The type system enforces D-15.
- **`outcome` is required.** A pack that cannot learn whether it was right does not ship.

### 3.2 Perception and backend limits

One backend call per decision point, all questions answered in parallel against one state.
Choice criteria for model selection are generated from the **live configured catalog**, never a
hardcoded enum.

Hard constraints discovered upstream (§11.5): **hosted requests cap at 32k tokens**. State plus
questions must be fitted below that, and question sets split across concurrent requests with the
same state resent and answers merged. Token estimation without a tokenizer is adequate if
calibrated to land slightly *above* reported counts.

### 3.3 Recalibration

Between `perceive` and `decide`. Per pack, fitted on the dedicated **calibration split** — never
on test. Reliability bins, ECE, Brier and NLL are reported per pack; a pack ships its numbers or
it does not ship. Backends may apply their own global temperature scaling; that does not address
per-task variance, so our corrector stacks on top rather than replacing it.

---

## 4. Milestones

Independently shippable, `main` green, binary acceptance criteria.

### M0 — Skeleton ⬜
- [ ] TS toolchain: `tsconfig`, ESM + CJS + `.d.ts`, `node --test`
- [ ] `package.json` with `bin`, `files`, `exports`
- [ ] CI on Node 20 + 22; `LICENSE`, `README`, `CONTRIBUTING`
- **Accept:** `npm pack` produces an installable tarball; CI green.

### M1 — Engine core + propensity logging ⬜
- [ ] `types.ts`, `backends/base.ts`
- [ ] `perceive.ts` with the Jev backend; 32k request fitting and splitting
- [ ] `ledger.ts` — append-only JSONL **including propensities** (D-16)
- [ ] `bandit.ts` moved forward from M3: it is the propensity source, not a nicety
- [ ] `decide.ts` — pure runner, property tests for totality
- **Accept:** a trivial pack writes a ledger row carrying a propensity; `decide` survives fuzzed
  malformed input without throwing.

> D-16 is why the bandit moved to M1. Logging deterministically for months and *then* adding
> replay produces biased estimates with no way to repair the history.

### M2 — Flagship: the policy audit ⬜
- [ ] Ingest existing agent logs (Claude Code transcripts, third-party policy traces)
- [ ] Per-rule firing rates, estimated false-positive rates, confidence intervals
- [ ] `jev audit` renders a receipt
- **Accept:** run against a real repo's agent history and produce a shareable report naming how
  often existing rules fired and how often they were overridden.

> Works on day one against *other people's* tools, before we ship a policy of our own. This is
> the launch artifact.

### M3 — Recalibration + calibration harness ⬜
- [ ] `stats.ts` — McNemar exact, percentile bootstrap, Wilson, reliability bins, ECE, Brier, NLL
- [ ] `recalibrate.ts` — isotonic/Platt per pack
- [ ] Fixture splits: train / dev / **calibration** / test / OOD
- [ ] Protocol discipline: seeded sampling blind to labels, frozen samples + code hash, no
      retries, errors counted as wrong, receipts never overwritten, aborted runs never reported
- **Accept:** a pack reports its own accuracy with CIs, and its corrected probabilities beat raw
  ones on ECE on a held-out split.

### M4 — Counterfactual replay ⬜
- [ ] `replay.ts` — IPS, SNIPS, doubly robust over logged propensities
- [ ] `jev replay --policy <ref>` reports deltas with CIs
- **Accept:** a threshold change is evaluated against ≥1k logged decisions *before* shipping:
  "would have changed N decisions, M of them wrongly," with intervals.

### M5 — Local backends ⬜
- [ ] `backends/nanojev.ts`, `backends/laya.ts`
- [ ] Adapter gaps: `noul`↔`boolean` naming, synthesized `confidence` and Score `legend`,
      canonical JSON state serialization
- [ ] Fidelity fixture suite: adapter ≡ reference in FP32 and FP16, repeated calls, no memory growth
- [ ] Per-pack backend selection driven by measured accuracy/latency/cost
- **Accept:** the same pack runs on all three backends over one fixture set, and the harness
  emits a table of accuracy, latency and cost per backend.

> Local backends measure 7–13 ms P50 versus 70–500 ms hosted. Hot-loop packs are only viable
> locally, so local is the **default** for gate-style packs and hosted is the fallback.

### M6 — Hooks surface + hot-loop packs ⬜
- [ ] `PreToolUse` / `PostToolUse` / `PreCompact` entrypoints; ship as a Claude Code plugin
- [ ] **Tool gate** — `reversible`, `blast_radius`, `in_scope`, `exfiltration`,
      `disposition: auto|confirm|block`. Outcome = user override. `failure: "loud"`.
- [ ] **Compaction** — adopt the never-summarize-only-delete design (§11.5). `failure: "loud"`.
- [ ] **Another-pass-worth-it** — kills the unconditional critique/revise call
- [ ] README: the gate is **defense in depth, not a security boundary**
- **Accept:** tool gate runs under a real session at p50 < 30 ms added latency on a local backend.

### M7 — Open the pack registry ⬜
- [ ] Pack authoring API, `jev pack init`, `jev pack test`
- [ ] CLI complete: `audit`, `route`, `explain`, `replay`, `calibrate`, `ledger`, `pack`
- **Accept:** a third party writes and calibrates a pack without reading our source.

### M8 — Model routing ⬜
- [ ] Proxy surface via the Vercel AI SDK; sentinel routing; closed loop with escalation
- **Accept:** a full agentic session runs through the proxy with tools working; routing decisions
  and outcomes land in the ledger.

> Demoted from flagship. The space is crowded (§11.6); we support it, we do not lead with it.

### M9 — Train a local backend from the ledger ⬜
- [ ] Export ledger → decision-question dataset with outcome labels
- [ ] Fine-tune a small local backend on a user's own decisions
- **Accept:** a model fine-tuned on ≥10k logged decisions beats the stock local backend on that
  user's held-out split.

> The compounding story, and uncopyable without D1 + D-16 logging from day one.

### M10 — Remaining packs ⬜
Failure novelty · CVE reachability · dependency disposition · PR checklist · deploy promotion ·
retrieval rerank · progress watchdog · scope-creep gate · tool-result triage.
**Accept (each):** fixtures + calibration + accuracy report, or it does not ship.

### M11 — Cut over ⬜
- [ ] Archive `Electrum-ai/fleet-router` with a banner pointing here
- [ ] `1.0.0` published; install verified from a clean machine

---

## 5. Pack catalog

| # | Pack | Surface | Outcome signal | Failure | M |
|---|---|---|---|---|---|
| 1 | **Policy audit** (flagship) | cli | override / later incident | open | M2 |
| 2 | Tool gate | hook | user override | **loud** | M6 |
| 3 | Compaction | hook | later re-fetch of dropped context | **loud** | M6 |
| 4 | Another-pass-worth-it | lib | verification delta | open | M6 |
| 5 | Model routing | proxy | verification result | open | M8 |
| 6 | Failure novelty | lib | analyst confirmation | open | M10 |
| 7 | CVE reachability | CI | triage confirmation | open | M10 |
| 8 | Dependency disposition | CI | override / later breakage | open | M10 |
| 9 | PR checklist | CI | reviewer added it after | open | M10 |
| 10 | Deploy promotion | CI | rollback | **loud** | M10 |
| 11 | Retrieval rerank | lib | downstream verification | open | M10 |
| 12 | Progress watchdog | lib | turn ended in success | open | M10 |
| 13 | Scope-creep gate | CI | reviewer comment | open | M10 |
| 14 | Tool-result triage | hook | later re-fetch | open | M10 |

---

## 6. Differentiators

- **D1 — Decision ledger.** State, questions, *full distribution*, **propensity**, action and
  outcome. Competitors log the answer. Simultaneously an audit trail, a calibration set, and a
  training set.
- **D2 — Counterfactual replay, done correctly.** Off-policy evaluation with real estimators
  (IPS/SNIPS/DR). Naive replay over a deterministic log is **biased**; unbiased OPE needs logged
  propensities, which a Thompson bandit supplies. The bandit is therefore not just the learner —
  it is what makes the replay valid. Nobody in devtools does this.
- **D3 — Every pack reports its own accuracy.** The required `outcome` field makes it structural.
- **D4 — Backend portfolio.** The same pack measured across hosted and local backends on
  accuracy, latency and cost. No one else can produce that table.
- **D5 — Your ledger trains your model.** M9.

---

## 7. Risk register

| # | Risk | Sev | Mitigation | Owner |
|---|---|---|---|---|
| R-01 | **Raw confidence is overconfident and task-dependent.** Measured: 0.98 claimed → 0.89 actual; 55%–100% across tasks; no universal threshold. | High | *No longer speculative — this is the product's reason to exist.* D-15 recalibration; mechanical verification wins where it applies (D-08) | M3 |
| R-02 | ~~Tool-call translation in the proxy~~ | — | **Retired** by D-14; the AI SDK normalizes tool calls | — |
| R-03 | **Scope.** "Every decision a developer needs" is how projects die. | High | Registry (M7) gated on two packs shipped *and calibrated*. No new packs before M3 proves the engine. | M3/M7 |
| R-04 | Calibration cost scales linearly with packs | Med | `jev pack test` must make it cheap; no fixtures, no ship | M7 |
| R-05 | Data egress | Low | *Downgraded.* Local backends (D-13) mean nothing need leave the machine | M5 |
| R-06 | Phase-routing economics may not clear cache cost | Med | Per-turn first, phase routing behind a flag | M8 |
| R-07 | Crowded field: routing, gating, tool pruning all occupied | High | We are the measurement layer, not another guardrail. Existing tools are our **input data**, not our competitors | M2 |
| R-08 | Secret-scanning asymmetry — a false "not a secret" is a leaked credential | High | **Rank, never suppress.** Not in the catalog | — |
| R-09 | Porting v1 logic loses behaviour | Med | Translate the tests first | M3 |
| R-10 | **No accuracy evidence for coding decisions on any backend.** Published evaluations cover benchmarks and games, not agent decisions. | High | Our fixtures are the only answer; do not claim transfer | M3 |
| R-11 | Local backend coverage is uneven — Apple Silicon only for the fastest option | Med | Two local backends plus hosted fallback | M5 |
| R-12 | Third-party licences differ (MIT, Apache-2.0) and weights may differ from code | Med | Audit weight and dataset licences separately before shipping a default | M5 |

---

## 8. Explicitly NOT building

- **Ensembles, fan-out, self-consistency sampling, synthesis** — D-06.
- **LLM-as-judge with swap-order de-biasing** — one scored call replaces it.
- **Keyword/embedding classifiers** — replaced by perception; drops the heavy ML dependency.
- **Test selection.** *Killed after research.* Datadog, Launchable, Skippy, Tia, nx/Bazel all use
  coverage maps and dependency graphs — deterministic ground truth. D-08 says mechanical beats
  judgment, so a judgment-based test selector would violate our own principle.
- **Tool-definition pruning.** *Killed after research.* Solved upstream by Tool Search, Code Mode
  and MCP code-execution patterns.
- **Subagent dispatch** — falls out of model routing.
- **Memory write/staleness** — no memory system to attach to.
- **Tool-failure retry** — backoff already solves it.
- **Interrupt / notification triage** — no observable outcome signal; violates D3.
- **Secret-scanner suppression** — R-08. Ranking only, if ever.
- **Our own provider layer** — D-14.

---

## 9. Open questions

| # | Question | Needed by |
|---|---|---|
| Q-01 | Pack registry public from day one, or internal until the schema settles? | M7 |
| Q-02 | Does the CI direction become the primary business? | M10 |
| Q-03 | Hosted ledger/replay service, or local JSONL only? | M10 |
| Q-04 | Which backend ships as the default, given licence and platform constraints? | M5 |

---

## 10. Progress log

- `2026-09-21` — Plan created; full design discussion folded in. Next: M0.
- `2026-09-21` — Renamed to Jevistication; new repo. D-03/04/05 revised, D-12 added.
- `2026-09-21` — **Research pass over five upstream repos + competitive landscape.** Twelve
  deltas folded in: repositioned to the measurement layer; killed test selection and tool
  pruning; demoted model routing; new flagship policy audit; added D-13 backend portfolio,
  D-14 AI SDK, D-15 recalibration, D-16 propensity logging; revised D-09 and D-10; bandit moved
  to M1; added M9 ledger fine-tuning; R-02 retired, R-05 downgraded, R-07/R-10/R-11/R-12 added.

---

## 11. Upstream work we build on

### 11.1 Calibration evidence — `wuyoscar/jev-skill` (MIT)
160 BIG-Bench Hard items, 320 paired API calls, frozen protocol. **85% accuracy; the ≥0.90
confidence band was 92/100; the [.9,1] bin had mean probability 0.9814 against 0.8898 accuracy;
ECE 0.0994; per-task range 55%–100%; no universal execution threshold.**
**Use:** port `evals/calibration_metrics.py` (Wilson, reliability bins, ECE, Brier, NLL); adopt
the protocol discipline wholesale; take `browser_permission`, `completion_verification`,
`goal_recovery` and `human_triage` as fixtures; match the receipt schema for interop; ship as a
catalog entry for distribution. *Their caveat stands: 160 mixed-domain items proves raw
confidence needs correcting, it does not pin a number on any pack.*

### 11.2 Local backend, trainable — `TianyuCodings/NanoJev` (MIT)
0.6B replica; Qwen3-0.6B plus decision heads; Choice/Boolean/Score; zero output-token decoding;
open weights and dataset; dedicated `calibration` split. Beats hosted on some tasks, loses on
others — **no backend dominates**.
**Use:** `backends/nanojev.ts`; `docs/TYPESAFE_CONTRACT.md` is a pre-written adapter spec
(`noul`↔`boolean`, missing `confidence`/`legend`, non-canonical state serialization); the
training recipe is the blueprint for M9. *Evidence is games-only; no transfer claim.*

### 11.3 Local backend, fast — `mizorewww/laya-mlx` (Apache-2.0)
322M/421M typed-decision models on Apple Silicon. **7.39 ms / 13.42 ms P50 end-to-end**,
395 q/s, zero output tokens. Port fidelity 378/378 across FP32 and FP16.
**Use:** `backends/laya.ts`; adopt the fidelity-suite methodology as a shipping requirement for
every adapter. *Apple Silicon only; makes no accuracy claims.*

### 11.4 Backend comparison
| Backend | P50 | Deploy | Licence |
|---|---|---|---|
| Hosted | 70–500 ms | API | commercial |
| NanoJev 0.6B | unmeasured | local, anywhere | MIT |
| Laya-MLX 322/421M | **7–13 ms** | local, Apple Silicon | Apache-2.0 |

### 11.5 Compaction reference — `tamaratran/fast-jev-compaction` (MIT)
TypeScript, npm package **and** Claude Code plugin. **Never summarizes — only deletes.** Two
`noul` questions per tool call (keep the call? keep the result verbatim?), three-way outcome.
Staged state fitting into a 25k budget; question sets split under the **32k request limit**;
tokens estimated without a tokenizer, calibrated to overshoot. Fails loud on any error.
**Use:** the design for pack #3; the plugin layout (`.claude-plugin/`, `hooks/hooks.json`) as our
hook-surface reference; its 32k/25k constraints are now in §3.2; its fail-loud posture drove the
D-10 revision. Its `keepThreshold` is an uncalibrated raw probability — **a concrete first
customer for the policy audit.**

### 11.6 Competitive landscape
Model routing is crowded (RouteLLM, vLLM semantic-router, LiteLLM, OpenRouter). Agent gating is
crowded and validated — the leading open-source tool passed 4.7k stars in five months with 39
deterministic policies across 12 harnesses. Tool pruning was solved upstream in early 2026.
**Every one of these ships uncalibrated rules and none reports its own error rate.** That gap is
the product, and their installed bases are our input data.
