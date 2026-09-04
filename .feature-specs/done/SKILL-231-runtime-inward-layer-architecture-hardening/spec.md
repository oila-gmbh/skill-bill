# SKILL-231: runtime inward-layer architecture hardening

## Intended Outcome

The eight runtime modules SKILL-227 and SKILL-229 did not reach are held to the
same standard as `runtime-application` and `runtime-cli`: guards that measure
every module rather than two, an MCP entry adapter that reads no process-wide
input behind its injected ones, ports that carry a contract their
implementations honour, and a composition root that is the only place a
collaborator is constructed.

`runtime-application` (SKILL-227) and `runtime-cli` (SKILL-229) are the two
modules a scanner currently sees. The remaining eight — `runtime-contracts`,
`runtime-domain`, `runtime-ports`, `runtime-infra-fs`, `runtime-infra-http`,
`runtime-infra-sqlite`, `runtime-core`, `runtime-mcp` — are measured only by the
repo-wide guards (line ceiling, suppression ban, inline-FQN ban, raw-map
allow-list, layering). A survey against the SKILL-227 and SKILL-229 standard
found drift in five directions.

1. **Guard scope, not guard design.** The acyclicity, ambient-clock,
   ambient-environment, `@Inject`-defaults, and spillover-filename scanners are
   already parameterized or trivially parameterizable. They are instantiated for
   two modules. Extending them surfaces 21 mutual-import area pairs
   (`runtime-infra-fs` 9, `runtime-ports` 6, `runtime-mcp` 4, `runtime-domain`
   1, `runtime-infra-sqlite` 1), 13 ambient-clock sites, roughly 127
   ambient-environment sites, and **112 spillover-named files**. The spillover
   guard understates its own reach twice over: it scans only `runtime-cli/src`,
   and its signature matches only `Extras`, `Extras2`, `Extras3`. The dominant
   signature outside `runtime-cli` is `Continued`, `Continued1`…`Continued6`
   (54 files in `runtime-application`, 3 in `runtime-ports`, 1 in
   `runtime-infra-sqlite`), followed by `Helpers2`…`Helpers4` / `Fns2` / `Fns3`
   in `runtime-infra-fs` (27 files) and `BindingsA1`…`BindingsB7` /
   `Provides1`…`Provides13` in `runtime-core` (27 files).

   Ten clusters exceed the 500-line ceiling once their parts are attributed to
   one unit: `FeatureTaskRuntimeRunLoopCheckpoint` 1,069 lines across 7 files,
   `…OutputVerification` 981/6, `…PhaseAttempts` 824/4, `…ValidationGate`
   817/5, `…AttemptSettlement` 754/4, `…Launch` 696/4,
   `RuntimeComponentProvides` 664/13, `…Drive` 655/5, `…PhaseRunner` 556/4,
   `…SharedArgs` 534/2. Each part is its own `@Inject class` taking the run loop
   as a method parameter, so nothing bills to a shared receiver and the
   logical-type ceiling passes. No single file exceeds 500 lines anywhere in the
   tree; the largest is 484.

2. **`runtime-mcp` is `runtime-cli` before SKILL-229.** `McpRuntimeContext`
   carries five JVM-seeded default arguments — `System.getenv()`,
   `Path.of(System.getProperty("user.home"))`, `NoopWorkflowGitOperations`,
   `UnconfiguredHttpRequester`, and a null repository root — the shape AC7
   removed from `CliRunState`. `McpScaffoldRuntime.kt:56` calls `LocalDate.now()`
   and line 61 declares `findRepoRoot(start: Path = Path.of("")…)`, the exact
   default argument AC8 deleted from the CLI's scaffold.
   `GovernedReviewEvidenceBridge.kt:28` defaults a parameter to `System.getenv()`.
   `Main.kt:6` reads `System.getenv()` directly.

   `canonicalRepositoryRoot` exists **three times, byte-identical**, in
   `runtime-infra-fs`, `runtime-cli/model/CliRuntimeContext.kt:53`, and
   `runtime-mcp/core/McpRuntime.kt:272`. Both entry-adapter copies call
   `toRealPath()` and `.toFile().exists()` — filesystem IO in entry-adapter main
   source with no port. SKILL-229 gave the CLI an injected `repositoryRoot` but
   left the resolver duplicated rather than inverted.

   `runtime-mcp` also mirrors `runtime-cli` presentation across 14 file pairs
   (`WorkflowContinue*Maps`, `Workflow*ResultMappers`, `Review*ResultMappers`,
   `Telemetry*ResultMappers`, `Scaffold*RequestParser` and siblings), against 31
   MCP files versus 111 CLI files.

