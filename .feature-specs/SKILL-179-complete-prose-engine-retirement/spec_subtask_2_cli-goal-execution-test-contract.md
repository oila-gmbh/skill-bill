# SKILL-179 Subtask 2 - CLI goal execution test contract migration

Parent spec: [.feature-specs/SKILL-179-complete-prose-engine-retirement/spec.md](spec.md)
Issue key: SKILL-179

## Scope

Fix the remaining CLI failure from the same prose-to-runtime contract mismatch.

### Failing test

`runtime-kotlin/runtime-cli/src/test/kotlin/skillbill/cli/CliGoalRuntimeTest.kt`,
class `CliGoalExecutionOptionsTest`:

- `goal no terminal outcome marks child workflow blocked` (`:1276`) — expects
  child step `implement`, gets `preplan`

The test drives `goalFixture(subtaskCount = 2)` with
`GoalFixtureAgentRunLauncher(fixture, noTerminalSubtask = 1)`. The expectation
of `implement` is inherited from the retired prose step sequence
(`assess`, `create_branch`, `implement`, ...). The runtime phase sequence begins
`preplan`, `plan`, `implement`, so a child that produces no terminal outcome
blocks at a different phase than the prose engine did.

Establish which phase the runtime engine genuinely blocks at for a child with no
terminal outcome, and whether `preplan` is the correct answer or evidence that
the fixture never advances the child past its first phase. Fix accordingly:
correct the expectation only if `preplan` is contractually right, otherwise fix
the fixture so the child reaches the phase under test.

## Acceptance Criteria

1. `(cd runtime-kotlin && ./gradlew :runtime-cli:test)` passes.
2. The test asserts the phase the runtime engine actually blocks at, justified
   against the runtime phase sequence rather than the retired prose sequence.
3. If the expectation was changed rather than the fixture, the subtask's
   history entry names the runtime contract clause that defines the new value.
4. No other test in the CLI surface drives a runtime workflow with retired
   prose step ids (`assess`, `create_branch`).

## Non-Goals

- The application and persistence surface (subtask 1).
- Changing goal runner production behaviour to preserve a prose-era assertion.

## Dependency Notes

- Independent of subtask 1; both may run in either order.

## Validation Strategy

- `(cd runtime-kotlin && ./gradlew :runtime-cli:test)`
- Grep the CLI test surface for retired prose step ids.

## Next Path

```bash
skill-bill goal SKILL-179
```
