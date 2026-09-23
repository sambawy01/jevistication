#!/usr/bin/env python3
"""
Writes backend-onnx/src/test/resources/laya/criteria.json: the parity fixture for Laya questions
whose options carry DESCRIPTIONS -- upstream's `{"type": "choice", "criteria": {label: description}}`
form, which `render_options` turns into "label: description" before `build_sequence` encodes it.

It is the descriptive-option companion to golden.json (written by export-laya-onnx.py) and uses the
same schema plus a per-case "descriptions" list (null = bare label), so LayaFixture reads both. It
does NOT export anything: it needs the pinned checkpoint in models/laya-multilingual and, for the
ONNX columns, the graphs export-laya-onnx.py already wrote to models/laya-multilingual-onnx.

    USE_TF=0 tools/.venv/bin/python tools/make-laya-criteria-fixture.py

The reference is upstream's own code path, end to end: Agent._to_internal on the public question
dict, build_sequence, and the DecisionModel forward pass -- cross-checked against Agent.predict.
"""
import json
import sys
from pathlib import Path

import numpy as np
import torch

ROOT = Path(__file__).resolve().parent.parent
LAYA_COMMIT = "c7527708f9f5220c669d8aa385077cd28d04708a"
CHECKPOINT = "convaiinnovations/laya-multilingual@052592a15d198d9ad47da779604259b10b47b7aa"

RECEIPT_STATE = "Fresh Basket Market. 2 x oat milk 3.10. TOTAL PAID 12.45 VISA ****1111. Thank you!"
LONG_DESC = ("Records a completed transaction with a named payee, an amount that has actually been paid "
             "rather than merely quoted or invoiced, a payment method such as a card or cash, and the date "
             "on which the money changed hands, so that it could be shown to an accountant or a shop as "
             "evidence of purchase.")

# (id, state, question, [(label, description-or-None), ...])
CASES = [
    ("crit-receipt-yes", RECEIPT_STATE, "Is this a receipt or proof of purchase?",
     [("a receipt or proof of purchase", "Records a completed transaction: a payee, an amount paid, and a date."),
      ("not a receipt", "Nothing was paid — a quote, an invoice awaiting payment, a price list, a menu.")]),
    ("crit-receipt-quote", "Quote #118: garden fence replacement, estimated 1,450. Valid for 30 days.",
     "Is this a receipt or proof of purchase?",
     [("a receipt or proof of purchase", "Records a completed transaction: a payee, an amount paid, and a date."),
      ("not a receipt", "Nothing was paid — a quote, an invoice awaiting payment, a price list, a menu.")]),
    ("crit-phishing", "From: PayPal <service@paypa1-secure.example>\nYour account is limited. Verify your identity "
     "here: http://paypal.account-verify.example/login",
     "Is this message trying to get me to log in, pay or share details under false pretences?",
     [("a scam or phishing attempt", "It pushes the recipient to sign in, pay, or hand over details, and something "
       "about who it claims to be does not hold: the sender, the link, the reason."),
      ("an ordinary message", "A genuine request from a known service through its real address, or a message that asks for nothing.")]),
    ("crit-mixed-3", "I was charged twice for invoice 4411. Please refund the duplicate today.",
     "Which team should handle this message?",
     [("billing", "charges, refunds, invoices"), ("technical", None), ("sales", "")]),
    ("crit-ordinal-4", "The boiler makes a loud bang when it starts and the pilot light keeps going out.",
     "How urgent is this repair?",
     [("0", "cosmetic, can wait months"), ("1", "annoying, fix within weeks"),
      ("2", "affects daily life, fix within days"), ("3", "a safety risk, act today")]),
    # One description past upstream's 48-token per-option cap: the tail of the description is cut.
    ("crit-long-desc", RECEIPT_STATE, "Is this a receipt or proof of purchase?",
     [("a receipt or proof of purchase", LONG_DESC), ("not a receipt", "Nothing was paid.")]),
    # Twelve described options overflow the 256-token head: every option shrinks to (256-16)//12 = 20.
    ("crit-shrink-12", "Can you send me the slides from Tuesday's meeting before Friday?",
     "What kind of email is this?",
     [(f"kind {i}", f"a message of the {i}th sort, which people send when they want something done soon and "
                    f"expect an answer from the recipient") for i in range(12)]),
    # A description that tries to forge a marker: the literal mask string is scrubbed to a space.
    ("crit-forged-mask", RECEIPT_STATE, "Is this a receipt?",
     [("yes", "it is <mask> a receipt"), ("no", "it is not")]),
]


