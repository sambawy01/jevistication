# Backlog

The owner approves ideas; this file records the approved ones. **Everything here is planned, not
built.** Nothing is started until the owner says "Go" (`CLAUDE.md` §1). Each item names what it
builds on, so the work starts from code that exists rather than from a blank page.

Approved 2026-09-26 (relayed from the Loupe Station session). The user-facing name of the model is
**the Loupe Decision Model** (PRODUCT.md §11); `Choice`, `Score` and `Noul` below are the upstream
question types, used here as technical terms only.

| ID | Idea | Platform | Tier | Depends on | Status | Source |
|---|---|---|---|---|---|---|
| BL-1 | Scam checking for WhatsApp / SMS messages as they arrive | Android | Free | Android A0–A4 (see ANDROID-PLAN.md) | approved, not started | JevBystander |
| BL-2 | "Before you pay" checklist for rental ads, job offers and listings | iOS, Android | Free | Templates, site checks | approved, not started | jev-rental |
| BL-3 | Scam-post labels in social feeds | Station extension; mobile limited | Free | Station browser extension; Loupe for Safari | approved, not started | owner |
| BL-4 | Bank-statement CSV import with a model pass for unknown columns | iOS, Android, desktop | Free | `CsvRows`, `RecurringMoney` | approved, not started | jev-table-import-mapper |
| BL-5 | Split scanned PDF bundles into separate documents | iOS, Android, desktop | Free | PDF extraction + OCR, `ExpiryRadar` | approved, not started | DocJev |
| BL-6 | Local "does this action match what you asked" gate for the assistant | iOS, Android, desktop | Paid (assistant) | `agent-tier` branch: `AgentGate`, `ActionGuard` | approved, not started | pi-heed, pi-verdict, Reflex |
| BL-7 | Local gate before paid LLM calls | all | Paid (assistant) | `AgentGate` | approved; **already built** (no change) | wakegate |
| BL-8 | Explicit "unclear" option where a forced pick is risky | all | Free | `Template.Shape.Pick.noOp` | approved, not started | TryJevAI |
| BL-9 | Public calibration page: gated accuracy at coverage, with sample counts | website | n/a | measurement harness, a real labelled set | approved, not started | owner |
| BL-10 | "Loupe Wrapped": a yearly on-device recap to share | iOS, Android | Free | watchers, Spotted log, ledger | approved, not started | owner |
| BL-11 | Consider listing Loupe on `yibie/awesome-jev` | n/a | n/a | BL-9 or another measured number helps | approved, not started (finding below) | owner |
| BL-12 | "Is this a scam?" from anywhere: text, link, screenshot or QR photo | iOS, Android | Free | Check a link, Share extension, clipboard intent, scam templates; Arabic calibration (notes) | approved, not started | Station research |
| BL-13 | Egypt / MENA "official channel" trust pack | all | Free | Brand list, pack format; ongoing curation | approved, not started | Station research |
| BL-14 | QR "look before you scan", plus a venue self-audit | iOS, Android | Free | BL-13 helps; phishing formula | approved, not started | Station research |
| BL-15 | Remote-access app warning | Android | Free | Android A0–A4; BL-1 | approved, not started | Station research |
| BL-16 | Kids' chat protection | Android first | To decide | Android A4b; legal review; measured false-positive rate | approved, not started | Station research |

---

## BL-1 — Scam checking for WhatsApp / SMS (Android)

**What.** Judge incoming WhatsApp and SMS messages on the phone and warn on likely scams. Egypt and
MENA first: WhatsApp scams, InstaPay / Vodafone Cash transfer fraud, fake delivery SMS. The headline
Android differentiator. Full plan: [`ANDROID-PLAN.md`](ANDROID-PLAN.md), *Message scam check*.

**Builds on.** The shared phishing formula and link verdicts (`loupe-kit/.../kit/site`,
`loupe-kit/.../kit/mail`, engine `SiteFraud.kt`); the downloaded lists and Spotted log
(`ios/Loupe/Protection/ProtectionStore.swift`, to be ported); `Impersonation.kt` and the address-book
feed in `loupe-kit/.../kit/watchers/WatcherRun.kt`; Android B8 (NotificationListenerService) in
ANDROID-PLAN.md.

