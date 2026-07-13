---
internal-for: bill-feature
name: bill-feature-task-prose
description: First-class prose orchestrator for end-to-end feature implementation, running entirely within the invoking agent session. Handles the full phase loop (assess, branch, preplan, plan, implement, review, audit, validate, history, commit, PR) without delegating to an external runtime. Use when user mentions implement feature, build feature, or feature from design doc, and prose in-session orchestration is preferred over the runtime-backed mode.
---

# Feature Task Prose Content

This file is the author-owned execution body for `bill-feature-task-prose`. It carries the workflow-state contract, continuation contract, stable step ids, stable artifact names, telemetry ownership rules, and the per-step orchestration prose.

## Workflow State

This skill is the first-class prose implementation mode for `bill-feature-task`.
It persists durable workflow rows as `bill-feature-task` with `mode=prose` in
the shared feature-task workflow store. It runs in parallel with
`bill-feature-task-runtime`; it is not a legacy fallback. The
`feature_task_prose_*` MCP tool names are compatibility aliases for
`bill-feature-task mode=prose` and must not be described as a separate
authoritative workflow store.

In addition to the top-level telemetry tools, the orchestrator must persist durable workflow state with these MCP tools:

- `feature_task_prose_workflow_open`
- `feature_task_prose_workflow_update`
- `feature_task_prose_workflow_get`

Workflow-state rules:

- Open workflow state once, immediately after Step 1 records the router-confirmed assessment.
- Save both ids:
  - `session_id` from `feature_task_prose_started` for telemetry
  - `workflow_id` from `feature_task_prose_workflow_open` for durable state
- Maintain a local `child_steps` list for orchestrated child telemetry exactly as before.
- After every major phase boundary, call `feature_task_prose_workflow_update` with:
  - the new `workflow_status`
  - the new `current_step_id`
  - `step_updates` for the steps whose status changed
  - `artifacts_patch` for the structured artifact produced by that phase
- Workflow state is independent of telemetry settings. Persist it even when `feature_task_prose_started` or `feature_task_prose_finished` returns `status: skipped`.
- `feature_task_prose_workflow_update` returns a compact acknowledgement by default: status, workflow id/status, current step id, updated step ids, updated artifact keys, db path, and read-only full-state guidance. It does not return the full durable artifact map; use `feature_task_prose_workflow_get` or `workflow show` for explicit read-only full-state inspection.
- Follow the detailed per-phase briefing contracts in the inline reference sections below. Do not invent prose-only handoffs when a structured artifact exists.

Stable step ids: `assess`, `create_branch`, `preplan`, `plan`, `implement`, `review`, `audit`, `validate`, `write_history`, `commit_push`, `pr_description`, `finish`. Stable artifact names: `assessment`, `branch`, `preplan_digest`, `plan`, `implementation_summary`, `review_result`, `audit_report`, `validation_result`, `history_result`, `commit_push_result`, `pr_result`.

Phase-to-artifact mapping: Step 1 -> `assessment`; Step 1b -> `branch`; Step 2 -> `preplan_digest`; Step 3 -> `plan` (implementation plan or decomposition package); Step 4 -> `implementation_summary`; Step 5 -> `review_result`; Step 6 -> `audit_report`; Step 6b -> `validation_result`; Step 7 -> `history_result`; Step 8 -> `commit_push_result`; Step 9 -> `pr_result`.

## Continuation Mode

When an external caller re-enters this skill using the payload returned by `feature_task_prose_workflow_continue`, treat that payload as the authoritative continuation contract for the resumed run.

Continuation-mode rules:

- Keep the same `workflow_id` and `session_id`; do not open a new workflow.
- Use `resume_step_id` / `continue_step_id` as the starting point.
- Use `current_step_artifacts` as authoritative recovered context; inline values are complete, while summarized values carry explicit size, preview, truncation, and omission metadata. Do not reconstruct earlier phases from chat history unless the step explicitly requires user confirmation.
- Read the `reference_sections` listed in the continuation payload before resuming work.
- Skip already-completed earlier steps unless the normal workflow loop sends work backwards (for example review back to implement, or audit back to plan).
- After the resumed step completes, continue the normal sequence defined below.
- If `continue_status` is `done`, do not rerun the workflow; summarize the terminal state instead.
- If `continue_status` is `blocked`, stop and restore the missing artifacts named by the workflow payload before continuing.
- `workflow continue` is a mutating activation/reopen command (it re-opens resumable state), not a read-only inspection command. Its compact continuation output is the default child-session context. Use `workflow show <workflow-id> --format json` for read-only full-state inspection, including the complete durable `artifacts` map, and fetch full state only when explicitly needed.

## Retry, Resume, and Review Reuse

## Code-review selection

Obtain the normalized `code-review:auto|inline|delegated` selection from the
entry authority. For an interactive single-task run, `bill-feature-task`
resolves omission to `auto` and rejects malformed, unknown, repeated, and
conflicting tokens before its sole confirmation gate. For a non-interactive
goal continuation, durable goal and child workflow state supply the immutable
selection. This sidecar must not reparse the token, present another gate, or
substitute a different mode. Persist the selected mode with the workflow and
reuse it for every review-fix or audit-driven re-review. In Step 5 invoke
`bill-code-review execution-mode:<selected-mode>`; when a parallel lane is
configured, pass the same execution mode to both lanes without allowing
recursive parallel launch.

On retry or resume, durable workflow state is the single source of authority. Avoid re-injecting prior plans, reviews, implementation summaries, or unrelated decomposition artifacts into the resumed run:

- Treat `current_step_artifacts` as the bounded recovered context. Pull only the artifacts the resumed step needs; do not re-paste prior plans, full prior review reports, prior implementation RESULT summaries, or sibling-subtask decomposition artifacts into history.
- When an artifact is summarized or omitted with size/truncation metadata, work from the summary and fetch the specific omitted slice read-only only if the resumed step genuinely needs it. Prefer scoped reads over full re-injection.
- Resume context is bounded: do not reconstruct earlier phases from chat history unless the step explicitly requires user confirmation.

Review and fix-loop reuse (AC5):

- Inside the same subtask fix loop, a prior clean specialist review result may be reused without rerunning that specialist when the relevant changed files and risk areas have not changed since that result was produced.
- Rerun a specialist when the fix touched files or risk areas that specialist owns, or when its prior risk assessment is no longer valid for the current diff.
- Never remove, downgrade, or make optional the full per-subtask review and validation, and never suppress a required review specialist when relevant risk changed. Reuse is an optimization within an unchanged risk surface, not a way to skip required review.

## Goal-Continuation Entry (non-interactive)

External goal runners may invoke `feature_task_prose_workflow_continue` / `skill-bill workflow continue` with a decomposed parent `issue_key` and, optionally, `subtask_id`. This is a non-interactive entry for exactly one runnable decomposed subtask.

Goal-continuation rules:

- Reuse the existing decomposition continuation selector: resume the in-progress subtask, otherwise start the first pending subtask whose dependencies are complete, otherwise report blocked or all-complete.
- If `subtask_id` is provided, treat it as a constraint on the next runnable subtask. Do not skip dependencies or run a later subtask; report blocked when the requested subtask is not the selected runnable subtask.
- Never run installer or uninstall flows during goal-continuation: do not call `./install.sh`, `./uninstall.sh`, `skill-bill install`, `skill-bill install apply`, or any equivalent install-sync command. This prohibition overrides repo instructions that normally ask maintainers to refresh local installs after changing governed skill source, because installer sync can reset local workflow state while the goal runner still needs it. Record skipped install-sync work in the phase result or review notes instead.
- Derive the subtask contract from the selected subtask spec and recovered workflow artifacts. Do not ask the user to reconfirm acceptance criteria for the subtask.
- Start new subtask workflows at `preplan` with `assessment`, `branch`, and `goal_continuation` artifacts already persisted from the parent manifest.
- Set `goal_continuation.suppress_pr=true`. Run the normal implementation, review, audit, validation, history, and commit steps, then suppress `pr_description` so the parent goal runner can open one PR for the whole goal.
- Treat a completed `commit_push` step as the terminal success signal for this entry when PR suppression is active. Persist the subtask outcome in durable workflow state; stdout is diagnostic only.
- The structured outcome fields are `issue_key`, `subtask_id`, `status`, `commit_sha`, `workflow_id`, `blocked_reason`, and `last_resumable_step`. Runtime state is authoritative; git-tracked `decomposition-manifest.yaml` projections may omit runtime-only commit SHAs.
- To explain why a subtask retried, stopped, or blocked, read the append-only attempt ledger (`goal_attempt_ledger`) on the child workflow via read-only `workflow show`; its `action`, `blocked_reason`, `stop_reason`, and `final_reconciled_result` fields are sufficient without scraping any provider session log. Caveat (not a Skill Bill contract): provider-reported total token counts can be dominated by cached input replay, so treat them as a diagnostic signal — Skill Bill optimizes payload size and session behavior, not provider cache accounting. See the workflow-contract playbook (installed as a support pointer beside this skill) for detail.
- Interactive `bill-feature-task` behavior is unchanged: the router performs the one confirmation gate, and this sidecar creates a PR in Step 9.
- The `bill-feature-goal` `mode:prose` orchestrator is the loop driver that enters this worker contract per subtask via `skill-bill workflow continue <issue_key> --subtask-id <id>` / `feature_task_prose_workflow_continue`.

