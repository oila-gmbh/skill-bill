---
name: bill-feature-implement
description: Use when doing end-to-end feature implementation from design doc to verified code. Automatically scales ceremony based on feature size — lightweight for small changes, full orchestration for large features. Runs each heavy phase (pre-planning, planning, implementation, completeness audit, quality check, PR description) inside its own subagent with a rich self-contained briefing, to keep the orchestrator context small. Code review stays in the orchestrator because it already spawns specialist subagents internally. Use when user mentions implement feature, build feature, implement spec, or feature from design doc.
---

# Feature Implement

End-to-end feature implementation from design doc to verified code (spec → plan → implement → review → audit → validate → history → commit → PR). Heavy phases run inside dedicated subagents to keep the orchestrator context small.

## Project Overrides

Follow the shell ceremony in [shell-ceremony.md](shell-ceremony.md).

If `.agents/skill-overrides.md` exists in the project root and contains a matching section, read that section and apply it as the highest-priority instruction for this skill.

## Orchestrator vs subagent split

| Step                                            | Where it runs    | Why                                                                  |
|-------------------------------------------------|------------------|----------------------------------------------------------------------|
| 1 — Collect design doc + assess                 | **Orchestrator** | Requires user interaction                                            |
| 1b — Create feature branch                      | **Orchestrator** | Trivial git op; keeps branch state visible                           |
| 2 — Pre-planning (history, spec save, patterns) | **Subagent**     | Heavy reading; digest is small                                       |
| 3 — Create implementation plan                  | **Subagent**     | Heavy reading; returns structured plan                               |
| 4 — Execute plan                                | **Subagent**     | Biggest context win; many file edits                                 |
| 5 — Code review (`bill-code-review`)            | **Orchestrator** | Already spawns specialist subagents internally — do not nest further |
| 6 — Completeness audit                          | **Subagent**     | Re-reads code + criteria; returns pass/fail digest                   |
| 6b — Quality check (`bill-quality-check`)       | **Subagent**     | Shell-heavy; returns structured result + telemetry payload           |
| 7 — Boundary history (`bill-boundary-history`)  | **Orchestrator** | Small write, git-auditable                                           |
| 8 — Commit and push                             | **Orchestrator** | Git ops must stay visible                                            |
| 9 — PR description (`bill-pr-description`)      | **Subagent**     | Reads branch diff; returns PR URL + telemetry payload                |

Subagents run **sequentially**, in the **same worktree** (no `isolation: "worktree"`). Do not launch any of these subagents in parallel.

Each subagent receives a **self-contained briefing** (see [reference.md](reference.md) for the per-phase briefing templates and structured return contracts). A subagent must not re-read the raw spec — it operates on what the orchestrator hands it. Its return value is a structured object the orchestrator can consume without re-parsing prose.

## Workflow State

This skill is the first runtime workflow-state pilot. In addition to the
top-level telemetry tools, the orchestrator must persist durable workflow
state with these MCP tools:

- `feature_implement_workflow_open`
- `feature_implement_workflow_update`
- `feature_implement_workflow_get`

Workflow-state rules:

- Open workflow state once, immediately after Step 1 is confirmed.
- Save both ids:
  - `session_id` from `feature_implement_started` for telemetry
  - `workflow_id` from `feature_implement_workflow_open` for durable state
- Maintain a local `child_steps` list for orchestrated child telemetry exactly
  as before.
- After every major phase boundary, call `feature_implement_workflow_update`
  with:
  - the new `workflow_status`
  - the new `current_step_id`
  - `step_updates` for the steps whose status changed
  - `artifacts_patch` for the structured artifact produced by that phase
- Workflow state is independent of telemetry settings. Persist it even when
  `feature_implement_started` or `feature_implement_finished` returns
  `status: skipped`.
- Follow the artifact names and phase-to-artifact mapping in
  [reference.md](reference.md). Do not invent prose-only handoffs when a
  structured artifact exists.

## Continuation Mode

When an external caller re-enters this skill using the payload returned by
`feature_implement_workflow_continue`, treat that payload as the authoritative
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
  work backwards (for example review back to implement, or audit back to plan).
- After the resumed step completes, continue the normal sequence defined below.
- If `continue_status` is `done`, do not rerun the workflow; summarize the
  terminal state instead.
