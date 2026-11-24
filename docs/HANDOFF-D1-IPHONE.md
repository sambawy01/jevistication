# Hand-off: is d1-2B fast enough on an iPhone?

*Written 2026-11-24 on branch `agent-tier` by the cloud session, for a Claude Code session on the
owner's Mac with the owner's iPhone plugged in. Background and the accuracy results that earned
this test: [`MODEL-TESTS-2026.md`](MODEL-TESTS-2026.md) §1. Nothing in the app changes.*

**The question:** LiquidAI `d1-2B` beat the shipped Laya on accuracy (62.8% vs 47.7% on Fast
Decisions dev) and, more importantly, on calibration (Arabic phishing at Loupe's 0.80 bar: 2 false
alarms vs 15). On a cloud CPU it was 21× slower (902 vs 42 ms). Can its shrunk ONNX build answer
fast enough, in little enough memory, on the owner's iPhone — and does shrinking keep its answers?

**Not in scope:** wiring d1 into Loupe, changing `OrtLayaBackend` or the ORT pin, tuning anything.
A pass earns a build plan; a fail keeps Laya and points at fine-tuning Laya instead.

---

## 0. Ground rules

- **Work in `~/d1-test/`, outside the repo.** Models, ONNX Runtime packages, the throwaway iPhone
  app and raw outputs are never committed. Only the report is (section 7).
- **Pin everything.** `LiquidAI/d1-2B-ONNX` at `2fd61486312020c30a1e3aa5aa677424672b2b37`;
  `LiquidAI/d1-2B` at `cfe5760ff5d5998f91d9ac56bd4b2dbd96996d41` (the revision the cloud run used).
  Record any newer revision you see, but test these.
- **Read the remote code before it runs.** Both repos ship Python (`prompt.py`, `runner.py`,
  `api.py`, `modeling_*.py`, …). The cloud session read d1-2B's at `cfe5760f`: no network,
  subprocess or unpickling; the only dynamic imports are optional kernels. Diff the ONNX repo's
  `.py` files against that copy and read anything that differs. Do **not** install the
  `kernels` package (transformers' "kernels from the Hub" hooks stay no-ops without it).
- **No user data.** Inputs are `docs/data/arabic-phishing-v0.jsonl` (65 synthetic messages) and
  the Fast Decisions dev split (`tools/fetch-fast-decisions.sh`).
- **Licence:** LFM Open License v1.0 — free for commercial use under $10M annual revenue.

## 1. Which files

| Build | Decoder | Size | For |
|---|---|---|---|
| **q4** | `onnx/decoder_q4.onnx` + `.onnx_data` | ~1.74 GB | **the main candidate** (int4 MLP gate/up, int8 rest, fp32 activations, CPU) |
| q8 | `onnx/decoder_q8.onnx` + `.onnx_data` | ~2.13 GB | fallback if q4 loses accuracy |
| q4f16 | `onnx/decoder_q4f16.onnx` + `.onnx_data` | ~1.57 GB | only if the Core ML or a GPU path is tried |

Text-only decisions need **only the decoder**; skip the vision encoders. Download just these with
`hf download LiquidAI/d1-2B-ONNX --revision 2fd61486… --include "onnx/decoder_q4.onnx*" "onnx/decoder_q8.onnx*" "*.py" "*.json" "*.jinja"`.

The decoder takes one packed row (a state and up to 16 questions, no padding, no cache):
`input_ids`, `attention_mask`, `position_ids`, `question_ids` (-1 on the state, k on question k),
`slot_positions`, plus `image_features` / `video_features` (empty for text), and returns `logits`
at the answer slots. The repo's Python builds the rows and turns logits into answers (with
`calibration.json`). **The iPhone never needs that Python**: rows are built on the Mac and replayed
on the phone (section 4).

## 2. Mac, step 1 — does shrinking keep the answers?

Python 3.12 venv, `onnxruntime` (latest), `transformers>=5.19`, `torch`. Write
`~/d1-test/onnx_answers.py` that, for each item and question:

1. builds the row with the ONNX repo's own code (`prompt.py` / `runner.py` — find the function the
   transformers path uses to build a row, and reuse it; do not re-implement the prompt);
2. runs it through `onnxruntime.InferenceSession` on the chosen decoder (CPU EP);
3. turns `logits` into probabilities with the repo's own readout and calibration;
4. writes the same JSONL format as the cloud run: `{"model","domain","item_id","task","ms",
   "predicted","probabilities"}`.