## Shared Feature-Spec Preparation Path

When planning returns `mode: "decompose"`, `bill-feature-task` must use
the shared feature-spec preparation path owned by `bill-feature-spec` and
reused by `bill-feature-goal`.

Do not bypass this shared path with one-off decomposition writing logic in this
skill. The shared path owns parent/spec-subtask writing and decomposition
manifest validation.

## Spec Source Mode (local vs linear)

The feature's spec source is an artifact stamp, never a config lookup. Read it
from the artifact only and default to `local` when absent:

- decomposed: `decomposition-manifest.yaml` field `spec_source`;
- single_spec: the `spec_source:` line in `spec.md`;
- absent or unreadable: `local`.

For `spec_source: local` (the default) everything below is skipped — specs are
staged and committed exactly as before, nothing is deleted, and no Linear MCP
call is made.

For `spec_source: linear`:

- **Rehydrate before any read.** Before reading the local spec (resume,
  goal-continuation, or any step that needs the spec) check whether the file at
  the needed `spec_path` exists. If it is missing (it was deleted on a prior
  terminal success), rehydrate first: fetch the parent issue by `issue_key` and
  each needed subtask by its `linear_issue_id` via the Linear MCP, rewrite the
  local spec files, then read the working tree as usual. A still-present spec is
  read directly with no MCP call.
- **Commit exclusion.** The local spec scratch is never committed (see Step 8).
- **Delete on terminal success.** The local spec scratch is deleted only on
  terminal success (see the terminal cleanup note after Step 9). An aborted,
  blocked, or otherwise non-terminal-success run leaves the scratch intact and
  resumable.

## Orchestrator vs Subagent Split

Step 1 (read the router-confirmed spec and assess) runs in the orchestrator so it can persist the established task contract. Step 1b (create feature branch) runs in the orchestrator because it is a trivial git op that should stay visible. Step 2 (pre-planning), Step 3 (planning), Step 4 (implementation), Step 6 (completeness audit), Step 6b (quality check), and Step 9 (PR description) run as subagents. Step 5 (code review via `bill-code-review`) runs in the orchestrator because it already spawns specialist subagents internally — do not nest further. Step 7 (boundary history via `bill-boundary-history`) and Step 8 (commit and push) run in the orchestrator.

Subagents run sequentially, in the same worktree (no `isolation: "worktree"`). Do not launch any of these subagents in parallel. Each subagent receives a self-contained briefing. See the inline reference sections below for the per-phase briefing templates and structured return contracts.

For KMP implementation work, resolve governed add-ons only after stack routing settles on `kmp`, then scan the matching pack-owned add-on supporting files whose cues match the work.

## Step 1: Read Confirmed Spec + Assess Size (orchestrator)

Step id: `assess`

Primary artifact: `assessment`

Read the issue key and governed spec path from the router-confirmed task context.
Do not ask the user for a design document, issue key, or another confirmation.
If the established context is missing, stop rather than inventing a replacement.

Derive and persist one assessment from the governed spec:
1. **Acceptance criteria** — numbered list
2. **Non-goals** — things explicitly out of scope
3. **Open questions** — unresolved decisions (if any)
4. **Feature size** — SMALL / MEDIUM / LARGE
5. **Feature name** inferred from spec
6. **Rollout need** — N/A unless spec, user, or repo requires guarded rollout

The router's confirmation is the only gate. Open questions must be resolved from
the governed spec before proceeding. The established criteria are the contract
for the completeness audit and every subagent briefing from Step 2 onward.

After recording the assessment, record the Step 1 telemetry fields, open workflow
state, save both ids, and persist the `assessment` artifact before advancing to
`create_branch`.

## Step 1b: Create Feature Branch (orchestrator)

Step id: `create_branch`

Primary artifact: `branch`

Create the feature branch with `git checkout -b feat/{ISSUE_KEY}-{feature-name}`.

After the branch is created, update workflow state to persist `branch`, mark `create_branch` completed, and advance to `preplan`.

## Step 2: Pre-Planning (subagent)

Step id: `preplan`

Primary artifact: `preplan_digest`

Spawn a subagent with the pre-planning briefing defined in the inline reference sections below under `Pre-planning subagent briefing`. The briefing includes acceptance criteria, non-goals, issue key, feature name, spec content (or saved spec path for MEDIUM/LARGE), feature size, expected affected boundaries (if known), rollout need, and explicit instructions to:

- Read `agent/history.md` in each boundary the feature is likely to touch (newest first; stop when no longer relevant).
- Read `agent/decisions.md` header lines in each boundary and only open full entries when titles look relevant.
- For MEDIUM/LARGE, save the spec to `.feature-specs/{ISSUE_KEY}-{feature-name}/spec.md` with status `In Progress`.
- Read `CLAUDE.md`, `AGENTS.md`, and the matching `bill-feature-task` section in `.agents/skill-overrides.md` when present.
- Discover codebase patterns: similar features referenced in the spec, build/runtime dependencies for affected boundaries, reusable components.
- When `kmp` signals dominate, resolve governed add-ons only after stack routing settles on `kmp`. Start from `Selected add-ons: none`. Let the routed pack own add-on detection and selection, then scan the matching pack-owned add-on supporting files' `## Section index` headings first. If the add-on is split into topic files, open only the linked topic files whose cues match the work during pre-planning and pattern discovery.
- Confirm `bill-code-check` can route this repo; if not, pick a repo-native validation command.
- If the rollout uses a feature flag, invoke `bill-feature-guard` via the Skill tool (do not search the filesystem to locate skill files) and choose a pattern (Legacy / DI Switch / Simple Conditional).

The subagent returns the pre-planning return contract from the inline reference sections below. The orchestrator keeps this digest in context and passes it to later subagents — the raw findings stay in the subagent. Persist `preplan_digest` before advancing to `plan`.

## Step 3: Create Implementation Plan (subagent)

Step id: `plan`

Primary artifact: `plan`

Spawn a subagent with the planning briefing defined in the inline reference sections below under `Planning subagent briefing`. The briefing includes acceptance criteria, non-goals, feature size, pre-planning digest (from Step 2), rollout info, feature-flag pattern (if any), and validation strategy.

The subagent returns one of two planning return contracts:

- `mode: "implement"` — an ordered task list, each task with description, files to create or modify, which acceptance criteria it satisfies, and test coverage (or `None` when deferred to the final test task). MEDIUM plans may use phases with checkpoints when helpful.
- `mode: "decompose"` — a terminal decomposition package for work that is too large for one reliable feature-task run.

Decomposition is mandatory when a plan would exceed 15 atomic implementation tasks, touch more than 6 boundaries, contain multiple independently resumable milestones, or require sequencing where later work depends on foundation that should be verified separately. In decomposition mode, the planning subagent returns a decomposition package only. The orchestrator must then invoke the shared feature-spec preparation path (the same path used by `bill-feature-spec` and `bill-feature-goal`) to write or update `spec.md`, ordered `spec_subtask_*.md` files, and `.feature-specs/{ISSUE_KEY}-{feature-name}/decomposition-manifest.yaml`; the runtime validates the manifest against the decomposition manifest schema contract; ordinary `mode: "implement"` and single-spec workflows do not read or require this manifest.

If an implementation plan includes testable logic, the final task must be a dedicated test task. The subagent is responsible for enforcing this rule when it returns `mode: "implement"`.

The orchestrator presents the plan, then proceeds to implementation — the plan is not a second approval gate.

If the planning subagent returns `mode: "decompose"`, the orchestrator must not proceed to implementation. Persist `plan`, invoke the shared feature-spec preparation path to write/update decomposition artifacts, present the decomposition summary with wording equivalent to: "I split this into N subtasks. Here are the acceptance criteria for each subtask. We should work on the first subtask first because of the dependency reason." Then close the workflow as an intentional planning-stage stop: mark `plan` completed, mark later steps skipped, set workflow status to `abandoned`, keep `current_step_id: "plan"`, call `feature_task_prose_finished` with `completion_status: "abandoned_at_planning"`, and record `plan_deviation_notes` as `decomposed into N subtasks`. This is a successful scope-governance outcome, not an implementation failure.

Persist `plan` before advancing to `implement` or before closing on decomposition.

## Step 4: Execute Plan (subagent)

Step id: `implement`

Primary artifact: `implementation_summary`

