#!/usr/bin/env python3
"""
Experiments towards "a better INT8" for Laya (docs/BUILD.md, hand-off item 2): can the 22
feed-forward output projections (`mlp.Wo`) that tools/export-laya-onnx.py keeps in FP32 be
quantised too, without changing answers?

It works on the FP32 graph export-laya-onnx.py already wrote, and measures every variant against
the committed parity fixtures (backend-onnx/src/test/resources/laya/golden.json, 34 questions, and
criteria.json, 8 questions), whose `expected.torch` probabilities are the authors' own PyTorch
forward pass. No torch is needed here; ORT and onnx only.

Recipes (--recipe, repeatable; default: all):

  baseline   the shipped INT8 (existing file, re-measured under the same conditions)
  smooth     SmoothQuant-style: per-channel scales s_j = max|X_j|^a / max|W_j|^(1-a) for the GeGLU
             product X feeding each Wo. 1/s is folded into the *gate* half of the preceding Wi
             columns (X = gelu(in) * gate, linear in gate, so the fold is exact in FP32) and s into
             Wo's rows. Then ORT dynamic INT8, per-channel weights, nothing excluded.
             Swept over --alphas.
  static     Wo as static QDQ (per-channel INT8 weights, activations calibrated on the calibration
             set, per-tensor: ORT's static quantiser has no per-channel activation scales), on top
             of the smoothed graph and on top of the unsmoothed one; the rest dynamic as shipped.
  sweep      per-layer sensitivity: shipped recipe plus ONE Wo layer quantised, for each of 22
             layers; then greedily add layers in order of least damage while the acceptance holds.

Calibration set (for smoothing scales and static calibration): every text-like file in the sample
data (sources-desktop/.../sample: .eml, .mbox messages, .md, .csv, .txt, .html, .json), each asked
three questions the app ships (receipt / needs a reply / phishing), plus the 34 parity prompts'
own sequences. Order is fixed (sorted paths); nothing is random, the seed below is recorded for
completeness. Using the parity prompts in calibration is deliberate (owner's brief) and noted in
docs: it flatters the static recipe slightly.

Acceptance (owner's brief): selected answers agree with FP32 on at least as many parity questions
as the shipped INT8, max probability difference not meaningfully worse, smaller file.

Run from the repo root:  USE_TF=0 tools/.venv/bin/python tools/quantise-laya-wo.py
Outputs go to models/laya-multilingual-onnx/ (gitignored) and tools/laya-wo-report.json.
"""
import argparse
import email
import hashlib
import json
import mailbox
import os
import platform
import sys
import time
from pathlib import Path

import numpy as np

SEED = 0
np.random.seed(SEED)
ROOT = Path(__file__).resolve().parent.parent
ONNX_DIR = ROOT / "models/laya-multilingual-onnx"
FP32 = ONNX_DIR / "laya-multilingual-choice.fp32.onnx"
SHIPPED = ONNX_DIR / "laya-multilingual-choice.int8.onnx"
FIXTURES = [ROOT / "backend-onnx/src/test/resources/laya/golden.json",
            ROOT / "backend-onnx/src/test/resources/laya/criteria.json"]
SAMPLE = ROOT / "sources-desktop/src/main/resources/dev/loupe/sources/sample"
CAL_QUESTIONS = [
    ("Is this a receipt or proof of purchase?", ["a receipt or proof of purchase", "not a receipt"]),
    ("Does this need a reply from me?", ["needs a reply", "no reply needed"]),
    ("Is this a phishing attempt?", ["phishing", "legitimate"]),
]
HIDDEN, INTER, LAYERS = 768, 1152, 22


def sha256(p: Path) -> str:
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for c in iter(lambda: f.read(1 << 20), b""):
            h.update(c)
    return h.hexdigest()


def softmax(z):
    z = np.asarray(z, dtype=np.float64)
    e = np.exp(z - z.max())
    return e / e.sum()


