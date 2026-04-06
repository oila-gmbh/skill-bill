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
2. **Issue key** (e.g., `ME-5066`)

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

## Step 6: Completeness Audit

**SMALL:** Quick confirmation that all acceptance criteria are satisfied.
**MEDIUM and LARGE:** Full per-criterion verification against actual code.

If gaps found: plan → implement → review → re-audit. Max **2 audit iterations**. When complete, update spec status to **Complete** (MEDIUM/LARGE only).

## Finalization sequence (Steps 6b → 9)

Once completeness audit passes, run Steps 6b through 9 as a **continuous sequence without pausing**. The only reason to stop is if a step fails.

For detailed finalization steps (validation gate, boundary history, commit/push, PR description), see [reference.md](reference.md).

## Reference

For size reference table, error recovery, skills invoked, and all detailed substep instructions, see [reference.md](reference.md).
