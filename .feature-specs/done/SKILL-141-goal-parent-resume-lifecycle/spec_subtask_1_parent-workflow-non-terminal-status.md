# SKILL-141 Subtask 1 - Non-terminal parent status and parent-id reuse on resume

## Scope

Introduce a non-terminal, resumable parent-workflow status and route every non-explicit interruption/reconstruction path through it while reusing the existing parent workflow id, so a killed or reconstructed decomposed-goal parent stays resumable and its `goal_planning_preparations` rows remain identity-aligned. Keep the explicit operator abandonment path terminal and unchanged.

Touch points:

- Workflow-state contract/schema: add the non-terminal status to the status enum, the definition's terminal/non-terminal sets, and the MCP status enums, with a pinned contract-version constant, parity test, and typed invalid-schema error.
- `GoalRunnerWorkflowStores.importFromManifestProjection` and `WorkflowService.bootstrapParentWorkflowFromManifest` and `DecompositionWorkflowContinuation`: stamp the non-terminal status and reuse a discoverable existing parent id for the issue key rather than minting a fresh `abandoned` row.
- Foreground-driver interruption handling: on kill/`SIGTERM`/idle-kill, persist the non-terminal status on the parent, not `abandoned`.
- `DecompositionWorkflowRuntimeLookup.findDecomposedParentWorkflow` / `isStaleAbandonedLineage`: reuse non-terminal parents; never GC them.
- `WorkflowService.abandon` (operator path): unchanged, still terminal.

## Acceptance Criteria (this subtask)

1. A non-terminal resumable parent-workflow status (for example `paused`) exists in the workflow-state schema enum, is excluded from every `terminalStatuses` consultation, and is present in the MCP status enums, following the runtime-contract recipe (schema const, pinned Kotlin version constant + parity test, typed `Invalid*SchemaError`, loud-fail seams).
2. Foreground-driver interruption (kill/`SIGTERM`/idle-kill) persists the non-terminal status on the parent workflow and never `abandoned`, and does not mint a new parent workflow id.
3. Resume reuses the existing persisted parent workflow id; `GoalPlanningIdentity` equality holds and planning recovery does not loud-fail with "differs from expected identity".
4. The three manifest-projection reconstruction paths reuse a discoverable existing parent id for the issue key and stamp the non-terminal status, never creating a divergent `abandoned` parent that orphans planning preparations.
5. The explicit operator abandonment path remains terminal, reason-required, and stamps its operator-abandonment artifact unchanged.
6. `findDecomposedParentWorkflow` and `isStaleAbandonedLineage` reuse non-terminal parents and never treat them as stale lineage; parents carrying real subtask progress are never discarded.
7. Acceptance and rejection tests cover: schema/parity for the new status; persistence round-trip of the non-terminal parent; goal-runner interrupt-then-resume reusing the same parent id with identity preserved; explicit abandonment still terminal; finder reuse of a non-terminal parent; and the full repository gates pass.

## Non-Goals

- Changing operator abandonment semantics or its terminal classification.
- Weakening the `GoalPlanningIdentity` guard.
- Any SKILL-137 phase-context or phase-handoff change.
- General pause/resume UX beyond the durable status required for correct resume.
- In-place repair of already-bricked pre-fix goals.

## Dependency Notes

No dependencies. Single self-contained subtask.

## Validation Strategy

- Schema + parity tests for the new status and contract version.
- Persistence round-trip test for the non-terminal parent status and stable id.
- Goal-runner interrupt/resume test asserting parent-id reuse and `GoalPlanningIdentity` equality.
- Rejection test: explicit operator abandonment yields terminal `abandoned` with reason artifact.
- Finder tests: non-terminal parent reused, not GC'd as stale abandoned lineage.
- Focused Gradle tests for changed modules, then:

    ```bash
    skill-bill validate
    (cd runtime-kotlin && ./gradlew check)
    npx --yes agnix --strict .
    scripts/validate_agent_configs
    ```

## Next Path

Run `skill-bill goal SKILL-141`.
