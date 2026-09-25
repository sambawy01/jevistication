# Model settings (schema v1)

How Laya is used, in one place, the same way on the iPhone as in Loupe Station. Owner decision
2026-09-24: in Station, Folder Scan labels were poor because "Use Laya for labels" was silently
off; the fix is one visible settings place and a banner whenever a run happened with Laya off.

- **Shared code:** `loupe-kit/src/commonMain/kotlin/dev/loupe/kit/settings/` —
  `EngineSettings` (schema, defaults, validation, `RunPolicy`), `EngineSettingsStore` (file,
  reset, Station's GET/PUT bodies), `ModelMemory` (memory mode).
- **File:** `engine_settings.json` in the app's Application Support directory (beside the ledger;
  a throwaway directory under XCTest and `-LoupeFixtures`), rewritten atomically on each change.
- **iPhone UI:** Me → Model settings (`ios/Loupe/Settings/`), English and Arabic (right to left).

## Document

```json
{"version":1,"settings":{"global":{…},"features":{"scan":{…},"email":{…},"browser":{…},
 "watchers":{…},"playground":{…},"judgments":{…},"flights":{…},"game":{…}}}}
```

Global keys and Station's five features come first, in Station's order and with Station's values;
the phone's three features follow. Every key is written (not only changed ones). Whole numbers are
written without a fraction (`10`, not `10.0`). On read, a missing, mistyped or unknown-choice value
keeps its default, a number out of range is clamped, unknown keys and features are ignored, and a
malformed file falls back to the defaults (the app keeps working).

`EngineSettingsStore.get()` / `put(body)` speak Station's `GET/PUT /api/engine/settings` bodies
(`reset: ["global.routing", "features.scan.use_laya", …]`, `reset_all: true`). `env` is always `{}`:
**iOS has no environment variables or MDM override for these, so "set by environment" never appears
on the phone.** `reload` is `["global.memory_mode", "global.idle_unload_min"]`.

## Keys

### Global (Station's, unchanged)

| Key | Type / range | Default | On iPhone |
|---|---|---|---|
| `routing` | `auto` \| `english` \| `multilingual` | `auto` | The phone ships one checkpoint, **Laya multilingual**. `auto` and `multilingual` both run it; `english` is kept (valid, stored) but shown as unavailable and also runs multilingual. |
| `memory_mode` | `full` \| `balanced` \| `low` | `balanced` | Laya's load/unload policy (`ModelMemory`): **full** keeps it loaded once opened (what the app did before); **balanced** frees it after `idle_unload_min` idle minutes; **low** frees it as soon as a run ends and the model lane is free. A freed Laya is reopened (files re-verified, a few seconds) by the next decision. Applies at once. |
| `idle_unload_min` | 0..1440 (0 = never) | 10 | Balanced only. Checked every 30 s (the maintenance tick), never while foreground work or the game holds the model. |
| `accept_confidence` | null \| 0.05..0.99 | null | null = each feature's own rule (judgments: their starting threshold; flights 0.80; expiry watcher 0.5). A number replaces those. A judgment whose threshold was set on Measure (differs from its shape's starting threshold) keeps it. |
| `use_calibration` | bool | true | Kept and shown. **No calibration is fitted on iPhone yet**, so on and off both use raw confidence today (identity recalibrator, as before). |
| `rules_first` | bool | true | Off: the exact-duplicate rule and the money judgments' transaction-evidence gate (`no-transaction-evidence`, BUILD.md C1a) no longer answer before Laya in sweeps. The per-judgment "Always baseline" still answers (it is the judgment's own override). |
| `baseline_switch` | bool | true | The automatic baseline (e45e806). Off: Laya keeps answering under Auto. The per-judgment Auto / Always baseline / Always Laya choice still wins. |
| `bias_correction` | `contextual` \| `domain` \| `off` | `off` | **Added 2026-09-24 (Station, after `baseline_switch`).** Station's answer re-weighting; Station measured both methods worse (clear-cut 1,035 and 1,019 of 1,621 vs 1,090 shipped; yes-rate on negatives 24% → 44% / 48%), hence `off`. **iPhone implements `off` only:** the other two are stored and shown as "Loupe Station only" and run as `off` (`EngineSettings.effectiveBiasCorrection`). |
| `text_chars_english` | 200..20000 | 1400 | Kept for parity; **unused on iPhone** (no English model). |
| `text_chars_multilingual` | 200..20000 | 2400 | The TextState budget for any feature whose `text_chars` is `"global"`. |

### Per feature: common keys (Station's)

| Key | Type / range | Default | Meaning |
|---|---|---|---|
| `use_laya` | bool | true | Off: the feature answers by rules only, and its screen shows the banner. |
| `routing` | null \| `auto` \| `english` \| `multilingual` | null | null = `global.routing`. Runs multilingual on iPhone whatever it says. |
| `text_chars` | null \| `"global"` \| 100..20000 | null | null = the feature's built-in limit (below); `"global"` = `global.text_chars_multilingual`; a number = that many characters. |

### Features

