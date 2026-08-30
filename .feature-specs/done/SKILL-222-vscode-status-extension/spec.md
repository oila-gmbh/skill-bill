# SKILL-222: VS Code Skill Bill status extension

## Intended Outcome

Ship a sibling VS Code / Cursor-compatible extension that mirrors the IntelliJ status
plugin's operator surface: a workspace-aware status-bar item driven by
`skill-bill work status --format json`, with Stop / Pause controls for an active
feature goal, and no Skill Bill database or `runtime-kotlin` coupling.

The IDE status contract already exists (`../../../orchestration/contracts/ide-status-schema.yaml`).
This feature adds only a new consumer under `../../../vscode-extension`, parallel to
`../../../intellij-plugin`: isolated package lifecycle, CLI as sole transport, same
lifecycle presentations and persistence limits.

## Acceptance Criteria

1. The repository contains an isolated VS Code extension package under
   `../../../vscode-extension` that builds and packages without making `runtime-kotlin` or
   `intellij-plugin` depend on VS Code APIs.
2. The extension resolves the `skill-bill` CLI (settings override, then PATH /
   `SKILL_BILL_BIN_DIR` / `~/.local/bin`), polls
   `skill-bill work status --format json --repo-root <workspace>`, coalesces
   overlapping refreshes, and cancels work when the workspace closes or the
   status item deactivates.
3. Status-bar text and detail view present the same lifecycle set as IntelliJ:
   active, idle, stale, blocked, failed, unavailable, and incompatible, with
   planning progress and current-phase execution wording when the contract
   supplies them.
4. Settings cover CLI path override and refresh interval; persistence is limited
   to those preferences plus an optional last-known display cache — never
   workflow state, tokens, prompts, raw process output, or Skill Bill databases.
5. For an active `feature-goal` snapshot with an issue key, the detail UI offers
   Stop and Pause-after-current-subtask via
   `skill-bill goal stop` / `skill-bill goal pause`; Resume is absent; mutation
   runners are distinct from the status-poll runner; the extension never
   terminates Skill Bill processes itself.
6. Contributor docs cover module ownership, CLI path resolution, local package /
   install, and deferred Marketplace / full tool-window work. A hosted CI path
   builds a verifiable VSIX (or zip) release asset without using a self-hosted
   runner for extension packaging.

## Constraints

- Treat the extension as an external consumer: versioned CLI contract only; no
  imports of runtime persistence, workflow engines, JDBC, SQLite, or IntelliJ
  plugin packages.
- Prefer TypeScript / VS Code Extension API; do not require the IntelliJ Gradle
  toolchain for this module.
- Match IntelliJ presentation semantics (freshness vs lifecycle, elapsed vs ran,
  planning vs execution text) unless a VS Code API forces a documented narrowing.
- Do not invoke `../../../install.sh`, `../../../uninstall.sh`, or `skill-bill install apply` from
  the extension.
- Do not read or mutate the user's unrelated dirty worktree changes.

## Non-Goals

- Marketplace publication, publisher signing, or paid listing.
- A full Skill Bill tool window / webview dashboard.
- Goal launch, resume, retry, abandon, or agent/profile selection from the IDE.
- Changing the IDE status schema or Kotlin runtime projection unless a contract
  bug blocks the consumer (fix under a separate issue).
- Remote / Codespaces-specific hardening beyond documenting PATH/CLI resolution.
- Porting or sharing Kotlin IntelliJ sources into the VS Code host.

## Validation Strategy

- Unit-test CLI JSON mapping, lifecycle/freshness presentation, elapsed-clock
  rules, and goal-control eligibility without launching VS Code.
- Smoke-test activation, status-bar update, settings resolve, and disposal in a
  VS Code Extension Development Host (or documented equivalent).
- Package the extension (`vsce package` or project-equivalent) and verify the
  artifact exists with a checksum sidecar when release packaging lands.
- Run `skill-bill validate` and the extension's own test / package scripts; do
  not fold VS Code tasks into `runtime-kotlin` Gradle `check`.

## Delivery Plan

1. Scaffold the isolated extension and ship read-only status-bar parity with
   settings and docs.
2. Add Stop / Pause controls and hosted release packaging for the VSIX asset.
