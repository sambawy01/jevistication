# Licence review

Verified 2026-09-22, before any code was written. Every entry below was read from the
artifact's own Hugging Face frontmatter or its LICENSE file — not from a badge, a README claim,
or a search result.

**Three candidates were eliminated by this check, two of them after having been chosen.**

---

## Verified at source

| Artifact | Declared | Status |
|---|---|---|
| `knowledgator/gliclass-modern-base-v2.0` | `license: apache-2.0` | **Adopted** — primary |
| `Qwen/Qwen3-0.6B` | `license: apache-2.0` + `license_link` | **Adopted** — second backend |
| `answerdotai/ModernBERT-base` | `license: apache-2.0` | Backbone of the primary |
| `C-Tianyu/NanoJev` | **no `license` field** | **Rejected** |
| `C-Tianyu/NanoJev-Data` | **no `license` field** | **Rejected** |
| openJev-verdict-2.0 | `NOASSERTION` | **Rejected** |
| `featherless-ai/simple-jev` | no licence file | **Rejected** |
| Laya-MLX | Apache-2.0 | Rejected on platform, not licence |
| `com.microsoft.onnxruntime:onnxruntime:1.20.0` | `MIT License` in the resolved POM | **Adopted** — runtime, pending review |

---

## Adopted

**`knowledgator/gliclass-modern-base-v2.0`** — frontmatter reads `license: apache-2.0`. The card
states the model "was trained on synthetic and licensed data that allow commercial use and can be
used in commercial applications", and names its training datasets. Backbone is
`answerdotai/ModernBERT-base`, independently confirmed Apache-2.0. A single-forward-pass
classifier over candidate labels, which is our Choice primitive directly.

**`Qwen/Qwen3-0.6B`** — frontmatter reads `license: apache-2.0` with an explicit `license_link`.
A decoder scored by its logits, against our encoder scoring labels: architecturally different,
which is what makes the §6 agreement check meaningful. It is also the base NanoJev used, so we
keep the diversity NanoJev offered without inheriting its licence problem.

*Honest note:* NanoJev's own benchmark records untuned Qwen3-0.6B at 7/20 and 2/20 zero-shot on
their tasks. We fine-tune both backends on our fixtures, so zero-shot weakness is expected and
not disqualifying — but it is a reason to expect the second backend to need its own training
before its votes mean anything.

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

Nothing blocking. On first fetch, re-read each frontmatter at the exact revision pinned and
record the commit hash here beside the date.

**ONNX Runtime is the project's first runtime dependency** and is not yet on `main`. It is
isolated in the `backend-onnx` module, so `:engine` still resolves to nothing but the Kotlin
standard library and the offline core carries no third-party code at all. Verified
2026-09-22 by reading `<licenses>` from the POM Gradle resolved
(`onnxruntime-1.20.0.pom`), not from a badge: `MIT License`,
`https://opensource.org/licenses/MIT`. It declares **no transitive dependencies**, so the
verification covers the whole of what it brings in — which is the check the NanoJev rejection
exists to enforce.

Two things remain before it ships:

- The published jar bundles **native binaries** per platform. A licence on the Java artifact is
  not automatically a licence on the shipped `.so`/`.dylib` blobs, and the standing rule that
  weights, datasets and code are licensed separately applies to native objects for the same
  reason. Read the native licence at the revision shipped.
- The Android artifact is a **different coordinate** (`onnxruntime-android`). Verify that one
  too; do not carry this finding across to it.
