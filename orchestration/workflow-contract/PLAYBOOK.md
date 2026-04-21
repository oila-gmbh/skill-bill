---
name: workflow-contract
description: Versioned contract for top-level orchestrator workflows that need durable step state, artifact handoff, and workflow-owned telemetry while reusing standalone skills as leaf steps.
---

# Shared Workflow Contract

This is the canonical workflow contract for Skill Bill. It defines when a
top-level skill should be treated as a workflow, what state a workflow owns,
how step handoff artifacts stay explicit, and how child skill telemetry rolls
up into a single parent-owned lifecycle.

This contract complements existing skill contracts; it does not replace them.
Skills remain the user-facing entry points and the reusable execution units.
Workflows add durable orchestration for the small set of top-level commands
whose behavior already spans multiple sequential child steps.

## Contract Version

The current workflow contract version is **`0.1`**.

- `0.x` means the shape is piloted and may still tighten before a stable `1.0`.
- The first pilot scope is `bill-feature-implement`.
- `bill-feature-verify` is the next intended adopter.

## When To Use A Workflow

Use the workflow contract only when all of the following are true:

- the command is a stable top-level entry point
- it coordinates multiple child skills or subagents over several phases
- the phases exchange structured artifacts that matter after the child exits
- resume, retry, or auditability would be materially improved by durable step
  state rather than prompt-only memory

Do not use the workflow contract for:

- routed leaf skills such as `bill-kotlin-code-review`
- governed add-ons
- single-pass shells that do not coordinate multi-step state
- specialist review passes whose output is already owned by a parent review

## Relationship To Skills

Workflows and skills have different responsibilities:

- **skills** own user-facing behavior, routing, rubrics, and standalone use
- **workflows** own step state, artifact handoff, retries, resume points, and
  parent-level completion semantics

A workflow must invoke existing skills wherever a skill boundary already
exists. The workflow layer must not duplicate stack routing, review rubrics,
quality-check behavior, or add-on selection logic that already belongs to a
skill.

## Workflow-Owned State

Every workflow run owns a single state object. A runtime may persist it in any
backend, but the shape below is the contract:

```json
{
  "workflow_id": "wfl-20260421-142530-a1b2",
  "workflow_name": "bill-feature-implement",
  "contract_version": "0.1",
  "status": "running",
  "current_step_id": "plan",
  "steps": [
    {
      "step_id": "assess",
      "status": "completed",
      "attempt_count": 1
    }
  ],
  "artifacts": {},
  "child_steps": []
}
```

Required top-level fields:

- `workflow_id` — unique id for the workflow run
- `workflow_name` — stable top-level command name, e.g. `bill-feature-implement`
- `contract_version` — workflow contract version string
- `status` — `pending | running | completed | failed | abandoned`
- `current_step_id` — stable step id currently being evaluated, or empty when
  terminal
- `steps` — ordered list of step state objects
- `artifacts` — map of named workflow artifacts
- `child_steps` — collected telemetry payloads returned by child skills invoked
  with `orchestrated=true`

Required per-step fields:

- `step_id` — stable identifier, never user-visible prose
- `status` — `pending | running | completed | failed | blocked | skipped`
- `attempt_count` — integer incremented on each re-run of the same step

Optional per-step fields:

- `started_at`
- `finished_at`
- `failure_reason`
- `depends_on`
- `artifact_keys`

## Artifact Handoff Contract

Every boundary between workflow steps must be explicit about what artifact the
parent carries forward. A step may emit prose for a human, but the workflow
must retain a structured artifact for later steps.

Artifact rules:

- Artifacts use stable keys such as `assessment`, `plan`, or `audit_report`.
- The parent workflow is the authority for artifact storage.
- Child steps may read only the artifacts the parent passes into their
  briefing or invocation.
- A workflow step must not require re-reading a raw upstream spec when an
  authoritative parent-owned artifact already exists for that phase.
- Human-readable summaries are optional; artifact keys and meanings are not.

## Step Semantics

Each workflow step must declare:

- a stable `step_id`
- its inputs
- its success artifact or side effect
- its retry rule
- its failure boundary
- whether it is safe to resume from the next step without rerunning it

Shared step rules:

