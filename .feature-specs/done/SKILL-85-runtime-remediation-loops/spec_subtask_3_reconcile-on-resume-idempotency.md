---
status: Complete
---

# SKILL-85 Subtask 3 - Reconcile-on-Resume Idempotency for Mutating Phases

Parent spec: [.feature-specs/SKILL-85-runtime-remediation-loops/spec.md](./spec.md)
Issue key: SKILL-85

## Scope

Make mutating phases safe to re-enter and to resume by replacing the runtime's
"re-run = re-apply from scratch" assumption with a reconcile-on-resume contract.
This is the change that removes the root justification for the blanket
`implement` retry exclusion (`FeatureTaskRuntimeFixLoopPolicy.kt:9-10, 19-27`):
implement was fenced out because re-running it would re-apply non-idempotent
repository mutations. With this subtask, a mutating phase that re-runs (after a
crash or via a backward edge) reconciles the working tree toward its intended
state rather than blindly re-applying, so it never double-applies. This unblocks
the mutating re-entries that M1 and M2 require.

## Acceptance Criteria

1. A documented mutating-phase idempotency contract exists: a mutating phase
   (`implement`, and the `implement_fix` phase added in Subtask 4) is given its
   intended-state inputs plus the current tree state and must converge the tree
   to the target, treating an already-applied change as a no-op. The contract is
   delivered structurally - via the phase prompt/briefing and the output schema -
   not left to agent goodwill.
2. The blanket `implement` exclusion is removed from the fix-loop/retry policy and
   replaced by the idempotency contract: a mutating phase may now be re-entered
   or resumed safely. `validate`'s unbounded semantics and the bounded caps for
   other phases are unchanged.
3. A remediation checkpoint makes re-entry crash-safe at the tree level:
   between a verifier-passing iteration and the next backward edge, the runtime
   establishes a known-clean tree boundary (a checkpoint commit on the feature
   branch, or an equivalent durable boundary) so a crash during a subsequent
   mutating phase resumes from a clean, reconcilable state. The mechanism honors
   `suppress_pr` goal subtasks and the existing branch-setup/commit model.
4. Resuming a mutating phase that died mid-edit does not double-apply: the
   re-run reconciles from the current (possibly partially-mutated) tree to the
   target. A test demonstrates a simulated mid-implement crash followed by a
   clean resume with no duplicated mutation.
5. The idempotency contract is verified, not assumed: the phase's output is
   schema-gated to assert it reports the reconciled end-state (files at intended
   state), so a phase that silently skipped reconciliation fails the gate loudly.
6. Branch-setup idempotency (already present:
   `FeatureTaskRuntimeBranchSetupRunner` re-attaches without force-switching) is
   preserved and composed with the new checkpoint boundary; a re-run never
   creates a divergent branch and never loses the checkpoint history.
7. Architecture boundaries hold: any new git/tree-boundary operation is reached
   through an existing domain-owned port (no direct filesystem/process use in
   application/domain); `RuntimeArchitectureTest` passes.
8. Application tests with fakes prove: a mutating phase re-run is a no-op when the
   tree already matches the target; a mid-phase crash resumes without
   double-applying; the checkpoint boundary is established at the right point and
   respects `suppress_pr`; and the schema gate rejects an output that did not
   reconcile.

## Non-Goals

- No concrete loops yet (Subtasks 4, 5 wire the review-fix and audit-gap edges).
- Do not change the decomposition-at-planning or spec-source models.
- Do not alter the prose family's commit/branch behavior.
- Do not make commit/push happen earlier for non-loop runs beyond the checkpoint
  boundary strictly needed for safe re-entry.

## Dependency Notes

Depends on: Subtask 1 (resume integrity) and Subtask 2 (cyclic executor +
durable counters), since safe re-entry presupposes correct resume position and
backward-edge accounting. Consumed by: Subtasks 4 and 5, whose loops re-enter
mutating phases.

## Validation Strategy

Application tests with a fake launcher and a fake git/tree port asserting:
idempotent re-run (no-op on matching tree), mid-phase-crash resume without
double-apply, checkpoint-boundary placement and `suppress_pr` honoring, and
schema-gated reconciliation reporting; a test asserting the removed `implement`
exclusion does not regress same-phase schema-retry bounds for non-mutating
phases; `RuntimeArchitectureTest`.

## Next Path

Run bill-feature-task on spec_subtask_4_review-driven-implement-fix-loop.md.

## Spec Path

.feature-specs/SKILL-85-runtime-remediation-loops/spec_subtask_3_reconcile-on-resume-idempotency.md
