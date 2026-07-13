---
internal-for: bill-feature
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

When args include a validated `workflow-id:<id>`, use continuation mode. Keep the persisted mode and governed spec path, reject a conflicting explicit mode before launch, and present this skill's single gate as a continuation confirmation. After confirmation, prose continues the same workflow/session through `feature_task_prose_workflow_continue`; runtime invokes `skill-bill feature-task resume <workflow_id> <issue_key> <persisted_spec_path> --agent <current-agent>`. Never open a replacement row or mutate state during lookup.

## Intake

Gather enough to identify and confirm the run:

- the issue key
- the governed spec path the run implements
- the agent currently executing this skill
- the mode (from args as `mode:runtime` or `mode:prose`; default to `runtime` when absent — except on opencode or zcode, where prose is the implicit default; see the prose-only rule below)
- the parallel review agent (from args as `parallel-review:<agent>`; absent when not provided)

If the issue key is missing, stop and ask for it. If the spec path is missing, search `.feature-specs` for exactly one governed `.feature-specs/{ISSUE_KEY}-*/spec.md` match and use it. If there is no match or more than one match, stop and ask for the explicit spec path. Do not invent either value.

Parse the mode and `parallel-review:<agent>` from args before presenting the confirmation gate. If no mode arg is provided, resolve the mode to `runtime`.

Also parse exactly one optional `code-review:auto|inline|delegated` token. Omission resolves to `auto`; malformed, unknown, duplicate, or conflicting values fail before confirmation, workflow opening, or delegation. Forward the resolved selection unchanged to either sidecar.

**opencode and zcode are prose-only.** When the agent currently executing this skill is opencode or zcode, prose is the implicit default and runtime mode is unsupported: opencode's foreground Bash tool is hard-killed at 120s before a phase can finish and per-phase output cannot be harvested back; zcode's foreground runtime exceeds the Bash execution ceiling and a detached zcode child emits no harvestable output before the supervisor kills it as unresponsive. On opencode or zcode: with no mode arg, resolve to `prose` (no need to pass `mode:prose`); with an explicit `mode:runtime`, stop and emit the actionable refusal and do NOT delegate to `bill-feature-task-runtime`:

> Runtime mode is not supported on opencode or zcode in this harness. opencode's foreground Bash tool is hard-killed at 120s before a phase can finish and per-phase output cannot be harvested back; zcode's foreground runtime exceeds the Bash execution ceiling and a detached zcode child emits no harvestable output before the supervisor kills it as unresponsive. Use prose instead — run bill-feature-task-prose for a single feature task, or bill-feature-goal mode:prose for a decomposed goal.

This skill gate and the runtime CLI agree: the CLI refuses the same way whenever the resolved runtime agent is opencode or zcode by any route.

## Single Confirmation Gate

Present one concise confirmation that includes:

- the issue key and spec path
- the agent that will run each phase, including any explicit override
- the resolved mode: show `runtime (default)` when the mode was not specified, `runtime` when explicitly set, or `prose` when `mode:prose` was passed
- the parallel review agent when `parallel-review:<agent>` was passed, or `none` otherwise
- the requested code-review selection, showing `auto (default)` when omitted

Ask exactly one confirmation question: whether to proceed with the selected mode.

Do not launch any downstream skill while the run is unconfirmed. If the user declines, stop. This is the only user interaction required before delegating.

## Confirmed Handoff

After confirmation, dispatch to the delegated sidecar by reading its file from this skill's own installed directory (a sibling file next to this `SKILL.md`) and executing its instructions in the current session. Do not use the Skill tool for this — `bill-feature-task-runtime` and `bill-feature-task-prose` are internal skills and are not listed.

When mode is `runtime` or unspecified (on opencode or zcode the mode resolves to `prose`, or an explicit `mode:runtime` already refused per the prose-only rule above, so this runtime branch is never taken on opencode or zcode):

- Read the file `bill-feature-task-runtime.md` located in this skill's own installed directory (a sibling of this `SKILL.md`) and execute its instructions in the current session. Forward `--agent`, `--agent-override`, `--phase-agent`, `parallel-review:<agent>`, and `code-review:<selected-mode>` identically from the args received by this router.

When mode is `prose`:

- Read the file `bill-feature-task-prose.md` located in this skill's own installed directory (a sibling of this `SKILL.md`) and execute its instructions in the current session. Forward `--agent`, `--agent-override`, `--phase-agent`, `parallel-review:<agent>`, and `code-review:<selected-mode>` identically from the args received by this router.

Delegate immediately after this router's gate clears. The delegated sidecar consumes
the confirmed normalized inputs and owns launch and execution behavior; it must
not repeat intake or present another confirmation gate.
