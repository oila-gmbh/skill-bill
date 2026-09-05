# SKILL-233 · Subtask 4: Engine module and package roots

## Scope

Move files once, after they have their final shape and names. Two moves, both
structural, both behaviour-free.

**Extract `runtime-engine`.** `skillbill.application.featuretask`,
`goalrunner`, `goalplanning`, and `planningprojection` are 344 of
`runtime-application`'s 534 files and took 1,430 of the last 150 commits'
production-file touches. `featuretask` imports 13 sibling areas, `goalrunner`
imports 13 including `featuretask`, and `work` imports both. They are the
product core and they change on a different cadence from install, scaffold,
learning, telemetry, and review.

Create Gradle module `runtime-engine` with package root `skillbill.engine`.
It depends on `runtime-ports`, `runtime-domain`, `runtime-contracts`, and
`runtime-application` (for the shared services it calls today: review,
telemetry, workflow, decomposition, continuation, agent output, IDE status,
subtask review, review evidence, spec source, phase artifacts, diagnostics,
runtime). `runtime-application` does not depend on `runtime-engine`. The one
inbound edge from `runtime-application` today is `skillbill.application.work`,
which imports `featuretask` and `goalrunner` for status projection; `work`
moves into the engine or reaches it through a port declared in
`runtime-ports`, whichever the second consumer justifies.

The engine's inbound API is the set of `RuntimeComponent` abstract properties
that resolve to engine types today (`featureTaskContinuationLookupService`,
`featureTaskRuntimePhaseRecorder`, `featureTaskRuntimeRunner`,
`featureTaskRuntimeStatusService`, `featureTaskRuntimeWorkerCoordinator`,
`goalPlanningPreparationCheckpoint`, `goalRunner`, `goalPreflightService`,
`goalRunnerStatusService`, `goalPlanningLogService`,
`goalOperatorDecisionService`, `unaddressedFindingsLedgerService`,
`parallelCodeReviewRunner` if it moves). An architecture test pins that set
and asserts no other engine type is referenced from `runtime-cli`,
`runtime-mcp`, or `runtime-application`.

`runtime-core` depends on `runtime-engine` and binds it. `runtime-cli` and
`runtime-mcp` add `runtime-engine` to their dependency allow-list in
`RuntimeAdapterDependencyAllowlistTest`. `RuntimeModuleCatalog.declaredGradleModules`,
`settings.gradle.kts`, `RuntimeGradleModuleLayeringTest`, the module-edge
pins, `RuntimeCoreCompositionOnlyTest`'s pinned edge set, the per-module
baseline file set, and `ARCHITECTURE.md`'s module list and diagram all record
the eleventh module.

**One package root per module.** After the extraction:

- `runtime-infra-sqlite`: the 97 files under `skillbill.db` move under
  `skillbill.infrastructure.sqlite`, keeping their sub-area (`db.core` to
  `infrastructure.sqlite.core`, `db.telemetry` to
  `infrastructure.sqlite.telemetry`, `db.workflow` to
  `infrastructure.sqlite.workflow`, `db.worklist` to
  `infrastructure.sqlite.worklist`).
- `runtime-infra-fs`: the 268 files under `scaffold`, `install`, `launcher`,
  `nativeagent`, `agentaddon`, `skillremove`, `goalplanning`, and `contracts`
  move under `skillbill.infrastructure.fs.<area>`. The `skillbill.contracts.*`
  validator packages kept their names to preserve classpath resource paths
  (decision dated 2026-06-12). Each validator now loads its schema by an
  explicit path constant from `*SchemaPaths` in `runtime-contracts`, so the
  package can move. The build-script copy tasks (`copyInstallPlanSchema`,
  `copyWorkflowStateSchema`, `copyDecompositionManifestSchema`) update their
  target directories to match.
- `runtime-application`: the seven files under `skillbill.telemetry` move to
  `skillbill.application.telemetry`, merging with the existing area.
- `runtime-domain` and `runtime-ports` keep their current roots; the domain's
  area roots (`skillbill.workflow`, `skillbill.review`, `skillbill.install`,
  and siblings) are the documented shape and are not the problem this subtask
  fixes.

`RuntimeModuleCatalog.declaredSubsystemPackages` shrinks from 30 entries to
the roots that remain, and `ARCHITECTURE.md`'s Package Ownership section is
rewritten to match. `PackageClusteringArchitectureTest` and
`RuntimeLayerBoundaryArchitectureTest` gain the rule that each infrastructure
and entry module has exactly one root, with a rejection fixture.

