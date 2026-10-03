# Hand-off: Laya vs Kai-0.6B on Fast Decisions

*Written 2026-10-03 on branch `agent-tier` by the cloud session, for a Claude Code session on the
owner's Mac. The cloud container cannot reach Hugging Face or the owner's model host, and Kai needs
more memory than the Composio sandbox has, so the run belongs on the Mac. Everything else is built.*

**The question:** is `vllm-sr/Decision-2.0-Kai-0.6B` better at Loupe's kind of decisions than the
Laya Loupe ships? Answer it with one exam we control, marked by one scorer, for both models.

**Not in scope:** swapping any model, changing the app, tuning anything to improve a score.

---

## 1. What is already built and tested

| Piece | What it does |
|---|---|
| `backend-onnx/src/test/.../FastDecisionsBenchTest.kt` | Laya's predictions through **Loupe's shipped path** — `DecisionEngine` → `OnnxBackend` → `LayaPrompt`, INT8 graph. Gated: skips without the files. |
| `tools/bench/kai_predict.py` | Kai's predictions at a **pinned revision**, same questions, same labels. |
| `tools/bench/score.py` | Marks any number of prediction files with **one** set of rules, prints the floors, the two Loupe heads, and a paired significance test. |
| `tools/bench/test_bench.py` | 17 checks with a fake model, no network. `python3 tools/bench/test_bench.py` |

The scorer **refuses to run** unless it reproduces the recorded floors (25.8% guessing, 35.2%
label-name matching). If it stops, the data or the scorer is not what the recorded numbers came
from — fix that before believing anything.

## 2. Read Kai's code before it runs — do not skip

Kai loads with `trust_remote_code=True`: its own Python files execute on this Mac. They are pinned
at revision `cd49ea3813fd8ba0928a9a23ef6c9a0f2f0cd764` and include `modeling_decision2.py`,
`pipeline_decision2.py`, `configuration_decision2.py` and the `decision2/` package (with a vendored
`dev2model/`). Download only the code first and look:

```bash
python3 -m venv .venv-bench && source .venv-bench/bin/activate
pip install "transformers>=5.17" torch safetensors huggingface_hub
huggingface-cli download vllm-sr/Decision-2.0-Kai-0.6B --revision cd49ea3813fd8ba0928a9a23ef6c9a0f2f0cd764 \
  --include "*.py" "decision2/**" --local-dir /tmp/kai-code
grep -rnE "requests|urllib|http|socket|subprocess|os\.system|popen|eval\(|exec\(|pickle|telemetry" /tmp/kai-code
```

Anything that phones home, shells out, or unpickles is a stop: report it, don't run. Model files
(`*.safetensors`) cannot execute code; the `.py` files can.

## 3. Get the inputs

```bash
git fetch origin agent-tier && git checkout agent-tier && git pull
tools/fetch-fast-decisions.sh                     # 17 files, each sha256-verified
```

Laya's two files, from the owner's model host, into the gitignored `models/` folder:

```bash
H=https://zqyeihzjpfnvkrprcwam.supabase.co/storage/v1/object/public/models/laya/v1
mkdir -p models/laya-multilingual/tokenizer models/laya-multilingual-onnx
curl -fL -o models/laya-multilingual/tokenizer/tokenizer.json "$H/tokenizer.json"
curl -fL -o models/laya-multilingual-onnx/laya-multilingual-choice.int8.onnx "$H/laya-multilingual-choice.int8.onnx"
shasum -a 256 models/laya-multilingual/tokenizer/tokenizer.json models/laya-multilingual-onnx/*.int8.onnx
# expect 609d8f4c…5b6f and 8b994315…b2fa (ios/Loupe/Resources/Laya/models.json)
```

## 4. Prove Laya loads correctly first

```bash
./gradlew :backend-onnx:test
```

The 15 `LayaModelTest` cases must **pass, not skip**. They check the graph against the golden
fixture and the tokenizer against English, Arabic, Egyptian, Franco, Spanish and French. A score
from a mis-loaded model is worthless.

## 5. Run both models

```bash
# Laya -> backend-onnx/build/bench/laya.jsonl
./gradlew :backend-onnx:test --tests '*FastDecisionsBenchTest*' --rerun

# Kai: smoke test, then the two arms
python3 tools/bench/kai_predict.py --limit 20
python3 tools/bench/kai_predict.py                 # one question per call -> bench/out/kai.jsonl
python3 tools/bench/kai_predict.py --batched       # a row's heads together -> bench/out/kai-batched.jsonl
```

**Arm one** (one question per call) is the fair comparison: it is how Loupe asks Laya. **Arm two**
lets Kai use its strength — several questions about the same text in one pass — and is reported
separately, never mixed into arm one.

## 6. Score

```bash
python3 tools/bench/score.py backend-onnx/build/bench/laya.jsonl bench/out/kai.jsonl bench/out/kai-batched.jsonl
```

## 7. The rules that make the answer honest

1. **Full coverage, errors count wrong.** No model may skip what it finds hard. The Laya runner sets
   the threshold to 0 and takes the top label even when the engine would abstain; Kai's exceptions
   and Laya's unusable answers are written with no prediction and marked wrong.
2. **Identical questions.** Both get `FastDecisions.questionFor` — "is phishing?", "intent?" — and
   bare labels with no descriptions. Do not improve the wording for either.
3. **Identical tie rule.** Kai returns no choice on an exact tie; the runner takes the first label,
   which is what Loupe's `Distribution.argmax` does. Without this, Kai would be penalised for
   something Laya's path cannot do.
4. **Read the McNemar line before the averages.** It says whether the gap is real or noise. A
   1–2 point difference with p > 0.05 is a tie, and must be reported as one.
5. **Development split.** Never call any of it "the benchmark" (the dataset card's own request).
6. **Latency is a Mac number.** Report it, but it says nothing about an iPhone — and Laya here is
   ONNX on CPU, the same unaccelerated path as the phone (`docs/AGENT.md`, Core ML finding).

## 8. What this does NOT tell you

**Arabic.** Fast Decisions is English only. A Kai win here says nothing about Egyptian Arabic or
Franco, which is most of Loupe's real input. There is no labelled Arabic decision set in this repo
— the Laya golden fixtures are Laya's *own* outputs, used for parity, not gold labels, so they
cannot score Kai. Before any switch, someone has to write ~50–100 labelled Arabic/Franco items for
the decisions Loupe makes (phishing yes/no, intent, urgency). Say this in the report.

**Phone viability.** Kai is ~2× Laya's size and ships for `transformers` only — no ONNX, Core ML or
phone build exists. A win here earns it the next step (an export and an on-device measurement), not
a place in the app.

## 9. Report back

- the floor self-check line;
- the full table from `score.py`, all three model columns;
- the two Loupe heads' line;
- the McNemar line for **Laya vs Kai (arm one)**, quoted exactly;
- errors, missing and median latency per model, and the device (CPU or MPS);
- both revisions: Laya `052592a1` (upstream, untuned, INT8 export) and Kai `cd49ea38`;
- the Arabic caveat, in a sentence.

Append a dated entry to `docs/BUILD.md`, commit the report (not the predictions, data or models),
push to `agent-tier`.
