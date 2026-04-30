# Feature Implement Content

This file is the author-owned execution body for `bill-feature-implement`. It carries the workflow-state contract, continuation contract, stable step ids, stable artifact names, telemetry ownership rules, and the per-step orchestration prose.

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

Stable step ids: `assess`, `create_branch`, `preplan`, `plan`, `implement`, `review`, `audit`, `validate`, `write_history`, `commit_push`, `pr_description`, `finish`. Stable artifact names: `assessment`, `branch`, `preplan_digest`, `plan`, `implementation_summary`, `review_result`, `audit_report`, `validation_result`, `history_result`, `commit_push_result`, `pr_result`.

Phase-to-artifact mapping: Step 1 -> `assessment`; Step 1b -> `branch`; Step 2 -> `preplan_digest`; Step 3 -> `plan` (implementation plan or decomposition package); Step 4 -> `implementation_summary`; Step 5 -> `review_result`; Step 6 -> `audit_report`; Step 6b -> `validation_result`; Step 7 -> `history_result`; Step 8 -> `commit_push_result` (reserved shell-owned artifact name; runtime behavior unchanged unless the workflow runtime already persists it); Step 9 -> `pr_result`.

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

## Orchestrator vs Subagent Split

Step 1 (collect design doc + assess) runs in the orchestrator because it requires user interaction. Step 1b (create feature branch) runs in the orchestrator because it is a trivial git op that should stay visible. Step 2 (pre-planning), Step 3 (planning), Step 4 (implementation), Step 6 (completeness audit), Step 6b (quality check), and Step 9 (PR description) run as subagents. Step 5 (code review via `bill-code-review`) runs in the orchestrator because it already spawns specialist subagents internally — do not nest further. Step 7 (boundary history via `bill-boundary-history`) and Step 8 (commit and push) run in the orchestrator.

Subagents run sequentially, in the same worktree (no `isolation: "worktree"`). Do not launch any of these subagents in parallel. Each subagent receives a self-contained briefing. See [reference.md](reference.md) for the per-phase briefing templates and structured return contracts.

For KMP implementation work, resolve governed add-ons only after stack routing settles on `kmp`, then scan the matching pack-owned add-on supporting files whose cues match the work.

## Step 1: Collect Design Doc + Assess Size (orchestrator)

Step id: `assess`

Primary artifact: `assessment`

Ask the user for:
1. **Feature design doc** — inline text, file path, or directory of spec files
2. **Issue key** (for example `ME-5066`, `SKILL-10`) — required. The issue key prefixes the branch name, spec directory, and commit message. If the user has no issue yet, stop and ask them to create one before continuing; do not invent a placeholder.

Accept PDFs (read in page ranges if >10 pages), markdown, images. If a directory, read all files and synthesize. If spec exceeds about 8,000 words, ask which sections matter most.

Single-pass assessment. Present everything together in one pass:
1. **Acceptance criteria** — numbered list
2. **Non-goals** — things explicitly out of scope
3. **Open questions** — unresolved decisions (if any)
4. **Feature size** — SMALL / MEDIUM / LARGE
5. **Feature name** inferred from spec
6. **Rollout need** — N/A unless spec, user, or repo requires guarded rollout

Then ask: **Confirm or adjust the above before I plan.** Open questions must be resolved before proceeding. The confirmed criteria are the contract for the completeness audit and for every subagent briefing from Step 2 onward.

After the assessment is confirmed, record the Step 1 telemetry fields, open workflow state, save both ids, and persist the `assessment` artifact before advancing to `create_branch`.

## Step 1b: Create Feature Branch (orchestrator)

Step id: `create_branch`

Primary artifact: `branch`

Create the feature branch with `git checkout -b feat/{ISSUE_KEY}-{feature-name}`.

After the branch is created, update workflow state to persist `branch`, mark `create_branch` completed, and advance to `preplan`.

## Step 2: Pre-Planning (subagent)

Step id: `preplan`

Primary artifact: `preplan_digest`

Spawn a subagent with the pre-planning briefing defined in [reference.md](reference.md) under `Pre-planning subagent briefing`. The briefing includes acceptance criteria, non-goals, issue key, feature name, spec content (or saved spec path for MEDIUM/LARGE), feature size, expected affected boundaries (if known), rollout need, and explicit instructions to:

- Read `agent/history.md` in each boundary the feature is likely to touch (newest first; stop when no longer relevant).
- Read `agent/decisions.md` header lines in each boundary and only open full entries when titles look relevant.
- For MEDIUM/LARGE, save the spec to `.feature-specs/{ISSUE_KEY}-{feature-name}/spec.md` with status `In Progress`.
- Read `CLAUDE.md`, `AGENTS.md`, and the matching `bill-feature-implement` section in `.agents/skill-overrides.md` when present.
- Discover codebase patterns: similar features referenced in the spec, build/runtime dependencies for affected boundaries, reusable components.
- When `kmp` signals dominate, resolve governed add-ons only after stack routing settles on `kmp`. Start from `Selected add-ons: none`. Let the routed pack own add-on detection and selection, then scan the matching pack-owned add-on supporting files' `## Section index` headings first. If the add-on is split into topic files, open only the linked topic files whose cues match the work during pre-planning and pattern discovery.
- Confirm `bill-quality-check` can route this repo; if not, pick a repo-native validation command.
- If the rollout uses a feature flag, read `bill-feature-guard` inline and choose a pattern (Legacy / DI Switch / Simple Conditional).

The subagent returns the pre-planning return contract from [reference.md](reference.md). The orchestrator keeps this digest in context and passes it to later subagents — the raw findings stay in the subagent. Persist `preplan_digest` before advancing to `plan`.

## Step 3: Create Implementation Plan (subagent)

Step id: `plan`

Primary artifact: `plan`

Spawn a subagent with the planning briefing defined in [reference.md](reference.md) under `Planning subagent briefing`. The briefing includes acceptance criteria, non-goals, feature size, pre-planning digest (from Step 2), rollout info, feature-flag pattern (if any), and validation strategy.

The subagent returns one of two planning return contracts:

- `mode: "implement"` — an ordered task list, each task with description, files to create or modify, which acceptance criteria it satisfies, and test coverage (or `None` when deferred to the final test task). MEDIUM plans may use phases with checkpoints when helpful.
- `mode: "decompose"` — a terminal decomposition package for work that is too large for one reliable feature-implement run.

Decomposition is mandatory when a plan would exceed 15 atomic implementation tasks, touch more than 6 boundaries, contain multiple independently resumable milestones, or require sequencing where later work depends on foundation that should be verified separately. In decomposition mode, the planning subagent writes subtask specs under `.feature-specs/{ISSUE_KEY}-{feature-name}/` using names like `spec_subtask_1_foundation.md`, `spec_subtask_2_runtime-wiring.md`, and `spec_subtask_3_validation.md`. Each subtask spec must contain its own acceptance criteria, non-goals, dependency notes, validation strategy, and a clear instruction to run `bill-feature-implement` on that subtask spec in a later session.

If an implementation plan includes testable logic, the final task must be a dedicated test task. The subagent is responsible for enforcing this rule when it returns `mode: "implement"`.

The orchestrator presents the plan, then proceeds to implementation — the plan is not a second approval gate.

If the planning subagent returns `mode: "decompose"`, the orchestrator must not proceed to implementation. Persist `plan`, present the decomposition summary with wording equivalent to: "I split this into N subtasks. Here are the acceptance criteria for each subtask. We should work on the first subtask first because of the dependency reason." Then close the workflow as an intentional planning-stage stop: mark `plan` completed, mark later steps skipped, set workflow status to `abandoned`, keep `current_step_id: "plan"`, call `feature_implement_finished` with `completion_status: "abandoned_at_planning"`, and record `plan_deviation_notes` as `decomposed into N subtasks`. This is a successful scope-governance outcome, not an implementation failure.

Persist `plan` before advancing to `implement` or before closing on decomposition.

## Step 4: Execute Plan (subagent)

Step id: `implement`

Primary artifact: `implementation_summary`

Spawn a subagent with the implementation briefing defined in [reference.md](reference.md) under `Implementation subagent briefing`. The briefing includes acceptance criteria, plan (from Step 3), pre-planning digest (from Step 2), rollout info, feature-flag pattern (if any), spec path (for MEDIUM/LARGE), and execution rules (project standards, test gate, orphan cleanup, catalog updates for agent-config changes).

The subagent executes the plan atomically (one task per turn), prints per-task progress, writes tests as specified, and stops to re-plan if a task reveals the plan is wrong. On stop-and-re-plan, the subagent returns with `plan_deviation_notes` populated so the orchestrator can decide whether to re-spawn the planning subagent.

The subagent returns the implementation return contract: `files_created`, `files_modified`, `tasks_completed`, `plan_deviation_notes`, `tests_written`, `notes_for_review`.

For MEDIUM/LARGE, the subagent performs the post-implementation compact internally before returning: summarize files, feature flag info, criteria-to-file mapping, deviations; then re-read the saved spec to verify every criterion is mapped.

Persist `implementation_summary` before advancing to `review`.

## Step 5: Code Review (orchestrator)

Step id: `review`

Primary artifact: `review_result`

Run `bill-code-review` inline in the orchestrator. Read its skill file and apply it inline. Scope: current unit of work for SMALL, branch diff for MEDIUM/LARGE. Do not wrap `bill-code-review` in an additional subagent — it already spawns specialist subagents internally.

Review loop:

- Auto-fix Blocker and Major findings by spawning the implementation subagent again with a fix briefing (acceptance criteria + list of findings + pointer to the current diff + instruction to fix only those findings).
- Before respawning, capture the exact diff pointer the review was run against — the branch name, commit range (for example `main..HEAD`), or explicit file list — and pass it as `{branch_or_commit_range}` in the fix briefing so the subagent knows which diff the findings refer to.
- Re-run review.
- Continue past Minor-only findings.
- Max 3 iterations.
- Do not pause to ask the user which finding to fix.

Orchestrated child telemetry:

- When this workflow invokes `import_review` and `triage_findings` for the review it owns, pass `orchestrated=true` to both tools.
- Collect the `telemetry_payload` returned by `triage_findings` (or by `import_review` when the review has no findings) and append it to the local `child_steps` list.
- The review will not emit `skillbill_review_finished` on its own — its payload will be embedded in the `skillbill_feature_implement_finished` event instead.

Persist `review_result`, then advance to `audit`.

## Step 6: Completeness Audit (subagent)

Step id: `audit`

Primary artifact: `audit_report`

Spawn a subagent with the audit briefing defined in [reference.md](reference.md) under `Completeness audit subagent briefing`. The briefing includes acceptance criteria, implementation return contract (from Step 4), and — for MEDIUM/LARGE — a pointer to the branch diff.

SMALL: the subagent returns a quick confirmation for each criterion. MEDIUM/LARGE: the subagent returns a full per-criterion report with evidence paths.

The subagent returns the audit return contract: `pass: bool`, `per_criterion: [...]`, `gaps: [...]`.

If gaps are found: the orchestrator respawns the planning subagent with the gaps, then the implementation subagent, then re-runs code review, then re-spawns the audit subagent. Max 2 audit iterations. When complete, the orchestrator updates the saved spec status to `Complete` (MEDIUM/LARGE only).

Persist `audit_report`, then advance to `validate`. Loop back to `plan` only when the audit contract requires it.

## Finalization Sequence (Steps 6b through 9)

Once the audit passes, run Steps 6b through 9 as a continuous sequence without pausing. The only reason to stop is if a step fails.

## Step 6b: Final Validation Gate (subagent)

Step id: `validate`

Primary artifact: `validation_result`

Spawn a subagent with the quality-check briefing defined in [reference.md](reference.md) under `Quality-check subagent briefing`. The subagent runs `bill-quality-check` (which auto-routes to the matching stack quality-check skill), fixes any issues at their root without using suppressions, and must call `quality_check_finished` with `orchestrated=true` itself. The subagent returns: `validation_result`, `routed_skill`, `detected_stack`, `initial_failure_count`, `final_failure_count`, and the `telemetry_payload` returned by `quality_check_finished`.

If `bill-quality-check` reports no supported stack for the affected repo, the subagent falls back to the closest existing repo-native validation command.

The orchestrator appends the returned `telemetry_payload` to the `child_steps` list. Persist `validation_result`, then advance to `write_history`.

## Step 7: Write Boundary History (orchestrator)

Step id: `write_history`

Primary artifact: `history_result`

Run `bill-boundary-history` inline in the orchestrator. Read its skill file and apply it inline. The skill owns write/skip rules and entry format. Persist `history_result`, then advance to `commit_push`.

## Step 8: Commit and Push (orchestrator)

Step id: `commit_push`

Reserved artifact name: `commit_push_result`

1. Stage all new and modified files from this feature (do not use `git add -A`).
2. Commit with message format `feat: <concise description>` (omit the issue key — the branch name already carries it).
3. Push the branch to the remote with `-u` to set upstream tracking.

Keep workflow runtime behavior unchanged unless Step 8 already persists a structured artifact.

## Step 9: Generate PR Description (subagent)

Step id: `pr_description`

Primary artifact: `pr_result`

Spawn a subagent with the PR-description briefing defined in [reference.md](reference.md) under `PR-description subagent briefing`. The subagent runs `bill-pr-description` (read its skill file and apply inline), creates the PR, and must call `pr_description_generated` with `orchestrated=true` itself. The subagent returns: PR URL, PR title, and the `telemetry_payload` returned by `pr_description_generated`.

The orchestrator appends the returned `telemetry_payload` to the `child_steps` list. Persist `pr_result`, then advance to `finish`.

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
- `boundary_history_value`: how useful the boundary history was during pre-planning — `none` means no history existed at pre-read time (so `boundary_history_written=true` paired with `value=none` is a legal combination, e.g. this run created the first entry); `irrelevant` means history was read but nothing applied; `low` means an adjacent entry was grazed but did not shape the plan; `medium` means an entry directly informed pre-planning; `high` means an entry was decisive in shaping the plan. Full anchored rubric lives in `reference.md`.
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
