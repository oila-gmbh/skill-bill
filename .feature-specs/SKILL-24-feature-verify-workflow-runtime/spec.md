# Feature: feature-verify-workflow-runtime
Created: 2026-04-21
Status: In Progress
Sources: User request in chat; existing `bill-feature-verify` skill contract; `orchestration/workflow-contract/PLAYBOOK.md`; shipped `bill-feature-implement` workflow runtime and continuation surfaces

## Acceptance Criteria
1. `bill-feature-verify` adopts the workflow contract as the second top-level workflow, with stable step ids, named artifacts, durable status, retry semantics, and resume/continue behavior comparable to `bill-feature-implement`.
2. The runtime persists verify workflow state independently of telemetry and exposes discovery, inspection, resume, and continue surfaces through both CLI and MCP.
3. `skills/bill-feature-verify/SKILL.md` is updated so the orchestrator opens workflow state after criteria confirmation, persists artifacts after each major phase, and supports re-entry from a continuation payload instead of restarting from Step 1.
4. The workflow keeps parent-owned telemetry intact: `bill-feature-verify` remains the lifecycle owner, child review telemetry stays embedded through `orchestrated=true`, and workflow state links to telemetry through `session_id` rather than replacing it.
5. The implementation ships deterministic state/resume tests plus opt-in end-to-end coverage for CLI and MCP workflow surfaces, including blocked continuation, terminal-state handling, and at least one backward-loop recovery case.
6. This feature does not introduce a generic workflow engine, does not migrate `bill-feature-verify` onto the shell+content contract, and does not add workflow state to routed leaf skills.

---

## Problem Statement

`bill-feature-verify` already behaves like a workflow in practice:

1. collect a task spec and target PR
2. extract and confirm criteria
3. gather the diff
4. optionally audit rollout or feature-flag behavior
5. run a full code review
6. audit completeness against the criteria
7. produce one consolidated verdict

Today that orchestration mostly lives in prompt prose and transient agent context. That has the same weaknesses `bill-feature-implement` had before workflow support:

- interruption means reconstructing progress from chat history
- intermediate artifacts such as extracted criteria or audit results are implicit rather than parent-owned state
- there is no first-class “resume latest verify workflow” surface
- continuation semantics are not governed, inspectable, or testable on their own

`bill-feature-implement` already proved the value of a thin workflow runtime layered over an existing top-level orchestrator. `bill-feature-verify` is the next obvious adopter because it already has stable phases, parent-owned telemetry, reusable child skill boundaries, and user-visible value from resuming interrupted work.

## Goals

- Make `bill-feature-verify` resumable and inspectable without changing its user-facing command.
- Persist phase state and verify artifacts in a durable runtime-owned store.
- Make continuation explicit: resume from the saved phase with saved artifacts instead of replaying the whole conversation.
- Keep telemetry ownership unchanged and aligned with the existing orchestrated-child pattern.
- Reuse the `bill-feature-implement` workflow model where it is already proven.

## Non-Goals

- Building a generic workflow engine or workflow manifest system.
- Migrating `bill-feature-verify` onto the shell+content contract in this feature.
- Adding workflow state to `bill-code-review`, `bill-quality-check`, `bill-pr-description`, or routed platform specialists.
- Rewriting the verification rubric or altering review quality standards.
- Replacing telemetry storage with workflow storage.

## Why This Skill Qualifies

`bill-feature-verify` meets all workflow-adoption criteria from `orchestration/workflow-contract/PLAYBOOK.md`:

- it is a stable top-level command
- it coordinates multiple child phases over time
- those phases exchange structured artifacts that matter after the child exits
- interruption and retry behavior materially improve when state is durable

It also has a clean workflow boundary:

- child review depth still belongs to `bill-code-review`
- optional rollout auditing still belongs to the verify contract and its rubrics
- the workflow layer owns only step state, artifact handoff, resume/continue, and final completion semantics

## Proposed Workflow Shape

Workflow name:

- `bill-feature-verify`

Stable step ids:

1. `collect_inputs`
2. `extract_criteria`
3. `gather_diff`
4. `feature_flag_audit`
5. `code_review`
6. `completeness_audit`
7. `verdict`
8. `finish`

Workflow statuses:

- `pending`
- `running`
- `completed`
- `failed`
- `abandoned`

Per-step statuses:

- `pending`
- `running`
- `completed`
- `failed`
- `blocked`
- `skipped`

## Artifact Contract

Required workflow artifact keys:

- `input_context`
  - normalized spec source, PR/diff source, and any user clarifications
- `criteria_summary`
  - numbered acceptance criteria, non-goals, rollout expectation, and key technical constraints
- `diff_summary`
  - resolved diff target, commit/PR reference, and changed-files summary
