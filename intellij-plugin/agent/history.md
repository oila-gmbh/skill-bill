## [2026-08-07] SKILL-148 IntelliJ plugin architecture foundation (subtask 2)
Areas: intellij-plugin/{domain,application,infrastructure,presentation,composition}, docs, CONTRIBUTING
- Isolated IntelliJ Platform Gradle 2.x plugin (IDEA 2025.2–2026.1, JDK 21) with stable plugin id, no language-plugin deps, and owned check/buildPlugin/runIde/verifyPlugin tasks. reusable
- Inward dependency packages: domain outcomes/clock/display-cache, application ports + StatusRefreshCoordinator, CLI/prefs adapters, immutable StateFlow ViewModel, project-scoped composition root. Architecture tests reject presentation→infra shortcuts and runtime/JDBC/SQLite imports. reusable
- CLI adapter is the sole live transport for `skill-bill work status --format json`: absolute project root, off-EDT, timeout/output bounds, coalesced polls, contract-version gate, typed domain failures — never raw stderr/tokens/sensitive paths. reusable
- Preferences persist only CLI path + refresh interval; last-known display cache is observation-time-marked and can yield only stale UI, never authoritative active. Polling starts on consumer activate and cancels with child processes on project dispose.
- Pattern for future IDE surfaces: widget/tool window collects the same ViewModel StateFlow and emits refresh/lifecycle intents — no second status transport. reusable
- Limitation (by design): status-bar widget, tool window, Compose UI, and publishing are out of scope for this subtask.
Feature flag: N/A
Acceptance criteria: 11/11 implemented