Use exactly the cloud run's question: `{"type": "choice", "instructions": questionFor(task),
"criteria": {label: None}}` (`tools/bench/fastdecisions.py`, `question_for`), one question per row,
first-label tie rule. Run it for **q4 and q8** on:

- the 65 Arabic messages (`docs/data/arabic-phishing-v0.jsonl`; each row also carries `lang`);
- the full Fast Decisions dev split (2,600), or a fixed 600-item sample if time is short — say which.

Then the reference: the full-precision transformers model (`LiquidAI/d1-2B` @ `cfe5760f`, fp32 on
CPU or fp16 on `mps`) on the same items. Score all three with `tools/bench/score.py` (English) and
the Arabic breakdown from `MODEL-TESTS-2026.md` §1b (overall, per dialect, phishing caught,
genuine flagged, right-and-false-alarms at ≥ 0.80).

**Expected reference numbers** (cloud run, full model): Fast Decisions dev 62.8% average;
Arabic 51/65, 32/32 phishing caught, 45 of 47 right at ≥ 0.80 with 2 false alarms. If the Mac's
full-model numbers differ by more than a couple of items, stop and find out why before going on.

## 3. Mac, step 2 — export rows for the phone

From the q4 run, save **30 real rows** as tensors (`.npy` or one JSON per row) with their token
counts: the 10 shortest, 10 median and 10 longest of the 65 Arabic messages, plus 10 Fast
Decisions items of ~300 tokens and 5 of ~1,000 tokens (long emails). Also save each row's
q4 logits from the Mac, to check the phone computes the same thing.

For Laya on the same phone, record each Arabic message's Laya token count (run it through
`HuggingFaceSubwordEncoder` + `LayaPrompt` in a tiny JVM test, or read it off the cloud
`FastDecisionsBenchTest` path) — the phone app times Laya's INT8 graph at those lengths.

## 4. iPhone — the throwaway app

`~/d1-test/ios/`, a minimal SwiftUI app, never in the Loupe repo:

1. **ONNX Runtime:** the latest official iOS package (`onnxruntime-objc` / `onnxruntime-c` via
   SPM or CocoaPods). **Also try ORT 1.20.0**, the version Loupe pins (`gradle/ios-third-party-notices.gradle.kts`,
   `ios-native/build.sh`): if d1's graph needs a newer ORT (contrib ops such as `MatMulNBits` or
   `GroupQueryAttention`), Loupe would have to upgrade — record exactly which error appears.
2. **Files:** copy `decoder_q4.onnx` and its `.onnx_data` into the app's Documents with Finder
   (external data must sit next to the `.onnx`). Same for q8 if time allows. Laya's
   `laya-multilingual-choice.int8.onnx` as the baseline.
3. **Run:** load once, then replay the exported rows: one warm-up, then each row 5 times. Laya:
   the INT8 graph with inputs of the recorded lengths (token values don't change its speed).
4. **Measure, Release build, phone unplugged from power for the timing runs:**
   - load time (first open) and app memory after load;
   - **median and P90 time per question**, by row length (short SMS / median / long email);
   - peak memory (Xcode memory gauge or Instruments Allocations), and whether iOS kills the app;
     if it does, retry with the `com.apple.developer.kernel.increased-memory-limit` entitlement and
     record both;
   - CPU execution provider, then the **Core ML** execution provider (expect partial support; record
     how much of the graph it took and whether it helped);
   - 200 questions back to back: temperature/throttling, and whether speed drops;
   - **parity:** the phone's logits vs the Mac's for the 30 rows (max absolute difference).

## 5. The bar, set before the run

| Check | Pass |
|---|---|
| q4 top answers vs full model, Fast Decisions | ≥ 98% the same |
| q4 on the Arabic set | ≤ 2 items below the full model; false alarms at ≥ 0.80 still ≤ 3 |
| **iPhone, short message (≤ 150 tokens)** | **≤ 250 ms median** |
| iPhone, long email (~1,000 tokens) | ≤ 1.5 s median |
| iPhone memory | ≤ 2.5 GB peak with Laya also loaded, no kill without the entitlement |
| Phone vs Mac logits | max abs difference ≤ 1e-2 |

Loupe decides in the background, so 250 ms per short message is acceptable even though it is ~7×
Laya's 36 ms. If q4 passes accuracy but misses speed, the fallback design is **"Laya first, d1
when Laya is unsure"** — report what fraction of the Arabic set Laya answers below 0.80 (that
share is how often d1 would run).

## 6. Optional: d1-omni-600M (Mac only)

`LiquidAI/d1-omni-600M` (587M, lfm1.0 licence) claims 76.9 on Fast Decisions dev — but its card lists
only English, German, Spanish, French, Italian and Dutch, **no Arabic**. Run it on the 65 Arabic
messages through its own `system_one` (read its code first). Only if it holds up on Arabic is it worth
an export; otherwise record the number and drop it.

## 7. Report back

Write `docs/D1-IPHONE-REPORT.md` with: all revisions and hashes; the Mac model and the **iPhone model
and iOS version**; the step-1 table (full / q8 / q4, English and Arabic); the phone table (load, median
and P90 by length, memory, CPU vs Core ML, ORT version that worked, whether 1.20.0 did); parity; the
bar with pass/fail per row; the Laya-first share from section 5; and one paragraph: **pass, fail or
close**. Append a dated entry to `docs/BUILD.md`, commit only those two files, `git fetch origin
agent-tier`, merge if another session pushed, and push to `agent-tier`.
