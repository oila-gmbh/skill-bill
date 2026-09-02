# SKILL-227: runtime-application architecture hardening

## Intended Outcome

`runtime-application` keeps its clean module boundary and gains an interior that
matches it: no hidden process-wide inputs, no silent degradation on durable
writes, no orchestration state that the database is supposed to own, and no
class that evades the size and layering guards by spreading across files.

The review that motivated this work found the boundary healthy — zero
`skillbill.infrastructure.*` imports, typed sealed results, five `!!` in 58,300
lines — and the interior drifting in four directions:

1. **Metric evasion.** `FeatureTaskRuntimeRunLoop` is one class of ~8,834 lines
   across 58 files, held together by `internal` extension functions and eleven
   mutable fields. The `GoalRunner` cluster is ~7,595 lines across 49 files.
   Every file passes the 500-line ceiling; neither class does. Sibling files
   named `*Extras2.kt` are the signature.
2. **Ambient inputs.** `WorkflowServiceHelpers.repoRoot()` resolves the
   repository from the JVM working directory via `Path.of("").toAbsolutePath()`,
   used across `workflow/` and `decomposition/`. Over thirty sites call
   `Instant.now()` directly rather than an injected `Clock`.
   `GoalRunnerExecutionCoordinator` registers a JVM shutdown hook, spawns a raw
   `Thread`, and calls `UUID.randomUUID()` inside the application layer.
3. **Silent degradation.** Invalid durable phase output becomes `emptyMap()`.
   Decomposition manifest projection failures are swallowed while the
   transaction commits, twenty lines from a `checkNotNull` on the same call.
   Twenty-two constructors default `RuntimeDiagnostics` to a no-op, and
   `FeatureTaskRuntimePhaseGateDependencies` defaults the review driver to one
   that returns `verdict: approved`. Those defaults are all bound today, so they
   are latent regressions rather than live bugs — deleting a binding leaves a
   green build and a self-approving review.
4. **Cyclic packages.** `featuretask` ↔ `goalrunner` (23/47 imports),
   `goalrunner` ↔ `workflow` (48/6), `workflow` ↔ `decomposition` (25/13),
   `review` ↔ `evidence` (10/11). No area can be tested or extracted alone.

A fifth item is a correctness bug rather than a smell: the component has no
scopes, so all 74 `RuntimeComponent` accessors are getters that rebuild their
object graph per access. `GoalRunner`'s in-memory
`validationQualityRetries` map therefore resets on every access, and
`MAX_VALIDATION_QUALITY_RETRIES` is not an enforceable budget.

## Acceptance Criteria

1. Architecture tests measure logical types, not files: a class plus every file
   declaring extensions on it counts as one unit against the production line
   ceiling, with a recorded baseline that lists current offenders and can only
   shrink.
2. An architecture test pins the allowed dependency direction between
   `skillbill.application.<area>` packages and fails on a new cycle, with a
   recorded baseline of today's cycles that can only shrink.
3. No `@Inject` constructor or dependency-bag field in `runtime-application`
   carries a default argument; an architecture test enforces it, and every
   removed default is replaced by an explicit binding in `RuntimeComponent`.
4. Dependency-injection scoping is explicit: adapters holding a cache,
   connection, or lease are scoped and reused; every service left unscoped is
   recorded in `../../../runtime-kotlin/ARCHITECTURE.md` as a deliberate choice.
5. The repository root reaches `workflow/`, `decomposition/`, and every
   consumer through an injected coordinate. `Path.of("").toAbsolutePath()` and
   `WorkflowServiceHelpers.repoRoot()` no longer exist in `runtime-application`.
6. Time reaches every application class through an injected `Clock`, enforced by
   an architecture test banning `Instant.now()`, `LocalDateTime.now()`, and
   `Clock.systemUTC()` in `runtime-application` main source.
7. JVM shutdown-hook registration, thread creation, and identifier generation
   move behind ports; `GoalRunnerExecutionCoordinator` calls no
   `Runtime.getRuntime()`, `Thread`, or `UUID` API directly.
