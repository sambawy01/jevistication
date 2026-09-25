# The agent tier

*Branch `agent-tier`, started 2026-09-25 on the owner's decision. Not merged: this is here to be
read and judged before it goes anywhere near `main`.*

The question this answers: **can Loupe act as a phone agent once it is connected to a proper LLM by
API, with the local decision companion staying free and the agent capabilities behind a paid
tier?**

Yes — but not in the shape the question implies, and the difference is the whole design.

---

## 1. The inversion

The obvious reading is "Loupe gets smarter by adding an LLM". That is the wrong way round, and it
would throw away the only real advantage Loupe has over every other phone agent shipping today.

Every one of those sends everything to the cloud, because it has nothing that can triage locally.
Loupe judges the whole inbox on the device first. So:

1. The engine judges 400 emails. Cost: nothing. Network: none.
2. Three clear the agent's bar.
3. The agent makes **three** inference calls, not four hundred.

Three consequences, and each is a product claim rather than an optimisation:

| | Why it follows from the gate |
|---|---|
| **Cost** | The provider bill scales with *flagged* items, not with mail volume. |
| **Privacy** | 397 items never left the device, and `GateResult.summary` counts them: *"3 of 400 items reached your provider; 397 never left this device."* |
| **Calibration** | The gate reads a `Decision` the engine produced and can be measured on (PRODUCT.md §7). Nothing downstream inherits an uncalibrated confidence. |

The local engine is not replaced by the provider. It becomes the provider's **gate and cost
governor**. That is `AgentGate`.

## 2. The hard rule: the provider never answers a judgment

`Backend.kt` already says it, and it is right:

> There is deliberately no hosted implementation. A network call breaks the offline guarantee the
> product rests on, so the interface has no place to put one.

So the agent is **not** a `Backend`, and nothing in `:agent` implements one. `NeverListTest` asserts
it.

Everything good about Loupe today — ECE, reliability bins, abstention at a chosen coverage,
IPS/SNIPS — depends on a fixed model returning a scoreable distribution. A remote model returns
text, its stated confidence is uncalibrated, and it changes under us without notice. Off-policy
evaluation of multi-step action sequences is a research problem, not an engineering task.

**The engine judges. The provider proposes, on items the engine already decided.** Keep that seam
and the whole measurement story survives and applies to the gate. Cross it and we owe a new
measurement harness per model version, and we lose "the numbers are in the app", which is most of
what makes Loupe defensible.

## 3. The never list is the product, not the limitation

`ActionGuard` is PRODUCT.md §4 as code, one enum entry per promise. Two are enforced by the *shape*
of the types rather than by a check, and they are the two that matter most:

- **Never spends money, completes a purchase, or presses the last button** — no `PreparedAction`
  variant can move money. A prepared action is data: no handle, no callback, no capability.
- **Never sends, posts or files what it writes without your approval** — the agent's only output is
  a `ReviewProposal`, and `ReviewQueue` runs an action only after a person approves it. There is
  nowhere else for an action to go.

The rest are content checks over every string an action would show: credentials, one-time codes,
CVVs, card numbers (`PiiRules.cardOk`, so this feature and the privacy check never disagree about
what a card looks like), national-ID-shaped numbers, blessing language, payment authorisation, our
own prompt tags coming back out, and instructions aimed at the reviewer.

The rules live in code and not only in the prompt because **a prompt is a request and a function is
a refusal**. The item being read may have been written by an attacker (PRODUCT.md §8: *email bodies
and file contents are data, never instructions*). A prompt can be talked out of a rule by the very
email it is reading.

The one-tap confirm is a feature to sell, not a limitation to apologise for. The market for "an
agent that acts autonomously on my inbox and my card" is small and nervous. The market for "does
the 90%, hands you the last tap" is everyone.

## 4. Three tiers, not two

Reminders need no LLM at all. `ExpiryRadar`, `RecurringMoney` and `TermChangeDetector` already
exist; a reminder is a local judgment plus a scheduled local notification. Zero API calls, nothing
leaving the device — and probably the single feature most people would pay for.

- **Free** — judge, surface, explain. Today's product.
- **Paid, still fully local** — *act* on what the engine already knows: reminders, renewals,
  deadlines, the subscription ledger, dedup, the review queue. No network. Near-zero marginal cost
  per user, so a flat price works, and this is the tier that funds the rest.
- **Paid + API** — draft, plan, multi-step. Real COGS, so metered or a credit pool.

