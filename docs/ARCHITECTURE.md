# Jevistication — Architecture

Status: design rationale — the *why*.
Plan of record is [`BUILD-PLAN.md`](BUILD-PLAN.md); where the two disagree, the plan wins.
Supersedes the ensemble architecture of [Fleet Router](https://github.com/Electrum-ai/fleet-router).

---

## 1. Why Fleet Router v1 no longer fits

Fleet Router v1 was built on a premise that has expired:

> A single call from a weak model is a guess, so buy 21 opinions and adjudicate.

Three things broke it.

**The pool became frontier-grade.** Ensembling exists to exploit *decorrelated* errors. Models
trained on converging data with converging post-training recipes fail in converging ways. When
three models agree 90% of the time you pay 3× for the same answer 90% of the time, and on the
10% where they disagree a majority vote is close to a coin flip.

**Self-consistency sampling was always the weakest link.** `samples_by_tag` draws 3–7 samples
from *one model at temperature 0.7*. Those errors are maximally correlated — same weights, same
blind spot, jittered. That is where most of the 20–80× cost multiplier went.

**The ensemble was never really about better answers.** It was a machine for *manufacturing a
confidence signal*. v1 could not ask "is this answer good?", so it asked "do 21 opinions agree?"
and used disagreement as a proxy for uncertainty. TypeSafe's Jev sells that signal directly at
$0.042/M input tokens in ~100ms. Once confidence can be **bought**, it no longer has to be
**manufactured** — and the entire fan-out/verify/synthesize apparatus loses its reason to exist.

## 2. The thesis

| | Fleet Router v1 | Jevistication |
|---|---|---|
| Goal | Many weak models → one good answer | One good answer, at the lowest tier that can produce it |
| Spend | Compute, to manufacture quality | ~Nothing, to decide where work goes |
| Verification | Quality feature (LLM jury) | **Safety net that makes routing down risk-free** |
| Scope | One provider (Ollama), one prompt | Any provider, whole agentic sessions |

**Jevistication is a closed-loop, cross-provider, budget-aware dispatcher for agentic work.**

### The load-bearing idea

An open-loop router must be conservative. `jev-router`'s policy refuses to downgrade whenever
confidence is low, because a wrong downgrade is unrecoverable — the bad answer ships and the
user eats it. That conservatism leaves most of the savings unclaimed.

Close the loop and the calculus inverts. Route optimistically to a cheap tier, verify the
result, escalate on failure. **The worst case stops being a bad answer and becomes a retry.**
That is the one thing an open-loop router structurally cannot do, and it is the whole reason
Jevistication should keep existing.

## 3. The core loop

```
                    ┌───────────────── learn ─────────────────┐
                    │                                         │
                    ↓                                         │
request → [Perceive] → [Decide] → [Execute] → [Verify] → [Settle]
          Jev, 1 call   pure fn    any tier,   ground truth    ship, or
          ~100ms                   any provider  or Jev        escalate + record
```

Five stages, five modules, one call to Jev per decision point.

### 3.1 Perceive — `src/engine/perceive.ts`

*Replaces `classifier.py` (161 lines of regex) and `llm_classifier.py` (107 lines).*

One Jev call returns a whole **PerceptionVector**: every question answered in parallel against
the same state, each with a calibrated probability distribution.

```ts
const state = {
  "request": prompt,
  "session": {"phase": phase, "context_tokens": n, "turns": k, "current_tier": tier},
  "environment": {"repo": repo_facts, "available_models": [...], "budget_remaining": usd},
}

questions = {
  "tier":               choice("Cheapest model that fully completes this in one pass", CATALOG),
  "reasoning_required": score("Reasoning required to get this right in one pass", SCALE),
  "blast_radius":       score("How far do the effects of this work reach", SCALE),
  "verifiable":         noul("Can success be checked mechanically — tests, types, a diff?"),
  "new_phase":          noul("Is this a new phase of work, or a continuation?"),
  "needs_human":        noul("Should a human approve before this proceeds?"),
}
```

`CATALOG` is generated from the **live configured model set**, not a hardcoded enum — the
technique `jev-router` uses in `questionForModels`, and the correct one. Model-agnosticism
starts here: Jevistication never names a model in code.

Two answers do more work than they look like they do:

- **`verifiable`** decides whether to trust a judgment at all. When true, Jevistication runs the
  project's real checks and ignores Jev's opinion entirely. Ground truth beats judgment.
- **`new_phase`** enables phase routing (§5.3).

Deleting the embedding classifier also removes the `sentence-transformers` dependency and the
60-second cold start that `scripts/fleet-ensure-proxy.py` currently has to poll through.

### 3.2 Decide — `src/engine/decide.ts`

A **pure, total** function: `(perception, config, budget, history) → Decision`. Any missing,
malformed, or unavailable input falls through to a safe default. `jev-router`'s `src/policy.mjs`
is the right shape and worth copying outright.

Precedence:

1. **Explicit model** named by the caller → passthrough, no routing.
2. **Jev unavailable or low confidence** → never downgrade; cap upgrades at the balanced tier.
3. **Cache economics** → do not switch tiers mid-phase unless the saving exceeds the prompt-cache
   rebuild (`jev-router` measured ~23.6k cache-creation tokens; carry that constant until Jevistication
   measures its own).
4. **Budget** → as remaining budget shrinks, the tier ceiling drops.
5. **Bandit posterior** for `(tag, tier)` shifts the sufficiency threshold.
6. Otherwise → Jev's choice.

Routing is **fail-open**: Jev being down degrades to "use the configured default tier", never to
a blocked request.

### 3.3 Execute — `src/providers/`

The provider pool builds one provider per `providers:` entry instead of hardcoding a
single `OllamaProvider`. Adds `OpenAICompatProvider` (covers OpenRouter, vLLM, LM Studio,
Together, and DeepSeek/Moonshot/Z.ai direct) and `AnthropicProvider`. Ollama becomes one option
among many rather than the substrate.

```yaml
providers:
  ollama:     {kind: openai_compat, base_url: http://localhost:11434}
  openrouter: {kind: openai_compat, base_url: https://openrouter.ai/api/v1, api_key_env: OPENROUTER_API_KEY}
  anthropic:  {kind: anthropic,     api_key_env: ANTHROPIC_API_KEY}

models:
  sonnet:   {provider: anthropic, api_model: claude-sonnet-5, tier: balanced, tags: [code, general]}
  opus:     {provider: anthropic, api_model: claude-opus-5,   tier: strong,   tags: [reasoning, code]}
  ds-flash: {provider: openrouter, api_model: deepseek-v4-flash, tier: fast,  tags: [code, general]}
```

Capability slots (`judge_model`, `escalation.model`, `critique_model`) resolve **by tag and
priority**, not by name. `auto` picks the highest-priority model carrying the needed tag from
whatever pool exists. A user's custom pool can no longer silently no-op a feature.

### 3.4 Verify — `src/engine/verify.ts`

Pruned to two kinds of signal, in strict priority order.

**Mechanical (ground truth) — always preferred.**
- `CodeVerifier`, re-aimed from AST heuristics to running the project's own tests and typecheck.
  The AST scoring was a proxy for execution back when execution was unsafe; the sandbox gate and
  the `code_execute` default-off posture stay exactly as they are.
- `MathVerifier`, numeric agreement. Unchanged.

**Judgment (Jev) — only when nothing mechanical applies.**
- New `JevVerifier`: a single `score()` call against a tag-specific rubric. Uses the **entropy of
  the returned distribution** as the abstention signal, which is strictly more information than
  v1's scalar threshold.

The rule, stated once: **if `verifiable` is true, never ask Jev — run the check.**

### 3.5 Settle — `src/engine/settle.ts`

- Verification passes → ship.
- Verification weak → escalate one tier, re-run, record the outcome.
- Verification weak at the top tier → **abstain**. Hand it to a human with the reason.

Abstention survives from v1 unchanged in spirit. "I don't know" beats a confident wrong answer,
and it is the honest terminal state of a closed loop.

Every outcome writes to the bandit and the event bus.

## 4. What dies

*(Fleet Router v1 modules, for the record — none are carried over.)*

| Module | Lines | Why |
|---|---:|---|
| `dispatcher.py` | 116 | No fan-out. There is one call. |
| `synthesizer.py` | 157 | Nothing to synthesize. |
| `verifiers/judge.py` | 397 | Swap-order de-biasing, multi-pass, self-preference guards — all scaffolding around an unreliable LLM jury. Jev replaces it. |
| `verifiers/heuristic.py` | 68 | Length/diversity scoring was a fallback for a fallback. |
| `classifier.py` | 161 | Regex keyword maps. |
| `llm_classifier.py` | 107 | An entire LLM call to obtain one label. |
| `sampling.*` config | — | Self-consistency on one frontier model is correlated noise. |

~1,000 lines removed, plus a heavyweight ML dependency and its cold start.

## 5. What survives, and gets promoted

### 5.1 The bandit — re-aimed

`bandit.py` keeps its Thompson sampling, decay, and cold-start priors. The arms change meaning:

- v1: `(tag, model)` → *which model produces the best answer*
- v2: `(tag, tier)` → *was the cheap tier **sufficient***

Reward is whether the cheap tier passed verification. The routing boundary becomes **learned from
real outcomes** rather than a static confidence threshold someone guessed. No open-loop router
can do this, because it never finds out whether the cheap tier actually succeeded.

### 5.2 The eval harness — promoted to centerpiece

`evals/stats.py` (McNemar exact + percentile bootstrap CI) and `evals/calibrate.py` stop being a
side feature and become the thing the product rests on. A router whose thresholds are not
calibrated is a guess wearing a probability distribution. Three measurements gate the design:

1. **Cheap-tier sufficiency rate per tag** — the *p* in the economics below.
2. **Jev's verification accuracy vs. ground truth** on the fixtures — does it actually catch
   cheap-tier failures?
3. **Closed-loop vs. always-top-tier**, McNemar-gated — is the quality difference real or noise?

### 5.3 The proxy — promoted to primary surface

`proxy.py` becomes the main entry point rather than a side feature. `_resolve_force_model`
(`proxy.py:174`) is already the correct hook: named model → passthrough, sentinel → route.

The 💭 roadmap item **"real tool-call translation in proxy"** stops being optional and becomes
load-bearing. Tool blocks currently flatten to text, which is exactly why v1 could not sit in
front of an agentic CLI.

This unlocks **phase routing**. An agentic turn is fifty steps of wildly varying difficulty:
plan (hard) → read files (trivial) → edit (medium) → run tests (trivial) → diagnose (hard).
Per-turn routing prices the whole loop at its hardest moment. Per-*step* routing loses to
prompt-cache thrash. Per-**phase** routing — hold a tier until `new_phase` fires — batches the
switches and amortizes the rebuild. This is a hypothesis, and §7 treats it as one.

### 5.4 Also kept

`events.py` (feeds budget tracking and the decision log), abstention, and the `config.py` schema
with a `providers:` block added.

## 6. Phase 2 capability: the governor

The perception call already returns `blast_radius` and `needs_human`. Wiring them to a
pre-tool-call gate is nearly free once perception exists:

> Should this tool call run at all, and under what permission?

Every agent CLI today gates tools with allowlists and regexes — a literal dumb `if`, which is why
everyone ends up on bypass-permissions. A ~100ms schema-constrained decision is the right shape
for that gate: it is a hot loop, so frontier latency and price are both disqualifying, and a
safety layer must not be able to hallucinate a verdict.

**This is not a security boundary** — the same caveat the README already makes about the AST
denylist applies verbatim. It is defense in depth and, mostly, a cure for permission fatigue.

Deferred to phase 2, but the `PerceptionVector` carries the fields from day one.

## 7. Honest risks

**1. Jev's calibration as a verifier is unproven.** "No hallucination" means the output is
*schema-constrained* — it cannot return a label you did not define. It does **not** mean the
judgment is correct. Jev can be confidently wrong about whether an implementation works. The
entire closed loop rests on it correctly flagging cheap-tier failures, so a miscalibrated verdict
means either burning the top tier on work the cheap tier already nailed, or shipping a failure.
*Mitigation:* mechanical verification always wins where it applies; ship conservative thresholds;
calibrate against fixtures before trusting the loop.

**2. Hosted dependency.** This permanently retires the "Ollama-only, no proprietary providers"
principle in project memory. Prompt text — and for the governor, tool arguments and diffs — leaves
the machine. `jev-router`'s README concedes the same for routing hints alone; Jevistication's surface is
wider. Replace the principle with "no vendor lock-in", which the provider abstraction actually
delivers, and make the trade explicit in the README rather than discovering it later.

**3. Phase-routing economics may not clear the cache cost.** Unproven. Measure before shipping
per-phase switching; ship per-turn routing first.

**4. Overlap with `jev-router`.** It already does per-turn, single-provider, in-catalog routing,
and does it well. Jevistication must be unambiguously the *closed-loop, cross-provider, budget-aware*
layer or it is simply a worse `jev-router`.

## 8. Economics

With cheap-tier sufficiency rate *p* and price ratio *r* = cheap/expensive:

```
closed-loop cost ≈ r + (1 − p)(r + 1)      vs.   1.0 for always-expensive
```

Break-even is forgiving when *r* is small, but it depends entirely on *p* for real work and on
Jev's accuracy at spotting the failures. Both are measurable with the harness that already
exists. Measure, do not project.

## 9. What is carried over

Jevistication is a new project, not a migration. Four things are ported from Fleet Router v1
because they are proven and expensive to re-derive — statistical gating (McNemar exact +
percentile bootstrap), threshold calibration, the Thompson bandit, and the proxy's API dialect
handling. Everything else is left behind. See `D-12` in the build plan.

## 10. Sequencing

Milestones live in [`BUILD-PLAN.md`](BUILD-PLAN.md) §4 and are the single source of truth.
This document deliberately keeps no schedule of its own.
