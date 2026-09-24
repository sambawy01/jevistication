# Live run view on iPhone

The contract is Loupe Station's README, section "Live run view" (`~/laya-studio`, read-only here). Loupe mobile
uses the same field names, kinds, stage keys, loop stage ids, counters, gates, shares, decision log, cost of
asking formula, settings keys and defaults. The phone draws the view dark neon (owner decision 2026-09-24);
Station's light theme amendment applies to Station only.

Code: `loupe-kit/src/commonMain/kotlin/dev/loupe/kit/activity/` (`Activity.kt` job model and registry,
`LiveRun.kt` loops / stage→node map / particles / 12 s announcer, `ActivityReport.kt` how the phone's jobs
report); `ios/Loupe/Activity/` (the SwiftUI view, dock, banner, EN + AR words).

## What a job carries

Only catalog keys, numbers and short identifiers. `Activity.clean` is Station's `activity.clean()` plus one
phone rule: a value that ends like a file name (`scan.pdf`, `IMG_1.HEIC`) is dropped. A user's judgment id is
a slug of its wording, so it travels as `j:` + 8 hex digits (FNV-1a) and the owning screen maps it back on the
device. Tested on the JVM and the iOS simulator (`ActivityTest.noTextOrFileNamesReachAJobThroughAnyDoor`,
`aRealPrivacyCheckReportsCountsOnly`) and in XCTest over the bundled sample.

## Cost of asking

decisions × (tokens in × input price + tokens out × output price) / 1,000,000, against Laya on this device
(free). Model settings keys (global): `cost_input_per_mtok` (2), `cost_output_per_mtok` (10),
`cost_tokens_in` (700), `cost_tokens_out` (60). Label "estimate · Claude Sonnet 5 list price as of 24 Sep
2026", or "estimate · your price from Model settings" once changed. Price verified at the source on 2026-09-24
(platform.claude.com pricing: Sonnet 5 $2 / $10 per MTok, now the standard price). Nothing is ever called.

## Where each job shows

| Job | kind | view | Screen (in place) |
|---|---|---|---|
| Privacy check | `scan` | `scan` | Now → Privacy check |
| Mail triage | `email_run` | `email` | Now → Mail triage |
| Watchers | `watchers` | `watchers` | Now (while running) |
| Passive sort (SweepCoordinator) | `sort` * | `now` * | Now (while running) |
| Judgment sweep | `judgments` * | `judgments` * | Judgments → results |
| Inbox import | `inbox` * | `sources` * | dock (Sources) |
| Flights ranking | `flights` * | `flights` * | Web → results (while running) |
| Model open / download | `model_load` | `setup` | Me → Laya model; banner "Loading the multilingual model… about N s" |
| Phishing lists | `feeds` | `protection` | Me → Online checks (while running) |
| Writing assistant | `llm_job` | `assist` * | dock |
| Game pilot | `game` * | `game` * | the game's panel under the river |

Jobs whose screen is not on screen show in the Activity dock (inline-end, above the tab bar): running jobs with
Cancel and the last ten finished.

## Names added beyond Station's (* above)

- Kinds: `judgments`, `sort`, `inbox`, `flights`, `game`.
- Views: `now`, `judgments`, `flights`, `sources`, `assist`, `game`.
- Loops: the phone's kinds reuse Station's layouts — `inbox` the Folder Scan loop; `judgments`, `sort`,
  `flights`, `game` the Watchers loop. No new stage ids.
- Stage keys: `act.stage.ranking`, `act.stage.playing`, `act.stage.importing` (→ `read`, `read`, `walk`).
- Titles: `act.title.privacy`, `act.title.mail`, `act.title.judgments`, `act.title.sort`, `act.title.inbox`,
  `act.title.flights`, `act.title.game`, `act.title.modelDownload`, `act.title.writing`; `act.kind.*` for each
  new kind.
- Results: `act.res.judgments`, `act.res.sort`, `act.res.inbox`, `act.res.flights`, `act.res.game`,
  `act.res.downloaded`, `act.res.writing`.
- Other: `act.cost.onDevice` ("on this device, free"), `act.q.flight`, `act.q.move` (the game's question id
  `move`), `act.status.running`, `act.dock.show`.
- Wording: `act.cost.local`, `act.cost.saved`, `act.node.storedSub`, `act.panel.empty` say "this device"
  instead of "this Mac" (same keys).
- Meta for the Watchers kind on the phone uses Station's `reads_used` / `reads_budget`; the scan meta uses
  Station's `files_seen`, `files_total`, `laya_files`, `rule_only`, `ocr_files`, `phase_unit`.

## Motion and access

One particle per real item, coloured by its gate, batched at 6 a gate per update; none when nothing moves (the
animation timeline pauses). Reduce Motion: no particles, a static mascot, static glows; counters and cards
still update. VoiceOver: a polite (low-priority) announcement at most every 12 s or on a stage change, also
shown as the view's last line. EN + AR (`-LoupeLanguage ar`), the pipeline mirrored in RTL.
