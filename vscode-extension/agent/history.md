## [2026-08-30] SKILL-222 subtask 1 — VS Code status extension foundation
Areas: vscode-extension/{domain,application,infrastructure/cli,infrastructure/prefs,presentation,ui,composition}, docs
- Greenfield `vscode-extension/` TypeScript package mirrors IntelliJ layered ownership: domain outcomes, application ports, CLI process infra, presentation mapping, thin `extension.ts` / status-bar UI, composition root. Builds, tests, and packages with no `runtime-kotlin` or `intellij-plugin` Gradle edges.
- CLI resolution is settings override → PATH / `SKILL_BILL_BIN_DIR` / `~/.local/bin`; a misconfigured absolute override does not fall back. Status polls `work status --format json` with coalesced refresh, bounded timeouts, redacted failures, and cancel on deactivate / workspace dispose.
- Presentation covers active, idle, stale, blocked, failed, unavailable, incompatible — planning `n/m` and current-phase execution text when present — plus a details view; local elapsed ticker without a CLI poll per tick. Last-known display cache is stale-overlay only.
- Pattern: keep IDE status wire → domain → presentation → UI identical in intent to IntelliJ so future Stop/Pause (subtask 2) can copy the plugin control eligibility and runner-isolation patterns without inventing a second status model. reusable
- Limitation: read-only status only; no Stop/Pause, Marketplace publish, or shared Kotlin extraction.
Feature flag: N/A
Acceptance criteria: 5/5 implemented
