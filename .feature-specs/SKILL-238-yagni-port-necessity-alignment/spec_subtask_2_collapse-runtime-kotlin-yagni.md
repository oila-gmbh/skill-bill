# SKILL-238 · Subtask 2 — Collapse runtime-kotlin same-module YAGNI

## Scope

Apply Port Necessity And Deletion inside `runtime-kotlin` for same-module
abstractions that fail the earn-it test: one implementation, no second product,
no needed test double beyond constructing the same data bag.

Collapse (illustrative anchors from the investigation — verify before edit):

- Feature-task phase-gate role ports:
  `FeatureTaskRuntimePhaseGateRolePorts.kt`,
  `FeatureTaskRuntimePhaseGateRolePortBindings.kt`,
  and unpacking in `FeatureTaskRuntimePhaseGates.kt`.
- Parallel review role ports:
  `ParallelCodeReviewRunnerRolePorts.kt`,
  `ParallelCodeReviewRunnerRolePortBindings.kt`,
  and `ParallelCodeReviewRunner` unpacking.
- Goal-planning sweep role ports:
  `GoalPlanningSweepRolePorts.kt`,
  `GoalPlanningSweepRolePortBindings.kt`,
  and `GoalPlanningSweep` injection.
- Goal-runner boundary role ports:
  `GoalRunnerRolePorts.kt`,
  `GoalRunnerRolePortBindings.kt`,
  and `GoalRunnerDeps` usage.
- Thin application forwarders when still pure renames:
  `UninstallFileSystemService`, `InstallAgentService`, `SkillRemoveService`.
- One-product `FileSystemReviewEvidenceBrokerFactory` class → DI lambda bind of
  the fun-interface factory.
- Same-module infra interfaces declared only beside their sole `Canonical*`
  class (`PlatformPackSchemaValidator`, `WorkflowStateSchemaValidator`) when
  the domain/port type already names the seam.
- Optional shrink: merge one-export `Jdk*Port` adapter files only if it does
  not blur testFixture substitute boundaries.
- Update `runtime-kotlin` / area `agent/history.md` with reuse notes.

**Keep (do not collapse):**

- Hexagonal `skillbill.ports.*` with one FS/SQLite adapter (module boundary).
- `GoalRunnerManifestStore` ISP parent slices cited in `docs/code-principles.md`.
- Ports with real second implementations or testFixtures substitutes
  (`BoundedWorkFanOutPort`, JDK process ports, `ExternalCommandRunner`, etc.).
- kotlin-inject `Runtime*Provides` composition splits.
- Governed contracts, validators, loud-fail typed errors.

## Acceptance Criteria

1. Listed same-module role-port interface + sole `Default*` pairs are replaced by data classes or direct constructor parameters; no leftover interface exists solely to name a single data bag.
2. Thin uninstall / install-agent / skill-remove application forwarders that only delegate are removed or inlined; CLI/MCP obtain the port or domain collaborator without a rename-only service.
3. One-product `FileSystemReviewEvidenceBrokerFactory` class is gone; DI still supplies `ReviewEvidenceBrokerFactory`.
4. Same-module `Canonical*` validator interfaces are inlined to the class (or the existing domain/port validator type) when they add no second implementation.
5. Hexagonal ports and `GoalRunnerManifestStore` ISP slices remain; no “collapse everything single-impl” pass.
6. `./gradlew` compile succeeds for `runtime-engine`, `runtime-application`, `runtime-core`, `runtime-cli`, and any touched infra modules; existing tests for those areas pass after DI/test-factory updates.

## Non-Goals

- Speculative leftover file deletion (subtask 1).
- IDE / VS Code mutator work (subtask 3).
- Mechanical architecture-test census for single-impl interfaces.
- SnakeYAML → Jackson migration unless required to complete a listed cut (catalog dep stays either way).

## Dependency Notes

- Independent of subtask 1 for compile purposes; may land after or beside it.
- Does not depend on subtask 3.

## Validation Strategy

```bash
cd runtime-kotlin && ./gradlew \
  :runtime-engine:compileKotlin \
  :runtime-application:compileKotlin \
  :runtime-core:compileKotlin \
  :runtime-cli:compileKotlin \
  :runtime-ports:compileKotlin
```

Run focused existing tests that construct phase-gate / goal-runner /
parallel-review factories (`GoalRunnerTestFactory`,
`FeatureTaskRuntimeRunnerTestSupport`, review harnesses). Update DI
`Runtime*Provides` bindings in the same commit as the collapse.

## Next Path

`.feature-specs/SKILL-238-yagni-port-necessity-alignment/spec_subtask_3_ide-extension-yagni-parity.md`
