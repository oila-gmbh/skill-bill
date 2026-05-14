---
name: bill-feature-implement
description: Use when doing end-to-end feature implementation from design doc to verified code. Automatically scales ceremony based on feature size - lightweight for small changes, full orchestration for medium features, and explicit decomposition into resumable subtask specs when work is too large for one reliable execution. Runs each heavy phase (pre-planning, planning, implementation, completeness audit, quality check, PR description) inside its own subagent with a rich self-contained briefing, to keep the orchestrator context small. Code review stays in the orchestrator because it already spawns specialist subagents internally. Use when user mentions implement feature, build feature, implement spec, or feature from design doc.
---

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
- Follow the detailed per-phase briefing contracts in the inline reference sections below. Do not invent prose-only handoffs when a structured artifact exists.

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

Subagents run sequentially, in the same worktree (no `isolation: "worktree"`). Do not launch any of these subagents in parallel. Each subagent receives a self-contained briefing. See the inline reference sections below for the per-phase briefing templates and structured return contracts.

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

Spawn a subagent with the pre-planning briefing defined in the inline reference sections below under `Pre-planning subagent briefing`. The briefing includes acceptance criteria, non-goals, issue key, feature name, spec content (or saved spec path for MEDIUM/LARGE), feature size, expected affected boundaries (if known), rollout need, and explicit instructions to:

- Read `agent/history.md` in each boundary the feature is likely to touch (newest first; stop when no longer relevant).
- Read `agent/decisions.md` header lines in each boundary and only open full entries when titles look relevant.
- For MEDIUM/LARGE, save the spec to `.feature-specs/{ISSUE_KEY}-{feature-name}/spec.md` with status `In Progress`.
- Read `CLAUDE.md`, `AGENTS.md`, and the matching `bill-feature-implement` section in `.agents/skill-overrides.md` when present.
- Discover codebase patterns: similar features referenced in the spec, build/runtime dependencies for affected boundaries, reusable components.
- When `kmp` signals dominate, resolve governed add-ons only after stack routing settles on `kmp`. Start from `Selected add-ons: none`. Let the routed pack own add-on detection and selection, then scan the matching pack-owned add-on supporting files' `## Section index` headings first. If the add-on is split into topic files, open only the linked topic files whose cues match the work during pre-planning and pattern discovery.
- Confirm `bill-quality-check` can route this repo; if not, pick a repo-native validation command.
- If the rollout uses a feature flag, invoke `bill-feature-guard` via the Skill tool (do not search the filesystem to locate skill files) and choose a pattern (Legacy / DI Switch / Simple Conditional).

The subagent returns the pre-planning return contract from the inline reference sections below. The orchestrator keeps this digest in context and passes it to later subagents — the raw findings stay in the subagent. Persist `preplan_digest` before advancing to `plan`.

## Step 3: Create Implementation Plan (subagent)

Step id: `plan`

Primary artifact: `plan`

Spawn a subagent with the planning briefing defined in the inline reference sections below under `Planning subagent briefing`. The briefing includes acceptance criteria, non-goals, feature size, pre-planning digest (from Step 2), rollout info, feature-flag pattern (if any), and validation strategy.

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

Spawn a subagent with the implementation briefing defined in the inline reference sections below under `Implementation subagent briefing`. The briefing includes acceptance criteria, plan (from Step 3), pre-planning digest (from Step 2), rollout info, feature-flag pattern (if any), spec path (for MEDIUM/LARGE), and execution rules (project standards, test gate, orphan cleanup, catalog updates for agent-config changes).

The subagent executes the plan atomically (one task per turn), prints per-task progress, writes tests as specified, and stops to re-plan if a task reveals the plan is wrong. On stop-and-re-plan, the subagent returns with `plan_deviation_notes` populated so the orchestrator can decide whether to re-spawn the planning subagent.

The subagent returns the implementation return contract: `files_created`, `files_modified`, `tasks_completed`, `plan_deviation_notes`, `tests_written`, `notes_for_review`.

