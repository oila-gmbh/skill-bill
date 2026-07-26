# SKILL-52.4 — Hexagon leak closure follow-ups (runtime + desktop)

Issue key: SKILL-52.4
Lineage: continues SKILL-52 / SKILL-52.1 / SKILL-52.2 / SKILL-52.3 (full hexagonal
runtime architecture and boundary-closure work).

## Context

`runtime-kotlin` is a hexagonal JVM graph documented in
`runtime-kotlin/ARCHITECTURE.md`:

```
entry adapters (cli / mcp / desktop data gateways)
  -> application use cases
    -> ports (dependency boundary)
      -> domain models + rules
      -> contracts (port-owned payload contracts)
infra-fs / infra-http / infra-sqlite -> ports + domain + contracts
core -> composition root only
```

The SKILL-52.x series drove this graph to a high bar: domain purity, typed-DTO
boundaries, a curated raw-map open-boundary allow-list, and a battery of
architecture tests under
`runtime-core/src/test/kotlin/skillbill/architecture/`.

An architecture audit (2026-06-11) re-walked the whole graph. The **inner
runtime layers are in good shape**: `runtime-domain` is effect-free (no
`Instant.now`/`UUID.randomUUID`/`Random`/IO in main source), its wire-maps are
confined to durable-boundary codecs behind `@OpenBoundaryMap`, and its
contracts imports are limited to the documented helper set. `runtime-application`
has no Clikt/Compose/MCP/JDBC/HTTP-client imports and reaches persistence only
through repository/unit-of-work ports.

The leaks and structural debt that remain cluster at three seams the prior
subtasks did not close: (a) the **application↔runtime-environment seam**
(threading, timing, logging baked into `GoalRunner`), (b) the **desktop
hexagon** (`runtime-desktop` has its own core:domain/core:data/feature layering
that drifts from the runtime rules and was only ever partially covered by the
architecture tests), and (c) the **enforcement layer itself** (several
architecture tests are hard-coded snapshots or `src/main`-only scans, so real
violations can land without turning a test red — proven by a live drift).

This spec captures the audit findings and the work to close them.

## Problem

The audit found one class of problem that is genuinely dangerous and three that
are quality/debt:

1. **Enforcement holes let real violations through.** The guardrail tests give a
   false sense of safety. We have a *live, currently-green* drift
   (`skillbill.goalrunner`, below) that proves the subsystem-package guarantee
   is hollow in one direction. Other scans are `src/main`-only or cover 4 of 21
   Gradle modules. Until these are closed, every other fix in this spec can
   silently regress.
2. **Application layer reaches into the runtime environment.** `GoalRunner`
   sleeps the calling thread, mints its own `java.util.logging.Logger`, and
   hand-rolls thread-interrupt checks. Timing/threading/logging are adapter or
   composition concerns; the supervisor loop is consequently untestable without
   real wall-clock delay.