- `feature_flag_audit_result`
  - skipped or performed; findings and rationale when applicable
- `review_result`
  - prioritized review findings and any nested child telemetry payloads
- `completeness_audit_result`
  - criterion-by-criterion pass/fail evidence and missing work
- `verdict_result`
  - final recommendation, key blockers, and PR-comment-ready summary

Optional workflow artifact keys:

- `session_notes`
  - user adjustments, clarifications, or recovery notes added during resume
- `review_diff_pointer`
  - canonical diff/PR pointer forwarded to `bill-code-review`

Artifact rules:

- the verify workflow is the authority for these artifacts
- child phases receive only the artifacts the parent passes forward
- the workflow may summarize for humans, but the stored artifact keys are the stable contract
- resume/continue decisions are based on artifact presence and current step id, not on free-form transcript reconstruction

## Step Semantics

### 1. `collect_inputs`

Inputs:
- user-provided spec location or inline spec
- PR number, branch, commit range, or diff target

Success output:
- `input_context`

Retry rule:
- may retry in place until the verify target is unambiguous

Resume rule:
- if complete, later steps must not ask for the same inputs again unless recovery explicitly marks them stale

### 2. `extract_criteria`

Inputs:
- `input_context`
- task spec contents

Success output:
- `criteria_summary`

Retry rule:
- may retry in place until the user confirms or adjusts criteria

Resume rule:
- workflow opens only after criteria are confirmed; this matches the existing telemetry boundary

### 3. `gather_diff`

Inputs:
- `input_context`
- confirmed criteria

Success output:
- `diff_summary`

Retry rule:
- may retry in place if the diff target changes or cannot be gathered

Resume rule:
- completed diff gathering should not be recomputed unless the workflow target changed

### 4. `feature_flag_audit`

Inputs:
- `criteria_summary`
- `diff_summary`

Success output:
- `feature_flag_audit_result`

Retry rule:
- may retry in place if the audit is required but inconclusive

Skip rule:
- may be legally `skipped` when rollout/feature-flag auditing is not required by the spec or diff

### 5. `code_review`

Inputs:
- `criteria_summary`
- `diff_summary`
- optional `feature_flag_audit_result`

Success output:
- `review_result`

Retry rule:
- may retry in place
- may loop once or more only if the governing verify skill later gains an explicit review-refresh rule after user changes or new commits

Parent/child rule:
- invoke `bill-code-review` with `orchestrated=true`
- store the returned child telemetry payload alongside the review result

### 6. `completeness_audit`

Inputs:
- `criteria_summary`
- `diff_summary`
- `review_result`
- optional `feature_flag_audit_result`

Success output:
- `completeness_audit_result`

Retry rule:
- may retry in place when evidence is incomplete

Loop rule:
- may loop back to `gather_diff` or `code_review` only if the verify target changed materially during the same session; otherwise it is a terminal assessment step

### 7. `verdict`

Inputs:
- `criteria_summary`
- `review_result`
- `completeness_audit_result`
- optional `feature_flag_audit_result`

Success output:
- `verdict_result`

Retry rule:
- may retry in place until the verdict is internally consistent and complete

Resume rule:
- if all required artifacts exist, this is a high-value continuation point because no upstream re-analysis is needed

### 8. `finish`

Inputs:
- `verdict_result`
- workflow/session metadata

Success output:
- terminal workflow status and final verify telemetry call

Retry rule:
- terminal gate only; no downstream phase may continue without it

## Runtime Surface

The runtime should mirror the `bill-feature-implement` model closely enough that operators do not learn two different systems.

### Persistence

Add a dedicated persisted store for verify workflow runs, parallel to `feature_implement_workflows`.

Expected stored fields:

- `workflow_id`
- `session_id`
- `workflow_name`
- `contract_version`
- `workflow_status`
- `current_step_id`
- `steps_json`
- `artifacts_json`
- timestamps

### MCP tools

Add:

- `feature_verify_workflow_open`
- `feature_verify_workflow_update`
- `feature_verify_workflow_get`
- `feature_verify_workflow_list`
- `feature_verify_workflow_latest`
- `feature_verify_workflow_resume`
- `feature_verify_workflow_continue`

Rules:

- these tools persist workflow state independently of telemetry configuration
- they return verify-specific payloads rather than reusing implement wording
- `continue` reopens resumable state and returns a verify continuation contract instead of executing the workflow directly

### CLI

Prefer a verify-specific surface instead of overloading the existing feature-implement-only commands.

Recommended command shape:

```bash
skill-bill verify-workflow list
skill-bill verify-workflow show <workflow-id>
skill-bill verify-workflow show --latest
skill-bill verify-workflow resume <workflow-id>
skill-bill verify-workflow resume --latest
skill-bill verify-workflow continue <workflow-id>
skill-bill verify-workflow continue --latest
```