**Feasibility.** Android: `NotificationListenerService` (Play-sanctioned, opt-in) reads message
previews; accessibility for the open chat only in the direct build (PRODUCT.md §7). iOS cannot read
other apps' messages; it offers Send to Loupe, the clipboard check and the Loupe keyboard instead.

**Open questions.** See ANDROID-PLAN.md.

## BL-2 — "Before you pay" checklist

**What.** Paste a rental ad, job offer or marketplace listing. Each claim in it is sorted into
*verify on site* / *demand evidence* / *high-risk pitch*, and the user gets the questions to ask
before paying a deposit or fee. Warns, never blesses: no "this listing is safe" outcome.

**Builds on.** The Paste control and link check (`ios/Loupe/Protection/LinkCheckView.swift`);
Send to Loupe (`ios/Shared/SharedInbox.swift`); the template library (`templates/.../TemplateLibrary.kt`,
`Template.kt`) for a `Choice` per claim; the site checks for any link or phone number in the ad.
Claim splitting is mechanical (sentences, bullet lines); the questions come from fixed text per
bucket, not generated prose (PRODUCT.md §4).

**Feasibility.** iOS and Android the same: text in, no special permission. Arabic listings rely on
the model's multilingual reading, which is unmeasured for Egyptian Arabic.

**Open questions.** Where it lives (Guard → Protection, or Judgments)? Which markets' patterns ship
first (Egypt rentals, Dubizzle / Facebook Marketplace)? A labelled set to measure it against.

## BL-3 — Scam-post labels in social feeds

**What.** Label fake giveaways, investment scams and impersonation posts in social feeds. Mainly
Loupe Station's browser extension (separate repo).

**Builds on.** Station's extension; on the phone, Loupe for Safari (`ios/LoupeSafari/`,
`ios/Shared/Protection/`), which today sends only the top-level website name to the app.

**Feasibility.** iOS: the Safari extension could read post text on sites the user allows, but that
is a new data path (page content, not just a name) and a model call per post while scrolling; only
for Safari, not the Facebook / Instagram apps. The in-app browser the assistant would drive does
not exist (AGENT.md §7: on iOS the agent prepares and hands off). Android: no Chrome extensions; only
the planned in-app WebView (ANDROID-PLAN.md, A6).

**Open questions.** Is mobile in scope at all, or Station only? Battery cost of per-post checks.

## BL-4 — Bank-statement CSV import with a model pass

**What.** Map any bank's CSV export onto date / amount / debit / credit / merchant / description /
currency: a deterministic column-name pass first, then one `Noul` per remaining (column, field)
pair, with unmapped columns left visible below a threshold rather than guessed. Closes the
`RecurringMoney` gap for charges that never send an email receipt.

**Builds on.** `sources-common/.../common/CsvRows.kt` already does most of the deterministic half:
encoding and delimiter sniffing, header names in English and Arabic for all seven fields, a
value-based fallback (the column whose cells read as dates / amounts / text), and amount parsing
(both decimal styles, parentheses, Arabic-Indic digits, currency markers). Rows with a date and an
amount carry `Row.money`, and `WatcherRun.charges` (`loupe-kit/.../kit/watchers/WatcherRun.kt`) already
feeds money CSVs into `RecurringMoney.census` (tested by `InboxChargesTest`). The Inbox imports CSVs
on iOS today (`ios/Loupe/Sources/Inbox/`). So the new work is only the model pass for columns
neither the names nor the values settle, plus a confirm step.

**Feasibility.** Shared Kotlin; same on iOS, Android and desktop.

**Open questions.** Confirm the mapping once per bank (a remembered header signature) or per file?
Which Egyptian bank exports to collect as fixtures?

## BL-5 — Split scanned PDF bundles

**What.** A scan of several documents in one PDF (a passport, an insurance card, three receipts)
becomes separate documents, so receipts and the expiry radar see each one on its own.

**Builds on.** PDF text and on-device Vision OCR on iOS (`ios/Loupe/Sources/AppleExtractors.swift`,
`OcrPolicy`, off by default); PDFBox on the desktop (`sources-desktop`); ML Kit on Android (planned,
B4). `ExpiryRadar.kt` and the receipts templates consume the result.

**Feasibility.** Mechanical first (page-size change, "Page 1 of", blank separator pages), then one
`Noul` per page boundary: "does this page start a new document?" Output is page ranges as
sub-items; the original file is never rewritten (desktop actions are preview-only, PRODUCT.md §12).