3. **Ports that do not keep the contract they claim.** `runtime-ports` declares
   176 interfaces. Six ship an `Unavailable*` implementation that `error(...)`
   for the whole contract: `UnavailableFeatureTaskRuntimeAuditGenerationRepository`,
   `UnavailableUnaddressedFindingsRepository`,
   `UnavailableReviewRunLaneCompletenessRepository`,
   `UnavailableReviewRunStageCompletenessRepository`,
   `UnavailableSpecScratchStore`, and `UnconfiguredHttpRequester`. One is
   internally inconsistent: `UnavailableUnaddressedFindingsRepository.issueExists`
   returns `false` silently while its seven siblings throw.

   The silent variant reaches production wiring.
   `runtime-ports/.../db/DatabaseSessionFactory.kt:55` declares
   `get() = EmptyGoalRunnerControlRepository`, so any session factory that does
   not override `goalRunnerControls` discards goal control-state writes —
   `persistControlState(...) = state`, `clearControlState(...) = Unit` — with no
   `RuntimeDiagnostics` record. That default belongs to a family: 22
   `Noop*`/`Empty*` port objects in ports, domain, and application main source,
   and 154 defaulted method bodies across 52 port files.
   `NoopWorkflowGitOperations` is wired in main source at four sites, not only in
   tests.

4. **Port count without port value.** 37 of the 176 interfaces have at most one
   production implementation, at most one consuming type, and no test double.
   Sixty-three have exactly one method. `DecompositionManifestFileStore` extends
   four sub-interfaces and every consumer takes the composite, so three of the
   four have no direct consumer at all; `GoalRunnerControlRepository` is the same
   shape over three. Nine thin ports have a real application-level caller and are
   the highest-confidence collapse set: `ExternalAddonOverlayPort`,
   `ExternalAddonSourceConfigPort`, `RepoSourceDiscoveryGateway`,
   `ScaffoldCatalogGateway`, `ScaffoldGateway`, `UnsupportedScaffoldGateway`,
   `CheckedOutBranchSource`, `RepoValidationGateway`,
   `GoalPlanningBoundaryBodyResolver`.

   Three application services forward exactly one port method and rename
   nothing: `McpRegistrationService` (19 lines), `NativeAgentInstallService`
   (22), `RepoValidationService` (20). Eight port names leak their adapter
   technology: `UninstallFileSystemGateway`, `HttpRequester`, `HttpResponse`, and
   the five `DecompositionManifestFile*Store`. The `*GitOperations` family does
   not — git is the capability the workflow needs, not an implementation choice.

5. **Composition that is not confined to `skillbill.di`.** Five main-source
   sites construct a collaborator instead of receiving it:
   `TelemetryService.kt:77` builds a `TelemetryLevelMutationService` that
   `RuntimeComponentProvides1` already binds; `GoalRunnerTickProgressReader.kt:87`
   builds `GoalRunnerProgressReader(outcomeStore)`;
   `InstallPlanBuilder.kt:37` builds `InstallPlanWireValidatorAdapter()`;
   `InstallApplySideEffects.kt:82` builds `FileTelemetryConfigStore(context)`;
   and `ScaffoldStandaloneEntrypoint.kt:25-26` is a second composition root
   inside `runtime-infra-fs`.

   `runtime-application` performs filesystem work outside any port at 24 files —
   38 `Path.of(` and 22 `.toRealPath()` occurrences, concentrated in
   `IdeStatusService`, `GoalPreflightInputValidation`,
   `GoalRunnerSubtaskLaunchPrepare`, and `RuntimeProvenanceService`. There are
   zero `Files.*` calls, so no current guard sees them, but `.toRealPath()` is a
   filesystem probe.