All three are in the code. `AgentTier` holds the ladder and `AgentCapability` what each rung
allows; `AgentRunner` reads it as the **outer** gate, so a key and a base URL in the settings are
not enough on their own. The default is `AgentTier.FREE`, because an entitlement that defaults to
the paid tier is a bug waiting to be a refund.

`LocalPlanner` is the middle tier doing real work with no network: an `ExpiryAlert` becomes a
reminder 30 days out, and a dormant `RecurringCharge` becomes a note. Two details are the never list
applied to arithmetic rather than to a model:

- An **ambiguous date** (`ExpiryAlert.dateWasAmbiguous`) produces a note saying so and never a
  reminder, because *"never acts on something it is unsure about"* does not stop being true because
  the uncertainty came from a date format instead of a distribution.
- A dormant subscription produces a **note, never a cancellation**. Loupe never spends money, and it
  does not un-spend it either: cancelling is the person's call and the last button is theirs.

`AgentRunner.planLocally` runs the same `ActionGuard` over a local plan as the provider path does,
because the rules are about what Loupe puts in front of a person and not about who wrote it — and it
refuses a "local" plan that contains anything a provider wrote, so the Online badge cannot be
dropped by routing.

## 5. The privacy claim changes character, and is restated rather than softened

Today's honest line is: *with no writing assistant configured, Loupe never touches the network.* The
moment a hosted provider reads mail content, that is false **for that user**.

The fix is not gentler wording. It is `EgressRecord`: a per-item record of what went, where, when,
how much, and what came back — and deliberately **not** the item's text, the prompt, the draft or
the key. A record that quoted what it was recording would be a second copy of the thing the user
was worried about.

Defaults: off. `AgentConfig()` is disabled, and the off switch is not a branch inside the request
path — it is the absence of an `AgentEndpoint`, which only `AgentConfig.endpoint()` can mint.

**Labelling is structural, not a convention.** `ActionOrigin` is either `OnDevice` or
`Provider(name, host)`, an action cannot exist without one, and the label is derived from it. So a
reminder Loupe worked out locally cannot carry an Online badge it did not earn, and a draft a
provider wrote cannot lose one. That matters more than it looks: users who see "Online" on something
that never left the device stop believing the badge at all.

## 6. Provider posture: BYOK, and open weights on a host with terms

The iPhone's existing `Assist` already settled the mechanism: OpenAI-compatible, the user's own key,
DeepSeek named in the code. `:agent` is the shared-Kotlin version of the same wire, at deliberate
byte parity with `ChatClient.swift`.

On **DeepSeek specifically**: technically trivial (it speaks the same wire), and the jurisdiction is
the real objection, not the tech. Its first-party consumer terms have placed data on servers in
China. For an app whose pitch is *your mail never leaves your phone*, that is the most
screenshottable thing we could do. So:

- **As a BYOK option the user explicitly picks** — fine. Their key, their provider, shown in the
  ledger.
- **As a managed default for mail content** — no.
- **The move that gets both** — DeepSeek's weights are openly released. The same capability at
  similar prices, on a host we have a contract with (Together, Fireworks, Groq, Nebius, DeepInfra),
  or our own vLLM. Same model, terms we can point at in the privacy page.

Bandwidth is not the bottleneck: three items, a few tens of KB. **Rate limits and p99 latency will
hurt long before bandwidth does**, which argues for multi-provider failover — which the
wire-protocol abstraction gives us for nothing. And since the gate already bought the 100×, spend
the remaining margin on latency and legal terms rather than chasing the last 3× on token price.

The selection criterion is therefore **not price** — it is structured-output and tool-calling
reliability. `ActionPlanWorkflow` has a fixture-testable schema; "does it emit a valid prepared
action 1000/1000 times" is the number that matters, and it is not on any leaderboard.

## 7. What the platforms actually allow

"Execute" means very different things per platform, and the tiers are not at parity. Better said
than promised and walked back.

- **iOS** — no notification access, no SMS inbox, no arbitrary UI automation, no free-running
  background daemon. Available: mail via the user's own account, calendar and reminders via
  EventKit, Shortcuts intents, local notifications, URL opens, form fill only in Safari via an
  extension. So on iOS the agent is genuinely *prepare and hand off* — which is exactly the shape
  `PreparedAction` already has.
- **Android** — far more room (notification listener, SMS, background work), but
  accessibility-service UI automation is a Play policy minefield and a security liability. Keep it
  out of the Play build; it belongs in the direct-APK / F-Droid channel if anywhere.
