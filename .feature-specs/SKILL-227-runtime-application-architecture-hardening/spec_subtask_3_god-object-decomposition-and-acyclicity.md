# SKILL-227 · Subtask 3: God-object decomposition and package acyclicity

## Scope

Turn the two distributed god objects into collaborator graphs and break the
package cycles, driving both of subtask 1's baselines to zero.

**`FeatureTaskRuntimeRunLoop`.** One class of ~8,834 lines across 58 files,
including `FeatureTaskRuntimeRunLoopExtras2.kt`. It holds eleven mutable fields
— `internal var` flags plus `mutableMapOf` accumulators — written from files it
does not declare. Decompose it into injected collaborators along the seams the
extension files already imply: phase dispatch, gate evaluation, remediation
loops, settlement, and observability. Per-attempt state moves into an explicit
run-scoped object or a sealed state type passed between collaborators; no
DI-constructed service keeps per-run mutable fields.

**`GoalRunner` cluster.** ~7,595 lines across 49 files. Same treatment: split by
responsibility, give per-run state an explicit owner, and stop constructing
collaborators eagerly in the constructor.

**Dependency bags.** Collapse the bags whose size records a missing boundary
rather than a genuine need: `ParallelCodeReviewRunnerDeps` (17),
`FeatureTaskRuntimePhaseGateDependencies` (16), `GoalPlanningSweepDeps` (13),
`GoalRunnerDeps` (12). Each becomes role-scoped ports that name what the
collaborator needs. Renaming a parameter list does not satisfy this; the test is
whether a caller can depend on one role without seeing the rest.

**Package cycles.** Break `featuretask` ↔ `goalrunner` (23/47 imports),
`goalrunner` ↔ `workflow` (48/6), `workflow` ↔ `decomposition` (25/13), and
`review` ↔ `evidence` (10/11), using shared abstractions in the depended-upon
direction rather than new bidirectional helpers. The acyclicity baseline ends
empty.

**Misplaced adapters.** The `WorkflowGoalRunner*` persistence adapters live in
`runtime-application`. Move them to the matching infrastructure module so the
application layer holds orchestration only.

**Agent identity branching.** Replace the remaining identity branch on agent
name with an injectable strategy on the request, per the runtime agent-behavior
rule in `AGENTS.md`.

**DTO aggregate.** `GoalRunnerRequests.kt` collects 482 lines of unrelated
request types in one file. Split it so each request type sits with the boundary
it serves.

## Acceptance Criteria

1. `FeatureTaskRuntimeRunLoop` is decomposed into injected collaborators, each
   under the production line ceiling as a logical type, and none of them holds
   per-run mutable fields on a DI-constructed instance.
2. The `GoalRunner` cluster is decomposed on the same terms, and `GoalRunner`
   no longer eagerly constructs collaborators in its constructor.
3. Per-attempt and per-run state lives in an explicit run-scoped object or
   sealed state type; no `internal var` flag on a service is written from a file
   that does not declare it.
4. Subtask 1's logical-type ceiling baseline is empty and the guard runs with no
   exemptions.
5. Subtask 1's package-acyclicity baseline is empty: no cycle remains between
   `skillbill.application.<area>` packages.
6. `ParallelCodeReviewRunnerDeps`, `FeatureTaskRuntimePhaseGateDependencies`,
   `GoalPlanningSweepDeps`, and `GoalRunnerDeps` are replaced by role-scoped
   ports, each collaborator depending only on the role it uses.
7. The `WorkflowGoalRunner*` persistence adapters live in the infrastructure
   module, not in `runtime-application`.
8. Agent-specific behavior is selected by an injectable strategy on the request;
   no identity branch on agent name remains in the process runner path.
9. `GoalRunnerRequests.kt` is split so each request type sits with the boundary
   it serves.
10. No `*Extras*.kt`, `*Support.kt`, or `*Helpers.kt` file in the touched areas
    exists solely to keep a class under the per-file ceiling.
11. Feature-task and goal-runner behavior is observable-equivalent: existing
    runtime module test suites pass unchanged except where a test asserted the
    structure being replaced.
12. `./gradlew compileKotlin`, the runtime module test suites, and
    `skill-bill validate` pass with no new suppression, and
    `PrincipleEnforcementInventory` records no new exemption.

## Non-Goals

- Changing the phase graph, gate ordering, remediation-loop policy, or commit
  finalisation model. Structure moves; behavior does not.
- Adding features or new phases.
- Rewriting review rubrics, prompt content, or agent directives.
- Restructuring modules other than the adapter move named above.
- Rewriting passing tests to match a new internal shape when they assert
  observable boundaries. Tests that assert the replaced structure are the
  exception and should be re-pointed at the boundary instead.

## Dependency Notes

Depends on subtasks 1 and 2, and cannot be specified before them.

Subtask 1 defines the target: the logical-type ceiling and the acyclicity
baseline are what "decomposed" means here, and without them a re-split is
unverifiable. Subtask 2 determines what the collaborators own — the mutable
retry budget and pending causes become durable there, so drawing collaborator
boundaries first would mean drawing them around state that is about to move.

## Validation Strategy

- The two baselines reaching empty is the primary proof, and it is mechanical.
- Existing runtime module suites act as the behavioral regression net; they pass
  unchanged, which is the assertion that this subtask moved structure only.
- New tests target the boundaries the decomposition creates, not the split
  itself. `bill-unit-test-value-check` applies: each names the bug it catches.
  A test asserting that a collaborator exists is the failure mode to avoid.
- A test that a run-scoped state object is not shared across concurrent runs,
  which is the class of bug the current `internal var` fields invite.
- `./gradlew compileKotlin`, runtime module tests, `skill-bill validate`.

## Next Path

Feature complete. Both baselines are empty and the guards from subtask 1 hold
the interior in place without exemptions.