| Id | Station / iPhone | What it drives on iPhone | Built-in text limit | `use_laya` off |
|---|---|---|---|---|
| `scan` | Station | Privacy check (+ the sources scan's content reading) | — (rules only) | Rules only either way; the Privacy screen shows the banner |
| `email` | Station | Mail triage | — (rules only) | Rules only either way; banner on Mail triage |
| `browser` | Station | Site checks on web links, online checks | — (rules only) | Rules only either way; banner above "Links in your items" |
| `watchers` | Station | The five watchers (expiry radar's Laya half) | 4000 | Mechanical half only; banner on Now's findings |
| `playground` | Station | — | — | **Desktop only**: kept in the document, listed as Loupe Station only |
| `judgments` | **iPhone** | `JudgmentSweep` (a judgment's Run) and `SweepCoordinator` (Sort) | 4000 | Rules only, model not needed: duplicates, "Always baseline", else the judgment's keyword baseline (`mechanical:laya-off`); items with no rule stay undecided and are judged by Laya on a later run with Laya on |
| `flights` | **iPhone** | Laya ranking of flight offers (`FlightJudge`) | 480 | The rule ranking, Laya not opened; banner in results |
| `game` | **iPhone** | Riverflight's Laya pilot (`ModelPilot`) | 600 | The baseline autopilot flies; banner in Watch mode |

### Feature extras

| Key | Type / range | Default | On iPhone |
|---|---|---|---|
| `scan.read_content` | bool | true | Off: the privacy check reads names, folders and exact duplicates (hash from the scan) only. |
| `scan.content_budget_s` | 1..600 | 60 | Kept for parity, **no effect**: the phone reads each file once when Sources scans it, within the extractor's own limits. |
| `scan.ocr` | bool | true | **Added 2026-09-24 (Station).** On-device Apple Vision OCR (`.accurate`, automatic language detection — needed for Arabic and mixed Arabic/Latin). Governs Photos (off: metadata only; on = the old behaviour) and **scanned PDFs** from Files and the Share inbox (a PDF whose PDFKit text has fewer than 12 letters/digits). Mail attachments: the phone's MIME reader lists attachments by name only and reads no attachment content, so there is nothing for OCR there yet (Station OCRs them); watchers read the same scanned items, so they follow this key. Read when a scan starts (`OcrPolicy.current()`). The bundled **sample** scan never OCRs, so its items stay identical to the desktop scanner's. |
| `scan.ocr_max_pages` | 1..20 | 3 | **Added 2026-09-24 (Station).** Pages of a scanned PDF read by OCR (rendered at about 2×, longest side ≤ 2,400 px). |
| `browser.time_limit_s` | 1..60 | 5 | Kept for parity, **no effect**: Laya does not read pages on iPhone. |
| `browser.queue_size` | 0..10 | 2 | Kept for parity, **no effect**: as above. |
| `game.max_decisions_per_s` | null \| 0.5..60 | null | **Added (iPhone).** null = no cap (every decision the session asks for, as before). A number: a requested decision waits until the cap allows it; the safety override still steers. Read every tick. |

## Keys added or renamed versus Station

- **Added feature ids:** `judgments`, `flights`, `game` (each with `use_laya`, `routing`, `text_chars`).
- **Added key:** `features.game.max_decisions_per_s`.
- **Mirrored from Station 2026-09-24:** `global.bias_correction`, `features.scan.ocr`, `features.scan.ocr_max_pages`.
- **Renamed:** none. Every Station key keeps its name, type, range and default.

## Applying changes

Every consumer reads the settings when a run starts (`RunPolicy` snapshot), so a change applies
to the next run with no restart; the game's decisions-per-second cap is read every tick.
`memory_mode` loads (full) or frees (low) Laya at once; `idle_unload_min` applies at the next
30-second tick — as Station.

Consumers: `JudgmentSweep.runWith`, `SweepCoordinator.runWith`, `WatcherRun.run(…, policy)` /
`runIsoWith`, `FlightJudge.forPolicy`, `PrivacyCheck.summariseWith`, `GameSessions.modelDeciderWithBudget`
(shared); `JudgmentsService`, `SortService`, `WatchersService`, `MailTriageService`,
`PrivacyService`, `WebModel`/`LayaRanker`, `GameController`/`PilotScheduler`, `ModelWork` +
`LayaModel` (memory) on iOS.

## Defaults versus earlier behaviour

Every default reproduces what the app did before, checked against the code (tests:
`SettingsConsumersTest`, `ModelSettingsTests`). Where the old behaviour cannot be expressed exactly:

1. **`memory_mode = balanced`** (owner decision, for parity with Station): the phone used to keep
   Laya loaded for the life of the process, which is `full`. With the default, Laya is freed after
   10 idle minutes and the next use waits a few seconds to reopen it.
2. **`scan`, `email`, `browser` `use_laya = true`:** these run on rules on the phone (Laya is not
   wired into them yet), so "on" changes nothing; only "off" is visible (the banner).
3. **`use_calibration = true`:** no calibration is fitted on the phone, so it is the identity
   either way.
4. **`scan.content_budget_s`, `browser.time_limit_s`, `browser.queue_size`:** the phone has nothing
   they could limit; "no limit" (the old behaviour) is not a value in Station's ranges, so they
   keep Station's defaults and are marked Loupe Station only.
5. **`scan.ocr = true` on scanned PDFs** is new behaviour (the phone did not OCR PDFs before); it
   matches Station's default. Photos were already OCR'd, so on changes nothing there.
6. **A judgment threshold equal to its shape's starting value** is treated as "not set on Measure",
   so a non-null `accept_confidence` replaces it.
