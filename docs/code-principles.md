# Code Principles

Each section states the rule, preferred shapes, anti-patterns, then reference examples
from this tree. `runtime-kotlin/ARCHITECTURE.md` records module boundaries and
package ownership; this document records copyable coding patterns.

Amended or review-only rules name what changed and why. A principle this program
did not mechanically enforce is amended here or in `runtime-kotlin/agent/decisions.md`,
not left as a silent violation.

## Type Modeling

**Rule.** Model closed sets as sealed hierarchies or enums in one file. Public
inputs and results live in area-owned `*.model` packages, not layer-wide buckets.
Prefer typed fields over raw maps at boundaries unless the surface is documented
as open.

**Preferred shapes.** One sealed interface per closed workflow outcome family;
enum wire values with a single `fromWire` companion; `@OpenBoundaryMap` only on
fields the architecture doc inventories as open extension.

**Anti-patterns.** A `model` package that mixes unrelated noun families;
parallel `*FailureKind` enums that drift from `*FailureCode`; residual `else`
on sealed branches the compiler could check.

**Reference examples.**

- `runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/featuretask/model/FeatureTaskRuntimePhaseLaunchBriefing.kt`
- `runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/workflow/taskruntime/model/FeatureTaskRuntimePhaseOutputValidationModels.kt`
- `runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/featuretask/FeatureTaskRuntimeRunLoopModels.kt` (`LaunchPreparation` sealed preparation chain)

## Failure Contracts

**Rule.** Every failure case in an in-scope `FailureWireCode` hierarchy maps to
exactly one stable wire code, and every code maps to exactly one case. Untrusted
input at a named parse boundary returns a typed contract failure; `error()`,
`require()`, bare `throw`, and `runCatching` classifiers do not report malformed
external input. `CancellationException` rethrows before broad catch.

**Preferred shapes.** `enum class … : FailureWireCode` with `wireValue`;
`failureWireByValue` at decode sites; `Invalid*SchemaError` or domain-specific
typed failures with payload-free reasons for operators.

**Anti-patterns.** Collapsing unknown wire tokens to `SCHEMA_INVALID`; a second
parallel kind enum that can diverge from the wire code; using `error("bad json")`
inside durable control-state or phase-output decoders.

**Reference examples.**

- `runtime-kotlin/runtime-contracts/src/main/kotlin/skillbill/error/FailureWireCodeContract.kt`
- `runtime-kotlin/runtime-domain/src/test/kotlin/skillbill/workflow/failureidentity/FailureWireCodeConformanceTest.kt`
- `runtime-kotlin/runtime-infra-sqlite/src/main/kotlin/skillbill/db/workflow/GoalRunnerControlStore.kt` (`decodeControlState` and helpers)
- `runtime-kotlin/runtime-core/src/test/kotlin/skillbill/architecture/TypedParseBoundaryArchitectureTest.kt`

**Amendment.** Genuinely open operator or agent JSON maps, external process
stdout, and `@OpenBoundaryMap` payloads stay open at `when` branches; each site
carries a one-line reason in code. Subtask 3 recorded the boundary instead of
forcing closed enums where closing would break pack extensibility.

## Capability Signaling

**Rule.** Declare capability once as data; consumers derive behavior from that
declaration. Closed compile-time choices use enums or sealed types; pack-authored
extension surfaces stay open strings validated for shape, not membership.

**Preferred shapes.** Manifest fields parsed once in scaffold loaders; closed
review-routing constants converted to manifest strings at the routing boundary;
typed failures (`InvalidFallbackCapabilityError`) when shape rules fail.

**Anti-patterns.** Duplicated string literals for the same capability across
loaders and services; silently accepting unknown fallback tokens; inventing a
closed enum for pack-local file paths.

**Reference examples.**

- `runtime-kotlin/runtime-infra-fs/src/main/kotlin/skillbill/scaffold/platformpack/ShellContentLoader.kt` (`parseFallbackCapabilities`)
- `runtime-kotlin/runtime-infra-fs/src/main/kotlin/skillbill/launcher/agentrun/AgentRunCommandBuildersLaunch.kt` (`GovernedReviewLaunchCapability`)
- `runtime-kotlin/runtime-contracts/src/main/kotlin/skillbill/error/GovernedReviewShellContentErrors.kt`

**Amendment (SKILL-220 subtask 3).** `fallback_capabilities`, native-agent
`entrypoint` paths, and `AgentRunIdlePolicy` remain open at their current
boundaries. See `runtime-kotlin/agent/decisions.md` dated 2026-08-29.

## Single Source Of Truth

**Rule.** Contract versions, schema paths, enforcement inventories, and
architecture allow-lists have one authoritative declaration; tests assert parity
with that declaration instead of re-embedding duplicate tables.

**Preferred shapes.** `*_CONTRACT_VERSION` constants beside YAML schemas;
`PrincipleEnforcementInventory` for architecture-test sites; `ARCHITECTURE.md`
marker blocks parsed by architecture tests for raw-map inventories.

