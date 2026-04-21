---
name: bill-feature-verify
description: Verify a PR against a task spec — extract acceptance criteria, decide whether rollout expectations matter, audit feature-flag behavior only when the spec or diff requires it, run full code review, and audit completeness. Use when reviewing teammates' PRs to ensure they match the design doc/spec. The reverse of bill-feature-implement. Use when user mentions verify PR, check PR against spec, review against design doc, or verify implementation.
---

# Feature Verify

## Project Overrides

Follow the shell ceremony in [shell-ceremony.md](shell-ceremony.md).

If `.agents/skill-overrides.md` exists in the project root and contains a matching section, read that section and apply it as the highest-priority instruction for this skill.

## Workflow Overview

```
Task Spec + PR → Extract Criteria → Gather Diff →
  Feature Flag Audit (if needed) →
  Code Review (dynamic agents) →
  Completeness Audit (criteria vs code) →
  Consolidated Verdict
```

## Workflow State

`bill-feature-verify` adopts the workflow contract as a durable top-level
workflow. In addition to the existing telemetry tools, the orchestrator must
persist workflow state with:

- `feature_verify_workflow_open`
- `feature_verify_workflow_update`
- `feature_verify_workflow_get`

Open workflow state once, immediately after Step 2 is confirmed. Save both ids:

- `session_id` from `feature_verify_started` for telemetry
- `workflow_id` from `feature_verify_workflow_open` for durable state

After every major phase boundary, call `feature_verify_workflow_update` with:

- the new `workflow_status`
- the new `current_step_id`
- `step_updates` for the steps whose status changed
- `artifacts_patch` for the structured artifact produced by that phase

Workflow state is independent of telemetry settings. Persist it even when
`feature_verify_started` or `feature_verify_finished` returns `status: skipped`.

Stable verify workflow step ids:

1. `collect_inputs`
2. `extract_criteria`
3. `gather_diff`
4. `feature_flag_audit`
5. `code_review`
6. `completeness_audit`
7. `verdict`
8. `finish`

Stable workflow artifact keys:

- `input_context`
- `criteria_summary`
- `diff_summary`
- `feature_flag_audit_result`
- `review_result`
- `completeness_audit_result`
- `verdict_result`

## Continuation Mode

When an external caller re-enters this skill using the payload returned by
`feature_verify_workflow_continue`, treat that payload as the authoritative
continuation contract for the resumed run.

Continuation-mode rules:

- Keep the same `workflow_id` and `session_id`; do not open a new workflow.
- Use `continue_step_id` as the starting point.
- Use `step_artifacts` and `session_summary` as authoritative recovered
  context; do not reconstruct earlier phases from chat history unless the step
  explicitly requires user confirmation.
- Read the `reference_sections` listed in the continuation payload before
  resuming work.
- Skip already-completed earlier steps unless the normal workflow loop sends
  work backwards.
- After the resumed step completes, continue the normal sequence defined below.
- If `continue_status` is `done`, do not rerun the workflow; summarize the
  terminal state instead.
- If `continue_status` is `blocked`, stop and restore the missing artifacts
  named by the workflow payload before continuing.

## Step 1: Collect Inputs

Ask the user for the task spec (paste, file path, directory) and PR (number, branch, or commit range).

Accept PDFs (read in page ranges if >10 pages), markdown, inline text. If total text exceeds ~8,000 words, ask which sections are most relevant.

The success artifact for this step is `input_context`: the normalized spec
source, verify target, and any user clarifications that later steps need.

## Step 2: Extract Acceptance Criteria

After reading the spec, produce in one pass:
1. **Acceptance criteria** — numbered list
2. **Non-goals** — things explicitly out of scope
3. **Rollout expectation** — does the spec require guarded rollout?
4. **Key technical constraints** — specific patterns, APIs, or architectural requirements

Then ask: **Confirm or adjust the criteria before I review the PR.**

The success artifact for this step is `criteria_summary`: acceptance criteria,
non-goals, rollout expectation, and technical constraints.

After the user confirms Step 2:

1. Call `feature_verify_started` with `acceptance_criteria_count`,
   `rollout_relevant`, and `spec_summary`. Save the returned `session_id`.
2. Call `feature_verify_workflow_open` with:
   - `session_id`
   - `current_step_id: "gather_diff"`
3. Save the returned `workflow_id`.
4. Immediately call `feature_verify_workflow_update` to:
   - mark `collect_inputs` as completed
   - mark `extract_criteria` as completed
   - set `gather_diff` to running
   - persist `input_context`
   - persist `criteria_summary`

