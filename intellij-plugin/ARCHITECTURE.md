# IntelliJ plugin architecture

This module is an isolated IntelliJ Platform plugin. It is an external consumer of
Skill Bill's versioned read-only IDE status contract (`skill-bill work status
--format json`). It never imports `runtime-kotlin` persistence, workflow engines,
filesystem manifest parsers, JDBC, or SQLite.

## Adapted starter principles

Borrowed from the KMP Compose starter's *useful* boundaries — not its stack:

| Starter idea | IntelliJ adaptation |
| --- | --- |
| Inward dependency direction | `presentation` → `application`/`domain` ← `infrastructure`; only `composition` wires concrete adapters |
| Feature-owned presentation | Project-scoped `SkillBillStatusViewModel` owns `StateFlow<SkillBillStatusUiState>` |
| Thin UI entry points | Status-bar widget (subtask 3) and future tool window only render/emit intents |
| Explicit lifetimes | IntelliJ `Project` + `Disposable` replace custom App/User/Screen scopes |
| Repository-backed source of truth | `StatusRepository` port; CLI adapter is the sole live transport |
| Persistence behind a port | `PreferenceCachePort` for settings + optional last-known display cache |

Not copied: KMP, Compose Multiplatform, Room/SQLDelight, navigation graphs, or
code-generated DI.

## Package ownership

```
dev.skillbill.intellij
├── domain/           Status outcomes, value types, clock, display-cache model
├── application/      StatusRepository, PreferenceCachePort, StatusRefreshCoordinator
├── infrastructure/   CLI process adapter, IntelliJ PersistentStateComponent prefs
├── presentation/     ViewModel, UI state, exhaustive StatusUiMapper
└── composition/      SkillBillStatusCompositionRoot, SkillBillProjectStatusService
```

Architecture tests under `src/test/.../architecture` reject presentation→infrastructure
shortcuts and forbidden runtime/JDBC/SQLite imports as pure JVM source scans.

## Source of truth

1. **Authoritative live status** — Skill Bill CLI IDE status contract only.
2. **Preferences** — CLI executable override and refresh interval (seconds-scale;
   default 15s). Never workflow state.
3. **Last-known display cache** — optional, observation-time-marked. May produce
   only a **stale** UI state, never an authoritative active state.
4. **Elapsed time** — derived in the ViewModel from authoritative `started_at` /
   `subtask_started_at` via an injected clock. Absent timestamps stay absent;
   never synthesized from `updated_at`.

## Persistence policy

- Use IntelliJ `PersistentStateComponent` (or equivalent lightweight settings).
- Persist only: CLI path override, refresh interval, bounded display-cache fields
  plus `observedAt`.
- Never persist tokens, prompts, phase artifacts, raw stderr, absolute sensitive
  paths, or unbounded process output.
- Never write Skill Bill runtime databases.

## Composition and lifetimes

`SkillBillProjectStatusService` is the project-scoped composition root entry.
It constructs adapters, coordinator, and ViewModel with explicit constructors.
Polling starts only while a consumer (future widget) is active, coalesces
overlapping polls, and cancels the loop plus child processes when the project
is disposed. There is no application-global mutable status cache.

## Future tool-window extension point

Subtask 3 registers the status-bar widget against `viewModel.uiState`. A later
tool window should:

1. Obtain `SkillBillProjectStatusService` for the open project.
2. Collect the same `StateFlow<SkillBillStatusUiState>`.
3. Emit the same refresh/lifecycle intents (`refresh`, activate/deactivate).
4. Add no second status transport and no direct CLI/process calls from UI code.

## Compatibility

- Products: IntelliJ IDEA Community and Ultimate only.
- Range: IDEA **2025.2** (build `252`) through **2026.1** (build `261.*`) inclusive.
- JVM toolchain: **JDK 21** for compile and `runIde`.
- Plugin id: `dev.skillbill.status` (Marketplace forbids the template word `intellij` in plugin IDs).

## Deferred

Marketplace publishing, plugin signing, Remote Development / Split Mode support,
and any workflow mutation commands remain out of scope for this foundation.