Spawn a subagent with the implementation briefing defined in the inline reference sections below under `Implementation subagent briefing`. The briefing includes acceptance criteria, plan (from Step 3), pre-planning digest (from Step 2), rollout info, feature-flag pattern (if any), spec path (for MEDIUM/LARGE), and execution rules (project standards, test gate, orphan cleanup, catalog updates for agent-config changes).

The subagent executes the plan atomically (one task per turn), prints per-task progress, writes tests as specified, and stops to re-plan if a task reveals the plan is wrong. On stop-and-re-plan, the subagent returns with `plan_deviation_notes` populated so the orchestrator can decide whether to re-spawn the planning subagent.

The subagent returns the implementation return contract: `files_created`, `files_modified`, `tasks_completed`, `plan_deviation_notes`, `tests_written`, `notes_for_review`.

For MEDIUM/LARGE, the subagent performs the post-implementation compact internally before returning: summarize files, feature flag info, criteria-to-file mapping, deviations; then re-read the saved spec to verify every criterion is mapped.

Persist `implementation_summary` before advancing to `review`.

## Step 5: Code Review (orchestrator)

Step id: `review`

Primary artifact: `review_result`

Run `bill-code-review execution-mode:<selected-mode>` inline in the orchestrator through the active skill runtime. Scope: current unit of work for SMALL, branch diff for MEDIUM/LARGE. Do not wrap `bill-code-review` in an additional subagent — it already spawns specialist subagents internally. When `parallel-review:<agent>` was passed to this skill, invoke `bill-code-review execution-mode:<selected-mode> parallel:<agent>` so a second review lane runs alongside the primary review without recursive parallel launch.

Review loop:

- Auto-fix Blocker and Major findings by spawning the implementation subagent again with a fix briefing (acceptance criteria + list of findings + pointer to the current diff + instruction to fix only those findings).
- Before respawning, capture the exact diff pointer the review was run against — the branch name, commit range (for example `main..HEAD`), or explicit file list — and pass it as `{branch_or_commit_range}` in the fix briefing so the subagent knows which diff the findings refer to.
- Re-run review.
- Continue past Minor-only findings.
- Max 3 iterations.
- Do not pause to ask the user which finding to fix.

For a decomposed prose-goal child, the parent supplies an immutable
`review_base_sha`, baseline untracked inventory, and durable pass count. Review
only that child's exact base-to-current delta, including committed, staged,
unstaged, and owned untracked changes. Do not use `origin/main...HEAD` or a
branch-wide substitute. Reserve a pass before review, reuse the selected mode
on every re-review, and never start more than two total passes. After a second
pass with unresolved Blocker/Major findings, persist the full review result,
record `review_cap_reached`, emit the compact path-free goal summary, and
continue to audit without reporting approval.

Orchestrated child telemetry:

- When this workflow invokes `import_review` and `triage_findings` for the review it owns, pass `orchestrated=true` to both tools.
- Import only complete `bill-code-review` output. The review text passed to `import_review` must include the metadata header lines, especially `Review run ID: <review-run-id>`. If the final review summary lacks those lines, re-run or reformat the review output before importing instead of synthesizing a prose-only review.
- Collect the `telemetry_payload` returned by `triage_findings` (or by `import_review` when the review has no findings) and append it to the local `child_steps` list.
- The review will not emit `skillbill_review_finished` on its own — its payload will be embedded in the `skillbill_feature_task_prose_finished` event instead.
- Before the first review telemetry call, do a lightweight Skill Bill MCP health check such as `feature_task_prose_workflow_latest`. If the MCP tool path returns `Transport closed`, call the same Skill Bill MCP tool through the packaged Kotlin `runtime-mcp` stdio binary from the repo (`runtime-kotlin/runtime-mcp/build/install/runtime-mcp/bin/runtime-mcp`) with a JSON-RPC `tools/call` payload and parse the returned text content. Use this direct-stdio fallback for subsequent owned telemetry/workflow calls in the run, and record that fallback in `review_result`.

Persist `review_result`, then advance to `audit`.

## Step 6: Completeness Audit (subagent)

Step id: `audit`

Primary artifact: `audit_report`

Spawn a subagent with the audit briefing defined in the inline reference sections below under `Completeness audit subagent briefing`. The briefing includes acceptance criteria, implementation return contract (from Step 4), and — for MEDIUM/LARGE — a pointer to the branch diff.

SMALL: the subagent returns a quick confirmation for each criterion. MEDIUM/LARGE: the subagent returns a full per-criterion report with evidence paths.

The subagent returns the audit return contract: `pass: bool`, `per_criterion: [...]`, `gaps: [...]`.

If gaps are found: the orchestrator respawns the planning subagent with the gaps, then the implementation subagent, then re-runs code review, then re-spawns the audit subagent. Max 2 audit iterations. When complete, if a tracked `.feature-specs/{ISSUE_KEY}-{feature-name}/spec.md` exists, the orchestrator reconciles it to its final state for ALL sizes (not only MEDIUM/LARGE): set `Status: Complete`, resolve any Open Questions with the decisions taken, and correct anything the implementation changed (for example a corrected flag or argument name). SMALL runs do not create a spec on disk, but when one already exists it must still be reconciled here.

When reconciling that `## Status` block, also write an `Agent:` line recording the resolved invoking agent next to `Status: Complete` — for example `- Agent: claude`. Resolve the agent through the existing governed order, never a re-invented source: `--agent` argument, then the `SKILL_BILL_AGENT` environment variable, then the detected invoking-agent execution context, then the documented last-resort default (`codex`). This line is a completion-reconciliation outcome, never an authored input: write it only here, only when the spec exists on disk (a SMALL run with no spec writes nothing), and keep it idempotent — if an `Agent:` line is already present under `## Status`, update it in place rather than adding a second one. The line lives only under `## Status` and must not perturb the `## Acceptance Criteria` section.

Persist `audit_report`, then advance to `validate`. Loop back to `plan` only when the audit contract requires it.

## Finalization Sequence (Steps 6b through 9)

Once the audit passes, run Steps 6b through 9 as a continuous sequence without pausing. The only reason to stop is if a step fails.

## Step 6b: Final Validation Gate (subagent)

Step id: `validate`

Primary artifact: `validation_result`

Spawn a subagent with the quality-check briefing defined in the inline reference sections below under `Quality-check subagent briefing`. The subagent runs `bill-code-check` (which auto-routes to the matching stack quality-check skill), fixes any issues at their root without using suppressions, and must call `quality_check_finished` with `orchestrated=true` itself. Validation findings are repair work, not a blocking gate: keep fixing and rerunning validation until the result passes, and do not persist `validate` as blocked for fixable findings. The subagent returns: `validation_result`, `routed_skill`, `detected_stack`, `fallback`, `initial_failure_count`, `final_failure_count`, and the `telemetry_payload` returned by `quality_check_finished`.

If `bill-code-check` reports no supported stack for the affected repo, the subagent falls back to the closest existing repo-native validation command.

The orchestrator appends the returned `telemetry_payload` to the `child_steps` list. Persist `validation_result`, then advance to `write_history`.

## Step 7: Write Boundary History (orchestrator)

Step id: `write_history`

Primary artifact: `history_result`

Run `bill-boundary-history` inline in the orchestrator. Read its skill file and apply it inline. The skill owns write/skip rules and entry format. Persist `history_result`, then advance to `commit_push`.

## Step 8: Commit and Push (orchestrator)

Step id: `commit_push`

Reserved artifact name: `commit_push_result`

1. If this run is a goal-continuation subtask with `goal_continuation.suppress_pr=true`, persist a pre-commit projection before staging: update workflow state with `current_step_id=commit_push`, a running `commit_push` step update, and `artifacts_patch.commit_push_result.pre_commit_projection=true`. In `spec_source: local` this writes the git-tracked decomposition manifest and subtask status files as complete before the commit, while leaving the runtime-only commit SHA unset. In `spec_source: linear` the manifest and spec files are excluded from the commit (next item), so skip writing a git-tracked manifest/spec projection delta here — keep the projection in durable runtime state only, not on the working tree.
2. Stage the new and modified files from this feature. In `spec_source: local`, include any updated `decomposition-manifest.yaml` and subtask spec status files. In `spec_source: linear`, stage by explicit enumerated path and exclude the entire `.feature-specs/{ISSUE_KEY}-{feature-name}/` directory (parent spec, every subtask spec, and `decomposition-manifest.yaml`) so the committed tree contains nothing spec-related. In both modes, do not use `git add -A` or `git add .`.
3. Commit with message format `feat: <concise description>` (omit the issue key — the branch name already carries it).
4. Persist the terminal Step 8 artifact after the commit: update workflow state with a completed `commit_push` step and `artifacts_patch.commit_push_result.commit_sha=<HEAD sha>`. The commit SHA is runtime-only and must not create another git-tracked manifest delta.
5. Push the branch to the remote with `-u` to set upstream tracking.

## Step 9: Generate PR Description (subagent)

Step id: `pr_description`

Primary artifact: `pr_result`