# ------------------------------------------------------------------------------------------ data
def parity_cases():
    out = []
    for f in FIXTURES:
        for c in json.loads(f.read_text())["cases"]:
            out.append({"id": c["id"], "set": f.stem, "ids": c["inputIds"], "markers": c["markerPositions"],
                        "torch": np.array(c["expected"]["torch"])})
    return out


def feeds(ids, markers):
    return {"input_ids": np.array([ids], dtype=np.int64),
            "attention_mask": np.ones((1, len(ids)), dtype=np.int64),
            "marker_pos": np.array([markers], dtype=np.int64)}


def sample_texts():
    texts = []
    for p in sorted(SAMPLE.rglob("*")):
        if not p.is_file():
            continue
        if p.suffix == ".mbox":
            for msg in mailbox.mbox(str(p)):
                texts.append((f"{p.name}:{msg['subject']}", body_of(msg)))
        elif p.suffix == ".eml":
            texts.append((p.name, body_of(email.message_from_bytes(p.read_bytes()))))
        elif p.suffix in {".md", ".csv", ".txt", ".html", ".json"}:
            texts.append((p.name, p.read_text(errors="replace")))
    return [(n, t) for n, t in texts if t.strip()]


def body_of(msg):
    parts = [str(msg.get("subject", ""))]
    for part in msg.walk():
        if part.get_content_type() in ("text/plain", "text/html"):
            payload = part.get_payload(decode=True)
            if payload:
                parts.append(payload.decode(part.get_content_charset() or "utf-8", errors="replace"))
    return "\n".join(parts)


def calibration_feeds(cases):
    """Sample texts x app questions, built with upstream's build_sequence, then the parity prompts."""
    sys.path.insert(0, str(ROOT / "tools"))
    from transformers import AutoTokenizer
    from laya.common import build_sequence
    from laya.agent import Agent

    model_dir = ROOT / "models/laya-multilingual"
    cfg = json.loads((model_dir / "rl_agent_config.json").read_text())
    max_len, head_max_len = cfg["max_len"], cfg["head_max_len"]
    tok = AutoTokenizer.from_pretrained(str(model_dir / "tokenizer"))
    out, names = [], []
    for name, text in sample_texts():
        for ins, cands in CAL_QUESTIONS:
            q = Agent._to_internal({"type": "choice", "instructions": ins, "criteria": cands})
            seq, markers = build_sequence(tok, text, q, max_len, head_max_len)
            out.append(feeds(seq, markers))
            names.append(name)
    for c in cases:
        if c["set"] == "golden":
            out.append(feeds(c["ids"], c["markers"]))
    return out, sorted(set(names))


# ----------------------------------------------------------------------------------------- graph
def load_prepped():
    import onnx

    m = onnx.load(str(FP32))
    del m.graph.value_info[:]  # stale exporter shape hints make quantize_* refuse the graph (Traps)
    return m


def find_ffn(m):
    """Per layer: (wo_node, wi_initialiser_name, product_tensor). Asserts the GeGLU structure."""
    init = {i.name: tuple(i.dims) for i in m.graph.initializer}
    prod = {o: n for n in m.graph.node for o in n.output}
    layers = []
    for n in m.graph.node:
        if n.op_type == "MatMul" and init.get(n.input[1]) == (INTER, HIDDEN):
            mul = prod[n.input[0]]
            assert mul.op_type == "Mul", mul
            gelu_out, gate = mul.input
            split = prod[gate]
            assert split.op_type == "Split" and list(split.output).index(gate) == 1, split
            wi = prod[split.input[0]]
            assert wi.op_type == "MatMul" and init.get(wi.input[1]) == (HIDDEN, 2 * INTER), wi
            layers.append((n, wi.input[1], n.input[0]))
    assert len(layers) == LAYERS, f"expected {LAYERS} mlp.Wo MatMuls, found {len(layers)}"
    return layers


