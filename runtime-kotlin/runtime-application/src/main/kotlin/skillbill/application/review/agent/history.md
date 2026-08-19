# Review Boundary History

## [2026-08-19] SKILL-196 subtask 1 — scope delegated lane plan to composed areas
Areas: application/review, infra-fs/infrastructure/fs, infra-fs/nativeagent/validation, infra-fs/scaffold/platformpack, core/architecture (test)
- Every launch/validation call site that unioned `declaredCodeReviewAreas` across all installed manifests now calls `ReviewLaunchPlanPolicy.composedAreas(slug, manifests)` per routed root, so a routed pack's plan only carries areas its own composition declares.
- `composedAreas` is the single seam for "which areas belong to this pack"; the launch path and `FileSystemReviewAttribution.composedLaunchPlan` are now pinned to the same set (parity test in `ParallelReviewComposedAreaPlanTest`).
- Pattern followed: caller fix only. `flatten`, its per-area winner selection, and `AmbiguousLaneOwnershipError` were left untouched.
- `RuntimeArchitectureTest` gained a guard that fails if the union shape reappears anywhere under `runtime-*/src/main` (reusable: extend it when a new area-set seam lands).
- Known limits: cross-root lane duplicates still survive the `groupBy { it.skillName }` merge (subtask 2) and fallback packs still contribute lanes (subtask 3).
Feature flag: N/A
Acceptance criteria: 6/6 implemented