Spawn a subagent with the PR-description briefing defined in the inline reference sections below under `PR-description subagent briefing`. The subagent invokes `bill-pr-description` via the Skill tool (do not search the filesystem to locate skill files), creates the PR, and must call `pr_description_generated` with `orchestrated=true` itself. The subagent returns: PR URL, PR title, and the `telemetry_payload` returned by `pr_description_generated`.

The orchestrator appends the returned `telemetry_payload` to the `child_steps` list. Persist `pr_result`, then advance to `finish`.

## Step 9b: Terminal-Success Spec Cleanup (orchestrator, linear mode only)

For `spec_source: local` this step is a no-op — nothing is deleted.

For `spec_source: linear`, after the run terminally succeeds, delete the local spec scratch (it was never committed and is rehydrated from Linear on demand):

- single_spec: after the PR is created, delete the `.feature-specs/{ISSUE_KEY}-{feature-name}/` directory.
- goal-continuation (decomposed) subtask: after this subtask's `commit_push` is durable, delete that subtask's spec file; the parent spec + `decomposition-manifest.yaml` are deleted only after the final subtask completes (the manifest is live runtime state until then). The goal runner owns parent + manifest deletion.

Delete only on terminal success. If the run aborted, blocked, or stopped before its terminal success signal, leave the entire scratch intact so it stays resumable.

## Telemetry: Record Finished

After the PR is created (or when the workflow ends early due to error or user abandonment), call the `feature_task_prose_finished` MCP tool with:

- `session_id`: from `feature_task_prose_started`
- `completion_status`: `completed` if PR was created, otherwise `abandoned_at_planning`, `abandoned_at_implementation`, `abandoned_at_review`, or `error`
- `plan_correction_count`: how many times the plan changed after the router-confirmed assessment (0 when no correction was needed)
- `plan_task_count`, `plan_phase_count`
- `feature_flag_used`, `feature_flag_pattern` (`simple_conditional`, `di_switch`, `legacy`, or `none`)
- `files_created`, `files_modified`, `tasks_completed`
- `review_iterations`, `audit_result` (`all_pass`, `had_gaps`, or `skipped`), `audit_iterations`
- `validation_result` (`pass` or `skipped`), `boundary_history_written`, `pr_created`
- `boundary_history_value`: how useful the boundary history was during pre-planning — `none` means no history existed at pre-read time (so `boundary_history_written=true` paired with `value=none` is a legal combination, e.g. this run created the first entry); `irrelevant` means history was read but nothing applied; `low` means an adjacent entry was grazed but did not shape the plan; `medium` means an entry directly informed pre-planning; `high` means an entry was decisive in shaping the plan. Full anchored rubric lives in the inline reference sections below.
- `plan_deviation_notes`: brief note if the plan changed during execution (empty if no deviations)
- `child_steps`: list of `telemetry_payload` dicts collected from child tools invoked with `orchestrated=true` during the session
- `estimated_phase_tokens`: optional map of `{phase_id: {estimated_input_tokens, estimated_output_tokens}}`. For each subagent phase (preplan, plan, implement, review, audit, pr_description), compute:
  - `estimated_input_tokens`: `ceil(utf8_byte_count(briefing_text) / 4)`, where `briefing_text` is the full prompt text you sent to the subagent.
  - `estimated_output_tokens`: `ceil(utf8_byte_count(result_text) / 4)`, where `result_text` is the full text returned by the subagent.
  - UTF-8 byte count: count bytes, not characters. For example: `hello` (5 ASCII chars) = 5 bytes; `€` (1 char) = 3 bytes; `𝄞` (1 char) = 4 bytes.
  - Example: a phase with a 2000-byte briefing and an 800-byte result = `ceil(2000/4) + ceil(800/4)` = 500 + 200 = 700 tokens for that phase.
- `estimated_total_tokens`: sum of all per-phase `estimated_input_tokens` and `estimated_output_tokens` across all phases that ran. Omit if not computable. Never claim billing accuracy — these are heuristic estimates.

For fields not yet reached (early exit), use: 0 for counts, `skipped` for results, false for booleans. For fields not yet reached (early exit): omit `estimated_phase_tokens` and `estimated_total_tokens` (or pass null).

Before terminal workflow-state or telemetry writes, repeat the Skill Bill MCP health check. If the in-session MCP tool path returns `Transport closed`, use the packaged Kotlin `runtime-mcp` direct-stdio fallback for `feature_task_prose_workflow_update`, `feature_task_prose_finished`, and any remaining orchestrated child telemetry calls. Workflow state must not be left `running` solely because the session MCP transport died when the packaged runtime is available.

Before or immediately after `feature_task_prose_finished`, call `feature_task_prose_workflow_update` one final time to:

- mark `finish` as completed for successful runs
- set `workflow_status` to `completed`, `abandoned`, or `failed`
- keep `current_step_id` at the step where the workflow stopped
- persist any final artifact patch needed to explain the terminal state

## Reference

For detailed step instructions, briefing templates, return-contract schemas, size reference, error recovery, and skills invoked, see the inline reference sections below.

## Reference


This reference holds the briefing templates, return contracts, and detailed substep instructions for `bill-feature-task`. Treat the rendered runtime wrapper as generated output; authored behavior lives here in `content.md`.

## Briefing Principles

Every subagent prompt is **self-contained**. Subagents do not have access to this conversation's prior turns, so the orchestrator must bundle everything the subagent needs:

1. **What to do** — phase-specific instructions.
2. **Contract** — acceptance criteria and non-goals.
3. **Context** — pre-planning digest, plan, spec path, issue key, feature-flag mode, validation strategy (whichever are relevant for the phase).
4. **Return shape** — the exact structured object the subagent must return.
5. **Standards** — pointer to `CLAUDE.md`, `AGENTS.md`, and the matching `.agents/skill-overrides.md` section.
6. **Tools and constraints** — e.g. do not use `git add -A`, do not re-read the raw spec, use `orchestrated=true` when calling specific MCP tools.

Every subagent **returns a single structured block** as the final text message, prefixed with `RESULT:` and containing valid JSON matching the declared contract, so the orchestrator can parse it deterministically. Narrative explanation (if any) goes above the `RESULT:` block; the orchestrator only consumes the JSON.

## Workflow State Contract

`bill-feature-task` now owns a durable workflow-state layer in addition to
its telemetry session.

The orchestrator must maintain:

- `session_id` — from `feature_task_prose_started`, used only for telemetry
- `workflow_id` — from `feature_task_prose_workflow_open`, used for durable
  workflow state
- `child_steps` — local list of orchestrated child telemetry payloads

### Required workflow tools

- `feature_task_prose_workflow_open`
- `feature_task_prose_workflow_update`
- `feature_task_prose_workflow_get`

### Operational recovery tools

External callers may inspect and reactivate persisted runs through:

- `feature_task_prose_workflow_resume` — dry-run recovery summary
- `feature_task_prose_workflow_continue` — re-open a resumable run and emit a recovered continuation brief.
  For decomposed parent features, callers may pass the parent issue key
  (for example `SKILL-51`) instead of naming a subtask workflow id; the
  runtime resolves the parent manifest and selects the current subtask.

### Canonical step ids

Use these exact step ids when updating workflow state:

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

### Canonical artifacts

The shell-owned source of truth for artifact names is the stable artifact list in this `content.md` file and the generated runtime wrapper rendered from it.

Persist these named artifacts through `artifacts_patch` when the workflow runtime reaches the corresponding phase:

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

### Phase-to-artifact mapping

At each phase boundary, persist the structured result the orchestrator already
has in hand:

- Step 1 → `assessment`
- Step 1b → `branch`
- Step 2 → `preplan_digest`
- Step 3 → `plan` (implementation plan or decomposition package)
- Step 4 → `implementation_summary`
- Step 5 → `review_result`
- Step 6 → `audit_report`
- Step 6b → `validation_result`
- Step 7 → `history_result`
- Step 8 → `commit_push_result`
- Step 9 → `pr_result`

### Open sequence

Immediately after Step 1 records the assessment:

1. Call `feature_task_prose_started`.
2. Save `session_id` even when the tool returns `status: skipped`.
3. Call `feature_task_prose_workflow_open` with:
   - `session_id`
   - `current_step_id: "assess"`
   - `issue_key: <normalized issue key>` from the router-confirmed task context
4. Save `workflow_id`.
5. Initialize `child_steps = []`.

### Update rules

After every major phase boundary:

- call `feature_task_prose_workflow_update`
- set the parent-owned `workflow_status`
- set the new `current_step_id`
- pass only the changed steps in `step_updates`
- merge the new structured artifact through `artifacts_patch`
- treat the response as a compact acknowledgement only; if a caller needs the complete steps or durable `artifacts` map after the write, call `feature_task_prose_workflow_get` or `workflow show` explicitly.

When a loop sends work backwards:

- set the next active step explicitly
- increment that step's `attempt_count`
- keep the latest artifact for the phase that triggered the loop

