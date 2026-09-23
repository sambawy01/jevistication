# Licence review

Verified 2026-09-22, before any code was written. Every entry below was read from the
artifact's own Hugging Face frontmatter or its LICENSE file — not from a badge, a README claim,
or a search result.

**Three candidates were eliminated by this check, two of them after having been chosen.**

**2026-09-23:** the primary model changed from GLiClass to Laya (`convaiinnovations/laya-multilingual`),
on the owner's decision that GLiClass's performance is not comparable. Laya had been listed here
as rejected "on platform" — that was a misreading, corrected below. Its weights are Apache-2.0,
but **the provenance of its training data is only partly published and includes sources with
non-commercial terms**; that is recorded in full under *Laya — training-data provenance* and
filed as build risk 12. The tokenizer dependency it needs (DJL) is recorded under *DJL tokenizers*.

---

## Verified at source

| Artifact | Declared | Status |
|---|---|---|
| `convaiinnovations/laya-multilingual` @ `052592a1` | `license: apache-2.0` | **Adopted** — primary (2026-09-23). **Training-data provenance open — risk 12** |
| `NandhaKishorM/laya` (code) @ `c7527708` | Apache-2.0, SPDX `Apache-2.0` | Reference implementation; used by the export tool only, not shipped |
| `jhu-clsp/mmBERT-base` @ `c5955035` | `license: mit` | Backbone of the primary. **Tokenizer is Gemma 2's — see below** |
| `knowledgator/gliclass-modern-base-v2.0` | `license: apache-2.0` | Verified; **superseded** as primary 2026-09-23 |
| `Qwen/Qwen3-0.6B` | `license: apache-2.0` + `license_link` | **Adopted** — second backend |
| `answerdotai/ModernBERT-base` | `license: apache-2.0` | Backbone of GLiClass |
| `C-Tianyu/NanoJev` | **no `license` field** | **Rejected** |
| `C-Tianyu/NanoJev-Data` | **no `license` field** | **Rejected** |
| openJev-verdict-2.0 | `NOASSERTION` | **Rejected** |
| `featherless-ai/simple-jev` | no licence file | **Rejected** |
| ~~Laya-MLX~~ | Apache-2.0 | ~~Rejected on platform~~ — **a misreading, corrected 2026-09-23.** Laya-MLX is only an MLX runtime port; the model family runs anywhere. See Laya above |
| `com.microsoft.onnxruntime:onnxruntime:1.20.0` | `MIT License` in the resolved POM | **Adopted** — runtime, pending review |
| `ai.djl.huggingface:tokenizers:0.38.0` + 8 transitive | Apache-2.0 (JNA: Apache-2.0 OR LGPL-2.1+; SLF4J: MIT) in the resolved POMs | **Adopted** — tokenizer, 2026-09-23 |

---

## Adopted

