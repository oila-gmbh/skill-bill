# SKILL-175 Subtask 6 - Kotlin persistence, application, and IDE prose branch

Parent spec: [.feature-specs/SKILL-175-remove-prose-opencode-runtime-support/spec.md](spec.md)
Issue key: SKILL-175

## Scope

Remove the live Kotlin prose workflow family from domain, ports, application,
SQLite, and IDE/work-list projection — the highest-risk cut because prose shares
`feature_task_workflows` with runtime via `mode`. Implement the in-flight row
policy decided in subtask 1.

### Detailed surfaces

**Identity / enums / models**

- `FeatureTaskWorkflowMode.PROSE` (`WorkflowStateRecord` / related)
- `WorkflowFamilyKind.TASK_PROSE`
- `WorkflowFamily.IMPLEMENT` (humanName `feature-task-prose`) and all switches
- `WorkItemKind.FEATURE_TASK_PROSE`
- `IdeStatusWorkflowFamily.FEATURE_TASK_PROSE` +
  `orchestration/contracts/ide-status-schema.yaml` enum value
- `FeatureTaskExecutionIdentity.mode` allowing `prose` —
  `feature-task-execution-identity-schema.yaml`
- `FeatureImplementWorkflowDefinition` (prose step DAG, `wfl`/`fis` prefixes)
- `FeatureImplementSessionSummary` and lifecycle request/record mappers for
  prose telemetry

**Persistence**

- `feature_task_workflows.mode` CHECK / reads that branch to prose definition
- `feature_implement_sessions` table + migrations strategy (drop vs retain
  archival read-only per subtask 1 policy — document which)
- `WorkflowStateRepository` / `WorkflowStateStore` / `WorkflowStateWrites`
  PROSE → `FeatureImplement*` methods
- `StaleSessionReconciler` implement/prose session handling
- `goal_run_sessions.mode` prose attribution + `GoalModeStats` prose bucket
- Continuation / decomposition code paths that open or resume
  `WorkflowFamily.IMPLEMENT` children for goals (ensure goal children are
  runtime-only; no prose-shaped child rows)

**Application services**

- `WorkflowService` prose/implement branches
- `DecompositionWorkflowContinuation*` prose assumptions
- `GoalBlockedPhaseRetryProjection` if prose-specific
- `FeatureTaskContinuationLookupService` mode splits
- `WorkListService` / `IdeStatusService` / `IdeStatusProjector`
- `FeatureImplementTelemetryValidator*` and emit support for
  `skillbill_feature_task_prose_*`
- `ReviewService.featureImplementStats` /
  `ReviewStatsRuntime.buildFeatureImplementStats` and related health DTOs

**Contracts**

- `orchestration/contracts/workflow-state-schema.yaml` —
  `featureImplementBranch` / `mode: "prose"` / prose step ids
- IDE + execution-identity schemas (above)
- Architecture docs in `runtime-kotlin/ARCHITECTURE.md` describing prose vs
  runtime persistence rules

**Tests (representative; not exhaustive)**

- `WorkflowServiceTest`, `WorkflowCompactContinuationTest`,
  `FeatureImplementWorkflowRuntimeTest`, `FeatureImplementTelemetryValidatorTest`,
  `WorkflowStateStoreTest`, `ApplicationPersistencePortTest`,
  `IdeStatus*Test` + goldens, `ReviewStatsRuntimeTest`,
  `GoalModeAttribution*`, `DatabaseMigrationsTest`,
  `FeatureTaskRouterContinuationTest`, work-list CLI tests with prose kinds

## Acceptance Criteria

1. No compile-time public API remains to open/update/continue a prose feature
   workflow family (`TASK_PROSE` / `WorkflowFamily.IMPLEMENT` /
   `FeatureImplementWorkflowDefinition`).
2. Subtask 1’s in-flight row policy is implemented: existing prose mode rows are
   migrated, quarantined, and/or loud-failed on resume — never executed as a
   live prose engine.
3. `feature_task_workflows` no longer accepts new `mode=prose` writes; schema /
   CHECK / validators updated accordingly (with migration for legacy values per
   policy).
4. IDE status and work-list schemas/code no longer expose `feature-task-prose`.
5. Goal stats/attribution no longer require a live prose mode bucket (legacy
   rows handled per policy).
6. Workflow-state and execution-identity contracts drop prose branches; contract
   versions/parity tests updated.
7. Application and SQLite tests retargeted to runtime-only; removed tests are
   deleted rather than skipped indefinitely.
8. `./gradlew check` in `runtime-kotlin` passes for this cut (may be the first
   full-check gate of the feature).

## Non-Goals

- OpenCode/zcode enum/install purge (subtask 2); do not reintroduce those agents
  while deleting prose types.
- Rewriting historical telemetry already shipped to remote systems (proxy policy
  handled in subtask 4).
- Changing runtime phase DAG semantics unrelated to prose deletion.

## Dependency Notes

- Depends on subtasks 1, 4, and 5 (no MCP/CLI re-entry; row policy fixed).
- Soft-depends on 2–3 (skills already runtime-only).
- Blocks subtask 7 final sweep.

## Validation Strategy

- Full `(cd runtime-kotlin && ./gradlew check)`.
- Schema parity tests for workflow-state, ide-status, execution-identity.
- Manual or test proof: attempting to resume a fixture prose row follows the
  documented loud-fail/migrate policy.
- Grep `runtime-kotlin` for `TASK_PROSE`, `FEATURE_TASK_PROSE`,
  `FeatureImplementWorkflow`, `mode = \"prose\"`, `feature-task-prose` —
  zero live product hits (tests for quarantine may mention the legacy token).

## Next Path

```bash
skill-bill goal SKILL-175
```

After this subtask: final tests/docs/parity sweep (subtask 7).
