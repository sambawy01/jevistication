# Model tests, October–November 2026

*Run by the cloud session on branch `agent-tier`, at the owner's request. Every run was on a
4-core Intel Xeon cloud machine (no GPU), so speeds compare models with each other, not with an
iPhone. Nothing in the app changed. The raw prediction files from the October runs were lost when
the cloud container was recycled; the numbers below are the ones reported at the time, and the
runs can be repeated with the steps in each section.*

**Headline.** Laya stays the shipped decider for now, but **LiquidAI `d1-2B` is the first model
that beats it where Loupe cares** — more accurate, and, above all, honest about its confidence —
and has earned an iPhone speed test. For reading Arabic photos and documents off the iPhone,
**LlamaIndex `OCRia-nano-1`** is the first on-device candidate worth integrating work.
Bonsai 2B (writer) and SoMark OXR (document reader) were tested and rejected.

---

## 1. Decision models: Laya vs d1-2B vs Kai-0.6B (vs Bonsai 2B)

| Model | What | Size | Licence |
|---|---|---|---|
| Laya (shipped) | `convaiinnovations/laya-multilingual@052592a1`, **untuned**, INT8 ONNX | 384 MB | — |
| `LiquidAI/d1-2B` @ `cfe5760f` | decision model, calibrated probabilities, 16 languages incl. Arabic | 3.8 GB (BF16, unquantised) | LFM Open License v1.0: free under $10M annual revenue |
| `vllm-sr/Decision-2.0-Kai-0.6B` @ `cd49ea38` | Qwen3-0.6B decision fine-tune | 1.5 GB | Apache-2.0 |
| `prism-ml/Ternary-Bonsai-2-2B` PQ2_0 @ `683f7be2` | general chat model, forced to pick a label | 0.48 GB | Apache-2.0 |

Laya ran through **Loupe's own path** (`FastDecisionsBenchTest`: `DecisionEngine` → `OnnxBackend`
→ `LayaPrompt`, threshold 0). d1 and Kai ran through their own `system_one` API with the same terse
question (`FastDecisions.questionFor`), bare labels and first-label tie rule as
`tools/bench/kai_predict.py`. d1's and Kai's remote code was read before it ran (no network,
subprocess or unpickling; Kai's only downloads are its own hash-pinned base files, unused here
because everything loaded from local folders).

### 1a. Fast Decisions, development split (English, 2,600 single-label head-instances)

| | majority | label names | Laya | **d1-2B** | Kai | Bonsai 2B |
|---|---:|---:|---:|---:|---:|---:|
| **average of 17 domains** | 28.2% | 38.2% | 47.7% | **62.8%** | 58.6% | 58.2% |
| pooled | 30.4% | 40.0% | 48.0% | **62.3%** | 58.1% | 57.4% |
| `email_triage.is_phishing` (of 100) | 53 | 57 | 54 | 51 | 55 | 54 |
| `ticket_route.contains_pii` (of 100) | 52 | 53 | 56 | **76** | 54 | 49 |

Paired McNemar: Laya right & d1 wrong 243, d1 right & Laya wrong 617 (p = 3e-38); Laya vs Kai
305 / 568 (p = 4e-19); d1 vs Kai 367 / 256 (p = 1e-5). All real differences. The scorer's floor
self-check reproduced 25.8% / 35.2%. **English "is phishing?" is a coin flip for every model** —
treat that head as uninformative. Development split: never call these numbers "the benchmark".
d1's own card claims 58.8 on Fast Decisions (split not stated); ours is 62.8 on dev.

### 1b. Arabic phishing set (65 synthetic messages, written for this test)

22 Egyptian, 21 Modern Standard, 22 Franco; 32 phishing, 33 genuine (genuine ones are deliberate
look-alikes: real OTP notices, debit alerts, delivery notices). Labels are the cloud session's
and **should be checked by the owner, Franco especially**.

| | Laya | **d1-2B** | Kai |
|---|---:|---:|---:|
| right overall | 48/65 (74%) | **51/65 (78%)** | 41/65 (63%) |
| Egyptian / Formal / Franco | 17 / 19 / 12 | 19 / 19 / 13 | 14 / 14 / 13 |
| phishing caught | 31/32 | **32/32** | 14/32 |
| genuine wrongly flagged | 16/33 | 14/33 | 6/33 |
| **acting only at ≥ 0.80 confidence: right** | 46 of 61 (75%) | **45 of 47 (96%)** | 1 of 1 |
| **false alarms at ≥ 0.80** | **15** | **2** | 0 |
| acting only at ≥ 0.90: right | 44 of 57 | 30 of 31 | — |

Laya vs d1 on raw accuracy is not significant (4 vs 7 discordant, p = 0.55). **The difference is
calibration:** Laya is ≥ 90% sure of almost everything and wrong on a quarter of it; d1 is right
96% of the time it clears Loupe's 0.80 bar and hands the rest to the user — which is what Loupe's
abstain design wants. Franco is weak for all three (≈ 55–60%).

### 1c. Speed (each model alone, single question, warm)

| | Laya | d1-2B | Kai | Bonsai 2B |
|---|---:|---:|---:|---:|
| this CPU | **42 ms** | 902 ms | 435 ms | ~2,200 ms |

