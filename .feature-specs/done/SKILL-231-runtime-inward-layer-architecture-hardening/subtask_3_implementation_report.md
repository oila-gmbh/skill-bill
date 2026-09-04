# SKILL-231 subtask 3 — Port contracts and surface collapse

## Outcome

Port null-object family is classified and guarded, the silent `goalRunnerControls` default is removed, pass-through scaffold services and one unused discovery port are deleted, eight technology-leaking ports are renamed to capability names, composite manifest/control ports are collapsed, sqlite-internal manifest ops moved out of `runtime-ports`, `IdeStatusValidator`/`SkillRemoveFileSystem` relocated, and `runtime-application` repository-root/`toRealPath` probes route through `RepositoryEnclosingRootPort`.

AC-004 behavior change was sequenced first (abstract `UnitOfWork.goalRunnerControls`, `UnavailableUnaddressedFindingsRepository.issueExists` uniform refusal, `GoalRunnerControlBindingArchitectureTest`) even though the governed model amends subtask work into one commit.

## Interface census

Command (same as task-1 pin):

```bash
rg -c '^interface ' runtime-kotlin/runtime-ports/src/main/kotlin | awk -F: '{s+=$2} END {print s}'
rg -c '^fun interface ' runtime-kotlin/runtime-ports/src/main/kotlin | awk -F: '{s+=$2} END {print s}'
```

| Metric | Baseline (900d82cc8) | Post-change |
|--------|----------------------|-------------|
| `interface` | 141 (176 total cited in spec) | 133 |
| `fun interface` | 36 | 36 |
| **Total** | **176** | **169** |

### Interfaces removed or collapsed

- `RepoSourceDiscoveryGateway` (deleted; unused)
- `DecompositionManifestFileReadStore`, `DecompositionManifestFileWriteStore`, `DecompositionManifestFileEncodeStore` → `DecompositionManifestPersistencePort`
- `DecompositionManifestFileDiscoveryStore` → `DecompositionManifestDiscoveryPort`
- `DecompositionManifestFileStore` → `DecompositionManifestStore`
- `GoalRunnerControlStateRepository`, `GoalRunnerReviewPolicyRepository`, `GoalRunnerOutOfBandAcceptanceRepository` → flat `GoalRunnerControlRepository`
- `GoalRunnerManifestLeaseOps`, `GoalRunnerManifestControlOps`, `GoalRunnerManifestWriteOps`, `GoalRunnerManifestReviewOps` → moved to `runtime-infra-sqlite`
- `UninstallFileSystemGateway` → `UninstallPathsPort`
- `HttpRequester` → `RemoteTransportPort`
- `HttpResponse` → `RemoteTransportResponse`

### Thin-port disposition (nine named in AC-005)

| Port | Disposition |
|------|-------------|
| `RepoSourceDiscoveryGateway` | Deleted (no caller) |
| `ScaffoldGateway`, `ScaffoldCatalogGateway`, `UnsupportedScaffoldGateway`, `RepoValidationGateway` | Collapsed: CLI/MCP reach port via `RuntimeComponent` |
| `ExternalAddonOverlayPort`, `ExternalAddonSourceConfigPort`, `CheckedOutBranchSource`, `GoalPlanningBoundaryBodyResolver` | Retained; recorded in `../../../agent/decisions.md` as load-bearing DI boundaries |

## Sealed-subject `else` census

Definition: `when` with trailing `else` whose scrutinee type is a repo-declared `sealed class` or `enum class` in `runtime-ports`, `runtime-domain`, or `runtime-application` main source.

| Source | Count |
|--------|-------|
| Spec table | 37 |
| Strict census at implement time | 3 exhaustivity fixes applied (`GoalRunnerWedgeClass`, `GoalPlanningPhaseProduction`); remaining sites are wire-string decoders recorded in `../../../agent/decisions.md` |

## Filesystem census (`runtime-application` main)

| Metric | Spec table | Measured post subtask 2+3 |
|--------|------------|---------------------------|
| Files with `Path.of` / `toRealPath` | 24 files | 20 files |
| `Path.of` occurrences | 38 | 28 (inert value construction; left) |
| `toRealPath` code sites | 22 | 12 (routed through `RepositoryEnclosingRootPort`) |
| `Files.*` | 0 | 0 |

## Null-object classification

Documented in `ARCHITECTURE.md` Architecture Guardrails; enforced by `PortNullObjectClassificationGuardTest` and `RecordingNullObjectDiagnosticsTest`.

## Baselines (AC-011)

| Baseline | Status |
|----------|--------|
| `runtime-domain-package-cycle-baseline.txt` | Empty |
| `runtime-ports-ambient-clock-baseline.txt` | Empty |
| `runtime-ports-package-cycle-baseline.txt` | 4 shrink-only pairs remain (`featuretask|taskruntime`, `featuretask|workflow`, `goalrunner|persistence`, `goalrunner|workflow`) |
| `runtime-ports` spillover | Three `Continued` files renamed; spillover baseline empty |

## Tests added

- `GoalRunnerControlBindingArchitectureTest` (AC-004)
- `PortNullObjectClassificationGuardTest` (AC-001)
- `RecordingNullObjectDiagnosticsTest` (AC-003)
