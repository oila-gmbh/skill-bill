# SKILL-229: runtime-cli architecture hardening

## Intended Outcome

`runtime-cli` keeps the thin, adapter-free boundary it has today and gains an
interior that matches it: one owner per run-scoped setting, no process-wide
inputs read behind the injected ones, no destructive command that reports
success over a failed mutation, and a command tree whose areas can be compiled
and tested without the whole tree.

The review that motivated this work found the boundary in better shape than
`runtime-application`'s. `runtime-cli` is 10,928 lines of main source across 100
files in 17 packages, with zero `skillbill.infrastructure.*` imports, one `!!`,
one `exitProcess` and five direct stdout writes — all in `Main.kt` — and 37
`catch` clauses that every one of them names a specific exception type. No file
exceeds the 500-line ceiling; the largest is `GoalCliRunCommands.kt` at 379.
No `@Inject` constructor takes more than nine collaborators. The interior
drifts in five directions instead:

1. **Split ownership of every run-scoped setting.** Six settings exist twice.
   `CliRuntimeContext` carries `dbPathOverride`, `environment`, `userHome`,
   `externalCommandRunner`, `liveStdout`, and `liveStderr`, becomes a
   `RuntimeContext`, and is captured into `RuntimeComponent` *before* argument
   parsing. `CliRunState` carries the same six as mutable `var` fields that
   Clikt writes during `SkillBillCommand.run()`. Reconciliation is per-call-site
   and inconsistent: `SQLiteDatabaseSessionFactory` does
   `dbOverride ?: resolvedContext.dbPathOverride`, `InstallCliCommands.kt:176`
   does `state.dbOverride ?: runtimeContext.dbPathOverride`, and the roughly
   forty `state.userHome` sites reconcile nothing. `CliRuntime.run` copies five
   of the six context fields onto `CliRunState` and never copies
   `dbPathOverride` at all.

   The failure mode is a command that resolves home or db through an injected
   adapter rather than through `state`: it silently gets the pre-parse value.
   `RemoveCliCommandTest` pins CLI-side precedence for `remove` by setting
   `CliRuntimeContext(userHome = contextHome)` against `--home selectedHome`,
   which is proof the divergence is real and load-bearing. The paths that
   resolve home through `EnvironmentContext.userHome` instead —
   `FileSystemReviewInputSource` tilde expansion,
   `FileSystemReviewNativeAgentPreflight`, `FileExternalAddonSourceConfigStore`,
   `FileSystemInstalledPlatformPackCatalog` — have no `--home` coverage at all.
   All sixteen `--home` tests exercise install, uninstall, and remove.

2. **Ambient inputs behind the injected ones.** `CliRunState` and
   `CliRuntimeContext` each default `environment` to `System.getenv()` and
   `userHome` to `Path.of(System.getProperty("user.home"))`, so an
   `@Inject`-constructed `CliRunState` is seeded from the JVM before anything
   overrides it.

   The repository root is the sharpest case, because the injected coordinate now
   exists and `runtime-cli` is the layer that ignores it. SKILL-227 added
   `repositoryRoot` to `CliRuntimeContext`, resolved once through
   `canonicalRepositoryRoot`, and threaded it into `RuntimeContext` — so
   `runtime-application` and the `runtime-infra-*` modules receive an injected
   root. `CliRunState` carries no `repositoryRoot`, so the commands themselves
   have nothing injected to read and fall back to the process working directory
   at fourteen sites via `Path.of("")` — ten in `goal/` (seven of them in
   `GoalCliControlCommands.kt` alone), two in `featuretask/`, one in
   `scaffold/` as a *default argument* on `findRepoRoot`, and one in
   `CliRuntimeContext` itself as the resolution seed. Every layer below the CLI
   now agrees on one root while the CLI re-derives its own.

   Beyond the root:
   `UninstallCommand.kt:278` branches on `System.getProperty("os.name")`,
   `GoalCliCommands.kt:177-179` reads `java.class.path` and `path.separator`,
   `FeatureTaskRuntimeCliCommandsExtras.kt:105` reads
   `SKILL_BILL_QUALITY_GATE_SELECTION` from `System.getenv` rather than from the
   injected `environment` map that exists for exactly this, and
   `ScaffoldCliPayloadHelpersExtras.kt:68` calls `LocalDate.now()`.

