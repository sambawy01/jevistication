#!/usr/bin/env python3
"""Kai's half of the model comparison: vllm-sr/Decision-2.0-Kai-0.6B over Fast Decisions.

Writes one prediction per single-label head-instance, in the format FastDecisionsBenchTest.kt
writes for Laya, so tools/bench/score.py marks both with the same code.

  pip install "transformers>=5.17" torch safetensors
  python3 tools/bench/kai_predict.py                    # one question per call (Loupe's mode)
  python3 tools/bench/kai_predict.py --batched          # all of a row's heads in one call
  python3 tools/bench/kai_predict.py --limit 20         # smoke test

Fairness, fixed here rather than left to whoever runs it:
- the same 26 single-label heads Laya answers; multi-label heads are skipped for both;
- the same terse question text (FastDecisions.questionFor) and the same bare labels -- Kai's
  "criteria" with no descriptions, exactly as Loupe asks Laya;
- the answer is the argmax of Kai's own probabilities with first-label tie-break, the rule
  Loupe's Distribution uses -- Kai returns choice=None on an exact tie, which would otherwise
  count as an error that Laya's path cannot make;
- any exception is recorded with no prediction and the scorer counts it wrong.

The model is loaded at a PINNED revision with trust_remote_code: its Python files run on this
machine. Read them at that revision before the first run (docs/HANDOFF-MODEL-BENCH.md, step 2).
"""
import argparse
import json
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import fastdecisions as fd  # noqa: E402

REPO = "vllm-sr/Decision-2.0-Kai-0.6B"
REVISION = "cd49ea3813fd8ba0928a9a23ef6c9a0f2f0cd764"


def question(case: dict) -> dict:
    return {"type": "choice", "instructions": fd.question_for(case["task"]),
            "criteria": {label: None for label in case["labels"]}}


def run(model, cases: list[dict], batched: bool, out, log=print) -> int:
    """Answers [cases] with [model].system_one and writes JSONL to [out]. Returns lines written."""
    single = [c for c in cases if not c["multi"]]
    groups: list[list[dict]] = []
    if batched:
        by_item: dict[str, list[dict]] = {}
        for c in single:
            by_item.setdefault(c["item_id"], []).append(c)
        groups = list(by_item.values())
    else:
        groups = [[c] for c in single]

    written = 0
    for n, group in enumerate(groups):
        questions = {c["task"]: question(c) for c in group}
        started = time.perf_counter()
        try:
            answers = model.system_one(state=group[0]["text"], questions=questions)["answers"]
            error = None
        except Exception as exc:  # recorded, counted wrong by the scorer
            answers, error = {}, f"{type(exc).__name__}: {exc}"
        ms = (time.perf_counter() - started) * 1000.0
        for c in group:
            line = {"model": "kai-0.6b" + ("-batched" if batched else ""), "domain": c["domain"],
                    "item_id": c["item_id"], "task": c["task"], "ms": ms / len(group)}
            answer = answers.get(c["task"]) if isinstance(answers, dict) else None
            probs = (answer or {}).get("probabilities") or {}
            line["predicted"] = fd.argmax(probs, c["labels"])
            if probs:
                line["probabilities"] = probs
            if line["predicted"] is None:
                line["error"] = error or f"no answer for {c['task']}"
            out.write(json.dumps(line, ensure_ascii=False) + "\n")
            written += 1
        if (n + 1) % 200 == 0:
            log(f"  {n + 1}/{len(groups)} calls")
    return written


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--data", default="third-party/fast-decisions")
    ap.add_argument("--out", default=None)
    ap.add_argument("--batched", action="store_true", help="all of a row's heads in one call")
    ap.add_argument("--device", default="auto", help="auto, cpu or mps")
    ap.add_argument("--limit", type=int, default=0, help="only the first N head-instances")
    args = ap.parse_args()

    cases = fd.load(args.data)
    if args.limit:
        cases = cases[: args.limit]
    out_path = Path(args.out or f"bench/out/kai{'-batched' if args.batched else ''}.jsonl")
    out_path.parent.mkdir(parents=True, exist_ok=True)

    import torch
    from transformers import AutoModel

    model = AutoModel.from_pretrained(REPO, revision=REVISION, trust_remote_code=True)
    device = args.device
    if device == "auto":
        device = "mps" if torch.backends.mps.is_available() else "cpu"
    try:
        model = model.to(device)
    except Exception as exc:
        print(f"could not move to {device} ({exc}); staying on the default device")
    print(f"{REPO}@{REVISION[:12]} on {device}; {sum(not c['multi'] for c in cases)} single-label head-instances")
    with out_path.open("w", encoding="utf-8") as out:
        n = run(model, cases, args.batched, out)
    print(f"Kai predictions: {n} lines -> {out_path}")


if __name__ == "__main__":
    main()
