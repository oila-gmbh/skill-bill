# SKILL-141 - Goal Parent Workflow Resume Lifecycle

## Mode

single_spec

## Intended Outcome

Interrupting a running decomposed goal — by killing the foreground `skill-bill goal` driver, or by any path that reconstructs a parent from the on-disk manifest projection — must leave the goal resumable. The parent goal workflow keeps a non-terminal, resumable status and reuses its existing parent workflow id instead of being stamped `abandoned` and re-minted under a fresh id. `abandoned` is reserved exclusively for the explicit, reason-stamped operator abandonment path. Resume reuses the persisted parent id so the `goal_planning_preparations` rows stay identity-aligned and the `GoalPlanningIdentity` guard passes.

## Problem

A `SIGTERM`/kill of the foreground driver, and the manifest-projection reconstruction paths, currently stamp the parent workflow `workflowStatus = "abandoned"` and mint a fresh parent workflow id via `generateWorkflowId(...)`. The shared-preplan and per-subtask planning rows in `goal_planning_preparations` remain keyed to the original parent id. On the next run the goal resolves a different parent id, and the `GoalPlanningIdentity = (parentGoalWorkflowId, normalizedIssueKey, repositoryIdentity)` equality check loud-fails with "stored goal or repository identity differs from expected identity", bricking resume. Read-only `goal status` still succeeds because it uses a lenient projection that skips this equality check, which masks the breakage.

Observed during a SKILL-137 goal run: the foreground driver was killed mid-`implement_fix`; the parent `wfl-20260724-131946-4m0j` became `abandoned`; the next run could not recover planning preparation subtask 0.

## Evidence Sites

- `runtime-kotlin/runtime-infra-sqlite/src/main/kotlin/skillbill/db/workflow/GoalPlanningPreparationStore.kt:694` — the `GoalPlanningIdentity` equality guard that loud-fails on parent-id drift.
- `runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/goalrunner/GoalRunnerWorkflowStores.kt:587` — `importFromManifestProjection` mints a new id and stamps `abandoned`.
- `runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/workflow/WorkflowService.kt:96` — `bootstrapParentWorkflowFromManifest` mints a new id and stamps `abandoned`.
- `runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/workflow/DecompositionWorkflowContinuation.kt:96` — reconstruction stamps `abandoned`.
- `runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/workflow/WorkflowService.kt:290` — the legitimate explicit operator abandonment path (requires a reason, stamps the operator-abandonment artifact). This must remain terminal and unchanged.
- `runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/workflow/DecompositionWorkflowRuntimeLookup.kt:74-83` — `isStaleAbandonedLineage` and the finder's active/terminal candidate selection.

## Acceptance Criteria

1. A non-terminal, resumable parent-workflow status (for example `paused`) is added to the workflow-state contract: the schema enum, the workflow definition's non-terminal/terminal status sets, and the MCP status enums. The runtime-contract recipe is followed — schema const, a pinned Kotlin `*_CONTRACT_VERSION` constant with a parity test, a typed `Invalid*SchemaError`, and loud-fail parse seams — with no forked status mechanism.
2. Interrupting a running goal (foreground-driver kill/`SIGTERM`, or any non-explicit reconstruction path) marks the parent workflow with the new non-terminal status, never `abandoned`, and never mints a new parent workflow id.
3. Resume after interruption reuses the existing persisted parent workflow id. `GoalPlanningIdentity` equality holds, `goal_planning_preparations` rows are not orphaned, and planning recovery proceeds without the "differs from expected identity" loud-fail.
4. The explicit operator abandonment path (`WorkflowService.kt:290`) is unchanged: it still requires a reason, stamps the operator-abandonment artifact, and remains a terminal status distinct from the new non-terminal status.
5. The parent finder (`findDecomposedParentWorkflow`) and `isStaleAbandonedLineage` reuse parents carrying the new non-terminal status and never garbage-collect them as stale lineage; genuine progress is never discarded.
6. Manifest-projection reconstruction (`importFromManifestProjection`, `bootstrapParentWorkflowFromManifest`, `DecompositionWorkflowContinuation`) reuses the existing parent id when one is discoverable for the issue key, and stamps the non-terminal status rather than a throwaway `abandoned` row; it does not create a divergent parent that orphans planning preparations.
7. Acceptance and rejection tests prove: kill-then-resume reuses the same parent id and preserves planning identity; an explicit operator abandonment still lands terminal; contract drift loud-fails through the typed error; and the repository validation gates pass:

    ```bash
    skill-bill validate
    (cd runtime-kotlin && ./gradlew check)
    npx --yes agnix --strict .
    scripts/validate_agent_configs
    ```

## Constraints

- Reuse the existing runtime-contract recipe: Draft 2020-12 schema, pinned Kotlin version constant, parity test, typed invalid-schema error, loud-fail parse seams, configuration-cache-friendly classpath copy. Do not fork a parallel status mechanism.
- The new non-terminal status must be excluded from `terminalStatuses` everywhere terminal status is consulted, so resume, finder reuse, and continuation treat it as live.
- Do not weaken the `GoalPlanningIdentity` equality guard; fix the cause (parent-id churn), not the guard.
- Schema introductions or version bumps loud-fail legacy records and are quarantined/regenerated in-band per the existing workflow-record drift policy; do not add silent migration.
- Preserve unrelated working-tree changes. Do not run installer or uninstall flows inside this change.

## Non-Goals

- Changing the explicit operator abandonment semantics, reason requirement, or its terminal classification.
- Weakening or removing the `GoalPlanningIdentity` identity guard.
- Reworking phase-context projection or any SKILL-137 phase-handoff behavior; that scope is separate.
- Adding a general workflow pause/resume UX beyond the durable status needed for correct goal resume.
- Retroactively repairing already-orphaned goals in place; a hard reset remains the documented recovery for pre-fix bricked state.

## Validation Strategy

- Add schema and parity tests for the new status enum and its contract version.
- Add a persistence/round-trip test proving an interrupted parent persists the non-terminal status and its id is stable across reload.
- Add a goal-runner test simulating driver interruption then resume, asserting the same parent workflow id is reused and `GoalPlanningIdentity` holds (no "differs from expected identity").
- Add a rejection test asserting the explicit operator abandonment still yields the terminal `abandoned` status with its reason artifact.
- Add finder tests proving a non-terminal parent is reused and not treated as stale abandoned lineage.
- Run focused Gradle tests for the changed modules, then the full repository gates in acceptance criterion 7.

## Next Path

Run `skill-bill goal SKILL-141`.
