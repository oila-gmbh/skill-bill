---
name: bill-feature-implement-agentic
description: Agentic feature orchestrator that runs inline and delegates heavy stages to subagents for context isolation and token savings. Each subagent loads the respective portable skill. Works across all agent runtimes that support subagent spawning (Claude Code, GLM, Codex). Use when implementing features and you want the agent-delegated pipeline instead of the standard inline flow.
---

# Agentic Feature Implement Orchestrator

## Project Overrides

If `.agents/skill-overrides.md` exists in the project root and contains a `## bill-feature-implement-agentic` section, read that section and apply it as the highest-priority instruction for this skill.

If an `AGENTS.md` file exists in the project root, apply it as project-wide guidance.

## Overview

This skill runs inline in the main thread and selectively delegates heavy stages to subagents for context isolation. It follows the same workflow as `bill-feature-implement` but uses subagents for plan, implement, code review, quality-check, resolve, and finalize stages to keep context lean and save tokens.

Each subagent loads and follows the respective portable skill as its primary rubric. This keeps the orchestrator runtime-agnostic — it works on any runtime that supports subagent spawning (Claude Code Task/Agent, GLM Task, Codex subagents).

## Subagent Delegation Model

When spawning a subagent, always include:
- The skill to load: tell the subagent to read the specified `SKILL.md` and follow it as the primary rubric
- The handoff context specific to the stage
- Relevant `AGENTS.md` and `.agents/skill-overrides.md` guidance
- The instruction to return a structured summary, not raw output

If a subagent is interrupted or fails, retry once. If it fails again, run the stage inline in this thread instead.

## Step 1: Spec and assessment

Collect the feature specification from the user. Extract:
- Numbered acceptance criteria
- Non-goals (out of scope)
- Open questions
- Size classification: SMALL (< 5 files), MEDIUM (5-15 files), LARGE (> 15 files)
- Feature name and issue key (if provided)

Clarify any ambiguities. Get user confirmation before proceeding.

After confirmation, call the `feature_implement_started` MCP tool with:
- `feature_size`, `acceptance_criteria_count`, `open_questions_count`
- `spec_input_types`: list of input types (`raw_text`, `pdf`, `markdown_file`, `image`, `directory`)
- `spec_word_count`: approximate word count of the spec
- `rollout_needed`: whether a feature flag / guarded rollout is needed
- `feature_name`, `issue_key`
- `issue_key_type`: `jira`, `linear`, `github`, `other`, or `none`
- `spec_summary`: one sentence summarizing the feature

Save the returned `session_id` for the finished event. If the tool returns `status: skipped`, continue normally.

Create the feature branch: `git checkout -b feat/{ISSUE_KEY}-{feature-name}`

## Step 2: Plan (subagent)

Spawn a subagent with this handoff:
- **Skill to load**: read `bill-feature-implement` skill's `reference.md` — follow the Pre-Planning Details and Planning Rules sections
- The full feature spec
- Acceptance criteria (numbered)
- Size classification
- Issue key
- Project conventions from `CLAUDE.md` / `AGENTS.md`

The subagent should explore the codebase (boundary history, boundary decisions, codebase patterns) and return:
- Pre-planning context (conventions, history, patterns found)
- Implementation plan (tasks with file targets, rationale, and criteria mapping)
- Risks and open questions

Present the plan to the user for approval before continuing.

## Step 3: Implement (subagent)

Spawn a subagent with this handoff:
- **Skill to load**: follow the Execution Rules from `bill-feature-implement` skill's `reference.md`
- The approved plan (tasks, file targets, rationale per step, criteria mapping)
- Key project conventions from the plan's pre-planning context

The subagent returns:
- Changes made per task with criteria mapping
- Decisions made during implementation
- Acceptance criteria coverage mapping

## Step 4: Completeness audit

Compare the implement summary's criteria coverage against the original acceptance criteria. If any criteria are not covered:
- List the gaps
- Spawn a subagent with: the missing criteria, files already modified, and relevant plan context
- Repeat review after re-implementation

