# SKILL-220 Subtask 4: Oversized Feature-Task Runtime Decomposition

## Intended Outcome

Resolve the feature-task share of P-08. `FeatureTaskRuntimeRunLoop` is ~7095
lines and neighbouring runtime types each hold several responsibilities.
Split them into cohesive collaborators without multiplying public
abstractions.

## Scope

Decompose production files in the feature-task runtime cluster that exceed
500 lines, including at least:

- `FeatureTaskRuntimeRunLoop.kt` (~7095)
- `FeatureTaskRuntimePhaseRecorder.kt` (~2177)
- `FeatureTaskRuntimeGoalContinuationRecorder.kt` (~1105)
- `FeatureTaskRuntimePersistenceModels.kt` (~1072)
- `FeatureTaskRuntimeHandoffProjectionValidator.kt` (~823)
- `FeatureTaskRuntimePhaseOutputStructuralRepair.kt` (~804)
- `FeatureTaskRuntimeHandoffProjectionModels.kt` (~766)
- `FeatureTaskRuntimePhasePromptComposer.kt` (~719)
- `FeatureTaskRuntimePhaseWorkflowDefinition.kt` (~708)
- `FeatureTaskRuntimeRunState.kt` (~624)
- `FeatureTaskRuntimeRunner.kt` (~606)
- `FeatureTaskRuntimeProjectionCanonicalization.kt` (~511)
- `FeatureTaskRuntimeCorrectiveRepairContext.kt` (~509)
- `FeatureTaskRuntimeStatusService.kt` (~509)

For each file, identify the distinct responsibilities and extract each into
a collaborator whose name states what it owns. Keep extracted collaborators
as narrow in visibility as their callers allow. An extraction whose only
caller is the file it came from stays internal or private.

Preserve transaction, lease, and fencing boundaries exactly. Do not open a
transaction in one collaborator and commit it in another. Do not hold a
lease across an external process wait.

Order each resulting file top-down: entry point first, helpers after.
Remove detekt `LargeClass` / `LongMethod` / `TooManyFunctions` suppressions
the split makes unnecessary.

## Applicable Principles

- Order a file top-down; delete pass-through wrappers.
- Prefer clear names, small functions, and refactoring over comments.
- Composition remains the only place that constructs the runtime graph —
  do not add a second construction site while splitting the run loop.
- Never hold a transaction, lease, or lock open across a wait on an
  external process.

## Acceptance Criteria

1. Every file listed above is at or under 500 lines, and no new production
   file this subtask introduces exceeds 500 lines.
2. No extraction introduces a public type whose only caller is the file it
   was extracted from.
3. Workflow transitions, repair-hop policy, phase order, and durable
   encodings are unchanged. Existing feature-task runtime tests pass without
   assertion changes.
4. No transaction is opened and committed in different collaborators, and no
   lease or lock is held across an external process wait.
5. Detekt complexity suppressions removed by the split are deleted rather
   than moved; any retained suppression carries a reason.
6. `scripts/validate` passes.
7. No test is added. If a split needs a new test to be safe, the split is
   wrong.

## Failure And Recovery Behavior

Unchanged. Every failure path, rollback, and recovery route must produce
identical typed results before and after.

## Non-Goals

- Goal-runner files (subtask 5) and remaining oversized files (subtask 6).
- Changing persistence schema or workflow definitions except moving code
  between files.
- Introducing new ports or modules.

## Dependency Notes

Runs after subtasks 1, 2, and 3 so exhaustive dispatch and typed failures
land before the run loop is split. Independent of subtasks 5 and 6. Must not
run concurrently with subtask 1.

## Validation Strategy

`scripts/validate` after the cluster is split. Compare failure-path test
output before and after to prove parity. Line counts of the listed files
and of files this subtask creates must all be ≤ 500.

## Next Path

Subtask 5 decomposes the goal-runner oversized units.
