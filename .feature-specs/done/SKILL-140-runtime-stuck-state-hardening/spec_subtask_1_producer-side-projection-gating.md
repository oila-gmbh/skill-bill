# SKILL-140 Subtask 1 - Producer-Side Projection Gating

## Scope

Enforce the bounded planning-projection contracts at the producing phase's schema gate, inside the bounded fix loop, using the exact validation function the consumer launch seam already uses.

- In `FeatureTaskRuntimeRunLoop.settleValidatedOutput`, add a planning-projection gate for the producing phases: `preplan` must satisfy the `preplanning_digest` variant, `plan` the `executable_plan` variant, and `implement` (including the implement-fix re-entry, which runs under the same phase id) the `implementation_receipt` variant. `plan_commitment` needs no producer gate because it is derived from a valid `executable_plan`.
- The gate calls `featureTaskRuntimePlanningProjectionFromEnvelope` with the run loop's `planningProjectionValidator` and routes `InvalidFeatureTaskRuntimePlanningProjectionSchemaError` into `schemaInvalidAttempt`, so the violating output re-enters the existing bounded fix loop with the projection error text and the phase blocks only at the existing cap.
- The gate applies only to output whose envelope status is `completed`; blocked and failed outputs keep their current settlement paths.
- The launch-seam validation in `launchAndCapture`/`prepareLaunch` stays in place as defense for legacy durable records; its behavioral change is Subtask 4's scope, not this one.

Primary files: `runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/featuretask/FeatureTaskRuntimeRunLoop.kt`, `runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/workflow/taskruntime/model/FeatureTaskRuntimePlanningProjectionModels.kt` (read-only reuse), plus runner tests.

## Acceptance Criteria (this subtask)

1. A `plan` phase output whose `produced_outputs` fails the `executable_plan` contract (for example missing `projection_kind`, wrong `contract_version`, or an undeclared dependency reference) is rejected at the plan phase's own schema gate, retried through the bounded fix loop with the projection error in the retry prompt context, and blocks only at the existing cap.
2. The same producer-gate behavior holds for `preplan` against `preplanning_digest` and for `implement` against `implementation_receipt`, including the implement-fix re-entry. Rejection tests include the two shapes observed in production on 2026-07-23 (RDN-29): `preplanning_digest.rollout` written as an array instead of an object, and `implementation_receipt.deviations` entries written as free-text strings instead of `{ref, note}` objects.
3. Producer gate and launch seam call the same `featureTaskRuntimePlanningProjectionFromEnvelope` function with the same validator port; no projection rule is restated at the gate.
4. A parity test proves any envelope accepted by the producer gate is accepted by the launch-seam parse for the corresponding consumer edge.
5. Blocked and failed phase outputs are not subjected to the projection gate; existing tests for blocked settlement stay green.
6. The gate's rejection reason names the phase, the expected `projection_kind`, and the underlying validation failure, bounded by the existing schema-gate detail truncation.

## Non-Goals

- Changing launch-seam rejection behavior (Subtask 4).
- Canonicalizing agent output before validation (Subtask 2).
- Replacing the Noop validator in existing tests (Subtask 3); this subtask's new tests may exercise typed cross-field rules through the existing harness.

## Dependency Notes

No dependencies; this is the first subtask and the acute production blocker.

## Validation Strategy

- New runner tests mirroring the existing reconciliation-gate test shape: schema-violating plan/preplan/implement outputs retry to the cap and block with the projection reason; conforming fixtures advance.
- Parity test between producer gate and launch seam.
- `(cd runtime-kotlin && ./gradlew :runtime-application:test)` then full `./gradlew check`.

## Next Path

Subtask 2 layers canonicalization ahead of the shared validation function so lexical trivia stops consuming fix-loop attempts.