Max 2 audit iterations.

## Step 5: Review (subagent or inline)

The code review skill triggers deep nested delegation (stack routing, specialist subagents). This works on runtimes that support nested subagent spawning (e.g. Claude Code). On runtimes where nested delegation is unavailable or hits runtime limits, run this step inline in the current thread instead of spawning a subagent.

When delegating, spawn a subagent with this handoff:
- **Skill to load**: read `bill-code-review` skill's `SKILL.md` and follow it as the primary rubric
- The branch diff (scope the review to the branch diff)
- Decisions made during implementation (so the reviewer understands intent)
- Key project conventions

The subagent runs the full code review flow — stack detection, routing, specialist delegation — and returns findings with severity, file paths, and descriptions.

After the review output is produced, call the `import_review` MCP tool with the complete review text (Sections 1 through 4). After findings are resolved (via the resolve subagent or skipped), call the `triage_findings` MCP tool with the `review_run_id` and decisions. When the review produced no findings, still call `triage_findings` with an empty decisions list to close the review lifecycle.

## Step 6: Resolve (subagent)

If there are Blocker or Major findings, spawn a subagent with:
- All findings including their context and suggested fixes
- The instruction to apply the minimal correct fix for each finding

The subagent returns: which findings were fixed and which were skipped.

If fixes were applied, spawn a new review subagent (Step 5) to verify. Max 3 review iterations.

Continue past Minor-only findings.

## Step 7: Quality check (subagent)

Spawn a subagent with this handoff:
- **Skill to load**: read `bill-quality-check` skill's `SKILL.md` and follow it as the primary rubric
- The repo path and changed files

The subagent returns pass or fail.

If it fails, spawn an implement subagent with the failures to fix, then re-run quality check. Max 2 quality iterations.

## Step 8: Finalize (subagent)

Spawn a subagent with this handoff:
- **Skill to load**: follow the Finalization Steps from `bill-feature-implement` skill's `reference.md`
- Feature name and issue key
- Acceptance criteria
- Summary of all changes made across all implementation passes
- Branch name

The subagent:
1. Writes boundary history using `bill-boundary-history`
2. Stages and commits with format `feat: [<ISSUE_KEY>] <concise description>`
3. Pushes with `-u` to set upstream tracking
4. Generates PR description using `bill-pr-description`

Returns: whether history was written, the commit hash, and the PR description output.

## Telemetry: Record Finished

After finalize completes (or when the workflow ends early due to error or user abandonment), call the `feature_implement_finished` MCP tool with:
- `session_id`: from `feature_implement_started`
- `completion_status`: `completed` if PR was created, otherwise `abandoned_at_planning`, `abandoned_at_implementation`, `abandoned_at_review`, or `error`
- `plan_correction_count`, `plan_task_count`, `plan_phase_count`
- `feature_flag_used`, `feature_flag_pattern` (`simple_conditional`, `di_switch`, `legacy`, or `none`)
- `files_created`, `files_modified`, `tasks_completed`
- `review_iterations`, `audit_result` (`all_pass`, `had_gaps`, or `skipped`), `audit_iterations`
- `validation_result` (`pass`, `fail`, or `skipped`), `boundary_history_written`, `pr_created`
- `boundary_history_value` (`none`, `irrelevant`, `low`, `medium`, or `high`)
- `plan_deviation_notes`: brief note if plan changed (empty if no deviations)

For fields not yet reached (early exit), use: 0 for counts, `skipped` for results, false for booleans.

## Handoff principles

- Each subagent starts with a clean context. Pass only what it needs for its stage.
- Always include rationale and decisions context — not just file lists. This prevents the next subagent from misunderstanding intentional choices.
- Hold subagent summaries in your context, not their raw outputs.
- When re-running a stage (audit loop, review loop), include what changed since the last run so the subagent can focus on the delta.
- If a delegated stage is interrupted twice, continue inline instead of retrying the subagent chain.
