# Feature Implement Content

This file is the author-owned execution body for `bill-feature-implement`. The shell contract, workflow-state contract, continuation contract, stable step ids, stable artifact names, and telemetry ownership remain in [SKILL.md](SKILL.md).

## Step 1: Collect Design Doc + Assess Size (orchestrator)

Ask the user for:
1. **Feature design doc** - inline text, file path, or directory of spec files
2. **Issue key** (for example `ME-5066`, `SKILL-10`) - required. The issue key prefixes the branch name, spec directory, and commit message. If the user has no issue yet, stop and ask them to create one before continuing; do not invent a placeholder.

Accept PDFs (read in page ranges if >10 pages), markdown, images. If a directory, read all files and synthesize. If spec exceeds about 8,000 words, ask which sections matter most.

### Single-Pass Assessment

Present everything together in one pass:
1. **Acceptance criteria** - numbered list
2. **Non-goals** - things explicitly out of scope
3. **Open questions** - unresolved decisions (if any)
4. **Feature size** - SMALL / MEDIUM / LARGE
5. **Feature name** inferred from spec
6. **Rollout need** - N/A unless spec, user, or repo requires guarded rollout

Then ask: **Confirm or adjust the above before I plan.** Open questions must be resolved before proceeding. The confirmed criteria are the contract for the completeness audit and for every subagent briefing from Step 2 onward.