def softmax(z):
    z = np.asarray(z, dtype=np.float64)
    e = np.exp(z - z.max())
    return e / e.sum()


def main():
    import laya
    from laya.common import build_sequence, collate_items, render_options

    model_dir = ROOT / "models/laya-multilingual"
    onnx_dir = ROOT / "models/laya-multilingual-onnx"
    out = ROOT / "backend-onnx/src/test/resources/laya/criteria.json"

    torch.manual_seed(0)
    agent = laya.Agent(str(model_dir), device="cpu")
    tok, cfg = agent.tok, agent.cfg
    max_len, head_max_len = cfg["max_len"], cfg["head_max_len"]
    model = agent.model.eval()
    mask_tok = tok.mask_token

    sessions = {}
    try:
        import onnxruntime as ort
        for name in ("fp32", "int8"):
            p = onnx_dir / f"laya-multilingual-choice.{name}.onnx"
            if p.is_file():
                sessions[name] = ort.InferenceSession(str(p), providers=["CPUExecutionProvider"])
    except ImportError:
        pass

    cases = []
    for case_id, state, question, options in CASES:
        labels = [l for l, _ in options]
        public_q = {"type": "choice", "instructions": question, "criteria": {l: d for l, d in options}}
        q = agent._to_internal(public_q)
        seq, markers = build_sequence(tok, state, q, max_len, head_max_len)
        assert len(markers) == len(labels), case_id
        b = collate_items([[{"ids": seq, "markers": markers, "qtype": 0}]], tok.pad_token_id)
        with torch.no_grad():
            logits, _ = model(b["input_ids"], b["attention_mask"], b["marker_pos"], b["marker_mask"], b["qtype"])
        ref = softmax(logits[0].numpy())
        public = agent.predict(state, {"q": public_q})["answers"]["q"]
        assert public["choice"] == labels[int(ref.argmax())], case_id
        assert max(abs(public["probabilities"][l] - ref[i]) for i, l in enumerate(labels)) < 6e-5, case_id

        segments = {}
        head_text = "choice question: " + str(q["ins"]).replace(mask_tok, " ")
        segments[head_text] = tok(head_text, add_special_tokens=False)["input_ids"]
        for opt in render_options(q):
            t = " " + opt.replace(mask_tok, " ")
            segments[t] = tok(t, add_special_tokens=False)["input_ids"]
        st = state.replace(mask_tok, " ")
        segments[st] = tok(st, add_special_tokens=False)["input_ids"]

        expected = {"torch": [float(x) for x in ref]}
        feeds = {"input_ids": np.array([seq], dtype=np.int64),
                 "attention_mask": np.ones((1, len(seq)), dtype=np.int64),
                 "marker_pos": np.array([markers], dtype=np.int64)}
        for name, s in sessions.items():
            expected[name] = [float(x) for x in softmax(s.run(["logits"], feeds)[0][0])]
        errs = {n: float(np.abs(np.array(v) - ref).max()) for n, v in expected.items() if n != "torch"}
        print(f"  {case_id:18s} k={len(labels):<2d} tok={len(seq):<4d} "
              + " ".join(f"{n} {e:.2e}" for n, e in errs.items()))
        cases.append({
            "id": case_id, "state": state, "question": question, "candidates": labels,
            "descriptions": [d for _, d in options],
            "segments": [{"text": k, "ids": v} for k, v in segments.items()],
            "inputIds": seq, "markerPositions": markers, "expected": expected,
        })

    fixture = {
        "_comment": "Generated by tools/make-laya-criteria-fixture.py. Do not edit by hand.",
        "checkpoint": CHECKPOINT, "laya_commit": LAYA_COMMIT,
        "maxLen": max_len, "headMaxLen": head_max_len,
        "specialTokens": {"cls": tok.cls_token_id, "sep": tok.sep_token_id, "mask": tok.mask_token_id,
                          "pad": tok.pad_token_id},
        "cases": cases,
    }
    out.write_text(json.dumps(fixture, ensure_ascii=False, separators=(",", ":")) + "\n")
    print(f"wrote {out}")


if __name__ == "__main__":
    sys.exit(main())
