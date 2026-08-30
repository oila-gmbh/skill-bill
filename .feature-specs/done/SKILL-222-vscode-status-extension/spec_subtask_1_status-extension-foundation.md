# SKILL-222 subtask 1: VS Code status extension foundation

## Scope

Create `../../../vscode-extension` as an isolated TypeScript VS Code extension that
consumes the existing IDE status CLI contract and surfaces workspace-aware
Skill Bill status in the status bar.

Include:

- Extension manifest, TypeScript build, test harness, and local `vsce package`
  (or equivalent) producing an installable artifact.
- Layered ownership mirroring IntelliJ intent: domain outcomes, application
  ports (status repository, preference cache), CLI process infrastructure,
  presentation mapping, thin `extension.ts` / status-bar UI entry.
- CLI resolution order: settings override → PATH / `SKILL_BILL_BIN_DIR` /
  `~/.local/bin`; misconfigured override does not fall back.
- Coalesced polling of
  `skill-bill work status --format json --repo-root <canonical workspace>`,
  bounded timeouts, redacted failure summaries, cancel on deactivate /
  workspace dispose.
- Status-bar presentations for active, idle, stale, blocked, failed,
  unavailable, incompatible — including planning `n/m` and current-phase
  execution text when present — plus a details view on click (refresh +
  safe context). Local elapsed ticker without a CLI poll per tick.
- Settings for CLI path and refresh interval; optional last-known display
  cache for stale presentation only.
- `../../../README.md` and `ARCHITECTURE.md` for the module; pointer from
  `../../../docs/getting-started.md` (or equivalent contributor index).

## Acceptance Criteria

1. `../../../vscode-extension` builds, tests, and packages independently of
   `runtime-kotlin` and `intellij-plugin` Gradle builds.
2. With a reachable CLI and matching `contract_version`, the status bar shows
   concise active / idle / blocked / failed / unavailable / incompatible text
   consistent with IntelliJ semantics; stale only overlays a previously live
   lifecycle.
3. Polling coalesces, respects the configured interval, and stops when the
   status consumer deactivates or the workspace closes; process output never
   appears raw in the UI.
4. Unit tests cover JSON→domain mapping, presentation mapping (including
   planning vs execution wording and elapsed/ran rules), and CLI resolution
   misconfiguration without requiring a full IDE host.
5. Contributor docs state ownership, CLI path resolution, local install of the
   packaged artifact, and that Marketplace / tool window / mutations are out of
   this subtask.

## Non-Goals

- Stop / Pause (or any mutating CLI verbs).
- GitHub release job or Marketplace publish.
- Shared Kotlin library extraction from `intellij-plugin`.
- IDE status schema or runtime projector changes.

## Dependency Notes

- Depends on the already-shipped IDE status contract and CLI
  (`skill-bill work status --format json`). No prior SKILL-222 subtask.
- IntelliJ plugin is the behavioral reference, not a build dependency.

## Validation Strategy

- Run the extension package's unit tests and TypeScript build.
- Manually or via Extension Development Host: open a workspace with/without
  CLI, confirm status-bar states and settings override behavior.
- Confirm `runtime-kotlin` / root gates do not gain VS Code dependencies.

## Next Path

Subtask 2 adds goal Stop / Pause controls and hosted VSIX release packaging.
