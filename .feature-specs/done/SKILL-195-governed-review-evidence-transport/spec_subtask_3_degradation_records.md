# SKILL-195 Subtask 3 — Degradation records for an unenforced evidence boundary

## Scope

Make the inert boundary say so. Today a governed review whose broker served nothing is
indistinguishable in the record from one that served everything: `evidence_bytes: 0` and
`expansions: 0` are reported as ordinary values, and the run completes as if governed.

Reuse the existing mechanism — `ReviewStageDegradationMeasurement` and
`ReviewStageDegradationReason` (`runtime-domain/.../review/model/ReviewStageDegradation.kt:3-23`) —
rather than inventing a second record shape.

Deliver:

- New typed `ReviewStageDegradationReason` values covering: the broker could not be bound for a
  governed launch, and a governed launch completed with the boundary unexercised (locators shipped,
  zero authorized reads).
- Emission wherever this path degrades, carrying `seam`, `expected`, `actual` as the existing model
  requires.
- A record whenever the parse seam rejects candidates (from subtask 1), so format drift is counted,
  not just reported once in the register.

This subtask is the gate that proves subtask 4 landed: once the transport is real, the
unexercised-boundary record must stop appearing on healthy runs.

## Acceptance Criteria

1. A governed review launch that ships locators and performs zero authorized reads emits exactly one
   unexercised-boundary degradation record naming the seam.
2. A governed review launch whose broker could not be bound emits an unbound-broker degradation
   record and does not complete as a clean governed review.
3. A parse seam that rejected at least one candidate emits one degradation record carrying the
   rejection count.
4. A healthy governed review that authorized reads and admitted a register emits none of these
   records.
5. Records carry no repository content — seam identity, counts, and reasons only.
6. Every new reason is a typed enum value with a stable `wireValue`; no free-text reason strings.
7. `(cd runtime-kotlin && ./gradlew check)` passes.

## Non-Goals

- Building the transport. This subtask observes the boundary's absence; subtask 4 supplies it.
- Changing `REVIEW_STAGE_DEGRADATION_CONTRACT_VERSION` semantics beyond adding reasons, or altering
  existing reason values.
- Emitting a record per read. Records are per-lane-run, not per-operation.

## Dependency Notes

Depends on subtask 1 for the rejection count. Independent of subtask 4, and must land before it so
that subtask 4's acceptance can be measured as "the record stops appearing".

## Validation Strategy

- A governed launch with zero authorized reads emits the unexercised-boundary record. This is the
  test that would have surfaced the inert protocol without a source audit.
- A launch with a broker that fails to bind emits the unbound record and does not report a clean
  governed completion.
- A healthy governed launch emits neither record — proving the records are not always-on noise.
- A record's payload contains counts and seam identity and no file content.

One test per rule; assert emitted records at the boundary, not the emitter's call sequence. Then
`(cd runtime-kotlin && ./gradlew check)`.

## Next Path

```bash
skill-bill goal SKILL-195
```