- If `continue_status` is `blocked`, stop and restore the missing artifacts
  named by the workflow payload before continuing.

## Step 1: Collect Design Doc + Assess Size (orchestrator)

Ask the user for:
1. **Feature design doc** — inline text, file path, or directory of spec files
2. **Issue key** (e.g., `ME-5066`, `SKILL-10`) — **required**. The issue key prefixes the branch name, spec directory, and commit message. If the user has no issue yet, stop and ask them to create one before continuing; do not invent a placeholder.

Accept PDFs (read in page ranges if >10 pages), markdown, images. If a directory, read all files and synthesize. If spec exceeds ~8,000 words, ask which sections matter most.

### Single-Pass Assessment

Present everything together in one pass:
1. **Acceptance criteria** — numbered list
2. **Non-goals** — things explicitly out of scope
3. **Open questions** — unresolved decisions (if any)
4. **Feature size** — SMALL / MEDIUM / LARGE
5. **Feature name** inferred from spec
6. **Rollout need** — N/A unless spec/user/repo requires guarded rollout

Then ask: **Confirm or adjust the above before I plan.** Open questions must be resolved before proceeding. The confirmed criteria are the **contract** for the completeness audit and for every subagent briefing from Step 2 onward.

### Telemetry: Record Started

After the user confirms the assessment, call the `feature_implement_started` MCP tool with:
- `feature_size`, `acceptance_criteria_count`, `open_questions_count`
- `spec_input_types`: list of input types the user provided (`raw_text`, `pdf`, `markdown_file`, `image`, `directory`)
- `spec_word_count`: approximate word count of the design spec
- `rollout_needed`: whether a feature flag / guarded rollout is needed
- `feature_name`: the inferred feature name
- `issue_key`: the issue key the user provided (required — Step 1 does not proceed without it)
- `issue_key_type`: `jira`, `linear`, `github`, or `other`
- `spec_summary`: one sentence summarizing what the feature does

Save the returned `session_id` for `feature_implement_finished`. If the tool returns `status: skipped`, continue normally.

### Workflow State: Open

After Step 1 is confirmed:

1. Call `feature_implement_workflow_open` with:
   - `session_id`: the `session_id` from `feature_implement_started`
   - `current_step_id`: `assess`
2. Save the returned `workflow_id`.
3. Immediately call `feature_implement_workflow_update` to:
   - mark `assess` as completed
   - set `create_branch` to running
   - persist the `assessment` artifact

The `assessment` artifact must include:
- acceptance criteria
- non-goals
- open questions
- feature size
- feature name
- rollout need

## Step 1b: Create Feature Branch (orchestrator)

`git checkout -b feat/{ISSUE_KEY}-{feature-name}`

After the branch is created, call `feature_implement_workflow_update` to:
- mark `create_branch` as completed
- set `preplan` to running
- persist a `branch` artifact with the branch name

## Step 2: Pre-Planning (subagent)

Spawn a subagent with the pre-planning briefing defined in [reference.md](reference.md) under "Pre-planning subagent briefing". The briefing includes: acceptance criteria, non-goals, issue key, feature name, spec content (or saved spec path for MEDIUM/LARGE), feature size, expected affected boundaries (if known), rollout need, and explicit instructions to:

- Read `agent/history.md` in each boundary the feature is likely to touch (newest first; stop when no longer relevant).
- Read `agent/decisions.md` header lines in each boundary and only open full entries when titles look relevant.
- For MEDIUM/LARGE, save the spec to `.feature-specs/{ISSUE_KEY}-{feature-name}/spec.md` with status `In Progress`.
- Read `CLAUDE.md`, `AGENTS.md`, and the matching `bill-feature-implement` section in `.agents/skill-overrides.md` when present.
- Discover codebase patterns: similar features referenced in the spec, build/runtime dependencies for affected boundaries, reusable components.
- When `kmp` signals dominate, resolve governed add-ons only after stack routing. Start from `Selected add-ons: none`. Let the routed pack own add-on detection and selection, then scan the matching pack-owned add-on supporting files' `## Section index` headings first. If the add-on is split into topic files, open only the linked topic files whose cues match the work during pre-planning / pattern discovery.
- Confirm `bill-quality-check` can route this repo; if not, pick a repo-native validation command.
- If the rollout uses a feature flag, read `bill-feature-guard` inline and choose a pattern (Legacy / DI Switch / Simple Conditional).

