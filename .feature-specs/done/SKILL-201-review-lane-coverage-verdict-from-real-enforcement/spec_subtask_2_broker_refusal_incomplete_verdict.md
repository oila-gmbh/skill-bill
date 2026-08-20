# SKILL-201 Subtask 2 — Make broker refusal the source of lane evidence incomplete verdict

## Intended Outcome

When `FileSystemReviewEvidenceBroker` refuses a `read_evidence` call for `lane_evidence_bytes`, the
lane completion state reports `incomplete`, names `lane_evidence_bytes` as `budget_dimension`, and
names only the units the refusal actually denied — not a greedy alphabetical tail from path sort.

## Scope

- `FileSystemReviewEvidenceBroker.kt`: carry refused units from `assignedHunkBudgetOutcome` and the
  whole-file path through to lane completion assembly.
- `ReviewLaneBundleAssembly.kt` and `ReviewContextModels.kt`: populate `unreviewed_segment_ids`,
  `unreviewed_units`, and `budget_dimension` from broker refusal evidence, not from projection.
- Tests in `FileSystemReviewEvidenceBrokerTest` and a lane-level test asserting refused reads surface
  as incomplete naming only denied units.

## Acceptance Criteria

1. A lane whose broker refused a `read_evidence` call for `lane_evidence_bytes` reports `incomplete`, names `lane_evidence_bytes` as its `budget_dimension`, and names only the units the refusal actually denied.
2. Refused units are not derived from path sort order or a greedy prefix over the assembled bundle.
3. A lane with no broker refusal and a successful worker run does not report incomplete for `lane_evidence_bytes` (builds on subtask 1).
4. `FileSystemReviewEvidenceBrokerTest` covers the refusal-to-completion wiring at the broker seam.
5. `(cd runtime-kotlin && ./gradlew check)` passes.
6. `skill-bill validate`, `npx --yes agnix --strict .`, and `../../../scripts/validate_agent_configs` pass.

## Non-Goals

- Per-lane budget derivation (subtask 3).
- Coverage report or integration-pass prompt changes (subtask 4).
- End-to-end delegated review reproduction (subtask 5).

## Dependency Notes

Depends on subtask 1 landing first: the projection must be severed before broker refusal becomes the
sole `lane_evidence_bytes` incomplete path.

## Validation Strategy

Unit test at the broker seam plus a lane-level test that a refused read surfaces as `incomplete`
naming only the denied units, not the tail of a path sort.

## Next Path

Proceed to subtask 3.
