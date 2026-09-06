# SKILL-236 Subtask 1 - Durable telemetry delivery identity and replay recovery

Parent spec: [spec.md](./spec.md)
Issue key: SKILL-236

## Scope

Own the full producer/outbox/transport/relay path for parent criteria 1–3 and delivery portions of 7 and 10. Diagnose the 30-signature replay using a reproducible failure model before selecting the repair. Integrate stable event identity at durable enqueue, migration of pending rows, supported receiver deduplication, acknowledgement/restart/concurrent drain recovery and non-recursive delivery health. Deliver working behavior in this commit, not an unused identity field.

## Acceptance Criteria

1. New logical emissions get distinct durable origin-scoped event IDs; rebatching, transport retries and restart preserve the original ID. Two real attempts with identical payloads/timestamps remain separate.
2. Lost acknowledgement after receiver acceptance and restart after send/before local acknowledgement yield one logical receiver event and a recoverable outbox entry until confirmation. Define and test deduplication retention; do not claim indefinite exactly-once behavior from a finite receiver window.
3. Concurrent drainers cannot create indefinite replay or lose queued records. Receiver rejection, network failure and local mark-synced failure preserve the original identity and expose bounded actionable delivery state.
4. Legacy pending rows receive persistent IDs once with explicit migration and collision isolation across databases/installs. Consent downgrades/off/queue clearing remain effective; discarded events are not reconstructed.
5. Producer, relay and receiver compatibility/rollout requirements are documented and validated. No live PostHog mutation or deletion is needed to verify the implementation.
6. Delivery-health diagnostics do not recursively enqueue failures into an endlessly failing drain. Document the reproduced cause, remaining uncertainty and read-only historical counting guidance.

## Non-Goals

Prose publication redesign, metric meaning changes, review policy changes, remote historical deletion and live deployment.

## Dependency Notes

No intrinsic feature-local dependency; execute in manifest order and preserve earlier increments. Apply all parent constraints. Reconcile current SKILL-233/234/235 ownership before touching overlapping seams; existing fixes count only when their behavioral evidence satisfies this scope. Do not overwrite concurrent work.

## Validation Strategy

Use fake receiver acknowledgement-loss and deduplication fixtures plus real SQLite restart/concurrent-drain boundaries. Name and prove the bugs: receiver accepted but sender lost acknowledgement; successful send followed by failed mark-synced; two drains race; two distinct emissions share identical content; consent clears pending records. Verify actual receiver identity contract without claiming fake transport proves provider behavior.

Follow the parent routed-gate constraints and test-value discipline. Include production integration, contracts, migration and documentation in this subtask commit; do not run a broader substitute build gate.

## Next Path

.feature-specs/SKILL-236-telemetry-truth-and-runtime-reliability/spec_subtask_2_truthful-lifecycle-metrics.md

## Spec Path

.feature-specs/SKILL-236-telemetry-truth-and-runtime-reliability/spec_subtask_1_durable-telemetry-delivery.md
