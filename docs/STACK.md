# The decision stack: models, switchboard, first scan, and what stays on the phone

*Design document, 2026-11-24, branch `agent-tier`. Written at the owner's request; no code yet —
every build step below waits for "Go". Evidence for the model choices:
[`MODEL-TESTS-2026.md`](MODEL-TESTS-2026.md); decisions logged in [`BUILD.md`](BUILD.md).*

## 0. The owner's requirements (2026-11-24)

1. **Download once.** Models are downloaded during onboarding only. Once on the phone they stay and
   are reused; Loupe never downloads them again as a matter of course.
2. **The first scan is the user's call:** *now*, *overnight*, at a *time they pick*, or *whenever the
   phone is charging*.
3. **Results stay on the phone.** A finished scan is never redone; later runs only handle what is new.

Plus the stack decided the same day: Laya for Arabic and Franco, d1-omni-600M ("the nano") for English
and for photos without text, a text reader for photos with text, the built-in barcode reader for QR codes.

## 1. The stack

| Input | Handled by | On the phone |
|---|---|---|
| Arabic-script text, and Franco | **Laya** (`laya-multilingual` INT8 ONNX) | 384 MB |
| English text | **the nano** (`LiquidAI/d1-omni-600M`, text trunk) | ~0.4 GB once quantised (not yet exported) |
| Photo with no text | **the nano**, vision part | +~0.1 GB on the same model |
| Photo with text | **text reader** → then Laya or the nano by language | iPhone: Apple Vision (in the OS, 0 MB). Android and Mac: OCRia-nano-1, ~15 MB ([`OCR-ARABIC-PLAN.md`](OCR-ARABIC-PLAN.md)) |
| QR code | **built-in barcode reader** → the link checks | 0 MB (Vision / ML Kit barcode) |

iPhone total: **about 0.9 GB of downloads**, one decider in memory at a time.

## 2. The line every item goes through (exists today)

```
collect → read text → rules first → model → recalibrate → confidence bar → ledger → act / ask
```

- **Collect:** sources produce `SourceItem`s (`SourcesService`, `PhotosSource`, mail, files, calendar, contacts).
- **Read text:** photos go through the text reader in the source step (`VisionTextRecognizer` on iOS;
  `PhoneItems.photo(ocrText)` marks the text as OCR'd).
- **Rules first:** card numbers, IDs, dates, link facts. A rule that can answer means the model is never asked
  (`DecisionEngine`, "mechanical checks run first").
- **Model** behind one plug, `Backend.score(judgment, state)`.
- **Recalibrate:** `Recalibrator` (today `Identity` everywhere; `TemperatureScaling` exists but is not live).
- **Bar:** `Policy` threshold → `Decision.Act` or `Abstain`.
- **Ledger:** every decision is appended and synced to disk (`PhoneLedger`, Application Support/Loupe).
- **Act / ask:** watchers raise findings; unsure items go to the review queue (`UncertainQueue`);
  the agent tier prepares actions behind `ActionGuard` and the user's approval.

**The models never talk to each other.** Each one answers questions at its station; the line, the rules and
the bars decide what happens.

## 3. The switchboard

A **`RoutingBackend`** implementing the same `Backend` plug. The engine, the policy and the ledger do not change.

| Item | Goes to |
|---|---|
| Text with Arabic letters (≥ 20% of letters in U+0600–U+06FF) | Laya |
| Latin text with Franco signs (digits inside words such as `3ala`, `7aga`, `ma3ak`; common Franco words) | Laya |
| Other text (English) | the nano |
| Photo, text reader found text | by its text, as above |
| Photo, no text | the nano (vision) |

- **The ledger records which model answered** (model id and version on each row), so results can be audited
  and a model can be re-run on just its own items later.
- **Each model has its own bars.** Laya's bar for *phishing* is raised to **0.90** (the Arabic-script test:
  18 of 21 phishing caught, 3 of 22 false alarms, vs 19 / 5 at 0.80); its other bars are unchanged. The nano's
  bars are set from its own test results once they exist.
