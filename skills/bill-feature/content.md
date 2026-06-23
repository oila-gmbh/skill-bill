---
name: bill-feature
description: "Use as the primary feature entry point: prepare a governed feature spec, then dispatch single-spec work to bill-feature-task or decomposed work to bill-feature-goal."
---

# Feature Content

`bill-feature` is the primary feature entry point. It owns routing only: prepare the governed feature-spec artifacts first, then choose the correct downstream executor from the prepared result.

It does not replace `bill-feature-spec`, `bill-feature-task`, or `bill-feature-goal`. It composes them:

- `bill-feature-spec` owns feature-spec preparation.
- `bill-feature-task` owns one implementation unit.
- `bill-feature-goal` owns decomposed goal-loop execution with durable state.

## Update Check

Call `mcp__skill-bill__update_check` before any other action.

When the tool returns `status: "update_available"`:
- Show the user: installed version (`installed_version`) → latest version (`latest_version`).
- Ask: **Update now or continue with the current version?**
  - If the user chooses to update: stop and show the `recommended_install_command`. Do not proceed to Intake.
  - If the user chooses to continue: proceed to Intake unchanged.

When the tool returns any other status (`up_to_date`, `ahead_of_release`, or `unknown`): silently proceed to Intake with no prompt.

## Intake

Clarify the user's feature request enough to identify:

- the issue key
- the intended outcome
- the acceptance criteria
- constraints, affected areas, and non-goals

If the issue key is missing, stop and ask for it. Do not invent one.

## Prepare Spec

Always invoke `bill-feature-spec` first in the current session. Do not write spec artifacts directly and do not fork spec-preparation logic.

Wait for `bill-feature-spec` to produce governed artifacts under `.feature-specs/{ISSUE_KEY}-{feature-name}/`. Treat its selected mode as authoritative for dispatch.

## Dispatch

For `single_spec` output:

- Run `bill-feature-task` on `.feature-specs/{ISSUE_KEY}-{feature-name}/spec.md` in the current session. When exactly one governed `.feature-specs/{ISSUE_KEY}-*/spec.md` exists, the issue key alone is enough for the runtime invocation.
- Do not invoke `bill-feature-goal`.
- Let `bill-feature-task` own implementation, review, validation, history, and PR description behavior.

For `decomposed` output:

- Invoke `bill-feature-goal` in the current session with the prepared issue key and artifacts.
- Do not ask an extra confirmation before invoking `bill-feature-goal`; `bill-feature-goal` owns the one confirmation gate before starting `skill-bill goal`.
- Treat `skill-bill goal <issue_key>` as runtime behavior with durable workflow state, not as spec authoring.

If `bill-feature-spec` cannot produce a valid mode or artifacts, stop and surface the failure instead of guessing a route.

## Status Requests

If the user asks for status on a decomposed feature, route to `bill-feature-goal` status behavior.

If the user asks for status on a single-spec feature implementation, use the normal `bill-feature-task` workflow status behavior when a workflow id is available.
