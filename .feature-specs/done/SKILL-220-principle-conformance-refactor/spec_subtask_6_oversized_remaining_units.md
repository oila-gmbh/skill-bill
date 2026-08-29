# SKILL-220 Subtask 6: Oversized Remaining-Unit Decomposition

## Intended Outcome

Resolve the remainder of P-08. After subtasks 4 and 5, production files
outside the feature-task runtime and goal-runner clusters still exceed 500
lines (CLI command bags, parallel review, scaffold/pack loaders, sqlite
stores, contract-error dump, composition root helpers). Split them so no
production file in the repository exceeds 500 lines.

## Scope

Decompose every remaining production `src/main` Kotlin file over 500 lines,
including at least:

- `ParallelCodeReviewRunner.kt` (~2218)
- `GoalCliCommands.kt` (~1855)
- `ReviewContextModels.kt` (~1455)
- `ShellContentLoader.kt` (~1450)
- `FeatureTaskRuntimeCliCommands.kt` (~1269)
- `ScaffoldCliCommands.kt` (~1257)
- `ScaffoldService.kt` (~1243)
- `ShellContentContractErrors.kt` (~1071)
- `RepoValidationRuntime.kt` (~1067)
- `WorkflowService.kt` (~1064)
- `WorkflowStateStore.kt` (~1007)
- `JvmAgentRunProcessRunner.kt` (~994)
- `GitWorkflowGitOperations.kt` (~934)
- `WorkflowEngine.kt` (~917)
- `InstallCliCommands.kt` (~871)
- `RuntimeComponent.kt` (~863)
- `SkillRemoveJvmFileSystem.kt` (~691)
- `FileSystemExternalAddonOverlay.kt` (~682)
- `ScaffoldManifestEdits.kt` (~681)
- `DatabaseMigrations.kt` (~679)
- `WorkflowGitOperations.kt` (~672)
- `PlatformPackSubstanceAudit.kt` (~635)
- `DatabaseColumnMigrations.kt` (~612)
- `AgentRunCommandBuilders.kt` (~607)
- `DatabaseSchema.kt` (~592)
- `FileSystemValidationGateRunner.kt` (~558)
- `ReviewSkillStructureValidator.kt` (~535)
- `InstallNativeAgentOperations.kt` (~519)
- `InstallModels.kt` (~513)

Re-scan at the start of this subtask: any production file still over 500
lines after subtasks 4 and 5 is in scope, including files not listed here.

`RuntimeComponent` remains the single construction site. Extract construction
steps as internal helpers; do not add a second DI graph. `WorkflowEngine`
remains the only place that chooses transitions; extract helpers, not a
second engine.

Keep extracted collaborators as narrow in visibility as their callers allow.
Preserve transaction and process-lifetime boundaries. Order files top-down.
Remove detekt suppressions the split makes unnecessary.

CLI command bags may split by verb family (`goal`, `featuretask`, `scaffold`,
`install`) into additional files in the same package; they must not become
a new public API surface.

## Applicable Principles

- Order a file top-down; delete pass-through wrappers.
- Composition is the only place that constructs the runtime graph.
- Only the workflow engine chooses transitions.
- Adapter and JDBC types stay in infrastructure modules.
- Every module starts from a convention plugin; this subtask does not
  reconfigure the build.

## Acceptance Criteria

1. No production file under `runtime-kotlin`, `intellij-plugin`, or
   `../../../runtime-kotlin/build-logic` exceeds 500 lines.
2. No extraction introduces a public type whose only caller is the file it
   was extracted from.
3. `RuntimeComponent` remains the single construction site; existing
   composition architecture tests still pass.
4. `WorkflowEngine` remains the sole transition chooser; existing workflow
   tests pass without assertion changes.
5. Every atomic write that was atomic before is atomic after.
6. Detekt complexity suppressions removed by the split are deleted rather
   than moved. Do not add new suppressions. Leftover `@Suppress` is
   SKILL-221.
7. `../../../scripts/validate` passes.
8. No test is added. If a split needs a new test to be safe, the split is
   wrong.

## Failure And Recovery Behavior

Unchanged. Every failure path, rollback, and recovery route must produce
identical typed results before and after.

## Non-Goals

- Files already brought under 500 lines by subtasks 4 and 5.
- Applying the 500-line ceiling to test sources.
- Changing CLI flags, MCP tool names, or persistence schema.
- Introducing new ports or modules.

## Dependency Notes

Runs after subtasks 1, 2, and 3. Independent of subtasks 4 and 5, but if
those are incomplete this subtask still owns every leftover file over 500
lines. Must not run concurrently with subtask 1.

## Validation Strategy

`../../../scripts/validate`. Line-count the whole production tree; any file over 500
lines fails this subtask. Compare installer, CLI, review, and persistence
tests before and after for assertion parity.

## Next Path

Subtask 7 makes these principles fail the build when violated.