**Anti-patterns.** A second handwritten allow-list in test code that can drift
from `ARCHITECTURE.md`; parsing the same manifest field in multiple loaders with
different validation rules.

**Reference examples.**

- `runtime-kotlin/runtime-core/src/test/kotlin/skillbill/architecture/PrincipleEnforcementInventory.kt`
- `orchestration/contracts/platform-pack-schema.yaml` with `PlatformPackSchemaContractVersionTest`
- `runtime-kotlin/ARCHITECTURE.md` open-boundary and raw-map inventory markers

## Module And Package Layout

**Rule.** Dependencies point inward: entry adapters and infrastructure implement
ports toward domain. Cluster by product area and noun family, not by type kind
(`model`, `persistence`, `service` buckets spanning areas). No adapter, JDBC, or
filesystem type in domain or application APIs.

**Preferred shapes.** `skillbill.application.<area>.model` for application
inputs/results; `skillbill.ports.<concept>` for repositories and gateways;
`skillbill.workflow.<subarea>` for split workflow families (`engine`,
`decomposition`, `taskruntime`, `goal`, `idestatus`, `specsource`).

**Anti-patterns.** Loose files in a parent package when a child area cluster
already exists; cross-area `ports.persistence` repositories grouped only by
storage technology.

**Reference examples.**

- `runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/featuretask/model/FeatureTaskRuntimeRunModels.kt`
- `runtime-kotlin/runtime-ports/src/main/kotlin/skillbill/ports/goalrunner/runner/GoalRunnerPorts.kt` (`GoalRunnerManifestStore`)
- `runtime-kotlin/runtime-infra-sqlite/src/main/kotlin/skillbill/infrastructure/sqlite/goalrunner/WorkflowGoalRunnerManifestStore.kt`
- `runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/workflow/engine/WorkflowEngine.kt`
- `runtime-kotlin/runtime-core/src/test/kotlin/skillbill/architecture/PackageClusteringArchitectureTest.kt`
- `runtime-kotlin/ARCHITECTURE.md` Package Ownership section

## Concurrency And Lifetime

**Rule.** Worker and execution leases carry owner token, fencing generation, and
expiry evidence. Status projection falls back through defined precedence; crash
reconciliation self-heals only when lease expiry and process-death evidence agree.
Do not terminate or replace a live owner without compare-and-set reservation.

**Preferred shapes.** Lease rows persisted before claiming work; supervisor
confirmation before orphan repair; `CancellationException` propagated out of
decode and IO boundaries.

**Anti-patterns.** Clearing leases from operator scripts as the normal path;
treating idle child worker lease as paused while parent execution lease is live;
silent takeover without generation fencing.

**Reference examples.**

- `runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/goalrunner/model/GoalRunnerControlModels.kt`
- `runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/goalrunner/GoalRunnerStatusService.kt`
- `runtime-kotlin/runtime-infra-sqlite/src/main/kotlin/skillbill/infrastructure/sqlite/goalrunner/WorkflowGoalRunnerOutcomeTerminalPersistence.kt`
- `runtime-kotlin/ARCHITECTURE.md` DB-first feature-task continuation section

## Composition And API Surface

**Rule.** `runtime-core` / `skillbill.di` is the sole composition root. Extract
collaborators with visibility as narrow as callers allow; do not introduce a
second DI graph or a public type whose only caller is its extraction sibling.

**Preferred shapes.** `@Inject` application services; `@Component` /
`RuntimeComponent` bindings in `skillbill.di`; internal `*LaunchCapture` /
`*Persistence` collaborators beside the orchestrator they serve.

**Anti-patterns.** Entry adapters importing concrete `skillbill.infrastructure.*`
types; infrastructure depending on `runtime-core`; multiplying port interfaces
with one implementation.

**Reference examples.**

- `runtime-kotlin/runtime-core/src/main/kotlin/skillbill/di/RuntimeComponentBindingsA1.kt`
- `runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/featuretask/FeatureTaskRuntimeRunLoopSession.kt`
- `runtime-kotlin/runtime-core/src/test/kotlin/skillbill/architecture/RuntimeCoreCompositionOnlyTest.kt`

## Port Necessity And Deletion

**Rule.** A port earns its place through a second implementation, a test
substitute, or a module boundary the composition root has to cross. An interface
with one implementation, one caller, and no test double is inlined, not kept.
Deleting code outranks abstracting it. An extension point waits for its second
case.

**Preferred shapes.** Port contracts in `skillbill.ports.<concept>` implemented
by one adapter and bound once in `skillbill.di`; test substitutes published from
`runtime-ports` test fixtures instead of re-declared per consumer; `internal`
visibility on collaborators extracted beside the orchestrator they serve.

**Anti-patterns.** A wrapper that only forwards calls; an application service
whose body is one port call plus a rename; a port declared in the module of its
only implementation; a type parameter or `Any`-typed map used at exactly one
site; a flag that is never flipped. `@OpenBoundaryMap` sites inventoried in
`ARCHITECTURE.md` are deliberate open boundaries, not this anti-pattern.

**Reference examples.**

