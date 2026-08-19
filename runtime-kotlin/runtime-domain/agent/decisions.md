# runtime-domain boundary decisions

## [2026-08-19] Excluded fallback lanes transfer owned paths to the native winner (SKILL-196)

Context: A fallback root exists because some changed files matched no native pack. Per-area fallback
exclusion removes the fallback lane when a native routed pack already contributes a candidate for the
same area, but those fallback-routed files would otherwise have no claimant for that area while
coverage accounting must record exactly as before.

Decision: When a fallback lane for area A is excluded, fold its `ownedPaths` and `changedHunkIds`
into the winning native lane for area A before lane materialization, deduplicated and sorted. Claim
transfer only — no rubric content from the fallback pack is composed into the native lane.

Reason: Keeps each area's file claim byte-identical to the pre-exclusion plan while lane count falls.
`unreviewedSegmentIds`, segment accounting, coverage facts, and integration terminal state stay
unchanged because the native winner still owns every path the removed fallback lane owned.

Alternatives considered: Leave fallback-routed files unclaimed for the area and rely on the coverage
ledger to treat them as out of scope — rejected because it risks recording those paths as unreviewed
coverage or forcing an incomplete lane disposition when the fallback lane disappears. Silently
dropping the paths — rejected by the parent spec.

Evidence: `ReviewCrossRootLaneReconciliationTest` — `excluded fallback paths transfer into the
surviving native lane for the area`; `ParallelReviewFallbackLaneExclusionTest` — `excluding a
redundant fallback lane leaves area coverage and lane accounting untouched`.

Revisit when: fallback exclusion moves to a model where fallback-routed files are never attributed
to area-specific lanes.
