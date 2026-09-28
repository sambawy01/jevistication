# Loupe for iPhone: Home · Ask · Me (redesign)

Status: design approved in conversation with the owner, 2026-09-28. Spec awaiting owner review.
No code until the owner says **Go** (repo working agreement).
Mockups (open in a browser): `2026-09-28-mockups/final-screens.html`, `tracking.html`, `ask-composer.html`.

## 1. Why

The owner feels lost in the app. Today there are five tabs (Now · Guard · Judgments · Sources · Me),
features split across places (Mail vs Mail triage; templates vs write-your-own vs Web questions), and
no way to simply ask the model about a photo or a question. The trackers (subscriptions, expiry)
caught nothing on the owner's phone.

**Goal:** a first-time user in Egypt can, without hunting: ask something, see what needs them, check
they're protected, and see their subscriptions and expiring documents.

**Success criteria**
1. Three places only: Home, the Ask button, Me. Every feature has one home.
2. Ask answers a question about a photo, file, link or pasted text in one screen, and says *before
   sending* what kind of answer it will be and whether it is free (on the phone) or paid.
3. Every answer anywhere shows its reference.
4. Subscription and expiry tracking find the owner's real items (see §7) and pass the measured bar.
5. The 13 scenario journeys (rewritten), relaunch persistence and the button audit pass.

## 2. Decisions (owner, 2026-09-28)

| # | Decision |
|---|---|
| D1 | Structure **B**: a **Home** screen, a large **Ask** button in the tab bar (reachable from every screen), and **Me**. |
| D2 | One tap from opening: **Ask**, **Money & documents**, and one merged **alerts + protection** place (on Home). |
| D3 | Free Ask answers **decisions** on the phone. Open questions are offered to the paid **Personal Assistant**, and the app explains the difference *before sending*. |
| D4 | Ask layout: **Question** card → **Decision needed** card → optional **What counts** card → "Decide on this iPhone". |
| D5 | The paid choice is labelled **"✍ Plain-text answer · Unlock Personal Assistant"**. |
| D6 | Unsure answers: **a badge on the Ask button** opening **one swipe stack** across all questions. |
| D7 | After an answer: one tap **Keep asking** (my photos / files / mail) saves it as a question that runs by itself. Built-in templates become **suggestion chips**. |
| D8 | Web questions (currency, weather, trains, flights) live **inside Ask**, marked **Online**. |
| D9 | The game lives in **Me → See Loupe think**, plus a one-time invite after onboarding. |
| D10 | **One place per thing**: Mail and Mail triage merge; the same check applies everywhere. |
| D11 | **Every answer always shows its reference.** |
| D12 | Look: the same dark theme, calmer (fewer glows, one accent), one job and one primary action per screen. |
| D13 | **Tracking must actually work** (§7), measured before it ships. |

## 3. Information architecture

| Place | Contents | Replaces |
|---|---|---|
| **Home** (launch) | 1. **Needs attention** (only when non-empty): risky items across Safari, clipboard, shared links, mail phishing, impersonation, site fraud, price rises, review proposals; each with one primary action and its reference. 2. **Quick check**: *Check a link*, *Check what I copied*. 3. **Money** card → Subscriptions (§6.2). 4. **Documents** card → Expiring (§6.3). 5. **Protected**: Safari, keyboard, clipboard, "0 bytes out"; a Turn on button for anything off. Empty states say what to connect, with a button. | Now, Guard |
| **Ask** (centre button, any screen; badge = unsure count) | The composer (§4); **Your questions** (saved "keep asking" questions, each with its dashboard); suggestion chips; online questions. | Judgments (templates, write your own, results, Measure entry), Web questions, flights |
| **Unsure stack** (from the badge) | One card at a time: the item, what was read, Loupe's guess and confidence; ✗ No · Skip · ✓ Yes; Undo. | Unsure queue |
| **Me** | **What Loupe reads** (each source: switch + count; **Mail** is one place: connect, sync, found: phishing / needs a reply / subscriptions, actions, Scan again, Remove); **Personal Assistant** (paid; Unlock); **Decision model**; **Your data** (export, delete all); **See Loupe think**; **Advanced** (Measure, packs, diagnostics, online checks, model settings, reminders); **About** (version, privacy, terms, licences). | Sources, Me, Mail triage |

Removed as separate places: the Now, Guard, Judgments and Sources tabs; the Mail triage screen; the
Web tab; template and write-your-own screens.

## 4. Ask

### 4.1 Composer
- **Question**: free text (Arabic, Egyptian, Franco, English) plus at most one attachment:
  📷 Photo (camera or library), 📄 File, 🔗 Link, 📋 Paste. An attachment alone with a chip is valid.
- **Decision needed**: exactly one of