- `runtime-kotlin/runtime-ports/src/testFixtures/kotlin/skillbill/ports/work/EmptyWorkListRepository.kt`
- `runtime-kotlin/runtime-ports/src/main/kotlin/skillbill/ports/goalrunner/runner/GoalRunnerPorts.kt`
- `runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/featuretask/FeatureTaskRuntimeRunLoopSession.kt`

## Build And Tooling

**Rule.** Shared JVM test and toolchain settings live in convention plugins;
module `build.gradle.kts` files do not re-declare what `configureKotlinJvm` already
owns. Production Kotlin files stay at or below 500 lines unless explicitly
exempted in `PrincipleEnforcementInventory`.

**Preferred shapes.** `skillbill.jvm-library` convention applying
`configureKotlinJvm`; file splits by verb family or responsibility within the same
package; empty exemption map when the tree is clean.

**Anti-patterns.** Copy-pasting `update-snapshots` `systemProperty` into module
build files; `@Suppress("LargeClass")` instead of splitting; applying the
500-line gate to test sources.

**Reference examples.**

- `runtime-kotlin/build-logic/convention/src/main/kotlin/dev/skillbill/runtime/buildlogic/Jvm.kt`
- `runtime-kotlin/runtime-core/src/test/kotlin/skillbill/architecture/ProductionFileLineCeilingArchitectureTest.kt`
- `runtime-kotlin/runtime-core/src/test/kotlin/skillbill/architecture/ConventionReapplicationArchitectureTest.kt`

**Amendment.** The 500-line ceiling applies to production `src/main` Kotlin only.
Test sources may exceed 500 lines. Detekt complexity pinning and suppression
cleanup are SKILL-221, not this program.

## Guard Baselines And Exemptions

**Rule.** `PrincipleEnforcementInventory` and the files under
`skillbill/architecture/baselines/` decide which rules run and what debt they
still tolerate. Both ratchet one direction. A new violation gets fixed; it does
not get recorded. Regenerate a baseline only when a fix shrinks it. All eight
baseline files, `productionLineCeilingExemptions`, and
`spilloverFileNameExemptions` are empty today, and empty is the target state.

**Preferred shapes.** `RECORD_ARCHITECTURE_BASELINES=1` run after a cleanup that
removes entries; a new rule registered in `enforceableRules` with its scan in
`ArchitectureScanSupport`; a rule that resists deterministic scanning registered
in `reviewOnlyRules` with the reason.

**Anti-patterns.** Running the recorder to turn a red test green; adding an
exemption entry instead of splitting the file; `@Suppress` in place of the fix; a
rule asserted in prose or review with no entry in `enforceableRules` or
`reviewOnlyRules`.

**Reference examples.**

- `runtime-kotlin/runtime-core/src/test/kotlin/skillbill/architecture/ArchitectureBaselineRecorder.kt`
- `runtime-kotlin/runtime-core/src/test/kotlin/skillbill/architecture/SuppressionBanArchitectureTest.kt`
- `runtime-kotlin/runtime-core/src/test/kotlin/skillbill/architecture/PrincipleEnforcementInventoryTest.kt`

## Imports And Simple Names (No Inline FQN)

**Rule.** Production and test Kotlin under `runtime-kotlin`, `intellij-plugin`,
and `runtime-kotlin/build-logic` reference types by imported simple name. Inline
fully-qualified references are not a style option.

**Preferred shapes.** `import` at file top; `import a.Foo as FooA` when simple
names collide; extension receivers imported like types.

**Anti-patterns.** `java.time.Instant.now()` in expression position without
import; `skillbill.workflow.Foo` in code when `import skillbill.workflow.Foo`
works; leaving inline FQNs because the architecture-test allow-list names them in
prose.

**Reference examples.**

- `runtime-kotlin/runtime-core/src/test/kotlin/skillbill/architecture/InlineFqnArchitectureTest.kt`
- `runtime-kotlin/runtime-core/src/test/kotlin/skillbill/architecture/PrincipleEnforcementInventory.kt` (`inlineFqnPrefixes`, `inlineFqnScanRoots`)

**Amendment (SKILL-220 subtask 2 and 7).** Keep-list: `package` and `import`
lines; string literals (including architecture inventories); generated sources;
compiler-required disambiguation after alias failure; KDoc and comment mentions
are not scanned. `ARCHITECTURE.md` FQN inventories document names for tests, not
production style.

## Review-Only Principles (Not Mechanically Enforced)

**Rule.** Principles that resist deterministic source scans stay documented here
and in decision logs; they are not silent violations.

**Not enforced by architecture tests.**

- Comment quality and density (subjective editorial standards).
- Naming taste beyond noun-family clustering.
- Deeper noun-family relatedness inside a single area cluster (only cross-area
  loose-file buckets are mechanically provable).
- Open harness and capability vocabulary keys subtask 3 left open.

**Reference examples.**

- `runtime-kotlin/runtime-core/src/test/kotlin/skillbill/architecture/PrincipleEnforcementInventory.kt` (`reviewOnlyRules`)
- `runtime-kotlin/agent/decisions.md` dated 2026-08-29 (capability vocabulary and enforcement boundaries)
