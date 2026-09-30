#!/usr/bin/env python3
"""Stock Laya vs the Loupe stack, on identical inputs (docs/STOCK-VS-LOUPE-MEASURE.md).

The Loupe half is `StockVsLoupeMeasurementTest` (loupe-desktop), which writes every labelled record
(state text exactly as `DecisionEngine` sends it, question, options, label) plus the Loupe arms'
probabilities to `measure-results/stock-vs-loupe/records.jsonl`. This script:

  run    runs STOCK -- the authors' own `laya` package (0.3.20), fp32 torch on CPU, the
         checkpoint's own settings (temperature 1), the package's own prompt building, score levels
         in written order, no calibration -- on exactly those records, via
         `laya.load(path, device="cpu").predict(state, questions)`. It also runs one ablation,
         STOCK-AS-CHOICE: the same fp32 torch model, but every question sent as a `choice` with
         Loupe's own options in written order, which is what Loupe's graph does (its qtype is fixed
         to choice). Writes stock.jsonl.
  score  scores every arm on every dataset and judgment and writes results.json + tables.md.

Nothing here is tuned: no threshold, wording, label order or temperature is chosen to move a number.

    USE_TF=0 tools/.venv/bin/python tools/measure-stock-vs-loupe.py run
    tools/.venv/bin/python tools/measure-stock-vs-loupe.py score
"""
import argparse
import json
import os
import platform
import sys
import time
from collections import Counter, OrderedDict, defaultdict

os.environ.setdefault("USE_TF", "0")

import numpy as np  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_DIR = os.path.join(ROOT, "measure-results", "stock-vs-loupe")
DEFAULT_CKPT = os.path.join(ROOT, "models", "laya-multilingual")
SEED = 20260930
RESAMPLES = 10_000
BINS = 10  # Loupe's Calibration.DEFAULT_BINS; used for every arm so the ECE figures compare.

ARMS = ["stock", "loupe", "loupe_cal", "loupe_fp32", "stock_as_choice", "loupe_norev"]


def read_jsonl(path):
    with open(path, encoding="utf-8") as f:
        return [json.loads(line) for line in f if line.strip()]


def key(r):
    return "%s|%s|%s" % (r["dataset"], r["question_id"], r["item_id"])


def render(label, desc):
    """Upstream `render_options` for one choice option: the label, or `label: description`."""
    return label if desc in (None, "") else "%s: %s" % (label, desc)


# ------------------------------------------------------------------------------------ question mapping

def stock_question(r):
    """The record as a laya question, in the package's own type for its shape.

    yes/no (bare or with descriptive options) -> `noul`, as the authors' presets write yes/no
    questions; a score -> `score`, levels in written order (index 0 first), each level's band as its
    criterion; a pick or a Fast Decisions label set -> `choice`, options in written order.
    Returns (question dict, function mapping the answer to probabilities in Loupe's option order).
    """
    opts, descs, shape = r["options"], r["descriptions"], r["shape"]
    if shape in ("yesno", "binary"):
        pos = r["positive"]
        assert opts[0] == pos and len(opts) == 2, key(r)
        if shape == "yesno":
            # The labels are literally yes/no, which is what noul's true/false already say;
            # only a description (criteria in prompt) is extra text.
            crit = {k: v for k, v in (("true", descs[0]), ("false", descs[1])) if v not in (None, "")}
        else:
            crit = {"true": render(opts[0], descs[0]), "false": render(opts[1], descs[1])}
        q = {"type": "noul", "instructions": r["instructions"]}
        if crit:
            q["criteria"] = crit
        return q, lambda a: [a["noul"], 1.0 - a["noul"]]
    if shape == "score":
        crit = [d if d not in (None, "") else o for o, d in zip(opts, descs)]
        q = {"type": "score", "instructions": r["instructions"], "criteria": crit}
        return q, lambda a: [a["probabilities"][str(i)] for i in range(len(opts))]
    return choice_question(r)


def choice_question(r):
    """The record exactly as Loupe's graph reads it: a `choice`, options in written order."""
    opts, descs = r["options"], r["descriptions"]
    crit = OrderedDict((o, d if d not in (None, "") else None) for o, d in zip(opts, descs))
    q = {"type": "choice", "instructions": r["instructions"], "criteria": crit}
    return q, lambda a: [a["probabilities"][o] for o in opts]


# ------------------------------------------------------------------------------------------- run