8. A decomposition-manifest projection write that fails surfaces a typed error
   or a recorded degradation, and no durable transaction commits while its disk
   projection is silently lost. `update`, `continue`, and blocked-phase retry
   share one policy.
9. Unparseable or schema-invalid durable phase output fails loudly or records a
   degradation; it never becomes `emptyMap()`.
10. Validation-quality retry budget, re-attempt cause, and causing-loop entry
    are durable, so the budget survives process restart and repeated component
    access.
11. `FeatureTaskRuntimeRunLoop` and `GoalRunner` are decomposed into injected
    collaborators that own their own state; per-run mutable state lives in an
    explicit run-scoped object or sealed state type, not in fields on a
    DI-constructed service.
12. The logical-type ceiling and package-cycle baselines are empty at the end of
    the feature.
13. Every dependency bag over seven collaborators is either split by
    responsibility or replaced by role-scoped ports; renaming a parameter list
    does not count.
14. `skill-bill validate` passes, the runtime module test suites pass, and no
    architecture-test suppression or exemption is added without an entry in
    `PrincipleEnforcementInventory` and `ARCHITECTURE.md`.

## Constraints

- Behavior of the feature-task phase machine, goal runner, and review pipeline
  stays observable-equivalent. This is a structural and integrity feature, not a
  product change.
- New guards land green against a recorded baseline so the build never sits red
  between subtasks. Baselines shrink; they never grow.
- Loud-fail changes follow `../../../docs/observability-policy.md`: every remaining
  fallback emits a record through a bound `RuntimeDiagnostics`.
- Ports added here belong in `runtime-ports` with adapters in the matching
  `runtime-infra-*` module. Application code gains no new concrete dependency.
- Contract or schema changes follow the runtime-contract rules: YAML schema
  first, Kotlin `*_CONTRACT_VERSION`, parity test, typed
  `Invalid<Contract>SchemaError`, loud-fail at every parse seam.
- Existing architecture tests keep passing throughout; the 500-line per-file
  ceiling is not relaxed to accommodate re-merged classes.

## Non-Goals

- Rewriting phase prompt content, review rubrics, or agent directives.
- Changing the workflow phase graph, gate ordering, or commit finalisation model.
- Adding test coverage for its own sake. `bill-unit-test-value-check` still
  applies: each new test names the bug it catches.
- Migrating other modules' interiors. `runtime-domain`, `runtime-ports`, and the
  `runtime-infra-*` modules change only where a new port or adapter requires it.
- Replacing kotlin-inject or introducing a second DI mechanism.
- Reworking `ParallelCodeReviewRunner`'s review semantics; only its dependency
  surface and file layout are in scope.

## Validation Strategy

- Architecture tests are the primary proof: logical-type ceiling, package
  acyclicity, injected-clock, and no-default-`@Inject` guards each ship with an
  acceptance case and a rejection case.
- Loud-fail seams get tests that assert the typed error or recorded degradation,
  not the absence of a crash: corrupt phase output, failed manifest projection,
  and unreadable findings ledger.
- Retry-budget durability is proven across a simulated restart, which is the
  bug the in-memory map hides today.
- Repository-root injection is proven by running a workflow operation from a
  working directory that is not the repository root.
- `skill-bill validate`, `./gradlew compileKotlin`, and the runtime module test
  suites gate every subtask.

## Delivery Plan

1. Guardrails and DI hardening: new architecture tests with recorded baselines,
   removal of every `@Inject` default argument, explicit bindings, and a
   documented scoping decision.
2. Ports and loud-fail seams: repository root, clock, process lifecycle, and
   identifier generation move behind ports; swallowed durable-write and parse
   failures become typed errors or recorded degradations; retry budget becomes
   durable.
3. Decomposition: `FeatureTaskRuntimeRunLoop` and `GoalRunner` become real
   collaborator graphs, dependency bags collapse into role ports, package cycles
   break, and both baselines reach zero.
