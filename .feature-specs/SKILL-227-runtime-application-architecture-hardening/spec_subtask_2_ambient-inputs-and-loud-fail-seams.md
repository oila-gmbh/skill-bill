# SKILL-227 · Subtask 2: Ambient inputs and loud-fail seams

## Scope

Remove the process-wide inputs that `runtime-application` reads directly, and
close the seams where a durable write or a parse failure degrades in silence.

**Ambient inputs behind ports.**

- *Repository root.* `skillbill.application.workflow.WorkflowServiceHelpers`
  resolves the repository with `Path.of("").toAbsolutePath()`, and `workflow/`
  and `decomposition/` consume it. The root becomes an injected coordinate
  carried on the runtime context. Delete `repoRoot()` and every direct
  `Path.of("")` resolution in the module.
- *Clock.* Replace the thirty-plus direct `Instant.now()` and
  `LocalDateTime.now()` calls — `FeatureTaskRuntimeWorkerCoordinator` among them
  — with an injected `Clock`, and empty subtask 1's clock baseline.
- *Process lifecycle and identity.* `GoalRunnerExecutionCoordinator` calls
  `Runtime.getRuntime().addShutdownHook`, constructs a raw `Thread`, and calls
  `UUID.randomUUID()`. Each moves behind a port in `runtime-ports` with an
  adapter in the matching infrastructure module.

Ports declared here follow the module rules: interface in `runtime-ports`,
adapter in `runtime-infra-*`, explicit binding in `RuntimeComponent`. Subtask 1's
no-defaults guard prevents any of them from acquiring a convenience default.

**Loud-fail seams.**

- *Manifest projection.* `WorkflowService.update` and `continue` swallow a
  failed decomposition-manifest disk projection while the durable transaction
  commits, roughly twenty lines from the `checkNotNull` that blocked-phase retry
  applies to the same call. All three paths adopt one policy: a typed error, or
  a recorded degradation through a bound `RuntimeDiagnostics`.
- *Phase output.* `FeatureTaskRuntimeRunStateOutputExtensions` turns
  unparseable or schema-invalid durable phase output into `emptyMap()`, so a
  corrupt record is indistinguishable from an empty one. It fails loudly or
  records the degradation, and never returns a silent empty map.
- *Progress and ledger reads.* A progress read that fails is distinguished from
  a row that does not exist. An invalid findings-ledger schema is recorded
  rather than dropped.
- *Observability emission.* Failures inside the observability emitter itself
  emit a record rather than vanishing, per `docs/observability-policy.md`.

**Durable retry budget.** `GoalRunner` holds `validationQualityRetries`,
`pendingReAttemptCause`, and `pendingCausingLoopEntry` as instance maps. With the
component unscoped these reset on every service access, so
`MAX_VALIDATION_QUALITY_RETRIES` bounds nothing and the `clear()` calls are dead.
Move the budget and both pending causes to durable storage keyed by run, and
delete the fields and their `clear()` calls.

**Static writer.** `DecompositionManifestWriter` is an `object` that owns file
I/O and policy, so it cannot be substituted in a test. Convert it to an
`@Inject` service with its I/O behind an existing port.

## Acceptance Criteria

1. `WorkflowServiceHelpers.repoRoot()` and every `Path.of("").toAbsolutePath()`
   are gone from `runtime-application`; the repository root arrives as an
   injected coordinate.
2. A workflow operation invoked from a working directory other than the
   repository root resolves the same paths as one invoked from the root.
3. Subtask 1's clock baseline is empty: no `Instant.now()`,
   `LocalDateTime.now()`, or `Clock.systemUTC()` remains in
   `runtime-application` main source, and the guard runs with no exemptions.
4. `GoalRunnerExecutionCoordinator` calls no `Runtime.getRuntime()`, `Thread`
   constructor, or `UUID` API; shutdown-hook registration, thread execution, and
   identifier generation each go through a port with an infrastructure adapter
   and an explicit binding.
5. A failed decomposition-manifest projection produces a typed error or a
   recorded degradation, and `update`, `continue`, and blocked-phase retry apply
   the same policy.
6. Unparseable or schema-invalid durable phase output produces a typed error or
   a recorded degradation; no code path returns `emptyMap()` for it.
7. A failed progress read is distinguishable from an absent progress row, and an
   invalid findings-ledger schema is recorded rather than dropped.
8. The validation-quality retry budget and both pending causes are durable and
   keyed by run; `GoalRunner` holds no mutable map for them, and the budget is
   enforced across a process restart.
9. `DecompositionManifestWriter` is an injected service, not an `object`, with
   its file I/O behind a port.
10. `./gradlew compileKotlin`, the runtime module test suites, and
    `skill-bill validate` pass; no architecture-test baseline grows.

## Non-Goals

- Splitting or relocating `FeatureTaskRuntimeRunLoop`, `GoalRunner`, or any
  dependency bag. That is subtask 3.
- Breaking package cycles or shrinking the acyclicity baseline.
- Changing which phases run, in what order, or under what gate.
- Adding new durable contracts beyond what the retry budget needs; if a schema
  change is required, it follows the runtime-contract rules with a version
  constant, parity test, and typed parse error.

## Dependency Notes

Depends on subtask 1. The clock guard's baseline is the work list for the clock
change, and the no-defaults guard is what keeps the new repository-root,
lifecycle, and identifier ports from being defaulted back to ambient behavior
the moment a binding is forgotten.

## Validation Strategy

- A workflow operation run from a non-root working directory proves the
  repository-root injection. This is the failure the current
  `Path.of("").toAbsolutePath()` produces whenever the JVM is not launched from
  the repository root.
- A restart simulation proves the retry budget survives; today the budget resets
  on every component access, which is what makes the bound unenforceable.
- Failure-injection tests assert the typed error or the recorded diagnostic for
  a failed manifest projection and for corrupt phase output, so a corrupt record
  can no longer read as an empty one.
- A fixed `Clock` in tests makes previously time-dependent assertions
  deterministic.
- `./gradlew compileKotlin`, runtime module tests, `skill-bill validate`.

## Next Path

Subtask 3 decomposes the two god objects and breaks the package cycles, with the
per-run state this subtask made durable no longer holding them together.
