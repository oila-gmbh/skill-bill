---
name: shell-ceremony
description: Shared ceremony sidecar for governed shells and horizontal skills.
---

# Shared Ceremony

This file is the canonical ceremony sidecar for governed platform-pack skills
and for horizontal shells that point their project-overrides section here.
Skills consume this file through sibling symlinks, so changes here propagate to
every linked skill immediately.

Do not reference this repo-relative path directly from installable skills — use
the sibling `shell-ceremony.md` file instead.

## Project Overrides

If `.agents/skill-overrides.md` exists in the project root and contains a
section whose heading matches this skill's `name` from the frontmatter, read
that section and apply it as the highest-priority instruction for this skill.
The matching section may refine or replace parts of the default workflow.

If an `AGENTS.md` file exists in the project root, apply it as project-wide
guidance.

Precedence: matching `.agents/skill-overrides.md` section > `AGENTS.md` >
built-in defaults.

## Inputs

Read the current skill's `SKILL.md` frontmatter before execution so project
overrides resolve against the correct skill name. When a sibling `content.md`
exists, treat it as the authored execution body for the current skill.

## Execution Mode Reporting

When the current skill runs, report the execution mode on its own line:

```
Execution mode: inline | delegated
```

- `inline` — the current agent handled the work directly.
- `delegated` — the current agent dispatched the work to a specialist subagent
  or sibling skill.

## Telemetry Ceremony Hooks

Follow the standalone-first telemetry contract documented in the sibling
`telemetry-contract.md` file:

- Emit a single `*_started` event at the top of the ceremony.
- Emit a single `*_finished` event at the bottom of the ceremony.
- Routers aggregate `child_steps` but never emit their own `*_started` or
  `*_finished` events.
- Degrade gracefully when telemetry is disabled: the skill must still run to
  completion without an MCP connection.
