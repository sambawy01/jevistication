# Third-party notices

Source code in this repository that is derived from third-party work, with the notice its licence
requires. Licence checks for every dependency are in [`docs/LICENSING.md`](docs/LICENSING.md).

**The notices that ship with the app** — every runtime library, the native code inside them, the
213 Rust crates in DJL's `libtokenizers`, and the items below — are generated into
[`loupe-desktop/src/main/resources/THIRD_PARTY_NOTICES.txt`](loupe-desktop/src/main/resources/THIRD_PARTY_NOTICES.txt)
and packaged in the app jar. Regenerate after any dependency change with
`./gradlew :loupe-desktop:generateThirdPartyNotices`; `./gradlew check` fails while it is stale.
Inputs and how to refresh them: `docs/LICENSING.md`, "Shipped notices".

---

## river-raid-2k

- **Upstream:** https://github.com/joaoneto/river-raid-2k, commit `5148ace` (read 2026-09-23)
- **Licence:** MIT, from the repository's `LICENSE` file
- **Used in:** `game/src/main/kotlin/dev/loupe/game/Noise.kt` (the gradient-noise function, adapted)
  and the idea behind `RiverGenerator` in `game/src/main/kotlin/dev/loupe/game/River.kt` (bank width
  from the magnitude of noise sampled down the river; an island when the banks leave enough water).
- **Not used:** its sprites, palette, engine, or any other asset. Its player sprite imitates a
  commercial game's silhouette, so the game's sprites were drawn fresh.