def unrounded(agent):
    """laya rounds reported probabilities to 4 places; keep the full values for ECE/Brier.

    `_decode_answers` is the package's own softmax; this wraps it and recomputes nothing, it only
    stops the rounding by running the same arithmetic on the logits it was given.
    """
    import types

    from laya.common import QTYPES, temp_bucket

    original = agent._decode_answers

    def decode(self, logits, act, items, ids, internal, offset, lang=None):
        answers = original(logits, act, items, ids, internal, offset, **({"lang": lang} if lang else {}))
        for j, qid in enumerate(ids):
            r = offset + j
            q = internal[qid]
            k = len(items[j]["markers"])
            qt = QTYPES[q["t"]]
            t_scale = self.temperature_by_options.get(temp_bucket(qt, k), self.temperature[qt])
            z = logits[r, :k] / t_scale
            p = np.exp(z - z.max())
            p = p / p.sum()
            a = answers[qid]
            if q["t"] == "choice":
                a["probabilities"] = {kk: float(v) for kk, v in zip(list(q["crit"].keys()), p)}
            elif q["t"] == "score":
                a["probabilities"] = {str(i): float(v) for i, v in enumerate(p)}
            else:
                a["noul"] = float(p[1])
        return answers

    agent._decode_answers = types.MethodType(decode, agent)


def cmd_run(args):
    import torch

    import laya

    recs = read_jsonl(os.path.join(args.dir, "records.jsonl"))
    out_path = os.path.join(args.dir, "stock.jsonl")
    done = {}
    if os.path.exists(out_path) and not args.fresh:
        done = {row["key"]: row for row in read_jsonl(out_path)}
    agent = laya.load(args.checkpoint, device=args.device)
    assert agent.device.type == args.device, agent.device
    assert agent.dtype == torch.float32 and not agent.amp_enabled, (agent.dtype, agent.amp_enabled)
    assert list(agent.temperature) == [1.0, 1.0, 1.0] and not agent.temperature_by_options, agent.temperature
    unrounded(agent)
    meta = {
        "laya": laya.__version__, "torch": torch.__version__, "device": str(agent.device),
        "dtype": str(agent.dtype), "amp": agent.amp_enabled, "threads": torch.get_num_threads(),
        "machine": platform.machine(), "python": platform.python_version(), "checkpoint": args.checkpoint,
    }
    print(json.dumps(meta), flush=True)

    def call(state, q):
        t0 = time.perf_counter()
        ans = agent.predict(state, {"q": q})["answers"]["q"]
        return ans, (time.perf_counter() - t0) * 1000.0

    for r in recs[:5]:  # warm-up, not timed
        call(r["state"], stock_question(r)[0])

    with open(out_path, "a" if done else "w", encoding="utf-8") as f:
        for i, r in enumerate(recs):
            k = key(r)
            if k in done:
                continue
            q, to_probs = stock_question(r)
            ans, ms = call(r["state"], q)
            row = {"key": k, "stock": {"probs": to_probs(ans), "ms": ms, "type": q["type"]}}
            if q["type"] != "choice":
                cq, c_probs = choice_question(r)
                cans, cms = call(r["state"], cq)
                row["stock_as_choice"] = {"probs": c_probs(cans), "ms": cms}
            f.write(json.dumps(row) + "\n")
            if i % 200 == 0:
                f.flush()
                print("%d/%d" % (i, len(recs)), flush=True)
    with open(os.path.join(args.dir, "stock-meta.json"), "w") as f:
        json.dump(meta, f, indent=2)
    print("wrote", out_path)


# ----------------------------------------------------------------------------------------- score

def argmax(p):
    return int(np.argmax(p))  # first maximum, as Loupe's Distribution.argmax breaks ties by option order


def ece(confs, correct):
    """Loupe's `Calibration.ece`: 10 equal-width bins on the top mass, size-weighted |conf - acc|."""
    n = len(confs)
    if n == 0:
        return float("nan")
    bins = defaultdict(list)
    for c, ok in zip(confs, correct):
        bins[min(int(c * BINS), BINS - 1)].append((c, ok))
    return sum(len(b) / n * abs(np.mean([c for c, _ in b]) - np.mean([ok for _, ok in b])) for b in bins.values())


def brier(probs, labels_idx):
    return float(np.mean([sum((p[i] - (1.0 if i == y else 0.0)) ** 2 for i in range(len(p))) for p, y in zip(probs, labels_idx)]))