3. **Silent degradation on the one destructive command.** `uninstall` wraps
   every mutation in `runCatching` and reports failure by appending to a
   `warnings: MutableList<String>`. That covers `removeLauncher`,
   `removeDesktop`, `removeRecursively`, `cleanupAgentInstallTargets`,
   `cleanupNativeAgentInstallLinks`, and `cleanupMcpRegistrations`. Nothing
   raises the exit code and nothing emits a `RuntimeDiagnostics` record, so a
   partial uninstall — launcher symlink removed, state tree left behind — exits
   0 with the failure buried in a payload field. `CliCompletionTelemetryDrain`
   swallows deliberately and documents why, which is correct for a drain, but it
   swallows into nothing: the 5-second timeout abandons the worker with no
   record that the outbox did not flush.

4. **A hub cycle around `skillbill.cli.core`.** `core` is bidirectionally
   coupled with fourteen of the sixteen sibling packages, because it holds both
   the shared kernel (`CliRunState`, `DocumentedCliCommand`, `CliOutput`) and
   the composition root (`CliCommandGroups`, `CliUtilityCommandGroups`,
   `SkillBillCommand`, `TopLevelCliCommands`). Heaviest edges: `goal -> core`
   19, `scaffold -> core` 19, `featuretask -> core` 15, `install -> core` 14,
   against `core -> system` 5, `core -> featuretask` 2, and one import each into
   the remaining eleven areas. `core` ↔ `model` (7/2) is the same cycle in
   miniature, caused by `CliRuntimeContext` importing `ExternalCommandRunner`
   from `core`. No command area compiles or tests without the whole tree.

5. **Metric evasion, at the stage where it is still cheap.** Every file passes
   the 500-line ceiling, but the `*Extras` / `*Extras2` / `*Extras3` spillover
   signature has already appeared across eleven `*Extras` files:
   `FeatureTaskRuntimeCliFormatting` at 467 lines across 3 files,
   `FeatureTaskRuntimeCliCommands` at 450 across 2, `GoalCliStatusFormatting` at
   344 across 3, `ScaffoldCliWizardHelpers` at 327 across 4,
   `ScaffoldCliPayloadHelpers` at 277 across 2, `ScaffoldCommandRequestParse`
   at 261 across 2, `GoalCliCommands` at 261 across 2, `GoalRunPresenter` at 208
   across 2. Each is under the ceiling as a logical unit, so the logical-type
   guard now on `main` passes on `runtime-cli` today. Naming these units by
   responsibility costs nothing now and is the whole cost later.

A sixth item is a scoping decision rather than a defect: `CliComponent` declares
no scope, so `rootCommand` and every transitive command rebuild per access.
`CliRuntime.run` reads `rootCommand` once, so no state is lost — unlike
`GoalRunner`'s retry map in SKILL-227. `CliRunState` reaches commands as
`@get:Provides`, which makes it the one shared instance; it is shared by being
mutable rather than by being scoped, which is what item 1 is about.

## Dependency