For MEDIUM/LARGE, the subagent performs the post-implementation compact internally before returning: summarize files, feature flag info, criteria-to-file mapping, deviations; then re-read the saved spec to verify every criterion is mapped.

Persist `implementation_summary` before advancing to `review`.

## Step 5: Code Review (orchestrator)

Step id: `review`

Primary artifact: `review_result`

Run `bill-code-review` inline in the orchestrator through the active skill runtime. Scope: current unit of work for SMALL, branch diff for MEDIUM/LARGE. Do not wrap `bill-code-review` in an additional subagent — it already spawns specialist subagents internally.

Review loop:

- Auto-fix Blocker and Major findings by spawning the implementation subagent again with a fix briefing (acceptance criteria + list of findings + pointer to the current diff + instruction to fix only those findings).
- Before respawning, capture the exact diff pointer the review was run against — the branch name, commit range (for example `main..HEAD`), or explicit file list — and pass it as `{branch_or_commit_range}` in the fix briefing so the subagent knows which diff the findings refer to.
- Re-run review.
- Continue past Minor-only findings.
- Max 3 iterations.
- Do not pause to ask the user which finding to fix.

Orchestrated child telemetry:

- When this workflow invokes `import_review` and `triage_findings` for the review it owns, pass `orchestrated=true` to both tools.
- Import only complete `bill-code-review` output. The review text passed to `import_review` must include the metadata header lines, especially `Review run ID: <review-run-id>`. If the final review summary lacks those lines, re-run or reformat the review output before importing instead of synthesizing a prose-only review.
- Collect the `telemetry_payload` returned by `triage_findings` (or by `import_review` when the review has no findings) and append it to the local `child_steps` list.
- The review will not emit `skillbill_review_finished` on its own — its payload will be embedded in the `skillbill_feature_implement_finished` event instead.
- Before the first review telemetry call, do a lightweight Skill Bill MCP health check such as `feature_implement_workflow_latest`. If the MCP tool path returns `Transport closed`, call the same Skill Bill MCP tool through the packaged Kotlin `runtime-mcp` stdio binary from the repo (`runtime-kotlin/runtime-mcp/build/install/runtime-mcp/bin/runtime-mcp`) with a JSON-RPC `tools/call` payload and parse the returned text content. Use this direct-stdio fallback for subsequent owned telemetry/workflow calls in the run, and record that fallback in `review_result`.

Persist `review_result`, then advance to `audit`.

## Step 6: Completeness Audit (subagent)

Step id: `audit`

Primary artifact: `audit_report`

Spawn a subagent with the audit briefing defined in the inline reference sections below under `Completeness audit subagent briefing`. The briefing includes acceptance criteria, implementation return contract (from Step 4), and — for MEDIUM/LARGE — a pointer to the branch diff.

SMALL: the subagent returns a quick confirmation for each criterion. MEDIUM/LARGE: the subagent returns a full per-criterion report with evidence paths.

The subagent returns the audit return contract: `pass: bool`, `per_criterion: [...]`, `gaps: [...]`.

If gaps are found: the orchestrator respawns the planning subagent with the gaps, then the implementation subagent, then re-runs code review, then re-spawns the audit subagent. Max 2 audit iterations. When complete, the orchestrator updates the saved spec status to `Complete` (MEDIUM/LARGE only).

Persist `audit_report`, then advance to `validate`. Loop back to `plan` only when the audit contract requires it.

## Finalization Sequence (Steps 6b through 9)

Once the audit passes, run Steps 6b through 9 as a continuous sequence without pausing. The only reason to stop is if a step fails.

## Step 6b: Final Validation Gate (subagent)

Step id: `validate`

Primary artifact: `validation_result`

Spawn a subagent with the quality-check briefing defined in the inline reference sections below under `Quality-check subagent briefing`. The subagent runs `bill-quality-check` (which auto-routes to the matching stack quality-check skill), fixes any issues at their root without using suppressions, and must call `quality_check_finished` with `orchestrated=true` itself. The subagent returns: `validation_result`, `routed_skill`, `detected_stack`, `initial_failure_count`, `final_failure_count`, and the `telemetry_payload` returned by `quality_check_finished`.

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

