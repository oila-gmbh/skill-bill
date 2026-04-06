# Audit Rubrics

## Feature Flag Audit

**Skip if:** the spec does not require feature-flagged rollout, no feature flag appears in the diff, and repo policy does not require one.

**Run if:** the spec requires a feature flag, a feature flag appears in the diff, or the repo has explicit feature-flag policy for this change.

Verify against the repo's rollout requirements. If the repo does not define its own rollout rubric, use `bill-feature-guard` as a narrow checklist rather than assuming every repo follows it by default:

1. **Flag exists** — is the flag defined in the codebase?
2. **Rollback safety** — when flag is OFF, behavior is identical to before the PR
3. **Minimal checks** — feature flag checks are at the highest practical level (not scattered)
4. **Legacy preserved** — if Legacy pattern used, legacy code is untouched
5. **No hybrid states** — no mixing of old/new behavior paths
6. **Default value** — if a new flag is introduced, it defaults to `false` (disabled)

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

## Completeness Audit

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

## Consolidated Verdict

Merge all findings into a single report:

```
FEATURE VERIFY: <feature name>

--- ACCEPTANCE CRITERIA ---
<completeness audit>

--- FEATURE FLAG ---
<audit, or "N/A — no flag required">

--- CODE REVIEW ---
<risk register and action items>

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