- Steps are sequential by default.
- Parallel fan-out is allowed only when child steps have disjoint write scope
  or are read-only and the parent owns fan-in.
- A failed step leaves the workflow in `failed` or `blocked`; the parent must
  not silently continue.
- Retrying a step increments `attempt_count` and replaces or supersedes that
  step's prior artifact.
- A workflow may skip a step only when the governing skill contract already
  defines that skip as legal.

## Parent-Owned Telemetry

The workflow contract extends the existing standalone-first telemetry contract
in `orchestration/telemetry-contract/PLAYBOOK.md`.

- The top-level workflow owns the lifecycle event.
- Child skills invoked inside the workflow must use `orchestrated=true`.
- Returned child `telemetry_payload` objects are appended to `child_steps`.
- The workflow emits one parent finished event with the collected `child_steps`.

This preserves the current Skill Bill rule: one user-initiated workflow should
produce one authoritative completion event.

## Runtime Pilot Surface

The first runtime-facing pilot uses dedicated MCP tools for
`bill-feature-implement` workflow state:

- `feature_implement_workflow_list`
- `feature_implement_workflow_latest`
- `feature_implement_workflow_open`
- `feature_implement_workflow_update`
- `feature_implement_workflow_get`
- `feature_implement_workflow_resume`
- `feature_implement_workflow_continue`

These tools persist workflow state independently of telemetry settings. The
existing `feature_implement_started` and `feature_implement_finished` tools
remain telemetry-owned; they are linked to workflow state via `session_id`
rather than replaced by it.

`feature_implement_workflow_continue` is the first activation tool in the pilot:
it does not execute the workflow itself, but it re-opens resumable state and
returns a governed continuation payload for `bill-feature-implement`, including
the resumed step id, recovered artifacts, reference sections to read, and a
paste-ready continuation prompt.

The CLI exposes the same recovery surface through:

- `skill-bill workflow show <workflow-id>`
- `skill-bill workflow resume <workflow-id>`
- `skill-bill workflow continue <workflow-id>`

## Pilot: `bill-feature-implement`

`bill-feature-implement` is the first workflow contract pilot. Its stable step
ids are:

1. `assess`
2. `create_branch`
3. `preplan`
4. `plan`
5. `implement`
6. `review`
7. `audit`
8. `validate`
9. `write_history`
10. `commit_push`
11. `pr_description`
12. `finish`

Required workflow artifacts for the pilot:

- `assessment` — accepted criteria, non-goals, open questions, feature size,
  feature name, rollout need
- `preplan_digest` — boundary history/decision findings, reusable patterns,
  selected add-ons, validation strategy
- `plan` — ordered tasks or phases with criteria coverage
- `implementation_summary` — files changed, tasks completed, deviations, tests
  written
- `review_result` — prioritized findings plus diff pointer used for review
- `audit_report` — per-criterion pass/fail evidence
- `validation_result` — routed skill or repo-native validator result
- `history_result` — written/skipped outcome for boundary history
- `pr_result` — PR url/title or terminal failure note

Pilot-specific retry rules:

- `review` may loop back to `implement` up to the governing skill's max review
  iterations
- `audit` may loop back to `plan` and `implement` up to the governing skill's
  max audit iterations
- `validate` retries in place until pass/fail is final
- `commit_push` and `pr_description` are terminal gates; no downstream step may
  continue if they fail

## Authoring Boundary

The workflow contract should stay in orchestration, not in platform packs.

- Platform packs may enrich child skills used by a workflow.
- Platform packs do not define new top-level workflow state machines.
- The top-level workflow remains manifest-agnostic unless and until Skill Bill
  introduces a governed workflow manifest shape.

## Non-Goals

- Not a replacement for `SKILL.md`, `content.md`, or routed skill manifests
- Not a generic DAG engine for arbitrary repo automation
- Not a reason to create a new slash command per workflow step
- Not a hidden fallback layer that continues after a failed step
- Not a second telemetry system separate from the existing parent-owned model

## Next Adoption Rules

Before another command adopts the workflow contract, confirm all of the
following:

- the command already behaves like a multi-step orchestrator in practice
- durable step state would improve resume, retry, or audit behavior
- the child boundaries already have stable artifact contracts or can be given
  them cleanly
- the workflow would reduce prompt duplication instead of creating a second
  source of truth