def metrics(rows, arm):
    probs = [np.asarray(r[arm]["probs"], dtype=float) for r in rows]
    y = [r["options"].index(r["label"]) for r in rows]
    pred = [argmax(p) for p in probs]
    correct = [int(a == b) for a, b in zip(pred, y)]
    conf = [float(p.max()) for p in probs]
    cut = [r["state_budget_cut"] or r.get("model_context_cut", False) for r in rows]
    abstain = [c < r["threshold"] or (ct and r["on_failure"] != "OPEN") for c, r, ct in zip(conf, rows, cut)]
    acted = [ok for ok, ab in zip(correct, abstain) if not ab]
    ms = [r[arm]["ms"] for r in rows if "ms" in r[arm]]
    return {
        "n": len(rows),
        "correct": int(sum(correct)),
        "acc": float(np.mean(correct)),
        "ece": ece(conf, correct),
        "brier": brier(probs, y),
        "abstention": float(np.mean(abstain)),
        "selective_acc": float(np.mean(acted)) if acted else None,
        "latency_p50_ms": float(np.percentile(ms, 50)) if ms else None,
        "latency_p95_ms": float(np.percentile(ms, 95)) if ms else None,
        "pred": pred,
        "correct_vec": correct,
    }


def floors(rows):
    """Majority-class floor (oracle: reads this split's labels, per judgment) and the keyword baseline."""
    by_q = defaultdict(list)
    for r in rows:
        by_q[r["question_id"]].append(r)
    maj = 0
    for qrows in by_q.values():
        counts = Counter(r["label"] for r in qrows)
        top = max(counts.values())
        maj += top
    base = [r for r in rows if r.get("baseline") is not None]
    return {
        "majority_acc": maj / len(rows),
        "baseline_n": len(base),
        "baseline_acc": (sum(r["baseline"] == r["label"] for r in base) / len(base)) if base else None,
    }


def bootstrap_diff(rows, a_vec, b_vec, cluster=True):
    """Paired bootstrap of acc(a) - acc(b); resamples items (all of an item's questions together)."""
    rng = np.random.default_rng(SEED)
    a, b = np.asarray(a_vec, float), np.asarray(b_vec, float)
    groups = defaultdict(list)
    for i, r in enumerate(rows):
        groups[r["item_id"] if cluster else i].append(i)
    idx = list(groups.values())
    sa = np.array([a[g].sum() for g in idx])
    sb = np.array([b[g].sum() for g in idx])
    cnt = np.array([len(g) for g in idx])
    m = len(idx)
    draws = rng.integers(0, m, size=(RESAMPLES, m))
    d = (sa[draws].sum(1) - sb[draws].sum(1)) / cnt[draws].sum(1)
    lo, hi = np.percentile(d, [2.5, 97.5])
    return float(a.mean() - b.mean()), float(lo), float(hi)


def compare(rows, arms_present):
    res = {"n": len(rows), "floors": floors(rows), "arms": {}}
    for arm in arms_present:
        sub = [r for r in rows if arm in r]
        if len(sub) != len(rows):
            continue
        res["arms"][arm] = metrics(rows, arm)
    s, l = res["arms"].get("stock"), res["arms"].get("loupe")
    if s and l:
        differ = [i for i in range(len(rows)) if s["pred"][i] != l["pred"][i]]
        res["agreement"] = {
            "same": len(rows) - len(differ),
            "differ": len(differ),
            "differ_stock_right": sum(s["correct_vec"][i] for i in differ),
            "differ_loupe_right": sum(l["correct_vec"][i] for i in differ),
        }
        res["delta_loupe_minus_stock"] = bootstrap_diff(rows, l["correct_vec"], s["correct_vec"])
    for arm, other in (("loupe_fp32", "loupe"), ("stock_as_choice", "stock")):
        if arm in res["arms"] and other in res["arms"]:
            x, y = res["arms"][arm], res["arms"][other]
            res["delta_%s_minus_%s" % (other, arm)] = bootstrap_diff(rows, y["correct_vec"], x["correct_vec"])
            res["changed_%s_vs_%s" % (other, arm)] = sum(p != q for p, q in zip(x["pred"], y["pred"]))
    if "stock_as_choice" in res["arms"] and "loupe_fp32" in res["arms"]:
        x, y = res["arms"]["stock_as_choice"], res["arms"]["loupe_fp32"]
        res["changed_loupe_fp32_vs_stock_as_choice"] = sum(p != q for p, q in zip(x["pred"], y["pred"]))
        res["max_abs_prob_diff_loupe_fp32_vs_stock_as_choice"] = max(
            float(np.max(np.abs(np.asarray(r["loupe_fp32"]["probs"]) - np.asarray(r["stock_as_choice"]["probs"])))) for r in rows)
    if "loupe_norev" in res["arms"]:
        res["changed_norev"] = sum(p != q for p, q in zip(res["arms"]["loupe"]["pred"], res["arms"]["loupe_norev"]["pred"]))
    for arm in res["arms"].values():
        arm.pop("pred"), arm.pop("correct_vec")
    return res