## Step 3: Gather PR Diff

Based on user input, gather changes via `gh pr diff`, `git diff`, or `git log`.

The success artifact for this step is `diff_summary`: the resolved diff target,
commit/PR reference, changed-files summary, and any canonical diff pointer
forwarded to review.

After Step 3, call `feature_verify_workflow_update` to:

- mark `gather_diff` as completed
- set `feature_flag_audit` to running
- persist `diff_summary`

## Step 4: Feature Flag Audit (conditional)

Read [audit-rubrics.md](audit-rubrics.md) for the full feature flag audit rubric and output format.

The success artifact for this step is `feature_flag_audit_result`.

If the audit is not required, this step may be legally skipped. Persist the
artifact either way and update workflow state to:

- mark `feature_flag_audit` as completed or `skipped`
- set `code_review` to running
- persist `feature_flag_audit_result`

## Step 5: Code Review

Run `bill-code-review` against the PR diff. Follow the full skill instructions including any matching `.agents/skill-overrides.md` section.

The success artifact for this step is `review_result`.

When this skill runs `bill-code-review`, this skill is itself a parent. Pass
`orchestrated=true` to `import_review` and `triage_findings`, collect the
returned `telemetry_payload`, and store it alongside `review_result` so the
verify workflow remains the lifecycle owner.

After Step 5, call `feature_verify_workflow_update` to:

- mark `code_review` as completed
- set `completeness_audit` to running
- persist `review_result`

## Step 6: Completeness Audit

Read [audit-rubrics.md](audit-rubrics.md) for the completeness audit format and rules.

The success artifact for this step is `completeness_audit_result`.

If the verify target changed materially during the same session, this step may
loop back to `gather_diff` or `code_review`. When that happens:

- mark `completeness_audit` as no longer running
- set the next active step explicitly
- increment the next step's `attempt_count`

Otherwise, after Step 6, call `feature_verify_workflow_update` to:

- mark `completeness_audit` as completed
- set `verdict` to running
- persist `completeness_audit_result`

## Step 7: Consolidated Verdict

Read [audit-rubrics.md](audit-rubrics.md) for the verdict format and PR comment instructions.

The success artifact for this step is `verdict_result`.

After Step 7, call `feature_verify_workflow_update` to:

- mark `verdict` as completed
- set `finish` to running
- persist `verdict_result`

## Telemetry

This skill emits `skillbill_feature_verify_started` and `_finished` events via the `feature_verify_started` / `feature_verify_finished` MCP tools.

For the shared telemetry contract including the `orchestrated` flag semantics, follow [telemetry-contract.md](telemetry-contract.md).

### Skill-specific telemetry fields

**Standalone invocation:**
1. Call `feature_verify_started` after Step 2 (criteria confirmed) with `acceptance_criteria_count`, `rollout_relevant`, and `spec_summary`. Save the returned `session_id`.
2. Call `feature_verify_finished` after Step 7 (verdict delivered) with `session_id`, `feature_flag_audit_performed`, `review_iterations`, `audit_result` (`all_pass` / `had_gaps` / `skipped`), `completion_status` (`completed` / `abandoned_at_review` / `abandoned_at_audit` / `error`), and optional `gaps_found` list.

**Orchestrated invocation** (when called from another workflow that passes `orchestrated=true`):
1. Skip `feature_verify_started`.
2. Call `feature_verify_finished` with `orchestrated=true` and all started+finished fields combined. The tool returns `{"mode": "orchestrated", "telemetry_payload": {...}}`.
3. Return that payload to the orchestrator — it will embed it in its own finished event.

Before or immediately after `feature_verify_finished`, call
`feature_verify_workflow_update` one final time to:

- mark `finish` as completed for successful runs
- set `workflow_status` to `completed`, `abandoned`, or `failed`
- keep `current_step_id` at the step where the workflow stopped
- persist any final artifact patch needed to explain the terminal state

## Nested Child Tools

When this skill runs `bill-code-review` as part of the verify workflow, this
skill is itself a parent. Pass `orchestrated=true` to `import_review` and
`triage_findings`, collect the returned `telemetry_payload`, and include it in
the verify-owned `review_result` or final verify payload. Standalone
feature-verify still emits only its own `skillbill_feature_verify_finished`
event; the nested review remains parent-owned.

## Skills Reused

- `bill-code-review` — shared router for stack-specific code review
- `bill-feature-guard` — optional rollout checklist when the spec, diff, or repo policy requires it
