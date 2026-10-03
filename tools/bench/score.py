#!/usr/bin/env python3
"""Marks any number of prediction files against Fast Decisions with one set of rules.

  python3 tools/bench/score.py backend-onnx/build/bench/laya.jsonl bench/out/kai.jsonl

Protocol (the dataset card's, applied identically to every model):
- full coverage: every single-label head-instance is asked; a missing line, a null prediction or
  an error is WRONG -- a model is not allowed to skip what it finds hard;
- exact match against the gold label;
- the suite figure is the mean of the 17 per-domain accuracies, with the pooled figure beside it;
- the 26 single-label heads only (2,600 head-instances), because Laya's Choice answers one label
  and comparing it on multi-label heads would be scoring a different task.

It first recomputes the two reference floors over all 29 heads and checks them against the
figures recorded in docs/BUILD.md (25.8% / 35.2%). If they differ, the data or this scorer is not
the one the recorded numbers came from, and every number below would be suspect -- so it stops.

With two or more files it also prints a paired comparison: on how many items each model was
right where the other was wrong, and an exact McNemar p-value, so a 1-point gap is not mistaken
for a win.

DEVELOPMENT SPLIT. Never report these as "the benchmark" -- see the dataset card.
"""
import argparse
import json
import statistics
import sys
from collections import Counter, defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import fastdecisions as fd  # noqa: E402

RECORDED_FLOORS = {"majority": 25.8, "lexical": 35.2}


def lexical(case: dict) -> list[str]:
    """FastDecisions.lexicalPredict, rule for rule."""
    text = set(fd.words(case["text"]))
    scored = {l: sum(1 for w in fd.words(l) if w in text) for l in case["labels"]}
    if case["multi"]:
        hits = [l for l in case["labels"] if scored[l] > 0]
        return hits or [case["labels"][0]]
    best = max(scored.values())
    if best == 0:
        return [case["labels"][0]]
    return [min(l for l in case["labels"] if scored[l] == best)]


def majority_table(cases: list[dict]) -> dict:
    """FastDecisions.majorityAnswer per head: most frequent gold set, ties by joined key."""
    counts: dict = defaultdict(Counter)
    for c in cases:
        counts[(c["domain"], c["task"])][tuple(sorted(c["gold"]))] += 1
    return {k: list(sorted(v.items(), key=lambda kv: (-kv[1], "\0".join(kv[0])))[0][0])
            for k, v in counts.items()}


def suite(cases: list[dict], predict) -> dict:
    """Per-domain accuracy, mean of domains, pooled. predict(case) -> list of labels or None."""
    per = defaultdict(lambda: [0, 0])
    for c in cases:
        p = predict(c)
        per[c["domain"]][1] += 1
        if p is not None and set(p) == set(c["gold"]):
            per[c["domain"]][0] += 1
    domains = [d for d in fd.DOMAINS if per[d][1]]
    avg = sum(per[d][0] / per[d][1] for d in domains) / len(domains)
    pooled = sum(per[d][0] for d in domains) / sum(per[d][1] for d in domains)
    return {"per": {d: per[d][0] / per[d][1] for d in domains}, "average": avg, "pooled": pooled,
            "n": sum(per[d][1] for d in domains)}


def mcnemar_p(b: int, c: int) -> float:
    """Exact two-sided McNemar test on the discordant pairs."""
    n = b + c
    if n == 0:
        return 1.0
    k = min(b, c)
    from math import comb
    tail = sum(comb(n, i) for i in range(k + 1))
    return min(1.0, 2 * tail / (1 << n))


def pct(x: float) -> str:
    return f"{x * 100:.1f}%"