| Kind | Model question | Default answers (editable) |
|---|---|---|
| Yes / No | two-option | Yes · No |
| Do / Don't | two-option | Do it · Don't do it |
| Go / No-go | two-option | Go · Don't go |
| Pick one… | Choice | user-entered, 2–10, plus "None of these" added |
| Score 1–5 | Score | labelled **rough** |
| ✍ Plain-text answer · Unlock Personal Assistant | provider (paid) | n/a |

  Two-option answers are always plain opposites (model fix #7). Loupe does not invent labels; it
  offers the defaults and the user edits them ("Pay it / Don't pay it").
- **What counts** (optional): becomes the decision criteria sent to the model.
- **Live checks**: the absence-phrasing lint (suggests the presence wording); more than 10 options
  blocked, with "split the question or use None of these"; Franco text noted as low-trust.
- **Online questions**: currency, weather, trains and flights are detected; the card shows an
  **Online** badge ("sends only your query to Loupe's helper") and uses the existing web helper path.
- **Paid**: choosing Plain-text answer shows before sending: "A written answer comes from the Personal
  Assistant (paid). Your question and attachment are sent to **the named provider** (chosen with the paid tier) and nowhere else. Choices
  like Yes / No stay on this iPhone for free." Unsubscribed users see *Unlock Personal Assistant*;
  subscribers see *Send to <provider name>* after the consent sheet (Apple 5.1.2(i)).
- Primary button: **Decide on this iPhone** (free kinds) / **Unlock** or **Send** (paid).

### 4.2 Input reading
| Attachment | Read by |
|---|---|
| Photo | Vision OCR (Arabic + Latin, auto-detect) + image labels, as PhotosSource |
| File | the shared extractors (PDF text, OCR fallback, text, CSV, email) |
| Link | the site-risk facts (the Check a link pipeline); page content only for online questions |
| Paste | as is |
Text is cut to the model's `text_chars` budget; the cut is noted in the reference.

### 4.3 Who answers
- **Suggestion chips** are today's templates with their rules intact (receipt gate, phishing formula
  v1.3, duplicates, and so on). Rules answer first; the model answers the rest.
- **Typed questions** use the model only.
- Confidence below the question's threshold → **"Not sure. Your answer teaches it"** and the item joins
  the unsure stack. Franco questions always return Not sure.
- `use_calibration` stays OFF by default (owner decision, 2026-09-27).

### 4.4 Answer screen
The answer (big), the confidence bar and "decided on this iPhone"; 👍 Right / 👎 Wrong (a correction,
recorded exactly as today); the **Reference** card (§5); **Keep asking** (My photos / My files / My mail);
"Ask something else".

### 4.5 Saved questions ("Keep asking")
- Saving creates a `UserJudgment` with the chosen source scope and runs a sweep; new items are judged
  as they arrive (existing sweep and sort paths).
- **Your questions** lists them; each opens the results dashboard (donut, who answered, answers list;
  every row opens its reference); ⋯ holds Edit and Delete (existing flows).
- **Migration**: every existing judgment becomes a saved question on first launch of the new build,
  keeping its ledger rows, corrections, threshold and calibration. Idempotent; tested.

## 5. The reference block (every answer)
- **Source**: the photo, file, email, link or pasted text, with **Open original**.
- **Read**: the text Loupe read; when a rule decided, the matched words are highlighted.
- **Answered by**: the named rule with its matched evidence, or "the decision model (NN%)", or
  "you" for corrections.
- **Asked**: the decision kind, the answers and the What counts text.
- **When** and **what left the phone**: "0 bytes out", or "Online: only your query was sent to …".
- Honest limit, stated in the UI copy: the model gives a choice and a confidence, not reasons.
Applies to Ask answers, saved-question rows, the unsure stack, Home cards, Subscriptions and Expiring.

## 6. Screens
Mockups show real content shapes. Numbers are illustrative.

### 6.1 Home: see `final-screens.html` #1.
### 6.2 Subscriptions (`tracking.html` #8, #9)
Monthly total and yearly equivalent; a share bar; price-rise cards; next charges by date; "No charge in
N days" flags; per row Confirm · Not a subscription · Set aside; detail: verdict buttons, reference (the
receipts or emails it came from, highlighted lines), charge history; a paid "cancel for me" hook.
### 6.3 Expiring (`tracking.html` #10, #11)
Overdue · This month · Later, with days left in large numbers; detail: Right date · Wrong date · Not a
<kind> · Remind me; reference (source, the line read, who found the date, who decided the kind); a paid
"help me renew" hook. Reminders at 30, 7 and 1 days before (Me → Advanced).
### 6.4 Ask, Answer, Your questions, Unsure stack, Me, Mail: `final-screens.html` #2–#7, `ask-composer.html`.

## 7. Tracking must actually work

### 7.1 Why nothing was caught (read-only diagnosis of the owner's phone, 2026-09-28)
The phone held 1,226 files (576 with text), 466 photos (318 with text), 354 events and 7,653 contacts.
Mail was off (removed after the Gmail quota failure), so its scan held 0 items.
Causes in `loupe-kit/.../watchers/WatcherRun.kt`:
1. Subscriptions read charges **only from EMAIL items** (`charges()`), so files, receipts, statements
   and calendar are ignored.