3. **Desktop hexagon leaks platform types and blocks the UI thread.** A
   `core:domain` commonMain port exposes `java.nio.file.Path` (a JVM type in the
   portable layer — against the team's own written rule), UI routes hard-code
   `Dispatchers.Default`, and a Room repository wraps async DAO calls in
   `runBlocking` on the ViewModel construction path.
4. **God objects concentrate risk.** `SkillBillViewModel` (1885 LOC, 30 state
   fields), `SkillBillFrame` (2713 LOC), `RuntimeRepoBrowserService` (844 LOC,
   3 interfaces + presentation logic), and the `GoalRunner` family
   (1497/1192/821 LOC) each fuse many responsibilities.

## Findings (evidence)

Every finding below was verified against source at the cited `file:line`.

### Tier 1 — Boundary leaks (clear hexagonal violations)

- **F1 — `GoalRunner` sleeps and threads in the application layer.**
  `runtime-application/.../application/GoalRunner.kt:945` calls
  `Thread.sleep(NO_TERMINAL_OUTCOME_RECHECK_DELAY_MILLIS)` (constant `200L`,
  ~line 963) inside `waitForLateTerminalOutcome`, with a hand-rolled retry
  counter and `Thread.currentThread().isInterrupted` check. A `java.time.Clock`
  is injected (constructor, ~line 56) but used only for `goalStarted` telemetry,
  not for this timing. Threading + wall-clock delay are infrastructure concerns;
  ARCHITECTURE.md rule 2 forbids application dependence on process/runtime
  mechanics. Net effect: the supervisor's late-outcome reconciliation cannot be
  tested without sleeping for real.

- **F2 — `GoalRunner` instantiates a concrete JDK logger.**
  `GoalRunner.kt:60` `private val log = java.util.logging.Logger.getLogger(...)`,
  used at lines 561 and 578. Concrete logging framework wired directly into an
  application use case (ARCHITECTURE.md rule 2: "must not depend on concrete
  infrastructure packages").

- **F3 — Desktop `core:domain` port exposes a JVM `Path` in commonMain.**
  `runtime-desktop/core/domain/src/commonMain/.../service/InstalledWorkspaceBaselineService.kt:3`
  `import java.nio.file.Path`; the interface method takes `workspaceRoot: Path`.
  This directly contradicts the team's own written rule one file over —
  `core/domain/.../model/ScaffoldModels.kt:250-252` documents that common-main
  DTOs exist specifically "so the scaffold preview and success surfaces don't
  import `java.nio.file.Path` from JVM-only code." A JVM-only type in a
  commonMain port blocks any non-JVM desktop target and is an inconsistent
  boundary.

- **F4 — Desktop commonMain UI hard-codes `Dispatchers.Default`.**
  `runtime-desktop/feature/skillbill/src/commonMain/.../ui/SkillBillRoute.kt`
  uses `withContext(Dispatchers.Default)` at lines 48, 64, 80, 88, 146, 155,
  250, 275 (and more). A `DispatcherProvider` abstraction exists in
  `core:common` but is bypassed here. Platform/testability leak: routes can't be
  driven with a test dispatcher.

- **F5 — `runBlocking` on the UI init path.**
  `runtime-desktop/core/data/src/jvmMain/.../repository/RoomRecentRepoRepository.kt:16,27,39`
  wraps async Room DAO calls in `runBlocking`. The `RecentRepoRepository`
  contract is synchronous, and `recentRepoPath()` is reached from
  `SkillBillViewModel` construction → blocks the thread during app startup.

### Tier 2 — Enforcement holes (guardrails that don't guard)

- **F6 — `skillbill.goalrunner` escapes the subsystem-package guarantee
  (LIVE DRIFT).** The package exists in source —
  `runtime-domain/src/main/kotlin/skillbill/goalrunner/` and `.../goalrunner/model/`
  (`GoalRunnerPolicy`, `GoalRunnerWorkerSubtaskRequestParser`, models). It is
  **absent** from `RuntimeModule.declaredSubsystemPackages`
  (`runtime-core/.../skillbill/RuntimeModule.kt`, the list ends at
  `skillbill.workflow`), absent from the hard-coded assertion set in
  `RuntimeArchitectureDocumentationTest.kt:86-111`, and absent from the
  ARCHITECTURE.md "subsystem package set" block (lines ~500-525). The test at
  `RuntimeArchitectureDocumentationTest.kt:84` asserts the constant equals a
  *hard-coded literal set*, and the test at line 117 asserts the constant equals
  the *doc*. Nothing walks the source tree to assert every real package is
  declared. So all three artifacts agree with each other while all three are
  blind to a real subsystem — the documented "same subsystem graph" guarantee is
  vacuous in the source→doc direction.

- **F7 — Raw-map scanner matches three string literals only.**
  `RuntimeArchitectureTest.kt:851-853` defines the banned shapes as exactly
  `"Map<String, Any?>"`, `"Map<String, *>"`, `"MutableMap<String, Any?>"` and
  detects them by substring (`shape in sigText`). Escapes: `Map<String, Any>`
  (non-null `Any`), `typealias Raw = Map<String, Any?>` then returning `Raw`,
  `HashMap<String, Any?>` / `LinkedHashMap<...>` return types, and whitespace
  variants. (No live violation today — grep for `Map<String, Any>` in
  application/domain/ports main is empty — but the rule the test claims to
  enforce is narrower than its English description.)

- **F8 — Architecture scans cover `src/main` only.** The `sourceRoots` list in
  `RuntimeArchitectureTest.kt` (and the domain-purity / inline-FQN scans in
  `RuntimeEnforcementHardeningArchitectureTest.kt`) enumerate only
  `src/main/kotlin` / `src/jvmMain/kotlin` / `src/commonMain/kotlin`. Test
  sources are unscanned, so fixtures can `import skillbill.infrastructure.*`
  or use inline FQNs to forbidden layers with no signal. (One already does:
  `runtime-desktop/core/data/src/jvmTest/.../JvmDesktopFirstRunGatewayTest.kt:10`
  imports `skillbill.infrastructure.fs.FileSystemInstallSelectionPersistence` —
  legitimate for a gateway test, but it shows test trees are an unguarded
  surface.)

- **F9 — Dependency-direction test covers 4 of 21 modules.**
  `RuntimeAdapterDependencyAllowlistTest` validates the `build.gradle.kts`
  dependency blocks of only `runtime-cli`, `runtime-mcp`,
  `runtime-desktop:core:data`, and `runtime-desktop:feature:skillbill`. The
  other ~17 modules — including every `runtime-desktop:core:*` leaf and all
  infra modules — have no test asserting they don't accidentally add an upward
  or sibling-concrete dependency.

### Tier 3 — God objects / misplaced responsibility

- **F10 — `SkillBillViewModel` is a 1885-LOC, 30-field mega-ViewModel.**
  `runtime-desktop/feature/skillbill/.../state/SkillBillViewModel.kt` (verified
  1885 lines, 30 `private var` fields). Fuses repo navigation, tree, editor,
  command palette, scaffold wizard, first-run setup, skill removal, and
  agent-config validation in one mutable state machine.

- **F11 — `SkillBillFrame` is a 2713-LOC Composable file.**
  `runtime-desktop/feature/skillbill/.../ui/SkillBillFrame.kt` holds 10+
  `@Composable` functions for every surface (frame, browser, editor, wizard,
  palette, first-run, confirm, validation console) in one file.

- **F12 — `RuntimeRepoBrowserService` is a 3-interface god-gateway with
  presentation logic.** `runtime-desktop/core/data/src/jvmMain/.../service/RuntimeRepoBrowserService.kt:34-40`
  implements `RepoSessionService`, `SkillTreeService`, and `AuthoringGateway` in
  one 844-LOC class, and embeds presentation tree-building / display-label logic
  that belongs in a view-model/presenter, not a data-layer adapter.

- **F13 — Application orchestrators are large.** `GoalRunner.kt` (1497),
  `GoalRunnerWorkflowStores.kt` (1192), `FeatureTaskRuntimeRunner.kt` (821).
  No cyclic coupling found, but each carries many responsibilities and 6–8
  injected ports.

- **F14 — `RuntimeContext` is a god-object in `runtime-ports`.**
  `runtime-ports/src/main/kotlin/skillbill/model/RuntimeContext.kt`: 8 fields
  spanning four unrelated concerns (env setup `dbPathOverride`/`stdinText`/
  `environment`/`userHome`; HTTP transport `requester`; git
  `workflowGitOperations`; optional callbacks `agentRunLauncher`/
  `goalPullRequestPort`). Every consumer takes the whole bag regardless of need.

### Tier 4 — Structural risks worth recording (intentional today, but smelly)

- **F15 — Presentation in ports.** Port models carry wire-mapping methods:
  `RepoValidationReport.toPayload` / `ReleaseRefMetadata.toPayload`
  (`runtime-ports/.../ports/validation/model/RepoValidationGatewayModels.kt`),
  `ReviewFinished*.toPayload` (`.../ports/telemetry/model/ReviewFinishedTelemetryPayload.kt`).
  They are `@OpenBoundaryMap`-annotated and allow-listed, so they are documented
  debt rather than silent leaks — but `toPayload()` on a port model is
  presentation living below the dependency boundary.
- **F16 — `skillbill.contracts.*` is a split package across two JARs.** DTOs/
  helpers/constants compile in `runtime-contracts`; the schema validators under
  `skillbill.contracts.install` / `skillbill.contracts.workflow` compile in
  `runtime-infra-fs` (documented in ARCHITECTURE.md, recorded in
  `agent/decisions.md` 2026-05-28). Split packages across JARs are a JPMS hazard
  and couple classpath resource lookups; the package name was retained only to
  preserve resource paths.
- **F17 — `runtime-infra-fs` is one module doing eight jobs** (~133 files):
  install, scaffold, native-agent, launcher, skill-remove, repo validation,
  fs gateways, and the moved contracts validators. High blast radius per change.
- **F18 — Adapter decision logic + double mapping.** CLI carries behavior
  decisions: `InstallCliCommands.kt:53` `refuseInstallMutationDuringGoalContinuation`
  (used at lines 144/215/507/591/618) and status→exit-code branching; CLI and
  MCP each maintain parallel result-mapper files with duplicated mapping.

### Explicitly clean (audited, no action)

`runtime-domain` purity (no nondeterminism/IO/logging in main source), the
typed public API of `WorkflowEngine` (map shapes are private/durable-boundary
only), `runtime-contracts` main-source purity (no networknt/jackson/Files), and
application persistence going only through ports — all verified clean. The
domain-owned validator ports (`WorkflowSnapshotValidator`,
`InstallPlanWireValidator`, etc.) are a deliberate, documented inversion and are
**not** a finding.

## Goals

1. Close the enforcement holes first (F6–F9) so the remaining fixes cannot
   silently regress and so the guardrails match their English contracts.
2. Remove the Tier-1 boundary leaks (F1–F5) and add the test that would have
   caught each one.
3. Reduce the highest-risk god objects (F10–F12, F14) without behavior change.
4. Record/track Tier-4 items (F15–F18) as decisions or backlog, fixing only
   where cheap; these are not required to land in this spec.

## Non-goals

- No behavioral change to skill authoring, install, scaffold, workflow, or
  telemetry semantics. This is a structure/enforcement spec; golden/wire outputs
  must stay byte-identical.
- Not splitting `runtime-infra-fs` into multiple Gradle modules (F17) — record
  the decision and defer; module surgery is its own spec.
- Not retiring the `skillbill.contracts.*` split package (F16) — record the
  rationale and a JPMS-safety guard test; physical move is out of scope.
- Not collapsing the desktop mini-hexagon into the runtime hexagon. The desktop
  layering is legitimate; we are fixing leaks within it, not removing it.
- No new product features.

## Target architecture / approach

### Phase 0 — Close enforcement holes (do first)

- **Subsystem-package completeness (F6).** Add a test that walks every declared
  Gradle main source root, extracts each `package` declaration, maps it to its
  owning declared subsystem package (longest declared prefix), and asserts there
  is **no source package without a declaring subsystem entry**. Then add
  `skillbill.goalrunner` to `RuntimeModule.declaredSubsystemPackages`, the
  `RuntimeArchitectureDocumentationTest` literal set, and the ARCHITECTURE.md
  subsystem block (and to the package-ownership prose). The new test — not the
  hard-coded snapshot — becomes the source→doc guarantee.
- **Raw-map shape coverage (F7).** Extend the banned-shape detection to also
  flag `Map<String, Any>` (non-null), `HashMap`/`LinkedHashMap`/`MutableMap`
  string-keyed `Any?`/`Any`/`*` returns, and typealiases that resolve to a
  banned shape (resolve `typealias X = Map<String, Any?>` declarations in scope,
  then treat `X` as banned). Add positive fixtures for each new pattern in the
  test's own fixture block. Anything newly caught must be either typed or added
  to the allow-list with an ARCHITECTURE.md entry — no silent grandfathering.
- **Test-source boundary scan (F8).** Add a (initially advisory, then
  enforcing) scan over `src/test` / `src/jvmTest` / `src/commonTest` that
  forbids inner-layer test code (`runtime-domain`, `runtime-ports`,
  `runtime-application`) from importing `skillbill.infrastructure.*`,
  `skillbill.cli.*`, `skillbill.mcp.*`, `skillbill.desktop.*`. Entry-adapter and
  infra test trees keep their legitimate seams via an explicit, documented
  allow-list (e.g. the `JvmDesktopFirstRunGatewayTest` infra import).
- **Module dependency-direction coverage (F9).** Generalize
  `RuntimeAdapterDependencyAllowlistTest` into a per-module rule set covering all
  21 modules: for each module, assert its non-test `project(...)` deps are a
  subset of an allowed set derived from the layer (infra → ports/domain/
  contracts only; core → no infra/entrypoint as `api`; desktop leaves → declared
  allow-lists). Pin the current edges and fail on any new upward/sibling edge.

### Phase 1 — Application environment seam (F1, F2)

- Introduce a domain-neutral port for bounded waiting/retry timing (e.g.
  `skillbill.ports.time.DelayController` or reuse a `Clock`-plus-`sleep`
  abstraction) and inject it into `GoalRunner`. The infra/composition layer
  provides the real `Thread.sleep`-backed implementation; tests provide a
  synthetic one. Remove `Thread.sleep` and the raw interrupt poke from
  `GoalRunner`.
- Replace the concrete `java.util.logging.Logger` field with an injected logging
  port (a minimal `RuntimeLog`/`DiagnosticSink` port in `runtime-ports`), or
  drop application-level logging and surface diagnostics through the existing
  telemetry/observability path. Composition wires the JDK-logger adapter.
- Add an architecture test forbidding `Thread`, `Thread.sleep`,
  `java.util.logging`, `java.util.concurrent` executors, and `*.getLogger(` in
  `runtime-application` main source (extends the existing domain-purity scan to
  the application module with an application-appropriate banned set).

### Phase 2 — Desktop boundary leaks (F3, F4, F5)

- **F3:** Change `InstalledWorkspaceBaselineService` (and any sibling
  commonMain port) to express the workspace root as a common-safe value
  (`String` path, or a desktop-domain `WorkspacePath` value type), matching the
  pattern ScaffoldModels.kt already documents. Move `java.nio.file.Path`
  conversion into the jvmMain implementation. Add a test forbidding
  `java.nio.*` / `java.io.*` imports in `runtime-desktop/**/commonMain`.
- **F4:** Route all `withContext(...)` in `SkillBillRoute` (and peers) through
  the existing `DispatcherProvider` from `core:common`, injected into the route
  scope. Add a test forbidding `Dispatchers.` literals in desktop commonMain.
- **F5:** Make the recent-repo read async (suspend) end-to-end, or defer it off
  the construction path into a `LaunchedEffect`/init coroutine, so no
  `runBlocking` runs on the UI thread. If `RecentRepoRepository` must stay
  synchronous for a caller, isolate the blocking call behind an explicitly
  off-main dispatcher and document why. Add a test forbidding `runBlocking` in
  desktop `jvmMain` non-test sources (allow-list any justified exception).

### Phase 3 — God-object reduction (F10, F11, F12, F14)

Behavior-preserving decomposition, each its own commit with tests pinned before
and after:

- **F10:** Split `SkillBillViewModel` into cohesive sub-state holders (editor,
  tree/navigation, scaffold-wizard, first-run, removal, validation, command
  palette) coordinated by a thin shell. Keep the public surface the UI consumes
  stable; move state and transition logic into the sub-holders.
- **F11:** Break `SkillBillFrame.kt` into per-surface Composable files under a
  `ui/` subpackage; the frame keeps only layout/slot wiring.
- **F12:** Split `RuntimeRepoBrowserService` along its three implemented
  interfaces, and lift tree-building / display-label presentation into a
  presenter owned by the feature/state layer (or a dedicated
  `SkillTreePresenter`), leaving the data services as pure translation.
- **F14:** Group `RuntimeContext`'s fields into intent-named sub-contexts
  (`EnvironmentContext`, `TransportContext`, `WorkflowOpsContext`,
  `OptionalCallbacks`) or split into the few cohesive contexts each consumer
  actually needs, so ports stop taking the whole bag. Keep one composed type at
  the composition root if convenient, but stop forcing all 8 fields on every
  consumer.

### Phase 4 — Tier-4 records (F15, F16, F17, F18)

- Record decisions in the relevant `agent/decisions.md` for F16 (split package
  retained for resource-path stability) and F17 (infra-fs not yet split), each
  with the trigger that would make us revisit, and add a guard test for F16 that
  fails if a *new* `skillbill.contracts.*` validator is added to
  `runtime-contracts` main (keeping the split one-directional and intentional).
- For F15, add an ARCHITECTURE.md note that port-model `toPayload` is the
  *only* sanctioned presentation-in-ports shape and is bounded by the
  open-boundary allow-list; open a backlog item to move it to adapter mappers.
- For F18, open a backlog item to lift `refuseInstallMutationDuringGoalContinuation`
  and exit-code policy into an application service and to share a single
  CLI/MCP result-mapper surface. Fix in this spec only if cheap.

## Acceptance criteria

Phase 0 (blocking; must land before later phases merge):
- [ ] A new architecture test walks all declared main source roots and fails if
      any real `package` has no declaring subsystem entry; it fails today
      against `skillbill.goalrunner` and passes after the declaration is added.
- [ ] `skillbill.goalrunner` appears in `RuntimeModule.declaredSubsystemPackages`,
      `RuntimeArchitectureDocumentationTest` literal set, and the ARCHITECTURE.md
      subsystem block + package-ownership prose; all three stay in parity.
- [ ] The raw-map scanner flags `Map<String, Any>`, `HashMap`/`LinkedHashMap`/
      `MutableMap` string-keyed maps, and typealias-laundered banned shapes, each
      proven by a fixture; any newly-caught real declaration is typed or
      allow-listed with an ARCHITECTURE.md entry.
- [ ] A test scans inner-layer test sources and forbids infra/adapter imports
      there (with a documented allow-list); existing legitimate seams are listed.
- [ ] A per-module dependency-direction test covers all 21 Gradle modules and
      fails on any new upward/sibling-concrete edge.

Phase 1:
- [ ] `runtime-application` main source contains no `Thread.sleep`,
      `java.util.logging`, `*.getLogger(`, or executor/threading APIs; an
      architecture test enforces this.
- [ ] `GoalRunner` waits/retries via an injected timing port and logs via an
      injected port (or not at all); the late-outcome path is unit-tested with
      synthetic time and no real delay.

Phase 2:
- [ ] No `runtime-desktop/**/commonMain` source imports `java.nio.*` / `java.io.*`;
      `InstalledWorkspaceBaselineService` takes a common-safe path type; a test
      enforces the import ban.
- [ ] No desktop commonMain source references `Dispatchers.` literally; routes
      use the injected `DispatcherProvider`; a test enforces this.
- [ ] No `runBlocking` on the desktop UI/init path; recent-repo load is async or
      deferred; a test forbids `runBlocking` in desktop jvmMain non-test sources
      (allow-list documented).

Phase 3 (behavior-preserving):
- [ ] `SkillBillViewModel`, `SkillBillFrame`, and `RuntimeRepoBrowserService` are
      decomposed into cohesive units; each new unit < ~400 LOC; existing desktop
      tests pass unchanged (or are mechanically updated for new types only).
- [ ] `RuntimeContext` consumers no longer all depend on all 8 fields; ports
      take only the sub-context they use.

Phase 4:
- [ ] Decisions recorded for F16/F17; guard test prevents a new contracts
      validator in `runtime-contracts` main; ARCHITECTURE.md note for F15;
      backlog items filed for F15/F18.

Global:
- [ ] `skill-bill validate`, `(cd runtime-kotlin && ./gradlew check)`,
      `npx --yes agnix --strict .`, and `scripts/validate_agent_configs` all pass.
- [ ] Golden/wire outputs (CLI JSON, MCP payloads, install-plan, workflow
      snapshots) are byte-identical to pre-change.

## Files expected to change (non-exhaustive)

Enforcement (Phase 0):
- `runtime-core/src/test/kotlin/skillbill/architecture/RuntimeArchitectureDocumentationTest.kt`
- `runtime-core/src/test/kotlin/skillbill/architecture/RuntimeArchitectureTest.kt`
- `runtime-core/src/test/kotlin/skillbill/architecture/RuntimeEnforcementHardeningArchitectureTest.kt`
- `runtime-core/src/test/kotlin/skillbill/architecture/RuntimeAdapterDependencyAllowlistTest.kt`
- `runtime-core/src/main/kotlin/skillbill/RuntimeModule.kt`
- `runtime-kotlin/ARCHITECTURE.md`

Application (Phase 1):
- `runtime-application/.../application/GoalRunner.kt`
- `runtime-ports/.../ports/time/…` (new timing port) and/or `…/ports/diagnostics/…` (logging port)
- `runtime-infra-fs/.../infrastructure/fs/…` (real adapters) + `runtime-core` DI wiring

Desktop (Phase 2/3):
- `runtime-desktop/core/domain/src/commonMain/.../service/InstalledWorkspaceBaselineService.kt`
  and its jvmMain implementation
- `runtime-desktop/feature/skillbill/src/commonMain/.../ui/SkillBillRoute.kt`
- `runtime-desktop/core/data/src/jvmMain/.../repository/RoomRecentRepoRepository.kt`
- `runtime-desktop/feature/skillbill/.../state/SkillBillViewModel.kt` (+ new sub-holders)
- `runtime-desktop/feature/skillbill/.../ui/SkillBillFrame.kt` (+ new per-surface files)
- `runtime-desktop/core/data/src/jvmMain/.../service/RuntimeRepoBrowserService.kt` (+ presenter)

Ports (Phase 3):
- `runtime-ports/src/main/kotlin/skillbill/model/RuntimeContext.kt` (+ consumers)

Records (Phase 4):
- relevant `agent/decisions.md` files; ARCHITECTURE.md note.

## Risks

- **Golden-output drift.** God-object decomposition and the RuntimeContext split
  touch many call sites; a stray reordering can change emitted map order. Pin
  golden tests before refactoring and diff after every commit.
- **New tests catch pre-existing debt.** The widened raw-map scanner and the
  per-module dependency test may surface violations beyond the ones audited.
  Triage each: type it, allow-list it with rationale, or carve a documented
  exception — do not weaken the rule to make a pre-existing case pass.
- **Desktop async change (F5)** can reorder startup; verify first-run/open-repo
  flows still behave (use the `verify`/`run` skills against the desktop app).
- **Timing-port abstraction (F1)** must not change real-world delays in
  production — the adapter keeps the existing `200L`/attempt constants.

## Open questions to resolve in planning

1. Logging (F2): introduce a logging port, or remove application-level logging
   and route through telemetry/observability? (Leaning: minimal diagnostics port
   so behavior is preserved.)
2. F5: make `RecentRepoRepository` fully suspend (cleaner, wider change) vs.
   defer the read into an init coroutine (smaller, keeps the sync contract)?
3. F8: land the test-source boundary scan as enforcing immediately, or advisory
   for one release while the allow-list is curated?
4. F14: one composed `RuntimeContext` kept at the composition root with
   sub-contexts extracted, vs. fully splitting it — which minimizes churn at the
   ~dozen consumer sites?
5. Sequencing of Phase 3: is the ViewModel/Frame decomposition in-scope here, or
   split into its own desktop-refactor spec to keep this one boundary-focused?

## Validation strategy

- Run the four canonical gates from `AGENTS.md` after each phase:
  `skill-bill validate`; `(cd runtime-kotlin && ./gradlew check)`;
  `npx --yes agnix --strict .`; `scripts/validate_agent_configs`.
- For each Tier-1/Tier-2 fix, write the guard test **first** and confirm it
  fails on the unfixed tree (red), then fix to green — proving the test has
  teeth (the F6 test must fail against current `main`).
- Snapshot CLI/MCP/install/workflow golden outputs before Phase 3 and assert
  byte-equality after.
- For desktop async/dispatcher changes, exercise the app via the `run` / `verify`
  skills (open repo, edit+save, scaffold wizard, first-run) to confirm no UI
  regression.

## References

- `runtime-kotlin/ARCHITECTURE.md` (boundary rules, open-boundary allow-list,
  SKILL-52.2 inventory)
- `.feature-specs/SKILL-52-full-hexagonal-runtime-architecture/spec.md`
- `.feature-specs/SKILL-52.1-hexagonal-runtime-hardening/`,
  `SKILL-52.2-runtime-boundary-closure/`, `SKILL-52.3-runtime-hexagon-leak-closure/`
- Audit date: 2026-06-11. Findings F1–F18 verified at the cited `file:line`.
