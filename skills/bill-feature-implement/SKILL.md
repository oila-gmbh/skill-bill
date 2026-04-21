---
name: bill-feature-implement
description: Use when doing end-to-end feature implementation from design doc to verified code. Automatically scales ceremony based on feature size - lightweight for small changes, full orchestration for large features. Runs each heavy phase (pre-planning, planning, implementation, completeness audit, quality check, PR description) inside its own subagent with a rich self-contained briefing, to keep the orchestrator context small. Code review stays in the orchestrator because it already spawns specialist subagents internally. Use when user mentions implement feature, build feature, implement spec, or feature from design doc.
---

# Feature Implement

## Project Overrides

Follow the shell ceremony in [shell-ceremony.md](shell-ceremony.md).

If `.agents/skill-overrides.md` exists in the project root and contains a matching section, read that section and apply it as the highest-priority instruction for this skill.

## Execution

Follow the detailed step instructions in [content.md](content.md).

This shell remains the source of truth for the workflow-state contract, continuation contract, stable step ids, stable artifact names, and telemetry ownership. `content.md` is the author-owned execution body.

For KMP implementation work, resolve governed add-ons only after stack routing settles on `kmp`, then scan the matching pack-owned add-on supporting files whose cues match the work.

## Workflow State

This skill is the first runtime workflow-state pilot. In addition to the top-level telemetry tools, the orchestrator must persist durable workflow state with these MCP tools:

- `feature_implement_workflow_open`
- `feature_implement_workflow_update`
- `feature_implement_workflow_get`

Workflow-state rules:

- Open workflow state once, immediately after Step 1 is confirmed.
- Save both ids:
  - `session_id` from `feature_implement_started` for telemetry
  - `workflow_id` from `feature_implement_workflow_open` for durable state
- Maintain a local `child_steps` list for orchestrated child telemetry exactly as before.
- After every major phase boundary, call `feature_implement_workflow_update` with:
  - the new `workflow_status`
  - the new `current_step_id`
  - `step_updates` for the steps whose status changed
  - `artifacts_patch` for the structured artifact produced by that phase
- Workflow state is independent of telemetry settings. Persist it even when `feature_implement_started` or `feature_implement_finished` returns `status: skipped`.
- Follow the detailed per-phase briefing contracts in [reference.md](reference.md). Do not invent prose-only handoffs when a structured artifact exists.

### Stable Step Ids

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

### Stable Artifact Names

- `assessment`
- `branch`
- `preplan_digest`
- `plan`
- `implementation_summary`
- `review_result`
- `audit_report`
- `validation_result`
- `history_result`
- `commit_push_result`
- `pr_result`

### Phase-to-Artifact Mapping

- Step 1 -> `assessment`
- Step 1b -> `branch`
- Step 2 -> `preplan_digest`
- Step 3 -> `plan`
- Step 4 -> `implementation_summary`
- Step 5 -> `review_result`
- Step 6 -> `audit_report`
- Step 6b -> `validation_result`
- Step 7 -> `history_result`
- Step 8 -> `commit_push_result` is a reserved shell-owned artifact name for this pilot; keep runtime behavior unchanged unless the workflow runtime already persists it.
- Step 9 -> `pr_result`

## Continuation Mode

When an external caller re-enters this skill using the payload returned by `feature_implement_workflow_continue`, treat that payload as the authoritative continuation contract for the resumed run.

Continuation-mode rules:

- Keep the same `workflow_id` and `session_id`; do not open a new workflow.
- Use `continue_step_id` as the starting point.
- Use `step_artifacts` and `session_summary` as authoritative recovered context; do not reconstruct earlier phases from chat history unless the step explicitly requires user confirmation.
- Read the `reference_sections` listed in the continuation payload before resuming work.
- Skip already-completed earlier steps unless the normal workflow loop sends work backwards (for example review back to implement, or audit back to plan).
- After the resumed step completes, continue the normal sequence defined below.
- If `continue_status` is `done`, do not rerun the workflow; summarize the terminal state instead.
- If `continue_status` is `blocked`, stop and restore the missing artifacts named by the workflow payload before continuing.

