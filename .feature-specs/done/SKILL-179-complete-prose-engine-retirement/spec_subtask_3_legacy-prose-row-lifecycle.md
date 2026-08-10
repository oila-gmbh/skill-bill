# SKILL-179 Subtask 3 - Legacy prose-mode row lifecycle

Parent spec: [.feature-specs/SKILL-179-complete-prose-engine-retirement/spec.md](spec.md)
Issue key: SKILL-179

## Scope

Close the three lifecycle gaps that appear once the prose engine is gone but
prose-mode rows remain in operator databases. All three were reproduced against
a real database while operating SKILL-175's own goal.

### Gap 1 - Silent `no_match` over a live goal (highest severity)

`goalContinuationFor` resolves the parent through
`findDecomposedParentWorkflow` / `findDecomposedParentOrCorruptFallback`, both
of which enumerate `listFeatureTaskWorkflows(FeatureTaskWorkflowMode.RUNTIME, ...)`
(`DecompositionWorkflowRuntimeLookup.kt:92` and `:44`). A legacy prose-mode goal
parent is therefore invisible, and
`skill-bill feature-task lookup <key> --repo-root <root>` returns:

```json
{ "result": "no_match" }
```

for an issue key whose goal owns durable state with six completed subtasks.
`bill-feature` treats `no_match` as new work and proceeds to fresh spec
preparation over in-flight work. The failure is silent, which makes it worse
than a crash.

Constraint: `mode='prose'` on a goal parent is load-bearing. It is what excludes
the legacy row from runtime-mode paths whose schema it cannot satisfy. Setting
it to `runtime` was tested and makes `goal status` throw
`InvalidWorkflowStateSchemaError` — the row carries retired step ids (`assess`,
`create_branch`) and status `paused`, none of which are in the runtime enums.
Discovery must recognise the legacy shape without asserting the runtime schema
over it.

### Gap 2 - Identity-less runtime row crashes the lookup gate

`findFeatureTaskCandidates` admits identity-less rows via
`identities.workflow_id IS NULL AND (workflows.mode = 'runtime' OR ...)`
(`WorkflowStateStore.kt:374-384`). `project()` then hard-throws
`InvalidFeatureTaskExecutionIdentitySchemaError`
(`FeatureTaskContinuationLookupService.kt:115`).

Reproduced: a goal runner killed at `18:46:07` had created workflow row
`wftr-20260809-184605-8z2c` two seconds earlier and died before writing its
execution identity row. Every subsequent `feature-task lookup` for that issue key
crashed. The exclusion heuristic in that query assumes goal parents are
prose-mode, which is exactly the assumption this feature's predecessor removed.

`feature-task repair-identity` already exists as the remedy, but the crash
prevents the operator from learning which row to aim it at.

### Gap 3 - No terminalization path for a legacy prose-mode goal parent

`skill-bill feature-task abandon <parent-id>` fails:

```
InvalidWorkflowStateSchemaError: Feature-task workflow '<id>' is mode='prose', not 'runtime'.
  at FeatureTaskWorkflowStateStore.getFeatureTaskWorkflowAsMode(WorkflowStateStore.kt:429)
```

`goal reset` discards progress rather than terminalizing, so it is not a
substitute. An operator retiring a legacy goal has no supported command.

## Acceptance Criteria

1. A goal parent stored as a legacy prose-mode row is discoverable by
   continuation lookup, and `feature-task lookup` reports `goal_continuation`
   with its status, current subtask, and complete/pending/blocked counts.
2. `feature-task lookup` never returns `no_match` for an issue key that owns
   non-terminal durable goal state in the queried repository.
3. Discovery of a legacy prose-mode row does not assert the runtime workflow
   schema against it; `goal status` continues to work for such a row and reports
   its true pause state, including a `runner_interrupted` pause.
4. An identity-less runtime workflow row yields an actionable lookup result that
   names the row and directs the operator to `feature-task repair-identity`,
   instead of throwing out of the lookup gate.
5. A legacy prose-mode goal parent can be terminalized through a supported
   command while preserving its durable history, with an operator reason
   recorded.
6. Regression tests cover all three gaps: a prose-mode parent that must be
   found, an identity-less runtime row that must not crash lookup, and
   terminalization of a prose-mode parent.
7. No fix flips a legacy row's stored `mode` to `runtime` as its mechanism.

## Non-Goals

- Migrating existing legacy rows on operator machines.
- Re-adding prose workflow definitions to satisfy the legacy schema.
- Changing the runtime schema to accept retired step ids.

## Dependency Notes

- Independent of subtasks 1 and 2; touches production code rather than test
  fixtures.

## Validation Strategy

- New regression tests for each of the three gaps.
- `(cd runtime-kotlin && ./gradlew check)`
- Manual verification against a database containing a legacy prose-mode goal
  parent, confirming lookup, status, and terminalization.

## Next Path

```bash
skill-bill goal SKILL-179
```
