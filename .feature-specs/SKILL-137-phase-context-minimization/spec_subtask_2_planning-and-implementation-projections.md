# SKILL-137 Subtask 2 - Planning and Implementation Projections

Parent spec: [.feature-specs/SKILL-137-phase-context-minimization/spec.md](./spec.md)
Issue key: SKILL-137

## Scope

Apply the projection foundation to preplanning, planning, implementation, and the implementation-to-audit boundary. Define strict contracts for the pre-planning digest, executable plan, plan commitment, and implementation receipt. Make repository checkpoints and the actual scoped diff authoritative during completeness audit.

## Acceptance Criteria

1. `preplan` receives only its declared discovery contract: spec reference or bounded criteria/non-goals, feature size, rollout constraints, applicable mandates, relevant boundary-memory instructions, validation-discovery requirement, and applicable preplan add-ons.
2. Review execution mode, commit/PR instructions, finalization-only mandates, unrelated add-on content, and outputs from any prior attempt are absent from an initial preplan prompt.
3. Preplan emits a versioned bounded digest containing affected boundaries, relevant patterns and decisions, risks, rollout/flag decision, validation strategy, unresolved planning questions, and compact evidence references.
4. `plan` receives the acceptance contract and preplanning digest but not the complete preplan envelope, preplan summary/derived notes, progress diagnostics, tool output, or raw discovered source.
5. Plan emits a versioned executable plan with mode, stable ordered task ids, task dependencies, descriptions, criterion references, target paths/symbols, test obligations, constraints, validation strategy, and decomposition data only when decomposition is a valid terminal outcome.
6. The implementation projection excludes planning narration, presentation summary, generic producer summary, progress diagnostics, and arbitrary plan output fields.
7. `implement` receives the executable plan, applicable acceptance criteria and mandates, rollout/flag information, spec reference when required, goal-continuation restrictions, and applicable implementation add-ons; it does not receive the preplan digest on ordinary initial or resumed implementation.
8. Implementation emits a strict receipt containing completed task ids, changed paths, tests added, tests updated, tests executed with compact outcomes, deviations tied to task/criterion ids, unresolved items, reconciliation evidence, and repository checkpoint.
9. Changed-path entries are normalized repository-relative paths with uniqueness and count limits. Tests executed are distinguished from tests merely added or updated.
10. Receipt claims do not satisfy audit evidence by themselves. Audit regenerates or reads the exact repository diff/state for the receipt checkpoint and compares acceptance criteria, plan commitment, implementation claims, and actual repository evidence.
11. `audit` receives a bounded plan commitment containing task/criterion obligations, the implementation receipt, the remaining acceptance criteria, and exact scoped repository context. It does not receive the complete plan or implementation phase envelopes.
12. Receipt checkpoint mismatch before audit either refreshes repository-derived context while preserving producer claims or fails stale according to the declared policy; it never silently audits a different tree under the old receipt.
13. Initial and resumed implementation/audit launches produce byte-equivalent semantic projections for the same durable state, excluding expected attempt metadata.
14. The goal-child path uses the child's immutable base and owned untracked inventory; it never includes sibling-subtask paths, receipts, plans, or diff context.
15. Decomposition planning output remains private to the preparation writer/goal boundary and is not inherited by implementation when planning terminates in `decompose` mode.
16. Contract, domain, prompt, runner, persistence, retry, resume, standalone, and goal-child tests assert required fields and forbidden upstream content.

## Non-Goals

- Audit repair and review handoffs, which are owned by subtask 3.
- Validation and finalization phases, which are owned by subtask 4.
- Changing task-count decomposition thresholds or implementation idempotency semantics.

## Dependency Notes

Depends on: 1.

Uses the shared projection envelope, budget policy, checkpoint model, and private-evidence boundary introduced by subtask 1.

## Validation Strategy

- Projection snapshot tests for preplan, plan, implement, and audit.
- Negative assertions for complete envelopes, narrative fields, telemetry, and preplan leakage into implementation.
- Receipt schema tests for normalized paths, test categories, deviations, unresolved items, and reconciliation.
- Stale-checkpoint and goal-child scope-isolation tests.
- Retry/resume latest-iteration tests.
- Focused runtime Gradle tests.

## Next Path

Continue with subtask 3 after this subtask commits.

## Spec Path

.feature-specs/SKILL-137-phase-context-minimization/spec_subtask_2_planning-and-implementation-projections.md