## Orchestrator vs subagent split

| Step | Where it runs | Why |
| --- | --- | --- |
| 1 - Collect design doc + assess | **Orchestrator** | Requires user interaction |
| 1b - Create feature branch | **Orchestrator** | Trivial git op; keeps branch state visible |
| 2 - Pre-planning (history, spec save, patterns) | **Subagent** | Heavy reading; digest is small |
| 3 - Create implementation plan | **Subagent** | Heavy reading; returns structured plan |
| 4 - Execute plan | **Subagent** | Biggest context win; many file edits |
| 5 - Code review (`bill-code-review`) | **Orchestrator** | Already spawns specialist subagents internally - do not nest further |
| 6 - Completeness audit | **Subagent** | Re-reads code + criteria; returns pass/fail digest |
| 6b - Quality check (`bill-quality-check`) | **Subagent** | Shell-heavy; returns structured result + telemetry payload |
| 7 - Boundary history (`bill-boundary-history`) | **Orchestrator** | Small write, git-auditable |
| 8 - Commit and push | **Orchestrator** | Git ops must stay visible |
| 9 - PR description (`bill-pr-description`) | **Subagent** | Reads branch diff; returns PR URL + telemetry payload |

Subagents run sequentially, in the same worktree (no `isolation: "worktree"`). Do not launch any of these subagents in parallel.

Each subagent receives a self-contained briefing. See [reference.md](reference.md) for the per-phase briefing templates and structured return contracts.

## Step 1: Collect Design Doc + Assess Size (orchestrator)

Step id: `assess`

Primary artifact: `assessment`

