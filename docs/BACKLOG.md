# Backlog

The owner approves ideas; this file records the approved ones. **Everything here is planned, not
built.** Nothing is started until the owner says "Go" (`CLAUDE.md` §1). Each item names what it
builds on, so the work starts from code that exists rather than from a blank page.

Approved 2026-09-26 (relayed from the Loupe Station session). The user-facing name of the model is
**the Loupe Decision Model** (PRODUCT.md §11); `Choice`, `Score` and `Noul` below are the upstream
question types, used here as technical terms only.

| ID | Idea | Platform | Tier | Depends on | Status | Source |
|---|---|---|---|---|---|---|
| BL-1 | Scam checking for WhatsApp / SMS messages as they arrive | Android | Free | Android A0–A4 (on hold, see ANDROID-PLAN.md) | approved, not started | JevBystander |
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
