## [2026-08-07] SKILL-168 plugin transient-idle smoothing (subtask 1)
Areas: intellij-plugin/{application,domain,infrastructure/cli}
- A `no_matching_work` problem code now marks its derived `Idle` via the existing `StatusDiagnostic.reasonCode` channel rather than a new outcome variant or boolean — the presentation `when` stays exhaustive with zero new branches. Reuse this marker pattern for any "origin of an outcome" distinction. reusable
- Sequential display policy lives in `StatusRefreshCoordinator.refreshOnce`, the single place owning emit + cache write + sequential state. The mapper stays a pure per-response translation and presentation stays a pure function of one outcome; do not add memory to either. reusable
- Corroboration policy: an unconfirmed idle following a live outcome re-emits the previous in-memory outcome and returns early; `UNCORROBORATED_IDLE_TOLERANCE = 1` in `domain/Constants.kt` settles genuine idleness on the second consecutive sample. Any other outcome resets the counter.
- New domain predicates `isUncorroboratedIdle()` / `isLiveOutcome()` on `SkillBillStatusOutcome`; `isLiveOutcome` enumerates Active/Paused/Blocked/Failed/Stale exhaustively, so a new outcome variant forces an explicit liveness decision. reusable
- A hold re-emits the **in-memory** prior outcome only — never a `LastKnownDisplayCache` read, and never a cache write. This preserves the standing contract that a cached display may surface only as `Stale`, and keeps `observedAt` from advancing on a sample that observed nothing.
- Limitation: plugin-side smoothing only; the bad sample still originates in the runtime (subtask 2). Tolerance is a constant, not user-configurable, and `UnavailableReason.NO_MATCHING_WORK` stays retained-for-compatibility and untouched.
Feature flag: N/A
Acceptance criteria: 8/8 implemented

## [2026-08-07] SKILL-167 plugin release workflow and docs (subtask 3)
Areas: .github/workflows/plugin-release.yml, intellij-plugin/README.md, RELEASING.md
- Plugin ships on its own `plugin-v*.*.*` tag stream via a dedicated `plugin-release.yml`; `release.yml` and the other runtime workflows stay byte-identical and gain no plugin references. A runtime tag never builds the plugin and vice versa. reusable
- Version flows only through `-Pversion=<derived-from-tag>`; `gradle.properties` keeps `0.1.0-SNAPSHOT` and no file is rewritten during release. Tag remainder must be plain semver or the job fails closed. reusable
- Release job is hosted-only (`ubuntu-latest`, `intellij-plugin/gradlew`, Temurin 21) and runs `check verifyPlugin buildPlugin` with `failureLevel` unchanged — self-hosted runner is off-limits for plugin work. reusable
- Asset staging is fail-closed and verified: exactly one `skill-bill-intellij-plugin-<version>.zip` plus a `sha256sum -c`-verifiable `.sha256` sidecar in `release.yml`'s format; the asset check precedes release creation, so a bad build never publishes a partial release. reusable
- Docs pattern for future streams: RELEASING.md documents tag format + never-cross-streams; plugin README points at the released zip first and build-from-source only as fallback.
- Limitation: no JetBrains Marketplace publishing, no signing, no plugin-side changelog automation.
Feature flag: N/A
Acceptance criteria: 7/7 implemented

## [2026-08-07] SKILL-165 planning progress rendering (subtask 2)
Areas: intellij-plugin/{domain,infrastructure/cli,presentation}
- `IdeStatusJsonMapper` parses the optional planning block from `work status --format json`; absent or malformed planning degrades to no planning state rather than failing the whole status parse. reusable
- Planning surfaces as an extra bar segment plus two tooltip lines (planning phase + `Progress: n/m`); when planning is absent the rendered output stays byte-identical to the pre-change baseline. reusable
- Paused/pre-planning goals anchor elapsed at observation time in `StatusUiMapper` (paused states do not tick with the local UI clock); stale observations keep the STALE_NOTE alongside the planning segment. reusable
- Planning state round-trips through `LastKnownDisplayCache`, so a stale cached render keeps planning context instead of dropping to a bare status.
- Limitation: planning rendering is read-only display; no new CLI transport, no planning-driven refresh cadence, non-goal status families render unchanged.
Feature flag: N/A
Acceptance criteria: 7/7 implemented

## [2026-08-07] SKILL-148 status-bar widget (subtask 3)
Areas: intellij-plugin/{ui,presentation}, plugin.xml, docs
- Registered `SkillBillStatusBarWidget` StatusBarWidgetFactory (id matches factory/widget) for normal project windows with resolvable project context. reusable
- Thin widget consumes project-scoped ViewModel StateFlow on EDT, owns local UI elapsed ticker (no CLI poll per tick), dispose cancels subscription/ticker/consumer without leaking project refs. reusable
- Pure StatusBarPresentation maps every UI state with dual elapsed clocks, progress-bound validation, truncation, tooltip/a11y, active-only animation, and read-only click refresh+details popup. reusable
- Presentation + platform fixture tests cover states, clocks, disposal, and independent consumers; full dual-Project HeavyPlatform harness not required when two ViewModel pairs prove isolation. reusable
- Docs cover expected states, CLI path setup, refresh/elapsed behavior, unavailable/incompatible troubleshooting, and deferred tool window.
Feature flag: N/A
Acceptance criteria: 12/12 implemented

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
