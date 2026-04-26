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

Read [audit-rubrics.md](audit-rubrics.md) for the full feature flag audit rubric and output format.

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

Read [audit-rubrics.md](audit-rubrics.md) for the completeness audit format and rules.

Persist `completeness_audit_result` when the audit succeeds. When the verify target changes materially during the same session, loop back to `gather_diff` or `code_review` and increment the next step's `attempt_count`. Otherwise, update workflow state so `completeness_audit` is completed and `verdict` is running.

## Step 7: Consolidated Verdict

Step id: `verdict`

Primary artifact: `verdict_result`

Read [audit-rubrics.md](audit-rubrics.md) for the verdict format and PR comment instructions.

Persist `verdict_result` after the verdict is delivered. Update workflow state so `verdict` is completed and `finish` is running.

## Telemetry

This skill emits `skillbill_feature_verify_started` and `_finished` events via the `feature_verify_started` / `feature_verify_finished` MCP tools.

For the shared telemetry contract including the `orchestrated` flag semantics, follow [telemetry-contract.md](telemetry-contract.md).

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

## Nested Child Tools

When this skill runs `bill-code-review` as part of the verify workflow, this skill is itself a parent. Pass `orchestrated=true` to `import_review` and `triage_findings`, collect the returned `telemetry_payload`, and include it in the verify-owned `review_result` or final verify payload. Standalone feature-verify still emits only its own `skillbill_feature_verify_finished` event; the nested review remains parent-owned.

## Skills Reused

- `bill-code-review` — shared router for stack-specific code review
