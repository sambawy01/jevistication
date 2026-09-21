# Jevistication

**A calibrated decision engine for developer workflows.**

Every developer workflow is full of decisions made by a regex, a hardcoded threshold, or
nothing at all. Which model should handle this turn. Should this tool call run. Which tests
does this diff actually need. Is this failure real or flaky. Is another revision pass worth it.

Jevistication makes those decisions with a fast structured-decision model, and — the part that
matters — **can prove whether its policy is any good**, before and after you ship it.

> Status: pre-alpha. The design is settled; the code is not written yet.
> Start with [`docs/BUILD-PLAN.md`](docs/BUILD-PLAN.md).

## The idea

```
                  ┌──────────────── learn ────────────────┐
                  ↓                                       │
state → [Perceive] → [Decide] → [Execute] → [Verify] → [Settle] → [Ledger]
        one call     pure fn    adapter     mechanical    ship /    state, distribution,
        ~100ms       total      per surface else model    escalate  action, outcome
                                                          / abstain
```

One engine. Every feature is a **pack**:

```
Pack = state schema + typed questions + pure policy fn + outcome signal + fixtures
```

No outcome signal, no pack. A decision system that cannot tell whether it was right is a guess
wearing a probability distribution.

## What makes it different

- **Decision ledger** — every decision logs its full probability distribution and its eventual
  outcome, not just the answer. An audit trail and a growing calibration set at once.
- **Counterfactual replay** — because policies are pure functions over logged state, a candidate
  policy can be replayed across history *before* shipping: *"this threshold would have skipped
  340 more test runs and missed 2 real failures."*
- **Every pack reports its own accuracy.** Structural, not aspirational.

## Documents

| | |
|---|---|
| [`docs/BUILD-PLAN.md`](docs/BUILD-PLAN.md) | Single source of truth: locked decisions, milestones, pack catalog, risk register, what we are explicitly not building |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | Why the design is shaped this way |

## History

Jevistication supersedes [Fleet Router](https://github.com/Electrum-ai/fleet-router), which was
built on a premise that expired: that a single call from a weak model is a guess, so you should
buy many opinions and adjudicate between them. Once the model pool became frontier-grade, that
ensemble stopped paying for itself.

The insight that survived: the ensemble was never really about better answers. It was an
expensive machine for manufacturing a **confidence signal**. Once confidence can be bought
directly, in milliseconds, the entire apparatus loses its reason to exist — and what you build
instead is a decision engine.

Four things are ported from v1 rather than re-derived: the statistical gating (McNemar exact +
percentile bootstrap), threshold calibration, the Thompson bandit, and the proxy's API dialect
handling.

## License

MIT