SKILL-227's four guards and their baseline infrastructure —
`ProductionLogicalTypeLineCeilingArchitectureTest`,
`ApplicationPackageAcyclicityArchitectureTest`,
`RuntimeApplicationAmbientClockArchitectureTest`,
`InjectConstructorDefaultsArchitectureTest`, plus
`ArchitectureBaselineRecorder`, `ArchitectureBaselineSupport`, and
`ArchitectureScanGuardSupport` — are on `main` as of `33179007` (PR #331).

They took a detour to get there. PR #328 landed SKILL-227 as `2e8f992e`; `main`
was then force-moved to `c4a1cd18`, an amend of PR #329's merge commit, which
dropped `2e8f992e` and `63aca571`. PR #331 restored both, closed a hole in the
`@Inject`-defaults scanner (it matched `@Inject` plus an optional `data` but no
visibility modifier, so `@Inject public class GoalRunnerLaunchReconciler`
evaded it while six `RuntimeDiagnostics = NoopRuntimeDiagnostics` defaults
survived), and guarded the amend and force-push seams against protected
branches so the rewrite cannot recur.

Two consequences for this feature. The `@Inject`-defaults guard it extends is
the **widened** scanner, so AC4 must catch a `runtime-cli` `@Inject` class
behind a visibility modifier rather than skip it — `GoalRunnerLaunchReconciler`
is the precedent. And `ProtectedBranches` now lives in `runtime-ports`, so a
`runtime-cli` seam needing that predicate consumes it rather than restating it.

Of the four, only the logical-type ceiling is already repo-wide (`productionRoots = listOf("runtime-kotlin",
"intellij-plugin")`). The package-acyclicity guard is hard-coded to
`runtime-application/src/main/kotlin` and the `skillbill.application.*` prefix;
the ambient-clock guard is hard-coded to the same root; the
`@Inject`-defaults guard takes `scanRoot` as a parameter that defaults to it.
All four baselines are empty as PR #331 leaves them, and must stay empty.

## Acceptance Criteria

1. The package-acyclicity guard is parameterized over scan root and package
   prefix, covers `skillbill.cli.*` alongside `skillbill.application.*`, and
   ships with a recorded baseline holding today's `runtime-cli` cycles. The
   `runtime-application` baseline stays empty.
2. The ambient-clock guard is parameterized over scan root and covers
   `runtime-cli` main source, banning `Instant.now()`, `LocalDateTime.now()`,
   `LocalDate.now()`, and `Clock.systemUTC()` with a recorded baseline that only
   shrinks.
3. A new ambient-environment guard bans `System.getenv`, `System.getProperty`,
   `Path.of("")`, and `Paths.get("")` in `runtime-cli` main source, with a
   recorded baseline of today's twenty-one sites. Its acceptance and rejection
   cases follow the shape of the guards it joins.
4. The `@Inject`-defaults guard runs against `runtime-cli` with an empty
   baseline; `CliRunState`'s eight default-valued fields are the only current
   offender and this feature removes them.
5. Every run-scoped setting has exactly one owner. `CliRuntimeContext` and
   `CliRunState` no longer both carry `dbPathOverride`, `environment`,
   `userHome`, `externalCommandRunner`, `liveStdout`, or `liveStderr`;
   precedence between the embedding context and a parsed flag is resolved once,
   at one seam, before `RuntimeComponent` is created.
6. `--home` and `--db` reach adapter-resolved paths, not only the `state`-threaded
   ones. A test drives a command whose home resolution goes through
   `EnvironmentContext.userHome` under `--home` and asserts the flag wins; the
   `remove` precedence test keeps passing unchanged.
7. `CliRunState` holds no mutable field seeded from the JVM. Whatever per-run
   mutable state survives lives in an explicit run-scoped object constructed
   from resolved inputs, not in `var` fields with `System.getenv()` defaults.
8. The repository root reaches `goal/`, `featuretask/`, and `scaffold/` through
   the coordinate SKILL-227 already resolves, not a second derivation:
   `CliRuntimeContext.repositoryRoot` is the single source, commands read it
   rather than the process working directory, `Path.of("")` no longer appears in
   `runtime-cli`, and `findRepoRoot` no longer takes it as a default argument. A
   command run from a subdirectory resolves the same root as the runtime beneath
   it.
9. Host-platform detection, JVM classpath, and path separator reach
   `UninstallCommand` and `GoalCliCommands` through ports or the injected
   environment; neither calls `System.getProperty` directly.
10. A failed `uninstall` mutation is a typed error or a recorded degradation with
    a non-zero exit code, not a warning string on a zero exit. Launcher removal,
    desktop removal, recursive tree removal, agent-target cleanup, native-agent
    unlinking, and MCP unregistration share one policy, and the policy is stated
    in `../../../runtime-kotlin/ARCHITECTURE.md`.
11. An abandoned telemetry drain emits a record through a bound
    `RuntimeDiagnostics`. The drain still cannot change the run's exit code or
    write to stdout or stderr.
12. `skillbill.cli.core` splits so that the composition root no longer imports
    the areas that import the kernel. `runtime-cli` reaches zero package cycles
    and its acyclicity baseline is empty.
13. The ten `*Extras` clusters are renamed or redistributed by responsibility.
    No file in `runtime-cli` is named `*Extras`, `*Extras2`, or `*Extras3`, and
    the logical-type ceiling baseline stays empty.
14. `skill-bill validate` passes, `./gradlew compileKotlin` passes, the
    `runtime-cli` and `runtime-core` test suites pass, and no architecture-test
    suppression or exemption is added without an entry in
    `PrincipleEnforcementInventory` and `../../../runtime-kotlin/ARCHITECTURE.md`.

## Constraints

- Observable CLI behavior stays equivalent: same command names, aliases,
  options, exit codes, help text, and payload keys. The one intended change is
  `uninstall`'s exit code on a failed mutation, which AC10 names explicitly.
- `runtime-cli` main source gains no concrete `skillbill.infrastructure.*`
  import. The narrowed dependency allow-list pinned by
  `RuntimeAdapterDependencyAllowlistTest` does not grow.
- New guards land green against a recorded baseline so the build never sits red
  between subtasks. Baselines shrink; they never grow.
- Loud-fail changes follow `../../../docs/observability-policy.md`: every remaining
  fallback emits a record through a bound `RuntimeDiagnostics`.
- Ports added here belong in `runtime-ports` with adapters in the matching
  `runtime-infra-*` module.
- Guards are extended in place, not forked. A second copy of a scanner scoped to
  a different module does not satisfy AC1 through AC4.
- The 500-line per-file ceiling is not relaxed to absorb re-merged `*Extras`
  files; AC13 is satisfied by naming responsibilities, not by concatenation.

## Non-Goals

- Changing the Clikt command surface, adding commands, or reorganizing the
  user-facing command hierarchy.
- Migrating `runtime-mcp` or `intellij-plugin`, which embed `CliRuntime`. They
  change only where AC5's single-owner seam alters the call they already make.
- Reworking `runtime-application`'s interior. SKILL-227 owns that; this feature
  touches it only to parameterize a shared guard.
- Adding test coverage for its own sake. `bill-unit-test-value-check` still
  applies: each new test names the bug it catches.
- Replacing Clikt or kotlin-inject, or introducing a second DI mechanism.
- Reworking the jlink runtime-image or install-staging wiring in
  `build.gradle.kts`.

## Validation Strategy

- Architecture tests are the primary proof. The parameterized acyclicity,
  ambient-clock, and `@Inject`-defaults guards plus the new ambient-environment
  guard each ship with an acceptance case and a rejection case, following the
  synthetic-fixture pattern the existing guards use.
- The split-ownership fix is proven by the test AC6 names: a command that
  resolves home through `EnvironmentContext.userHome` run under `--home`, which
  is the divergence no current test covers. `RemoveCliCommandTest`'s existing
  precedence assertion is the regression guard for the other direction.
- Repository-root injection is proven by running a `goal` and a `scaffold`
  command from a working directory that is not the repository root.
- The `uninstall` policy gets tests that assert the non-zero exit code and the
  recorded degradation on a failed launcher removal, a failed tree removal, and
  a failed MCP unregistration — not the absence of a crash.
- Cycle removal is proven by the empty baseline plus a compile of a single
  command area, which is the capability the hub cycle denies today.
- `skill-bill validate`, `./gradlew compileKotlin`, and the `runtime-cli` and
  `runtime-core` test suites gate every subtask.

## Delivery Plan

1. Guardrails: parameterize the acyclicity, ambient-clock, and
   `@Inject`-defaults scanners over scan root and package prefix, extend them to
   `runtime-cli`, add the ambient-environment guard, record the baselines, and
   register everything in `PrincipleEnforcementInventory` and `ARCHITECTURE.md`.
2. Single-owner inputs: collapse the `CliRuntimeContext` / `CliRunState`
   duplication into one resolution seam ahead of component creation, remove
   `CliRunState`'s JVM-seeded defaults, and move repository root, host platform,
   classpath, and path separator behind injected coordinates. Ambient baselines
   reach empty.
3. Loud-fail and structure: `uninstall` failures become typed errors or recorded
   degradations with a non-zero exit code, the telemetry drain records its
   abandonment, `skillbill.cli.core` splits kernel from composition root, the
   `*Extras` clusters are renamed by responsibility, and both the acyclicity and
   logical-type baselines are empty.