### Terminal-state rules

When the workflow succeeds:

- mark `finish` completed
- set `workflow_status: "completed"`

When the user abandons:

- set `workflow_status: "abandoned"`
- leave `current_step_id` on the step where the workflow stopped
- persist a small final artifact patch such as `{"terminal_note": "..."}`
  when useful

When the workflow errors:

- set `workflow_status: "failed"`
- leave `current_step_id` on the failing step
- persist a final artifact patch describing the failure when useful

Workflow state is independent of telemetry. Do not skip workflow-state writes
just because telemetry is disabled.

## Continuation Mode Contract

When an external caller invokes `feature_task_prose_workflow_continue`, the
returned payload becomes the supported re-entry contract for
`bill-feature-task`.

The compact continuation payload includes:

- `skill_name` — always `bill-feature-task`
- `continue_status` — `reopened`, `already_running`, `blocked`, or `done`
- `resume_step_id` / `continue_step_id` and the matching step label
- `continue_step_directive` — the step-specific rule for the resumed phase
- `required_artifact_keys`, `available_artifact_keys`, and
  `missing_artifact_keys`
- `current_step_artifacts` — compact summaries for the required current-step
  artifacts. Small artifacts are inline; large artifacts include size,
  preview, truncation, and omission metadata instead of the full value.
- `omitted_artifact_keys` — available durable artifacts omitted from the
  compact current-step summaries
- `continuation_brief` — short human-facing summary
- `continuation_entry_prompt` — a paste-ready prompt for an orchestrator or AI
  caller
- `read_only_full_state_command` and `read_only_full_state_guidance` — the
  read-only `workflow show` fallback for complete state

Re-entry rules:

- Do not open a new workflow when continuing an existing run.
- Keep using the same `workflow_id` and `session_id`.
- Treat inline `current_step_artifacts` values as authoritative inputs for the
  resumed phase. If an artifact is summarized or omitted because it is large,
  inspect full state with the payload's `read_only_full_state_command` only as
  needed.
- Skip earlier completed steps unless the normal workflow loops send work
  backwards.
- After the resumed step completes, continue the standard `bill-feature-task`
  sequence from that point onward.
- For heavy phases (`preplan`, `plan`, `implement`, `audit`, `validate`,
  `pr_description`), continuation prompts and spawned subagent briefings must
  carry durable progress-write instructions that include `workflow_id`,
  `step_id`, and next `attempt_count`.
- For decomposed parent features, `continue <issue-key>` resumes the
  in-progress subtask at its last durable workflow step. If none is in
  progress, it starts the first pending subtask whose dependencies are complete.
- If the selected decomposition path is blocked, report the blocked reason and
  stop. Do not skip to a later dependent subtask unless the manifest explicitly
  marks that dependency as optional and skipped.
- Decomposed execution defaults to `same_branch_commit_per_subtask`: all
  subtasks run on the parent feature branch, and each completed subtask must
  produce its own commit before the runtime advances to the next subtask.
- `stacked_branches` is an explicit parent manifest opt-in. In stacked mode the
  runtime uses the declared subtask branch and base relationship, and must stop
  instead of advancing when the current branch/base does not match the manifest.

## Durable Progress Write Contract

Heavy phases (`preplan`, `plan`, `implement`, `audit`, `validate`,
`pr_description`) must write durable progress through
`feature_task_prose_workflow_update` while work is in flight.

Progress-write rules:

- Inputs for each write:
  - `workflow_id` from continuation/open payload
  - `current_step_id` set to the active heavy-phase step id
  - `step_updates` containing the active step with `status: "running"` and the
    active `attempt_count`
  - `workflow_status: "running"`
- Emit progress writes at:
  - phase start (`kind: "phase_started"`)
  - each task start (`kind: "task_started"`)
  - each task completion (`kind: "task_completed"`)
  - bounded heartbeat interval during work that exceeds the interval (`kind: "heartbeat"`)
  - phase completion before returning the final `RESULT:` block (`kind: "phase_completed"`)
- Persist each event through `artifacts_patch.progress_event` with this shape:
  - `workflow_id`
  - `step_id`
  - `attempt_count`
  - `source` (`phase_subagent`)
  - `kind`
  - `message`
  - `sequence` (phase-local monotonic integer)
  - `timestamp` (ISO-8601 UTC string)
- Progress writes are best-effort, but failures are never silent:
  - collect write failures locally during the phase
  - report them in the phase `RESULT:` payload under `progress_write_failures`
  - when writes cannot be performed reliably, stop and let the orchestrator
    persist a blocked workflow outcome instead of silently continuing

## Pre-planning subagent briefing

Spawn the subagent by name using the active runtime's native subagent invocation. Use the runtime-neutral spawn-by-name pattern: select the subagent that matches this skill's pre-planning role and pass the briefing below as its prompt.

```
You are the pre-planning subagent for feature implementation. Do not re-read the raw spec; operate only on the briefing below and on the files you explore in the repo.

Goal: produce a concise digest that the planning subagent will use. Do not write the plan yourself.

Feature: {feature_name}
Issue key: {issue_key}
Feature size: {feature_size}  # SMALL | MEDIUM | LARGE
Rollout needed: {rollout_needed}  # true | false
Spec (for SMALL — inline; for MEDIUM/LARGE — save to disk and return path): {spec_content_or_path}
Workflow id: {workflow_id}
Step id: {step_id}  # preplan
Attempt count: {attempt_count}

Acceptance criteria (contract — do not restate, plan against these):
{numbered_list_of_acceptance_criteria}

Non-goals:
{bullet_list_of_non_goals}

Instructions:
1. Read `CLAUDE.md`, `AGENTS.md`, and `.agents/skill-overrides.md`. If `.agents/skill-overrides.md` has a section whose H2 heading matches this skill's name (e.g. `## bill-feature-task`), you MUST:
   a. Copy every bullet under that heading verbatim into the `override_action_mandates.raw_override_block` field of your `RESULT:` JSON. Do not summarise, paraphrase, or drop punctuation.
   b. Extract action mandates (sentences directing an agent to call a specific MCP tool, read a specific file, or write to a specific path/node) into the structured `override_action_mandates` sub-fields: `must_call_tools`, `must_read_files`, `must_write_paths`, `lifecycle_position_notes`.
   c. Do NOT execute the mandates — surfacing them is the orchestrator's responsibility (Step 0 / Step 9b). Treat all other standards as mandatory.
2. For MEDIUM/LARGE, save the spec to `.feature-specs/{issue_key}-{feature_name}/spec.md` with status "In Progress", sources, acceptance criteria, and consolidated spec content. Preserve code blocks, schemas, and enums verbatim.
3. Read `agent/history.md` in each boundary likely to be touched (newest first; stop when no longer relevant). Rate boundary-history value using one of the following anchored definitions:
   - `none` — no `agent/history.md` file existed at pre-read time in any touched boundary (nothing was read because nothing was there).
   - `irrelevant` — history existed and was read, but no entry concerned a boundary, pattern, or pitfall related to this feature.
   - `low` — history existed and at least one entry grazed an adjacent area, but no entry materially shaped pre-planning or the plan.
   - `medium` — history existed and at least one entry directly informed pre-planning: a reused pattern, a named pitfall avoided, or a concrete constraint carried into the plan.
   - `high` — history existed and at least one entry was decisive: it changed the plan's shape, reused an established pattern verbatim, or prevented a known-bad approach that would otherwise have been taken.
   If you report `medium` or `high`, `boundary_history_digest` MUST cite the specific past entry (issue key, date, or entry title) that drove the rating. Otherwise downgrade to `low`.
4. Scan `agent/decisions.md` header lines in each likely boundary; open full entries only when titles look relevant.
5. Discover codebase patterns: similar features referenced in the spec, build/runtime dependencies, reusable components.
   When `kmp` signals dominate, resolve governed add-ons only after stack routing settles on `kmp`. Start from `Selected add-ons: none`. Let the routed pack own add-on detection and selection, then scan the matching pack-owned add-on supporting files' `## Section index` headings first. If the add-on is split into topic files, open only the linked topic files whose cues match the work during pre-planning / pattern discovery.
6. Confirm `bill-code-check` can route this repo. If it cannot, pick the closest existing repo-native validation command.
7. If rollout uses a feature flag, invoke `bill-feature-guard` via the Skill tool — DO NOT search the filesystem (no `find`, `grep -r`, etc.) to locate skill files; the Skill tool resolves skills by name. Apply it in the current agent context and choose a pattern: legacy | di_switch | simple_conditional. Record flag name and switch point.
8. Do NOT produce a plan. Do NOT implement anything.
9. Follow the Durable Progress Write Contract in this skill:
   - write progress at phase start, task boundaries, heartbeat interval, and phase completion before `RESULT:`
   - use `workflow_id`, `step_id`, and `attempt_count` from this briefing
   - if a progress write fails, record it and continue only when safe; otherwise stop so the orchestrator can block explicitly.

