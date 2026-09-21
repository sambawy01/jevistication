# Jevistication

**An on-device decision engine for everything you own.**

Teach it a judgment in plain language. It applies it across your photos, files, email,
spreadsheets, calendar and the pages you browse. It shows you what it is unsure about. It
learns you. Nothing it learns leaves your device.

> Status: specification locked, code not started.
> Start with [`docs/PRODUCT.md`](docs/PRODUCT.md).

## The idea

One concept, pointed at anything: **a judgment**.

```
"Receipts I'll need at tax time"
  → email attachments · photographed paper · PDF statements · spreadsheet rows
```

Organising your files, screening a call, ranking flight options, triggering an automation and
flagging a fake bank page are the same feature wearing different clothes. No single-app tool
can do that, because no single-app tool sees the other apps.

## Why it runs on your phone

The model is ~150M parameters, answers in 7–25 ms, emits zero output tokens, and is
Apache-2.0. On a desktop those are conveniences. On a phone they are the only reason this can
exist — your photos and messages are not going to a cloud API, and a model that decodes no
tokens costs almost no battery.

Airplane mode: everything still works.

## What it does

- **Judgments** you write yourself, applied to every source, forever
- **Census** — classify then count, over thousands of items
- **The uncertain queue** — you review only what teaches it most
- **A threshold slider that shows the counterfactual** before you commit
- **Form filling that can refuse**, decided from page context rather than field names
- **Fraud and identity-mismatch detection**, across page, email and SMS at once
- **Semantic automation triggers** — *when an email arrives that is actually urgent*
- **Search and compare** across many options in one call
- **Visible calibration** — how well it knows you, and how that changed

## What it never does

Never spends money or presses the last button. Never fills a credential or a one-time code.
Never acts on something it is unsure about. Never displays an all-clear implying safety. Never
writes prose. Never sends your data to our servers.

## Documents

| | |
|---|---|
| [`docs/PRODUCT.md`](docs/PRODUCT.md) | The whole specification: sources, capabilities, actuators, architecture, build order |
| [`docs/RESEARCH.md`](docs/RESEARCH.md) | The evidence: sixteen repositories, the measured numbers, the patterns taken, and what we chose not to build |

## License

MIT
