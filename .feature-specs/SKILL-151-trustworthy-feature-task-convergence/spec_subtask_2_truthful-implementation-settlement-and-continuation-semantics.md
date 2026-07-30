# SKILL-151 Subtask 2 - Truthful implementation settlement and continuation semantics

Parent spec: [.feature-specs/SKILL-151-trustworthy-feature-task-convergence/spec.md](./spec.md)
Issue key: SKILL-151

## Scope

Update phase-output normalization, implementation completion decisions, phase recording, run-loop gates, transition handling, retry accounting, prompts, and bounded handoff projections. Derive obligations from governed plan tasks, carried audit repair items, and required remediation findings. Model retryable blocked or failed incomplete work as continuation, distinct from schema-invalid retry and quarantine. Persist and redeliver the complete bounded prior receipt on retry, resume, crash recovery, and standalone or goal-child continuation.

## Acceptance Criteria

1. A mutating phase cannot settle completed while a governed plan task, carried audit repair item, or required remediation finding remains unresolved.
2. Retryable incomplete work enters continuation rather than schema-invalid handling and receives the complete bounded prior receipt.
3. Schema retries and implementation continuations have distinct durable records, status, telemetry, retry accounting, and bounded policies.
4. Production crash, resume, and goal-child paths reuse the same receipt and cannot turn partial implementation into completion.
5. Producer-side projection validation remains authoritative without converting valid incomplete work into a schema failure.

## Non-Goals

- Guaranteeing completion in one process invocation.
- Relaxing bounded retry limits or projection validation.
- Adding or modifying tests or test infrastructure.

## Dependency Notes

Depends on: 1

The implementation settlement gate consumes the stable obligations and receipts introduced by subtask 1.

## Validation Strategy

Run existing focused module checks and the repository validation commands. Add or modify no tests or fixtures.

## Next Path

Proceed to closure-complete audit repair convergence.

## Spec Path

.feature-specs/SKILL-151-trustworthy-feature-task-convergence/spec_subtask_2_truthful-implementation-settlement-and-continuation-semantics.md