def act_absmax(m, layers, cal):
    """Per-channel max|X| of each Wo input over the calibration set, from the FP32 graph."""
    import onnx
    import onnxruntime as ort

    m2 = onnx.ModelProto()
    m2.CopyFrom(m)
    for _, _, x in layers:
        m2.graph.output.append(onnx.helper.make_tensor_value_info(x, onnx.TensorProto.FLOAT, None))
    tmp = ONNX_DIR / "_probe.onnx"
    onnx.save(m2, str(tmp))
    try:
        s = ort.InferenceSession(str(tmp), providers=["CPUExecutionProvider"])
        names = [x for _, _, x in layers]
        mx = np.zeros((LAYERS, INTER), dtype=np.float32)
        for f in cal:
            for i, a in enumerate(s.run(names, f)):
                mx[i] = np.maximum(mx[i], np.abs(a).reshape(-1, INTER).max(0))
    finally:
        tmp.unlink()
    return mx


def smooth(m, layers, mx, alpha):
    """Returns a copy with 1/s folded into Wi's gate columns and s into Wo's rows. Exact in FP32."""
    import onnx
    from onnx import numpy_helper as nh

    out = onnx.ModelProto()
    out.CopyFrom(m)
    inits = {i.name: i for i in out.graph.initializer}
    for li, (wo, wi_name, _) in enumerate(layers):
        wo_t, wi_t = inits[wo.input[1]], inits[wi_name]
        W = nh.to_array(wo_t).astype(np.float64)       # [INTER, HIDDEN], X @ W
        Wi = nh.to_array(wi_t).astype(np.float64)      # [HIDDEN, 2*INTER]
        wmax = np.abs(W).max(1)
        s = np.power(np.maximum(mx[li], 1e-5), alpha) / np.power(np.maximum(wmax, 1e-5), 1 - alpha)
        s = np.clip(s, 1e-3, 1e3)
        Wi[:, INTER:] /= s[None, :]
        W *= s[:, None]
        wo_t.CopyFrom(nh.from_array(W.astype(np.float32), wo_t.name))
        wi_t.CopyFrom(nh.from_array(Wi.astype(np.float32), wi_t.name))
    return out


def save(m, path):
    import onnx

    onnx.save(m, str(path))
    return path


def dynamic(src, dst, exclude):
    from onnxruntime.quantization import QuantType, quantize_dynamic

    quantize_dynamic(str(src), str(dst), weight_type=QuantType.QInt8, per_channel=True,
                     nodes_to_exclude=list(exclude))


def static_wo(src, dst, wo_names, cal):
    """Wo only, static QDQ, per-channel weights; then the rest dynamic (as shipped)."""
    from onnxruntime.quantization import (CalibrationDataReader, CalibrationMethod, QuantFormat,
                                          QuantType, quantize_static)

    class Reader(CalibrationDataReader):
        def __init__(self):
            self.it = iter(cal)

        def get_next(self):
            return next(self.it, None)

    mid = dst.with_suffix(".qdq-wo.onnx")
    quantize_static(str(src), str(mid), Reader(), quant_format=QuantFormat.QDQ,
                    activation_type=QuantType.QInt8, weight_type=QuantType.QInt8, per_channel=True,
                    nodes_to_quantize=list(wo_names), calibrate_method=CalibrationMethod.MinMax,
                    extra_options={"ActivationSymmetric": True})
    try:
        dynamic(mid, dst, exclude=wo_names)
    finally:
        mid.unlink()
        for p in ONNX_DIR.glob("augmented_model*.onnx"):
            p.unlink()


