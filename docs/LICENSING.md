# Licence review

Checked 2026-09-22, before any code was written. Records what was verified, what was rejected,
and what a human still has to confirm at source.

---

## Decision

| | Component | Licence | Status |
|---|---|---|---|
| Primary | `knowledgator/gliclass-modern-base-v2.0` | Apache-2.0 | **Adopted**, pending A0 |
| Second backend | NanoJev (`TianyuCodings/NanoJev`) | MIT | **Adopted**, pending A0 |
| — | openJev-verdict-2.0 | `NOASSERTION` | **Rejected** |
| — | simple-jev (`featherless-ai/simple-jev`) | none | **Rejected** |
| — | Laya-MLX | Apache-2.0 | Rejected on platform, not licence |

---

## Rejected: openJev-verdict-2.0

This was the original choice. Two disqualifying problems, both raised in
[issue #2](https://github.com/Heman10x-NGU/openJev-verdict-2.0/issues/2) on the repository
itself, open and without maintainer response.

**The weights are unobtainable.** `artifacts/verdict2-base/model.pt` is a 134-byte Git LFS
pointer whose object returns 404 — "Object does not exist on the server". The HuggingFace
repository the README badges to returns **401 for anonymous requests**. The reporter ruled out a
client-side cause by successfully fetching a different model from the same account.

**The licence is not Apache-2.0 despite the badge.** The LICENSE file is 410 words against
Apache-2.0's 1,581. Missing: the whole of **Section 4** (redistribution obligations), the patent
termination language from §3, and parts of §§7–8. GitHub's own licence detection reports
**`"spdx_id":"NOASSERTION"`**.

A truncated Apache text is not Apache-2.0. It is a bespoke licence with undefined terms, and
`NOASSERTION` means no downstream user can rely on it. Either problem alone disqualifies it.

*What we lose:* the 77.10% accuracy and 1.44% ECE figures were this model's. They are not
available to us and we do not cite them as ours. Its base model is, and that is what we adopted.

## Rejected: simple-jev

No repository licence. The only LICENSE files in the tree cover 3D assets inside the demo
directories — a car model, trees, textures. No `license` field in any manifest, no licence
section in the README.

**No licence means all rights reserved.** We do not use the code.

The logit-scoring *method* is a technique, not protected expression, and we may implement it
independently. We do not copy their source, their prompt templates, or their scoring code.

---

## Adopted, and why the chain is clean

**ModernBERT** — architecture, weights and training codebase released under Apache-2.0.

**`knowledgator/gliclass-modern-base-v2.0`** — Apache-2.0, ModernBERT backbone, 151,378,177
parameters. The authors state it was trained on synthetic and licensed data permitting
commercial use. It is a single-forward-pass classifier over candidate labels, which is our
Choice primitive directly, and it is the same base the rejected derivative was built on — the
broken packaging was downstream, the foundation is sound.

**NanoJev** — MIT, with weights and dataset published. Architecturally different from the
primary (decoder with decision heads against an encoder), which is what makes the §6 agreement
check meaningful rather than two similar models failing alike.

**Consequence worth stating:** adopting the base rather than a finished derivative means we fit
our own calibration instead of inheriting a number we cannot verify. A6 required that anyway.
It is the product's own argument applied to its own foundations.

---

## Still to verify at source — work item A0

`huggingface.co` was **not reachable from the session that performed this review**. Every
licence statement above for model weights comes from search results and third-party listings,
not from the model card itself.

Given that a repository in this same survey shipped an Apache badge over a mangled licence,
that gap must be closed by a person before any weights are fetched or shipped:

1. The licence tag on the HF model card for `knowledgator/gliclass-modern-base-v2.0`, read at
   source.
2. NanoJev's **weight licence and dataset licence separately** — a code licence does not carry
   over to either.
3. That neither carries a use restriction, acceptable-use addendum, or field-of-use limitation.
4. That the licence text is complete, not a truncation. Word-count the file. That is exactly the
   check that caught openJev.

Record each result here with the date and the model revision it applied to.

---

## Standing rules

- **A badge is not a licence.** Read the file; count the words; check the SPDX identifier the
  host reports.
- **Weights, datasets and code are licensed separately.** Verify each.
- **Verify at the revision you will ship**, and record it. Licences change.
- **No licence means all rights reserved.** Absence is not permission.
- Our own per-file provenance manifest — path, hash, origin, licence scope — follows the
  practice NanoJev uses, and exists because this project vendors across mixed licences.