**Open questions.** Sub-items only, or also an "export as separate PDFs" action through the review
queue? OCR cost on a phone for long scans.

## BL-6 — "Does this action match what you asked" gate

**What.** Before the assistant runs a prepared action, a local `Choice` (allow / ask / deny) on
whether the action matches the user's request. Deterministic rules settle clear cases first;
errors and timeouts deny.

**Builds on.** The `agent-tier` branch (not merged): `agent/.../AgentGate.kt` (which items may reach
a provider) and `agent/.../ActionGuard.kt` (the never list as code). This gate is the third check:
not *is the item worth sending* and not *does the action break a promise*, but *is this what was
asked*. It sits in `AgentSession` between parse and guard; `ask` becomes the existing review step.

**Feasibility.** Pure shared Kotlin, runs on the device; same everywhere. Only matters in the paid
assistant tier, where actions exist.

**Open questions.** Every action already needs a person's approval (`ReviewQueue`), so does *deny*
hide the proposal or show it marked? Needs a labelled set of request / action pairs.

## BL-7 — Local gate before paid LLM calls

**Confirmation, no change.** The wakegate pattern (ask a cheap local question before paying for an
LLM call) is what `AgentGate` already does: only a `Decision.Act` above 0.80 (floored at 0.60)
reaches a provider, and `GateResult.summary` reports how many items never left the device
(AGENT.md §1, §8).

## BL-8 — Explicit "unclear" option

**What.** Where a forced pick is risky, the question offers an explicit *unclear* option, and
choosing it sends the item to the Unsure queue instead of acting. Example: "a date is mentioned" vs
"this is the expiry date" vs "unclear".

**Builds on.** `templates/.../Template.kt` already has `Pick.noOp` ("not sure / none of these"), and
some library templates use it (`TemplateLibrary.kt`, e.g. the keep-or-discard and sender-kind
templates); `UserJudgment.kt` recognises no-op words. `ExpiryAlert.dateWasAmbiguous` covers the
*format* ambiguity; this covers the *meaning* ambiguity.

**Feasibility.** Shared Kotlin. The work is an audit: which yes/no templates should become
three-way, and does the policy treat the no-op as abstain. First candidate: `expires-before`, a
yes/no whose expiry date is "the latest date on a document", which is exactly where a mentioned date
can pass for the expiry.

**Open questions.** Does adding the option cost accuracy on clear cases? Measure before and after.

## BL-9 — Public calibration page

**What.** A page on the Loupe website showing, per judgment, accuracy at a stated coverage (the
share it answered) with the sample count beside every number. Only measured numbers, per the
website's honesty rule.

**Builds on.** `engine/.../Harness.kt`, `VisibleCalibration.kt`, `Calibration.kt`, `OffPolicy.kt`, and
the risk–coverage reporting in PRODUCT.md §6 and §9.

**Feasibility.** Website only. It needs a real labelled set first: today's figures are on 45
synthetic items, where the untuned model loses to keyword baselines (PRODUCT.md §12). Publishing
that loss is allowed; publishing a number without its sample count is not.

**Open questions.** Which judgments first? Where the labelled data comes from (the owner's own, or a
public set per judgment)?

## BL-10 — Loupe Wrapped

**What.** A yearly recap card to share: scams caught, subscriptions found, documents expiring. Made
on the device from local counts; the shared image carries counts only, no names, merchants or
website names.

**Builds on.** The Spotted log (`ProtectionStore.swift`), Guard's watcher findings
(`ios/Loupe/Guard/GuardModel.swift`, `loupe-kit/.../kit/watchers/WatcherFindings.kt`), and the ledger.

**Feasibility.** iOS and Android the same; share sheet / share intent. Spotted keeps 90 days only,
so a yearly count needs a separate running tally.

**Open questions.** Counts only, or opt-in details? The Spotted retention rule vs a yearly total.

## BL-11 — Listing on awesome-jev