Spawn a subagent with the PR-description briefing defined in the inline reference sections below under `PR-description subagent briefing`. The subagent invokes `bill-pr-description` via the Skill tool (do not search the filesystem to locate skill files), creates the PR, and must call `pr_description_generated` with `orchestrated=true` itself. The subagent returns: PR URL, PR title, and the `telemetry_payload` returned by `pr_description_generated`.

The orchestrator appends the returned `telemetry_payload` to the `child_steps` list. Persist `pr_result`, then advance to `finish`.

## Telemetry: Record Finished

After the PR is created (or when the workflow ends early due to error or user abandonment), call the `feature_implement_finished` MCP tool with:

- `session_id`: from `feature_implement_started`
- `completion_status`: `completed` if PR was created, otherwise `abandoned_at_planning`, `abandoned_at_implementation`, `abandoned_at_review`, or `error`
- `plan_correction_count`: how many times the user corrected the assessment or plan (0 if confirmed without changes)
- `plan_task_count`, `plan_phase_count`
- `feature_flag_used`, `feature_flag_pattern` (`simple_conditional`, `di_switch`, `legacy`, or `none`)
- `files_created`, `files_modified`, `tasks_completed`
- `review_iterations`, `audit_result` (`all_pass`, `had_gaps`, or `skipped`), `audit_iterations`
- `validation_result` (`pass`, `fail`, or `skipped`), `boundary_history_written`, `pr_created`
- `boundary_history_value`: how useful the boundary history was during pre-planning — `none` means no history existed at pre-read time (so `boundary_history_written=true` paired with `value=none` is a legal combination, e.g. this run created the first entry); `irrelevant` means history was read but nothing applied; `low` means an adjacent entry was grazed but did not shape the plan; `medium` means an entry directly informed pre-planning; `high` means an entry was decisive in shaping the plan. Full anchored rubric lives in the inline reference sections below.
- `plan_deviation_notes`: brief note if the plan changed during execution (empty if no deviations)
- `child_steps`: list of `telemetry_payload` dicts collected from child tools invoked with `orchestrated=true` during the session

For fields not yet reached (early exit), use: 0 for counts, `skipped` for results, false for booleans.

Before terminal workflow-state or telemetry writes, repeat the Skill Bill MCP health check. If the in-session MCP tool path returns `Transport closed`, use the packaged Kotlin `runtime-mcp` direct-stdio fallback for `feature_implement_workflow_update`, `feature_implement_finished`, and any remaining orchestrated child telemetry calls. Workflow state must not be left `running` solely because the session MCP transport died when the packaged runtime is available.

Before or immediately after `feature_implement_finished`, call `feature_implement_workflow_update` one final time to:

- mark `finish` as completed for successful runs
- set `workflow_status` to `completed`, `abandoned`, or `failed`
- keep `current_step_id` at the step where the workflow stopped
- persist any final artifact patch needed to explain the terminal state

## Reference

For detailed step instructions, briefing templates, return-contract schemas, size reference, error recovery, and skills invoked, see the inline reference sections below.

## Reference


This reference holds the briefing templates, return contracts, and detailed substep instructions for `bill-feature-implement`. Treat the rendered runtime wrapper as generated output; authored behavior lives here in `content.md`.

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

`bill-feature-implement` now owns a durable workflow-state layer in addition to
its telemetry session.

The orchestrator must maintain:

- `session_id` — from `feature_implement_started`, used only for telemetry
- `workflow_id` — from `feature_implement_workflow_open`, used for durable
  workflow state
- `child_steps` — local list of orchestrated child telemetry payloads

### Required workflow tools

- `feature_implement_workflow_open`
- `feature_implement_workflow_update`
- `feature_implement_workflow_get`

### Operational recovery tools

External callers may inspect and reactivate persisted runs through:

- `feature_implement_workflow_resume` — dry-run recovery summary
- `feature_implement_workflow_continue` — re-open a resumable run and emit a recovered continuation brief

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
- `commit_push_result` (reserved shell-owned name; Step 8 currently does not persist it)
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
- Step 8 → no persisted artifact today; `commit_push_result` remains a shell-documented reserved name until runtime support exists
- Step 9 → `pr_result`

### Open sequence

Immediately after Step 1 is confirmed:

1. Call `feature_implement_started`.
2. Save `session_id` even when the tool returns `status: skipped`.
3. Call `feature_implement_workflow_open` with:
   - `session_id`
   - `current_step_id: "assess"`
4. Save `workflow_id`.
5. Initialize `child_steps = []`.

### Update rules

After every major phase boundary:

- call `feature_implement_workflow_update`
- set the parent-owned `workflow_status`
- set the new `current_step_id`
- pass only the changed steps in `step_updates`
- merge the new structured artifact through `artifacts_patch`

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

When an external caller invokes `feature_implement_workflow_continue`, the
returned payload becomes the supported re-entry contract for
`bill-feature-implement`.

The continuation payload includes:

- `skill_name` — always `bill-feature-implement`
- `continuation_mode` — currently `resume_existing_workflow`
- `continue_status` — `reopened`, `already_running`, `blocked`, or `done`
- `continue_step_id` and `continue_step_label`
- `continue_step_directive` — the step-specific rule for the resumed phase
- `reference_sections` — the exact governed sections to re-read before
  resuming
- `step_artifacts` — the recovered structured artifacts that should replace
  chat-history reconstruction
- `session_summary` — saved Step 1 metadata when a telemetry session exists
- `continuation_brief` — short human-facing summary
- `continuation_entry_prompt` — a paste-ready prompt for an orchestrator or AI
  caller

Re-entry rules:

- Do not open a new workflow when continuing an existing run.
- Keep using the same `workflow_id` and `session_id`.
- Treat `step_artifacts` as authoritative inputs for the resumed phase.
- Skip earlier completed steps unless the normal workflow loops send work
  backwards.
- After the resumed step completes, continue the standard `bill-feature-implement`
  sequence from that point onward.

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

Acceptance criteria (contract — do not restate, plan against these):
{numbered_list_of_acceptance_criteria}

Non-goals:
{bullet_list_of_non_goals}

Instructions:
1. Read `CLAUDE.md`, `AGENTS.md`, and `.agents/skill-overrides.md`. If `.agents/skill-overrides.md` has a section whose H2 heading matches this skill's name (e.g. `## bill-feature-implement`), you MUST:
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
6. Confirm `bill-quality-check` can route this repo. If it cannot, pick the closest existing repo-native validation command.
7. If rollout uses a feature flag, invoke `bill-feature-guard` via the Skill tool — DO NOT search the filesystem (no `find`, `grep -r`, etc.) to locate skill files; the Skill tool resolves skills by name. Apply it in the current agent context and choose a pattern: legacy | di_switch | simple_conditional. Record flag name and switch point.
8. Do NOT produce a plan. Do NOT implement anything.

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
  "validation_strategy": "bill-quality-check | <repo-native command>",
  "feature_flag": {
    "used": false,
    "pattern": "none|simple_conditional|di_switch|legacy",
    "flag_name": "<name or empty>",
    "switch_point": "<where the switch lives, or empty>"
  },
  "standards_notes": "<anything from CLAUDE.md/AGENTS.md/skill-overrides.md the planner must honor>",
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

Decomposition rules:
- Once decomposition mode is selected, do not implement anything and do not return an implementation task list.
- Read the saved spec path only as needed to create accurate subtask specs.
- Write subtask specs under `.feature-specs/{issue_key}-{feature_name}/` using names like `spec_subtask_1_foundation.md`, `spec_subtask_2_runtime-wiring.md`, and `spec_subtask_3_validation.md`.
- Keep `spec.md` as the parent overview. Each subtask spec links back to `spec.md`, contains only the scope for that subtask, and includes acceptance criteria, non-goals, dependencies, validation strategy, and the recommended next `bill-feature-implement` prompt.
- Prefer 2-4 subtasks. Each subtask should be small enough for one independent feature-implement run.
- Order subtasks by dependency and identify the first subtask to run.

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
  "has_dedicated_test_task": <bool>
}