2. `AMOUNT` matches only `£ $ €`, so EGP, ج.م and جنيه amounts are invisible.
3. `CHARGE_WORDS` are English only (charged, payment received, paid, receipt for).
4. `EXPIRY_WORDS` are English only (expir…, valid until/to/thru, renewal date), so 0 of 318 OCR'd
   photos matched, although Egyptian documents say تنتهي في / تاريخ الانتهاء / صالحة حتى.
5. Arabic-Indic digits are not read as dates (fixed by Station's parity branch: the digit fold, owner-approved).
6. The radar only surfaces documents nearing a 6-month rule; the approved design shows a full timeline.
Photo OCR is not the problem: it already auto-detects Arabic and Latin.

### 7.2 Required changes
- **Charges from every source**: email; files (PDF, image receipts, HTML); statement CSVs (via
  CsvRows, and BL-4's mapper when it lands); calendar events with amounts. Deduplicate the same charge
  seen in two sources.
- **Money**: EGP / ج.م / جنيه / LE / L.E. alongside £ $ €, Arabic and Persian digits via the parity fold,
  thousands separators in both scripts; amounts kept with their currency (no conversion).
- **Charge and subscription words**: EN + AR + Egyptian (e.g. اشتراك، فاتورة، تم الدفع، تم خصم،
  تجديد، مبلغ) plus Franco forms; the known Egyptian merchants (WE, Vodafone, Orange, e&, InstaPay,
  Fawry, Netflix, Spotify, Anghami, Shahid, and so on) as merchant hints.
- **Expiry words**: EN + AR + Egyptian (تنتهي، تاريخ الانتهاء، صالحة حتى، صالح لغاية، الصلاحية) and
  Egyptian document kinds (National ID, passport, car and driving licence رخصة, insurance, contracts).
- **Full timeline**: every expiry candidate is kept and grouped (overdue / 0–7 / 8–30 / later); the
  6-month rule stays as a highlight, not a filter.
- Everything keeps the rules-first, model-second split, and every row has its reference.

### 7.3 The measured bar (before this step ships)
- A labelled test set of real-shaped Egyptian and English documents: at least Netflix, Spotify, WE,
  Vodafone, InstaPay and Fawry receipts and emails, a bank statement CSV, a National ID, a car licence,
  a passport, an insurance policy, and 30+ everyday non-subscription / non-expiring items.
- **Recall ≥ 90%** for subscriptions and expiring documents on that set; **0 false positives** on the
  everyday items; results reported per language.
- An end-to-end test per tracker: source item → watcher → Home card → detail → reference.
- A check on the owner's phone (read-only pull plus on-screen confirmation).

## 8. Look
Same dark theme (Palette, Typeface), calmer: fewer glows, one accent (cyan), large plain headings,
plain words. Card pattern everywhere: title, one-line summary, one primary action, reference behind
it. At most one primary button per screen. Arabic and English with full RTL, Dynamic Type, ≥44 pt
targets, VoiceOver labels, Reduce Motion respected.

## 9. Testing
- The 13 scenario journeys rewritten for Home, Ask and Me, still relaunching and checking persistence,
  still auditing every button (hittable, ≥44 pt, no user-visible "Laya").
- New unit tests: decision kind → model question and default labels; the lint and option cap in the
  composer; photo, file, link and paste reading; the reference block contents per answerer; online
  detection; Keep asking → saved question; judgment migration (history, corrections, threshold kept);
  the unsure stack (count, undo); the tracking changes of §7 against the §7.3 test set.
- The device smoke scheme (non-destructive) and `docs/PREDEPLOY-CHECKLIST.md` updated for the new places.
- The full gate before every commit (targeted tests while working; the full suite once at the end).

## 10. Rollout
Five steps, each gated, committed, pushed and installed on the owner's phone:
1. **Shell**: Home, the Ask button and Me, hosting today's screens inside, so nothing breaks.
2. **Ask**: the composer, input reading, the answer screen and the reference block.
3. **Saved questions and the unsure stack**: judgments migrate, templates become chips, the old
   Judgments screens go.
4. **Home cards and tracking**: Needs attention, Money → Subscriptions, Documents → Expiring,
   Protected; the §7 tracking changes and the §7.3 measurement; Mail becomes one place.
5. **Cleanup and polish**: remove the old tabs and screens, and apply the calmer look throughout.

## 11. Dependencies and risks
- **Station's parity branch** (the Arabic digit fold, explicit regex classes) must land before §7's
  Arabic dates and amounts; step 4 waits for it, or includes the fold if parity is late.
- **The Personal Assistant** (paid) is designed on `agent-tier`; step 2 ships the Unlock entry point
  and the consent copy; the provider call arrives with the paid tier.
- **The model's weaknesses** (Arabic accuracy, Score): the UI says "rough" for Score, and Franco goes
  to review.
- **Migration** must not lose corrections: backed up before migration, with a restore path.

## 12. Out of scope
Android (the Station session), BOND, calibration switch-on, the fine-tune, Personal Assistant billing
and the key service, AI phone calls.
