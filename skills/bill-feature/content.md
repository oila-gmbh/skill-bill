---
name: bill-feature
description: "Use as the primary feature entry point: prepare a governed feature spec, then dispatch single-spec work to bill-feature-task or decomposed work to bill-feature-goal. Use when user mentions implement feature, build feature, implement spec, run feature-task, feature from design doc, decomposed goal, goal status, or resume goal."
---

# Feature Content

`bill-feature` is the primary feature entry point. It owns routing only: prepare the governed feature-spec artifacts first, then choose the correct downstream executor from the prepared result.

It does not replace `bill-feature-spec`, `bill-feature-task`, or `bill-feature-goal`. It composes them:

- `bill-feature-spec` owns feature-spec preparation.
- `bill-feature-task` owns one implementation unit.
- `bill-feature-goal` owns decomposed goal-loop execution with durable state.

## Code-review selection

Accept zero or one `code-review:auto`, `code-review:inline`, or
`code-review:delegated` argument. Omission resolves to automatic selection.
Reject a malformed, unknown, repeated, or conflicting `code-review:` argument
before preparing a spec, presenting confirmation, opening a workflow, or
launching a child. Carry an explicit argument unchanged into the selected task
or goal sidecar. When omitted, do not synthesize `code-review:auto`; preserve
the omission so the downstream confirmation gate can show `auto (default)`
before resolving the automatic policy.

## Update Check

## Agent add-on selection

Accept zero or more ordered `agent-addon:<slug>` arguments alongside the existing
mode, review, parallel-review, agent, agent-override, and phase-agent arguments.
Omission preserves existing behaviour. Before spec preparation, confirmation,
workflow creation, or child launch, call the read-only `agent-addon
resolve-selection` boundary once with every receiving agent assignment. Reject
empty or malformed values, duplicates, unknown sources, unsupported consumers,
and incompatible agents. Preserve caller order.

After resolution, forward only the structured selection object containing slug,
canonical manifest source identity, content digest, and confirmation description.
No downstream router or worker may parse the original tokens or rediscover the
catalogue. A continuation with no token inherits its durable selection; an
explicit continuation selection must exactly match it.

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

Before discovering or preparing governed artifacts, perform the read-only, repository-scoped continuation lookup for the normalized issue key and current canonical Git root. The workflow database and immutable execution identity are authoritative; `spec.md` is the governed feature contract, not a planning checkpoint.

Handle `resumable`, `already_running`, `ambiguous`, and `terminal_only` before new-work preparation. For `resumable`, dispatch directly to the task sidecar with the persisted workflow id, mode, and spec path. Report and stop for running or terminal rows, and report every ambiguous candidate rather than selecting by recency. Only `no_match` may continue below. A malformed request, identity/snapshot/version error, selector mismatch, or explicit mode conflict must loud-fail rather than becoming `no_match`.

For `no_match`, invoke `bill-feature-spec` first in the current session unless the direct-dispatch rules below find existing governed artifacts. Do not write spec artifacts directly and do not fork spec-preparation logic.

Wait for `bill-feature-spec` to produce governed artifacts under `.feature-specs/{ISSUE_KEY}-{feature-name}/`. Treat its selected mode as authoritative for dispatch.

## Direct Dispatch When Governed Artifacts Exist

Before running spec preparation, check `.feature-specs/{ISSUE_KEY}-*/` for the issue key:

- If it already contains a governed `spec.md` and **no** `decomposition-manifest.yaml`, skip spec preparation and dispatch straight to the `bill-feature-task.md` sidecar (below) with the issue key and spec path.
- If it already contains a `decomposition-manifest.yaml`, skip spec preparation and dispatch straight to the `bill-feature-goal.md` sidecar (below) with the issue key.
- Only invoke `bill-feature-spec` when no governed artifacts exist for the issue key.

## Dispatch

For `single_spec` output (or the direct-dispatch route above when only a `spec.md` exists):

- Read the file `bill-feature-task.md` located in this skill's own installed directory (a sibling of this `SKILL.md`) and execute its instructions in the current session with args: `<issue-key> mode:<mode> parallel-review:<agent> code-review:<explicit-mode> workflow-id:<id> .feature-specs/{ISSUE_KEY}-{feature-name}/spec.md`, including `workflow-id:<id>` only for a validated resumable lookup result, omitting `parallel-review:<agent>` when the caller did not provide it, and omitting the `code-review:` token when the caller did not provide it. For continuation, use the persisted mode and spec path rather than rediscovering or preparing them. Do not use the Skill tool for this — `bill-feature-task` is an internal skill and is not listed. When exactly one governed `.feature-specs/{ISSUE_KEY}-*/spec.md` exists, the issue key alone is enough only for the `no_match` path.
- Do not dispatch to the goal sidecar.
- Let the task sidecar own implementation, review, validation, history, and PR description behavior.

For `decomposed` output (or the direct-dispatch route above when a `decomposition-manifest.yaml` exists):

  - Read the file `bill-feature-goal.md` located in this skill's own installed directory (a sibling of this `SKILL.md`) and execute its instructions in the current session with args: `<issue-key> mode:<mode> parallel-review:<agent> code-review:<explicit-mode>`, omitting `parallel-review:<agent>` when the caller did not provide it and omitting the `code-review:` token when the caller did not provide it. Do not use the Skill tool for this — `bill-feature-goal` is an internal skill and is not listed.
- Do not ask an extra confirmation before dispatching to the goal sidecar; the goal sidecar owns the one confirmation gate before starting `skill-bill goal`.
- Treat `skill-bill goal <issue_key>` as runtime behavior with durable workflow state, not as spec authoring.

If `bill-feature-spec` cannot produce a valid mode or artifacts, stop and surface the failure instead of guessing a route.

## Status Requests

If the user asks for status on a decomposed feature, read the `bill-feature-goal.md` sidecar in this skill's own installed directory and follow its status behavior. Do not use the Skill tool — `bill-feature-goal` is an internal skill.

If the user asks for status on a single-spec feature implementation, read the `bill-feature-task.md` sidecar in this skill's own installed directory and follow its workflow status behavior when a workflow id is available. Do not use the Skill tool — `bill-feature-task` is an internal skill.