Return exactly one RESULT: block as your final message, containing valid JSON with this shape:

RESULT:
{
  "spec_path": "<path or null for SMALL>",
  "selected_addons": ["<addon-slug>", ...],
  "boundaries_touched": ["<module/package/area>", ...],
  "boundary_history_digest": "<concise summary — patterns to reuse, pitfalls to avoid>",
  "boundary_history_value": "none|irrelevant|low|medium|high",
  "boundary_decisions_digest": "<concise summary or empty string>",
  "codebase_patterns_digest": "<concise summary — similar features, reusable components, gotchas>",
  "validation_strategy": "bill-code-check | <repo-native command>",
  "feature_flag": {
    "used": false,
    "pattern": "none|simple_conditional|di_switch|legacy",
    "flag_name": "<name or empty>",
    "switch_point": "<where the switch lives, or empty>"
  },
  "standards_notes": "<anything from CLAUDE.md/AGENTS.md/skill-overrides.md the planner must honor>",
  "progress_write_failures": ["<message>", ...],
  "override_action_mandates": {
    "must_call_tools": ["<mcp_tool_name>", ...],
    "must_read_files": ["<path>", ...],
    "must_write_paths": ["<path or graph node>", ...],
    "lifecycle_position_notes": "<concise — when each mandate fires>",
    "raw_override_block": "<verbatim copy of the matched section, empty string if none>"
  }
}
```

## Planning subagent briefing

```
You are the planning subagent for feature implementation. Do not re-read the raw spec; operate on the briefing below.

Goal: produce either an ordered implementation plan the implementation subagent can execute, or a decomposition package that splits oversized work into resumable subtask specs.

Feature: {feature_name}
Issue key: {issue_key}
Feature size: {feature_size}
Spec path (MEDIUM/LARGE): {spec_path}
Workflow id: {workflow_id}
Step id: {step_id}  # implement
Attempt count: {attempt_count}
Workflow id: {workflow_id}
Step id: {step_id}  # plan
Attempt count: {attempt_count}

Acceptance criteria (contract):
{numbered_list}

Non-goals:
{non_goals}

Pre-planning digest (from Step 2):
{pre_planning_digest_json}

Planning rules:
- Break work into atomic tasks; each completable in one turn.
- Order tasks by dependency (data layer → domain → presentation).
- Each task must reference the acceptance criteria it satisfies.
- If the plan includes testable logic, the FINAL task must be a dedicated test task. Implementation tasks may have `tests: "None"` only because the final test task will cover them. Skip the final test task only when there is genuinely nothing testable (pure config, docs, agent-config/skill prose, UI changes with no test infra).
- For MEDIUM: if the plan benefits from phases, split it into phases with checkpoints.
- For LARGE or any plan that would exceed 15 atomic implementation tasks, touch more than 6 boundaries, contain multiple independently resumable milestones, or require sequencing where later work depends on foundation that should be verified separately: switch to decomposition mode.
- If rollout uses a feature flag, every task states how it respects the flag strategy (pattern: {feature_flag_pattern}).
- Reference design artifacts (mockups, screenshots, wireframes, API examples) by filename where relevant.
- Do NOT implement anything.
- Do NOT expose a separate "codebase patterns" section; fold those findings into task descriptions.
- Follow the Durable Progress Write Contract in this skill:
  - write progress at phase start, task boundaries, heartbeat interval, and phase completion before `RESULT:`
  - use `workflow_id`, `step_id`, and `attempt_count` from this briefing
  - if a progress write fails, record it and continue only when safe; otherwise stop so the orchestrator can block/retry explicitly.

Decomposition rules:
- Once decomposition mode is selected, do not implement anything and do not return an implementation task list.
- Read the saved spec path only as needed to create accurate decomposition output.
- Return subtask definitions with enough detail for the orchestrator-owned shared feature-spec preparation path to write `spec.md`, ordered `spec_subtask_*.md` files, and the decomposition manifest.
- Keep `spec.md` as the parent overview contract. Each returned subtask must include scope, acceptance criteria, non-goals, dependencies, validation strategy, and the recommended next `bill-feature-task` prompt.
- Prefer 2-4 subtasks. Each subtask should be small enough for one independent feature-task run.
- Order subtasks by dependency and identify the first subtask to run.
- The decomposition manifest uses `execution_model: same_branch_commit_per_subtask` by default with one parent feature branch and no stack branches. Use `execution_model: stacked_branches` only as an explicit opt-in and then declare one stack branch per subtask in subtask order. Return enough manifest metadata for the shared path to emit those fields.
- The orchestrator writes `decomposition-manifest.yaml` directly from the template in `bill-feature-spec`. Your decomposition RESULT must include all fields required to fill that template. Required top-level fields: `contract_version` (always `"0.4"`), `issue_key`, `feature_name`, `parent_spec_path`, `execution_model`, `base_branch`, `feature_branch` (non-null string for same-branch, null for stacked), `stack_branches` (empty array for same-branch, one entry per subtask for stacked), `current_subtask_intent` (`subtask_id` and `action`). Optional top-level fields: `spec_source` (omit for local). Required per-subtask fields: `id`, `name`, `spec_path`, `status` (always `pending` on creation), `branch` (null), `commit_sha` (null), `workflow_id` (null), `blocked_reason` (null), `last_resumable_step` (null), `dependencies` (array of `{subtask_id, optional, skipped}`). Optional per-subtask fields: `linear_issue_id` (null unless spec_source is linear).
- Same-branch decompositions advance one subtask at a time on the parent feature
  branch. Each completed subtask gets an individual commit before the next
  pending dependency-complete subtask starts.
- The Git-tracked decomposition manifest is a human recovery ledger: commit
  subtask status/current-intent projections with the subtask, but keep the
  resulting commit SHA in durable workflow runtime state rather than trying to
  write it into the same commit's manifest projection.
- Stacked decompositions are opt-in only. Every subtask must declare its branch
  and expected base in stack order so continuation can check out the right branch
  and reject advancement onto the wrong base.
- Blocked subtasks are sticky: continuation reports the blocked reason and stops
  unless a later subtask's dependency marks the blocked dependency both optional
  and skipped.

Return exactly one RESULT: block as your final message, containing valid JSON with this shape:

RESULT:
{
  "mode": "implement",
  "rollout_summary": "<feature flag + pattern, or N/A>",
  "validation_strategy": "<inherit from pre-planning digest>",
  "phases": [
    {
      "name": "<phase name or 'single'>",
      "tasks": [
        {
          "id": 1,
          "description": "<what this task does>",
          "files": ["<path or path-pattern>", ...],
          "satisfies_criteria": [1, 3],
          "tests": "<test coverage description or 'None' (deferred to final test task) or 'None (nothing testable)'>"
        }
      ]
    }
  ],
  "task_count": <int>,
  "phase_count": <int>,
  "has_dedicated_test_task": <bool>,
  "progress_write_failures": ["<message>", ...]
}

For decomposition mode, return this shape instead:

RESULT:
{
  "mode": "decompose",
  "decomposition_reason": "<why this is too large for one reliable feature-task execution>",
  "parent_spec_path": "<path to spec.md>",
  "recommended_first_subtask_id": 1,
  "execution_model": "same_branch_commit_per_subtask",
  "base_branch": "<base branch name, e.g. main>",
  "feature_branch": "<feat/{issue_key}-{feature_name} or null for stacked>",
  "stack_branches": [],
  "current_subtask_intent": {
    "subtask_id": 1,
    "action": "start"
  },
  "subtasks": [
    {
      "id": 1,
      "name": "<short dependency-ordered name>",
      "spec_path": ".feature-specs/{issue_key}-{feature_name}/spec_subtask_1_<slug>.md",
      "status": "pending",
      "branch": null,
      "commit_sha": null,
      "workflow_id": null,
      "blocked_reason": null,
      "last_resumable_step": null,
      "linear_issue_id": null,
      "dependencies": [],
      "depends_on": [],
      "dependency_reason": "<why this comes first, or empty for the first subtask>",
      "scope": "<what this subtask owns>",
      "acceptance_criteria": ["<criterion 1>", "<criterion 2>"],
      "non_goals": ["<explicitly deferred work>"],
      "validation_strategy": "<bill-code-check or repo-native command>",
      "handoff_prompt": "Run bill-feature-task on <spec_path>."
    }
  ],
  "presentation_summary": "I split this into N subtasks. We should work on subtask <id> first because <dependency reason>.",
  "progress_write_failures": ["<message>", ...]
}
```

## Implementation subagent briefing

```
You are the implementation subagent for feature implementation. Do not re-read the raw spec; operate on the briefing below and on the files in the repo.

Goal: execute the plan atomically and return a structured summary.

Feature: {feature_name}
Issue key: {issue_key}
Feature size: {feature_size}
Spec path (MEDIUM/LARGE): {spec_path}

