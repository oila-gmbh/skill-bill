# SKILL-236 Subtask 2 - Truthful lifecycle metrics and correlated diagnostics

Parent spec: [spec.md](./spec.md)
Issue key: SKILL-236

## Scope

Own parent criteria 4–7 and metric portions of 10 end-to-end: producers, schemas, durable state, redaction, local/remote aggregation and documentation. Correct cap flags, audit counters, unavailable quality outcomes and missing failure causes. Correlate lifecycle/rejection/review/error records without exposing raw content. Integrate the delivery identity from subtask 1 when present rather than introducing another identity authority.

## Acceptance Criteria

1. An ordinary review repair sets no false exhaustion flag. Actual named-budget exhaustion is recorded at the durable transition and remains interpretable after resumed success; output-correction rejection telemetry agrees with terminal state.
2. Audit recurrence, new-gap, attempted and resolved counts reflect durable state with explicit measurement grain. Unsupported or unavailable state is null/absent with declared availability, not a fabricated zero; known zero remains distinguishable.
3. A stale/interrupted quality check cannot be counted as a measured clean result based on zero final failures. Local stats, payload consumers and documented query definitions use availability and completion together.
4. Lifecycle, rejection and diagnostic records link known goal/subtask/workflow/session/phase attempt/generation and stable event identity through privacy-safe consistent identifiers; runtime/contract and actual provider/model context are retained when known and explicitly unknown otherwise.
5. Paused/stale reconciliation and unhandled errors retain normalized reasons; review-worker failures distinguish process, timeout, invalid output/publication and budget cases. A diagnostic-store conflict preserves the original failure through a bounded independent local fallback without recursive telemetry.
6. Fixture aggregates distinguish delivery rows from logical events, correction attempts, session outcomes, resumed invocations and last observed logical-goal state. Test/synthetic sources are excluded by explicit defaults and unknown source is reported.
7. Contracts, producer mappings, redaction and consumers change together under governed migration/version rules. Historical missing fields remain unknown; no retrospective approval or fabricated counter is inferred.

## Non-Goals

Changing semantic phase approval, provider selection, historical event rewriting, raw diagnostic export or rebuilding the analytics backend.

## Dependency Notes

No intrinsic feature-local dependency; execute in manifest order and preserve earlier increments. Apply all parent constraints. Reconcile current SKILL-233/234/235 ownership before touching overlapping seams; existing fixes count only when their behavioral evidence satisfies this scope. Do not overwrite concurrent work.

## Validation Strategy

Use observable lifecycle fixtures for repair-without-exhaustion, actual exhaustion followed by completion, two audit passes with recurrence and partial resolution, stale check with previous findings, pause/error/reconciliation causes, diagnostic-store failure and privacy-safe joins. Validate event schema acceptance/rejection and exact aggregate denominators. Test real persistence where transition ordering matters, not object-constructor snapshots.

Follow the parent routed-gate constraints and test-value discipline. Include production integration, contracts, migration and documentation in this subtask commit; do not run a broader substitute build gate.

## Next Path

.feature-specs/SKILL-236-telemetry-truth-and-runtime-reliability/spec_subtask_3_review-and-evidence-recovery.md

## Spec Path

.feature-specs/SKILL-236-telemetry-truth-and-runtime-reliability/spec_subtask_2_truthful-lifecycle-metrics.md