**`convaiinnovations/laya-multilingual`** — verified 2026-09-23 at revision
`052592a15d198d9ad47da779604259b10b47b7aa`: frontmatter reads `license: apache-2.0`. The
`model.safetensors` (643,835,514 bytes, SHA-256 `9d628fd9…aa8f204`) and `tokenizer/tokenizer.json`
(SHA-256 `609d8f4c…b5b6f`) hashes match the Hub's LFS object ids and are pinned in
`tools/export-laya-onnx.py`, which refuses a mismatch. The repository carries no LICENSE file of
its own; the grant is the frontmatter. The code repository `NandhaKishorM/laya` (commit
`c7527708`) is Apache-2.0: GitHub reports SPDX `Apache-2.0`, and its LICENSE was compared word for
word against the canonical text — 1,413 words against 1,581, **the whole difference being the
non-normative "How to apply" appendix**; Sections 1–9 are identical. (This is the count that
exposed openJev's truncated licence; here it comes out clean.) The card's `commercial-use` tag is
a tag, not evidence, and is not relied on.

A non-autoregressive decision model: mmBERT-base encoder plus a 2-layer head that scores every
option at its own `<mask>` marker, then softmaxes per question — the Choice primitive in one
forward pass. 322M parameters, 1,024-token context of which 256 go to the question and options.

*Backbone:* `jhu-clsp/mmBERT-base` (revision `c5955035`) declares `license: mit` and names open
pretraining datasets (`jhu-clsp/mmbert-*`). **Its card states the tokenizer is Gemma 2's.** Laya's
`tokenizer.json` is that vocabulary. Gemma 2 is distributed under Google's Gemma Terms of Use, not
an OSI licence. Whether those terms reach a tokenizer vocabulary redistributed inside an
MIT-licensed model is a question this review **has not resolved** — it is recorded, not waved
through, and is part of risk 12.

**`knowledgator/gliclass-modern-base-v2.0`** — *superseded as primary on 2026-09-23; the licence
finding stands.* Frontmatter reads `license: apache-2.0`. The card states the model "was trained
on synthetic and licensed data that allow commercial use and can be used in commercial
applications", and names its training datasets. Backbone is `answerdotai/ModernBERT-base`,
independently confirmed Apache-2.0. Laya's card makes no equivalent statement about its data —
that difference is exactly what the provenance section below is about.

**`Qwen/Qwen3-0.6B`** — frontmatter reads `license: apache-2.0` with an explicit `license_link`.
A decoder scored by its logits, against our encoder scoring labels: architecturally different,
which is what makes the §6 agreement check meaningful. It is also the base NanoJev used, so we
keep the diversity NanoJev offered without inheriting its licence problem.

*Honest note:* NanoJev's own benchmark records untuned Qwen3-0.6B at 7/20 and 2/20 zero-shot on
their tasks. We fine-tune both backends on our fixtures, so zero-shot weakness is expected and
not disqualifying — but it is a reason to expect the second backend to need its own training
before its votes mean anything.

---

## Laya — training-data provenance

The check that caught NanoJev: weights, data and code are licensed separately, so an Apache-2.0
weight file says nothing about what the weights were trained on. Findings, 2026-09-23. Where a
thing is unknown it says so.

**What the checkpoint says about itself.** The card: "Trained from scratch with RLCD: 15,987
updates, 4 epochs, ~4.97 h." `rl_agent_config.json`'s `training` block records updates, epochs,
hours and `fine_tuned_from_checkpoint: false`. **Neither names a single training dataset.** The
full training mix of `laya-multilingual` is **not published** anywhere we found — not on the card,
the GitHub README, `BENCHMARKS.md`, or the `research` branch.

**What the authors' own evidence says was in it.** Their benchmark script
(`research/scripts/bench_apps.py` on the `research` branch, commit `28d43add`) marks each source
`in_training=True` or held out, and the README and `BENCHMARKS.md` label rows "in training mix".
Those flags name six sources, with the licence each declares on its Hugging Face card, read
2026-09-23:

| Source (authors flag it "in training") | Declared licence | Consequence |
|---|---|---|
| `Tobi-Bueck/customer-support-tickets` (support triage) | **`cc-by-nc-4.0`** | **Non-commercial** |
| `microsoft/ms_marco` (RAG relevance) | card: "More Information Needed"; Microsoft's MS MARCO terms: *"intended for non-commercial research purposes only"* | **Non-commercial research only** |
| `zefang-liu/phishing-email-dataset` (phishing) | `lgpl-3.0` | Copyleft licence on a dataset; how it applies to weights is unclear |
| `google/boolq` | `cc-by-sa-3.0` | Share-alike; whether it reaches weights is legally unsettled |
| `fancyzhx/ag_news` | `unknown` | Unknown |
| `SetFit/enron_spam` | **no licence declared** | Default copyright; the Enron corpus's own status is separate again |

Two caveats cut both ways. The flags describe "Laya" across all three checkpoints; whether each
source is in the **multilingual** checkpoint's mix specifically is **not stated** — unknown. And
the list is what the benchmark happened to test, not a manifest: sources outside it are
**unknown**, including whatever gave the model its 100+ languages.

**The "teacher".** It belongs to the `typed-decisions` benchmark (`LocalLLaMA/typed-decisions`,
`apache-2.0`, revision `c76749ec`), not to this checkpoint's base training. That dataset's gold
labels are "the mean of three samples from a teacher endpoint of roughly 4B-class capability", and
its textual states were "written by a model conditioned on the skeleton". **Neither model is
named**, so whether that endpoint's terms forbid training on its outputs is **unknown**. It is used
to fine-tune `laya-typed-decisions`, a different checkpoint we do not use; the Laya cards say the
base checkpoints were not trained on it (they score near chance on it zero-shot, 0.342 for
multilingual). Whether any teacher-labelled or LLM-generated data is in the *base* mix is
**unknown**.

**TypeSafe Jev.** The Laya repositories state every Jev number is third-party published and that
they had "no TypeSafe API access", so nothing indicates Laya was distilled from Jev outputs. The
`typed-decisions` card does record calling the TypeSafe API to *score* Jev on its test set —
evaluation, not labels.

**What this means.** The Apache-2.0 grant on the weights is real, and it is the authors'. It does
not answer whether weights trained on CC-BY-NC and non-commercial-research data can be used
commercially — a question on which the law is unsettled, and which NanoJev's rejection shows we
take seriously. Unlike NanoJev, nothing here is *undeclared*; the risk is in **declared upstream
terms** the model card does not mention. Fine-tuning on our own fixtures does not remove it.
**Filed as build risk 12.** Adopted for development on the owner's decision; **not cleared for
shipping** until one of: the authors publish the full training mix and it is clean; they confirm
the non-commercial sources are absent from the multilingual checkpoint; or we train the head (or
the whole model) on data we can account for. Asking the authors is the cheapest first step.

---

## Rejected

### NanoJev — weights and dataset declare no licence

The GitHub repository is MIT. **The artifacts we would actually ship are not.**

`C-Tianyu/NanoJev` frontmatter declares `language`, `base_model`, `library_name` and `tags`, and
**no `license` field**. `C-Tianyu/NanoJev-Data` declares `language`, `tags`, `size_categories`
and `configs`, and **no `license` field**.

A code licence does not carry to weights. The model card says the release "is available without
authentication" — availability is not permission. With no declared licence, default copyright
applies.

Layered provenance compounds it: the dataset's expert episodes derive from a third-party
project ([Sonic Doom](https://github.com/thainv0212/sonic_doom)) and a ViZDoom environment, each
with its own terms, which would need separate review even if the dataset itself were licensed.

*This is the entry that justifies the whole exercise.* NanoJev was adopted on the strength of
"MIT" read off a GitHub repository. The rule that caught it — weights, datasets and code are
licensed separately — was already written in this file before it was applied.

### openJev-verdict-2.0 — unobtainable and misdeclared

Two disqualifying problems, both in
[issue #2](https://github.com/Heman10x-NGU/openJev-verdict-2.0/issues/2), open and without
maintainer response.

**The weights are unobtainable.** `artifacts/verdict2-base/model.pt` is a 134-byte Git LFS
pointer whose object returns 404. The Hugging Face repository the README badges to returns
**401 for anonymous requests**. The reporter ruled out a client-side cause by fetching a
different model from the same account.

**The licence is not Apache-2.0 despite the badge.** The LICENSE file is 410 words against
Apache-2.0's 1,581 — missing the whole of **Section 4**, the patent-termination language from
§3, and parts of §§7–8. GitHub reports **`"spdx_id":"NOASSERTION"`**.

A truncated Apache text is a bespoke licence with undefined terms. Either problem alone
disqualifies it.

*What we lose:* the 77.10% accuracy and 1.44% ECE figures were this model's. They are not ours
and we do not cite them. Its base is, and that is what we adopted.

### Laya-MLX — the rejection was wrong, and is withdrawn

Recorded here because it was a finding once. Laya-MLX was rejected as "Apple Silicon only". It is
an MLX **runtime port** of the Laya model family, and the port is what is Apple-only; the models
themselves are ordinary PyTorch/`transformers` checkpoints (`convaiinnovations/laya*`) that export
to ONNX like anything else. The model is now the primary, above.

### simple-jev — no repository licence

The only LICENSE files in the tree cover 3D assets inside demo directories — a car model, trees,
textures. No repository licence, no `license` field in any manifest, no licence section in the
README. **No licence means all rights reserved.**

The logit-scoring *method* is a technique, not protected expression, and we implement it
independently against Qwen3-0.6B. We do not copy their source, prompt templates or scoring code.

---

## Standing rules

- **A badge is not a licence.** Read the frontmatter or the file. Count the words. Check the
  SPDX identifier the host reports.
- **Weights, datasets and code are licensed separately.** Verify each. This rule eliminated a
  model we had already adopted.
- **Absence is not permission.** No declared licence means default copyright.
- **Verify at the revision you will ship**, and record it. Licences change.
- **Check upstream provenance**, not just the top layer. A dataset can inherit terms from the
  projects that generated it.
- A per-file provenance manifest — path, hash, origin, licence scope — ships with the project,
  because it vendors across mixed licences.

## Still open

- **Laya's training-data provenance (risk 12)** — the one item that blocks *shipping*, though not
  development. See the provenance section above for what would close it.
- **The Gemma 2 tokenizer question** — whether the Gemma Terms of Use reach mmBERT's vocabulary.
  Unresolved; part of risk 12.
- **DJL on Android** — the `tokenizer-native` AAR lags the Java API (0.33.0 vs 0.38.0). Untested.
- On first fetch of any other artifact, re-read its frontmatter at the exact revision pinned and
  record the commit hash here beside the date. Laya's revision and hashes are recorded above.

**ONNX Runtime is the project's first runtime dependency**, on `main` since `392e25e`. It is
isolated in the `backend-onnx` module, so `:engine` still resolves to nothing but the Kotlin
standard library and the offline core carries no third-party code at all. Verified
2026-09-22 by reading `<licenses>` from the POM Gradle resolved
(`onnxruntime-1.20.0.pom`), not from a badge: `MIT License`,
`https://opensource.org/licenses/MIT`. It declares **no transitive dependencies**, so the
verification covers the whole of what it brings in — which is the check the NanoJev rejection
exists to enforce.

### Native binaries — checked 2026-09-22

The desktop jar (89 MB) bundles `libonnxruntime` for five platforms: linux-x64, linux-aarch64,
osx-x64, osx-aarch64 and win-x64. It carries a 345 KB `ThirdPartyNotices.txt` covering roughly
**85 components**.

**No copyleft and no non-commercial obligation.** Four strings looked alarming on a keyword scan
and all four are false positives, recorded here so nobody re-runs the scare:

- `GNU General Public` / `GNU Lesser` / `Affero` at lines 489–493 are MPL 2.0's own definition of
  *"Secondary License"* — definitional text inside a permissive licence, not a component under GPL.
- `GNU Lesser` at line 17 is Microsoft boilerplate *granting* reverse-engineering rights for
  LGPL debugging. A grant, not an obligation. The literal string `LGPL` appears **zero** times as
  a component licence.
- All three `non-commercial` hits are permissive grants — the Unlicense (G3log), SQLite's
  public-domain dedication and a CC dedication — each reading "for any purpose, commercial or
  non-commercial".

Licence families present: MIT, Apache-2.0, BSD 2- and 3-clause, ISC, Boost, zlib, public
domain/Unlicense, **MPL-2.0 (Eigen)**, and a distinct **Intel licence** carrying a limited patent
grant and its own jurisdiction terms.

Two conditions do apply, and neither is satisfied by merging:

- **MPL-2.0 on Eigen** is file-level weak copyleft. Shipping it unmodified inside a binary is
  fine; modifying any MPL-covered file obliges us to publish that file. Do not patch Eigen.
- **Attribution is owed to ~85 components.** MIT, BSD, ISC and Apache all require the notice to
  travel with the binary.

**The jar ships no `LICENSE` file.** The MIT grant exists only in POM metadata, so even ONNX
Runtime's own licence has to be reproduced by us rather than lifted from the artifact.

### `onnxruntime-android` — checked 2026-09-22

A separate coordinate, as expected, and separately declared: `MIT License` in
`onnxruntime-android-1.20.0.pom`, packaging `aar`, versions published through 1.30.0. ABIs
shipped: `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`.

**The AAR contains no licence or notice files at all** — no `LICENSE`, no `ThirdPartyNotices`,
nothing. The desktop jar at least carries its notices; the artifact that would actually ship
carries none. So shipping the Android build satisfies **none** of the attribution obligations
above from the artifact itself. The notices must be sourced upstream and bundled by us, and the
Android build's component set cannot be assumed identical to the desktop jar's — it is a
different native build.

Recorded as **build risk 11**. It does not block adopting the dependency; it blocks *shipping*
without a notices screen.

### DJL tokenizers — checked 2026-09-23

`ai.djl.huggingface:tokenizers:0.38.0` — the Hugging Face `tokenizers` Rust library behind a JNI
binding, so `tokenizer.json` is read by the same code the Python reference uses (the hand-off's
trap: never hand-roll a tokenizer). The coordinates that failed to resolve before were an older
guess; `0.38.0` is the current release on Maven Central (published 2026-09-09) and resolves.
Isolated in `backend-onnx` with ONNX Runtime; `:engine` still resolves to the Kotlin standard
library alone.

Licences read from the `<licenses>` block of each POM Gradle resolved — following `<parent>`
where a POM declares none — not from a badge:

| Artifact | Declared |
|---|---|
| `ai.djl.huggingface:tokenizers:0.38.0` | The Apache License, Version 2.0 |
| `ai.djl:api:0.38.0` | The Apache License, Version 2.0 |
| `com.google.code.gson:gson:2.13.1` | Apache-2.0 |
| `com.google.errorprone:error_prone_annotations:2.38.0` | Apache 2.0 |
| `net.java.dev.jna:jna:5.17.0` | **`LGPL-2.1-or-later` *or* `Apache-2.0`, licensee's choice** — we take Apache-2.0 |
| `org.apache.commons:commons-compress:1.27.1` | none in its POM → `commons-parent:72` → `org.apache:apache:33`: Apache-2.0 |
| `commons-codec:commons-codec:1.17.1` | → `commons-parent:71` → `apache:32`: Apache-2.0 |
| `commons-io:commons-io:2.16.1` | → `commons-parent:69` → `apache:31`: Apache-2.0 |
| `org.slf4j:slf4j-api:2.0.17` | → `slf4j-parent` → `slf4j-bom:2.0.17`: MIT |

**No copyleft is forced.** JNA is dual-licensed and its own `META-INF/LICENSE` says "You can freely
decide which license you want to apply"; choosing Apache-2.0 is the whole of the obligation. The
DJL code repository itself reports SPDX `Apache-2.0`.

**Bundled native libraries.** The 18 MB tokenizers jar carries `libtokenizers` for linux-x86_64,
linux-aarch64, osx-aarch64 and win-x86_64 (no osx-x86_64), built from Rust `tokenizers` 0.21.0
(`native/lib/tokenizers.properties`). The Windows build also bundles `libstdc++-6.dll`,
`libgcc_s_seh-1.dll` (GPL-3.0 **with the GCC Runtime Library Exception**, which permits
redistribution in a binary without copyleft effect) and `libwinpthread-1.dll`; they are loaded only
on Windows and would never ship on Android. JNA's jar carries `libjnidispatch` for ~25 platforms.
**Neither DJL jar ships a LICENSE or NOTICE file**, and nothing credits the Rust crates statically
linked into `libtokenizers` — the same attribution gap as build risk 11, which now covers these too.

**Two behaviours that matter more than the licence, found reading the source (DJL `v0.38.0`):**

- **It phones home unless told not to.** `HuggingFaceTokenizer.newInstance` calls
  `Ec2Utils.callHome`, which probes the EC2 metadata address `169.254.169.254` and can send a
  telemetry request, at most once a day, unless DJL offline mode or `OPT_OUT_TRACKING` is set. Its
  native loader can also download a JNI library from `publish.djl.ai` for GPU flavours. Both
  contradict the offline guarantee, so `HuggingFaceSubwordEncoder.open` switches offline mode on
  and **refuses to load if it did not take effect**. A test asserts it.
- **It writes to the home directory.** The native library is extracted to `~/.djl.ai/tokenizers/`
  on first load (overridable with `DJL_CACHE_DIR`). Harmless on a desktop; on Android the library
  is loaded differently, below.

**Android is not verified.** On Android DJL loads `libdjl_tokenizer` through `System.loadLibrary`,
supplied by a separate AAR, `ai.djl.android:tokenizer-native` — Apache-2.0 in its POM, but **last
published at 0.33.0** (June 2025) while the Java API is at 0.38.0. Whether a 0.33 native library
matches the 0.38 JNI surface is untested; pinning both to 0.33.0 or building the JNI library
ourselves are the fallbacks. Recorded in the hand-off.
