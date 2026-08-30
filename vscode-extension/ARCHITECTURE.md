# VS Code extension architecture

## Ownership

| Layer | Path | Responsibility |
| --- | --- | --- |
| Domain | `src/domain/` | Wire-agnostic outcomes, clocks, last-known display cache |
| Application | `src/application/` | `StatusRepository` port, preference port, refresh coordinator, goal mutation ports |
| Infrastructure | `src/infrastructure/` | CLI resolver, bounded process runners, JSON mapper, VS Code prefs |
| Presentation | `src/presentation/` | Domain → UI state, status-bar text mapping, goal-control eligibility, view model |
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

## Goal controls

Control eligibility and label text are decided once in
`GoalControlsPresentation` and consumed — never re-derived — by the details UI.

Mutating verbs:

- `skill-bill goal stop <issue-key> --repo-root <canonical>`
- `skill-bill goal pause <issue-key> --repo-root <canonical>`

Each verb uses its own `ProcessRunner` instance (`status`, `pause`, `stop`).
`ProcessRunner.runCoalesced` coalesces per instance, so sharing the poll runner
would let a mutation join an in-flight poll. The extension never terminates
Skill Bill processes itself.

## Persistence

Workspace settings persist CLI path and refresh interval. Optional
`workspaceState` stores a bounded last-known display cache surfaced only as
`Stale` — never as a live lifecycle.

## IntelliJ parity

Presentation semantics mirror the IntelliJ plugin intent (planning vs execution
slot, elapsed vs ran clocks, stale overlay, goal controls). The Kotlin plugin is a
behavioral reference, not a build dependency.

## Remote / Codespaces

Use PATH or `skillBill.cliPath` so the remote environment can reach an installed
`skill-bill` CLI. No additional hardening ships in subtask 1.

## Release

Hosted CI publishes on `extension-vX.Y.Z` tags via
`.github/workflows/extension-release.yml`. Each release attaches exactly one
`skill-bill-vscode-extension-<version>.vsix` and its `.sha256` sidecar; the job
fails closed before creating the release if the staged set is wrong.