# ----------------------------------------------------------------------------------------- eval
def evaluate(path, cases, fp32_probs):
    import onnxruntime as ort

    s = ort.InferenceSession(str(path), providers=["CPUExecutionProvider"])
    rows = []
    for c in cases:
        p = softmax(s.run(["logits"], feeds(c["ids"], c["markers"]))[0][0])
        ref = fp32_probs[c["id"]]
        rows.append({"id": c["id"], "set": c["set"], "agrees": bool(p.argmax() == ref.argmax()),
                     "agreesTorch": bool(p.argmax() == c["torch"].argmax()),
                     "maxDiff": float(np.abs(p - ref).max()),
                     "margin": float(np.sort(ref)[-1] - np.sort(ref)[-2])})
    res = {}
    for st in ("golden", "criteria"):
        r = [x for x in rows if x["set"] == st]
        res[st] = {"agree": f"{sum(x['agrees'] for x in r)}/{len(r)}",
                   "flips": [(x["id"], round(x["margin"], 4)) for x in r if not x["agrees"]],
                   "maxDiff": round(max(x["maxDiff"] for x in r), 4),
                   "meanMaxDiff": round(float(np.mean([x["maxDiff"] for x in r])), 4)}
    res["flips"] = sum(not x["agrees"] for x in rows)
    res["maxDiff"] = max(res["golden"]["maxDiff"], res["criteria"]["maxDiff"])
    res["sizeBytes"] = path.stat().st_size
    return res, s


