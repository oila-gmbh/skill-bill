# SKILL-201 Subtask 1 — Sever the coverage verdict from the projected evidence budget

## Intended Outcome

A lane whose assembled diff bundle exceeds `max_lane_evidence_bytes`, whose segmentation produced
no unreviewable entry, and whose worker run succeeded reports `lane_disposition: complete`. The
pre-flight projection in `withLaneEvidenceBudget` no longer writes incomplete coverage or
`unreviewedUnits` from a size comparison against an allowance for reads the worker never attempted.

## Scope

- `ReviewLaneBundleAssembly.kt`: remove `withLaneEvidenceBudget` from the completion path; retire or
  repurpose `EVIDENCE_UNREVIEWABLE_SEGMENT_ID` and `LANE_EVIDENCE_BYTES_DIMENSION` only where they
  served the projection (subtask 2 owns broker-driven use of `lane_evidence_bytes`).
- `ReviewContextModels.kt`: stop calling `withLaneEvidenceBudget` at the `completionState`
  composition around `:1115`.
- Preserve `asFailedLaneRun` in `ParallelCodeReviewRunner.kt` unchanged.
- Preserve segmentation's `unreviewable` path for entries that cannot fit `max_lane_launch_bytes`
  alone.

## Acceptance Criteria

1. A lane whose assembled diff bundle exceeds `max_lane_evidence_bytes`, whose segmentation produced no unreviewable entry, whose worker run succeeded, and whose broker refused no read reports `lane_disposition: complete`, an empty `unreviewed_segment_ids`, an empty `unreviewed_units`, and no `budget_dimension`.
2. A lane holding an entry that cannot fit `max_lane_launch_bytes` alone still reports `incomplete` with the `unreviewable` segment id and that entry in `unreviewed_units`.
3. A lane whose worker run failed still reports `incomplete` naming its whole assigned bundle, unchanged from `asFailedLaneRun` today.
4. `unreviewedUnits` is populated only from units that were withheld, refused, or lost to a failed run. No code path derives it from a size comparison against an allowance for an operation that was not attempted.
5. `ReviewLaneBundleAssemblyTest`'s evidence-overflow case is deleted or rewritten against criterion 1, not weakened to keep passing.
6. `(cd runtime-kotlin && ./gradlew check)` passes.
7. `skill-bill validate`, `npx --yes agnix --strict .`, and `../../../scripts/validate_agent_configs` pass.

## Non-Goals

- Wiring broker refusal into lane completion (subtask 2).
- Per-lane budget derivation or parent budget reconciliation (subtask 3).
- Coverage report or integration-pass prompt changes (subtask 4).
- Raising `max_lane_evidence_bytes` in `../../../.skill-bill/config.yaml`.
- Changing `max_lane_launch_bytes`, segmentation, or `deliveredEntries`.

## Dependency Notes

None. This is the first subtask.

## Validation Strategy

Expect `ReviewLaneBundleAssemblyTest`'s evidence-overflow case to fail first; rewrite or delete it
against criterion 1. Add a test constructing a lane over the cap with clean segmentation, a successful
run, and no broker refusal, asserting `complete`. Add tests that fail if the segmentation
`unreviewable` path or `asFailedLaneRun` stops reporting.

## Next Path

Proceed to subtask 2.
