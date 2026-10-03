#!/usr/bin/env python3
"""Tests for the bench tools. No model, no network:  python3 tools/bench/test_bench.py"""
import io
import json
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import fastdecisions as fd  # noqa: E402
import kai_predict  # noqa: E402
import score  # noqa: E402


def synthetic(root: Path) -> None:
    """17 domains x 4 rows; email_triage carries is_phishing, restaurant_review a multi-label head."""
    for d in fd.DOMAINS:
        lines = []
        for r in range(4):
            heads = [{"task": "kind", "true_label": ["alpha" if r % 2 == 0 else "beta"],
                      "labels": ["alpha", "beta", "gamma"], "multi_label": False}]
            if d == "email_triage":
                heads.append({"task": "is_phishing", "true_label": ["yes" if r < 2 else "no"],
                              "labels": ["yes", "no"], "multi_label": False})
            if d == "restaurant_review":
                heads.append({"task": "aspects", "true_label": ["food"],
                              "labels": ["food", "service"], "multi_label": True})
            lines.append(json.dumps({"input": f"row {r} mentions alpha", "output": {"classifications": heads}}))
            if r == 1:
                lines.append("")  # a blank line must not advance the row counter
        (root / f"{d}.jsonl").write_text("\n".join(lines) + "\n", encoding="utf-8")


class FakeKai:
    """Right on even rows, a deliberate exact tie on row 1, an exception on row 3."""

    def system_one(self, state, questions):
        if "row 3" in state:
            raise RuntimeError("over budget")
        out = {}
        for task, q in questions.items():
            labels = list(q["criteria"])
            assert all(v is None for v in q["criteria"].values()), "bare labels, no descriptions"
            assert q["instructions"] == fd.question_for(task)
            if "row 1" in state:
                probs = {l: 1.0 / len(labels) for l in labels}  # exact tie -> choice None upstream
                out[task] = {"type": "choice", "choice": None, "probabilities": probs}
            else:
                probs = {l: (0.9 if l == labels[0] else 0.1 / (len(labels) - 1)) for l in labels}
                out[task] = {"type": "choice", "choice": labels[0], "probabilities": probs}
        return {"answers": out}


def check(cond, msg):
    if not cond:
        raise AssertionError(msg)
    print("  ok -", msg)


def main():
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        data = root / "data"
        data.mkdir()
        synthetic(data)
        cases = fd.load(data)

        check(cases[0]["item_id"] == "support_intent#0", "item ids are <domain>#<row>")
        ids = [c["item_id"] for c in cases if c["domain"] == "support_intent"]
        check(ids == ["support_intent#0", "support_intent#1", "support_intent#2", "support_intent#3"],
              "a blank line does not advance the row counter (the Kotlin parser's rule)")
        check(fd.question_for("is_phishing") == "is phishing?", "the terse question text matches Kotlin")
        check(fd.argmax({"a": 0.5, "b": 0.5}, ["b", "a"]) == "b", "ties break to the first label supplied")

        # Kai runner, one question per call
        buf = io.StringIO()
        n = kai_predict.run(FakeKai(), cases, batched=False, out=buf, log=lambda *_: None)
        single = [c for c in cases if not c["multi"]]
        check(n == len(single), f"one line per single-label head-instance ({n})")
        lines = [json.loads(l) for l in buf.getvalue().splitlines()]
        tie = [l for l in lines if l["item_id"].endswith("#1")]
        check(all(l["predicted"] == "alpha" for l in tie if l["task"] == "kind"),
              "an exact tie is resolved to the first label, not dropped as an error")
        err = [l for l in lines if l["item_id"].endswith("#3")]
        check(all(l["predicted"] is None and "over budget" in l["error"] for l in err),
              "an exception is recorded with no prediction")
        check(not any(l["task"] == "aspects" for l in lines), "multi-label heads are skipped")

        # batched mode makes one call per row
        calls = []

        class Counting(FakeKai):
            def system_one(self, state, questions):
                calls.append(len(questions))
                return super().system_one(state, questions)
        kai_predict.run(Counting(), cases, batched=True, out=io.StringIO(), log=lambda *_: None)
        check(max(calls) == 2, "batched mode asks a row's heads together (email_triage has 2 here)")

        kai_path = root / "kai.jsonl"
        kai_path.write_text(buf.getvalue(), encoding="utf-8")
        # A second model that is always right except it never answers email_triage
        other = [{"model": "laya-int8", "domain": c["domain"], "item_id": c["item_id"], "task": c["task"],
                  "predicted": c["gold"][0], "ms": 30.0} for c in single if c["domain"] != "email_triage"]
        laya_path = root / "laya.jsonl"
        laya_path.write_text("\n".join(json.dumps(o) for o in other) + "\n", encoding="utf-8")

        res = subprocess.run([sys.executable, str(HERE / "score.py"), str(laya_path), str(kai_path),
                              "--data", str(data), "--skip-floor-check"], capture_output=True, text=True)
        print(res.stdout[-1800:])
        check(res.returncode == 0, "the scorer runs on two prediction files")
        check("laya-int8 right & kai-0.6b wrong" in res.stdout, "it prints the paired comparison")
        check("missing 8" in res.stdout, "unanswered head-instances are counted as missing (and wrong)")
        check("errors 18" in res.stdout, "Kai's exceptions are counted as errors (and wrong)")

        res2 = subprocess.run([sys.executable, str(HERE / "score.py"), str(kai_path), "--data", str(data)],
                              capture_output=True, text=True)
        check(res2.returncode != 0 and "FLOORS DO NOT MATCH" in (res2.stdout + res2.stderr),
              "on data that is not Fast Decisions the floor self-check refuses to score")

    check(score.mcnemar_p(0, 0) == 1.0, "McNemar with no disagreements is p = 1")
    check(score.mcnemar_p(30, 5) < 0.001, "McNemar sees a 30-vs-5 split as real")
    check(score.mcnemar_p(10, 9) > 0.5, "McNemar sees a 10-vs-9 split as noise")
    print("all bench tests passed")


if __name__ == "__main__":
    main()