- **Franco guard** (owner may waive): a Franco item is flagged as phishing only with corroborating evidence —
  a link failing the domain checks, a request for a code — never on Laya's answer alone.
- **Disagreements:** a rule beats a model (a card number found by `PiiRules` is a card, whatever the nano
  thinks); two signals that disagree make the item *unsure* and it goes to the user.

## 4. Photos: the one change to the engine's core

Today an `Item` is `(id, text)`, so a model can only be given text. The nano's picture questions need an item to
carry its image: an optional **image reference** on `Item` (a local file handle, never bytes copied into the
ledger), read only by a backend that declares it can see. Everything else on the line stays as it is. A photo with
no text that today is skipped (`text = ""`) becomes a vision item.

QR codes: the source step runs the platform barcode reader on each photo; a decoded link is added to the item's
facts and goes through the same link rules as a link in an email.

## 5. The model shelf: download once, keep for good

Generalise what Laya already has — `LayaModel`, `ModelManifest`, `ModelStorage`, `ModelDownloader` — into a
**model shelf** that treats every model the same way:

- **One manifest** listing each model's files, sizes and pinned SHA-256 (as `LayaModelStore.FILES` does today).
- **Downloaded during onboarding**, all at once, after a consent screen that shows the total size
  (~0.9 GB on iPhone), on Wi-Fi, through the existing resumable background transport
  (`BackgroundDownloadTransport`). Leaving the app or losing the network pauses, never restarts.
- **Verified once**, after download (hash of every file). On later launches only a cheap presence check runs
  (`missing()`); the full hash check runs again only if a model fails to open, or after an update.