```
MIT License

Copyright (c) 2017 João Neto

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

## Mozilla Public Suffix List

- **Upstream:** https://publicsuffix.org/list/public_suffix_list.dat, snapshot `VERSION:
  2026-09-21_18-50-07_UTC`, `COMMIT: 728555a30ef4d40e42a82d5678e5fbad2ad17b26` (fetched 2026-09-23)
- **SHA-256:** `e81c6f5f11359a79a2479238e732e08bd8521071fd95ee47053471e3426d7b54`, recorded in
  `public_suffix_list.dat.sha256` beside it and checked by a test
- **Licence:** Mozilla Public License 2.0, stated in the file's own header, which travels with it
- **Used in:** `engine/src/main/resources/dev/loupe/engine/public_suffix_list.dat`, bundled
  **unmodified** and read by `PublicSuffix` for the registrable-domain (eTLD+1) check. Refreshed
  only by `tools/update-psl.sh` at build time; the engine never fetches it. Source for the covered
  file is the upstream URL above.
- **Test vectors:** `engine/src/test/resources/dev/loupe/engine/psl_tests.txt` is `tests/tests.txt`
  from https://github.com/publicsuffix/list, unmodified, dedicated to the public domain (CC0 1.0)
  by its header.

```
This Source Code Form is subject to the terms of the Mozilla Public
License, v. 2.0. If a copy of the MPL was not distributed with this
file, You can obtain one at https://mozilla.org/MPL/2.0/.
```

---

## Fonts the iPhone app ships

- **Rajdhani** (SemiBold, Bold), © 2014 Indian Type Foundry — SIL Open Font License 1.1.
  `ios/Loupe/Resources/Fonts/Rajdhani-*.ttf`, unmodified; licence text
  `ios/Loupe/Resources/Fonts/OFL-Rajdhani.txt`, bundled in the app and shown under Me → Licences.
- **JetBrains Mono**, © 2020 The JetBrains Mono Project Authors — SIL Open Font License 1.1.
  `ios/Loupe/Resources/Fonts/JetBrainsMono.ttf`, unmodified; licence text
  `ios/Loupe/Resources/Fonts/OFL-JetBrainsMono.txt`, bundled and shown the same way.

The OFL allows bundling in an app provided the fonts are not sold on their own and the licence
travels with them; neither is renamed, so the Reserved Font Name clause is not engaged. The app
also bundles `ios-native/THIRD_PARTY_NOTICES-ios.txt` (ONNX Runtime iOS, the tokenizers crates)
under Me → Licences.

---

## Libraries the desktop app ships (not derived source)

No source in this repository is derived from these; they are runtime dependencies of
`:sources-desktop` and `:loupe-desktop`, listed here because their notices must travel with any
binary built from them. They do: all are in the generated `THIRD_PARTY_NOTICES.txt` above. Licence checks, including the
components PDFBox bundles, are in [`docs/LICENSING.md`](docs/LICENSING.md), "The desktop app and
its sources".

| Library | Licence | Notice owed |
|---|---|---|
| Apache James mime4j 0.8.15 (`core`, `dom`, `mbox-iterator`) | Apache-2.0 | its `NOTICE` ("Copyright 2004-2025 The Apache Software Foundation") |
| Apache PDFBox 3.0.8 (`pdfbox`, `pdfbox-io`, `fontbox`) | Apache-2.0, with bundled BSD, Adobe-permissive, SIL OFL 1.1 (Liberation, Lohit, Noto fonts), CC BY 4.0 (Font Awesome shapes) and CC0 components | its `NOTICE` and the "EXTERNAL COMPONENTS" section of its `LICENSE` |
| Apache Commons IO 2.19.0, Commons Logging 1.4.0 | Apache-2.0 | their `NOTICE` files |
| metadata-extractor 2.21.0 (Drew Noakes) | Apache-2.0 | the Apache-2.0 text; the jar ships none |
| Adobe XMPCore 6.1.11 | BSD-3-Clause | the BSD-3-Clause text with Adobe's copyright; the jar ships none |
| Compose Multiplatform 1.7.3, skiko 0.8.18, ONNX Runtime 1.20.0, DJL 0.38.0, Gson 2.13.1 | see `docs/LICENSING.md` | in the generated `THIRD_PARTY_NOTICES.txt` |

---

## Loupe Station (the owner's own repository)

- **Upstream:** the owner's Loupe Station repository (`~/laya-studio`), commit
  `ea7697a4f78e9a648ba49fc8bf9d13226c26cb0e` (read 2026-09-23). Same owner as this repository; no
  third-party licence applies.
- **Used in:** `loupe-kit/src/commonMain/kotlin/dev/loupe/kit/privacy/` — rule tables, regular
  expressions, severities and masking from `laya_studio/scan/{pii_rules,secret_rules,dupes,planner,rules}.py`,
  and the ported test cases in `loupe-kit/src/commonTest/kotlin/dev/loupe/kit/privacy/` from
  `tests/test_scan.py`. Wording on the iOS Privacy screen follows its `static/js/scan-*.js` / `i18n.js`.
- **Also used in (epic #7 children 11 and 12):** `loupe-kit/src/commonMain/kotlin/dev/loupe/kit/mail/`
  — the phishing evidence rules, weights, thresholds, brand/freemail/tracker/service-word tables and
  reason wording of `laya_studio/mail/phishing.py`, the label rules of `mail/classify.py`,
  `mail/triage.py` and `mail/provider.py`, and the `wf-email-triage` keyword rules of
  `measure/baseline.py`; `loupe-kit/src/commonMain/kotlin/dev/loupe/kit/site/` — the brand list,
  shorteners, suspicious TLDs and shared-hosting list of `browser/brands.py` / `browser/psl.py`, and
  the signal weights, confusables and scoring of `browser/{signals,scoring}.py` (registrable domains
  come from the engine's pinned Mozilla PSL, not `psl.py`). Ported tests from `tests/email_cases.py`,
  `tests/test_phishing.py` and `tests/test_browser.py`. Mail triage wording follows
  `static/js/email*.js` / `i18n.js`. No Composio or model code is used.
- **Also used in (epic #7 children 13 and 14):** `loupe-kit/src/commonMain/kotlin/dev/loupe/kit/review/`
  — the review queue's statuses and state machine, the append-only decision log, approve-with-edits,
  reject-needs-a-reason, retry, the limits (16-hex ids, 128 000-byte items, 1 000-character notes,
  one-line 200-character titles, 2 000-character summaries masked with `redact_text`), event and actor
  names and the kind / action registry of `laya_studio/review/{service,store,registry}.py`;
  `loupe-kit/src/commonMain/kotlin/dev/loupe/kit/packs/` — the preset-pack format
  (`laya-preset-pack` v1), every limit, the slug / preset-id / question-id patterns, `slugify`, the
  namespace-stripping rule, the `translations` block and the error locations of `laya_studio/packs.py`,
  with the question rules of `schemas.py` (`ChoiceQuestion`, `ScoreQuestion`, `NoulQuestion`,
  `PredictRequest`). Ported tests from `tests/test_review.py` and `tests/test_packs.py`. The example
  pack `examples/packs/bistro-cloud.json` is copied verbatim; it is the owner's own business rules
  (Bistro Cloud) and the app labels it an example. Review / pack wording follows `static/js/review*.js`
  and `static/js/packs.js`.
- **Also used in (epic #7 child 15):** `sources-common/src/commonMain/kotlin/dev/loupe/sources/common/CsvRows.kt`
  — the CSV decoding order, delimiter candidates, header-row rule, English and Arabic header names,
  value-based column detection, amount and number reading (`facts/money.py` `parse_number` and
  currency markers) and the row identity of `laya_studio/items/csvimport.py`; `Inbox.kt` — the
  item-store ideas (batches as removable sources, content-hash dedupe) of `items/store.py`, the safe
  file names and size caps of `items/uploads.py`; `ZipReader.kt` — the archive limits of
  `scan/content.py` (10 000 members, ratio 200 above 4 MB). Ported tests from `tests/test_items.py`.
  The DEFLATE decoder (`Inflate.kt`) is written for this repository from RFC 1951, in the manner of
  zlib's public `puff.c` reference decoder; no zlib code is included and no dependency was added.
