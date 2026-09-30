# Stock Laya vs the Loupe stack (2026-09-30)

**The question:** "Test our tuned Laya vs the original Laya for Loupe tasks." Loupe ships the **stock
weights** of `convaiinnovations/laya-multilingual` (`fine_tuned_from_checkpoint: false`; no retrained
checkpoint exists). What Loupe tuned is the stack around the weights, so that is what is compared.

**Short answer.** On the one outside dataset (Fast Decisions), the Loupe stack and stock Laya are the
same within noise: 47.6% vs 47.2% (+0.4 points, 95% CI −0.5 to +1.3). On Loupe's own yes/no
questions, Loupe answers far better (+26.7 points on the hand-labelled sample). That gain does **not**
come from int8 or from anything learned. It comes from **how the question is sent**: Loupe sends a
yes/no as a two-option `choice` with the options written out. The package's own yes/no type, `noul`,
answers "yes" to almost everything here. Calibration never changes an answer. It lowers ECE a lot on
Fast Decisions, but at the shipped thresholds it sends almost every yes/no answer to a person.

## The arms

| arm | runtime | prompt | score order | calibration |
|---|---|---|---|---|
| **STOCK** | authors' `laya` 0.3.20 (commit `23a17522`), fp32 torch 2.8.0, **CPU** (4 threads, no autocast), checkpoint temperatures 1 | the package's own, in its native type for the shape (below) | written order | none |
| **LOUPE** | Kotlin `OnnxBackend`, INT8 graph (`…choice.int8.onnx`, sha256 `8b994315…`), ONNX Runtime on the JVM | `LayaPrompt`/`LayaTokenizer`, always `choice` (the graph's qtype is fixed) | reversed (`REVERSE_SCORE_ON`) | off (shipped default) |
| **LOUPE+CAL** | same masses as LOUPE | same | same | `ModelPrior` on (`model_calibration.json`) |

**How STOCK is asked each question** (`stock_question` in the script): a yes/no (bare or with
descriptive options) is a `noul`, which is how the authors' presets write yes/no questions. The
positive option's text is the `true` criterion and the negative's is the `false` one. A score is a
`score` with the bands as level criteria, index 0 first. A pick or a Fast Decisions label set is a
`choice` with the options in written order. The answer is the top option (argmax), for scores too.
Fast Decisions heads are label sets, so they are a `choice` in both arms, even the binary ones.

**Identical inputs.** The Kotlin test writes each record: the state text exactly as `DecisionEngine`
builds it (`TextState`, 4,000-character budget), the question, options, descriptions and label.
The Python script feeds STOCK those same records. No state was cut by the budget or by the model's
context in any record.

## Headline

Accuracy is at full coverage: every item is answered. The 95% CI is a paired bootstrap of
LOUPE − STOCK over items (10,000 resamples, seed 20260930). An item's questions are resampled
together. ECE uses Loupe's `Calibration.ece` (10 equal-width bins on the top probability), the same
for every arm. The majority floor is an oracle: the most common label per judgment, read from these
labels.

| dataset | n | STOCK acc | LOUPE acc | Δ LOUPE − STOCK (95% CI) | ECE stock | ECE Loupe | ECE Loupe+cal | answers differ (of those: stock right / Loupe right) | majority floor | keyword baseline |
|---|---|---|---|---|---|---|---|---|---|---|
| Hand-labelled sample (**synthetic**, 45 items × 3) | 135 | 45 (33.3%) | 81 (60.0%) | **+26.7** [+15.6, +38.5] | 0.581 | 0.227 | 0.187 | 64 (14 / 50) | 85.2% | 96.3% |
| Template examples (written with the templates) | 117 | 72 (61.5%) | 76 (65.0%) | +3.4 [−6.0, +12.8] | 0.224 | 0.108 | 0.132 | 30 (13 / 17) | 48.7% | 84.6% |
| Fast Decisions **development split**, 26 single-label heads | 2,600 | 1,227 (47.2%) | 1,237 (47.6%) | +0.4 [−0.5, +1.3] | 0.387 | 0.386 | **0.181** | 218 (64 / 74) | 30.4% | 40.0% |

**The two Loupe heads** (Fast Decisions development split, n = 100 each):

| head | STOCK | LOUPE | Δ (95% CI) | ECE stock / Loupe / Loupe+cal | answers differ | majority | label-name baseline |
|---|---|---|---|---|---|---|---|
| `email_triage.is_phishing` | 54% | 55% | +1.0 [+0.0, +3.0] | 0.449 / 0.449 / 0.187 | 1 (Loupe right on it) | 53% | 57% |
| `ticket_route.contains_pii` | 56% | 57% | +1.0 [−5.0, +7.0] | 0.350 / 0.334 / 0.068 | 11 (5 / 6) | 52% | 53% |

Both arms sit a few points above the majority floor and **at or below the label-name baseline** on
both heads. These are the Laya judgments with these terse wordings, not Loupe's phishing formula or
privacy check. Neither of those was scored here.

**Fast Decisions suite** (development split, single-label heads only; the three multi-label heads are
excluded): mean of the 17 domains is STOCK 46.9%, LOUPE 47.3%. Pooled: 47.2% vs 47.6%. This is a
**development-split figure, not the benchmark.** The vendor's table uses a held-out split that is not
published. `FastDecisions.questionFor` wording is used unchanged.

## Per judgment

**Hand-labelled sample** (the synthetic sample; labels by the person who wrote the criteria):

| judgment | STOCK | LOUPE | Δ (95% CI) | ECE stock / Loupe / Loupe+cal | differ (stock right / Loupe right) | yes-rate: labels / STOCK / LOUPE | majority | keyword |
|---|---|---|---|---|---|---|---|---|
| `is-receipt` | 23/45 | 31/45 | +17.8 [−4.4, +40.0] | 0.447 / 0.187 / 0.207 | 28 (10 / 18) | 14 / 36 / 8 | 68.9% | 95.6% |
| `phishing` | 12/45 | 27/45 | +33.3 [+20.0, +46.7] | 0.689 / 0.351 / 0.249 | 15 (0 / 15) | 2 / 35 / 20 | 95.6% | 97.8% |
| `needs-reply` | 10/45 | 23/45 | +28.9 [+11.1, +46.7] | 0.653 / 0.278 / 0.104 | 21 (4 / 17) | 4 / 35 / 18 | 91.1% | 95.6% |

Neither arm reaches the majority floor or the keyword baseline on any of the three. STOCK's `noul`
says "yes" to 35–36 of 45 items on each question. For example, it calls a bank statement a receipt
at p = 0.99997.

**Template examples:** 55 templates, 117 examples, 2–3 each. Answers differ on 30 examples: Loupe is
right on 17 and STOCK on 13. With n = 2 or 3 per template no single template's figure means
anything. The full per-template table is in `measure-results/stock-vs-loupe/tables.md`.

**Fast Decisions, per head** (n = 100 each): the Δ is between −4 and +4 points on every head, and
no head's CI excludes 0. The table is in `tables.md`.

## What the tuning does: the ablations

Two extra arms isolate each difference. **Loupe fp32** is the same Loupe stack on the fp32 export of
the same weights. **STOCK-as-choice** is the authors' torch model with every question sent as a
`choice` with Loupe's options in written order.

**Parity first.** Loupe fp32 ONNX and STOCK-as-choice torch give the same answer on all 2,852
records. The largest probability difference is 1.1e-4 on Fast Decisions and 1.9e-5 on the sample.
On the templates it is 0.062, all from `urgency`, where Loupe fp32 still reverses the score order. So
Loupe's prompt is the package's `choice` prompt, and the graph matches torch.

| difference | sample (135) | templates (117) | Fast Decisions (2,600) |
|---|---|---|---|
| **Question type**: STOCK native (`noul`/`score`) → as `choice`: answers changed; acc change | 67 changed; **+30.4 pts** [+18.5, +43.0] for choice | 25 changed; +0.9 [−7.7, +9.4] for choice | none (already `choice`) |
| **(a) int8 vs full precision**: Loupe fp32 → Loupe int8: answers changed; acc change | 7 changed; −3.7 [−7.4, +0.0] | 11 changed; +2.6 [−2.6, +8.5] | 218 changed (8.4%); +0.4 [−0.5, +1.3] |
| **(b) score reversal on/off** (score questions only) | no score question | `urgency`, n = 3: 0 answers changed (2/3 either way) | no score question |

- **Question type is the whole yes/no story.** Sent as a `choice`, stock fp32 Laya scores 86/135 on
  the sample. Loupe int8 scores 81 and STOCK's `noul` scores 45. On the template examples the type
  moves 25 answers but not the total.
- **(a) int8 changes answers, not accuracy.** It flips 8% of Fast Decisions answers, with a net +10
  right answers for int8 (inside the CI). On the sample it costs 5 answers (86 → 81), also inside the
  CI. ECE is unchanged on Fast Decisions (0.387 fp32, 0.386 int8).
- **(b) reversal:** only one Loupe judgment is a score, and it has 3 labelled examples. The reversal
  changes none of those 3. On the unlabelled synthetic sample it changed 21 of 45 `urgency` answers
  (`docs/LAYA-UPGRADE-MEASURE.md`). The labelled data here cannot say whether that is better. STOCK's
  own `score` type (levels in written order, "level i: band") gets 1/3.
- **Calibration (LOUPE+CAL)** changed no answer on any record (checked, 0 of 2,852). Its effect by
  dataset:

  | dataset | ECE | Brier | abstention at the judgment's threshold |
  |---|---|---|---|
  | Fast Decisions | 0.386 → 0.181 | 0.865 → 0.703 | 4.9% → 18.9% (selective acc 48.9% → 52.8%) |
  | sample | 0.227 → 0.187 | 0.624 → 0.525 | 38.5% → **96.3%** |
  | templates | 0.108 → 0.132 (worse) | 0.456 → 0.474 | 56.4% → 89.7% |

  The yes/no temperature (T = 12.88) pulls almost every yes/no answer below the templates'
  thresholds. With it on, the phone would ask a person about nearly every item on these sets.

## Latency

These figures are for information only. The arms run on different runtimes: JVM ONNX Runtime at its
default threads vs torch on 4 CPU threads, both on the same Apple-silicon Mac mini, one at a time.
Each figure is milliseconds per question, one question per call, after a 5-call warm-up.

| dataset | STOCK torch fp32 p50 / p95 | LOUPE int8 p50 / p95 | Loupe fp32 ONNX p50 / p95 |
|---|---|---|---|
| sample | 52 / 98 | 76 / 182 | 166 / 390 |
| templates | 30 / 38 | 33 / 43 | 72 / 108 |
| Fast Decisions | 67 / 100 | 114 / 174 | 269 / 581 |

On this desktop, int8 ONNX is about 2.2–2.4× faster than fp32 ONNX. Stock torch is faster than either
here, so none of this predicts phone latency.

## Caveats

- **Sample sizes.** The sample has 45 items, and its labels are skewed (2 of 45 phishing, 4 of 45
  need a reply). The templates have 2–3 examples each. Only Fast Decisions (2,600 head-instances) is
  big enough to rule effects in or out, and it has no score question and no Loupe-written yes/no.
- **Synthetic data.** The sample is invented, and its hand labels were written by the person who
  wrote the criteria. The template examples were written with the templates.
- **Development split.** Fast Decisions figures come from the published development split. They are
  not the benchmark and cannot be compared with the vendor's held-out table. Floors on the same rows:
  majority 30.4%, label-name 40.0% (pooled, single-label heads).
- **The STOCK mapping is a choice.** Mapping a yes/no to `noul` follows the authors' presets. The
  STOCK-as-choice arm shows the other option, and it is the one that explains the gap.
- **Not tuned.** No threshold, wording, label order or temperature was changed to move a number.
  Abstention uses each judgment's own threshold (0.5 for Fast Decisions, as in the hand-off). ECE for
  every arm is computed by the same 10-bin code. The package's own figures use 15 bins.
- **Not measured:** Loupe's phishing formula and privacy check on the two Fast Decisions heads, and
  the three multi-label heads.

## Reproduce

```bash
ln -s <main checkout>/models models            # gitignored weights
tools/fetch-fast-decisions.sh                   # 17 files, sha256-verified, never committed
./gradlew :loupe-desktop:test --tests '*StockVsLoupe*' -Ploupe.measure=stock-vs-loupe   # LOUPE arms + records (~23 min)
USE_TF=0 tools/.venv/bin/python tools/measure-stock-vs-loupe.py run     # STOCK + STOCK-as-choice (~6 min)
tools/.venv/bin/python tools/measure-stock-vs-loupe.py score            # results.json + tables.md
```

Raw outputs go to `measure-results/stock-vs-loupe/` (gitignored): `records.jsonl`, `stock.jsonl`,
`stock-meta.json`, `results.json`, `tables.md`. `LayaModelTest`'s 15 gated tests passed on these
files before the run.