This keeps operator intent explicit and avoids pretending all workflows are interchangeable before a shared multi-workflow CLI layer exists.

## Skill Integration

`skills/bill-feature-verify/SKILL.md` should be updated so the orchestrator:

1. collects and confirms criteria first
2. calls `feature_verify_started` after criteria confirmation, preserving the existing telemetry boundary
3. opens workflow state immediately after the started event returns `session_id`
4. persists workflow state after each major phase with:
   - `workflow_status`
   - `current_step_id`
   - `step_updates`
   - `artifacts_patch`
5. calls `feature_verify_finished` at the terminal boundary with parent-owned telemetry semantics unchanged

The skill should also gain an explicit continuation mode section parallel to `bill-feature-implement`:

- continuation payload is the supported re-entry contract
- resumed step id is authoritative
- persisted artifacts are authoritative context
- already-completed earlier steps are not rerun unless the continuation contract explicitly says recovery is required

## Resume And Continue Contract

`resume` should answer:

- can the workflow resume?
- what is the target step?
- what artifacts are available?
- what artifacts are required?
- what artifacts are missing?
- what should happen next?

`continue` should:

- reopen the workflow to `running` when continuation is legal
- increment the resumed step's `attempt_count`
- return a step-specific continuation payload for `bill-feature-verify`

Expected continuation payload fields:

- `skill_name`
- `continuation_mode`
- `continue_step_id`
- `continue_step_directive`
- `required_artifacts`
- `step_artifacts`
- `session_summary`
- `reference_sections`
- `continuation_entry_prompt`

Terminal-state behavior:

- completed workflow: return a `done`/terminal payload; do not reopen execution
- abandoned workflow: allow recovery only when required artifacts are still sufficient
- failed or blocked workflow: continue only when the missing prerequisites are explicitly addressed

## Telemetry Contract

Workflow adoption must preserve the current telemetry model:

- `feature_verify_started` and `feature_verify_finished` remain authoritative for verify lifecycle telemetry
- workflow state links to telemetry via `session_id`
- nested `bill-code-review` calls remain orchestrated children
- the verify workflow remains the parent owner of embedded child telemetry in its finished payload

No workflow-specific telemetry stream should be introduced in this feature.

## Tests

### Required unit/integration coverage

- verify workflow state helpers
- validation of step ids and statuses
- MCP open/update/get/list/latest/resume/continue behavior
- CLI list/show/resume/continue behavior
- contract test proving `bill-feature-verify` uses the workflow tools in its own instructions

### Required deterministic agent-harness coverage

- interrupted at `gather_diff`, resume into `feature_flag_audit` or `code_review`
- interrupted at `code_review`, continue into `completeness_audit`
- interrupted at `verdict`, continue directly to terminal output
- blocked continuation because required artifacts are missing
- terminal completed workflow does not reopen

### Required opt-in E2E coverage

- CLI `--latest` workflow discovery and continuation
- MCP latest/list parity with CLI
- telemetry-linked workflow with a real `session_id`
- skipped `feature_flag_audit` path
- performed `feature_flag_audit` path
- abandoned and completed terminal-state handling

As with `bill-feature-implement`, the E2E tier should stay opt-in unless it proves stable enough for default CI.

## Implementation Sequence

1. Add verify workflow constants, DB storage, and domain helpers.
2. Add MCP workflow tools.
3. Add verify workflow CLI surface.
4. Update `bill-feature-verify` to open/update/continue workflow state.
5. Add deterministic tests.
6. Add opt-in subprocess E2E tests.
7. Only after this lands, evaluate whether shared workflow plumbing should be factored out of implement/verify.

## Open Questions

1. Should verify workflow CLI live under `skill-bill verify-workflow ...` or should Skill Bill grow a typed `skill-bill workflow --kind feature-verify ...` surface?
Recommendation: start with `verify-workflow` to avoid premature generic abstraction.

2. Should `feature_flag_audit` be modeled as a normal step that is sometimes `skipped`, or omitted from the step list when not needed?
Recommendation: keep it as a stable step id and mark it `skipped` when not applicable. This keeps resume logic and analytics simpler.

3. Should completeness audit ever loop back to code review automatically?
Recommendation: no new automatic loop in the first cut. Keep verify mostly linear, with retries in place, unless real usage shows a governed backward edge is needed.

## Out Of Scope Follow-Ups

- Refactor implement and verify onto a shared multi-workflow runtime module.
- Migrate `bill-feature-verify` onto the shell+content contract.
- Add workflow support to `bill-create-skill`.
- Introduce workflow-level dashboards or a generic workflow registry UI.
