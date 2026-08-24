# Review Boundary History

## [2026-08-24] SKILL-207 subtask 2 — claim verification as phase call and skill alignment
Areas: runtime-application/review, runtime-application/review/model, skills/bill-code-review, skills/bill-code-review-inline
- Claim verification now receives review prose through the `input` + `requestedAction` phase envelope; optional parsed findings enrich per-claim checks instead of gating launch.
- Empty admitted finding lists still launch one prose verification pass when review output is present; blank output remains a recorded skip.
- Verification output is preserved as an `AgentPhaseOutput` for later enrichment, and prompts treat the prose blob as authoritative.
- Pattern: best-effort register shape with runtime-owned launch, evidence, and persistence; reusable for later phase I/O envelope adoption.
- Known limitation: structured verdicts/citations remain optional enrichment, not a typed replacement for verifier prose.
Feature flag: N/A
Acceptance criteria: 5/5 implemented

## [2026-08-19] SKILL-196 subtask 3 — exclude fallback lanes per area
Areas: application/review, domain/review/plan
- `ReviewPerAreaFallbackExclusion.partition` runs after per-root lane assembly and before cross-root reconciliation; a fallback pack contributes a lane for area A only when no native routed pack in the assembled plan declares A.
- Fallback owner resolves through `ReviewFallbackResolver`; with `generic` + `kotlin` + `kmp` on a Kotlin/Android diff the plan carries exactly one lane per area and zero `generic` rows.
- Excluded fallback lanes fold `ownedPaths` and `changedHunkIds` into the winning native lane via `ReviewCrossRootLaneReconciliation`'s `excludedFallbackLanesByArea` input — claim transfer only, no rubric composition (rationale in `runtime-domain/agent/decisions.md`).
- Pattern followed: domain-owned `ReviewFallbackExclusionPartition` consumed by `ParallelCodeReviewRunner`; `ReviewStackRouting` scoring and the per-file fallback branch untouched.
- Regression: the 13-lane cross-stack fixture resolves to one lane per area; coverage ledger and `unreviewedSegmentIds` stay unchanged when a redundant fallback lane drops.
Feature flag: N/A
Acceptance criteria: 11/11 implemented

## [2026-08-19] SKILL-196 subtask 2 — reconcile lanes across routed roots by area
Areas: application/review, domain/review/plan
- Cross-root reconciliation now keys on `lane.area` instead of `skillName`; the delegated launch path no longer uses `groupBy { it.skillName }`, so `kotlin` and `kmp` lanes for the same area collapse to one owner.
- `ReviewCrossRootLaneReconciliation` picks the nearest composition depth across routed roots via `compositionDepthOffsets`, raises `AmbiguousLaneOwnershipError` when two native packs tie at that depth, and sorts inputs deterministically before selection so manifest and root order do not affect owner or `orderIndex`.
- The surviving lane merges reconciled inputs: `required` is the disjunction, `ownedPaths` the sorted distinct union, `changedHunkIds` the distinct union; a dropped duplicate lane's paths stay on the winner so coverage does not hole.
- Pattern followed: domain-owned reconciliation primitive (`ReviewRootLanes`, `ReviewReconciledLane`) consumed by `ParallelCodeReviewRunner`; `flatten` and its in-plan winner selection untouched.
- Known limits: fallback `generic` lanes that survive because no native pack ties at a nearer depth remain until subtask 3.
Feature flag: N/A
Acceptance criteria: 11/11 implemented

## [2026-08-19] SKILL-196 subtask 1 — scope delegated lane plan to composed areas
Areas: application/review, infra-fs/infrastructure/fs, infra-fs/nativeagent/validation, infra-fs/scaffold/platformpack, core/architecture (test)
- Every launch/validation call site that unioned `declaredCodeReviewAreas` across all installed manifests now calls `ReviewLaunchPlanPolicy.composedAreas(slug, manifests)` per routed root, so a routed pack's plan only carries areas its own composition declares.
- `composedAreas` is the single seam for "which areas belong to this pack"; the launch path and `FileSystemReviewAttribution.composedLaunchPlan` are now pinned to the same set (parity test in `ParallelReviewComposedAreaPlanTest`).
- Pattern followed: caller fix only. `flatten`, its per-area winner selection, and `AmbiguousLaneOwnershipError` were left untouched.
- `RuntimeArchitectureTest` gained a guard that fails if the union shape reappears anywhere under `runtime-*/src/main` (reusable: extend it when a new area-set seam lands).
- Known limits: cross-root lane duplicates still survive the `groupBy { it.skillName }` merge (subtask 2) and fallback packs still contribute lanes (subtask 3).
Feature flag: N/A
Acceptance criteria: 6/6 implemented