The subagent returns the **pre-planning return contract** (see reference.md), including any selected governed add-ons as metadata. The orchestrator keeps this digest in context and passes it to later subagents — the raw findings stay in the subagent.

After the subagent returns, call `feature_implement_workflow_update` to:
- mark `preplan` as completed
- set `plan` to running
- persist the `preplan_digest` artifact

## Step 3: Create Implementation Plan (subagent)

Spawn a subagent with the planning briefing defined in [reference.md](reference.md) under "Planning subagent briefing". The briefing includes: acceptance criteria, non-goals, feature size, pre-planning digest (from Step 2), rollout info, feature-flag pattern (if any), and validation strategy.

The subagent returns the **planning return contract**: an ordered task list, each task with description, files to create/modify, which acceptance criteria it satisfies, and test coverage (or `None` when deferred to the final test task). For MEDIUM/LARGE with >15 tasks, the plan must be split into phases with checkpoints.

If the plan includes testable logic, the **final task must be a dedicated test task**. The subagent is responsible for enforcing this rule in the plan it returns.

The orchestrator presents the plan, then proceeds to implementation — the plan is not a second approval gate.

After the plan is accepted for execution, call `feature_implement_workflow_update` to:
- mark `plan` as completed
- set `implement` to running
- persist the `plan` artifact

## Step 4: Execute Plan (subagent)

Spawn a subagent with the implementation briefing defined in [reference.md](reference.md) under "Implementation subagent briefing". The briefing includes: acceptance criteria, plan (from Step 3), pre-planning digest (from Step 2), rollout info, feature-flag pattern (if any), spec path (for MEDIUM/LARGE), and execution rules (project standards, test gate, orphan cleanup, catalog updates for agent-config changes).

The subagent executes the plan atomically (one task per turn), prints per-task progress, writes tests as specified, and stops to re-plan if a task reveals the plan is wrong. On stop-and-re-plan, the subagent returns with `plan_deviation_notes` populated so the orchestrator can decide whether to re-spawn the planning subagent.

The subagent returns the **implementation return contract**: `files_created`, `files_modified`, `tasks_completed`, `plan_deviation_notes`, `tests_written`, `notes_for_review`.

For MEDIUM/LARGE, the subagent performs the **post-implementation compact** internally before returning: summarize files, feature flag info, criteria-to-file mapping, deviations; then re-read the saved spec to verify every criterion is mapped.

After the subagent returns, call `feature_implement_workflow_update` to:
- mark `implement` as completed
- set `review` to running
- persist the `implementation_summary` artifact

## Step 5: Code Review (orchestrator)

Run `bill-code-review` **inline in the orchestrator** (read its skill file and apply inline). Scope: current unit of work for SMALL, branch diff for MEDIUM/LARGE. Do not wrap `bill-code-review` in an additional subagent — it already spawns specialist subagents internally.

**Review loop:** Auto-fix Blocker and Major findings by spawning the implementation subagent again with a fix briefing (acceptance criteria + list of findings + pointer to the current diff + instruction to fix only those findings). Before respawning, capture the exact diff pointer the review was run against — the branch name, commit range (e.g. `main..HEAD`), or explicit file list — and pass it as `{branch_or_commit_range}` in the fix briefing so the subagent knows which diff "the findings" refer to. Re-run review. Continue past Minor-only findings. Max **3 iterations**. Do not pause to ask the user which finding to fix.

**Orchestrated child telemetry:** when this workflow invokes `import_review` and `triage_findings` for the review it owns, pass `orchestrated=true` to both tools. Collect the `telemetry_payload` returned by `triage_findings` (or by `import_review` when the review has no findings) and append it to the local `child_steps` list. The review will not emit `skillbill_review_finished` on its own — its payload will be embedded in the `skillbill_feature_implement_finished` event instead.

Workflow-state rules for review:

- After each review pass, persist a `review_result` artifact containing:
  - the diff pointer reviewed
  - prioritized findings summary
  - current review iteration count
- If the review loop sends work back to implementation:
  - update workflow state so `review` is no longer running
  - set `implement` back to running
  - increment the `implement` step's `attempt_count`
