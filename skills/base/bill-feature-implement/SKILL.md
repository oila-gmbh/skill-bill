---
name: bill-feature-implement
description: Use when doing end-to-end feature implementation from design doc to verified code. Automatically scales ceremony based on feature size — lightweight for small changes, full orchestration for large features. Collects design spec, plans, implements, reviews, and audits completeness. Use when user mentions implement feature, build feature, implement spec, or feature from design doc.
---

# Feature Implement v2

## Project Overrides

If `.agents/skill-overrides.md` exists in the project root and contains a `## bill-feature-implement` section, read that section and apply it as the highest-priority instruction for this skill. The matching section may refine or replace parts of the default workflow below.

If an `AGENTS.md` file exists in the project root, apply it as project-wide guidance.

Precedence for this skill: matching `.agents/skill-overrides.md` section > `AGENTS.md` > built-in defaults. When you read another skill inline, also apply that skill's matching section from `.agents/skill-overrides.md` when present.

## Step 1: Collect Design Doc + Assess Size

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

Then ask: **Confirm or adjust the above before I plan.** Open questions must be resolved before proceeding. The confirmed criteria are the **contract** for the completeness audit.

### Telemetry: Record Started

After the user confirms the assessment, call the `feature_implement_started` MCP tool with:
- `feature_size`, `acceptance_criteria_count`, `open_questions_count`
- `spec_input_types`: list of input types the user provided (`raw_text`, `pdf`, `markdown_file`, `image`, `directory`)
- `spec_word_count`: approximate word count of the design spec
- `rollout_needed`: whether a feature flag / guarded rollout is needed
- `feature_name`: the inferred feature name
- `issue_key`: the issue key the user provided (required — Step 1 does not proceed without it)
- `issue_key_type`: `jira` (e.g. ME-5066), `linear` (e.g. LIN-123, SKILL-10, or UUID), `github` (e.g. #42), or `other`
- `spec_summary`: one sentence summarizing what the feature does

Save the returned `session_id` for `feature_implement_finished`. If the tool returns `status: skipped`, continue normally.

## Step 1b: Create Feature Branch

After confirmation: `git checkout -b feat/{ISSUE_KEY}-{feature-name}`

## Step 2: Pre-Planning

**All sizes:** Read Boundary History if history files exist, determine final validation strategy.
**MEDIUM and LARGE only:** Also Save Spec, discover codebase patterns, Feature Flag Setup if needed.

For detailed pre-planning instructions, see [reference.md](reference.md).

## Step 3: Create Implementation Plan

For planning rules, format, and task structure, see [reference.md](reference.md).

Present the plan, then proceed to implementation — the plan is not a second approval gate.

## Step 4: Execute Plan

For detailed execution rules, see [reference.md](reference.md).

## Step 5: Code Review

Run `bill-code-review` (read its skill file and apply inline). Scope: current unit of work for SMALL, branch diff for MEDIUM/LARGE.

**Review loop:** Auto-fix Blocker and Major findings, re-run review. Continue past Minor-only findings. Max **3 iterations**. Do not pause to ask the user which finding to fix.

**Orchestrated child telemetry:** when this workflow invokes `import_review` and `triage_findings` for the review it owns, pass `orchestrated=true` to both tools. Collect the `telemetry_payload` returned by `triage_findings` (or by `import_review` when the review has no findings) and append it to the local `child_steps` list. The review will not emit `skillbill_review_finished` on its own — its payload will be embedded in the `skillbill_feature_implement_finished` event instead.

## Step 6: Completeness Audit

**SMALL:** Quick confirmation that all acceptance criteria are satisfied.
**MEDIUM and LARGE:** Full per-criterion verification against actual code.

If gaps found: plan → implement → review → re-audit. Max **2 audit iterations**. When complete, update spec status to **Complete** (MEDIUM/LARGE only).

## Finalization sequence (Steps 6b → 9)

Once completeness audit passes, run Steps 6b through 9 as a **continuous sequence without pausing**. The only reason to stop is if a step fails.

For detailed finalization steps (validation gate, boundary history, commit/push, PR description), see [reference.md](reference.md).

### Telemetry: Record Finished

After the PR is created (or when the workflow ends early due to error or user abandonment), call the `feature_implement_finished` MCP tool with:
- `session_id`: from `feature_implement_started`
- `completion_status`: `completed` if PR was created, otherwise `abandoned_at_planning`, `abandoned_at_implementation`, `abandoned_at_review`, or `error`
- `plan_correction_count`: how many times the user corrected the assessment or plan (0 if confirmed without changes)
- `plan_task_count`, `plan_phase_count`
- `feature_flag_used`, `feature_flag_pattern` (`simple_conditional`, `di_switch`, `legacy`, or `none`)
- `files_created`, `files_modified`, `tasks_completed`
- `review_iterations`, `audit_result` (`all_pass`, `had_gaps`, or `skipped`), `audit_iterations`
- `validation_result` (`pass`, `fail`, or `skipped`), `boundary_history_written`, `pr_created`
- `boundary_history_value`: how useful the boundary history was during pre-planning (`none` if no history existed, `irrelevant`, `low`, `medium`, or `high`)
- `plan_deviation_notes`: brief note if the plan changed during execution (empty if no deviations)
- `child_steps`: list of `telemetry_payload` dicts collected from child tools invoked with `orchestrated=true` during the session. Each entry was returned by a child MCP tool call (see the orchestration contract below). Pass an empty list if no orchestrated child calls were made.

For fields not yet reached (early exit), use: 0 for counts, `skipped` for results, false for booleans.

### Orchestration contract

This skill acts as the parent for code review, quality checks, and PR description generation. When invoking those child workflows, pass `orchestrated=true` to their MCP tools and collect each returned `telemetry_payload`. Append those payloads to a running `child_steps` list and pass the list to `feature_implement_finished`. The children will not emit their own telemetry events — the full workflow produces exactly one `skillbill_feature_implement_finished` event that carries the `child_steps` inside.

Tools to call with `orchestrated=true` during this workflow:
- `import_review` and `triage_findings` (when the code-review step runs)
- `quality_check_finished` (skip `quality_check_started` in orchestrated mode; pass all started+finished fields directly to `quality_check_finished`)
- `feature_verify_finished` (if feature-verify is invoked)
- `pr_description_generated`

If a child skill is invoked but `orchestrated=true` is not passed, that child will emit its own standalone event — graceful degradation, but produces an extra event the workflow did not intend. Always pass the flag.

## Reference

For size reference table, error recovery, skills invoked, and all detailed substep instructions, see [reference.md](reference.md).