def fast_suite(rows, arm):
    by_domain = defaultdict(list)
    for r in rows:
        by_domain[r["question_id"].split(".")[0]].append(r)
    per = {}
    for d, rs in by_domain.items():
        per[d] = float(np.mean([argmax(r[arm]["probs"]) == r["options"].index(r["label"]) for r in rs]))
    pooled = float(np.mean([argmax(r[arm]["probs"]) == r["options"].index(r["label"]) for r in rows]))
    return {"average": float(np.mean(list(per.values()))), "pooled": pooled, "per_domain": per}


def fmt_pct(x):
    return "—" if x is None else "%.1f%%" % (100 * x)


def cmd_score(args):
    recs = {key(r): r for r in read_jsonl(os.path.join(args.dir, "records.jsonl"))}
    for row in read_jsonl(os.path.join(args.dir, "stock.jsonl")):
        r = recs[row["key"]]
        r["stock"] = row["stock"]
        r["stock_type"] = row["stock"]["type"]
        # Where STOCK is already a choice, STOCK-AS-CHOICE is the same call; reuse it.
        r["stock_as_choice"] = row.get("stock_as_choice", row["stock"])
    rows = [r for r in recs.values() if "stock" in r]
    missing = len(recs) - len(rows)
    datasets = OrderedDict((d, [r for r in rows if r["dataset"] == d]) for d in ("sample", "templates", "fast"))
    out = {"missing_stock": missing, "datasets": {}, "judgments": {}, "fast_suite": {}, "loupe_heads": {}, "score_questions": {}}
    for d, rs in datasets.items():
        if not rs:
            continue
        out["datasets"][d] = compare(rs, ARMS)
        by_q = OrderedDict()
        for r in rs:
            by_q.setdefault(r["question_id"], []).append(r)
        out["judgments"][d] = {q: compare(qr, ARMS) for q, qr in by_q.items()}
        for q, qr in by_q.items():
            if qr[0]["ordinal"]:
                out["score_questions"]["%s/%s" % (d, q)] = compare(qr, ARMS)
    fast = datasets.get("fast") or []
    if fast:
        for arm in ("stock", "loupe", "loupe_fp32"):
            out["fast_suite"][arm] = fast_suite(fast, arm)
        for h in ("email_triage.is_phishing", "ticket_route.contains_pii"):
            out["loupe_heads"][h] = out["judgments"]["fast"][h]
    meta_path = os.path.join(args.dir, "stock-meta.json")
    if os.path.exists(meta_path):
        out["stock_meta"] = json.load(open(meta_path))
    with open(os.path.join(args.dir, "results.json"), "w") as f:
        json.dump(out, f, indent=2)
    write_tables(out, os.path.join(args.dir, "tables.md"))
    print(open(os.path.join(args.dir, "tables.md")).read())


def delta(res):
    d = res.get("delta_loupe_minus_stock")
    return "—" if d is None else "%+.1f pts [%+.1f, %+.1f]" % (100 * d[0], 100 * d[1], 100 * d[2])


def row_line(name, res):
    a, f = res["arms"], res["floors"]
    s, l, c = a["stock"], a["loupe"], a.get("loupe_cal")
    ag = res["agreement"]
    return "| %s | %d | %d/%d (%s) | %d/%d (%s) | %s | %.3f | %.3f | %s | %d of %d differ (stock right %d, Loupe right %d) | %s | %s |" % (
        name, res["n"], s["correct"], s["n"], fmt_pct(s["acc"]), l["correct"], l["n"], fmt_pct(l["acc"]), delta(res),
        s["ece"], l["ece"], "%.3f" % c["ece"] if c else "—", ag["differ"], res["n"], ag["differ_stock_right"],
        ag["differ_loupe_right"], fmt_pct(f["majority_acc"]), fmt_pct(f["baseline_acc"]) if f["baseline_n"] == res["n"] else
        ("%s (on %d)" % (fmt_pct(f["baseline_acc"]), f["baseline_n"]) if f["baseline_n"] else "—"))


HEAD = ("| %s | n | STOCK acc | LOUPE acc | Δ LOUPE − STOCK (95%% CI) | ECE stock | ECE Loupe | ECE Loupe+cal | answers differ | majority floor | keyword baseline |\n"
        "|---|---|---|---|---|---|---|---|---|---|---|")


