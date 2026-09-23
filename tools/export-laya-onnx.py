#!/usr/bin/env python3
"""
Exports convaiinnovations/laya-multilingual to ONNX for OnnxBackend, and proves the export is
faithful before anything is built on it (A1's first risk in docs/BUILD.md).

What it does, in order:

  1. Fetches the checkpoint at a PINNED Hugging Face revision into models/laya-multilingual/
     (gitignored) and checks every file's SHA-256 against the value recorded below.
  2. Loads it with the Laya authors' own package (`laya.Agent`, installed from GitHub at a pinned
     commit -- see tools/requirements-laya.txt), so the reference forward pass is theirs, not ours.
  3. Exports encoder + decision head as ONE graph that answers ONE Choice question per forward
     pass: FP32, then an INT8 dynamically-quantised copy.
  4. Parity: runs a varied question set (2-10 options, many languages and scripts, short, long
     and over-length state) through PyTorch and both ONNX graphs, reports argmax agreement and
     the maximum absolute probability error.
  5. Writes the golden fixture the Kotlin tests read, and a small JSON report of sizes, export
     time and desktop-CPU latency.

Run it (from the repository root):

    uv venv --python 3.12 tools/.venv
    uv pip install --python tools/.venv/bin/python -r tools/requirements-laya.txt
    USE_TF=0 tools/.venv/bin/python tools/export-laya-onnx.py

## The graph contract

Inputs (all int64):
    input_ids       [batch, seq]      the sequence built by upstream `build_sequence`
    attention_mask  [batch, seq]      1 for real tokens, 0 for padding
    marker_pos      [batch, options]  index into `seq` of each option's <mask> marker, in
                                      option order
Outputs (float32):
    logits          [batch, options]  one raw score per option; softmax over them is the answer
    act_logits      [batch, 2]        the act/escalate head; softmax[...,0] is upstream's
                                      `act_probability`. Exported for completeness, unused by Loupe

Deliberately NOT inputs:
  * `qtype` is fixed to `choice` (0) inside the graph. Loupe's Backend only ever receives a
    Judgment.Choice (Bool and Score route through `asChoice`), and upstream also changes the
    *text* of the prompt with the question type ("choice question:", "level i:" option
    rendering). Letting a caller set the type without the matching formatting would produce a
    silently wrong answer, so the graph offers no way to do it.
  * `marker_mask` is all-true for a single question -- upstream's collate builds it that way when
    one question is scored alone -- so it is constructed inside the graph from `marker_pos`.

Temperature: the checkpoint ships temperature [1, 1, 1] and no per-option buckets, so upstream's
softmax(logits / 1) is exactly softmax(logits). Loupe fits its own calibration (A6) on top.
"""
import argparse
import hashlib
import json
import os
import platform
import statistics
import sys
import time
from pathlib import Path

os.environ.setdefault("USE_TF", "0")  # transformers' TF probe can deadlock model construction
os.environ.setdefault("TOKENIZERS_PARALLELISM", "false")

import numpy as np  # noqa: E402
import torch  # noqa: E402

REPO_ID = "convaiinnovations/laya-multilingual"
# Pinned revision. Re-verify the licence at this hash before shipping (LICENSING.md standing rule).
REVISION = "052592a15d198d9ad47da779604259b10b47b7aa"
# SHA-256 of the LFS objects at REVISION, as reported by the Hub API and re-computed locally.
EXPECTED_SHA256 = {
    "model.safetensors": "9d628fd971b700382ac6f65920a86f149777b2e748e0c955fb3b19695aa8f204",
    "tokenizer/tokenizer.json": "609d8f4c067cd3950f88594c5a802616cea245823836ef5848ee4fc40aab5b6f",
}
FILES = [
    "rl_agent_config.json",
    "model.safetensors",
    "encoder/config.json",
    "tokenizer/tokenizer.json",
    "tokenizer/tokenizer_config.json",
    "README.md",
]
OPSET = 18  # the torch.export exporter's native opset in torch 2.8; ORT 1.20 runs up to 21
ROOT = Path(__file__).resolve().parent.parent


# --------------------------------------------------------------------------------------------
# The parity question set. Varied on purpose: option counts 2..10, Latin, Cyrillic, Arabic,
# Devanagari, CJK, Thai and Hebrew scripts, a one-word state, multi-paragraph states, a state far
# past the 1024-token budget (exercises upstream's truncation), options long enough to hit the
# 48-token per-option cap, and a question containing the literal "<mask>" string.
# --------------------------------------------------------------------------------------------