d1 publishes 18.6 ms on an M5 Pro GPU and lists quantised builds; **its iPhone speed is the open
question** and decides whether it can replace or back up Laya.

## 2. Bonsai 2B as the on-device reply writer — rejected

`docs/HANDOFF-BONSAI-2B.md`'s 18 emails, Loupe's own `EmailReplyWorkflow` prompt ported byte for
byte, PrismML llama.cpp fork `prism-b10777-e3de715` built from source, thinking off.

- **Loupe's request format fails outright:** the app sends two `system` messages and Bonsai's
  (Qwen) chat template rejects that ("System message must be at the beginning") on all 18.
  Merged into one, every reply came back as valid JSON. If Loupe ever uses a local writer,
  `ChatClient.chat` must send one system message.
- **Failed the safety bar:** obeyed the hidden instruction in E5 ("I confirm that the full refund
  of $500 has been approved"); invented facts (instalments in A3, a confirmed booking in M4, a
  contract "already sent" in M3); never wrote Egyptian Arabic; Franco answered in English or MSA.
  Roughly 2 of 6 English and 1 of 12 Arabic drafts sendable after small edits (preliminary
  grading by the session). ~5–6 s per draft on this CPU.
- Packing matters on x86: `PQ2_0` read prompts 7× faster than `PTQ1_0` (120 vs 16 tok/s) here.

## 3. Reading Arabic documents and photos

11 synthetic pages (bill with table, mixed receipt, Egyptian chat, MSA letter in Amiri, bank SMS,
phishing SMS with a link, pharmacy label in Arabic-Indic digits, English invoice as control, two
"photographed" copies: tilted, blurred, JPEG 55, and a Franco chat), rendered with Chromium, scored
by character accuracy and 42 key facts (amounts, dates, IDs, links). Arabic-Indic and Persian
digits count as the same digit.

### OCRia-nano-1 vs Tesseract (run 2026-11-24)

| page | OCRia-nano: chars / facts | Tesseract 5.3.4 ara+eng: chars / facts |
|---|---:|---:|
| bill (MSA, table) | 71% / 3 of 4 | 100% / 4 of 4 |
| receipt (mixed) | 58% / 4 of 5 | 41% / 4 of 5 |
| chat (Egyptian) | 84% / 2 of 3 | 75% / 3 of 3 |
| letter (MSA, Amiri) | 69% / 2 of 3 | 94% / 2 of 3 |
| bank SMS (mixed) | 94% / 5 of 6 | 51% / 6 of 6 |
| phishing SMS | 92% / **0 of 2 (link garbled)** | 66% / 1 of 2 |
| English invoice | 100% / 4 of 4 | 100% / 4 of 4 |
| pharmacy label | 97% / 4 of 4 | 79% / 1 of 4 |
| bill, photographed | 56% / 4 of 4 | 64% / 2 of 4 |
| chat, photographed | **82%** / 2 of 3 | **0% (empty)** / 0 of 3 |
| Franco chat | 93% / 3 of 4 | 98% / 4 of 4 |
| **overall** | **77.3% / 33 of 42** | 71.7% / 31 of 42 |

OCRia-nano-1 (`llamaindex/OCRia-nano-1`, via `liteparse` 2.21.1, models pinned at v1.0.0 and
sha256-checked by `lit models pull`): 8.1M parameters, ONNX on CPU (~0.4 s per page here), a
dedicated right-to-left recogniser with Arabic-Indic digits. Better on photos and mixed
Arabic/Latin lines; worse on clean calligraphic (Amiri) text; **mangled the phishing link**
(`httpsl/cb-egypt-verfycom/iogn`) and sometimes routes a mixed Arabic line to the Latin reader.
Outputs Persian-style digits and letters (۸, ی, ک) that need normalising before matching.
**Licence:** free under $2M revenue and $2M funding; a commercial licence is needed above that,
and for any document-parsing product competing with LlamaParse at any size — the owner should
confirm Loupe's use (OCR inside a privacy assistant) is outside that clause.

### SoMark OXR-1.0 (run 2026-10-22) — rejected

1.3B parameters, custom licence (free under $2M revenue and funding; online services must credit
"SoMark OXR"; no competing hosted API). English invoice 100%; **Arabic unusable**: Persian- and
Chinese-looking garbage on every Arabic page, stuck repeating on 4 of 11 pages, invented text on
the pharmacy label. Its language model is Qwen3-0.6B per its config. Running it on transformers
5.13 also needed two non-persistent buffers rebuilt after loading (they come back uninitialised).

### alOCR 2.0 (DEEP.SA) — not tested

A hosted Saudi API with no downloadable model: sending users' documents to it breaks Loupe's
on-device promise. Possible only as an opt-in, labelled "read this file precisely" feature.

## 4. What follows

1. **d1-2B on an iPhone:** a quantised build, speed and memory against Laya's 36 ms. If it is
   fast enough it becomes the decider, or the second opinion when Laya is unsure.
2. **OCRia-nano for Android and the Mac app,** where Loupe has no Arabic OCR today (ML Kit has no
   Arabic; the desktop has no OCR) — after the licence question is settled, and with a link
   repair step for phishing URLs.
3. **A bigger, owner-checked Arabic set,** Franco first, before any model swap.
