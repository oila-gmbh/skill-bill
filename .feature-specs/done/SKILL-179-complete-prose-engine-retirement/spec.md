# SKILL-179 - Complete the prose engine retirement

Issue key: SKILL-179
Predecessor: SKILL-175 (abandoned with subtasks 5-7 incomplete)

## Context

SKILL-175 removed the prose feature engine and all OpenCode/zcode product
surface. Subtasks 1-4 landed and hold: the repo-wide guard test
`SKILL-175 no live prose-engine or opencode-zcode product surface remains`
(`runtime-kotlin/runtime-core/src/test/kotlin/skillbill/architecture/RuntimeArchitectureTest.kt:992`)
passes, prose skills and native agents are deleted, and the deliberate
legacy-read retentions are in place and documented
(`WorkItem.kt:13-16` frozen historical status literals,
`TelemetryConstants.kt:19`, `IdeStatusService.kt:132`).

SKILL-175 was abandoned before subtasks 5-7 completed. At its final commit
`fb973bf42`, `(cd runtime-kotlin && ./gradlew check)` fails with 13 test
failures. This feature finishes that work.

### Root cause of the 13 failures

The failing tests were migrated from `WorkflowFamilyKind.TASK_PROSE` to
`WorkflowFamilyKind.TASK_RUNTIME` by enum substitution alone. Their artifact
fixtures were never translated. The two families have incompatible artifact
contracts:

- the retired prose family required named artifacts: `assessment`,
  `validation_request`, `audit_clearance`, `review_result`
- the runtime family requires **phase-output keys**
  (`FeatureTaskRuntimePhaseWorkflowDefinition.kt:156-164`), for example
  `validate` requires `[implement, audit]`

So `resumeView` reports non-empty `missingArtifacts`, `canResume` is false
(`WorkflowEngine.kt:204`), and `continueStatusFor` returns `"blocked"` instead
of `reopened` / `already_running` (`WorkflowEngine.kt:589`). Because the
manifest then never advances, six tests additionally receive
`WorkflowContinueResult.DecompositionStandard` where they expect
`DecompositionDone`, `DecompositionBlockedGit`, or
`DecompositionSubtaskOutcome`.

This is one mechanical job left half-finished, not thirteen independent
defects.

### Separately: three legacy-row lifecycle gaps

Operating SKILL-175's own goal surfaced three defects in how a legacy
prose-mode workflow row is handled now that the prose engine is gone. All three
were reproduced against a real database:

1. **Silent `no_match` over a live goal.** Goal-parent discovery filters
   `listFeatureTaskWorkflows(FeatureTaskWorkflowMode.RUNTIME, ...)`
   (`DecompositionWorkflowRuntimeLookup.kt:92`, and again at `:44`), so a
   legacy prose-mode goal parent is invisible. `skill-bill feature-task lookup`
   then returns `no_match` for an issue key that owns durable state with six
   completed subtasks. `bill-feature` reads `no_match` as new work and proceeds
   to fresh spec preparation. This is the dangerous one because it is silent.
2. **An identity-less runtime row hard-crashes the lookup gate.** The candidate
   query admits `identities.workflow_id IS NULL AND workflows.mode = 'runtime'`
   (`WorkflowStateStore.kt:374-384`) and `project()` then throws
   `InvalidFeatureTaskExecutionIdentitySchemaError`
   (`FeatureTaskContinuationLookupService.kt:115`). A runner killed between
   creating a workflow row and writing its identity row permanently wedges
   lookup for that issue key. `feature-task repair-identity` exists but cannot
   be aimed until the operator can read the state the crash is hiding.
3. **A legacy prose-mode goal parent cannot be terminalized.**
   `skill-bill feature-task abandon` rejects it with
   `is mode='prose', not 'runtime'`
   (`WorkflowStateStore.kt:429`), and `goal reset` discards progress rather
   than terminalizing. There is no supported way to close out such a row.

Note that `mode='prose'` on a goal parent is load-bearing, not drift: it is what
keeps the legacy parent out of runtime-mode discovery. Flipping it to `runtime`
makes `goal status` throw `InvalidWorkflowStateSchemaError` because the row
carries retired step ids (`assess`, `create_branch`) and a `paused` status
outside the runtime enum. Any fix must preserve that exclusion while still
making the row discoverable, terminalizable, and honestly reported.

## Intended Outcome

`(cd runtime-kotlin && ./gradlew check)` is green, no test enforces the retired
prose artifact contract, and a legacy prose-mode workflow row is discoverable,
truthfully reported, and terminalizable through supported commands.

## Acceptance Criteria

1. `(cd runtime-kotlin && ./gradlew check)` passes with no test failures and no
   detekt violations.
2. No test drives a runtime workflow with retired prose artifact fixtures
   (`assessment`, `validation_request`, `audit_clearance`, `review_result`) as
   the artifacts a runtime step requires.
3. Every test retained from the prose era asserts behaviour that still exists
   under a single runtime engine; tests whose behaviour no longer exists are
   deleted rather than adapted, with the deletion justified in the subtask's
   history entry.
4. `skill-bill feature-task lookup <key> --repo-root <root>` reports a
   continuation result for an issue key whose goal parent is a legacy
   prose-mode row, and never reports `no_match` for an issue key that owns
   non-terminal durable goal state.
5. An identity-less runtime workflow row does not crash the lookup gate; the
   operator receives an actionable result naming the row and the
   `feature-task repair-identity` remedy.
6. A legacy prose-mode goal parent can be terminalized through a supported
   command while preserving its durable history.
7. The repo-wide guard test for prose-engine and OpenCode/zcode product tokens
   still passes, and its allowlist is not widened to accommodate this work.
8. `skill-bill validate` and `scripts/validate_agent_configs` pass.

## Constraints

- The documented legacy-read retentions from SKILL-175 subtask 1 stay. Historic
  prose rows must keep listing in the work list and telemetry. Do not delete the
  frozen literal sets.
- Do not widen the guard test allowlist.
- Do not repair durable state by hand-editing `decomposition-manifest.yaml` or
  the workflow database. Fixes land as code plus supported commands.

## Non-Goals

- Re-adding a prose feature engine or OpenCode/zcode runtime support.
- Migrating existing legacy prose rows on operator machines to runtime mode.
  Flipping mode is demonstrated to break read paths; discovery must handle the
  legacy shape instead.
- Editing `.feature-specs/done/**` or the abandoned SKILL-175 spec tree beyond
  a pointer to this feature.

## Execution

Four dependency-ordered subtasks:

1. Application and persistence test contract migration (12 failures)
2. CLI goal execution test contract migration (1 failure)
3. Legacy prose-mode row lifecycle: discovery, diagnosis, terminalization
4. Final sweep, parity locks, and gates

## Next Path

```bash
skill-bill goal SKILL-179
```
