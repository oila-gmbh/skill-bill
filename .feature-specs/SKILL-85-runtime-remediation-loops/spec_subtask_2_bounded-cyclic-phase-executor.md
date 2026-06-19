---
status: Complete
---

# SKILL-85 Subtask 2 - Bounded Cyclic Phase Executor

Parent spec: [.feature-specs/SKILL-85-runtime-remediation-loops/spec.md](./spec.md)
Issue key: SKILL-85

## Scope

Turn the runtime's strict forward executor into a bounded, verdict-driven cyclic
state machine, and add durable per-edge iteration accounting - the control-flow
foundation both loops need. Today `RunLoop` iterates a fixed list and `advance()`
can only skip/run/stop (`FeatureTaskRuntimeRunner.kt:95-156`); there is no
re-entry. This subtask introduces backward edges as a first-class concept while
keeping the common case a loud-failing forward pipeline. It wires the machinery
generically (driven by an explicit edge/transition declaration) but does **not**
yet enable any mutating-phase re-entry - that is gated on Subtask 3 - so it is
exercised in tests with non-mutating cycles.

## Acceptance Criteria

1. The phase definition expresses backward edges declaratively: a transition is
   a function of `(currentPhaseId, verdict, edgeIterationCount)` yielding the next
   phase id or a terminal (advance/block). The executor consumes this declaration
   rather than hardcoding the loop topology, so M1 and M2 are data, not bespoke
   branches.
2. `RunLoop` becomes a bounded state machine: after a phase completes, the
   executor computes the next phase from the transition function instead of
   advancing to the next list index. Forward progression is the default edge when
   no loop applies. The change does not introduce a second orchestration loop.
3. Per-edge iteration counters are durable and runtime-owned: each backward edge
   has its own counter persisted in the phase ledger (distinct from the existing
   same-phase schema `attempt_count`), minted by the runtime, never agent-
   reported. The counter survives resume.
4. Caps are enforced across crashes: on resume, an edge whose durable counter has
   reached its cap re-blocks immediately (mirroring
   `FeatureTaskRuntimeFixLoopPolicy.blockReasonIfBudgetExhausted`) rather than
   relaunching and bypassing the cap.
5. Cap exhaustion blocks loudly with the loop id, the iteration count, and the
   unresolved verdict, persisted as a durable terminal blocked record and an
   observability/ledger event, consistent with every other runtime block path.
6. The handoff contract feeds a re-entered phase the latest-iteration upstream
   outputs plus the driving verdict (e.g. the review findings for an
   `implement_fix` re-entry), reusing the existing latest-iteration resolution
   (`FeatureTaskRuntimeHandoffContract`); the re-entered agent never selects its
   own inputs.
7. The verdict abstraction is generic: a verifying phase's structured output
   yields a typed verdict the transition function reads. The concrete review and
   audit verdict schemas are added in Subtasks 4 and 5; this subtask defines the
   verdict type and a default `advance`-only verdict for phases without one.
8. Resume reconstructs loop position deterministically from durable state: the
   executor resumes at the correct phase and edge iteration after a crash at any
   point in a cycle, using Subtask 1's truthful model plus the per-edge ledger.
9. Architecture boundaries hold: the transition declaration and verdict types are
   domain `model` types; the executor stays in `runtime-application` with no
   infra imports; `RuntimeArchitectureTest` passes.
10. Application tests with a fake launcher prove: a declared non-mutating cycle
    iterates up to its cap and converges on a satisfying verdict; cap exhaustion
    blocks loudly; per-edge counters increment and persist; resume mid-cycle
    lands on the correct phase+iteration; a cap-exhausted edge re-blocks on
    resume; and a definition with no backward edges behaves exactly as the
    current forward pipeline (no behavior change for the common case).

## Non-Goals

- No mutating-phase re-entry yet (Subtask 3 makes that safe; Subtasks 4/5 enable
  the concrete loops).
- No concrete review/audit verdict schemas (Subtasks 4, 5).
- Do not change `validate`'s unbounded semantics or the same-phase schema fix
  loop's existing behavior.
- Do not modify the prose family.

## Dependency Notes

Depends on: Subtask 1 (truthful resume/state model). Consumed by: Subtasks 4 and
5, which declare the concrete review-fix and audit-gap edges over this machinery,
and Subtask 3, which makes the mutating re-entries safe.

## Validation Strategy

Domain tests for the transition function and verdict types (pure, exhaustive over
verdict x iteration); application tests with a fake launcher and a synthetic
non-mutating cyclic definition for iteration, convergence, cap-block, resume
position, and cap-across-resume; a test asserting an edge-free definition is
behaviorally identical to today's forward loop; `RuntimeArchitectureTest`.

## Next Path

Run bill-feature-task on spec_subtask_3_reconcile-on-resume-idempotency.md.

## Spec Path

.feature-specs/SKILL-85-runtime-remediation-loops/spec_subtask_2_bounded-cyclic-phase-executor.md
