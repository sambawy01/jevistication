# Loupe

**An on-device decision engine for everything you own.**

Teach it a judgment in plain language. It applies it across your photos, files, email,
spreadsheets, calendar and the pages you browse. It shows you what it is unsure about. It
learns you. Nothing it learns leaves your device.

> Status: the engine core is built and tested headless (JVM Kotlin, CI on every push); the
> decision model runs through ONNX Runtime on a desktop. No Android app yet, no device
> measurements, no labelled data. Where it stands: [`docs/BUILD.md`](docs/BUILD.md).
> The specification: [`docs/PRODUCT.md`](docs/PRODUCT.md).

## The idea

One concept, pointed at anything: **a judgment**.

```
"Receipts I'll need at tax time"
  → email attachments · photographed paper · PDF statements · spreadsheet rows
```

Organising your files, screening a call, ranking flight options, triggering an automation and
flagging a fake bank page are the same feature wearing different clothes. No single-app tool
can do that, because no single-app tool sees the other apps.

And the point of seeing all of them at once:

> **It notices what you'd miss.**

A passport expiring before a visa rule bites. Five subscriptions you stopped using. A premium
that rose 23% in an email you never opened. A message from “Mom” sent from a number that isn't
hers. A bank page that isn't your bank.

## Why it runs on your phone

The model ([Laya](https://huggingface.co/convaiinnovations/laya-multilingual), multilingual) is
~320M parameters, emits zero output tokens, and its weights are Apache-2.0. A short question
takes tens of milliseconds on a desktop CPU; phone latency is not measured yet. On a desktop
those are conveniences. On a phone they are the only reason this can exist — your photos and
messages are not going to a cloud API, and a model that decodes no tokens costs far less battery
than one that does.

Airplane mode: everything still works.

## What it does

- **Judgments** you write yourself, applied to every source, forever
- **Census** — classify then count, over thousands of items
- **The uncertain queue** — you review only what teaches it most
- **A threshold slider that shows the counterfactual** before you commit
- **Form filling that can refuse**, decided from page context rather than field names
- **Watchers** — expiry radar, recurring money, silent term changes, person impersonation,
  and site fraud across page, email and SMS at once
- **Semantic automation triggers** — *when an email arrives that is actually urgent*
- **Search and compare** across many options in one call
- **Visible calibration** — how well it knows you per judgment, and how often it declines
  to answer rather than guess

## What it never does

Never spends money or presses the last button. Never fills a credential or a one-time code.
Never acts on something it is unsure about. Never displays an all-clear implying safety. Never
writes prose. Never sends your data to our servers.

## Documents

| | |
|---|---|
| [`docs/PRODUCT.md`](docs/PRODUCT.md) | The whole specification: sources, capabilities, actuators, architecture, build order |
| [`docs/RESEARCH.md`](docs/RESEARCH.md) | The evidence: sixteen repositories, the measured numbers, the patterns taken, and what we chose not to build |
| [`docs/BUILD.md`](docs/BUILD.md) | The build plan, what is built, what is blocked, and the hand-off |
| [`docs/LICENSING.md`](docs/LICENSING.md) | Every model, dataset and dependency, verified at source — including what is still open |

## License

MIT
