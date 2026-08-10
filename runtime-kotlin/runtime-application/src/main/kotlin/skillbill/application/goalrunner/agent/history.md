# goalrunner boundary history

## [2026-08-11] SKILL-181 subtask 3 — Terminal-with-commit plan cascade exclusion
Areas: runtime-application/goalrunner + workflow, runtime-ports/persistence, runtime-infra-sqlite/db/workflow, runtime-cli/goal, skills/bill-feature-goal, runtime-kotlin/agent/decisions.md
- Shared eligibility helper: cascade only when manifest subtask is not (`complete` + non-blank `commit_sha`); used by scoped replan `--include-shared-preplan` and heading-set refresh replace
- Soft-invalidate shared preplan when survivors remain (FK ON DELETE CASCADE must not wipe terminal plan rows); delete only when no retained plans
- `replaceSharedPreplan` deletes an explicit cascade id list then restamps remaining plan provenance in the same transaction; relaunch regeneration restamps survivors without re-cascading
- Patterns: `cascadeEligiblePlanSubtaskIds` / `isTerminalWithCommitPlan`; `invalidateSharedPreplan`; `restampSubtaskPlanProvenance`
- Limits: exit codes / `planning_reason` remain subtask 4; scoped replan without `--include-shared-preplan` still deletes only the named plan
Feature flag: N/A
Acceptance criteria: 5/5 implemented

## [2026-08-11] SKILL-181 subtask 2 — In-run stale-valid preplan refresh
Areas: runtime-application/goalrunner + workflow, runtime-ports/persistence, runtime-infra-sqlite/db/workflow, runtime-core/di, .feature-specs/SKILL-181-preplan-provenance-refresh
- StaleValid no longer stops prepare: one in-run shared-preplan refresh per launch, gated by child-aware liveness (parent prepare lease treated as IDLE so it cannot self-refuse)
- Heading-set equality on `selected_boundary_headings` decides outcome: same set advances provenance only and keeps payload bytes + all sibling plans; changed set adopts the new payload and discards via shared cascade helper
- Atomic refresh persist: `replaceSharedPreplanForRefresh` leaves either the prior valid record or the new one — never a provenance/payload mismatch mid-crash
- Patterns: `GoalPlanningRefreshLiveness` / `ChildAwareGoalPlanningRefreshLiveness`; `refreshedThisPrepare` latch; cascade only through `cascadeSiblingPlansAfterSharedPreplanRefresh` (reusable seam for ST3 terminal filter)
- Limits: cascade filtered by terminal-with-commit exclusion in subtask 3; exit codes / `planning_reason` remain subtask 4; explicit `replan --include-shared-preplan` still force-regenerates
Feature flag: N/A
Acceptance criteria: 7/7 implemented

## [2026-08-11] SKILL-181 subtask 1 — Validity vs freshness provenance gate
Areas: runtime-application/goalrunner, .feature-specs/SKILL-181-preplan-provenance-refresh
- Replaced single equality `recoverableProvenance` with `classifyGoalPlanningProvenanceRecoverability`: Valid (manifest hash, phase-output schema id, parent-spec self-hash, payload sha, selected heading ids in the fresh model-free catalog) vs Fresh (canonical parent-spec equality)
- Outcomes: Reuse when valid+fresh; StaleValid keeps the checkpoint and continues prepare without `incompatibleProvenance` (in-run refresh is subtask 2); Invalid still loud-stops at preplan / subtask 0
- Pattern: never short-circuit on provenance equality alone — payload integrity and heading resolution always run when a checkpoint exists; catalog ids come from `contextDiscovery`, not the recovered packet
- Reusable: `GoalPlanningProvenanceRecoverability` sealed result (`Reuse` / `StaleValid` / `Invalid`) and `selectedBoundaryHeadingIds` helper shared with body resolution
- Limitation: StaleValid does not yet re-run preplan, cascade, CLI replan, exit codes, or `planning_reason`
Feature flag: N/A
Acceptance criteria: 5/5 implemented
