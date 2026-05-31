---
name: bill-feature-verify
description: Verify a PR against a task spec — extract acceptance criteria, decide whether rollout expectations matter, audit feature-flag behavior only when the spec or diff requires it, run full code review, and audit completeness. Use when reviewing teammates' PRs to ensure they match the design doc/spec. The reverse of bill-feature-task. Use when user mentions verify PR, check PR against spec, review against design doc, or verify implementation.
---

# Feature Verify Content

This file is the author-owned execution body for `bill-feature-verify`. It carries the workflow-state contract, continuation contract, stable step ids, stable artifact names, telemetry ownership rules, and the per-step orchestration prose.

## Workflow State

`bill-feature-verify` adopts the workflow contract as a durable top-level workflow. In addition to the existing telemetry tools, the orchestrator must persist workflow state with:

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

Workflow state is independent of telemetry settings. Persist it even when `feature_verify_started` or `feature_verify_finished` returns `status: skipped`.

Stable step ids: `collect_inputs`, `extract_criteria`, `gather_diff`, `feature_flag_audit`, `code_review`, `completeness_audit`, `verdict`, `finish`. Stable artifact names: `input_context`, `criteria_summary`, `diff_summary`, `feature_flag_audit_result`, `review_result`, `completeness_audit_result`, `verdict_result`.

## Continuation Mode

When an external caller re-enters this skill using the payload returned by `feature_verify_workflow_continue`, treat that payload as the authoritative continuation contract for the resumed run.

Continuation-mode rules:

- Keep the same `workflow_id` and `session_id`; do not open a new workflow.
- Use `continue_step_id` as the starting point.
- Use `step_artifacts` and `session_summary` as authoritative recovered context; do not reconstruct earlier phases from chat history unless the step explicitly requires user confirmation.
- Read the `reference_sections` listed in the continuation payload before resuming work.
- Skip already-completed earlier steps unless the normal workflow loop sends work backwards.
- After the resumed step completes, continue the normal sequence defined below.
- If `continue_status` is `done`, do not rerun the workflow; summarize the terminal state instead.
- If `continue_status` is `blocked`, stop and restore the missing artifacts named by the workflow payload before continuing.

## Step 1: Collect Inputs

Step id: `collect_inputs`

Primary artifact: `input_context`

Ask the user for the task spec and PR inputs.

Accept the task spec as pasted text, a file path, a directory, or another readable spec source. Accept the PR as a number, branch, or commit range. Accept PDFs if the spec lives in one; read them in page ranges when they are longer than 10 pages. If the total text exceeds roughly 8,000 words, ask which sections matter most before continuing.

The success artifact for this step is `input_context`: the normalized spec source, the verify target, and any user clarifications that later steps need.

## Step 2: Extract Acceptance Criteria

Step id: `extract_criteria`

Primary artifact: `criteria_summary`

After reading the spec, produce in one pass:

1. **Acceptance criteria** — numbered list
2. **Non-goals** — things explicitly out of scope
3. **Rollout expectation** — does the spec require guarded rollout?
4. **Key technical constraints** — specific patterns, APIs, or architectural requirements

Then ask: **Confirm or adjust the criteria before I review the PR.**

The success artifact for this step is `criteria_summary`: acceptance criteria, non-goals, rollout expectation, and technical constraints.

After Step 2 is confirmed, call `feature_verify_started` and save the returned `session_id`. Open workflow state with `feature_verify_workflow_open`. Immediately call `feature_verify_workflow_update` to mark `collect_inputs` and `extract_criteria` completed, set `gather_diff` to running, and persist `input_context` plus `criteria_summary`.

## Step 3: Gather PR Diff

Step id: `gather_diff`

Primary artifact: `diff_summary`

Based on user input, gather changes via `gh pr diff`, `git diff`, or `git log`. Resolve the PR diff target from the user input, persist `diff_summary` before advancing to the feature-flag audit, and update workflow state so `gather_diff` is completed and `feature_flag_audit` is running.

The success artifact for this step is `diff_summary`: the resolved diff target, commit/PR reference, changed-files summary, and any canonical diff pointer forwarded to review.

## Step 4: Feature Flag Audit (conditional)

Step id: `feature_flag_audit`

Primary artifact: `feature_flag_audit_result`

Use the Feature Flag Audit rubric below for the full rubric and output format.

The audit is legally skippable when the spec and diff do not require it. Persist `feature_flag_audit_result` either way. Update workflow state so `feature_flag_audit` is completed or skipped and `code_review` is running.

## Step 5: Code Review

Step id: `code_review`

Primary artifact: `review_result`

Run `bill-code-review` against the PR diff. Follow the full skill instructions including any matching `.agents/skill-overrides.md` section.

When this skill runs `bill-code-review`, this skill is itself a parent. Pass `orchestrated=true` to `import_review` and `triage_findings`, collect the returned `telemetry_payload`, and store it alongside `review_result` so the verify workflow remains the lifecycle owner.

Persist `review_result` after review finishes. Update workflow state so `code_review` is completed and `completeness_audit` is running.

## Step 6: Completeness Audit

Step id: `completeness_audit`

Primary artifact: `completeness_audit_result`

Use the Completeness Audit rubric below for the audit format and rules.

