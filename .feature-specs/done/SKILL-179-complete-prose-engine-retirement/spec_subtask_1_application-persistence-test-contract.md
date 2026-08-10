# SKILL-179 Subtask 1 - Application and persistence test contract migration

Parent spec: [.feature-specs/SKILL-179-complete-prose-engine-retirement/spec.md](spec.md)
Issue key: SKILL-179

## Scope

Fix the 12 failures in the application and persistence test surface, all caused
by the `TASK_PROSE` -> `TASK_RUNTIME` enum swap being applied without
translating artifact fixtures to the runtime phase-output contract.

### Failing tests

`runtime-kotlin/runtime-core/src/test/kotlin/skillbill/application/ApplicationPersistencePortTest.kt` (11):

- `workflow service reopens blocked decomposition subtask runtime state on continuation` — expects `reopened`, gets `blocked`
- `workflow service resumes in-progress decomposed subtask by issue key` — expects `already_running`, gets `blocked`
- `workflow service updates decomposition subtask runtime status for blocked and skipped outcomes` — expects `skipped`, gets `blocked`
- `workflow service completes all subtasks without mutating specs` — `DecompositionStandard` cannot be cast to `DecompositionDone`
- `workflow service records blocked status when same branch subtask commit fails` — cannot be cast to `DecompositionBlockedGit`
- `workflow service returns requested terminal subtask outcome without advancing later subtasks` — cannot be cast to `DecompositionSubtaskOutcome`
- `workflow service does not auto commit earlier completed subtasks when explicit subtask requested` — `DecompositionBlockedSubtask` cannot be cast to `DecompositionStandard`
- `workflow service records same branch subtask commit before starting next subtask` — expects subtask 2, gets 1
- `workflow service hydrates implement session summary for continuation payloads` — expects `ftr-001`, gets null
- `workflow service owns implement rows list resume and continuation through ports` — expects `[]`, gets `[plan]`
- `workflow service does not write decomposition projection when durable save fails` — expected `IllegalStateException`, completed successfully

`runtime-kotlin/runtime-application/src/test/kotlin/skillbill/application/FeatureTaskRouterContinuationTest.kt` (1):

- `runtime router continuation after plan preserves identity and supplies only completed plan` — expects `[plan, repository_evidence]`, gets `[plan]`

### Required translation

Replace prose artifact fixtures with runtime phase-output artifacts keyed by
phase id. The runtime contract is
`FeatureTaskRuntimePhaseWorkflowDefinition.requiredArtifactsByStep`
(`:156-164`). A working runtime-native example is
`FeatureTaskRouterContinuationTest.kt:52-55`
(`"preplan_digest" to ...`, `"plan" to ...`).

Two tests name prose concepts in their titles — `hydrates implement session
summary` and `owns implement rows list resume and continuation` — and must be
assessed for whether the behaviour still exists under a single runtime engine
before being adapted. The `repository_evidence` expectation is filtered from
`missingArtifacts` at `WorkflowEngine.kt:203`; establish whether the assertion
or the delivery is wrong rather than editing the assertion to match observed
output.

### Retire versus rewrite

For each test, decide explicitly:

- **rewrite** when the asserted behaviour still exists under the runtime engine
- **delete** when it asserted prose-only behaviour or runtime/prose dual
  maintenance

Never weaken an assertion to make it pass. Changing an expected value to the
observed value is only correct when the runtime contract genuinely defines that
value; state which contract clause justifies it.

## Acceptance Criteria

1. `(cd runtime-kotlin && ./gradlew :runtime-core:test :runtime-application:test)` passes.
2. No retained test supplies `assessment`, `validation_request`,
   `audit_clearance`, or `review_result` as artifacts a runtime step requires.
3. Every retained test's artifact fixture satisfies
   `requiredArtifactsByStep` for the step it drives, so `canResume` is true for
   the state under test.
4. Each deleted test is justified in the subtask's history entry, naming the
   prose-only behaviour it covered.
5. No assertion is weakened to match observed output without citing the runtime
   contract clause that defines the expected value.

## Non-Goals

- Changing runtime production behaviour to satisfy a prose-era assertion.
- Touching the CLI test surface (subtask 2).
- The legacy-row lifecycle defects (subtask 3).

## Dependency Notes

- No dependencies; may start immediately.

## Validation Strategy

- `(cd runtime-kotlin && ./gradlew :runtime-core:test :runtime-application:test)`
- Confirm no new detekt violations in touched files.

## Next Path

```bash
skill-bill goal SKILL-179
```
