# SKILL-231 Subtask 1 Implementation Report

## Scoping decisions recorded

- `runtime-infra-*` ambient-environment baselines are shrink-only ceilings (see `../../../runtime-kotlin/agent/decisions.md` 2026-09-03).
- `runtime-core` composition ambient input enters at `RuntimeComponentBindingsA1.runtimeContext` as a named seam, not a permanent baseline entry.
- Spillover baseline keys repository-relative paths; scan root is each module `src` (main and test).
- Gradle edge pin retains the infrastructure-and-entrypoint `api` ban alongside per-module set equality.
- Permanent-floor (2026-09-02) binds the eight already-empty baselines; module baselines recorded here are shrink-only ceilings.

## Baseline reconciliation vs sub-spec table

| baseline area | sub-spec | recorded | divergence |
| --- | --- | --- | --- |
| runtime-infra-fs package cycles | 9 pairs | 9 pairs | none |
| runtime-ports package cycles | 6 pairs | 6 pairs | none |
| runtime-mcp package cycles | 4 pairs | 4 pairs | none |
| runtime-domain package cycles | 1 pair | 1 pair | none |
| runtime-infra-sqlite package cycles | 1 pair | 1 pair | none |
| ambient clock | 13 sites | 12 sites | runtime-infra-fs records 5 sites, sub-spec expected 6 |
| ambient environment | ~127 sites | 129 sites | runtime-ports contributes 2 sites not listed in sub-spec table |
| @Inject defaults | McpRuntimeContext 5 + infra | runtime-infra-fs 11; others empty | runtime-mcp records 0 (no `@Inject` constructor defaults in main); spec pre-count may be stale |
| spillover filenames | 112 files | 112 paths | none (54 application, 27 core, 27 infra-fs, 3 ports, 1 infra-sqlite) |

Eight baselines empty on main verified byte-identical after recorder run.

## Rejection-case demonstrations (task-10)

| mutation | targeted test | observed |
| --- | --- | --- |
| Extras-only spillover regex | `RuntimeSpilloverFileNameArchitectureTest` synthetic case | FAILED (Continued2 and BindingsA1 not flagged) |
| ambientEnvironmentCallSites hardwired to runtime-cli | `AmbientEnvironmentArchitectureTest` runtime-infra-fs case | FAILED (census mismatch) |
| runtime-ports removed from edge expectations | `RuntimeCoreCompositionOnlyTest` coverage case | FAILED |
| throwaway `api(project(":runtime-contracts"))` on runtime-mcp | `RuntimeCoreCompositionOnlyTest` api pin | FAILED |

All mutations restored before close.
