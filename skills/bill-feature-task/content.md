---
internal-for: bill-feature
name: bill-feature-task
description: "Runtime entry for a single feature-task run via `bill-feature-task-runtime`. Use when implementing a feature/spec or running feature-task; not for multi-subtask goals (use bill-feature / bill-feature-goal)."
---

# Feature Task Router

`bill-feature-task` is the entry point for feature-task implementation. It collects the run context, confirms with the user, and delegates to `bill-feature-task-runtime`, which launches the `skill-bill feature-task` foreground driver.

Durable workflow rows use the public workflow identity `bill-feature-task` in the
shared feature-task workflow store with `mode=runtime`.

When args include a validated `workflow-id:<id>`, use continuation mode. Keep the persisted governed spec path and present this skill's single gate as a continuation confirmation. After confirmation, invoke `skill-bill feature-task resume <workflow_id> <issue_key> <persisted_spec_path> --agent <current-agent>`. Never open a replacement row or mutate state during lookup.

## Intake

Gather enough to identify and confirm the run:

- the issue key
- the governed spec path the run implements
- the agent currently executing this skill
- the parallel review agent (from args as `parallel-review:<agent>`; absent when not provided)
- the optional validated continuation selector (from args as `workflow-id:<id>`)
- the already-resolved ordered agent add-on selection, if present

If the issue key is missing, stop and ask for it. If the spec path is missing, search `.feature-specs` for exactly one governed `.feature-specs/{ISSUE_KEY}-*/spec.md` match and use it. If there is no match or more than one match, stop and ask for the explicit spec path. Do not invent either value.

Parse `parallel-review:<agent>` and at most one `workflow-id:<id>` from args before presenting the confirmation gate. Reject empty, duplicate, or conflicting workflow selectors. If a selector is present, use the persisted governed spec path supplied by the lookup.

Also parse exactly one optional `code-review:auto|inline|delegated` token. Omission resolves to `inline`; `delegated` is the experimental full-depth tier and is reached only by an explicit token. Malformed, unknown, duplicate, or conflicting values fail before confirmation, workflow opening, or delegation. Forward the resolved selection unchanged to the runtime sidecar.

## Single Confirmation Gate

Present one concise confirmation that includes:

- the issue key and spec path
- the agent that will run each phase, including any explicit override
- the parallel review agent when `parallel-review:<agent>` was passed, or `none` otherwise
- the requested code-review selection, showing `inline (default)` when omitted and marking an explicit `delegated` selection as experimental
- selected agent add-on slugs and manifest descriptions in caller order, or `none`

Ask exactly one confirmation question: whether to proceed with the run.

Do not launch any downstream skill while the run is unconfirmed. If the user declines, stop. This is the only user interaction required before delegating.

## Confirmed Handoff

After confirmation, dispatch to the runtime sidecar by reading its file from this skill's own installed directory (a sibling file next to this `SKILL.md`) and executing its instructions in the current session. Do not use the Skill tool for this — `bill-feature-task-runtime` is an internal skill and is not listed.

Read the file `bill-feature-task-runtime.md` located in this skill's own installed directory (a sibling of this `SKILL.md`) and execute its instructions in the current session. Forward `--agent`, `--agent-override`, `--phase-agent`, `parallel-review:<agent>`, `code-review:<selected-mode>`, and the structured agent add-on selection identically from the args received by this router.

For continuation, also forward the validated workflow id, persisted issue key, and persisted governed spec path so the sidecar invokes the runtime resume path rather than opening a new workflow.

Delegate immediately after this router's gate clears. The delegated sidecar consumes
the confirmed normalized inputs and owns launch and execution behavior; it must
not repeat intake or present another confirmation gate.