- Once review is complete, call `feature_implement_workflow_update` to:
  - mark `review` as completed
  - set `audit` to running

## Step 6: Completeness Audit (subagent)

Spawn a subagent with the audit briefing defined in [reference.md](reference.md) under "Completeness audit subagent briefing". The briefing includes: acceptance criteria, implementation return contract (from Step 4), and — for MEDIUM/LARGE — a pointer to the branch diff.

**SMALL:** the subagent returns a quick confirmation for each criterion.
**MEDIUM/LARGE:** the subagent returns a full per-criterion report with evidence paths.

The subagent returns the **audit return contract**: `pass: bool`, `per_criterion: [...]`, `gaps: [...]`.

If gaps found: the orchestrator respawns the planning subagent with the gaps, then the implementation subagent, then re-runs code review, then re-spawns the audit subagent. Max **2 audit iterations**. When complete, the orchestrator updates the saved spec status to **Complete** (MEDIUM/LARGE only).

Workflow-state rules for audit:

- After each audit pass, persist the `audit_report` artifact.
- If the audit finds gaps and loops back:
  - mark `audit` as no longer running
  - set `plan` back to running
  - increment the `plan` step's `attempt_count`
- Once the audit passes, call `feature_implement_workflow_update` to:
  - mark `audit` as completed
  - set `validate` to running

## Finalization sequence (Steps 6b → 9)

Once the audit passes, run Steps 6b through 9 as a **continuous sequence without pausing**. The only reason to stop is if a step fails.

### Step 6b: Final Validation Gate (subagent)

Spawn a subagent with the quality-check briefing defined in [reference.md](reference.md) under "Quality-check subagent briefing". The subagent runs `bill-quality-check` (which auto-routes to the matching stack quality-check skill), fixes any issues at their root without using suppressions, and **must call `quality_check_finished` with `orchestrated=true`** itself. The subagent returns: `validation_result`, `routed_skill`, `detected_stack`, `initial_failure_count`, `final_failure_count`, and the `telemetry_payload` returned by `quality_check_finished`.

If `bill-quality-check` reports no supported stack for the affected repo, the subagent falls back to the closest existing repo-native validation command.

The orchestrator appends the returned `telemetry_payload` to the `child_steps` list.

After validation finishes, call `feature_implement_workflow_update` to:
- mark `validate` as completed
- set `write_history` to running
- persist the `validation_result` artifact

### Step 7: Write Boundary History (orchestrator)

Run `bill-boundary-history` inline in the orchestrator (read its skill file and apply inline). The skill owns write/skip rules and entry format.

After Step 7, call `feature_implement_workflow_update` to:
- mark `write_history` as completed
- set `commit_push` to running
- persist the `history_result` artifact

### Step 8: Commit and Push (orchestrator)

1. Stage all new and modified files from this feature (do not use `git add -A`)
2. Commit with message format: `feat: <concise description>` (omit the issue key — the branch name already carries it)
3. Push the branch to the remote with `-u` to set upstream tracking

After push succeeds, call `feature_implement_workflow_update` to:
- mark `commit_push` as completed
- set `pr_description` to running

### Step 9: Generate PR Description (subagent)

Spawn a subagent with the PR-description briefing defined in [reference.md](reference.md) under "PR-description subagent briefing". The subagent runs `bill-pr-description` (read its skill file and apply inline), creates the PR, and **must call `pr_description_generated` with `orchestrated=true`** itself. The subagent returns: PR URL, PR title, and the `telemetry_payload` returned by `pr_description_generated`.

The orchestrator appends the returned `telemetry_payload` to the `child_steps` list.

After the PR step finishes, call `feature_implement_workflow_update` to:
- mark `pr_description` as completed
- set `finish` to running
- persist the `pr_result` artifact

### Telemetry: Record Finished

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

Before or immediately after `feature_implement_finished`, call
`feature_implement_workflow_update` one final time to:
- mark `finish` as completed for successful runs
- set `workflow_status` to `completed`, `abandoned`, or `failed`
- keep `current_step_id` at the step where the workflow stopped
- persist any final artifact patch needed to explain the terminal state

## Reference

For briefing templates, return-contract schemas, size reference, error recovery, and skills invoked, see [reference.md](reference.md).
