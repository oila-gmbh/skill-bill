---
status: Complete
---

# SKILL-85 Subtask 4 - M1: Review-Driven Implement-Fix Loop

Parent spec: [.feature-specs/SKILL-85-runtime-remediation-loops/spec.md](./spec.md)
Issue key: SKILL-85

## Scope

Implement M1: restore prose's review -> implementation-fix -> review remediation
loop to the runtime. Add a dedicated `implement_fix` mutating phase and a
structured review verdict that drives a bounded backward edge from `review` to
`implement_fix` and back, capped at 3 iterations - the contained loop that proves
the Subtask 2 executor and Subtask 3 idempotency on a single backward edge before
M2 generalizes it. This mirrors prose's `bill-feature-task-implementation-fix`
agent, which addresses Blocker/Major findings on the current tree.

## Acceptance Criteria

1. `review` emits a structured, schema-validated verdict: `approved` (no
   Blocker/Major findings) or `changes_requested` (Blocker/Major findings
   present), with the findings carried in the output for the fix handoff. The
   runtime evaluates the verdict; prose alone cannot advance past Blocker/Major.
2. A new `implement_fix` phase exists, distinct from `implement`, whose briefing
   carries the review findings plus the latest implement output and intended
   state. It addresses the findings on the current tree as incremental
   reconciliation (per Subtask 3), not a plan re-application.
3. The backward edge is declared over the Subtask 2 executor: `review`
   `changes_requested` -> `implement_fix` -> `review`, capped at 3 review->fix
   iterations via a durable per-edge counter. On the first `approved` verdict the
   run advances to `audit`.
4. Cap exhaustion blocks loudly: after 3 unsuccessful iterations the run blocks
   with the loop id `review_fix`, the iteration count, and the unresolved
   findings, as a durable terminal blocked record plus observability/ledger
   event - it never advances to `audit` on unresolved Blocker/Major findings.
5. The loop is crash-safe: a death during any `implement_fix` or re-`review`
   iteration resumes at the correct phase and iteration with no double-applied
   mutations (Subtasks 1+3), and a loop that already burned its cap re-blocks on
   resume (Subtask 2).
6. Observability records each iteration as ground truth: `implement_fix`
   launches and re-`review` runs carry the `review_fix` loop id and iteration in
   the ledger and status output; finished telemetry reflects the review-fix
   iteration count.
7. Per-phase agent assignment applies to `implement_fix` through the existing
   resolution order, defaulting to the invoking agent; review scope/parallel
   review behavior for the re-runs is unchanged from the first review.
8. The runtime skill content documents the M1 loop (when it triggers, the cap,
   and the block-on-exhaustion behavior) so operators understand the new
   backward edge.
9. Architecture boundaries hold; the verdict and findings types are domain
   `model` types; `RuntimeArchitectureTest` passes.
10. Application tests with a fake launcher prove: `approved` advances with no fix;
    `changes_requested` spawns `implement_fix` then re-reviews; convergence on
    iteration 2 or 3 advances; 3 unsuccessful iterations block loudly; the fix
    briefing contains the findings; idempotent re-entry (no double-apply);
    crash/resume mid-loop; and cap enforcement across resume.

## Non-Goals

- No audit-gap loopback (Subtask 5).
- Do not change `validate` semantics or the same-phase schema fix loop.
- Do not modify the prose family or its `implementation-fix` agent.
- Do not make the loop unbounded or let cap exhaustion silently advance.

## Dependency Notes

Depends on: Subtask 1 (resume integrity), Subtask 2 (cyclic executor + per-edge
counters + verdict abstraction), Subtask 3 (mutating-phase idempotency for
`implement_fix`). Consumed by: Subtask 5, which reuses the verdict + edge pattern
for the wider audit loop.

## Validation Strategy

Domain tests for the review verdict schema and the `review_fix` transition over
verdict x iteration; application tests with a fake launcher for the full loop
matrix (advance/converge/exhaust), fix-briefing contents, idempotent re-entry,
crash/resume, and cap-across-resume; skill-content lint (agnix); assertions the
prose family is untouched; `RuntimeArchitectureTest`.

## Next Path

Run bill-feature-task on spec_subtask_5_audit-gap-loopback.md.

## Spec Path

.feature-specs/SKILL-85-runtime-remediation-loops/spec_subtask_4_review-driven-implement-fix-loop.md
