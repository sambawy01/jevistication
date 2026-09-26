# Hand-off: score our tuned Laya on Fast Decisions

*Written 2026-09-26 on branch `agent-tier` by the cloud session, for a Claude Code session on the
owner's Mac. Everything here is done except the last step, because the cloud container cannot reach
the model files.*

**The job:** run our tuned Laya over the Fast Decisions development split and report the number.
Nothing else. Do not swap any model, do not change the app.

---

## 1. Why this exists

Fastino published [GLiNER2.5-Decide](https://huggingface.co/fastino/GLiNER2.5-Decide) on 2026-09-24
with a scoreboard that puts **Laya Router at 46.6%** against their own 340M model at 60.2%. Two
things about that number:

- it is **Laya Router**, a different product from the `laya-multilingual` we build on, and
- it is untuned, while ours is tuned.

So it is a cousin's number, not ours. Nobody has measured ours. This hand-off measures ours.

Before believing any of those figures, note what the cloud session already established by running
two deliberately stupid predictors over the same 1,700 rows:

| Predictor | Average (mean of 17 domains) | Pooled (2,900 heads) |
|---|---|---|
| Guess each head's most common answer, read nothing | 25.8% | 28.8% |
| Match the label's own name against the text | **35.2%** | 36.7% |
| Laya Router (Fastino's table, held-out split) | 46.6% | — |
| GLiNER2.5-Decide 340M (their table) | 60.2% | — |

**A third of this suite falls to word-matching.** Laya Router is ~11 points above that floor. Keep
this table beside whatever you measure; a bare percentage here means very little.

## 2. What is already built and pushed

On `agent-tier`:

- `loupe-kit/src/commonMain/kotlin/dev/loupe/kit/measure/FastDecisions.kt` — parses the dataset,
  turns every single-label head into a `Judgment.Choice` with fixtures, and scores the suite with
  the dataset card's protocol. 23 tests, green.
- `tools/fetch-fast-decisions.sh` + `tools/fast-decisions.sha256` — fetches the 17 files and
  verifies every one. The data is not committed.
- `loupe-kit/src/jvmMain/.../FastDecisionsMain.kt` + `./gradlew :loupe-kit:fastDecisions` — prints
  the floor table above. **It has no model wired in.** That is your job.

## 3. Get the inputs

```bash
git fetch origin agent-tier && git checkout agent-tier
tools/fetch-fast-decisions.sh          # 17 files, verifies each sha256
./gradlew :loupe-kit:fastDecisions     # reproduce the floor table first
```

The model files go where `LayaFixture` already looks — `models/` at the repo root, which is
gitignored (`backend-onnx/build.gradle.kts` sets `loupe.models.dir` to it):

```
models/laya-multilingual/tokenizer/tokenizer.json
models/laya-multilingual-onnx/laya-multilingual-choice.int8.onnx
```

If they are not on the machine already, they are on the owner's own model host, pinned in
`ios/Loupe/Resources/Laya/models.json`:

```
https://zqyeihzjpfnvkrprcwam.supabase.co/storage/v1/object/public/models/laya/v1/tokenizer.json
https://zqyeihzjpfnvkrprcwam.supabase.co/storage/v1/object/public/models/laya/v1/laya-multilingual-choice.int8.onnx
```

Check them against the manifest's SHA-256 before using them:

| File | sha256 | bytes |
|---|---|---|
| `tokenizer.json` | `609d8f4c067cd3950f88594c5a802616cea245823836ef5848ee4fc40aab5b6f` | 34,363,188 |
| `…choice.int8.onnx` | `8b994315135dd7684331bb58fe3a769e3ca1145a5c421a2a41e2b3c85397b2fa` | 383,883,281 |

## 4. Prove the model loads before trusting any score

`./gradlew :backend-onnx:test` — **15 tests in `LayaModelTest` are currently skipped** for want of
those two files. With them present they run, and they check the graph against the golden fixture
(34/34 answers, 8/8 criteria questions) and the tokenizer against Arabic, Egyptian, Franco, Spanish
and French.

Do not proceed until those 15 pass. A bad exam score from a mis-loaded model would waste a day.

## 5. Wire it up

Put the runner in `backend-onnx/src/test/kotlin/dev/loupe/backend/onnx/` as a gated test, the same
shape as `LayaModelTest`, so it skips cleanly on a machine without the files and CI stays green.
Add `testImplementation(project(":loupe-kit"))` to `backend-onnx/build.gradle.kts`.

The construction is exactly `LayaModelTest`'s ("a real Laya decision runs through the engine"):

```kotlin
val encoder = HuggingFaceSubwordEncoder.openLayaMultilingual(LayaFixture.tokenizerJson())
val tokenizer = LayaTokenizer(LayaPrompt(encoder, LayaSpecialTokens.MULTILINGUAL))
OnnxBackend.open(LayaFixture.graph("int8"), tokenizer, TensorNames.LAYA).use { backend ->
    val engine = DecisionEngine(backend, threshold = Probability.of(0.5))
    for (name in FastDecisions.DOMAINS) {
        val domain = FastDecisions.parse(name, File("third-party/fast-decisions/$name.jsonl").readText())
        for (head in domain.heads.filter { FastDecisions.isHarnessable(it) }) {
            val report = Harness.evaluate(
                judgment = FastDecisions.judgment(head),
                fixtures = FastDecisions.fixtures(domain, head),
                engine = engine,
                baseline = FastDecisions.lexicalBaseline(head),
            )
            // report.accuracyAtFullCoverage is the comparable number -- see §6.
        }
    }
}
```

Roughly 2,600 single-label model calls. At Laya's usual ~36 ms that is a couple of minutes.

## 6. Four rules that decide whether the number is honest

1. **Report `accuracyAtFullCoverage`, not `selectiveAccuracy`.** Loupe abstains when unsure; the
   exam demands an answer to every question. Quoting selective accuracy against 46.6% would compare
   "our score on the ones we felt sure about" with "their score on everything" — flattering and
   wrong. `Harness` already computes both. Report coverage and selective accuracy *as well*, as our
   own extra insight, clearly separated.
2. **Do not touch `FastDecisions.questionFor`.** It yields deliberately terse text — "is phishing?",
   "intent?" — because the dataset has no question and §7 makes wording a controlled variable. A
   better-written question would be our prose, and the gain would be miscredited to the model.
3. **Never call the result "the benchmark".** The dataset card says: *"Do not report a score computed
   on the files in this repo as the benchmark."* Their published figures are a held-out
   300-per-domain split that is not distributed. Ours is a **development-split** figure. Say so
   every time it is written down.
4. **Report the three multi-label heads separately or not at all.** `product_feedback.product_area`,
   `restaurant_review.aspects` and `screen_tags.genres` have no `Choice` — a `Choice` answers with
   one label. Scoring them through the harness would be scoring a different task. If you want the
   full 29-head suite figure, use `FastDecisions.score` with a predictor, not `Harness`.

## 7. The two heads that matter most

`email_triage.is_phishing` (53 no / 47 yes) and `ticket_route.contains_pii` (52 yes / 48 no) are
already Loupe features. Near-balanced binaries, so the floors are known: majority 53% and 52%,
label-names 57% and 53%.

Report these two **on their own line**, above the suite average. They are third-party test cases for
the phishing formula and the privacy check, written by people with no stake in our answer, and they
are worth more than the other twenty-seven heads put together.

Note the phishing formula (`docs/PHISHING-FORMULA.md`) is a separate mechanical path from the
judgment, so consider scoring the formula against `is_phishing` too, not only Laya. If the two
disagree, that disagreement is the finding.

## 8. What to report back

- the floor table from `:loupe-kit:fastDecisions`, so the reader has the context;
- per-domain and suite `accuracyAtFullCoverage`, both average and pooled;
- the two Loupe heads on their own line, against their own floors;
- coverage and selective accuracy separately;
- the variant used (**int8**, the graph we ship — the full-precision graph would score slightly
  differently, and saying which one is part of the number);
- whether `LayaModelTest`'s 15 gated tests passed first.

Then append a dated entry to `docs/BUILD.md`'s progress log, commit on `agent-tier`, and push.

## 9. Do not

- swap the model, change a threshold, or touch the app;
- commit the dataset or the model files;
- tune the question wording, the threshold, or the label order to improve the score — there is no
  point measuring if we adjust until we like the answer.
