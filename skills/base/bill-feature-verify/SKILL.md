---
name: bill-feature-verify
description: Verify a PR against a task spec — extract acceptance criteria, decide whether rollout expectations matter, audit feature-flag behavior only when the spec or diff requires it, run full code review, and audit completeness. Use when reviewing teammates' PRs to ensure they match the design doc/spec. The reverse of bill-feature-implement. Use when user mentions verify PR, check PR against spec, review against design doc, or verify implementation.
---

# Feature Verify

## Project Overrides

If `.agents/skill-overrides.md` exists in the project root and contains a `## bill-feature-verify` section, read that section and apply it as the highest-priority instruction for this skill. The matching section may refine or replace parts of the default workflow below.

If an `AGENTS.md` file exists in the project root, apply it as project-wide guidance.

Precedence for this skill: matching `.agents/skill-overrides.md` section > `AGENTS.md` > built-in defaults. When you reuse another skill as part of this workflow, also apply that skill's matching override section when present.

## Workflow Overview

```
Task Spec + PR → Extract Criteria → Gather Diff →
  Feature Flag Audit (if needed) →
  Code Review (dynamic agents) →
  Completeness Audit (criteria vs code) →
  Consolidated Verdict
```

## Step 1: Collect Inputs

Ask the user for the task spec (paste, file path, directory) and PR (number, branch, or commit range).

Accept PDFs (read in page ranges if >10 pages), markdown, inline text. If total text exceeds ~8,000 words, ask which sections are most relevant.

## Step 2: Extract Acceptance Criteria

After reading the spec, produce in one pass:
1. **Acceptance criteria** — numbered list
2. **Non-goals** — things explicitly out of scope
3. **Rollout expectation** — does the spec require guarded rollout?
4. **Key technical constraints** — specific patterns, APIs, or architectural requirements

Then ask: **Confirm or adjust the criteria before I review the PR.**

## Step 3: Gather PR Diff

Based on user input, gather changes via `gh pr diff`, `git diff`, or `git log`.

## Step 4: Feature Flag Audit (conditional)

Read [audit-rubrics.md](audit-rubrics.md) for the full feature flag audit rubric and output format.

## Step 5: Code Review

Run `bill-code-review` against the PR diff. Follow the full skill instructions including any matching `.agents/skill-overrides.md` section.

## Step 6: Completeness Audit

Read [audit-rubrics.md](audit-rubrics.md) for the completeness audit format and rules.

## Step 7: Consolidated Verdict

Read [audit-rubrics.md](audit-rubrics.md) for the verdict format and PR comment instructions.

## Skills Reused

- `bill-code-review` — shared router for stack-specific code review
- `bill-feature-guard` — optional rollout checklist when the spec, diff, or repo policy requires it
