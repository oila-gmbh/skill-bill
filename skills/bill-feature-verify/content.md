# Feature Verify Content

## Step 1: Collect Inputs

Ask the user for the task spec and PR inputs.

Accept the task spec as pasted text, a file path, a directory, or another readable spec source. Accept the PR as a number, branch, or commit range. Accept PDFs if the spec lives in one; read them in page ranges when they are longer than 10 pages. If the total text exceeds roughly 8,000 words, ask which sections matter most before continuing.

The success artifact for this step is `input_context`: the normalized spec source, the verify target, and any user clarifications that later steps need.

## Step 2: Extract Acceptance Criteria

After reading the spec, produce in one pass:

1. **Acceptance criteria** - numbered list
2. **Non-goals** - things explicitly out of scope
3. **Rollout expectation** - does the spec require guarded rollout?
4. **Key technical constraints** - specific patterns, APIs, or architectural requirements

Then ask: **Confirm or adjust the criteria before I review the PR.**

The success artifact for this step is `criteria_summary`: acceptance criteria, non-goals, rollout expectation, and technical constraints.

## Step 3: Gather PR Diff

Based on user input, gather changes via `gh pr diff`, `git diff`, or `git log`.

The success artifact for this step is `diff_summary`: the resolved diff target, commit/PR reference, changed-files summary, and any canonical diff pointer forwarded to review.

## Step 4: Feature Flag Audit (conditional)

Read [audit-rubrics.md](audit-rubrics.md) for the full feature flag audit rubric and output format.

The success artifact for this step is `feature_flag_audit_result`.

If the audit is not required, this step may be legally skipped. Persist the artifact either way.

## Step 5: Code Review

Run `bill-code-review` against the PR diff. Follow the full skill instructions including any matching `.agents/skill-overrides.md` section.

The success artifact for this step is `review_result`.

## Step 6: Completeness Audit

Read [audit-rubrics.md](audit-rubrics.md) for the completeness audit format and rules.

The success artifact for this step is `completeness_audit_result`.

If the verify target changed materially during the same session, this step may loop back to `gather_diff` or `code_review` before continuing.

## Step 7: Consolidated Verdict

Read [audit-rubrics.md](audit-rubrics.md) for the verdict format and PR comment instructions.

The success artifact for this step is `verdict_result`.