For decomposition mode, return this shape instead:

RESULT:
{
  "mode": "decompose",
  "decomposition_reason": "<why this is too large for one reliable feature-implement execution>",
  "parent_spec_path": "<path to spec.md>",
  "recommended_first_subtask_id": 1,
  "subtasks": [
    {
      "id": 1,
      "name": "<short dependency-ordered name>",
      "spec_path": ".feature-specs/{issue_key}-{feature_name}/spec_subtask_1_<slug>.md",
      "depends_on": [],
      "dependency_reason": "<why this comes first, or empty for the first subtask>",
      "scope": "<what this subtask owns>",
      "acceptance_criteria": ["<criterion 1>", "<criterion 2>"],
      "non_goals": ["<explicitly deferred work>"],
      "validation_strategy": "<bill-quality-check or repo-native command>",
      "handoff_prompt": "Run bill-feature-implement on <spec_path>."
    }
  ],
  "presentation_summary": "I split this into N subtasks. We should work on subtask <id> first because <dependency reason>."
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
- Follow standards in `CLAUDE.md`, `AGENTS.md`, and any matching `.agents/skill-overrides.md` section.
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
  ]
}
```

## Quality-check subagent briefing

```
You are the quality-check subagent. Your job is to run the final validation gate and return a structured result.

Feature: {feature_name}
Validation strategy: {validation_strategy}  # 'bill-quality-check' or a repo-native command
Scope: branch diff since main for MEDIUM/LARGE, current unit of work for SMALL.

Instructions:
1. If validation_strategy is `bill-quality-check`, invoke the `bill-quality-check` skill via the Skill tool — DO NOT search the filesystem (no `find`, `grep -r`, etc.) to locate skill files; the Skill tool resolves skills by name. Apply its instructions in the current agent context (do not delegate to another subagent); it auto-routes to the matching stack-specific quality-check skill.
2. Otherwise, run the provided repo-native command.
3. Fix any issues at their root cause. Do not use suppressions unless explicitly allowed by project standards.
4. Call the `quality_check_finished` MCP tool with `orchestrated=true`. Pass all started+finished fields directly (skip `quality_check_started` in orchestrated mode): `routed_skill`, `detected_stack`, `scope_type`, `initial_failure_count`, plus the finished fields.
5. Capture the `telemetry_payload` returned by `quality_check_finished` verbatim.

Return exactly one RESULT: block as your final message, containing valid JSON with this shape:

RESULT:
{
  "validation_result": "pass|fail|skipped",
  "routed_skill": "<skill name or empty>",
  "detected_stack": "<stack or empty>",
  "initial_failure_count": <int>,
  "final_failure_count": <int>,
  "fixes_applied": "<brief summary>",
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

Acceptance criteria (for reference when drafting the PR body):
{numbered_list}

Implementation summary (from Step 4):
{implementation_return_json}

Instructions:
1. Invoke `bill-pr-description` via the Skill tool — DO NOT search the filesystem (no `find`, `grep -r`, etc.) to locate skill files; the Skill tool resolves skills by name. Apply its instructions in the current agent context. Respect repo-native PR templates if present (`.github/pull_request_template.md`, `PULL_REQUEST_TEMPLATE.md`, etc.).
2. Create the PR with `gh pr create` using a HEREDOC for the body.
3. Call the `pr_description_generated` MCP tool with `orchestrated=true` once the PR is created.
4. Capture the `telemetry_payload` returned by `pr_description_generated` verbatim.

Return exactly one RESULT: block as your final message, containing valid JSON with this shape:

RESULT:
{
  "pr_created": <bool>,
  "pr_url": "<url or empty>",
  "pr_title": "<title>",
  "used_repo_template": <bool>,
  "template_path": "<path or empty>",
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

LARGE decomposition runs stop after Step 3, persist the decomposition package as `plan`, write subtask specs, and close with `completion_status: "abandoned_at_planning"` plus `plan_deviation_notes: "decomposed into N subtasks"`. This is an intentional planning-stage terminal state. The next implementation run starts from the first generated subtask spec.

## Error Recovery

For the parsing posture of subagent `RESULT:` blocks (best-effort recovery, single corrective re-spawn, escalation), see the `RESULT:` block parsing tolerance section below. Treat that section as authoritative for any runtime-level malformed-payload handling referenced below.

- **Pre-planning subagent fails** — report the error and ask the user whether to retry, adjust scope, or abandon. If abandoned, call `feature_implement_finished` with `completion_status: "abandoned_at_planning"`.
- **Planning subagent returns an invalid plan** (missing fields, no dedicated test task when testable logic exists, etc.) — respawn it once with a corrective briefing that lists the violations. If it still fails, abandon at planning.
- **Planning subagent returns `mode: "decompose"`** — treat this as a valid terminal planning result. Persist the `plan` artifact, present the subtask order and acceptance criteria, mark later workflow steps skipped, close workflow state as `abandoned` at `plan`, and call `feature_implement_finished` with `completion_status: "abandoned_at_planning"`.
- **Implementation subagent stops early with `stopped_early: true`** — the orchestrator decides: if `plan_deviation_notes` imply a re-plan, respawn the planning subagent with the deviation notes and then a fresh implementation subagent; otherwise, hand to the user.
- **Code-review fix loop exceeds 3 iterations** — stop, report remaining findings, hand to user. Call `feature_implement_finished` with `completion_status: "abandoned_at_review"`.
- **Completeness audit loops exceed 2 iterations** — report remaining gaps, let user decide. Call `feature_implement_finished` accordingly.
- **Quality-check subagent returns `validation_result: "fail"`** — escalate to the user (do not silently commit). If the user abandons, call `feature_implement_finished` with `completion_status: "error"`.
- **PR-description subagent fails to create the PR** — report the error, offer to retry. If abandoned, call `feature_implement_finished` with `completion_status: "error"`.
- **Skill Bill MCP transport closes** — do not treat `Transport closed` as telemetry disabled. First run a lightweight health check such as `feature_implement_workflow_latest`; if the tool path is still closed, call the same Skill Bill tool through the packaged Kotlin `runtime-mcp` stdio binary with a JSON-RPC `tools/call` payload. Use that direct-stdio fallback for owned review telemetry, workflow-state updates, and `feature_implement_finished`. Record the fallback in the current step artifact. If the packaged runtime is unavailable too, report the telemetry failure explicitly and preserve the terminal artifact in the final user summary.
- **Review telemetry import is rejected** — inspect the import error. If the review text is missing required `bill-code-review` metadata such as `Review run ID: <review-run-id>`, re-run or reformat the review output from the actual review result and retry import once. Do not pass a prose-only review summary to `import_review`.

In all early-exit cases, close the telemetry session with the appropriate `completion_status` so the run is not orphaned.

In those same early-exit cases, also close the workflow state with
`feature_implement_workflow_update` so the durable workflow does not remain in
`running`.

## RESULT Block Parsing Tolerance


This artifact records the parsing posture for the JSON `RESULT:` block returned by every `bill-feature-implement` subagent. The orchestrator parses these blocks inline; no machine parser exists in the Kotlin runtime modules. The choice below governs what subagents may emit and how the orchestrator behaves when a subagent's final message deviates from the strict contract.

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
- If the user abandons, call `feature_implement_finished` with an appropriate `completion_status` (typically `error`) and update workflow state to `failed` with the malformed payload preserved in the artifact patch.

## Non-Goals

- Replacing the JSON `RESULT:` contract with a different return shape.
- Adding schema validation in the Kotlin runtime modules (`runtime-core` / `runtime-cli`).
- Changing the per-phase return contracts in the inline reference sections above.