Follow the detailed assessment flow in [content.md](content.md#step-1-collect-design-doc--assess-size-orchestrator).

After the assessment is confirmed, record the Step 1 telemetry fields, open workflow state, save both ids, and persist the `assessment` artifact before advancing to `create_branch`.

## Step 1b: Create Feature Branch (orchestrator)

Step id: `create_branch`

Primary artifact: `branch`

Follow the detailed branch-creation instructions in [content.md](content.md#step-1b-create-feature-branch-orchestrator).

After the branch is created, update workflow state to persist `branch`, mark `create_branch` completed, and advance to `preplan`.

## Step 2: Pre-Planning (subagent)

Step id: `preplan`

Primary artifact: `preplan_digest`

Follow the detailed pre-planning instructions in [content.md](content.md#step-2-pre-planning-subagent).

Spawn the pre-planning subagent with the briefing defined in [reference.md](reference.md) under `Pre-planning subagent briefing`, then persist `preplan_digest` before advancing to `plan`.

## Step 3: Create Implementation Plan (subagent)

Step id: `plan`

Primary artifact: `plan`

Follow the detailed planning instructions in [content.md](content.md#step-3-create-implementation-plan-subagent).

Spawn the planning subagent with the briefing defined in [reference.md](reference.md) under `Planning subagent briefing`, then persist `plan` before advancing to `implement`.

## Step 4: Execute Plan (subagent)

Step id: `implement`

Primary artifact: `implementation_summary`

Follow the detailed implementation instructions in [content.md](content.md#step-4-execute-plan-subagent).

Spawn the implementation subagent with the briefing defined in [reference.md](reference.md) under `Implementation subagent briefing`, then persist `implementation_summary` before advancing to `review`.

## Step 5: Code Review (orchestrator)

Step id: `review`

Primary artifact: `review_result`

Follow the detailed review-loop instructions in [content.md](content.md#step-5-code-review-orchestrator).

Run `bill-code-review` inline in the orchestrator, pass `orchestrated=true` to the review MCP tools it owns, append returned child telemetry payloads to `child_steps`, persist `review_result`, and loop back to `implement` when the review contract requires fixes.

## Step 6: Completeness Audit (subagent)

Step id: `audit`

Primary artifact: `audit_report`

Follow the detailed audit instructions in [content.md](content.md#step-6-completeness-audit-subagent).

Spawn the audit subagent with the briefing defined in [reference.md](reference.md) under `Completeness audit subagent briefing`, persist `audit_report`, and loop back to `plan` only when the audit contract requires it.

## Finalization sequence (Steps 6b -> 9)

Once the audit passes, run the finalization sequence as a continuous flow without pausing. Follow the detailed finalization instructions in [content.md](content.md#finalization-sequence-steps-6b---9).

### Step 6b: Final Validation Gate (subagent)

Step id: `validate`

Primary artifact: `validation_result`

Spawn the quality-check subagent with the briefing defined in [reference.md](reference.md) under `Quality-check subagent briefing`, require it to call `quality_check_finished` with `orchestrated=true`, append the returned child telemetry payload to `child_steps`, persist `validation_result`, and advance to `write_history`.

### Step 7: Write Boundary History (orchestrator)

Step id: `write_history`

Primary artifact: `history_result`

Run `bill-boundary-history` inline in the orchestrator, persist `history_result`, and advance to `commit_push`.

### Step 8: Commit and Push (orchestrator)

Step id: `commit_push`

Reserved artifact name: `commit_push_result`

Stage only this feature's files, commit with `feat: <concise description>`, and push with upstream tracking. Keep workflow runtime behavior unchanged unless Step 8 already persists a structured artifact.

### Step 9: Generate PR Description (subagent)

Step id: `pr_description`

Primary artifact: `pr_result`

Spawn the PR-description subagent with the briefing defined in [reference.md](reference.md) under `PR-description subagent briefing`, require it to call `pr_description_generated` with `orchestrated=true`, append the returned child telemetry payload to `child_steps`, persist `pr_result`, and advance to `finish`.

## Telemetry: Record Finished

For the shared telemetry contract including orchestrated flag semantics, child step collection, and graceful-degradation rules, follow [telemetry-contract.md](telemetry-contract.md).

After the PR is created (or when the workflow ends early due to error or user abandonment), call the `feature_implement_finished` MCP tool with:

- `session_id`: from `feature_implement_started`
- `completion_status`: `completed` if PR was created, otherwise `abandoned_at_planning`, `abandoned_at_implementation`, `abandoned_at_review`, or `error`
- `plan_correction_count`: how many times the user corrected the assessment or plan (0 if confirmed without changes)
- `plan_task_count`, `plan_phase_count`
- `feature_flag_used`, `feature_flag_pattern` (`simple_conditional`, `di_switch`, `legacy`, or `none`)
- `files_created`, `files_modified`, `tasks_completed`
- `review_iterations`, `audit_result` (`all_pass`, `had_gaps`, or `skipped`), `audit_iterations`
- `validation_result` (`pass`, `fail`, or `skipped`), `boundary_history_written`, `pr_created`
- `boundary_history_value`: how useful the boundary history was during pre-planning - `none` means no history existed at pre-read time (so `boundary_history_written=true` paired with `value=none` is a legal combination, e.g. this run created the first entry); `irrelevant` means history was read but nothing applied; `low` means an adjacent entry was grazed but did not shape the plan; `medium` means an entry directly informed pre-planning; `high` means an entry was decisive in shaping the plan. Full anchored rubric lives in `reference.md`.
- `plan_deviation_notes`: brief note if the plan changed during execution (empty if no deviations)
- `child_steps`: list of `telemetry_payload` dicts collected from child tools invoked with `orchestrated=true` during the session

For fields not yet reached (early exit), use: 0 for counts, `skipped` for results, false for booleans.

Before or immediately after `feature_implement_finished`, call `feature_implement_workflow_update` one final time to:

- mark `finish` as completed for successful runs
- set `workflow_status` to `completed`, `abandoned`, or `failed`
- keep `current_step_id` at the step where the workflow stopped
- persist any final artifact patch needed to explain the terminal state

## Reference

For detailed step instructions, briefing templates, return-contract schemas, size reference, error recovery, and skills invoked, see [reference.md](reference.md).
