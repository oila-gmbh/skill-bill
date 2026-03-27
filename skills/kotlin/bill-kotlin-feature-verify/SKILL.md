---
name: bill-kotlin-feature-verify
description: Verify a PR against a task spec — extract acceptance criteria, check feature flag compliance, run full code review, and audit completeness. Use when reviewing teammates' PRs to ensure they match the design doc/spec. The reverse of bill-kotlin-feature-implement.
---

# Feature Verify

## Project Overrides

If `.agents/skill-overrides.md` exists in the project root and contains a `## bill-kotlin-feature-verify` section, read that section and apply it as the highest-priority instruction for this skill. The matching section may refine or replace parts of the default workflow below.

If an `AGENTS.md` file exists in the project root, apply it as project-wide guidance.

Precedence for this skill: matching `.agents/skill-overrides.md` section > `AGENTS.md` > built-in defaults. When you reuse another skill as part of this workflow, also apply that skill's matching override section when present.

## Workflow Overview

```
Task Spec + PR → Extract Criteria → Gather Diff →
  Feature Flag Audit (if spec requires) →
  Code Review (dynamic agents) →
  Completeness Audit (criteria vs code) →
  Consolidated Verdict
```

## Step 1: Collect Inputs

Ask the user:
> **Provide the task spec** — paste text, give me a file path, or point me to a folder with spec files.
> **Provide the PR** — PR number (for `gh pr diff`), branch name, or commit range.

Accept any of:
- Inline text (paste)
- Single file path (PDF or markdown)
- Directory path containing multiple spec files
- PR number, branch name, or commit range for the diff

**Reading PDFs:** If given PDF files, use the Read tool. For large PDFs (>10 pages), read in page ranges.

**Spec size limit:** If the total extracted text exceeds ~8,000 words, ask the user:
> **The spec is very large. Which sections are most relevant for this review?**

## Step 2: Extract Acceptance Criteria

After reading the spec, produce in one pass:

1. **Acceptance criteria** — numbered list of verifiable requirements
2. **Non-goals** — things explicitly out of scope
3. **Feature flag expectation** — does the spec require a feature flag? If yes, note the expected flag name/pattern
4. **Key technical constraints** — specific patterns, APIs, or architectural requirements mentioned in the spec

Present as:

```
ACCEPTANCE CRITERIA:
1. ...
2. ...

NON-GOALS: ...

FEATURE FLAG: Required (<expected name/pattern>) | Not required | Not mentioned

TECHNICAL CONSTRAINTS: ...
```

Then ask:
> **Confirm or adjust the criteria before I review the PR.**

## Step 3: Gather PR Diff

Based on user input, gather the changes:
- PR number: `gh pr diff <number>` and `gh pr view <number>`
- Branch: `git diff main...<branch>`
- Commit range: `git diff <range>`

Also gather:
- List of changed files
- Commit messages (`git log main..<branch> --oneline`)

## Step 4: Feature Flag Audit (conditional)

**Skip if:** spec says no feature flag needed AND no feature flag appears in the diff.

**Run if:** spec requires a feature flag, OR a feature flag appears in the diff.

Verify against `bill-feature-guard` principles:

1. **Flag exists** — is the flag defined in the codebase?
2. **Rollback safety** — when flag is OFF, behavior is identical to before the PR
3. **Minimal checks** — feature flag checks are at the highest practical level (not scattered)
4. **Legacy preserved** — if Legacy pattern used, legacy code is untouched
5. **No hybrid states** — no mixing of old/new behavior paths
6. **Default value** — flag defaults to `false` (disabled)

Output:

```
FEATURE FLAG AUDIT
Flag name: <name>
Pattern: Legacy / DI Switch / Simple Conditional / N/A

[ PASS | FAIL ] Flag defined in codebase
[ PASS | FAIL ] Rollback safe (flag OFF = identical old behavior)
[ PASS | FAIL ] Minimal flag checks (not scattered)
[ PASS | FAIL ] Legacy code untouched (if applicable)
[ PASS | FAIL ] No hybrid states
[ PASS | FAIL ] Default value is false

Issues: <list, or "None">
```

## Step 5: Code Review

Run the `bill-kotlin-code-review` skill against the PR diff. This will:

1. Always spawn `bill-kotlin-code-review-architecture`
2. Analyze the diff and select additional specialist agents based on triggers
3. Launch all agents in parallel
4. Merge and deduplicate findings
5. Produce the risk register and action items

Follow the full `bill-kotlin-code-review` skill instructions, including any matching `.agents/skill-overrides.md` section — do not abbreviate or skip agents.

## Step 6: Completeness Audit

For each numbered acceptance criterion, search the actual code changes to verify implementation:

```
COMPLETENESS AUDIT

Acceptance criteria: <total>
Implemented:         <count>
Missing:             <count>
Partial:             <count>

---

[PASS] #1: <criterion text>
  Evidence: FileA.kt:42, FileB.kt:88

[FAIL] #6: <criterion text>
  Not found — <reason>

[PARTIAL] #8: <criterion text>
  Missing — <what's missing>
```

**Rules:**
- Every criterion must have concrete file:line evidence or be marked FAIL
- "Partial" means some but not all aspects of the criterion are covered
- Check both positive (feature works) and negative (edge cases, error states) aspects
- If the spec mentions tests, verify test coverage exists for the criterion

## Step 7: Consolidated Verdict

Merge all findings into a single report:

```
FEATURE VERIFY: <feature name>

--- ACCEPTANCE CRITERIA ---
<completeness audit from Step 6>

--- FEATURE FLAG ---
<audit from Step 4, or "N/A — no flag required">

--- CODE REVIEW ---
<risk register and action items from Step 5>

--- VERDICT ---
<one of:>
  APPROVE — all criteria met, no blockers
  APPROVE WITH FIXES — all criteria met, but code issues need fixing [list P0/P1]
  REQUEST CHANGES — missing criteria or blockers [list what's missing/blocking]
```

After presenting the verdict, ask:
> **Would you like me to leave this as a PR comment, or fix any of the issues?**

If the user wants a PR comment:
- Format the verdict as a GitHub PR review comment using `gh pr review <number>`
- Use `--comment` for APPROVE WITH FIXES, `--approve` for APPROVE, `--request-changes` for REQUEST CHANGES

## Skills Reused

This skill orchestrates:
- `bill-kotlin-code-review` — full dynamic-agent code review (and its sub-agents: `bill-kotlin-code-review-architecture`, `bill-kmp-code-review-compose-check`, `bill-kmp-code-review-ux-accessibility`, `bill-kotlin-code-review-platform-correctness`, `bill-kotlin-code-review-security`, `bill-kotlin-code-review-performance`, `bill-kotlin-code-review-testing`, `bill-kotlin-code-review-backend-api-contracts`, `bill-kotlin-code-review-backend-persistence`, `bill-kotlin-code-review-backend-reliability`)
- `bill-feature-guard` — feature flag compliance checklist (read inline, not spawned)
