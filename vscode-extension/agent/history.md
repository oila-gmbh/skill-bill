## [2026-08-30] SKILL-222 subtask 2 — Goal controls and VSIX release packaging
Areas: vscode-extension/{application,composition,domain,infrastructure/cli,presentation,ui}, .github/workflows/extension-release.yml, RELEASING.md
- Extension mutates workflow state via `GoalStopRepository` / `GoalPauseRepository` issuing `goal stop` / `goal pause` for the snapshot issue key and canonical workspace root. Eligibility lives only in `GoalControlsPresentation` (active + feature-goal + issue key); details UI consumes descriptors and never re-derives them. No Resume kind. reusable
- Composition root wires three distinct `ProcessRunner` instances (status poll, stop, pause) so a mutation cannot coalesce into a poll result; `ProcessRunnerIsolationTest` locks this behaviourally. Failures collapse to bounded summaries — no stdout/stderr/paths in the UI. Pause disables when `pause_requested` is already set on the snapshot.
- Hosted-only `extension-release.yml` on `extension-v*` tags stages exactly `skill-bill-vscode-extension-<version>.vsix` plus a `.sha256` sidecar and fails closed before publishing a partial set; Marketplace publish stays deferred. README documents Install from VSIX parallel to IntelliJ Install from Disk.
- Pattern: mirror IntelliJ SKILL-168 control eligibility + runner isolation in TypeScript presentation/composition; keep release asset naming and checksum sidecar convention aligned with `plugin-release.yml`. reusable
- Limitation: Stop/Pause only — no resume, launch, retry, abandon, tool window, or Marketplace listing.
Feature flag: N/A
Acceptance criteria: 4/4 implemented

## [2026-08-30] SKILL-222 subtask 1 — VS Code status extension foundation
Areas: vscode-extension/{domain,application,infrastructure/cli,infrastructure/prefs,presentation,ui,composition}, docs
- Greenfield `vscode-extension/` TypeScript package mirrors IntelliJ layered ownership: domain outcomes, application ports, CLI process infra, presentation mapping, thin `extension.ts` / status-bar UI, composition root. Builds, tests, and packages with no `runtime-kotlin` or `intellij-plugin` Gradle edges.
- CLI resolution is settings override → PATH / `SKILL_BILL_BIN_DIR` / `~/.local/bin`; a misconfigured absolute override does not fall back. Status polls `work status --format json` with coalesced refresh, bounded timeouts, redacted failures, and cancel on deactivate / workspace dispose.
- Presentation covers active, idle, stale, blocked, failed, unavailable, incompatible — planning `n/m` and current-phase execution text when present — plus a details view; local elapsed ticker without a CLI poll per tick. Last-known display cache is stale-overlay only.
- Pattern: keep IDE status wire → domain → presentation → UI identical in intent to IntelliJ so future Stop/Pause (subtask 2) can copy the plugin control eligibility and runner-isolation patterns without inventing a second status model. reusable
- Limitation: read-only status only; no Stop/Pause, Marketplace publish, or shared Kotlin extraction.
Feature flag: N/A
Acceptance criteria: 5/5 implemented
