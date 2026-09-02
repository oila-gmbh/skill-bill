# SKILL-227 · Subtask 1: Architecture guardrails and DI hardening

## Scope

Put the measurement and wiring rules in place before any structural change, so
later subtasks are enforced rather than trusted.

**Guardrails.** Extend the architecture suite under
`../../../runtime-kotlin/runtime-core/src/test/kotlin/skillbill/architecture`:

- *Logical-type line ceiling.* `ProductionFileLineCeilingArchitectureTest`
  measures files, which is why `FeatureTaskRuntimeRunLoop` sits at ~8,834 lines
  across 58 files and passes. Add a sibling guard that attributes each file's
  lines to the type it declares members or extensions on, and applies the
  ceiling to that total. Ship with a recorded baseline file listing today's
  offenders and their current sizes; the test fails when a listed unit grows or
  an unlisted unit exceeds the ceiling.
- *Package acyclicity.* Add a guard over `skillbill.application.<area>` import
  edges that fails on any cycle, with a recorded baseline holding today's four:
  `featuretask` ↔ `goalrunner`, `goalrunner` ↔ `workflow`, `workflow` ↔
  `decomposition`, `review` ↔ `evidence`.
- *Injected clock.* Ban `Instant.now()`, `LocalDateTime.now()`, and
  `Clock.systemUTC()` in `runtime-application` main source, with a recorded
  baseline of the existing call sites. Subtask 2 empties it.
- *No `@Inject` defaults.* Fail on any default argument in an `@Inject`
  constructor or in a dependency-bag data class consumed by one. This guard
  ships with an empty baseline because this subtask fixes every site.

Register all four in `PrincipleEnforcementInventory` and document them in
`../../../runtime-kotlin/ARCHITECTURE.md`.

**DI hardening.** Delete every default argument from `@Inject` constructors and
dependency bags in `runtime-application`, and add the corresponding explicit
binding in `skillbill/di/RuntimeComponent.kt`. The sites that matter:

- `RuntimeDiagnostics = NoopRuntimeDiagnostics` across 22 constructors,
  including `ReviewService` and `WorkflowService`.
- `FeatureTaskRuntimePhaseGateDependencies.reviewDriver =
  FeatureTaskRuntimeReviewDriver.EMPTY`, whose `EMPTY` returns
  `verdict: approved`. Delete the `EMPTY` constant with the default.
- `FeatureTaskRuntimeUnreadableDiffResolver? = null` and the equivalent
  nullable-port defaults, which degrade to unreadable diffs when unbound.
- `WorkflowService`'s `NoopWorkflowGitOperations` and
  `NoopGoalObservabilityEventValidator` defaults.
- `Clock = Clock.systemUTC()` defaults, replaced by a bound `Clock`.

Keep the no-op implementations as named, explicitly bound types where a genuine
no-op is the intended production behavior; the point is that the binding is
visible in the composition root, not inferred from a parameter default.

**Scoping.** The generated component exposes all 74 accessors as
`get() = ...`, rebuilding each graph per access. Introduce a runtime scope
annotation and apply it to services and adapters that hold a cache, connection,
lease, or any state expected to outlive one call. For every service left
unscoped, record in `ARCHITECTURE.md` that per-access construction is deliberate.
`GoalRunner`'s in-memory retry map is the known casualty; this subtask only
needs the scoping decision, and subtask 2 makes the budget durable regardless.

## Acceptance Criteria

1. A logical-type line-ceiling architecture test attributes extension-declaring
   files to their receiver type and applies the production ceiling to the
   combined total, failing when a baselined unit grows or a non-baselined unit
   exceeds the ceiling.
2. A package-acyclicity architecture test over `skillbill.application.<area>`
   fails on any import cycle not present in its recorded baseline.
3. An architecture test bans `Instant.now()`, `LocalDateTime.now()`, and
   `Clock.systemUTC()` in `runtime-application` main source, with a recorded
   baseline of existing sites.
4. An architecture test fails on any default argument in an `@Inject`
   constructor or in a dependency bag consumed by one, and it passes with an
   empty baseline.
5. No `@Inject` constructor or dependency bag in `runtime-application` carries a
   default argument; each removed default has an explicit `RuntimeComponent`
   binding.
6. `FeatureTaskRuntimeReviewDriver.EMPTY` no longer exists, and no code path can
   reach an auto-approving review verdict through an unbound dependency.
7. A runtime scope annotation exists and is applied to every service or adapter
   holding a cache, connection, or lease; services left unscoped are listed in
   `ARCHITECTURE.md` with the reason.
8. All four guards are registered in `PrincipleEnforcementInventory` and
   documented in `../../../runtime-kotlin/ARCHITECTURE.md`.
9. `./gradlew compileKotlin`, the runtime module test suites, and
   `skill-bill validate` pass with no new suppression.

## Non-Goals

- Moving or splitting any production class. The baselines record today's state;
  shrinking them is subtask 3's work.
- Removing any `Instant.now()` call site. That is subtask 2.
- Changing the behavior of any bound no-op implementation.
- Replacing kotlin-inject or restructuring `RuntimeComponent` beyond adding
  bindings and scope annotations.

## Dependency Notes

No dependencies. This subtask must land first: the guards define the target that
subtasks 2 and 3 shrink, and the no-defaults rule is what stops subtask 2's new
ports from being silently defaulted back into ambient behavior.

## Validation Strategy

- Each guard ships an acceptance case and a rejection case. The rejection case
  for the logical-type ceiling uses a synthetic type split across two fixture
  files, which is the exact evasion the file-based test misses.
- The no-defaults guard's rejection case is an `@Inject` constructor with a
  no-op default, the shape that lets `FeatureTaskRuntimeReviewDriver.EMPTY`
  auto-approve reviews when a binding is dropped.
- A component test asserts that a scoped service returns the same instance
  across two accessor reads, which is the bug behind the unenforceable retry
  budget.
- `./gradlew compileKotlin`, runtime module tests, `skill-bill validate`.

## Next Path

Subtask 2 removes the ambient inputs and silent degradations that the clock
baseline and the no-defaults rule now make visible.
