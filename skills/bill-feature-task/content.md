---
name: bill-feature-task
description: Router skill for feature-task implementation. Accepts `mode:runtime` (default) or `mode:prose` in args and delegates to the appropriate skill. Use when user mentions implement feature, build feature, implement spec, run feature-task, or feature from design doc.
---

# Feature Task Router

`bill-feature-task` is the entry point for feature-task implementation. It collects the run context, confirms with the user, and delegates to the appropriate implementation mode:

- **runtime** (default) — delegates to `bill-feature-task-runtime`, which launches the `skill-bill feature-task` foreground driver.
- **prose** — delegates to `bill-feature-task-prose`, which orchestrates the full phase loop in-session without an external runtime.

Both modes persist under the public workflow identity `bill-feature-task` in the
shared feature-task workflow store: prose records use `mode=prose`, and runtime
records use `mode=runtime`.

## Intake

Gather enough to identify and confirm the run:

- the issue key
- the governed spec path the run implements
- the agent currently executing this skill
- the mode (from args as `mode:runtime` or `mode:prose`; default to `runtime` when absent)
- the parallel review agent (from args as `parallel-review:<agent>`; absent when not provided)

If the issue key is missing, stop and ask for it. If the spec path is missing, search `.feature-specs` for exactly one governed `.feature-specs/{ISSUE_KEY}-*/spec.md` match and use it. If there is no match or more than one match, stop and ask for the explicit spec path. Do not invent either value.

Parse the mode and `parallel-review:<agent>` from args before presenting the confirmation gate. If no mode arg is provided, resolve the mode to `runtime`.

## Single Confirmation Gate

Present one concise confirmation that includes:

- the issue key and spec path
- the agent that will run each phase, including any explicit override
- the resolved mode: show `runtime (default)` when the mode was not specified, `runtime` when explicitly set, or `prose` when `mode:prose` was passed
- the parallel review agent when `parallel-review:<agent>` was passed, or `none` otherwise

Ask exactly one confirmation question: whether to proceed with the selected mode.

Do not launch any downstream skill while the run is unconfirmed. If the user declines, stop. This is the only user interaction required before delegating.

## Confirmed Handoff

After confirmation, invoke the delegated skill via the Skill tool — do not search the filesystem to locate skill files; the Skill tool resolves skills by name.

When mode is `runtime` or unspecified:

- Invoke `bill-feature-task-runtime` via the Skill tool.
- Forward `--agent`, `--agent-override`, `--phase-agent`, and `parallel-review:<agent>` identically from the args received by this router.

When mode is `prose`:

- Invoke `bill-feature-task-prose` via the Skill tool.
- Forward `--agent`, `--agent-override`, `--phase-agent`, and `parallel-review:<agent>` identically from the args received by this router.

Do not add a second confirmation gate on top of the delegated skill's own behavior. Delegate immediately after this router's own gate clears. The delegated skill owns its own intake, confirmation, and execution logic.