LONG_EN = (
    "Dear customer, thank you for your order #88213. Your subscription to Premium Cloud Storage "
    "renews automatically on the first of every month. This month you were charged 12.99 EUR. "
    "If you did not authorise this charge please contact our billing department. "
) * 40  # far beyond 1024 tokens: upstream keeps the prefix that fits

MEDIUM_DE = (
    "Sehr geehrte Damen und Herren, hiermit kündige ich meinen Mobilfunkvertrag mit der "
    "Kundennummer 4471-22 fristgerecht zum nächstmöglichen Zeitpunkt. Bitte senden Sie mir eine "
    "schriftliche Bestätigung der Kündigung sowie des Vertragsendes. Die Einzugsermächtigung "
    "widerrufe ich zum selben Datum. Mit freundlichen Grüßen, Anna Schmidt"
)

CASES = [
    ("en-billing-3", "I was charged twice for invoice 4411. Please refund the duplicate today.",
     "Which team should handle this message?", ["billing", "technical", "sales"]),
    ("en-receipt-2", "TOTAL 12.40 VAT 2.07 CARD **** 4411 THANK YOU FOR SHOPPING",
     "Is this a receipt?", ["yes", "no"]),
    ("en-oneword-2", "hello", "Is this message urgent?", ["yes", "no"]),
    ("en-sentiment-5", "The hotel was fine. Room was clean but breakfast was cold and staff rude.",
     "How would the guest rate the stay?", ["1", "2", "3", "4", "5"]),
    ("en-intent-10", "set an alarm for six thirty tomorrow morning please",
     "What does the user want?",
     ["alarm", "weather", "music", "calendar", "email", "news", "timer", "lights", "shopping", "transport"]),
    ("en-doc-6", "PASSPORT United Kingdom of Great Britain Surname SMITH Given names JOHN "
     "Date of expiry 14 MAR 2027 Date of issue 14 MAR 2017",
     "What kind of document is this?", ["passport", "driving licence", "invoice", "bank statement",
                                       "utility bill", "other"]),
    ("en-phish-2", "Your PayPal account has been limited. Verify your identity within 24 hours at "
     "http://paypa1-secure-login.com or your account will be closed.",
     "Is this a phishing attempt?", ["yes", "no"]),
    ("en-long-4", LONG_EN, "What is this email mainly about?",
     ["a subscription charge", "a delivery update", "a password reset", "a job offer"]),
    ("en-longopts-3", "Please move my dentist appointment from Tuesday to Thursday afternoon.",
     "Which action should the assistant take?",
     ["reschedule an existing calendar event to a different day and time, keeping all attendees, "
      "reminders and the original location exactly as they were before the change was requested",
      "create a brand new calendar event at the requested time without touching any existing event "
      "that might already be scheduled on the calendar for the user in that particular week",
      "do nothing"]),
    ("en-masklit-2", "The form asks for the <mask> field again.",
     "Does the text mention a <mask> token?", ["yes", "no"]),
    ("en-reply-3", "Hi Sam, can you send me the Q3 numbers before Friday's board meeting? Thanks, Priya",
     "Does this email need a reply?", ["yes, soon", "yes, eventually", "no"]),
    ("en-spam-2", "CONGRATULATIONS!!! You have WON a FREE iPhone. Click here to claim now!!!",
     "Is this spam?", ["spam", "not spam"]),
    ("de-cancel-4", MEDIUM_DE, "Was möchte die Absenderin?",
     ["Vertrag kündigen", "Tarif wechseln", "Rechnung reklamieren", "Adresse ändern"]),
    ("de-short-2", "Ihre Lieferung kommt morgen zwischen 10 und 12 Uhr.",
     "Ist das eine Lieferbenachrichtigung?", ["ja", "nein"]),
    ("fr-support-4", "Bonjour, mon application plante à chaque fois que j'ouvre l'appareil photo "
     "depuis la dernière mise à jour. Pouvez-vous m'aider ?",
     "Quel service doit traiter ce message ?", ["facturation", "technique", "ventes", "juridique"]),
    ("es-urgent-3", "URGENTE: el servidor de producción está caído desde las 3 de la mañana y los "
     "clientes no pueden pagar.", "¿Qué prioridad tiene este incidente?", ["alta", "media", "baja"]),
    ("pt-receipt-2", "NOTA FISCAL - Supermercado Bom Preço - TOTAL R$ 87,35 - Pagamento: débito",
     "Isto é um recibo?", ["sim", "não"]),
    ("it-topic-5", "La Juventus ha battuto il Milan per 2 a 1 nella partita di ieri sera a Torino.",
     "Di che argomento parla il testo?", ["sport", "politica", "economia", "tecnologia", "cultura"]),
    ("nl-intent-3", "Kun je de verwarming in de woonkamer op twintig graden zetten?",
     "Wat wil de gebruiker?", ["thermostaat", "verlichting", "muziek"]),
    ("ru-billing-3", "Здравствуйте, с моей карты дважды списали оплату за подписку. Верните деньги.",
     "Какой отдел должен обработать это сообщение?", ["биллинг", "техподдержка", "продажи"]),
    ("pl-sentiment-3", "Produkt przyszedł uszkodzony i nikt nie odpowiada na moje maile.",
     "Jaki jest wydźwięk opinii?", ["pozytywny", "neutralny", "negatywny"]),
    ("tr-2", "Siparişiniz kargoya verildi ve yarın teslim edilecek.",
     "Bu bir teslimat bildirimi mi?", ["evet", "hayır"]),
    ("ar-intent-4", "أريد إلغاء اشتراكي في الخدمة واسترداد المبلغ المدفوع هذا الشهر",
     "ماذا يريد العميل؟", ["إلغاء الاشتراك", "ترقية الخطة", "الإبلاغ عن خطأ", "سؤال عام"]),
    ("he-2", "הפגישה שלנו נדחתה ליום חמישי בשעה עשר בבוקר.",
     "האם ההודעה עוסקת בשינוי מועד?", ["כן", "לא"]),
    ("hi-billing-3", "मुझसे इनवॉइस 4411 के लिए दो बार शुल्क लिया गया। कृपया आज ही धनवापसी करें।",
     "इस संदेश को कौन सी टीम संभालेगी?", ["billing", "technical", "sales"]),
    ("ja-2", "二重に請求されました。返金をお願いします。", "これは請求に関する問い合わせですか？", ["はい", "いいえ"]),
    ("zh-topic-4", "央行今天宣布下调基准利率零点二五个百分点，以刺激经济增长。",
     "这段文字的主题是什么？", ["经济", "体育", "娱乐", "科技"]),
    ("ko-intent-3", "내일 아침 일곱 시에 알람 맞춰 줘", "사용자가 원하는 것은 무엇입니까?",
     ["알람", "날씨", "음악"]),
    ("th-2", "พัสดุของคุณถูกจัดส่งแล้วและจะถึงภายในสองวัน", "นี่คือการแจ้งเตือนการจัดส่งหรือไม่",
     ["ใช่", "ไม่ใช่"]),
    ("vi-3", "Tôi muốn đổi mật khẩu vì không đăng nhập được vào tài khoản.",
     "Người dùng cần gì?", ["đặt lại mật khẩu", "hủy tài khoản", "thanh toán"]),
    ("mixed-8", "Meeting notes: 会議は来週の月曜日に移動しました. Bitte bestätigen. Merci.",
     "Which language dominates the text?",
     ["English", "Japanese", "German", "French", "Spanish", "Chinese", "Korean", "Italian"]),
    ("en-json-4", json.dumps({"from": "noreply@bank-alerts.example", "subject": "Unusual sign-in",
                              "body": "We noticed a sign-in from a new device in Lagos."}),
     "What should happen to this alert?", ["ignore", "notify user", "lock account", "escalate"]),
    ("en-7", "Your electricity bill for August is 84.20 GBP and is due on 15 September.",
     "What kind of document is this?", ["utility bill", "receipt", "payslip", "tax form",
                                       "insurance policy", "bank statement", "other"]),
    ("en-9", "Reminder: your car insurance policy POL-99812 expires on 30 November 2026.",
     "Which category fits best?", ["insurance", "banking", "travel", "health", "housing",
                                   "vehicle", "shopping", "work", "family"]),
]


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def fetch(model_dir: Path) -> None:
    missing = [f for f in FILES if not (model_dir / f).exists()]
    if missing:
        from huggingface_hub import hf_hub_download

        for f in missing:
            print(f"fetching {f} @ {REVISION[:12]}")
            hf_hub_download(REPO_ID, f, revision=REVISION, local_dir=str(model_dir))
    for f, expected in EXPECTED_SHA256.items():
        actual = sha256(model_dir / f)
        if actual != expected:
            sys.exit(f"SHA-256 mismatch for {f}: expected {expected}, got {actual}")
    print(f"checkpoint verified at {REPO_ID}@{REVISION[:12]}")