After the assessment is confirmed, follow the Step 1 transition contract in [SKILL.md](SKILL.md#step-1-collect-design-doc--assess-size-orchestrator) and the workflow-state contract in [SKILL.md](SKILL.md#workflow-state).

## Step 1b: Create Feature Branch (orchestrator)

Create the feature branch with:

`git checkout -b feat/{ISSUE_KEY}-{feature-name}`

After the branch is created, follow the Step 1b workflow-state update contract in [SKILL.md](SKILL.md#step-1b-create-feature-branch-orchestrator).

## Step 2: Pre-Planning (subagent)

Spawn a subagent with the pre-planning briefing defined in [reference.md](reference.md) under `Pre-planning subagent briefing`. The briefing includes: acceptance criteria, non-goals, issue key, feature name, spec content (or saved spec path for MEDIUM/LARGE), feature size, expected affected boundaries (if known), rollout need, and explicit instructions to:

- Read `agent/history.md` in each boundary the feature is likely to touch (newest first; stop when no longer relevant).
- Read `agent/decisions.md` header lines in each boundary and only open full entries when titles look relevant.
- For MEDIUM/LARGE, save the spec to `.feature-specs/{ISSUE_KEY}-{feature-name}/spec.md` with status `In Progress`.
- Read `CLAUDE.md`, `AGENTS.md`, and the matching `bill-feature-implement` section in `.agents/skill-overrides.md` when present.
- Discover codebase patterns: similar features referenced in the spec, build/runtime dependencies for affected boundaries, reusable components.
- When `kmp` signals dominate, resolve governed add-ons only after stack routing settles on `kmp`. Start from `Selected add-ons: none`. Let the routed pack own add-on detection and selection, then scan the matching pack-owned add-on supporting files' `## Section index` headings first. If the add-on is split into topic files, open only the linked topic files whose cues match the work during pre-planning and pattern discovery.
- Confirm `bill-quality-check` can route this repo; if not, pick a repo-native validation command.
- If the rollout uses a feature flag, read `bill-feature-guard` inline and choose a pattern (Legacy / DI Switch / Simple Conditional).

The subagent returns the pre-planning return contract from [reference.md](reference.md). The orchestrator keeps this digest in context and passes it to later subagents - the raw findings stay in the subagent.

## Step 3: Create Implementation Plan (subagent)

Spawn a subagent with the planning briefing defined in [reference.md](reference.md) under `Planning subagent briefing`. The briefing includes: acceptance criteria, non-goals, feature size, pre-planning digest (from Step 2), rollout info, feature-flag pattern (if any), and validation strategy.

The subagent returns the planning return contract: an ordered task list, each task with description, files to create or modify, which acceptance criteria it satisfies, and test coverage (or `None` when deferred to the final test task). For MEDIUM/LARGE with more than 15 tasks, the plan must be split into phases with checkpoints.

If the plan includes testable logic, the final task must be a dedicated test task. The subagent is responsible for enforcing this rule in the plan it returns.

The orchestrator presents the plan, then proceeds to implementation - the plan is not a second approval gate.

## Step 4: Execute Plan (subagent)

Spawn a subagent with the implementation briefing defined in [reference.md](reference.md) under `Implementation subagent briefing`. The briefing includes: acceptance criteria, plan (from Step 3), pre-planning digest (from Step 2), rollout info, feature-flag pattern (if any), spec path (for MEDIUM/LARGE), and execution rules (project standards, test gate, orphan cleanup, catalog updates for agent-config changes).

The subagent executes the plan atomically (one task per turn), prints per-task progress, writes tests as specified, and stops to re-plan if a task reveals the plan is wrong. On stop-and-re-plan, the subagent returns with `plan_deviation_notes` populated so the orchestrator can decide whether to re-spawn the planning subagent.

The subagent returns the implementation return contract: `files_created`, `files_modified`, `tasks_completed`, `plan_deviation_notes`, `tests_written`, `notes_for_review`.

For MEDIUM/LARGE, the subagent performs the post-implementation compact internally before returning: summarize files, feature flag info, criteria-to-file mapping, deviations; then re-read the saved spec to verify every criterion is mapped.

## Step 5: Code Review (orchestrator)

Run `bill-code-review` inline in the orchestrator. Read its skill file and apply it inline. Scope: current unit of work for SMALL, branch diff for MEDIUM/LARGE. Do not wrap `bill-code-review` in an additional subagent - it already spawns specialist subagents internally.

Review loop:

- Auto-fix Blocker and Major findings by spawning the implementation subagent again with a fix briefing (acceptance criteria + list of findings + pointer to the current diff + instruction to fix only those findings).
- Before respawning, capture the exact diff pointer the review was run against - the branch name, commit range (for example `main..HEAD`), or explicit file list - and pass it as `{branch_or_commit_range}` in the fix briefing so the subagent knows which diff the findings refer to.
- Re-run review.
- Continue past Minor-only findings.
- Max 3 iterations.
- Do not pause to ask the user which finding to fix.

Orchestrated child telemetry:

- When this workflow invokes `import_review` and `triage_findings` for the review it owns, pass `orchestrated=true` to both tools.
- Collect the `telemetry_payload` returned by `triage_findings` (or by `import_review` when the review has no findings) and append it to the local `child_steps` list.
- The review will not emit `skillbill_review_finished` on its own - its payload will be embedded in the `skillbill_feature_implement_finished` event instead.

## Step 6: Completeness Audit (subagent)

Spawn a subagent with the audit briefing defined in [reference.md](reference.md) under `Completeness audit subagent briefing`. The briefing includes: acceptance criteria, implementation return contract (from Step 4), and - for MEDIUM/LARGE - a pointer to the branch diff.

SMALL: the subagent returns a quick confirmation for each criterion.

MEDIUM/LARGE: the subagent returns a full per-criterion report with evidence paths.

The subagent returns the audit return contract: `pass: bool`, `per_criterion: [...]`, `gaps: [...]`.

If gaps are found: the orchestrator respawns the planning subagent with the gaps, then the implementation subagent, then re-runs code review, then re-spawns the audit subagent. Max 2 audit iterations. When complete, the orchestrator updates the saved spec status to `Complete` (MEDIUM/LARGE only).

## Finalization sequence (Steps 6b -> 9)

Once the audit passes, run Steps 6b through 9 as a continuous sequence without pausing. The only reason to stop is if a step fails.

### Step 6b: Final Validation Gate (subagent)

Spawn a subagent with the quality-check briefing defined in [reference.md](reference.md) under `Quality-check subagent briefing`. The subagent runs `bill-quality-check` (which auto-routes to the matching stack quality-check skill), fixes any issues at their root without using suppressions, and must call `quality_check_finished` with `orchestrated=true` itself. The subagent returns: `validation_result`, `routed_skill`, `detected_stack`, `initial_failure_count`, `final_failure_count`, and the `telemetry_payload` returned by `quality_check_finished`.

If `bill-quality-check` reports no supported stack for the affected repo, the subagent falls back to the closest existing repo-native validation command.

The orchestrator appends the returned `telemetry_payload` to the `child_steps` list.

### Step 7: Write Boundary History (orchestrator)

Run `bill-boundary-history` inline in the orchestrator. Read its skill file and apply it inline. The skill owns write/skip rules and entry format.

### Step 8: Commit and Push (orchestrator)

1. Stage all new and modified files from this feature (do not use `git add -A`)
2. Commit with message format: `feat: <concise description>` (omit the issue key - the branch name already carries it)
3. Push the branch to the remote with `-u` to set upstream tracking

### Step 9: Generate PR Description (subagent)

Spawn a subagent with the PR-description briefing defined in [reference.md](reference.md) under `PR-description subagent briefing`. The subagent runs `bill-pr-description` (read its skill file and apply inline), creates the PR, and must call `pr_description_generated` with `orchestrated=true` itself. The subagent returns: PR URL, PR title, and the `telemetry_payload` returned by `pr_description_generated`.

The orchestrator appends the returned `telemetry_payload` to the `child_steps` list.
