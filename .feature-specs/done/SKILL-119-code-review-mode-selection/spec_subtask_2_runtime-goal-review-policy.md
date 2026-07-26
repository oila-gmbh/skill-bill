---
status: Complete
issue_key: SKILL-119
parent_issue_key: SKILL-119
subtask_id: 2
---

# SKILL-119 Subtask 2: Runtime goal review policy and exact delta

## Outcome

Runtime decomposed-goal children persist their review mode and immutable
baseline, review only their complete child-owned delta, and continue safely
after a goal-only two-pass cap without weakening standalone task behavior.

## Scope

- Persist and validate `code_review_mode`, `review_base_sha`, baseline
  untracked inventory, review-pass reservation/completion, results, and cap
  disposition in the goal review-state contract.
- Capture the child baseline before implementation and construct only the
  base-to-current tracked and child-owned untracked review input.
- Carry mode and optional parallel lane into each runtime child and both review
  lanes without recursive parallel execution; coordinated lanes consume one
  pass.
- Make crash reconciliation preserve a declared `changes_requested` result
  even when structured findings are absent.
- Emit compact class/symbol-safe runtime goal summaries while preserving raw
  location-bearing artifacts and telemetry.

## Acceptance Criteria

1. A runtime goal child captures and reuses a safe immutable baseline across
   retry, repair, audit re-entry, and resume; a missing or unsafe baseline
   blocks loudly instead of widening scope.
2. Review input includes the full current child delta and owned untracked
   files, but excludes earlier-subtask commits and never substitutes
   `origin/main...HEAD`, branch scope, or arbitrary `HEAD`.
3. Parent and child persistence retain the selected review mode and optional
   parallel lane; both lanes use the same exact mode and scope as one pass.
4. A child has at most two total passes across all re-entry paths. A crash with
   a reserved pass resumes that pass rather than consuming another one.
5. On unresolved Blocker or Major findings at pass two, the runtime persists
   `review_cap_reached`, preserves raw findings, emits a non-approval compact
   summary, and continues normal goal gates without a third review.
6. The compact runtime summary uses structured class/symbol labels or a safe
   stem and never leaks paths, lines, hunks, or raw review text.
7. Standalone feature-task review-loop caps and blocking semantics remain
   unchanged.

## Non-goals

- Do not add prose-agent instructions or generated provider-agent output.
- Do not alter the public feature argument grammar defined by subtask 1.

## Dependencies

Depends on subtask 1 for the canonical review-selection contract.

## Validation Strategy

Use isolated Git-repository tests for baseline ancestry, untracked inventory,
and earlier-subtask exclusion. Add persistence, crash-resume, cap, parallel,
raw-artifact, and compact-summary tests across domain, application, infra-fs,
and CLI modules.

## Next Path

Proceed to subtask 4 after subtask 3 reaches prose parity.