class ChoiceGraph(torch.nn.Module):
    """Upstream's DecisionModel, fixed to one Choice question per row. See the module docstring."""

    def __init__(self, decision_model):
        super().__init__()
        self.m = decision_model

    def forward(self, input_ids, attention_mask, marker_pos):
        qtype = torch.zeros(input_ids.shape[0], dtype=torch.long, device=input_ids.device)
        marker_mask = torch.ones_like(marker_pos, dtype=torch.bool)
        return self.m(input_ids, attention_mask, marker_pos, marker_mask, qtype)


def cpu_name() -> str:
    try:
        import subprocess

        return subprocess.run(["sysctl", "-n", "machdep.cpu.brand_string"], capture_output=True,
                              text=True, check=True).stdout.strip()
    except Exception:
        return platform.processor() or platform.machine()


def softmax(z: np.ndarray) -> np.ndarray:
    z = z.astype(np.float64)
    e = np.exp(z - z.max())
    return e / e.sum()


def upstream_question(instructions: str, candidates):
    # Exactly what laya.Agent._to_internal produces for {"type": "choice", "criteria": [...]}:
    # a bare label list becomes {label: None}, rendered as the label alone.
    from laya.agent import Agent

    return Agent._to_internal({"type": "choice", "instructions": instructions, "criteria": list(candidates)})


