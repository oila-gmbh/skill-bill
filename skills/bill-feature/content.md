---
name: bill-feature
description: "Use as the primary feature entry point: prepare governed manifest-backed feature specs, then dispatch them through bill-feature-goal. Use when user mentions implement feature, build feature, implement spec, run feature-task, feature from design doc, goal status, or resume goal."
---

# Feature Content

`bill-feature` is the primary feature entry point. It owns routing only: prepare the governed feature-spec artifacts first, then choose the correct downstream executor from the prepared result.

It does not replace `bill-feature-spec`, `bill-feature-task`, or `bill-feature-goal`. It composes them:

- `bill-feature-spec` owns feature-spec preparation.
- `bill-feature-task` owns each executable implementation unit.
- `bill-feature-goal` owns every prepared feature's one-or-more-subtask loop with durable state.

## Code-review selection

Accept zero or one `code-review:auto`, `code-review:inline`, or
`code-review:delegated` argument. Omission resolves to delegated review.
Reject a malformed, unknown, repeated, or conflicting `code-review:` argument
before preparing a spec, presenting confirmation, opening a workflow, or
launching a child. Carry an explicit argument unchanged into the selected task
or goal sidecar. When omitted, do not synthesize a `code-review:` token; preserve
the omission so the downstream confirmation gate can show `delegated (default)`
before resolving the review policy.

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

Handle `resumable`, `already_running`, `ambiguous`, and `terminal_only` before new-work preparation. For `resumable`, dispatch directly to the task sidecar with the persisted `workflow-id:<id>`, mode, and spec path; continuation authority predates the unified manifest invariant. Report and stop for running or terminal rows, and report every ambiguous candidate rather than selecting by recency. Only `no_match` may continue below. A malformed request, identity/snapshot/version error, selector mismatch, or explicit mode conflict must loud-fail rather than becoming `no_match`.

For `no_match`, invoke `bill-feature-spec` first in the current session unless the direct-dispatch rules below find existing governed artifacts. Do not write spec artifacts directly and do not fork spec-preparation logic.

Wait for `bill-feature-spec` to produce a parent spec, one or more executable subtask specs, and a schema-valid manifest under `.feature-specs/{ISSUE_KEY}-{feature-name}/`. Preparation mode is sizing metadata and does not select the executor.

## Direct Dispatch When Governed Artifacts Exist

Before running spec preparation, check `.feature-specs/{ISSUE_KEY}-*/` for the issue key:

- A bare governed `spec.md` without `decomposition-manifest.yaml` is intake, not prepared state. Invoke `bill-feature-spec` to preserve its contract content and upgrade it through the shared preparation path.
- Exactly one issue-matching, schema-valid `decomposition-manifest.yaml` is the sole prepared-feature authority marker. Dispatch it through `bill-feature-goal.md`, including when it contains exactly one subtask.
- Multiple matching manifests, malformed manifests, selector conflicts, or invalid prepared artifacts loud-fail. Never choose by recency and never fall back from an invalid manifest to a bare spec.
- Only invoke `bill-feature-spec` when there is no authoritative manifest.

## Dispatch

For every authoritative manifest, regardless of preparation mode or subtask cardinality:

  - Read the file `bill-feature-goal.md` located in this skill's own installed directory (a sibling of this `SKILL.md`) and execute its instructions in the current session with args: `<issue-key> mode:<mode> parallel-review:<agent> code-review:<explicit-mode> agent-addon-selection:<structured-selection>`, including the structured resolver output only when non-empty, omitting `parallel-review:<agent>` when the caller did not provide it and omitting the `code-review:` token when the caller did not provide it. Do not reconstruct raw add-on tokens. Do not use the Skill tool for this — `bill-feature-goal` is an internal skill and is not listed.
- Do not ask an extra confirmation before dispatching to the goal sidecar; the goal sidecar owns the one confirmation gate before starting `skill-bill goal`.
- Treat `skill-bill goal <issue_key>` as runtime behavior with durable workflow state, not as spec authoring.

If `bill-feature-spec` cannot produce a valid mode or artifacts, stop and surface the failure instead of guessing a route.

## Status Requests

If the user asks for status on a prepared feature, read the `bill-feature-goal.md` sidecar in this skill's own installed directory and follow its status behavior. Do not use the Skill tool — `bill-feature-goal` is an internal skill.
