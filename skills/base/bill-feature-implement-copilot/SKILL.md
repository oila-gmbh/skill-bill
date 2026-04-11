---
name: bill-feature-implement-copilot
description: Copilot-optimized feature orchestrator that runs inline and delegates heavy stages to sub-agents for context isolation and token savings. Use when implementing features on Copilot and you want the agent-delegated pipeline instead of the standard inline flow.
---

# Copilot Feature Implement Orchestrator

## Project Overrides

If `.agents/skill-overrides.md` exists in the project root and contains a `## bill-feature-implement-copilot` section, read that section and apply it as the highest-priority instruction for this skill.

If an `AGENTS.md` file exists in the project root, apply it as project-wide guidance.

## Overview

This skill runs inline in the main thread and selectively delegates heavy stages to Copilot custom agents for context isolation. It follows the same workflow as `bill-feature-implement` but uses sub-agents for plan, implement, quality-check, and finalize stages to keep context lean and save tokens.

Code review runs inline because the `bill-code-review` skill triggers deep nested delegation that exceeds Copilot's runtime limits when spawned as a sub-agent.

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

## Step 2: Plan (sub-agent)

Delegate to the `bill-plan` agent with this handoff:
- The full feature spec
- Acceptance criteria (numbered)
- Size classification
- Issue key

It returns: pre-planning context (conventions, history, patterns found), implementation plan (tasks with file targets, rationale, and criteria mapping), and risks.

Present the plan to the user for approval before continuing.

If the sub-agent is interrupted, retry once. If it fails again, run the planning step inline in this thread instead.

## Step 3: Implement (sub-agent)

Delegate to the `bill-implement` agent with this handoff:
- The approved plan (tasks, file targets, rationale per step, criteria mapping)
- Key project conventions from the plan's pre-planning context

It returns: changes made per task, decisions made during implementation, and acceptance criteria coverage mapping.

If the sub-agent is interrupted, retry once. If it fails again, run the implementation step inline in this thread instead.

## Step 4: Completeness audit

Compare the implement summary's criteria coverage against the original acceptance criteria. If any criteria are not covered:
- List the gaps
- Delegate to `bill-implement` agent with: the missing criteria, files already modified, and relevant plan context
- Repeat review after re-implementation

Max 2 audit iterations.

## Step 5: Review (inline)

Run the `bill-code-review` skill directly in this thread — do NOT delegate to a sub-agent. Code review triggers deep nested delegation that exceeds Copilot's runtime limits when spawned as a sub-agent.

Scope the review to the branch diff. Use the implementation decisions context to understand intent behind choices.

The skill produces findings with severity, file paths, and descriptions.

After the review output is produced, call the `import_review` MCP tool with the complete review text (Sections 1 through 4). After findings are resolved or skipped, call the `triage_findings` MCP tool with the `review_run_id` and decisions. When the review produced no findings, still call `triage_findings` with an empty decisions list to close the review lifecycle.

**Review loop:** Auto-fix Blocker and Major findings, re-run `bill-code-review` inline to verify. Continue past Minor-only findings. Max **3 review iterations**. Do not pause to ask the user which finding to fix.

## Step 6: Quality check (sub-agent)

Delegate to the `bill-quality-check` agent. It returns pass or fail.

If it fails, delegate to `bill-implement` agent with the failures to fix, then re-run quality check. Max 2 quality iterations.

## Step 7: Finalize (sub-agent)

Delegate to the `bill-finalize` agent with this handoff:
- Feature name and issue key
- Acceptance criteria
- Summary of all changes made across all implementation passes
- Branch name

It returns: whether history was written, the commit hash, and the PR description output.

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

- Each sub-agent starts with a clean context. Pass only what it needs for its stage.
- Always include rationale and decisions context — not just file lists. This prevents the next agent from misunderstanding intentional choices.
- Hold sub-agent summaries in your context, not their raw outputs.
- When re-running a stage (audit loop, review loop), include what changed since the last run so the sub-agent can focus on the delta.
- If a delegated stage is interrupted twice, continue inline instead of retrying the agent chain.
