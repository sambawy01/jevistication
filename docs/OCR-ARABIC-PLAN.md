# Plan: Arabic text in photos on Android and the Mac app (OCRia-nano-1)

*Design note, 2026-11-24, branch `agent-tier`. Written at the owner's request; no code yet (the
build freeze holds until "Go"). Evidence: [`MODEL-TESTS-2026.md`](MODEL-TESTS-2026.md) §3.*

## 1. The gap

Loupe reads text out of photos and screenshots so Laya can judge them ("Photo (text recognised):
IMG_1507.HEIC" — `PhoneItems.photo(ocrText)` in `sources-common`). Today:

| Platform | Text from images | Arabic |
|---|---|---|
| iPhone | Apple Vision, `.accurate`, auto language (`PhotosSource.swift`, `VisionTextRecognizer`) | yes |
| Android | planned: ML Kit text recognition (`ANDROID-PLAN.md`, source B4) | **no — ML Kit's on-device recogniser has no Arabic script** |
| Mac / desktop | none: images give EXIF only (`sources-desktop/Extractors.kt`: "no OCR on the desktop") | — |

Most of the owner's users read Arabic, so on Android and the Mac a screenshot of a scam SMS is
currently a blank to Loupe.

## 2. Why OCRia-nano-1

Of four candidates tested on 11 synthetic Arabic/English pages:

- **OCRia-nano-1** read 77% of characters and 33 of 42 key facts. It was the only one that coped with a photographed screenshot (82%).
- **Tesseract** read 72% of characters and 31 of 42 key facts, and returned nothing for the photographed chat.
- **SoMark OXR** was unusable on Arabic.
- **alOCR** is a hosted service and was not tested.

OCRia-nano-1 is also **8.1M parameters of ONNX on the CPU**, about 0.4 s per page, with a dedicated right-to-left recogniser that includes Arabic-Indic digits. Everything stays on the device, which is the point.

**iPhone keeps Apple Vision.** It is free, already shipped, and reads Arabic; OCRia would only be
compared against it later (section 7).

## 3. How it runs

OCRia-nano is not a standalone model: its five ONNX files (line detector, script router, Latin /
CJK / RTL / world recognisers, LayoutNet) are driven by **LiteParse**, LlamaIndex's Apache-2.0
parser, whose core is a Rust library (`liteparse` crate 2.21.x; Python, Node and WASM bindings).

| Platform | Proposed route | Open questions |
|---|---|---|
| **Mac app** (`loupe-desktop`, JVM) | Call the Rust library through a thin JNI bridge for **one function: image bytes → lines with text, box and confidence**. Fallback: ship the signed `lit` binary and run it as a subprocess. | LiteParse loads its own ONNX Runtime (1.31.x) while `backend-onnx` loads ORT 1.20.0 for Laya: two ORT copies in one process must be shown not to clash, or Laya's pin moves up. |
| **Android** | The same Rust core cross-compiled for `aarch64-linux-android` with the NDK, behind the same one-function JNI bridge; models shipped as an on-demand download, as Laya's are. | Whether the crate builds for Android with PDF/Office features off (only the image → OCR path is needed); APK size; whether ORT Android can be shared with Laya's. |
| Plan B (both) | Re-implement the small pipeline in Kotlin over ONNX Runtime directly: detector → box post-processing → router → CTC decoding → right-to-left line order. | More code, but no Rust toolchain. Only if the Rust route fails. |

Only the **OCR path** is needed: no PDF rendering, tables or layout for screenshots. LayoutNet
(4.3M of the 8.1M) can be left out; the OCR stack alone is 3.9M parameters.

## 4. Three fixes OCRia's output needs before Laya sees it

1. **Links (security-critical).** OCRia garbled the phishing link in the test
   (`https://cib-egypt-verify.com/login` → `httpsl/cb-egypt-verfycom/iogn`). Plan:
   - find lines that look like a link: `http`, `www`, a dot followed by a known top-level domain, or a line the router sent to the Latin reader inside an Arabic message;
   - re-read just that crop with the Latin recogniser at a higher resolution;
   - accept the result only if it parses as a URL.

   **Never "correct" a link towards a known-good domain.** That would turn `cib-egypt-verify.com` into `cib.eg` and hide the scam. If a link cannot be read cleanly, Loupe marks it **"link present but unreadable"**. That is itself a risk signal in the phishing formula (`PHISHING-FORMULA.md`), and the item goes to the user.
2. **Digits and letters.** OCRia writes Persian-style digits (۰–۹) and letters (ی, ک) where the
   source has Arabic ones (٠–٩, ي, ك). Normalise before anything matches text: digits to one form
   (so amounts, OTPs and the `PiiRules` card/ID checks still fire), and ی→ي, ک→ك in Arabic lines.
   One small, tested function in `sources-common`, shared by every platform.
3. **Confidence travels with the text.** LiteParse returns per-word confidence. Words below a
   threshold are kept but flagged, and an item whose key words (amounts, links, names) are
   low-confidence is never acted on automatically — it follows the existing rule that OCR'd text
   is labelled as such (`PhoneItems.OCR_MARK`).

## 5. Licence — to settle before any code ships

The OCRia-nano-1 Model License (based on AI Pubs Open RAIL-M):
- free for commercial use under **$2M annual revenue and $2M total funding**; a paid licence above either;
- **a commercial licence is needed at any size for "any use in a document parsing, OCR or
  extraction product or service that competes with LlamaParse or LlamaCloud"**.

Loupe uses OCR internally, inside a privacy assistant, and does not offer parsing to anyone, so it
should fall outside that clause — but that is LlamaIndex's reading to confirm, not ours. Draft for
the owner to send (https://www.llamaindex.ai/contact):

> We're building Loupe, an on-device privacy assistant for phones and desktops. We'd like to use
> OCRia-nano-1 through LiteParse to read text in a user's own screenshots and photos, entirely on
> their device, so the assistant can warn them about scams. We don't offer document parsing, OCR or
> extraction to anyone as a product or API. Could you confirm this use doesn't count as competing
> with LlamaParse or LlamaCloud, and what licence would apply once we pass $2M in revenue or
> funding?

LiteParse itself (Apache-2.0) and ONNX Runtime (MIT) need only the usual notices in
`THIRD_PARTY_NOTICES.md`.

## 6. Order of work (each step needs its own "Go")

1. **Licence answer** from LlamaIndex.
2. **Spike, Mac app:** the one-function bridge plus the two-ORT check; read the 11 test pages and
   reproduce the cloud numbers (77% / 33 of 42).
3. **The three fixes** in `sources-common`, with tests: the garbled link becomes "unreadable",
   never a brand domain; Persian digits match an OTP; low-confidence amounts are flagged.
4. **Spike, Android:** cross-compile, APK size, speed on a mid-range phone.
5. **Wire into the sources:** desktop images and Android photos feed `PhoneItems.photo(ocrText)`
   the way Vision does on iOS.

## 7. What is not known yet

- **Real screenshots.** Eleven synthetic pages are a smoke test. Before shipping, measure about 50 of
  the owner's own screenshots (scam SMS, bank apps, WhatsApp), on the owner's Mac only, and commit
  only the scores.
- **Android speed and memory** on a mid-range phone, not a laptop.
- **OCRia vs Apple Vision on the same pages,** to know how far Android/Mac reading is behind iPhone.
- **Handwriting and stylised fonts:** OCRia was weakest on the calligraphic Amiri letter (69%).