def quantise(fp32_path: Path, paths: dict, encoder_cfg) -> float:
    """Writes paths["int8"] and paths["int8-naive"]; returns the seconds the shipped one took.

    ORT's default dynamic recipe (every weight MatMul and the embedding Gather to int8, per-tensor
    weight scales, activations quantised per tensor at run time) changed the answer on 4 of 34 parity questions, with
    probability errors up to 0.56. A per-group sweep traced almost all of it to ONE group: the
    22 feed-forward output projections (`mlp.Wo`, [intermediate, hidden]). Their input is the
    GeGLU product, whose per-channel outliers a single per-tensor uint8 scale cannot represent.
    Quantising only that group reproduced most of the damage; quantising everything except it
    brought the error down by ~6x. Per-channel weight scales alone did not help (31/34). So the
    shipped INT8 is per-channel and keeps those 22 matrices in FP32 (~78 MB)
    and quantises everything else, including the 256k x 768 embedding that is most of the size.

    SmoothQuant-style rescaling or static per-channel activation calibration would likely let
    Wo be quantised too; not attempted in this spike.
    """
    import onnx
    from onnxruntime.quantization import QuantType, quantize_dynamic

    # The exporter annotates intermediate value_info with shapes it inferred from the example
    # input; onnx's shape inference (which quantize_dynamic runs first) then disagrees with one of
    # them and refuses the model. The annotations are hints, not part of the computation, so
    # quantise a copy with them cleared.
    prep = onnx.load(str(fp32_path))
    del prep.graph.value_info[:]
    initialisers = {i.name: tuple(i.dims) for i in prep.graph.initializer}
    wo_shape = (encoder_cfg.intermediate_size, encoder_cfg.hidden_size)
    wo_nodes = [n.name for n in prep.graph.node
                if n.op_type == "MatMul" and initialisers.get(n.input[1]) == wo_shape]
    assert len(wo_nodes) == encoder_cfg.num_hidden_layers, (
        f"expected one mlp.Wo MatMul per encoder layer, found {len(wo_nodes)}")
    prep_path = fp32_path.with_suffix(".noshapes.onnx")
    onnx.save(prep, str(prep_path))
    del prep
    try:
        quantize_dynamic(str(prep_path), str(paths["int8-naive"]), weight_type=QuantType.QInt8)
        t0 = time.time()
        quantize_dynamic(str(prep_path), str(paths["int8"]), weight_type=QuantType.QInt8,
                         per_channel=True, nodes_to_exclude=wo_nodes)
        seconds = time.time() - t0
    finally:
        prep_path.unlink()
    print(f"quantised INT8 in {seconds:.1f}s ({len(wo_nodes)} mlp.Wo MatMuls kept FP32) -> {paths['int8']}")
    return seconds


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--model-dir", default=str(ROOT / "models/laya-multilingual"))
    ap.add_argument("--out-dir", default=str(ROOT / "models/laya-multilingual-onnx"))
    ap.add_argument("--fixture", default=str(ROOT / "backend-onnx/src/test/resources/laya/golden.json"))
    ap.add_argument("--report", default=str(ROOT / "tools/laya-export-report.json"))
    ap.add_argument("--latency-runs", type=int, default=60)
    ap.add_argument("--skip-export", action="store_true", help="reuse existing .onnx files")
    args = ap.parse_args()

    model_dir, out_dir = Path(args.model_dir), Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    fetch(model_dir)

    import laya
    from laya.common import build_sequence, collate_items, render_options

    torch.manual_seed(0)
    agent = laya.Agent(str(model_dir), device="cpu")
    tok, cfg = agent.tok, agent.cfg
    max_len, head_max_len = cfg["max_len"], cfg["head_max_len"]
    assert agent.temperature == [1.0, 1.0, 1.0] and not agent.temperature_by_options, (
        "checkpoint ships fitted temperatures; the graph contract assumes it does not"
    )
    model = agent.model.eval()

    fp32_path = out_dir / "laya-multilingual-choice.fp32.onnx"
    # "int8" is the variant Loupe ships; "int8-naive" is ORT's default recipe, kept so the reason
    # for the exclusions stays measured rather than remembered. See quantise().
    paths = {
        "fp32": fp32_path,
        "int8": out_dir / "laya-multilingual-choice.int8.onnx",
        "int8-naive": out_dir / "laya-multilingual-choice.int8-naive.onnx",
    }
    export_s = quant_s = None
    if not args.skip_export:
        # nn.TransformerEncoderLayer's inference "fast path" is a fused kernel the exporter cannot
        # express; the slow path is the same maths in exportable ops.
        torch.backends.mha.set_fastpath_enabled(False)
        graph = ChoiceGraph(model).eval()
        seq, markers = build_sequence(tok, CASES[0][1], upstream_question(CASES[0][2], CASES[0][3]),
                                      max_len, head_max_len)
        dummy = (
            torch.tensor([seq]), torch.ones(1, len(seq), dtype=torch.long), torch.tensor([markers]),
        )
        # The torch.export ("dynamo") exporter, not the legacy TorchScript tracer. The tracer bakes
        # the example's sequence length into the head's nn.MultiheadAttention reshapes: the graph
        # then loads, and fails with a Reshape error on the first input of any other length. This
        # was hit, not guessed -- the parity run below is what catches it.
        import torch.onnx._internal.exporter._compat as compat
        from torch.onnx._internal.exporter import _constants

        assert OPSET == _constants.TORCHLIB_OPSET, "the conversion skip below assumes a no-op"
        # torch 2.8 always runs onnxscript's opset converter, even when the target equals the
        # exporter's native opset; that no-op conversion refuses graphs that still contain local
        # functions (before optimize() inlines them). Skip it only for that no-op case.
        compat.onnxscript_apis.convert_version = lambda model, version: model

        batch = torch.export.Dim("batch", min=1, max=64)
        seq_dim = torch.export.Dim("seq", min=4, max=max_len)
        options = torch.export.Dim("options", min=2, max=head_max_len)
        t0 = time.time()
        with torch.no_grad():
            torch.onnx.export(
                graph, dummy, str(fp32_path),
                input_names=["input_ids", "attention_mask", "marker_pos"],
                output_names=["logits", "act_logits"],
                dynamic_shapes={
                    "input_ids": {0: batch, 1: seq_dim},
                    "attention_mask": {0: batch, 1: seq_dim},
                    "marker_pos": {0: batch, 1: options},
                },
                opset_version=OPSET,
                dynamo=True,
                optimize=True,
                external_data=False,  # ~1.3 GB, under protobuf's 2 GB: one self-contained file
            )
        export_s = time.time() - t0
        print(f"exported FP32 in {export_s:.1f}s -> {fp32_path}")

        quant_s = quantise(fp32_path, paths, encoder_cfg=model.encoder.config)

    import onnx
    import onnxruntime as ort

    for p in paths.values():
        onnx.checker.check_model(str(p))  # path form: handles models near the 2 GB proto limit

    so = ort.SessionOptions()
    sessions = {name: ort.InferenceSession(str(p), so, providers=["CPUExecutionProvider"])
                for name, p in paths.items()}

    fixture_cases, rows = [], []
    for case_id, state, instructions, candidates in CASES:
        q = upstream_question(instructions, candidates)
        seq, markers = build_sequence(tok, state, q, max_len, head_max_len)
        assert len(markers) == len(render_options(q)), case_id
        b = collate_items([[{"ids": seq, "markers": markers, "qtype": 0}]], tok.pad_token_id)
        with torch.no_grad():
            ref_logits, _ = model(b["input_ids"], b["attention_mask"], b["marker_pos"],
                                  b["marker_mask"], b["qtype"])
        ref_logits = ref_logits[0].numpy()
        ref = softmax(ref_logits)

        # Cross-check the reference against upstream's public API (it rounds to 4 d.p.).
        public = agent.predict(state, {"q": {"type": "choice", "instructions": instructions,
                                             "criteria": list(candidates)}})["answers"]["q"]
        assert public["choice"] == candidates[int(ref.argmax())], case_id
        assert max(abs(public["probabilities"][c] - ref[i]) for i, c in enumerate(candidates)) < 6e-5, case_id

        feeds = {
            "input_ids": np.array([seq], dtype=np.int64),
            "attention_mask": np.ones((1, len(seq)), dtype=np.int64),
            "marker_pos": np.array([markers], dtype=np.int64),
        }
        got = {}
        for name, s in sessions.items():
            logits = s.run(["logits"], feeds)[0][0]
            got[name] = {"logits": logits, "probs": softmax(logits)}

        mask_tok = tok.mask_token
        row = {
            "id": case_id, "options": len(candidates), "tokens": len(seq),
            "stateTokens": len(tok(state.replace(mask_tok, " "), add_special_tokens=False)["input_ids"]),
            # top-1 minus top-2 in the reference: a disagreement on a near-tie means little, one
            # on a confident answer means the quantised graph changed the model's mind.
            "referenceMargin": float(np.sort(ref)[-1] - np.sort(ref)[-2]),
        }
        for name in sessions:
            row[f"{name}_argmax_agrees"] = bool(got[name]["probs"].argmax() == ref.argmax())
            row[f"{name}_max_prob_err"] = float(np.abs(got[name]["probs"] - ref).max())
            row[f"{name}_max_logit_err"] = float(np.abs(got[name]["logits"] - ref_logits).max())
        rows.append(row)

        # Segment token ids, so a CI test can check the Kotlin sequence assembly against upstream
        # build_sequence without the 34 MB tokenizer: each entry is (exact string encoded, ids).
        segments = {}
        head_text = "%s question: %s" % ("choice", str(q["ins"]).replace(mask_tok, " "))
        segments[head_text] = tok(head_text, add_special_tokens=False)["input_ids"]
        for opt in render_options(q):
            t = " " + opt.replace(mask_tok, " ")
            segments[t] = tok(t, add_special_tokens=False)["input_ids"]
        st = state.replace(mask_tok, " ")
        segments[st] = tok(st, add_special_tokens=False)["input_ids"]

        fixture_cases.append({
            "id": case_id,
            "state": state,
            "question": instructions,
            "candidates": list(candidates),
            "segments": [{"text": k, "ids": v} for k, v in segments.items()],
            "inputIds": seq,
            "markerPositions": markers,
            "expected": {
                "torch": [float(x) for x in ref],
                "fp32": [float(x) for x in got["fp32"]["probs"]],
                "int8": [float(x) for x in got["int8"]["probs"]],
            },
        })

    def summary(name):
        return {
            "argmaxAgreement": f"{sum(r[f'{name}_argmax_agrees'] for r in rows)}/{len(rows)}",
            "maxAbsProbError": max(r[f"{name}_max_prob_err"] for r in rows),
            "meanMaxAbsProbError": statistics.mean(r[f"{name}_max_prob_err"] for r in rows),
            "maxAbsLogitError": max(r[f"{name}_max_logit_err"] for r in rows),
        }

    parity = {name: summary(name) for name in sessions}
    for r in rows:
        print("  %-16s k=%-2d tok=%-4d margin=%.3f" % (r["id"], r["options"], r["tokens"], r["referenceMargin"])
              + "".join("  %s %s %.2e" % (n, "ok " if r[f"{n}_argmax_agrees"] else "BAD", r[f"{n}_max_prob_err"])
                        for n in sessions))
    print(json.dumps(parity, indent=2))

    # ---- desktop CPU latency, single question, batch 1 ---------------------------------------
    def latency(run, feeds_list):
        for f in feeds_list[:3]:
            run(f)  # warm-up
        times = []
        for i in range(args.latency_runs):
            f = feeds_list[i % len(feeds_list)]
            t0 = time.perf_counter()
            run(f)
            times.append((time.perf_counter() - t0) * 1000)
        times.sort()
        return {"p50_ms": round(times[len(times) // 2], 2),
                "p95_ms": round(times[int(len(times) * 0.95) - 1], 2), "runs": len(times)}

    def feeds_for(case):
        return {"input_ids": np.array([case["inputIds"]], dtype=np.int64),
                "attention_mask": np.ones((1, len(case["inputIds"])), dtype=np.int64),
                "marker_pos": np.array([case["markerPositions"]], dtype=np.int64)}

    short = [feeds_for(c) for c in fixture_cases if len(c["inputIds"]) <= 128]
    full = [feeds_for(c) for c in fixture_cases if len(c["inputIds"]) == max_len]
    lat = {}
    for name, s in sessions.items():
        if name == "int8-naive":
            continue
        lat[name] = {
            "short (<=128 tokens)": latency(lambda f: s.run(["logits"], f), short),
            "full (1024 tokens)": latency(lambda f: s.run(["logits"], f), full),
        }

    def torch_run(f):
        with torch.no_grad():
            ChoiceGraph(model)(*(torch.from_numpy(f[k]) for k in ("input_ids", "attention_mask", "marker_pos")))

    lat["torch_fp32_reference"] = {"short (<=128 tokens)": latency(torch_run, short),
                                   "full (1024 tokens)": latency(torch_run, full)}
    print(json.dumps(lat, indent=2))

    report = {
        "checkpoint": f"{REPO_ID}@{REVISION}",
        "laya_commit": "c7527708f9f5220c669d8aa385077cd28d04708a",
        "versions": {"torch": torch.__version__, "onnx": onnx.__version__, "onnxruntime": ort.__version__,
                     "transformers": __import__("transformers").__version__, "python": platform.python_version()},
        "opset": OPSET,
        "exportSeconds": export_s, "quantiseSeconds": quant_s,
        "sizesBytes": {**{n: p.stat().st_size for n, p in paths.items()},
                       "safetensors": (model_dir / "model.safetensors").stat().st_size},
        "sha256": {n: sha256(p) for n, p in paths.items()},
        "parity": parity,
        "cases": rows,
        "latency": {
            "WARNING": "DESKTOP CPU, NOT A PHONE. A1's acceptance needs a real mid-range Android device.",
            "machine": f"{cpu_name()} / {platform.platform()} ({os.cpu_count()} cores), "
                       f"ORT CPUExecutionProvider, default threads, batch 1",
            "inputs": "short: cycles over every fixture sequence of <=128 tokens (17-106 tokens), so "
                      "P95 partly reflects length spread; full: the one 1024-token fixture sequence",
            **lat,
        },
    }
    Path(args.report).write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n")

    fixture = {
        "_comment": "Generated by tools/export-laya-onnx.py. Do not edit by hand.",
        "checkpoint": f"{REPO_ID}@{REVISION}",
        "laya_commit": "c7527708f9f5220c669d8aa385077cd28d04708a",
        "maxLen": max_len,
        "headMaxLen": head_max_len,
        "specialTokens": {"cls": tok.cls_token_id, "sep": tok.sep_token_id, "mask": tok.mask_token_id,
                          "pad": tok.pad_token_id},
        "cases": fixture_cases,
    }
    Path(args.fixture).parent.mkdir(parents=True, exist_ok=True)
    Path(args.fixture).write_text(json.dumps(fixture, ensure_ascii=False, separators=(",", ":")) + "\n")
    print(f"wrote {args.fixture} and {args.report}")


if __name__ == "__main__":
    main()