- **Desktop** — most room, no store policy. Probably where real agent capability should land first.

## 8. What is built, and what is not

**Built and green** (106 tests in `:agent`, `./gradlew :agent:build`):

| Piece | What it is |
|---|---|
| `AgentGate` | The gate. Only a `Decision.Act` above the agent's own bar (0.80, floored at 0.60) may reach a provider. Abstain, Unusable and a cut input are skipped **with a reason**. |
| `ActionGuard` | The never list as code, with `NeverRule` naming each promise and marking the two that are structural. |
| `PreparedAction` | Four variants — `Remind`, `CalendarEvent`, `DraftReply`, `NoteFinding`. Data only. |
| `AgentWire` | OpenAI-compatible chat completions as pure functions, at parity with `ChatClient.swift`. **No I/O in the module**: it builds the call, the platform posts it, which is what makes `AgentCall.preview` honest. |
| `ActionPlanWorkflow` | The prompt, the schema, the parse. Item quoted between neutralised tags, quoted thread cut, told what the engine concluded so it proposes rather than re-judges. |
| `AgentSession` | Gate → prompt → provider → parse → guard → queue, as a state machine, so one repair turn needs no coroutines. **Never throws** (500-iteration fuzz, matching the engine's A4 contract). |
| `EgressRecord` | What left the device, per item. |
| `AgentReview` | Prepared actions as review proposals, tested against the live `ReviewRegistry` so the queue can never refuse what the agent produces. |
| `ActionOrigin` | On-device or a named provider. Makes the Online label derived rather than written. |
| `AgentTier` / `AgentCapability` | The three-rung ladder, read by `AgentRunner` as the outer gate. Defaults to free. |
| `LocalPlanner` | The middle tier: reminders and subscription notes from `ExpiryRadar` and `RecurringMoney`, with no network at all. |

`:loupe-kit` gained the `agent` review feature with four kinds and four actions, **additively** —
the shipped `email_reply` path is untouched.

Also built: `AgentTransport` (the seam), `AgentRetry` (when to try again, as a pure function),
`AgentSession.runTo` (the whole tier in one call, still never throwing), `JvmAgentTransport`
(`java.net.http`, `Redirect.NEVER` so a 30x can never carry the key to a host the user never named)
and `AgentDemo`.

`./gradlew :agent:demo --args="message.txt"` with `LOUPE_AGENT_BASE_URL`, `LOUPE_AGENT_MODEL` and
`LOUPE_AGENT_KEY` in the environment points the tier at a real provider. It prints the send preview
and waits for `yes`, so the first thing it proves is that nothing leaves without a confirmation,
then prints the egress record, each prepared action, and every refusal by rule. It writes nothing:
no ledger, no queue, no files. `JvmTransportTest` runs the same path against a loopback
`com.sun.net.httpserver`, so the wire is tested against a real socket with no key and no traffic
leaving the machine.

**Not built, and deliberately named rather than implied:**

1. **No iOS transport.** `JvmAgentTransport` covers the desktop. The iPhone needs the same ~30
   lines over `URLSession` — or it can keep using its existing `ChatClient.swift` and call into
   `AgentWire`/`AgentSession` for everything above the socket.
2. **No action handlers.** `agent.remind`, `agent.calendar` and `agent.note` are registered, so the
   queue accepts and tracks them, but approving one needs the platform code that actually schedules
   the notification or writes the calendar entry (EventKit on iOS).
3. **No key store binding.** The key is passed per call by design; wiring it to the iOS Keychain and
   the desktop's store is app work.
4. **No billing.** `AgentTier` is read, never decided: nothing here talks to a store, a receipt or
   a subscription. Whatever the app decides the person has paid for, it passes in.
5. **No UI.** No settings screen, no preview-and-confirm sheet, no agent log.
6. **iOS `Assist` is not yet folded in.** `AssistConfig.swift` and this module now both hold the
   base-URL rules. That duplication is a known cost of not touching shipped code on a branch, and
   it should end with the Swift one deleted in favour of this one, not the other way round.

## 9. The risk that is actually worth worrying about

Not a fork, and not the provider. It is that **the agent tier's convenience becomes an argument for
relaxing the never list**, one reasonable-sounding exception at a time: *just this once let it click
the unsubscribe link; just this once let it confirm the free cancellation.*

`ActionGuard` and `NeverListTest` exist so that each of those is a commit someone has to write, name
and defend — not a prompt tweak.