def main() -> None:
    ap = argparse.ArgumentParser(description="Mark prediction files against Fast Decisions.")
    ap.add_argument("predictions", nargs="+")
    ap.add_argument("--data", default="third-party/fast-decisions")
    ap.add_argument("--skip-floor-check", action="store_true", help="for synthetic test data only")
    args = ap.parse_args()

    cases = fd.load(args.data)
    maj = majority_table(cases)

    # 1. Reproduce the recorded floors over all 29 heads, or stop.
    floor_all = {"majority": suite(cases, lambda c: maj[(c["domain"], c["task"])])["average"] * 100,
                 "lexical": suite(cases, lexical)["average"] * 100}
    print("Self-check, all 29 heads:  majority %.1f%%  label-names %.1f%%  (recorded 25.8%% / 35.2%%)"
          % (floor_all["majority"], floor_all["lexical"]))
    if not args.skip_floor_check and any(abs(round(floor_all[k], 1) - v) > 0.05 for k, v in RECORDED_FLOORS.items()):
        raise SystemExit("FLOORS DO NOT MATCH the recorded figures: different data or scorer. Stopping.")

    # 2. The comparison set: single-label heads only.
    single = [c for c in cases if not c["multi"]]
    key = lambda c: (c["item_id"], c["task"])  # noqa: E731
    floors = {"majority": suite(single, lambda c: maj[(c["domain"], c["task"])]),
              "label-names": suite(single, lexical)}

    models = {}
    for path in args.predictions:
        preds, ms, errors, cut, name = {}, [], 0, 0, Path(path).stem
        for line in Path(path).read_text(encoding="utf-8").splitlines():
            if not line.strip():
                continue
            r = json.loads(line)
            name = r.get("model", name)
            preds[(r["item_id"], r["task"])] = r.get("predicted")
            if r.get("ms") is not None:
                ms.append(float(r["ms"]))
            errors += 1 if r.get("error") else 0
            cut += 1 if r.get("cut") else 0
        missing = sum(1 for c in single if key(c) not in preds)
        result = suite(single, lambda c: None if preds.get(key(c)) is None else [preds[key(c)]])
        result.update(errors=errors, missing=missing, cut=cut,
                      median_ms=statistics.median(ms) if ms else None, preds=preds)
        models[name] = result

    cols = list(floors) + list(models)
    every = {**floors, **models}
    print(f"\nFast Decisions DEVELOPMENT split -- {len(single)} single-label head-instances, full coverage, exact match\n")
    print("| domain | " + " | ".join(cols) + " |")
    print("|---|" + "---:|" * len(cols))
    for d in fd.DOMAINS:
        if d in every[cols[0]]["per"]:
            print(f"| {d} | " + " | ".join(pct(every[m]["per"][d]) for m in cols) + " |")
    print("| **average (mean of 17)** | " + " | ".join(f"**{pct(every[m]['average'])}**" for m in cols) + " |")
    print("| pooled | " + " | ".join(pct(every[m]["pooled"]) for m in cols) + " |")

    print("\nThe two heads Loupe already ships:")
    for hid in fd.LOUPE_HEADS:
        dom, task = hid.split(".")
        hc = [c for c in single if c["domain"] == dom and c["task"] == task]
        row = []
        for m in cols:
            if m in models:
                p = models[m]["preds"]
                ok = sum(1 for c in hc if p.get(key(c)) == c["gold"][0])
            else:
                f = (lambda c: maj[(c["domain"], c["task"])]) if m == "majority" else lexical
                ok = sum(1 for c in hc if f(c) == c["gold"])
            row.append(f"{m} {ok}/{len(hc)}")
        print(f"  {hid}: " + "  |  ".join(row))

    print("\nPer model:")
    for m, r in models.items():
        lat = f"median {r['median_ms']:.1f} ms/question" if r["median_ms"] is not None else "no timings"
        print(f"  {m}: errors {r['errors']}, missing {r['missing']}, input cut {r['cut']}, {lat}")

    names = list(models)
    if len(names) >= 2:
        print("\nPaired (same items):")
        for i in range(len(names)):
            for j in range(i + 1, len(names)):
                a, b = names[i], names[j]
                pa, pb = models[a]["preds"], models[b]["preds"]
                only_a = sum(1 for c in single if pa.get(key(c)) == c["gold"][0] and pb.get(key(c)) != c["gold"][0])
                only_b = sum(1 for c in single if pb.get(key(c)) == c["gold"][0] and pa.get(key(c)) != c["gold"][0])
                p = mcnemar_p(only_a, only_b)
                verdict = "a real difference" if p < 0.05 else "NOT distinguishable from noise"
                print(f"  {a} right & {b} wrong: {only_a}   {b} right & {a} wrong: {only_b}   "
                      f"McNemar p = {p:.4g} -> {verdict}")

    print("\nDevelopment split: never call any number above \"the benchmark\".")


if __name__ == "__main__":
    main()
