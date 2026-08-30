# VS Code extension architecture

## Ownership

| Layer | Path | Responsibility |
| --- | --- | --- |
| Domain | `src/domain/` | Wire-agnostic outcomes, clocks, last-known display cache |
| Application | `src/application/` | `StatusRepository` port, preference port, refresh coordinator |
| Infrastructure | `src/infrastructure/` | CLI resolver, bounded process runner, JSON mapper, VS Code prefs |
| Presentation | `src/presentation/` | Domain → UI state, status-bar text mapping, view model |
| UI | `src/ui/` | Status bar item, details popup |
| Composition | `src/composition/` | Per-workspace wiring |

## Source of truth

Live status comes only from:

`skill-bill work status --format json --repo-root <canonical workspace>`

No workflow DB, SQLite, or MCP reads in this extension.

## Polling

`StatusRefreshCoordinator` starts polling when a status consumer activates,
coalesces overlapping polls through a serialized refresh chain and a dedicated
`ProcessRunner`, and stops on deactivate or workspace dispose.

Uncorroborated `no_matching_work` idles hold the prior live display for one
sample before settling.

## Persistence

Workspace settings persist CLI path and refresh interval. Optional
`workspaceState` stores a bounded last-known display cache surfaced only as
`Stale` — never as a live lifecycle.

## IntelliJ parity

Presentation semantics mirror the IntelliJ plugin intent (planning vs execution
slot, elapsed vs ran clocks, stale overlay). The Kotlin plugin is a behavioral
reference, not a build dependency.

## Remote / Codespaces

Use PATH or `skillBill.cliPath` so the remote environment can reach an installed
`skill-bill` CLI. No additional hardening ships in subtask 1.
