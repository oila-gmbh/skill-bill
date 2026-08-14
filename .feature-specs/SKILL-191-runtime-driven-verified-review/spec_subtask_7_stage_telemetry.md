# SKILL-191 · Subtask 7 — Stage telemetry and measurement

## Scope

Make the verifier itself auditable. A verification stage has two failure modes that
are invisible without numbers: refuting a large share of findings, which means the
review tier is producing junk, and refuting almost nothing, which means the verifier
is rubber-stamping. Both look identical from a single review's output.

Extend `orchestration/contracts/telemetry-event-schema.yaml` and the
`skillbill_review_finished` payload with:

- verdict distribution per stage — counts of `confirmed`, `refuted`, `unresolved`,
  and each `scope_disposition`
- refutation rate per stage, as a recorded ratio over the run's finding count
- rejected-verdict counts: uncited refutations recorded as `unresolved`, uncited
  downgrades recorded as `in_scope`, and rejected finding-mutation attempts
- severity adjustment counts by direction
- resolved tier, since inline and delegated rates are not comparable and pooling them
  hides both failure modes

Emit a degradation record for every skipped or degraded stage, per
`docs/observability-policy.md`: `spec_context: none` with its reason, a skipped
adjudication stage, a verification worker that failed to launch or return, and a
stage that ended without reaching its boundary.

Extend `skill-bill review-stats` so the rates are readable without querying the
database by hand.

The numbers must come from the runtime's own recorded verdicts, never from a worker's
self-report. A rubber-stamping verifier reporting its own accuracy is the failure mode
this subtask exists to detect.

## Acceptance Criteria

1. `skillbill_review_finished` carries per-stage verdict distribution, refutation rate, severity-adjustment counts by direction, and the resolved tier.
2. Rejected-verdict counts are recorded separately: uncited refutations downgraded to `unresolved`, uncited downgrades recorded as `in_scope`, and rejected finding-mutation attempts.
3. Every reported number is derived from the runtime's durable verdict rows, not from any value a worker reported about itself.
4. Rates are reported per resolved tier and are not pooled across `inline` and `delegated`.
5. A `spec_context: none` resolution, a skipped adjudication stage, a worker that failed to launch or return, and a stage that ended without reaching its boundary each emit a degradation record per `docs/observability-policy.md`.
6. The telemetry schema version is bumped with matching Kotlin constant and parity test, and legacy payloads are quarantined and regenerated in band rather than crashing a read.
7. `skill-bill review-stats` surfaces per-stage verdict distribution and refutation rate.

## Non-Goals

- Acting on the rates — no automatic tier switching, threshold alerting, or verifier
  tuning. This subtask makes the numbers visible; deciding what they mean is a
  separate change.
- Changing existing telemetry fields beyond the additions.
- Remote telemetry proxy behaviour.

## Dependency Notes

Depends on subtasks 4 and 5 for the verdicts it counts. Independent of subtask 6.

## Validation Strategy

- One test that reported counts match the durable verdict rows for a run with a mix of
  outcomes. Derivation from self-report instead of storage is the realistic bug and it
  is silent.
- One test that inline and delegated rates are reported separately.
- One test per degradation record that it is emitted rather than swallowed.
- One schema parity test and one legacy-payload quarantine test.

## Next Path

Subtask 8 — runtime-driven standalone entry.