Acceptance criteria (contract):
{numbered_list}

Plan (from Step 3):
{plan_json}

Pre-planning digest (from Step 2):
{pre_planning_digest_json}

Execution rules:
- After each task, print progress: "✅ [<n>/<total>] <task description>".
- Follow the Durable Progress Write Contract in this skill:
  - write progress at phase start, each task start/completion, heartbeat interval, and phase completion before `RESULT:`
  - use `workflow_id`, `step_id`, and `attempt_count` from this briefing
  - if a progress write fails, record it and continue only when safe; otherwise stop so the orchestrator can block/retry explicitly.
- Follow standards in `CLAUDE.md`, `AGENTS.md`, and any matching `.agents/skill-overrides.md` section.
- If this briefing or recovered context says `goal_continuation.enabled=true`, never run installer or uninstall flows: do not call `./install.sh`, `./uninstall.sh`, `skill-bill install`, `skill-bill install apply`, or any equivalent install-sync command. This overrides `AGENTS.md` install-refresh guidance for the duration of goal-continuation because install sync can reset local workflow state. If source skill changes would normally require install refresh, leave it for after the goal run and mention it in `notes_for_review`.
- Write production-grade code. Do not introduce deprecated components, APIs, or patterns when a supported alternative exists.
- Write tests exactly as specified in each task's `tests` field.
- If a task reveals the plan is wrong, STOP and return with `plan_deviation_notes` populated describing what changed and why; do not try to silently re-plan.
- Do not skip or combine tasks.
- If the plan has phases, pause between phases for a brief self-checkpoint.
- When removing user-facing code, shared resources, or wiring: immediately clean up orphaned artifacts in the same task.
- When changing agent-config or skill repositories: update adjacent catalogs and wiring in the same task.
- Test gate: before returning, verify unit tests were written if the plan included testable logic.
- For MEDIUM/LARGE: before returning, perform a post-implementation self-compact — summarize files created/modified, feature-flag info, criteria-to-file mapping, and any plan deviations. Then re-read the saved spec to verify every criterion is mapped.
- Do NOT commit or push. Do NOT open a PR. Those are the orchestrator's job.

Return exactly one RESULT: block as your final message, containing valid JSON with this shape:

RESULT:
{
  "tasks_completed": <int>,
  "files_created": [<path>, ...],
  "files_modified": [<path>, ...],
  "tests_written": [<path>, ...],
  "plan_deviation_notes": "<empty if no deviations>",
  "criteria_to_file_map": {"1": ["path"], "2": ["path"]},
  "notes_for_review": "<anything reviewers should focus on>",
  "progress_write_failures": ["<message>", ...],
  "stopped_early": <bool>,
  "stopped_reason": "<empty if stopped_early is false>"
}
```

### Fix-loop briefing (used by Step 5 review loop)

When the code-review step produces Blocker/Major findings, the orchestrator respawns the implementation subagent with a fix-focused briefing:

```
You are the implementation subagent, invoked to fix findings from the code-review step. Scope: fix only the findings listed below; do not add unrelated changes.

Acceptance criteria (contract, for reference only — do not expand scope):
{numbered_list}

Findings to fix:
{risk_register_rows_with_F-ids_and_file_line_paths}

Current branch diff pointer: {branch_or_commit_range}

Test gate is relaxed: write tests only when the finding being fixed requires them (for example, a finding about missing regression coverage or a broken test). Do not treat the standard "write tests if the plan included testable logic" gate as mandatory in fix mode — the plan is not being re-executed here.

Return the standard implementation return contract, with `notes_for_review` describing which finding each change addresses.
```

## Completeness audit subagent briefing

```
You are the completeness audit subagent. Do not re-read the raw spec unless you need to resolve ambiguity in a criterion; prefer the briefing.

Goal: verify every acceptance criterion is actually satisfied by the implementation.

Feature: {feature_name}
Feature size: {feature_size}
Spec path (MEDIUM/LARGE): {spec_path}
Workflow id: {workflow_id}
Step id: {step_id}  # audit
Attempt count: {attempt_count}

Acceptance criteria (contract):
{numbered_list}

Implementation summary (from Step 4):
{implementation_return_json}

Branch diff pointer (MEDIUM/LARGE): {branch_or_commit_range}

Instructions:
- SMALL: produce a quick confirmation per criterion. Read only the files mentioned in the implementation summary.
- MEDIUM/LARGE: produce a full per-criterion report with evidence paths. Verify against actual code, not the summary.
- Do NOT implement fixes. Do NOT edit files.
- If a criterion is partially satisfied, record it as a gap with `suggested_fix`.
- Follow the Durable Progress Write Contract in this skill:
  - write progress at phase start, per-criterion start/completion, heartbeat interval, and phase completion before `RESULT:`
  - use `workflow_id`, `step_id`, and `attempt_count` from this briefing
  - if a progress write fails, record it and continue only when safe; otherwise stop so the orchestrator can block explicitly.

Return exactly one RESULT: block as your final message, containing valid JSON with this shape:

RESULT:
{
  "pass": <bool>,
  "per_criterion": [
    {
      "id": 1,
      "criterion": "<text>",
      "verdict": "pass|partial|fail",
      "evidence": ["<path:line>", ...]
    }
  ],
  "gaps": [
    {
      "criterion_id": <int>,
      "missing": "<what is missing>",
      "suggested_fix": "<concrete suggestion>"
    }
  ],
  "progress_write_failures": ["<message>", ...]
}
```

## Quality-check subagent briefing

```
You are the quality-check subagent. Your job is to run the final validation gate and return a structured result.

Feature: {feature_name}
Validation strategy: {validation_strategy}  # 'bill-code-check' or a repo-native command
Scope: branch diff since main for MEDIUM/LARGE, current unit of work for SMALL.
Workflow id: {workflow_id}
Step id: {step_id}  # validate
Attempt count: {attempt_count}

Instructions:
1. If validation_strategy is `bill-code-check`, invoke the `bill-code-check` skill via the Skill tool — DO NOT search the filesystem (no `find`, `grep -r`, etc.) to locate skill files; the Skill tool resolves skills by name. Apply its instructions in the current agent context (do not delegate to another subagent); it auto-routes to the matching stack-specific quality-check skill.
2. Otherwise, run the provided repo-native command.
3. Fix validation findings at their root cause, rerun validation, and keep iterating until validation passes, like the code-review fix loop handles review findings. Do not mark the step blocked, stop, or return `validation_result: "fail"` for fixable validation findings. Do not use suppressions unless explicitly allowed by project standards.
4. Call the `quality_check_finished` MCP tool with `orchestrated=true`. Pass all started+finished fields directly (skip `quality_check_started` in orchestrated mode): `routed_skill`, `detected_stack`, `fallback`, `scope_type`, `initial_failure_count`, plus the finished fields. Include `fallback_reason` when `fallback=true` or a reason is known.
5. Capture the `telemetry_payload` returned by `quality_check_finished` verbatim.
6. Follow the Durable Progress Write Contract in this skill:
   - write progress at phase start, command/check start-completion boundaries, heartbeat interval, and phase completion before `RESULT:`
   - use `workflow_id`, `step_id`, and `attempt_count` from this briefing
   - if a progress write fails, record it and continue only when safe; otherwise stop so the orchestrator can block explicitly.

Return exactly one RESULT: block as your final message, containing valid JSON with this shape:

RESULT:
{
  "validation_result": "pass|skipped",
  "routed_skill": "<skill name or empty>",
  "detected_stack": "<stack or empty>",
  "fallback": false,
  "fallback_reason": "<reason when fallback is true or known, otherwise omit>",
  "initial_failure_count": <int>,
  "final_failure_count": <int>,
  "fixes_applied": "<brief summary>",
  "progress_write_failures": ["<message>", ...],
  "telemetry_payload": { ... verbatim from quality_check_finished ... }
}
```

## PR-description subagent briefing

```
You are the PR-description subagent. Your job is to create the pull request and return its URL.

