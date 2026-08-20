# SKILL-190 Subtask 4 — Remediation base reconciliation and rollback under amend

## Intended Outcome

The `review_fix` loop keeps a correct diff base across amends, and the compensating rollback path
keeps working when the commit it used to reset past no longer exists as a distinct branch object.

This is the subtask that prevents SKILL-190 from silently reintroducing the SKILL-189 failure.

## Scope

- Rewrite `reconcileRemediationBaseCoherence`
  (`FeatureTaskRuntimeGoalContinuationRecorder.kt:465-519`) to resolve the latest `review_fix` base
  through the checkpoint ref recorded on the identity, replacing the branch-ancestry filter at
  `:487-491`.
- Preserve the reconciliation's existing outcomes and reason codes — `committed_but_unrecorded` and
  `recorded_but_superseded` (`:515-518`) — and its cheap early return at `:477-481` that avoids a
  HEAD read on the common no-remediation-yet path.
- Close the silent-degradation path at `:504-513`: a base that cannot be resolved must produce a
  typed blocked outcome, not a fallthrough that rewrites the base to HEAD.
- Redefine `rollbackRemediationCheckpointCommit` (`FeatureTaskRuntimeRunLoop.kt:868-876`). Under
  amend semantics a compensating rollback restores the subtask commit from the prior checkpoint ref
  rather than soft-resetting past a commit that is no longer distinct.
- Keep `recordRemediationBaseIfNeeded` (`:850-865`) selective: `review_fix` records a base,
  `audit_gap` stays exempt.
- Update the two governed boundary records that named this change as their trigger:
  `runtime-kotlin/agent/decisions.md:97-98` and the featuretask `agent/history.md:111`, using
  `bill-boundary-decisions` and `bill-boundary-history`.

## Acceptance Criteria

1. `reconcileRemediationBaseCoherence` resolves the latest `review_fix` base from the checkpoint ref
   on the identity record, not from branch ancestry against HEAD.
2. Two consecutive `review_fix` passes across an amend boundary resolve a non-empty diff base; the
   second review sees the changes the first one made.
3. A base that cannot be resolved produces a typed blocked outcome with operator guidance; no path
   rewrites the base to HEAD as a fallback.
4. The existing reason codes `committed_but_unrecorded` and `recorded_but_superseded` retain their
   current meaning for the cases that still produce them.
5. The early return that avoids a HEAD read when there is no stored base and no `review_fix`
   checkpoint on record is preserved.
6. `rollbackRemediationCheckpointCommit` restores the subtask commit from the prior checkpoint ref,
   leaves exactly one branch commit afterwards, and is idempotent when the rollback already ran.
7. A rollback with no prior checkpoint ref — the first checkpoint of a subtask — removes the subtask
   commit and leaves the branch at its pre-subtask tip.
8. `audit_gap` continues to record no remediation base.
9. Every degradation this subtask can hit emits an observability record per
   `../../../docs/observability-policy.md`, including ref-resolution misses and blocked reconciliation.
10. `../../../runtime-kotlin/agent/decisions.md` and the featuretask `../../../agent/history.md` record that the
    runtime-owned history rewrite has landed and that the paired base update they anticipated is
    this subtask.

## Non-Goals

- Changing the `review_fix` or `audit_gap` loop structure, edge conditions, or convergence caps.
- Changing what a review agent receives beyond the corrected base.
- Reworking the goal-continuation outcome model or its artifact keys.
- Retrofitting reconciliation for runs whose ledgers predate subtask 2; those quarantine and
  regenerate.

## Dependency Notes

Depends on subtask 1 for ref resolution, subtask 2 for the ref field on the identity record, and
subtask 3 for the refs actually being written.

This subtask and subtask 3 are the shippable unit. Subtask 3 alone leaves reconciliation reading
branch ancestry against commits that amends have orphaned, which is the exact failure the parent
spec documents. Do not ship 3 without 4.

## Validation Strategy

The SKILL-189 scenario is the primary case and must be exercised end to end:

- Review pass one finds issues, remediation amends the subtask commit, review pass two receives a
  base that yields a non-empty diff containing pass one's changes.
- A deleted or unresolvable checkpoint ref produces the typed blocked outcome, and specifically does
  not resolve to HEAD.
- Rollback after a remediation restores the prior state and leaves one branch commit.
- Rollback of the first checkpoint leaves the branch at its pre-subtask tip.
- The no-remediation-yet path performs no HEAD read.
- `audit_gap` records no base.

Then the `runtime-application` module checks and `skill-bill validate`.

## Next Path

Subtask 5 moves the finalisation commit into runtime ownership so the subtask commit receives its
outcome message and is pushed once.
