# SKILL-151 Subtask 6 - Adaptive sizing, review depth, and phase-appropriate quality checks

Parent spec: [.feature-specs/SKILL-151-trustworthy-feature-task-convergence/spec.md](./spec.md)
Issue key: SKILL-151

## Scope

Update adaptive policy contracts, complexity signal collection, planning stopper and decomposition decisions, review launch policy, phase briefings, focused-quality selection, and platform-manifest consumption. Escalate or decompose based on boundary breadth, dependency graph, persistence and migration, privacy and security, lifecycle and concurrency, process and crash recovery, platform breadth, and expected change surface. Enforce minimum specialist depth for cross-cutting or high-risk work. Resolve non-test checks dynamically through manifest-declared packs and add-ons.

## Acceptance Criteria

1. Work beyond the resolved direct-work threshold automatically escalates or decomposes.
2. Cross-module persistence, privacy, lifecycle, CLI, and recovery work cannot remain MEDIUM or light merely through agent classification.
3. Review execution mode cannot lower required substance depth or specialist coverage.
4. Build, compilation, formatting, schema, migration, and static-analysis checks may run before final validation.
5. Final validation remains authoritative after earlier build checks.
6. Routing stays dynamic and manifest-driven with no Kotlin, KMP, provider, or fixed-platform hard-coding.

## Non-Goals

- Replacing review with quality checks or validation with review.
- Adding new tests or test infrastructure.

## Dependency Notes

Depends on: 1, 2

Adaptive decisions consume the versioned policy and truthful obligation model established by the first two subtasks.

## Validation Strategy

Run existing focused module checks and repository validation commands. Fix failing production behavior and correct stale existing test expectations when necessary. Add no new tests or fixtures.

## Next Path

Proceed to legacy reconciliation, observability, and end-to-end recovery.

## Spec Path

.feature-specs/SKILL-151-trustworthy-feature-task-convergence/spec_subtask_6_adaptive-sizing-review-depth-and-phase-appropriate-quality-checks.md