def write_tables(out, path):
    L = []
    L.append("## Headline\n")
    L.append(HEAD % "dataset")
    names = {"sample": "Hand-labelled sample (synthetic)", "templates": "Template examples", "fast": "Fast Decisions dev split (single-label heads)"}
    for d, res in out["datasets"].items():
        L.append(row_line(names[d], res))
    if out["loupe_heads"]:
        L.append("\n## Loupe heads (Fast Decisions dev split)\n")
        L.append(HEAD % "head")
        for h, res in out["loupe_heads"].items():
            L.append(row_line("`%s`" % h, res))
    L.append("\n## Brier, abstention, latency per dataset\n")
    L.append("| dataset | arm | Brier | abstention at the judgment's threshold | selective acc | p50 ms | p95 ms |\n|---|---|---|---|---|---|---|")
    for d, res in out["datasets"].items():
        for arm in ("stock", "loupe", "loupe_cal", "loupe_fp32", "stock_as_choice"):
            m = res["arms"].get(arm)
            if not m:
                continue
            L.append("| %s | %s | %.3f | %s | %s | %s | %s |" % (d, arm, m["brier"], fmt_pct(m["abstention"]), fmt_pct(m["selective_acc"]),
                     "—" if m["latency_p50_ms"] is None else "%.1f" % m["latency_p50_ms"],
                     "—" if m["latency_p95_ms"] is None else "%.1f" % m["latency_p95_ms"]))
    for d, js in out["judgments"].items():
        L.append("\n## Per judgment: %s\n" % names[d])
        L.append(HEAD % "judgment")
        for q, res in js.items():
            L.append(row_line("`%s`" % q, res))
    L.append("\n## Ablations per dataset\n")
    L.append("| dataset | int8 → fp32 (Loupe prompt): answers changed | acc Loupe int8 − Loupe fp32 (95% CI) | STOCK native types vs as-choice: answers changed | acc STOCK − STOCK-as-choice (95% CI) | Loupe fp32 ONNX vs STOCK-as-choice torch: answers changed, max |Δp| |\n|---|---|---|---|---|---|")
    for d, res in out["datasets"].items():
        a = res.get("delta_loupe_minus_loupe_fp32")
        b = res.get("delta_stock_minus_stock_as_choice")
        f = lambda x: "—" if x is None else "%+.1f pts [%+.1f, %+.1f]" % tuple(100 * v for v in x)
        L.append("| %s | %s | %s | %s | %s | %s, %s |" % (d, res.get("changed_loupe_vs_loupe_fp32", "—"), f(a),
                 res.get("changed_stock_vs_stock_as_choice", "—"), f(b), res.get("changed_loupe_fp32_vs_stock_as_choice", "—"),
                 "%.2g" % res["max_abs_prob_diff_loupe_fp32_vs_stock_as_choice"] if "max_abs_prob_diff_loupe_fp32_vs_stock_as_choice" in res else "—"))
    if out["score_questions"]:
        L.append("\n## Score questions: reversal on (shipped) vs off\n")
        L.append("| question | n | Loupe (reversed) acc | Loupe reversal off acc | answers changed | STOCK (score type, written order) acc |\n|---|---|---|---|---|---|")
        for q, res in out["score_questions"].items():
            a = res["arms"]
            nr = a.get("loupe_norev")
            L.append("| `%s` | %d | %d/%d | %s | %s | %d/%d |" % (q, res["n"], a["loupe"]["correct"], res["n"],
                     "%d/%d" % (nr["correct"], res["n"]) if nr else "—", res.get("changed_norev", "—"), a["stock"]["correct"], res["n"]))
    if out["fast_suite"]:
        L.append("\n## Fast Decisions suite (development split, single-label heads)\n")
        L.append("| arm | average of domains | pooled |\n|---|---|---|")
        for arm, s in out["fast_suite"].items():
            L.append("| %s | %s | %s |" % (arm, fmt_pct(s["average"]), fmt_pct(s["pooled"])))
    if out["missing_stock"]:
        L.append("\n**%d records have no STOCK result.**" % out["missing_stock"])
    with open(path, "w") as f:
        f.write("\n".join(L) + "\n")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    r = sub.add_parser("run")
    r.add_argument("--dir", default=DEFAULT_DIR)
    r.add_argument("--checkpoint", default=DEFAULT_CKPT)
    # CPU only: on MPS the package autocasts to fp16, which is not the full-precision arm.
    r.add_argument("--device", default="cpu", choices=["cpu"])
    r.add_argument("--fresh", action="store_true", help="ignore an existing stock.jsonl")
    s = sub.add_parser("score")
    s.add_argument("--dir", default=DEFAULT_DIR)
    args = ap.parse_args()
    {"run": cmd_run, "score": cmd_score}[args.cmd](args)


if __name__ == "__main__":
    sys.exit(main())
