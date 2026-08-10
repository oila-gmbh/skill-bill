# goalrunner boundary history

## [2026-08-11] SKILL-181 subtask 1 — Validity vs freshness provenance gate
Areas: runtime-application/goalrunner, .feature-specs/SKILL-181-preplan-provenance-refresh
- Replaced single equality `recoverableProvenance` with `classifyGoalPlanningProvenanceRecoverability`: Valid (manifest hash, phase-output schema id, parent-spec self-hash, payload sha, selected heading ids in the fresh model-free catalog) vs Fresh (canonical parent-spec equality)
- Outcomes: Reuse when valid+fresh; StaleValid keeps the checkpoint and continues prepare without `incompatibleProvenance` (in-run refresh is subtask 2); Invalid still loud-stops at preplan / subtask 0
- Pattern: never short-circuit on provenance equality alone — payload integrity and heading resolution always run when a checkpoint exists; catalog ids come from `contextDiscovery`, not the recovered packet
- Reusable: `GoalPlanningProvenanceRecoverability` sealed result (`Reuse` / `StaleValid` / `Invalid`) and `selectedBoundaryHeadingIds` helper shared with body resolution
- Limitation: StaleValid does not yet re-run preplan, cascade, CLI replan, exit codes, or `planning_reason`
Feature flag: N/A
Acceptance criteria: 5/5 implemented