**Finding (read 2026-09-26 from the repo's README and CONTRIBUTING.md).** The list collects projects
that use **Jev**, TypeSafe AI's System One model, "or a documented Jev port/derivative", and whose
source names Jev, cites System One models, or shows a typed-decision loop. It has no licence rule
(its checklist only warns readers when an entry has none), and no platform rule: Android and macOS
apps are listed (JevBystander, Qualm). It also requires real code, a runnable check, numbers
traceable to the linked page, one sentence per entry, one category, and disclosure of AI-generated
work.

**Does Loupe qualify?** Partly. For: this repository is public and MIT-licensed, it runs typed
`Choice` decisions with confidence → act / abstain / queue, and it has runnable checks
(`./gradlew check`, the iOS tests). Against: Loupe does not use Jev. Its model is built on Laya
(`convaiinnovations/laya-multilingual`), an independent Apache-2.0 model, and `LICENSING.md` records
that nothing indicates Laya was distilled from Jev, so it is not a documented Jev port. The list does
name adjacent models (Qualm runs on Jev or Kev; Switchboard names Laya as "coming soon"), but each
of those also uses Jev, so acceptance of a Laya-only app is the maintainers' call.

**If pursued.** Ask the maintainers in an issue first. Candidate category: Verification & Guardrails
(the phishing check) or Classification & Routing (judgments). An entry must name the upstream model,
which is fine as a technical reference but must not become user-facing copy. Disclose AI-assisted
development. A measured number (BL-9) would strengthen it. Nothing has been submitted.

## BL-12 — "Is this a scam?" from anywhere

**What.** Size S; free; the acquisition hook. Hand Loupe any text, link, screenshot or photo of a
QR code and get one verdict card, which can be read aloud in Arabic or English. On iOS this is the
way to check a WhatsApp forward, since Loupe cannot read WhatsApp there (BL-1 is Android only);
Google's on-device scam detection is not offered in Egypt.

**Questions, in one pass.** A `Choice` for the lure: *prize, account verification, delivery fee,
job, investment, relative in need, marketplace deposit, government benefit, none*. A `Noul` for
*asks you to move money*, and a `Noul` for *asks for a one-time code or PIN*. The same pass fans out
the existing `phishing`, `pressure-tactics` and `asks-for-secrets` templates
(`templates/.../TemplateLibrary.kt`). Links go through the shared phishing formula first, as now.

**Builds on, and the overlap.** Links are already covered three ways: Check a link
(`ios/Loupe/Protection/LinkCheckView.swift`), the Share extension's on-device link verdicts
(`ios/LoupeShare/ShareViewController.swift`), and the clipboard checks (`ios/Loupe/Clipboard/`: the
no-prompt detection chip, the "Check what I copied" App Intent in `CheckClipboardIntent.swift`, the
widget in `ios/LoupeWidgets/`), plus the on-device Loupe keyboard (`ios/LoupeKeyboard/`,
`ios/Shared/Clipboard/KeyboardCheck.swift`). **What is new:** plain text with no link, screenshots
(Vision OCR, as in `ios/Loupe/Sources/AppleExtractors.swift`), QR photos (decode on the device, then
BL-14's checks), the lure questions, and reading the verdict aloud. The intent grows from "Check what
I copied" into "Ask Loupe if this is safe".

**Feasibility.** iOS: Share extension plus App Intents / Siri, both already in the tree. Android: a
share target (ANDROID-PLAN.md, B1) with the same shared Kotlin. The share extension's memory limit
(each extension already links LoupeKit, about 46 MB in Debug) may keep the model call in the app.

**Open questions.** Does the model run inside the Share extension or hand off to the app? Arabic
accuracy is not yet measured and gates the copy (see *Engineering notes* below). Voice: the system
voices only, and never a "safe" verdict read aloud (the never list).

## BL-13 — Egypt / MENA "official channel" trust pack

**What.** Size S, plus ongoing curation; free. A local, downloadable registry of the real sender IDs,
SMS short codes, domains and WhatsApp business numbers of CBE, InstaPay, Vodafone Cash / e&, Egypt
Post, Aramex, the banks and the ministries. **The model decides only which organisation a message
claims to be; code checks whether the channel really belongs to it.** A mismatch adds suspicion; a
match never clears a message (content can only add suspicion, PRODUCT.md §4).

**Questions.** A two-level `Choice`: first the sector (*bank, wallet, courier, government, telecom,
retailer*), then the organisation within that sector, so no option list has more than 10 entries
(see *Engineering notes*: the upstream `Choice` saturates with 11 or more options). Plus a `Noul`
for *claims to be support staff*. It feeds BL-12, BL-14 and the Android message check (BL-1).

**Builds on.** The shared brand list `loupe-kit/.../kit/site/Brands.kt` (ported from Station), which
already holds names, domains and host-name tokens and doubles as the known-good list, but has **no
Egyptian brands yet** (InstaPay, Fawry, CIB, NBE, Vodafone Cash). Adding them is a small first step.
The downloadable part can reuse the lists' App Group delivery (`ios/Loupe/Protection/ProtectionStore.swift`)
and the pack format's validation (`loupe-kit/.../kit/packs/PackFormat.kt`).

**Feasibility.** Shared Kotlin data plus a signed download; same on every platform.

**Open questions.** Who curates it, and how is each entry verified (the organisation's own published
page)? How often is it updated, and how is a stale entry handled? Station and Loupe must share one
list.

## BL-14 — QR "look before you scan", plus a venue self-audit

**What.** Size S; free. Photograph a QR code; Loupe decodes it on the device and judges the payload
together with the sticker's OCR'd text, before the user opens anything. **Venue mode:** a business
photographs its own table and parking QR codes weekly to catch stickers placed over them (the
owner's restaurants are the pilot).

**Questions.** A `Choice` for what the payload is: *payment page, login page, app download, Wi-Fi
join, menu or info, contact*. A `Noul` for *the payload does not match its context* (a "menu" sticker
that opens a payment page), and a `Noul` for *demands payment or a login*. Links then go through the
phishing formula; BL-13 checks any organisation the sticker claims.

**Builds on.** The phishing formula and link verdicts (`loupe-kit/.../kit/site`, Check a link), Vision
OCR (`AppleExtractors.swift`). Decoding is platform code: Vision barcode detection on iOS, ML Kit
barcode scanning on Android. Venue mode compares each week's decoded payloads with the first
approved set, which is mechanical.

**Feasibility.** iOS and Android both decode QR codes on the device with system or ML Kit APIs.

**Open questions.** Venue mode's home: the consumer app, or a business pack? What a venue does when a
code changed (alert only; Loupe never edits anything).

## BL-15 — Remote-access app warning (Android)

**What.** Size M; free; Android only. Two parts: (1) a `Noul` on messages for
*tells you to install an app or share your screen* (AnyDesk-style "support" scams); (2) a list of
newly installed apps that hold accessibility, SMS or other powerful access, each sorted by a
`Choice` over its store description: *remote control, SMS reader, loan app, utility*. Full plan in
[`ANDROID-PLAN.md`](ANDROID-PLAN.md), *Remote-access app warning*.

**Builds on.** BL-1's message check (the `Noul` is one more question in the same pass); the review
queue for "look at this app" findings (Loupe never uninstalls or changes anything).

**Feasibility.** Android only. iOS apps cannot see other installed apps.

**Open questions.** Play's `QUERY_ALL_PACKAGES` / package-visibility policy (see ANDROID-PLAN.md).
Where the store description comes from without a network call.

## BL-16 — Kids' chat protection

**What.** Size M; tier to decide. The owner calls it "a huge differentiator". On the child's Android
phone, screen game, Discord and messaging chats for grooming and gift-card lures, with `Noul`s for
*asks to move to a private chat*, *offers in-game currency or a gift card*, *asks for photos or
location*, and *says to keep it secret*. Alerts to the parent carry **no content**: the category, the
confidence and the time only. Full plan in [`ANDROID-PLAN.md`](ANDROID-PLAN.md), *Kids' chat
protection*.

**Hard requirements, before any build.**

1. **Consent and transparency:** the child knows it is on, in age-appropriate words.
2. **Google Play:** the stalkerware / `isMonitoringTool` policy (a persistent notification,
   disclosure, the parental-control declaration) and the AccessibilityService declaration.
3. **iOS is heavily limited:** check Apple's Screen Time APIs (FamilyControls, ManagedSettings,
   DeviceActivity) for anything usable; they are not known to expose message content. Android first.
4. **Legal review:** children's data, Egypt's Personal Data Protection Law (151/2020), and COPPA /
   GDPR-K if sold abroad.
5. **A measured false-positive rate** before any launch claim.

**Builds on.** BL-1's message reading and judging path; the `Noul` pass and the review queue.
Reference designs (MIT; both call hosted Jev, so reuse the question design only, not the code path):
`brainstormity/Jev-Moderation-Bot` and `CodeAlive-AI/mastra-jev-moderation` (which reports 9 of 9
hostile messages blocked and 0 of 49 false positives on its own set).

**Feasibility.** Android: notification previews (Play) or the visible chat through accessibility,
**allowed in the Play build for this feature only (owner decision, 2026-09-26)**, as a scoped
exception in PRODUCT.md §7, declared to Google as parental control. Conditions: on only in kids'
mode on the child's device; a persistent, non-dismissible "Loupe protection is on" notification
while it runs; the `isMonitoringTool` / parental-control and accessibility-use declarations, with a
prominent in-app disclosure and consent screen before it is turned on; it reads only the configured
chat and game apps, stores no text, and sends only content-free alerts through the encrypted relay
below. Outside kids' mode, §7's exclusion is unchanged. iOS: probably not possible beyond Screen
Time controls.

**Parent alerts: decided (owner, 2026-09-26) — an end-to-end encrypted relay.** Recorded as a
scoped exception in PRODUCT.md §4: for this feature only, the promise is "no server can read
anything" instead of "no Loupe server".

- The child's device encrypts each alert to the paired parent device's public key; pairing is in
  person (for example, a QR code carrying a key exchange).
- The payload is content-free: category, confidence, timestamp and at most the app name. Never
  message text.
- Transport: a push service (FCM and/or APNs, or a minimal Loupe relay) that carries only ciphertext
  it cannot read and stores nothing beyond short-lived delivery queues.
- Obligations: the privacy policy discloses the relay; key rotation and unpairing; replay
  protection; and the relay necessarily sees metadata (timing and device tokens), which is stated.

**Open questions.** Free or paid? Where a labelled
grooming set comes from, ethically.

---

## Engineering notes: model findings (seen by the owner, not approved changes)

These are findings about the upstream model, recorded so the items above are not claimed beyond
what it can do. None of them is an approved change.

1. **Arabic is weak and uncalibrated out of the box.** The upstream model's Arabic intent accuracy
   is reported at only 0.11–0.40, uncalibrated. Calibration per language and per question type must
   come before any claim about Arabic scam detection; this gates the copy for BL-12, BL-13 and BL-16
   (and BL-1). The receipt gate measurement in `BUILD.md` (Progress log, 2026-09-25) points the same
   way: a real Arabic receipt got 0.46 yes and fell to 0.32 under a sharper wording, and the English
   till receipt got 0.11 yes under either.
2. **Candidate order changes the answer** (upstream Hugging Face discussion #9 on
   `convaiinnovations/laya`). Mitigation: ask with the options in several orders and average.
   `BUILD.md` records the same weakness in the game: the mirror probe (a mirrored scene steered the
   mirrored way only 74%, 43% after the word-bias correction) and the word bias ("right" 0.74
   against "left" 0.26 with every way described identically), now divided out by content-free passes
   (`GAME-FINETUNE-PLAN.md` §0).
3. **The `Choice` saturates with 11 or more options** (upstream discussion #2): keep option lists at
   10 or fewer, as BL-13's two-level question does. The template library already caps a choice at
   12 (`Template.kt`); lowering the cap to 10 is a candidate change.
4. **`Score` is the weakest question type.** Try cumulative `Noul`s instead ("at least somewhat
   urgent?", "at least very urgent?"). `LAYA-UPGRADE-MEASURE.md` shows how fragile it is: reversing
   the order of the score levels changed 21 of 45 answers of `urgency`, the only score template.

### Second research pass (2026-09-26, from `hellogumbo/awesome-jev`)

Findings from public projects, relayed from the Loupe Station session. Each names its source and
licence; "no licence" or "licence not given" means ideas only, and nothing may be copied from it. Findings, not approved
changes, except where marked as a plan refinement.

5. **Fine-tuning can collapse confidence (the most important).** In
   `github.com/yuvrajrox/laya-jev-eval` (no licence, ideas only) a fine-tuned upstream model gave
   **1.00 confidence on wrong answers**, so no threshold could separate its errors; zero-shot, its
   errors sat at 0.37–0.84. The authors blame training the decision head directly with gradients,
   which left the escalation head untrained. For any fine-tune (game, Arabic, scam), three
   requirements follow: a proper scoring rule as the loss (soft cross-entropy on label
   distributions plus a Brier term); a temperature per question type fitted afterwards, bounded to
   [0.25, 5]; and **"confidence spread on wrong answers" as a release gate**. Reference recipe:
   `github.com/intikhab49/open-jev-typed-decision-engine` (Apache-2.0): ModernBERT-150M, 30 minutes
   on a Colab T4, ECE 0.156 → 0.057. Folded into `GAME-FINETUNE-PLAN.md` §5 as plan requirements.
6. **Head-only fine-tuning works.** `huggingface.co/ichenney/laya-browser-v32b` (Apache-2.0) keeps
   the encoder byte-identical to upstream and trains only the 36-tensor decision head (about 59.5k
   synthetic items, lr 1e-4, bf16, about 2 GPU-hours on an RTX 3080) for +17 to +29 points. So game,
   Arabic or scam heads could ship as small downloads on top of the existing 384 MB graph. This
   supports `GAME-FINETUNE-PLAN.md`'s option C and its delta-download idea, and gives a GPU estimate
   (plan refinement, cited there).
7. **Teacher-trained heads with a promotion lifecycle.** `github.com/bladedevoff/stuntd`
   (Apache-2.0): small heads on frozen upstream embeddings, trained from a teacher's answers (at
   least 300 captures per decision site); shadow → live at 0.99 calibrated agreement → 2% of live
   traffic re-checked → automatic demotion on drift. **Idea for the paid assistant (evaluation only;
   needs owner approval):** the provider's answers on items the user already sent become the teacher
   for per-user local heads, so the paid tier improves the free tier. Privacy: those answers are
   already on the device (`EgressRecord`, AGENT.md §5) and nothing new leaves it, but the privacy
   page would have to say that provider answers train a local head.
8. **Self-vouching text flips verdicts.** Text such as "verified by CIB security" inside the item
   flips typed-decision verdicts; for Jev, 96.5% → 26.5% (`github.com/zkousama/jagged`; licence not given; ideas only). Station is
   measuring the upstream model's exposure. Affects BL-12, BL-13 and the `phishing`,
   `pressure-tactics` and `asks-for-secrets` templates. Mitigations to evaluate: strip or neutralise
   self-claims before the model sees the text, and have code check the claimed channel, which is
   BL-13's design already (and PRODUCT.md §4: page content never raises trust).
9. **Upstream-specific lessons.**
   - **Absence questions fail.** "X is missing" should be rephrased as a presence check
     (`github.com/PerryLink/llm-jev-laya-bench`; licence not given; ideas only). Worth a lint rule.
   - **Calibration drifts in opposite directions by question type:** `Noul` underconfident
     (T ≈ 0.66), `Choice` overconfident (T ≈ 1.30), `Score` strongly overconfident (T ≈ 1.92)
     (`github.com/scienthoon/jev-ood-calibration`, MIT). Calibrate per template type × language,
     never globally; the engine already calibrates per judgment × source × option count
     (PRODUCT.md §8), and language is the missing axis.
   - **Numbers inside a document are not preserved** (45% came out as 6.6%): always extract numbers
     and compute in code (`github.com/KantaHayashiAI/jev-does-not-play-dice`; no licence stated,
     ideas only). We already do: the receipt gate is mechanical evidence (BUILD.md 2026-09-25),
     `ExpiryRadar` and `RecurringMoney` do the arithmetic in code, and the game's scene words are
     categories, not sums.
   - **A `Choice` with no none/unclear option failed confidently** (≥ 0.9) 36% of the time
     (`github.com/suraj-phanindra/wellposed`, MIT, a 45-rule template linter). Station is running
     it read-only over our templates. Reinforces BL-8.
   - **More than 20 options:** split into interleaved chunks. **Mixed Arabic/English pages:**
     filtering distractors by script beat reordering them
     (`github.com/ChenneyZhuang/laya-browser-agent`, Apache-2.0).
   - **A smaller download.** `github.com/yzfly/edgejev` (Apache-2.0): per-tensor dynamic INT8 of the
     upstream model is 1,290 → 324 MB at 15.6 ms a question (4 vCPU), with an explanation of why the
     model tolerates quantisation. Ours: `int8` 384 MB (the 22 `mlp.Wo` kept FP32) and `int8-partial`
     357 MB; BUILD.md risk 13 records that the full ~58 MB saving was tried and rejected because it
     changed answers. Worth re-testing their recipe against golden 34/34 and criteria 8/8.