def latency(s, cases, runs=60):
    short = [feeds(c["ids"], c["markers"]) for c in cases if len(c["ids"]) <= 128 and c["set"] == "golden"]
    for f in short[:3]:
        s.run(["logits"], f)
    t = []
    for i in range(runs):
        t0 = time.perf_counter()
        s.run(["logits"], short[i % len(short)])
        t.append((time.perf_counter() - t0) * 1000)
    t.sort()
    return {"p50_ms": round(t[len(t) // 2], 1), "p95_ms": round(t[int(len(t) * .95) - 1], 1),
            "loadavg": [round(x, 2) for x in os.getloadavg()]}


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--recipe", action="append", choices=["baseline", "smooth", "static", "sweep"])
    ap.add_argument("--alphas", default="0.5,0.65,0.8")
    ap.add_argument("--report", default=str(ROOT / "tools/laya-wo-report.json"))
    ap.add_argument("--keep", action="store_true", help="keep every candidate .onnx, not just the best")
    args = ap.parse_args()
    recipes = args.recipe or ["baseline", "smooth", "static", "sweep"]

    import onnx
    import onnxruntime as ort

    cases = parity_cases()
    s32 = ort.InferenceSession(str(FP32), providers=["CPUExecutionProvider"])
    fp32_probs = {c["id"]: softmax(s32.run(["logits"], feeds(c["ids"], c["markers"]))[0][0]) for c in cases}
    del s32
    results = {}

    def record(name, path, extra=None):
        res, sess = evaluate(path, cases, fp32_probs)
        res["latencyShort"] = latency(sess, cases)
        res["sha256"] = sha256(path)
        res.update(extra or {})
        results[name] = res
        print(f"{name:28s} size={res['sizeBytes'] / 1e6:6.1f}MB flips={res['flips']} "
              f"golden={res['golden']['agree']} crit={res['criteria']['agree']} maxDiff={res['maxDiff']:.4f} "
              f"p50={res['latencyShort']['p50_ms']}ms", flush=True)
        return res

    base = record("baseline (shipped int8)", SHIPPED) if "baseline" in recipes else None

    m = load_prepped()
    layers = find_ffn(m)
    wo_names = [wo.name for wo, _, _ in layers]
    prep = save(m, ONNX_DIR / "_prep.onnx")
    cal, cal_names = calibration_feeds(cases)
    print(f"calibration: {len(cal)} sequences ({len(cal_names)} sample texts x {len(CAL_QUESTIONS)} questions + parity prompts)")
    mx = act_absmax(m, layers, cal)
    tmp_files = [prep]
    try:
        if "smooth" in recipes or "static" in recipes:
            for a in [float(x) for x in args.alphas.split(",")]:
                sm = smooth(m, layers, mx, a)
                smp = save(sm, ONNX_DIR / f"_smooth{a}.onnx")
                tmp_files.append(smp)
                # the fold must be exact before quantisation, or the comparison is meaningless
                chk, _ = evaluate(smp, cases, fp32_probs)
                assert chk["flips"] == 0 and chk["maxDiff"] < 1e-4, ("smoothing changed FP32 output", chk)
                if "smooth" in recipes:
                    out = ONNX_DIR / f"laya-multilingual-choice.int8-smooth{a}.onnx"
                    dynamic(smp, out, exclude=[])
                    record(f"smooth a={a} + dynamic", out, {"alpha": a, "woQuantised": LAYERS})
                if "static" in recipes:
                    out = ONNX_DIR / f"laya-multilingual-choice.int8-smooth{a}-static.onnx"
                    static_wo(smp, out, wo_names, cal)
                    record(f"smooth a={a} + static Wo", out, {"alpha": a, "woQuantised": LAYERS})
        if "static" in recipes:
            out = ONNX_DIR / "laya-multilingual-choice.int8-static.onnx"
            static_wo(prep, out, wo_names, cal)
            record("static Wo (no smoothing)", out, {"woQuantised": LAYERS})
        if "sweep" in recipes:
            # Resumable: each layer's result is cached, because 44 quantisations of a 1.3 GB graph
            # on a 16 GB machine can be killed for memory part-way through.
            import gc
            del m
            gc.collect()
            cache = ONNX_DIR / "_sweep-cache.json"
            single = {int(k): v for k, v in json.loads(cache.read_text()).items()} if cache.exists() else {}
            for i, n in enumerate(wo_names):
                if i in single:
                    continue
                out = ONNX_DIR / f"_sweep{i}.onnx"
                dynamic(prep, out, exclude=[x for x in wo_names if x != n])
                r, _ = evaluate(out, cases, fp32_probs)
                out.unlink()
                gc.collect()
                single[i] = {"flips": r["flips"], "maxDiff": r["maxDiff"]}
                cache.write_text(json.dumps(single))
                print(f"  sweep layer {i:2d}: flips={r['flips']} maxDiff={r['maxDiff']:.4f}", flush=True)
            results["sweep_single_layer"] = {i: {"flips": r["flips"], "maxDiff": r["maxDiff"]} for i, r in single.items()}
            order = sorted(single, key=lambda i: (single[i]["flips"], single[i]["maxDiff"]))
            limit_flips = base["flips"] if base else 1
            limit_diff = base["maxDiff"] if base else 0.114
            chosen = []
            for i in order:
                trial = chosen + [i]
                out = ONNX_DIR / "_greedy.onnx"
                dynamic(prep, out, exclude=[wo_names[j] for j in range(LAYERS) if j not in trial])
                r, _ = evaluate(out, cases, fp32_probs)
                ok = r["flips"] <= limit_flips and r["maxDiff"] <= limit_diff
                print(f"  greedy +{i:2d} -> {sorted(trial)} flips={r['flips']} maxDiff={r['maxDiff']:.4f} {'keep' if ok else 'reject'}", flush=True)
                if ok:
                    chosen = trial
                out.unlink()
                gc.collect()
            if chosen:
                out = ONNX_DIR / "laya-multilingual-choice.int8-partial.onnx"
                dynamic(prep, out, exclude=[wo_names[j] for j in range(LAYERS) if j not in chosen])
                record("partial (greedy Wo subset)", out, {"woQuantised": len(chosen), "layers": sorted(chosen)})
    finally:
        for p in tmp_files:
            p.unlink(missing_ok=True)

    report = {
        "fp32": {"path": FP32.name, "sha256": sha256(FP32)},
        "seed": SEED,
        "calibration": {"sequences": len(cal), "sampleTexts": cal_names, "questions": CAL_QUESTIONS,
                        "plus": "the 34 golden.json parity sequences"},
        "versions": {"onnx": onnx.__version__, "onnxruntime": ort.__version__, "numpy": np.__version__,
                     "python": platform.python_version()},
        "machine": f"{platform.platform()} ({os.cpu_count()} cores), ORT CPU, batch 1, short (<=128 tok) golden cases",
        "results": results,
    }
    Path(args.report).write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n")
    print(f"wrote {args.report}")


if __name__ == "__main__":
    main()
