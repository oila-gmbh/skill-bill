---
name: bill-feature-task
description: Router skill for feature-task implementation. Accepts `mode:prose` (default) or `mode:runtime` in args and delegates to the appropriate skill. Use when user mentions implement feature, build feature, implement spec, run feature-task, or feature from design doc.
---

# Feature Task Router

`bill-feature-task` is the entry point for feature-task implementation. It collects the run context, confirms with the user, and delegates to the appropriate implementation mode:

- **prose** (default) — delegates to `bill-feature-task-prose`, which orchestrates the full phase loop in-session without an external runtime.
- **runtime** — delegates to `bill-feature-task-runtime`, which launches the `skill-bill feature-task` foreground driver.

## Intake

Gather enough to identify and confirm the run:

- the issue key
- the governed spec path the run implements
- the agent currently executing this skill
- the mode (from args as `mode:prose` or `mode:runtime`; default to `prose` when absent)
- the parallel review agent (from args as `parallel-review:<agent>`; absent when not provided)

If the issue key or spec path is missing, stop and ask for it. Do not invent either one.

Parse the mode and `parallel-review:<agent>` from args before presenting the confirmation gate. If no mode arg is provided, resolve the mode to `prose`.

## Single Confirmation Gate

Present one concise confirmation that includes:

- the issue key and spec path
- the agent that will run each phase, including any explicit override
- the resolved mode: show `prose (default)` when the mode was not specified, `prose` when explicitly set, or `runtime` when `mode:runtime` was passed
- the parallel review agent when `parallel-review:<agent>` was passed, or `none` otherwise

Ask exactly one confirmation question: whether to proceed with the selected mode.

Do not launch any downstream skill while the run is unconfirmed. If the user declines, stop. This is the only user interaction required before delegating.

## Confirmed Handoff

After confirmation, invoke the delegated skill via the Skill tool — do not search the filesystem to locate skill files; the Skill tool resolves skills by name.

When mode is `prose` or unspecified:

- Invoke `bill-feature-task-prose` via the Skill tool.
- Forward `--agent`, `--agent-override`, `--phase-agent`, and `parallel-review:<agent>` identically from the args received by this router.

When mode is `runtime`:

- Invoke `bill-feature-task-runtime` via the Skill tool.
- Forward `--agent`, `--agent-override`, `--phase-agent`, and `parallel-review:<agent>` identically from the args received by this router.

Do not add a second confirmation gate on top of the delegated skill's own behavior. Delegate immediately after this router's own gate clears. The delegated skill owns its own intake, confirmation, and execution logic.
