# SKILL-142 Subtask 2 — Producer-side planning projection gate parity

Parent: `.feature-specs/SKILL-142-bounded-loop-convergence/spec.md` (unit 1)

## Scope

Close the validator-parity gap on the goal hydration path so a planning output
that fails its projection contract is repaired producer-side, inside its own
phase's bounded fix loop, before it is checkpointed or hydrated. A consumer must
never repair an upstream producer's contract violation.

`AGENTS.md` already states the design:

> A feature-task-runtime phase owning a bounded planning projection (`preplan`,
> `plan`, `implement`) is gated producer-side: a completed output failing its
> projection contract re-enters that phase's own bounded fix loop instead of
> blocking a consumer that cannot repair it. The gate and the consumer launch
> seam share one validation function and validator port.

That parity does not hold. `GoalChildPlanningHydrator` validates the prepared
payload with `PreparedPlanningPayloadValidator`, which calls
`FeatureTaskRuntimePhaseOutputValidator.validateAndReadPhaseOutput` — the
**phase-output** contract. The consumer launch seam validates the **planning
projection** contract via `FeatureTaskRuntimePlanningProjectionValidator`. Two
validators against two contracts, so a payload valid as phase output but invalid
as a planning projection passes hydration and is rejected downstream.

## Evidence Sites

- `runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/goalrunner/GoalChildPlanningHydrator.kt:48,54,188`
  — hydration validates via `FeatureTaskRuntimePhaseOutputValidator`.
- `runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/featuretask/FeatureTaskRuntimePhaseGates.kt:14`
  — the consumer seam validates via `FeatureTaskRuntimePlanningProjectionValidator`.
  The two validator ports are the parity gap.
- `AGENTS.md` — the shared-validation-function requirement.

Observed escape, phase ledger for `wftr-20260724-184042-578i`:

```
seq 0  preplan  complete   attempt=1  execution_origin=goal-planning-hydrated
seq 1  plan     complete   attempt=1  execution_origin=goal-planning-hydrated
seq 2  implement start     attempt=1  execution_origin=agent-executed
seq 3  plan     loop_edge  driving_verdict=record_rejected loop_id=regenerate_plan edge_iteration=1
seq 4  plan     resume     attempt=2  execution_origin=agent-executed
seq 5  plan     fix_loop_iteration attempt=3 fix_loop_iteration=1
```

Quarantine entry:

```
producing_phase_id: plan   consuming_phase_id: implement
rejection_class:    planning_projection_schema
rejection_detail:   $.tasks[0].test_obligations: must have at least 1 items but found 0
```

Preplan and plan were correctly hydrated once from the parent checkpoint
(`goal-planning-hydrated`, attempt 1). The plan-once invariant is intact and must
stay intact. The re-planning came entirely from a contract-invalid plan escaping
its producer gate.

## Acceptance Criteria

1. Every phase owning a bounded planning projection validates its completed
   output against the **planning projection contract** producer-side, before that
   output is marked settled, checkpointed as a goal planning preparation, or
   hydrated into a child.
2. The producer gate and the consumer launch seam call one shared validation
   function through one validator port. A second, weaker validator on any
   producer path is a defect.
3. A planning output failing its projection contract re-enters its own phase's
   bounded fix loop with the validation detail in the remediation briefing, and
   is never checkpointed or hydrated in the failing state.
4. Goal planning preparation rejects a shared preplan or subtask plan that fails
   its projection contract at write time. A child hydration that would import a
   projection-invalid payload loud-fails through the typed error rather than
   deferring rejection to the consumer.
5. The specific escape observed on SKILL-141 — `plan` completing with
   `tasks[].test_obligations` empty — is rejected producer-side by an explicit
   acceptance test, and a rejection test proves the same payload no longer
   reaches `implement`'s launch seam.
6. The `regenerate_plan`, `regenerate_preplan`, and `regenerate_implement` edges
   remain as the recovery path for genuine drift, unchanged in cap and behavior.
   This unit reduces how often they fire; it does not remove them.
7. The "preplan and plan execute once at the parent and are hydrated into the
   child" invariant is preserved. Hydrated preplan/plan still record
   `execution_origin=goal-planning-hydrated` at attempt 1. No new path
   re-executes settled planning.
8. Repository validation gates pass:

    ```bash
    skill-bill validate
    (cd runtime-kotlin && ./gradlew check)
    npx --yes agnix --strict .
    scripts/validate_agent_configs
    ```

## Non-Goals

- Removing or re-capping the record-regeneration edges.
- Changing the planning projection contract's own field requirements.
- Introducing a new path that re-executes settled planning.
- Reworking the `audit_gap` loop or audit-first ordering.

## Dependency Notes

Independent of SKILL-141 and of every other SKILL-142 subtask.

`GoalChildPlanningHydrator.kt` was under active modification by the SKILL-141
run. That work has landed (merge `e586cb43`); rebase this unit onto the landed
state, not onto the pre-run file.

## Validation Strategy

- Validator-port parity test asserting producer gate and consumer launch seam
  resolve to one shared validation function for every planning-projection phase.
- Acceptance test: `tasks[].test_obligations` empty is rejected producer-side and
  re-enters plan's own fix loop with the detail in the briefing.
- Rejection test: a projection-invalid payload cannot be written as a goal
  planning preparation or hydrated into a child.
- Regression test asserting hydrated preplan/plan still record
  `execution_origin=goal-planning-hydrated` at attempt 1 — the plan-once
  invariant.
- Typed-error test asserting contract drift loud-fails rather than degrading.
- Focused Gradle tests for changed modules, then the full repository gates.

## Next Path

On completion, proceed to subtask 3 (Blocker-only reopen).