Two survey results are recorded as closed rather than open.
`runtime-application` has **not** absorbed persistence logic: zero `ResultSet`,
`PreparedStatement`, `executeQuery`, or SQL string literals. `runtime-contracts`
is a pure leaf: no `com.networknt`, no Jackson, no `java.nio.file.Files`, no
validator class. Neither is in scope.

The port boundary itself holds and must keep holding: zero
`skillbill.infrastructure.*` imports in `runtime-application`, `runtime-cli`,
`runtime-mcp`, `runtime-domain`, or `runtime-ports` main source, and zero
reverse imports of `skillbill.di` / `skillbill.cli` / `skillbill.mcp` from any
infrastructure module.

## Dependency

SKILL-227's four guards and SKILL-229's three, plus
`ArchitectureBaselineRecorder`, `ArchitectureBaselineSupport`, and
`ArchitectureScanGuardSupport`, are on `main` as of `e42c263e` (PR #334). All
eight baselines under
`../../../runtime-kotlin/runtime-core/src/test/kotlin/skillbill/architecture/baselines/`
are empty and `runtime-kotlin/gradlew check` is green at that commit.

SKILL-229 established the shape this feature follows: parameterize a scanner
over its scan root rather than forking it, land the widened case green against a
recorded baseline, then empty the baseline in a later subtask. It also
established the two guards that prove what an empty cycle baseline cannot —
`RuntimeCliAreaIsolationArchitectureTest` for transitive closure and
`RuntimeCliSpilloverFileNameArchitectureTest` for naming. Both are scoped to
`runtime-cli` today.

## Acceptance Criteria

1. The package-acyclicity, ambient-clock, ambient-environment, and
   `@Inject`-defaults scanners each cover every module in
   `../../../runtime-kotlin/settings.gradle.kts`, through their existing scan-root and
   package-prefix parameters. No scanner is forked; a second copy scoped to
   another module does not satisfy this criterion. The `runtime-application` and
   `runtime-cli` baselines stay empty.
2. The spillover-filename guard scans all ten modules and its signature covers
   `Continued`, `Continued<N>`, `Helpers<N>`, `Fns<N>`, `Support<N>`, single
   letter-plus-digit suffixes such as `A1` and `B7`, and bare trailing digits,
   alongside the existing `Extras` forms. Its exemption list stays a named list
   on `PrincipleEnforcementInventory`, never an ad-hoc regex carve-out.
3. Every module's `api(project(...))` and `implementation(project(...))` edge
   sets are pinned by an architecture test, in the shape
   `RuntimeCoreCompositionOnlyTest` already uses for `runtime-core`. An `api`
   edge is justified only by a type in that module's own public signatures; the
   three `runtime-infra-*` modules' `api(runtime-ports)` and `api(runtime-domain)`
   edges are resolved either by narrowing them or by documenting the signature
   that requires them.
4. `runtime-mcp` main source reads no process-wide input behind an injected one.
   `McpRuntimeContext` holds no JVM-seeded default argument, `LocalDate.now()`
   and `Path.of("")` do not appear, and `findRepoRoot` does not take the working
   directory as a default argument. The `runtime-mcp` ambient-clock,
   ambient-environment, and `@Inject`-defaults baselines are empty.
5. Repository-root resolution has one implementation. `canonicalRepositoryRoot`
   exists once, behind a port, and neither `runtime-cli` nor `runtime-mcp` main
   source performs filesystem IO to resolve it. An MCP tool invoked from a
   subdirectory resolves the same root as the runtime beneath it.
6. A port implementation either honours the contract it claims or is not an
   implementation. Every `Unavailable*` and `Noop*`/`Empty*` port object in
   `runtime-ports`, `runtime-domain`, and `runtime-application` main source is
   classified as one of: a total refusal that fails loudly and uniformly, a
   sanctioned null object that emits a `RuntimeDiagnostics` record on every
   swallow, or deleted. The classification is stated in
   `../../../runtime-kotlin/ARCHITECTURE.md` and enforced by a test.
7. No production default silently discards a write.
   `DatabaseSessionFactory.goalRunnerControls` no longer defaults to
   `EmptyGoalRunnerControlRepository`, and
   `UnavailableUnaddressedFindingsRepository.issueExists` no longer diverges from
   its siblings.
8. The nine thin ports with an application-level caller are collapsed or
   deleted, and the segregation-without-payoff composites
   (`DecompositionManifestFileStore` over four sub-interfaces,
   `GoalRunnerControlRepository` over three) are reduced to the interfaces some
   caller actually depends on. Port count falls; no port is added without a
   second consumer today.
9. The three pure pass-through services — `McpRegistrationService`,
   `NativeAgentInstallService`, `RepoValidationService` — are deleted and their
   callers reach the port directly.
10. The eight technology-leaking port names are renamed to the capability, with
    adapter names left alone: `UninstallFileSystemGateway`, `HttpRequester`,
    `HttpResponse`, and the five `DecompositionManifestFile*Store`. The
    `*GitOperations` family is explicitly out of scope and recorded as such.
11. Every `when` with a trailing `else` over a `sealed`/`enum` subject in
    `runtime-application`, `runtime-domain`, and `runtime-ports` main source — 37
    sites — is either made exhaustive or recorded in
    `../../../runtime-kotlin/agent/decisions.md` with the reason the set is
    genuinely open.
12. No main-source site outside `skillbill.di` constructs a collaborator the
    component binds. The five named sites are fixed and
    `ScaffoldStandaloneEntrypoint` is either removed or documented in
    `../../../runtime-kotlin/ARCHITECTURE.md` as a sanctioned second entrypoint
    with the reason it cannot resolve through `RuntimeComponent`.
13. All 112 spillover-named files are renamed for the responsibility they hold,
    and the logical-type ceiling baseline stays empty. Re-merging parts into one
    file is not an acceptable fix; a merged unit that breaches 500 lines fails
    the ceiling instead.
14. Every package-cycle baseline this feature records reaches empty except where
    a subtask names the exception and records it in
    `../../../runtime-kotlin/agent/decisions.md`.
15. The `Package Ownership` section of `../../../runtime-kotlin/ARCHITECTURE.md`
    matches the tree: `skillbill.model` no longer claims to hold only
    `RuntimeContext`, `skillbill.idestatus` is documented, and the
    `skillbill.contracts` split-package paragraph covers every package
    `runtime-infra-fs` declares under that root and every file in them that is
    not a schema validator.
16. `runtime-kotlin/gradlew check` passes, `npx --yes agnix --strict .` passes,
    `../../../scripts/validate_agent_configs` passes, and `skill-bill validate` passes,
    with no new `@Suppress`, no new line-ceiling exemption, and no baseline
    entry recorded to make a test pass.

## Constraints

- Behavior does not change. The one intended exception is criterion 7: a
  session factory that previously discarded goal control state now fails or
  records instead. That change is named here and carries its own test.
- The ten-module set in `../../../runtime-kotlin/settings.gradle.kts` does not change.
  Adding or removing a Gradle module is a plan-level decision.
- Baselines shrink; they never grow. Regenerate them from the scanners with
  `RECORD_ARCHITECTURE_BASELINES=1`, never by hand. The eight baselines that are
  empty on `main` stay empty.
- `runtime-infra-*` modules keep a shrink-only ambient-environment baseline
  rather than reaching zero: reading the host environment is what a filesystem
  or process adapter does. The boundary is that a *policy* decision may not
  depend on an ambient read. That scoping is recorded in
  `../../../runtime-kotlin/agent/decisions.md`, not left implicit.
- `runtime-core` composition may read ambient input at exactly one seam. That
  seam is a named exemption with a documented reason, not a baseline entry.
- No guard is weakened to make a refactor pass. A rule that genuinely needs to
  change is argued in `../../../runtime-kotlin/agent/decisions.md` first.
- No abstraction is added without a second caller today. A change that only
  makes future work easier is noted and skipped.
- Never mix a behavior change with a structural move in the same commit.
- No comments. Names and small functions carry the meaning.

## Non-Goals

- `runtime-contracts`. The survey confirmed it is a pure leaf; it is touched
  only if a port rename crosses it.
- Reworking `runtime-application`'s interior beyond the four items named here
  (spillover renames, sealed `else` branches, the two direct-construction sites,
  and the filesystem reach). SKILL-227 owns that module.
- Reworking `runtime-cli`'s interior. SKILL-229 owns it; this feature touches it
  only where the shared repository-root port and the widened guards reach.
- `../../../intellij-plugin`, `../../../vscode-extension`, `../../../skills`, `../../../platform-packs`,
  `../../../orchestration`, generated sources, `../../../install.sh`, and the skill render
  pipeline.
- Renaming the `*GitOperations` port family. Git is the capability, not a leaked
  technology.
- Detekt complexity thresholds, the Spotless ratchet configuration, or the
  jlink runtime-image wiring.
- Collapsing the CLI/MCP presentation duplication into a shared module. The 14
  mirrored file pairs are recorded as a finding and a decision; merging two
  entry adapters' presentation is a separate feature.
- Adding tests for coverage. `bill-unit-test-value-check` applies: each new test
  names the bug it catches.

## Validation Strategy

- Architecture tests are the primary proof. Every widened or new guard ships an
  acceptance case and a rejection case in the synthetic-fixture style the
  existing guards use, and each rejection case is verified by removing the guard
  and observing the case fail.
- Each widened case asserts set equality against its baseline rather than
  absence of unlisted sites, so a scanner that ignored its new scan-root
  parameter cannot pass against a stale baseline.
- The repository-root port is proven by running an MCP tool and a CLI command
  from a working directory below the repository root and asserting both resolve
  the root the runtime resolves.
- Criterion 7 is proven by a test that drives a session factory without a
  `goalRunnerControls` binding and asserts a loud failure or a recorded
  degradation, not the absence of a crash.
- Port collapse is proven by the falling interface count plus the existing
  suites staying green; a collapse that needed a new test to stay correct was a
  behavior change and belongs in its own commit.
- Cycle removal is proven by the empty baseline plus compiling a single area of
  the affected module, which is the capability the cycles deny today.
- `runtime-kotlin/gradlew check` runs in a clean checkout, since the Spotless
  ratchet needs a real `.git` directory and does not work in a linked worktree.

## Delivery Plan

1. **Guardrails and recorded baselines.** Extend the five scanners over all ten
   modules, widen the spillover signature, add the module-edge pin, record every
   baseline, and register the guards in `PrincipleEnforcementInventory` and
   `ARCHITECTURE.md`. Pure measurement; lands green.
2. **MCP entry-adapter closure.** Remove `McpRuntimeContext`'s JVM-seeded
   defaults, invert `canonicalRepositoryRoot` behind a port shared by both entry
   adapters, and record the CLI/MCP presentation duplication as a decision. The
   three `runtime-mcp` input baselines reach empty.
3. **Port contracts and surface collapse.** Classify and fix the null-object
   family, remove the silent `DatabaseSessionFactory` default, collapse the nine
   thin ports and the two payoff-free composites, delete the three pass-through
   services, rename the eight technology-leaking ports, close the 37 sealed
   `else` branches, and empty the `runtime-ports` and `runtime-domain`
   baselines.
4. **Composition and structure.** Rename all 112 spillover files, break the
   `runtime-infra-fs`, `runtime-infra-sqlite`, and `runtime-mcp` cycles, fix the
   five direct-construction seams, correct the `skillbill.contracts` package
   ownership, and bring `ARCHITECTURE.md` Package Ownership back in line with
   the tree.