## Acceptance Criteria

1. `runtime-engine` exists in `settings.gradle.kts`, applies the
   `skillbill.jvm-library` and `skillbill.quality` conventions, and declares
   dependencies on `runtime-ports`, `runtime-domain`, `runtime-contracts`, and
   `runtime-application` only. `runtime-application` has no dependency on
   `runtime-engine`.
2. Every file formerly under `skillbill.application.featuretask`, `goalrunner`,
   `goalplanning`, and `planningprojection` lives in `runtime-engine` under
   `skillbill.engine.<area>`. `runtime-application` retains no reference to
   those packages except through the pinned inbound API or a `runtime-ports`
   port.
3. An architecture test pins the engine's inbound API as a named set of types
   and fails when `runtime-cli`, `runtime-mcp`, or `runtime-application`
   references an engine type outside that set.
4. `RuntimeModuleCatalog`, `RuntimeGradleModuleLayeringTest`,
   `RuntimeAdapterDependencyAllowlistTest`, `RuntimeCoreCompositionOnlyTest`,
   the module-edge pins, the per-module baseline files, and `ARCHITECTURE.md`
   record eleven modules. Every new baseline file is empty.
5. `gradlew :runtime-engine:test` runs the moved run-loop and goal-runner
   suites; every test passes with changes limited to package declarations and
   imports.
6. Every `runtime-infra-sqlite` main-source file is under
   `skillbill.infrastructure.sqlite`. `skillbill.db` does not exist.
7. Every `runtime-infra-fs` main-source file is under
   `skillbill.infrastructure.fs`. The validator classes load schemas through
   `*SchemaPaths` constants, the copy tasks target the new resource
   directories, and `skill-bill validate` and `./install.sh` pass against the
   moved resources.
8. Every `runtime-application` main-source file is under `skillbill.application`.
9. `RuntimeModuleCatalog.declaredSubsystemPackages` lists only the roots that
   exist, and an architecture test asserts one root per infrastructure, entry,
   engine, and application module with a rejection fixture.
10. `ARCHITECTURE.md`'s module list, dependency diagram, and Package Ownership
    section match the tree, and `RuntimeArchitectureDocumentationTest` proves
    it.
11. Behaviour is unchanged. No test assertion changes.
12. `runtime-kotlin/gradlew check`, `skill-bill validate`, and `./install.sh`
    pass with no new suppression, no new exemption, and no baseline entry.

## Non-Goals

- Changing any engine type's shape or signature. Subtasks 1 and 3 finished
  that; this subtask moves.
- Splitting `runtime-engine` further into `runtime-engine-featuretask` and
  `runtime-engine-goalrunner`. The two areas are mutually dependent today; a
  second split waits for that coupling to be cut, which is a later spec.
- Re-homing `runtime-domain`'s area roots under `skillbill.domain`. The
  domain's shape is documented and enforced; the infrastructure roots are the
  incoherence.
- Changing schema YAML contents or contract versions. Resource paths move;
  contracts do not.
- Touching `intellij-plugin` or `vscode-extension`, which call the CLI and see
  no package names.

## Dependency Notes

Depends on subtask 1: the run loop moves as consolidated collaborators, not as
65 fragments.

Depends on subtask 2: `runtime-engine` compiles against a `runtime-ports` that
is interfaces only, and the codecs it needs are in `runtime-domain` where the
engine already depends.

Depends on subtask 3: the engine is extracted with typed identifiers and
without `dbOverride` threading, so the pinned inbound API is the final shape.

Nothing depends on this subtask.

## Validation Strategy

- Module extraction is proven by `gradlew :runtime-engine:compileKotlin` and
  `:runtime-application:compileKotlin` both succeeding, plus the pinned-API
  architecture test with a synthetic rejection fixture referencing an
  unexposed engine type from `runtime-cli`.
- Package moves are proven by the one-root-per-module scanner with an empty
  baseline and a rejection fixture, and by `RuntimeArchitectureDocumentationTest`
  matching the rewritten `ARCHITECTURE.md`.
- Resource-path relocation is proven by the validator round-trip tests, by
  `skill-bill validate` in a clean checkout, and by `./install.sh` completing;
  the bug this catches is a validator that cannot find its schema after the
  package move.
- The moved test suites are the behaviour specification; they pass with
  package and import changes only.
- `runtime-kotlin/gradlew check` in a clean checkout.

## Next Path

```bash
skill-bill goal SKILL-233
```