- **Stored in Application Support**, which iOS never purges (not Caches), and **excluded from iCloud backup**
  (`ModelStorage.excludeFromBackup`, as Laya's files are today).
- **Never downloaded again**, except:
  1. a file is missing or fails its check (repair, that file only);
  2. the phone was restored from a backup or moved to a new phone (models are not in backups: one re-download,
     with the same consent screen);
  3. the user accepts a model update — offered, never forced; Wi-Fi only; the old version is kept until the new
     one is verified, then removed.
- **Loaded on demand, one at a time.** Opening a model reads it from disk (seconds), not from the network.
  Memory holds one decider; the shelf unloads one before loading the other.

## 6. The first scan: the user chooses when

The last onboarding screen, after the models are on the phone:

> **Loupe is ready to look through your phone. When should it do the first full scan?**
> - **Now** — keep Loupe open; you can watch it work.
> - **Tonight, while charging** — plug in before bed; you'll get a notification in the morning.
> - **Pick a time** — Loupe starts after that time, once the phone is charging.
> - **Whenever the phone is charging** — no fixed time.

How each is kept honest:

- **Now:** runs while Loupe is open, with the live progress the app already shows (`ActivityCenter`,
  `LiveRunModel`). If the user leaves the app, iOS pauses it; it carries on at the next opening or the next
  charging window, never from the start. (iOS 26's continued-processing task may let a user-started scan keep
  going in the background with a progress banner — to verify on a device.)
- **Tonight / whenever charging:** the existing "Sort while charging" task (`BackgroundSorter`,
  `BGProcessingTask` with external power required).
- **Pick a time:** iOS does not run background work at an exact minute; the chosen time becomes the *earliest*
  start (`earliestBeginDate`) and the scan runs after it once the phone is charging and idle. The screen says
  "after 11 pm, while charging", not "at 11 pm". Android (WorkManager with a charging constraint) and the Mac
  can keep closer to the time.
- **It stops, and resumes later, when** the phone gets hot, Low Power Mode comes on, or iOS ends the window —
  already built (`SweepCoordinator`: `THERMAL`, `LOW_POWER`, `EXPIRED`). Every decision made before the stop is
  already in the ledger.
- **When it finishes:** one notification with real counts — "412 sorted, 9 need you" (`SortSummary`).
- The choice can be changed later in Settings.

The order inside a scan follows section 7: rules first, then all of Laya's items, then one model swap, then the
nano's text and photo items.

## 7. Running order: little things now, big things on the charger

1. **One worker, one queue.** Items are judged one after another; never two models at once.
2. **Grouped by model.** A scan runs every Laya item, unloads Laya, loads the nano, runs every nano item —
   one swap per scan, not one per item.
3. **New items as they come:** a new screenshot or email is judged when it arrives (one item, a fraction of a
   second), with whichever model it needs.
4. **Big work waits for the charger:** the first scan, a re-run after a model update, a re-run after the user
   changes a question's wording.

## 8. Results stay on the phone, and are never redone

**What exists:** the ledger is durable — every decision is appended and synced to disk (`PhoneLedger`), and the
coordinator skips items "already decided under the judgment's current wording" (`SweepCoordinator`,
`alreadyDecided`).

**What to add: an item index**, so the reading is never redone either — today a re-scan re-reads sources (and
re-runs text recognition on photos) before skipping the decided items. Per item: its id, a content fingerprint
(`SourceItem.hashOf` already computes one), the text read from it, whether the text reader ran, and which model
and version judged it. A later scan then:

- **skips** items whose fingerprint is unchanged — no text reading, no model;
- **reads** only new items, or items whose content changed (an edited file, a new email in a thread);
- **forgets** items that were deleted from the phone (their decisions stay in the ledger as history).

**Re-judging only happens when** the user changes a question's wording (existing rule), the user accepts a
model update and chooses to re-run (on the charger), or the user asks for it. Never on its own.

**Where results live:** on the phone only, in Application Support. Nothing is sent to any server; the user can
export (`DataExport`) or delete everything from Settings.

## 9. Where the agent sits

After the line, never inside it. The agent tier (`agent/`) reads decisions ("passport expires in March"),
prepares an action (a reminder, a note, a draft) that passes the never-list (`ActionGuard`), and waits for the
user's approval (`ReviewQueue`). The optional cloud helper only fetches public facts; it never sees the user's data.

## 10. What has to be built (each step after its own "Go")

1. **Model shelf** — generalise the Laya download, check and storage code to a manifest of models; onboarding
   downloads all of them.
2. **Export and shrink the nano** — ONNX of the text trunk and vision part, INT8; check its answers against the
   full model, as `HANDOFF-D1-IPHONE.md` describes for d1-2B.
3. **`RoutingBackend`** + model id on ledger rows + per-model bars (Laya phishing 0.90) + the Franco guard.
4. **Image reference on `Item`** and the nano's vision path; barcode reading in the photo source.
5. **Item index** — skip unchanged items before any reading.
6. **First-scan choice** in onboarding, wired to the existing charging task and the live view.
7. **Android and Mac:** OCRia-nano text reading (`OCR-ARABIC-PLAN.md`).

## 11. Not known yet

- **The nano's English** on a test it cannot have trained on (its 79.6% on Fast Decisions looks contaminated).
- **The nano on pictures** — bank cards, passports, Egyptian IDs, screenshots.
- **Its speed and heat on the iPhone** once shrunk (140 ms per message unquantised on a cloud CPU; Laya 32 ms).
- **iOS 26 continued processing** for a "Now" scan that keeps going when the user leaves the app.

## 12. Open decisions for the owner

- **The ledger in iPhone backups.** Models are excluded from backup; the ledger (Application Support/Loupe) is
  not, so decisions are in the user's iCloud or computer backups and survive a phone restore. Keep it that way,
  or exclude it so results never leave the phone even in a backup?
- **The Franco guard** — keep, or let Laya's answer alone flag Franco messages.
- **Model updates** — ask each time (as above), or update automatically on Wi-Fi while charging.