Persist `completeness_audit_result` when the audit succeeds. When the verify target changes materially during the same session, loop back to `gather_diff` or `code_review` and increment the next step's `attempt_count`. Otherwise, update workflow state so `completeness_audit` is completed and `verdict` is running.

## Step 7: Consolidated Verdict

Step id: `verdict`

Primary artifact: `verdict_result`

Use the Consolidated Verdict rubric below for the verdict format and PR comment instructions.

Persist `verdict_result` after the verdict is delivered. Update workflow state so `verdict` is completed and `finish` is running.

## Telemetry

This skill emits `skillbill_feature_verify_started` and `_finished` events via the `feature_verify_started` / `feature_verify_finished` MCP tools.

Skill-specific telemetry fields, standalone invocation:

1. Call `feature_verify_started` after Step 2 (criteria confirmed) with `acceptance_criteria_count`, `rollout_relevant`, and `spec_summary`. Save the returned `session_id`.
2. Call `feature_verify_finished` after Step 7 (verdict delivered) with `session_id`, `feature_flag_audit_performed`, `review_iterations`, `audit_result` (`all_pass` / `had_gaps` / `skipped`), `completion_status` (`completed` / `abandoned_at_review` / `abandoned_at_audit` / `error`), `history_relevance` (`none` / `irrelevant` / `low` / `medium` / `high`), `history_helpfulness` (`none` / `irrelevant` / `low` / `medium` / `high`), and optional `gaps_found` list.

Orchestrated invocation (when called from another workflow that passes `orchestrated=true`):

1. Skip `feature_verify_started`.
2. Call `feature_verify_finished` with `orchestrated=true` and all started+finished fields combined. The tool returns `{"mode": "orchestrated", "telemetry_payload": {...}}`.
3. Return that payload to the orchestrator — it will embed it in its own finished event.

Before or immediately after `feature_verify_finished`, call `feature_verify_workflow_update` one final time to:

- mark `finish` as completed for successful runs
- set `workflow_status` to `completed`, `abandoned`, or `failed`
- keep `current_step_id` at the step where the workflow stopped
- persist any final artifact patch needed to explain the terminal state

## Audit Rubrics

Use these inline rubrics for the feature-flag audit, completeness audit, and final verdict phases.

## Feature Flag Audit

**Skip if:** the spec does not require feature-flagged rollout, no feature flag appears in the diff, and repo policy does not require one.

**Run if:** the spec requires a feature flag, a feature flag appears in the diff, or the repo has explicit feature-flag policy for this change.

Verify against the repo's rollout requirements. If the repo does not define its own rollout rubric, use `bill-feature-guard` as a narrow checklist rather than assuming every repo follows it by default:

1. **Flag exists** — is the flag defined in the codebase?
2. **Rollback safety** — when flag is OFF, behavior is identical to before the PR
3. **Minimal checks** — feature flag checks are at the highest practical level (not scattered)
4. **Legacy preserved** — if Legacy pattern used, legacy code is untouched
5. **No hybrid states** — no mixing of old/new behavior paths
6. **Default value** — if a new flag is introduced, it defaults to `false` (disabled)

Output:

```
FEATURE FLAG AUDIT
Flag name: <name>
Pattern: Legacy / DI Switch / Simple Conditional / N/A

[ PASS | FAIL ] Flag defined in codebase
[ PASS | FAIL ] Rollback safe (flag OFF = identical old behavior)
[ PASS | FAIL ] Minimal flag checks (not scattered)
[ PASS | FAIL ] Legacy code untouched (if applicable)
[ PASS | FAIL ] No hybrid states
[ PASS | FAIL ] Default value is false

Issues: <list, or "None">
```

## Completeness Audit

For each numbered acceptance criterion, search the actual code changes to verify implementation:

```
COMPLETENESS AUDIT

Acceptance criteria: <total>
Implemented:         <count>
Missing:             <count>
Partial:             <count>

---

[PASS] #1: <criterion text>
  Evidence: FileA.kt:42, FileB.kt:88

[FAIL] #6: <criterion text>
  Not found — <reason>

[PARTIAL] #8: <criterion text>
  Missing — <what's missing>
```

**Rules:**
- Every criterion must have concrete file:line evidence or be marked FAIL
- "Partial" means some but not all aspects of the criterion are covered
- Check both positive (feature works) and negative (edge cases, error states) aspects
- If the spec mentions tests, verify test coverage exists for the criterion

## Consolidated Verdict

Merge all findings into a single report:

```
FEATURE VERIFY: <feature name>

--- ACCEPTANCE CRITERIA ---
<completeness audit>

--- FEATURE FLAG ---
<audit, or "N/A — no flag required">

--- CODE REVIEW ---
<risk register and action items>

--- VERDICT ---
<one of:>
  APPROVE — all criteria met, no blockers
  APPROVE WITH FIXES — all criteria met, but code issues need fixing [list P0/P1]
  REQUEST CHANGES — missing criteria or blockers [list what's missing/blocking]
```

After presenting the verdict, ask:
> **Would you like me to leave this as a PR comment, or fix any of the issues?**

If the user wants a PR comment:
- Format the verdict as a GitHub PR review comment using `gh pr review <number>`
- Use `--comment` for APPROVE WITH FIXES, `--approve` for APPROVE, `--request-changes` for REQUEST CHANGES