Feature: {feature_name}
Issue key: {issue_key}
Branch: feat/{issue_key}-{feature_name}
Base branch: main (or the repo's main branch if different)
Workflow id: {workflow_id}
Step id: {step_id}  # pr_description
Attempt count: {attempt_count}

Acceptance criteria (for reference when drafting the PR body):
{numbered_list}

Implementation summary (from Step 4):
{implementation_return_json}

Instructions:
1. Invoke `bill-pr-description` via the Skill tool — DO NOT search the filesystem (no `find`, `grep -r`, etc.) to locate skill files; the Skill tool resolves skills by name. Apply its instructions in the current agent context. Respect repo-native PR templates if present (`.github/pull_request_template.md`, `PULL_REQUEST_TEMPLATE.md`, etc.).
2. Create the PR with `gh pr create` using a HEREDOC for the body.
3. Compare the normalized generated body with the actual PR body passed to `gh pr create` or read back with `gh pr view --json body` when available; call the `pr_description_generated` MCP tool with `orchestrated=true` and `was_edited_by_user=true` when those bodies differ once the PR is created.
4. Capture the `telemetry_payload` returned by `pr_description_generated` verbatim.
5. Follow the Durable Progress Write Contract in this skill:
   - write progress at phase start, draft/create/report boundaries, heartbeat interval, and phase completion before `RESULT:`
   - use `workflow_id`, `step_id`, and `attempt_count` from this briefing
   - if a progress write fails, record it and continue only when safe; otherwise stop so the orchestrator can block explicitly.

Return exactly one RESULT: block as your final message, containing valid JSON with this shape:

RESULT:
{
  "pr_created": <bool>,
  "pr_url": "<url or empty>",
  "pr_title": "<title>",
  "used_repo_template": <bool>,
  "template_path": "<path or empty>",
  "progress_write_failures": ["<message>", ...],
  "telemetry_payload": { ... verbatim from pr_description_generated ... }
}
```

## Size Reference

| | SMALL (≤5 tasks, ≤3 boundaries) | MEDIUM (6-15 tasks, ≤6 boundaries) | LARGE (>15 tasks or >6 boundaries) |
|---|---|---|---|
| Save spec to disk | No | Yes | Yes, plus subtask specs when decomposed |
| Planning output | Implementation plan | Implementation plan | Decomposition package |
| Post-implementation compact (inside impl subagent) | No | Yes | Only in later subtask runs |
| Completeness audit | Quick confirmation | Full per-criterion report | Only in later subtask runs |
| Boundary history write | If impactful | Yes | Only in later subtask runs |
| Codebase discovery (inside pre-planning subagent) | No | Yes | Yes, enough to split safely |

SMALL and MEDIUM implementation runs: feature flag if required, code review (inline in orchestrator), quality check (subagent), boundary history (inline), commit/push (inline), PR description (subagent).

LARGE decomposition runs stop after Step 3, persist the decomposition package as `plan`, write subtask specs, create or update the parent `decomposition-manifest.yaml`, and close with `completion_status: "abandoned_at_planning"` plus `plan_deviation_notes: "decomposed into N subtasks"`. This is an intentional planning-stage terminal state. The next implementation run starts from the first generated subtask spec.

## Error Recovery

For the parsing posture of subagent `RESULT:` blocks (best-effort recovery, single corrective re-spawn, escalation), see the `RESULT:` block parsing tolerance section below. Treat that section as authoritative for any runtime-level malformed-payload handling referenced below.

- **Pre-planning subagent fails** — report the error and ask the user whether to retry, adjust scope, or abandon. If abandoned, call `feature_task_prose_finished` with `completion_status: "abandoned_at_planning"`.
- **Planning subagent returns an invalid plan** (missing fields, no dedicated test task when testable logic exists, etc.) — respawn it once with a corrective briefing that lists the violations. If it still fails, abandon at planning.
- **Planning subagent returns `mode: "decompose"`** — treat this as a valid terminal planning result. Persist the `plan` artifact, validate and write the parent `decomposition-manifest.yaml`, present the subtask order and acceptance criteria, mark later workflow steps skipped, close workflow state as `abandoned` at `plan`, and call `feature_task_prose_finished` with `completion_status: "abandoned_at_planning"`.
- **Implementation subagent stops early with `stopped_early: true`** — the orchestrator decides: if `plan_deviation_notes` imply a re-plan, respawn the planning subagent with the deviation notes and then a fresh implementation subagent; otherwise, hand to the user.
- **Code-review fix loop exceeds 3 iterations** — stop, report remaining findings, hand to user. Call `feature_task_prose_finished` with `completion_status: "abandoned_at_review"`.
- **Completeness audit loops exceed 2 iterations** — report remaining gaps, let user decide. Call `feature_task_prose_finished` accordingly.
- **Quality-check subagent cannot run any validation command** — persist `validation_result: "skipped"` with the reason and continue finalization. Do not block the workflow for validation failures that can be fixed in the repository; keep fixing and rerunning validation until it passes.
- **PR-description subagent fails to create the PR** — report the error, offer to retry. If abandoned, call `feature_task_prose_finished` with `completion_status: "error"`.
- **Skill Bill MCP transport closes** — do not treat `Transport closed` as telemetry disabled. First run a lightweight health check such as `feature_task_prose_workflow_latest`; if the tool path is still closed, call the same Skill Bill tool through the packaged Kotlin `runtime-mcp` stdio binary with a JSON-RPC `tools/call` payload. Use that direct-stdio fallback for owned review telemetry, workflow-state updates, and `feature_task_prose_finished`. Record the fallback in the current step artifact. If the packaged runtime is unavailable too, report the telemetry failure explicitly and preserve the terminal artifact in the final user summary.
- **Review telemetry import is rejected** — inspect the import error. If the review text is missing required `bill-code-review` metadata such as `Review run ID: <review-run-id>`, re-run or reformat the review output from the actual review result and retry import once. Do not pass a prose-only review summary to `import_review`.

In all early-exit cases, close the telemetry session with the appropriate `completion_status` so the run is not orphaned.

In those same early-exit cases, also close the workflow state with
`feature_task_prose_workflow_update` so the durable workflow does not remain in
`running`.

## RESULT Block Parsing Tolerance


This artifact records the parsing posture for the JSON `RESULT:` block returned by every `bill-feature-task` subagent. The orchestrator parses these blocks inline; no machine parser exists in the Kotlin runtime modules. The choice below governs what subagents may emit and how the orchestrator behaves when a subagent's final message deviates from the strict contract.

## Resolutions Considered

- **Resolution A — Best-effort plus surface failure with retry.** The orchestrator attempts a strict parse first. If the strict parse fails, it tries a best-effort recovery: locate the last `RESULT:` literal in the message, isolate the trailing JSON object, and parse that. If recovery still fails, the orchestrator surfaces the malformed payload back to the subagent with one corrective re-spawn before escalating to the user. Subagents are expected to return strict JSON, but minor deviations (leading prose above the marker, trailing whitespace, single trailing comma) do not abort the workflow.
- **Resolution B — Defensive parsing.** The orchestrator only accepts strictly-formatted output; any deviation is a hard failure with no retry. Subagents would need a richer parser contract (schema validation, error codes) and the orchestrator would need to embed a real parser rather than inline extraction.

## Chosen Resolution

**Resolution A.** The orchestrator currently parses inline; investing in defensive parsing would require a separate parser layer that does not exist today. Best-effort with one corrective retry preserves the strict contract while tolerating the minor formatting deviations that show up in practice (different runtimes wrap output differently, and some emit a short narrative before the `RESULT:` marker even when told not to).

## Runtime Posture

- **All supported runtimes** (Claude, Codex, OpenCode, Copilot) are treated as best-effort emitters. No runtime is granted strict-only treatment. Subagents target strict JSON; the orchestrator absorbs minor noise.
- Subagents MUST still emit exactly one `RESULT:` block as their final message. Multiple `RESULT:` blocks, missing blocks, or non-JSON payloads remain failure conditions.

## Orchestrator Behavior on Malformed RESULT

1. **Strict parse.** Attempt to locate the single `RESULT:` marker and parse the JSON body that follows.
2. **Best-effort recovery.** If strict parse fails, locate the last `RESULT:` marker in the message and parse the trailing JSON object. Strip a single trailing comma before the final closing brace if present. Strip surrounding code-fence markers (```json … ```) if the subagent wrapped the JSON.
3. **Corrective re-spawn (one attempt).** If recovery still fails, re-spawn the same subagent with the same briefing plus a corrective addendum that quotes the malformed payload back and reminds the subagent to emit exactly one `RESULT:` block as the final message with valid JSON matching the declared contract.
4. **Escalation.** If the corrective re-spawn still produces a malformed payload, stop the workflow at the failing step, persist the malformed payload as the failed phase artifact, and report the failure to the user with the payload attached. Do not silently fall back to placeholder values or skip the phase.

## Retry Posture

- Best-effort recovery is automatic and silent (no user prompt).
- Corrective re-spawn is automatic and counts toward the orchestrator's per-step retry budget; it is logged in the workflow state.
- Beyond one corrective re-spawn, the orchestrator escalates to the user. Loops are not permitted at the parsing layer.

## Escalation Path

When parsing escalates to the user:

- The workflow stays in `running` until the user decides; the failing step keeps `status: "running"` with `attempt_count` incremented.
- The user may choose to: re-run the failing step, abandon the workflow (`workflow_status: "abandoned"`), or hand-edit the artifact and resume.
- If the user abandons, call `feature_task_prose_finished` with an appropriate `completion_status` (typically `error`) and update workflow state to `failed` with the malformed payload preserved in the artifact patch.

## Non-Goals

- Replacing the JSON `RESULT:` contract with a different return shape.
- Adding schema validation in the Kotlin runtime modules (`runtime-core` / `runtime-cli`).
- Changing the per-phase return contracts in the inline reference sections above.
