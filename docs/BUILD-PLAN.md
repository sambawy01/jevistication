# Jevistication — Build Plan

Single source of truth. Every decision from the design conversation is folded in here.
If something isn't in this document, it is not in scope. If it is here and unchecked, it is
still owed.

Companion: `docs/ARCHITECTURE.md` (the why). This document is the what and the when.

Jevistication is a fresh project. It supersedes [Fleet Router](https://github.com/Electrum-ai/fleet-router),
whose ensemble premise expired; the assets worth keeping are ported, not inherited.

---

## 0. Locked decisions

| # | Decision | Value | Rationale |
|---|---|---|---|
| D-01 | Language | **TypeScript / Node ≥ 20.12** | Not a compromise for npm. The TypeSafe SDK is TS-native: `ResultFor<T>` infers answer types from the question schema, so a pack's questions produce compile-time-typed answers. Type safety *is* the product; Python throws it away. |
| D-02 | Distribution | **`npm i -g jevistication`** | Name verified available on npm. Binary is `jev` (no collision with `jev-router`, which ships `jev-claude`/`jev-codex`/`jev-explain`). Agentic CLI ecosystem is Node; a package nobody can `npm install` cannot attract pack authors. |
| D-03 | Repo | **New repo: `sambawy01/jevistication`** | Clean history. Fleet Router v1 stays where it is and gets archived, not rewritten. Proven logic is ported deliberately (see D-12), not dragged along. |
| D-04 | Name | **Jevistication** | The v1 name ("router") undersells a decision engine. Settled now rather than before GA, since the repo is new. |
| D-05 | Version | **0.1.0 → 1.0.0 at M6** | New project, so no breaking-change story to manage. 1.0.0 is earned when the pack API goes public and its schema is committed to. |
| D-06 | Ensemble | **Deleted** | Gain ∝ error *decorrelation*, not model count. Frontier models fail alike. Self-consistency sampling on one model is correlated noise. |
| D-07 | Verification | **Kept, re-roled** | No longer a quality feature. It is the safety net that makes routing *down* risk-free: worst case becomes a retry, not a bad answer. |
| D-08 | Mechanical > judgment | **Always** | Where success is mechanically checkable (tests, types, numeric agreement), run the check and ignore Jev. Ground truth beats judgment. |
| D-09 | Hosted dependency | **Accepted** | Retires the "Ollama-only / no proprietary providers" principle permanently. Replaced by **"no vendor lock-in"**, which the provider abstraction actually delivers. Must be stated plainly in the README. |
| D-10 | Fail-open | **Always** | Jev down, slow, or malformed ⇒ configured default, never a blocked request. |
| D-11 | Sequencing | **Local-first, CI packs at M7** | Local packs are closer to working code and ship sooner. CI packs have better ground truth and stronger differentiation, so they follow once the engine is proven. *Override this if you want the CI/platform business first — it changes M2 and M7 order, nothing else.* |
| D-12 | Ported from v1 | **`stats.py`, `calibrate.py`, `bandit.py`, proxy dialect handling** | These four are genuinely proven and expensive to re-derive. Ported behaviour-for-behaviour with their tests translated first. Everything else in Fleet Router v1 is left behind. |

---

## 1. The product in one page

**Thesis.** Fleet Router v1 spent ~25 model calls to decide which of 21 answers was best.
Jevistication spends one
~100ms structured-decision call to decide what should happen before and after a *single*
frontier call.

**The engine.** One loop, written once:

```
                  ┌──────────────── learn ────────────────┐
                  ↓                                       │
state → [Perceive] → [Decide] → [Execute] → [Verify] → [Settle] → [Ledger]
        Jev, 1 call  pure fn    adapter     mechanical    ship /    every decision,
        ~100ms       total      per surface  else Jev     escalate  distribution + outcome
                                                          / abstain
```

**Decision Packs.** Every feature is a pack, not a code path:

```
Pack = state schema + typed questions + pure policy fn + outcome signal + fixtures
```

Model routing is pack #1. Tool gating is a pack. Test selection is a pack. **Users write
their own packs.** No outcome signal ⇒ not a pack.

**Three surfaces**, because a proxy only sees LLM traffic:

| Surface | Sees | Packs it can host |
|---|---|---|
| Proxy | LLM requests/responses | model routing |
| Hooks (`PreToolUse`/`PostToolUse`) | tool calls before they run | tool gate, tool prune, result triage |
| Library / CLI | whatever you hand it | everything else, incl. user packs |

**The three differentiators** (§6) are what defend this: ledger, counterfactual replay, and
every pack reporting its own accuracy.

---

## 2. Module layout

```
src/
  engine/
    types.ts          PerceptionVector, Decision, Outcome, PackSpec
    perceive.ts       one Jev call → typed answers (fail-open)
    decide.ts         pure policy runner
    verify.ts         mechanical-first dispatcher
    settle.ts         ship / escalate / abstain
    ledger.ts         D1 — append-only decision log
    replay.ts         D2 — counterfactual replay
    calibrate.ts      threshold fitting from ledger + fixtures
    stats.ts          McNemar exact + percentile bootstrap CI
    bandit.ts         Thompson over (pack, context) thresholds
  packs/
    registry.ts       discovery, validation, schema versioning
    model-routing/    pack 1
    tool-gate/        pack 2
    tool-prune/       pack 3
    ...
  providers/
    base.ts  openai-compat.ts  anthropic.ts  ollama.ts
  surfaces/
    proxy/            Anthropic Messages + OpenAI Chat Completions dialects
    hooks/            Claude Code hook entrypoints
    cli/              jev <cmd>
    lib.ts            public API for embedders
  config/
    schema.ts  load.ts  migrate.ts   (imports a Fleet Router v1 YAML config)
```

Ported from v1 Python, not reinvented: `stats.ts` ← `evals/stats.py`, `calibrate.ts` ←
`evals/calibrate.py`, `bandit.ts` ← `bandit.py`, proxy dialect handling ← `proxy.py`.
These are the assets worth carrying across the language boundary.

---

## 3. Core contracts

### 3.1 PackSpec

```ts
interface PackSpec<Q extends Questions> {
  name: string;
  surface: "proxy" | "hook" | "cli" | "lib";
  trigger: string;                                  // e.g. "pre_tool_use"
  buildState: (ctx: unknown) => EntryType;          // what Jev sees
  questions: Q;                                     // typed; answers inferred
  decide: (a: Answers<Q>, c: PolicyContext) => Decision;   // PURE + TOTAL
  outcome: OutcomeSource;                           // REQUIRED — no outcome, no pack
  fixtures: string;                                 // calibration corpus
  schemaVersion: number;
}
```

Two rules the type system enforces:

- **`decide` is pure and total.** No I/O, no clock, no randomness. Every malformed or missing
  input falls through to a safe default. This is what makes §6 D2 (replay) possible at all.
- **`outcome` is required.** A pack that cannot learn whether it was right is a guess wearing a
  probability distribution, and it does not ship.

### 3.2 Perception

One Jev call per decision point, all questions answered in parallel against one state. The
`choice` criteria for model selection is generated from the **live configured catalog**, never a
hardcoded enum — this is how model-agnosticism is enforced at the type level. Jevistication must never
name a model in code.

Core questions carried by every agentic pack:

```ts
{
  tier:               choice("Cheapest model that fully completes this in one pass", CATALOG),
  reasoning_required: score("Reasoning required to get this right in one pass", SCALE),
  blast_radius:       score("How far do the effects of this work reach", SCALE),
  verifiable:         noul("Can success be checked mechanically — tests, types, a diff?"),
  new_phase:          noul("Is this a new phase of work, or a continuation?"),
  needs_human:        noul("Should a human approve before this proceeds?"),
}
```

`verifiable` gates D-08. `new_phase` enables phase routing (§5, M3). `blast_radius` +
`needs_human` are the governor's inputs, carried from day one even though the governor lands at M5.

### 3.3 Policy precedence (model-routing pack)

1. Explicit model named by caller → passthrough, no routing.
2. Jev unavailable or low confidence → never downgrade; cap upgrades at the balanced tier.
3. Cache economics → no tier switch mid-phase unless saving > prompt-cache rebuild
   (~23.6k cache-creation tokens measured upstream; Jevistication measures its own at M4).
4. Budget → as remaining budget shrinks, the tier ceiling drops.
5. Bandit posterior for (tag, tier) shifts the sufficiency threshold.
6. Otherwise → Jev's choice.

---

## 4. Milestones

Each milestone is independently shippable and leaves `main` green. Acceptance criteria are
binary — no "mostly done".

### M0 — Skeleton ⬜
- [ ] TS toolchain: `tsconfig`, build to ESM + CJS + `.d.ts`, `node --test`
- [ ] `package.json` with `bin`, `files`, `exports`; publishable but unpublished
- [ ] CI: build, typecheck, test, on Node 20 + 22
- [ ] `LICENSE` (MIT), `README`, `CONTRIBUTING`, issue templates
- **Accept:** `npm pack` produces an installable tarball; CI green.

### M1 — Engine core ⬜
- [ ] `types.ts` — PerceptionVector, Decision, Outcome, PackSpec
- [ ] `perceive.ts` — Jev call, pinned timeout/retry/deadline, fail-open (D-10)
- [ ] `decide.ts` — pure policy runner, property tests for totality
- [ ] `ledger.ts` — append-only JSONL, `{state, questions, distribution, action, outcome?}`
- [ ] `packs/registry.ts` — load, validate, version
- **Accept:** a trivial pack runs end-to-end and writes a ledger row; `decide` passes
  property tests over malformed input (fuzz: every field missing/null/wrong type → no throw).

### M2 — Pack 1: model routing, on the proxy ⬜
- [ ] `providers/` — `openai-compat.ts`, `anthropic.ts`, `ollama.ts`
- [ ] `config/` — `providers:` block, `models:` naming a provider, capability slots resolved
      **by tag+priority, not by name** (`auto`)
- [ ] `jev models discover` — query provider catalogs, write entries
- [ ] Proxy surface: Anthropic Messages + OpenAI Chat Completions dialects
- [ ] Sentinel model routing (named model → passthrough; sentinel → route)
- [ ] **Real tool-call translation** — tool blocks must survive, not flatten to text
- [ ] `config/migrate.ts` — v1 YAML: `ollama:` → `providers.ollama`; ensemble keys warn+ignore
- **Accept:** Claude Code runs a full agentic session through the proxy with tools working;
  routing decisions appear in the ledger; a second provider is reachable from the same config.

> Tool-call translation is the highest-risk item in the plan. v1 flattened tool blocks to text,
> which is exactly why it could never sit in front of an agentic CLI. Budget real time here.

### M3 — Close the loop ⬜
- [ ] `verify.ts` — mechanical-first (D-08): run project tests/typecheck; numeric agreement
- [ ] `JevVerifier` — one `score()` call; **entropy of the distribution** as abstention signal
- [ ] `settle.ts` — ship / escalate one tier / abstain at top tier
- [ ] `bandit.ts` — arms are `(tag, tier)` → *was the cheap tier sufficient*, reward = passed verification
- [ ] Phase routing behind a flag (hold tier until `new_phase`)
- **Accept:** a deliberately hard prompt routed to the cheap tier fails verification, escalates,
  and the bandit posterior moves. Abstention fires at top tier with a stated reason.

### M4 — Prove it ⬜
- [ ] `stats.ts` — McNemar exact + percentile bootstrap CI (port from `evals/stats.py`)
- [ ] `calibrate.ts` — fit per-pack thresholds from fixtures + ledger
- [ ] `replay.ts` — **D2**: replay historical ledger against a candidate policy, diff outcomes
- [ ] `jev replay --policy <ref>` reports counterfactual deltas with CIs
- [ ] Measure and record: cheap-tier sufficiency rate *p*; Jev verification accuracy vs ground
      truth; closed-loop vs always-top-tier under McNemar; Jevistication's own cache-rebuild constant
- **Accept:** a threshold change can be evaluated against ≥1k logged decisions *before* shipping,
  reporting "would have changed N decisions, M of them wrongly," with confidence intervals.

> M4 is the differentiator. Everything before it is table stakes; this is the part competitors
> cannot copy in an afternoon.

### M5 — Hooks surface + local packs ⬜
- [ ] Hook entrypoints: `PreToolUse`, `PostToolUse`
- [ ] **Pack 2 — tool gate** (governor): `reversible`, `blast_radius`, `in_scope`,
      `exfiltration`, `disposition: auto|confirm|block`. Outcome = *user override* (free supervision)
- [ ] **Pack 3 — tool prune**: score tool relevance, load only what the task needs
- [ ] **Pack 4 — another-pass-worth-it**: kill the unconditional critique/revise call
- [ ] README: governor is **defense in depth, not a security boundary** (same caveat as v1's AST denylist)
- **Accept:** tool gate runs under a real Claude Code session with measured p50 < 150ms added
  latency; tool-prune cuts tool-definition tokens ≥ 50% on a 40+ tool config with no measured
  loss in task success.

### M6 — Open the pack registry ⬜
- [ ] Public pack authoring API + `jev pack init` scaffold
- [ ] `jev pack test` — run a pack against its fixtures, report accuracy + calibration
- [ ] CLI surface complete: `route`, `explain`, `replay`, `calibrate`, `ledger`, `models`, `pack`
- [ ] `explain` — render the decision (state, distribution, policy branch taken) for any ledger row
- [ ] Docs: pack authoring guide, schema versioning policy
- **Accept:** a third party writes and calibrates a pack without reading Jevistication's source.

### M7 — CI packs ⬜
- [ ] **Pack 5 — test selection** (flagship): which subset does this diff need?
      Outcome = *did anything skipped fail later* (free, perfect ground truth)
- [ ] **Pack 6 — real-vs-flaky triage**
- [ ] GitHub Action / CI adapter
- **Accept:** test selection replayed over ≥90 days of a real repo's CI history shows CI time
  saved and real failures missed, both with CIs. Ship only if the miss rate is defensible.

### M8 — Remaining packs ⬜
- [ ] Pack 7 — CVE reachability (call graph mechanical + Jev exploitability)
- [ ] Pack 8 — dependency update disposition
- [ ] Pack 9 — PR checklist from diff (migration? flag? changelog? docs?)
- [ ] Pack 10 — failure novelty / dedup
- [ ] Pack 11 — deploy promotion
- [ ] Pack 12 — compaction policy
- [ ] Pack 13 — retrieval reranking
- [ ] Pack 14 — agent progress watchdog (`making_progress`/`looping`/`drifted`)
- [ ] Pack 15 — scope-creep gate on diffs
- [ ] Pack 16 — tool-result triage
- **Accept (each):** fixtures + calibration + accuracy report, or it does not ship. Packs land
  one at a time; none is a prerequisite for another.

### M9 — Cut over ⬜
- [ ] Archive `Electrum-ai/fleet-router`, README banner pointing here
- [ ] `1.0.0` published to npm
- [ ] Install verified from a clean machine
- **Accept:** `npm i -g jevistication` works from a clean machine; v1 is archived, read-only,
  and signposted.

---

## 5. Pack catalog

Status: ⬜ planned · 🛠 in progress · ✅ shipped

| # | Pack | Surface | Outcome signal | M | Status |
|---|---|---|---|---|---|
| 1 | Model routing | proxy | verification result | M2/M3 | ⬜ |
| 2 | Tool gate (governor) | hook | user override | M5 | ⬜ |
| 3 | Tool-definition prune | hook | task success + token delta | M5 | ⬜ |
| 4 | Another-pass-worth-it | lib | verification delta | M5 | ⬜ |
| 5 | **Test selection** | CI | did a skipped test later fail | M7 | ⬜ |
| 6 | Real-vs-flaky | CI | rerun result | M7 | ⬜ |
| 7 | CVE reachability | CI | triage confirmation | M8 | ⬜ |
| 8 | Dependency disposition | CI | override / later breakage | M8 | ⬜ |
| 9 | PR checklist | CI | reviewer added it after | M8 | ⬜ |
| 10 | Failure novelty | lib | analyst confirmation | M8 | ⬜ |
| 11 | Deploy promotion | CI | rollback | M8 | ⬜ |
| 12 | Compaction policy | hook | later re-fetch of dropped context | M8 | ⬜ |
| 13 | Retrieval rerank | lib | downstream verification | M8 | ⬜ |
| 14 | Progress watchdog | lib | turn ended in success | M8 | ⬜ |
| 15 | Scope-creep gate | CI | reviewer comment | M8 | ⬜ |
| 16 | Tool-result triage | hook | later re-fetch | M8 | ⬜ |

---

## 6. Differentiators (why this defends)

- **D1 — Decision ledger.** Every decision logs state, questions, *full distribution*, action,
  and eventual outcome. Competitors log the answer. This is simultaneously an audit trail and a
  growing labeled calibration set for every pack.
- **D2 — Counterfactual replay.** Because `decide` is pure over logged state, any candidate
  policy can be replayed over history: *"this threshold would have skipped 340 more test runs and
  missed 2 real failures, one in payments."* Turns "trust my classifier" into an underwriting
  argument. **Build the product around this.**
- **D3 — Every pack reports its own accuracy.** The required `outcome` field makes this
  structural, not aspirational.

These compound: ledger feeds replay, replay validates policy, policy feeds the ledger.

---

## 7. Risk register

| # | Risk | Severity | Mitigation | Owner milestone |
|---|---|---|---|---|
| R-01 | **Jev's calibration as a verifier is unproven.** "No hallucination" means schema-constrained output, *not* correct judgment. The whole closed loop rests on it flagging cheap-tier failures. | High | D-08 (mechanical always wins where applicable); conservative shipped thresholds; measure before trusting | M4 |
| R-02 | **Tool-call translation in the proxy.** v1 flattened tool blocks to text; agentic CLIs break. Request formats are not public contracts. | High | Budget real time at M2; dump-and-diff harness against live CLI traffic; version-pin tested CLI versions | M2 |
| R-03 | **Scope.** "Every decision a developer needs" is how projects die. | High | Pack architecture is the mitigation, but M6 (open registry) is gated on two packs shipped and calibrated. No pack #5+ before M4 proves the engine. | M4/M6 |
| R-04 | **Calibration cost scales linearly with packs.** Pack #12 costs as much to make trustworthy as pack #1. | Medium | `jev pack test` must make calibration cheap; a pack without fixtures does not ship (D3) | M6 |
| R-05 | **Hosted dependency + data egress.** Prompt text, tool args, and diffs leave the machine. | Medium | Stated plainly in README (D-09); per-pack egress disclosure; fail-open so Jev is never required | M2 |
| R-06 | **Phase-routing economics may not clear the cache cost.** | Medium | Ship per-turn routing first; phase routing behind a flag; measure Jevistication's own constant | M3/M4 |
| R-07 | **Overlap with `jev-router`.** It already does per-turn, single-provider, in-catalog routing well. | Medium | Jevistication must be unambiguously the *closed-loop, cross-provider, budget-aware, multi-pack* layer, or it is a worse `jev-router` | M3 |
| R-08 | **Secret-scanning asymmetry.** A false "not a secret" is a leaked credential. | High | **Rank, never suppress.** Not in the pack catalog as suppression. | — |
| R-09 | **TS rewrite loses v1's proven logic.** | Medium | Port `stats.py`, `calibrate.py`, `bandit.py` behavior-for-behavior with their test cases translated first | M3/M4 |
| R-10 | **Local vs CI is two businesses.** Different users, distribution, trust bar. | Medium | D-11 sequencing; revisit at M7 with real data from both | M7 |

---

## 8. Explicitly NOT building

Recorded so they don't get re-litigated:

- **Ensembles, fan-out, self-consistency sampling, answer synthesis** — D-06.
- **LLM-as-judge with swap-order de-biasing** — replaced by one Jev `score()` call.
- **Keyword/embedding classifiers** — replaced by perception. Drops `sentence-transformers`
  and the 60s cold start the SessionStart hook currently polls through.
- **Subagent dispatch** — falls out of model routing for free; don't build it twice.
- **Memory write/staleness decisions** — no memory system to attach to; building one to justify
  a pack is the tail wagging the dog.
- **Tool-failure retry decisions** — exponential backoff already solves it; Jev adds little over
  a status code.
- **Interrupt / notification triage** — genuinely novel, but no clean outcome signal exists
  (you can't observe whether a suppressed interrupt should have fired). Violates D3. Parked.
- **Secret-scanner suppression** — see R-08. Ranking only, if ever.

---

## 9. Open questions

Not blockers for M0–M2; needed before the milestone named.

| # | Question | Needed by |
|---|---|---|
| Q-01 | Is the pack registry public from day one, or internal until the schema settles? Public early gets contributors and freezes a schema you'll want to change. | M6 |
| Q-02 | Does the CI direction become the primary business? D-11 says local-first; M7 data should settle it. | M7 |
| Q-03 | Does Jevistication ship its own hosted ledger/replay service, or stay a local tool writing JSONL? Changes the business, not the engine. | M7 |

---

## 10. Progress log

Append one line per session. Never rewrite history here.

- `2026-09-21` — Plan created; the full design discussion folded in. Nothing built yet. Next: M0.
- `2026-09-21` — Renamed to Jevistication; moved to a new repo. D-03/04/05 revised, D-12 added.
