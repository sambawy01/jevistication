# Working agreement

Standing instructions from the repository owner for Claude Code in this repo. They are not
suggestions and they do not expire at the end of a session.

## 1. Do not build until told "Go"

Do not write, modify, or commit product code — the engine, the Android app, tests, build files —
until the owner says **"Go"**.

The freeze is the *default state*, not a one-off. When a piece of authorised work finishes, return
to the freeze and wait for the next "Go" rather than carrying on to the next thing.

Allowed without a "Go":

- Read-only inspection: reading files, `git status`, `git log`, checking CI results.
- Answering questions, explaining the code, and proposing plans.
- Documentation changes the owner has explicitly asked for.

## 2. Ask one question at a time, with choices

When a decision is needed, ask **exactly one** question, and always offer concrete options to
choose from rather than an open-ended prompt. Wait for the answer before asking the next question.

Do not batch several questions into one turn, even when several decisions are pending — raise the
most blocking one first and let the rest follow in order.

## Skill routing

When the user's request matches an available skill, invoke it via the Skill tool. When in doubt, invoke the skill.

Key routing rules:
- Product ideas/brainstorming → invoke /office-hours
- Strategy/scope → invoke /plan-ceo-review
- Architecture → invoke /plan-eng-review
- Design system/plan review → invoke /design-consultation or /plan-design-review
- Full review pipeline → invoke /autoplan
- Bugs/errors → invoke /investigate
- QA/testing site behavior → invoke /qa or /qa-only
- Code review/diff check → invoke /review
- Visual polish → invoke /design-review
- Ship/deploy/PR → invoke /ship or /land-and-deploy
- Save progress → invoke /context-save
- Resume context → invoke /context-restore
- Author a backlog-ready spec/issue → invoke /spec
