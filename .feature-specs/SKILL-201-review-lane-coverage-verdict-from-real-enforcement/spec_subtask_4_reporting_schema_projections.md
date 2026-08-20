# SKILL-201 Subtask 4 — Reconcile reporting seams, projections, and schema

## Intended Outcome

Coverage reporting and integration-pass prompts emit incomplete gaps only for broker refusal,
segmentation unreviewable entries, and failed worker runs. Any retained projected headroom occupies
its own field with wording that cannot be mistaken for unreviewed code. Schema and legacy-record
handling follow runtime contract rules.

## Scope

- `ReviewCoverageReport.kt` and `ReviewIntegrationPassRunner.kt` reporting seams.
- `ReviewIntegrationPass.kt` invariant at `:27`.
- `ReviewAccountingProjection.kt` and `ReviewCommitEnvelopeFragments.kt` wire projections.
- `orchestration/contracts/review-context-schema.yaml` if record shape changes.
- Legacy quarantine for old `evidence-unreviewable` segment ids.
- Rewrite tests in `ReviewContextSchemaValidatorTest`, `FileSystemRepoLocalConfigTest`, and any
  remaining suites pinning removed projection behavior.

## Acceptance Criteria

1. `Coverage: NOT clean` and the per-lane `left unreviewed:` line (`ReviewCoverageReport.kt:50`) are emitted only for broker refusal, segmentation unreviewable entries, and failed worker runs.
2. The integration pass's `Coverage gap — this lane left unreviewed:` prompt line (`ReviewIntegrationPassRunner.kt:186`) is emitted on the same conditions, so the parent is never told to compensate for a gap that does not exist.
3. If projected expansion headroom is still reported, it occupies a field distinct from `unreviewed_segment_ids`, `unreviewed_units`, and `budget_dimension`; its wording names headroom or allowance rather than unreviewed code; and it does not change `lane_disposition`.
4. `orchestration/contracts/review-context-schema.yaml` still requires `budget_dimension` whenever `lane_disposition` is `incomplete` (the `allOf` rule at line 301), and `ReviewIntegrationPass`'s invariant that an incomplete summary must name what it left unreviewed (`ReviewIntegrationPass.kt:27`) still holds for every remaining incomplete path.
5. Any schema change to lane completion or accounting records lands as a contract version bump with a parity test and a typed rejection, per the runtime contract rules.
6. `evidence-unreviewable` is absent from the runtime, or retained only under criterion 3 with the segment id renamed to match what it now means.
7. Legacy accounting records carrying the old `evidence-unreviewable` segment id do not crash a read; they are quarantined or migrated in band and the degradation is recorded.
8. Every test that pinned the removed projection behavior is deleted or rewritten against the new rule, not weakened to keep passing, across `ReviewLaneBundleAssemblyTest`, `ReviewPreparationServiceTest`, `ReviewContextSchemaValidatorTest`, `FileSystemRepoLocalConfigTest`, and `FileSystemReviewEvidenceBrokerTest`.
9. `(cd runtime-kotlin && ./gradlew check)` passes.
10. `skill-bill validate`, `npx --yes agnix --strict .`, and `scripts/validate_agent_configs` pass.

## Non-Goals

- End-to-end delegated review over the SKILL-190 range (subtask 5).
- Changing finding admission, severity vocabulary, or risk register format.

## Dependency Notes

Depends on subtasks 1, 2, and 3. Reporting and schema must reflect the final incomplete semantics
and budget model.

## Validation Strategy

`ReviewContextSchemaValidatorTest` for any record change. Contract-version parity test in the
`PlatformPackSchemaContractVersionTest` pattern. Rejection test for the typed error. Test that a
legacy record carrying `evidence-unreviewable` is quarantined rather than crashing a read.

## Next Path

Proceed to subtask 5.
