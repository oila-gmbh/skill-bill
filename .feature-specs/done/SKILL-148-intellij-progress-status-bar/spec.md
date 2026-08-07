# SKILL-148: IntelliJ progress status bar foundation

## Intended Outcome

Add an IntelliJ Platform plugin to this repository whose first user-facing surface is a
project-aware Skill Bill status-bar widget. While feature work is active, the widget
shows the current Skill Bill step and bounded progress when the runtime can provide it.
Idle, stale, blocked, failed, incompatible, and unavailable states remain distinct and
honest.

The first release establishes an external-consumer boundary that can support a future
full IntelliJ tool window. Skill Bill exposes one versioned, read-only IDE status
contract across prose feature tasks, runtime feature tasks, verification, and
decomposed goals. The plugin consumes that contract through a repository port and keeps
IntelliJ presentation, application state, transport, and preference persistence
separate.

The architecture adapts the useful principles from
`Sermilion/KMPComposeStarter`—inward dependency direction, feature-owned presentation,
thin UI entry points, explicit lifetimes, repository-backed sources of truth, and
persistence behind a boundary—without copying its KMP, Compose, navigation, Room, or
code-generation choices into a JVM-only IntelliJ plugin.

## Acceptance Criteria

1. The repository contains an isolated IntelliJ Platform plugin build that uses the IntelliJ Platform Gradle Plugin 2.x, targets an explicitly documented IDE/JDK compatibility range, and does not make the existing runtime Gradle build depend on IntelliJ APIs.
2. Skill Bill exposes a schema-first, versioned, read-only IDE status command that selects status for an explicit repository root and returns typed JSON without requiring consumers to inspect SQLite tables, workflow records, manifests, or human-formatted CLI output.
3. The IDE status contract represents repository identity, selected work identity, workflow family, lifecycle state, current step identifier and display label, optional completed/total progress, authoritative goal/work and current-subtask start timestamps, update time, freshness, and a typed unavailable/incompatible/error condition.
4. Status projection has one runtime-owned precedence rule for multiple work items and reconciles prose feature tasks, runtime feature tasks, verification, and decomposed goals without the plugin reproducing workflow rules.
5. The plugin follows an enforceable clean architecture: IntelliJ UI depends on a presentation model; presentation depends on application/domain contracts; infrastructure implements status and preference ports; composition is the only layer that selects concrete adapters.
6. A project-scoped ViewModel exposes immutable `StateFlow` UI state and explicit refresh/lifecycle inputs; the status-bar widget only renders that state and emits user intents.
7. A project-scoped status repository invokes the Skill Bill status contract off the Event Dispatch Thread, bounds polling and process lifetime, coalesces overlapping refreshes, redacts unsafe process output from the UI, and stops all work when the IntelliJ project is disposed.
8. Plugin persistence is limited to lightweight user/project preferences and an optional last-known display cache behind a persistence port; it never becomes the source of truth for workflow progress and never writes to Skill Bill runtime persistence.
9. The registered status-bar widget displays concise current-step text, elapsed time since the goal/work started, elapsed time since the current subtask started, bounded progress when available, and unambiguous idle, stale, blocked, failed, unavailable, and incompatible presentations without misleading indefinite animation.
10. Widget registration, identifiers, disposal, visibility, accessibility text, tooltip content, and updates follow IntelliJ Platform status-bar APIs and remain project-aware when multiple IDE projects are open.
11. Unit and integration tests cover contract validation, work-selection precedence, status mapping, goal/work and subtask elapsed-time calculation, stale and error transitions, polling cancellation, persistence fallback, widget text/tooltips, and two concurrently open project contexts.
12. Architecture tests and dependency declarations prevent IntelliJ UI or plugin packages from importing runtime persistence implementations, filesystem parsing internals, workflow engines, or SQLite APIs.
13. Contributor documentation explains module ownership, dependency direction, status authority, local development, compatibility verification, and the extension path from the widget to a future full tool window.

## Constraints

- Keep the IntelliJ implementation in this repository as a top-level isolated project or
  included build with its own plugin packaging lifecycle.
- Treat the plugin as an external consumer even while it is colocated: communication
  crosses the versioned status contract only.
- Preserve `runtime-kotlin` as the authority for workflow interpretation, selection,
  reconciliation, and progress.
- Define or update runtime contract YAML under `orchestration/contracts/` before Kotlin
  consumers, with the required version constant, parity test, typed schema error,
  classpath bundling, and loud-fail parse seams.
- Use IntelliJ Platform services and disposables for application/project lifetimes; do
  not reproduce the starter's `AppScope`/`UserScope`/`ScreenScope` types.
- Use MVVM as a presentation boundary, not as permission to put process execution,
  persistence, or workflow interpretation in the ViewModel.
- Prefer IntelliJ-native UI for the status-bar surface. Compose and KMP are not required.
- Do not read or mutate the user's unrelated dirty worktree changes.

## Non-Goals

- Building the full Skill Bill IntelliJ tool window, replacing the desktop application,
  or reproducing its editor, validation, scaffold, or navigation surfaces.
- Starting, resuming, cancelling, retrying, or mutating Skill Bill workflows from the
  plugin.
- Reading Skill Bill SQLite tables or checked-in decomposition manifests directly from
  the plugin.
- Introducing Room, SQLDelight, or a plugin-owned relational database for the first
  release.
- Streaming events, IDE notifications, Marketplace publication, signing, paid features,
  or automatic Skill Bill installation.
- Remote Development/Split Mode support in the first slice; the architecture must not
  preclude it, and the limitation must be documented.
- Supporting non-IntelliJ editors or duplicating the existing desktop UI.

## Validation Strategy

- Validate the IDE status schema, Kotlin version parity, invalid payload rejection, and
  CLI JSON golden output.
- Exercise the unified projection against prose, runtime, verification, decomposed-goal,
  competing-work, terminal, stale, malformed, and absent-work fixtures.
- Run plugin domain/application unit tests without an IDE fixture, then IntelliJ
  Platform fixture tests for widget registration, update, disposal, accessibility, and
  multi-project isolation.
- Run the IntelliJ Platform Plugin Verifier against the declared minimum and current
  supported IDE versions.
- Run `skill-bill validate`, `(cd runtime-kotlin && ./gradlew check)`, the plugin's
  `check` and plugin-verification tasks, `npx --yes agnix --strict .`, and
  `scripts/validate_agent_configs`.

## Delivery Plan

1. Define the runtime-owned, versioned IDE status contract and unified read-only query.
2. Establish the isolated IntelliJ plugin build and clean MVVM/repository/persistence architecture.
3. Implement and verify the project-aware Skill Bill status-bar widget.
