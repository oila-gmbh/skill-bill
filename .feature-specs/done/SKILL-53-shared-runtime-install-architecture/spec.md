# SKILL-53 — Shared runtime install architecture

Status: Complete
Issue key: SKILL-53

## Sources

- User-provided feature brief for SKILL-53 shared-runtime-install-architecture.

## Context

The desktop post-publish reinstall flow can reuse install selections only when those selections were persisted by the desktop setup wizard. Installs performed through `./install.sh` or `skill-bill install apply` go through the same typed install plan/apply runtime, but they do not persist reusable desktop-visible selections.

This exposed a broader architecture issue: install-selection state is runtime state, not desktop-only state. Today the desktop owns `DesktopFirstRunPreferences` in `runtime-desktop:core:datastore`, while CLI and `install.sh` depend on top-level runtime modules and cannot write that desktop-owned contract without creating the wrong dependency direction.

## Problem

The runtime has top-level shared modules such as `runtime-domain`, `runtime-core`, and `runtime-application`, but install behavior is concentrated in `runtime-core` and does not have clear domain/data/application submodule boundaries. Desktop has its own `core:domain`, `core:data`, and `core:datastore` modules, but those are not shared with CLI.

As a result:

- `install.sh` can collect agent, platform, telemetry, MCP, and desktop app choices, but cannot persist them into the desktop preference contract cleanly.
- Desktop has to own install-selection persistence even though the data describes the last runtime install.
- Future shared install state risks duplication between shell, CLI, desktop, and runtime internals.

## Goals

1. Define a shared runtime-owned install-selection domain model that can represent the latest successful install choices.
2. Define a shared persistence contract and implementation for that model outside desktop-only modules.
3. Let desktop setup/reinstall flows and CLI/shell install flows write the same persisted install-selection record after successful apply.
4. Let desktop read the shared install-selection record for post-publish reinstall without depending on shell-specific behavior.
5. Preserve existing typed install plan/apply behavior and loud failure semantics.
6. Avoid making `runtime-cli` depend on `runtime-desktop:*`.

## Proposed Direction

Introduce install-focused shared boundaries, either as new modules or as an incremental split from `runtime-core`:

- `runtime-install-domain`
  - install-selection model
  - store interface
  - path/key contract types where appropriate
- `runtime-install-data`
  - file-backed persistence implementation
  - properties/serialization handling
  - default `~/.skill-bill` path rules
- `runtime-install-application`
  - orchestration helpers around install apply and selection persistence
  - optional compatibility wrappers for existing `InstallOperations`

If introducing all three modules is too large for one cut, start with the smallest shared boundary that moves last-install-selection persistence out of desktop-only modules, then split additional install code afterward.

## Acceptance Criteria

1. A shared runtime install-selection model exists outside `runtime-desktop:*` and is usable by both CLI and desktop JVM code.
2. A shared persistence implementation writes and reads the latest successful install choices, including selected agents, selected platform packs, telemetry level, and MCP registration choice.
3. Desktop first-run setup and post-publish reinstall use the shared install-selection persistence instead of owning a separate install preference format.
4. `skill-bill install apply` can persist the latest successful install choices without introducing a dependency on desktop modules.
5. `install.sh` delegates persistence through the runtime CLI or shared runtime API after successful apply; it does not hand-write desktop preference internals.
6. Detected-agent installs persist the actual resolved installed agents when that information is available from the typed apply result.
7. Existing desktop-only preferences, such as recent repo path or UI-only state, remain desktop-owned.
8. Tests cover CLI install persistence, desktop preference adapter behavior, detected/manual agent cases, MCP opt-out, and migration/backward compatibility for existing desktop preference files.

## Non-goals

- Rework the entire install runtime in one change if a smaller persistence-focused split is sufficient.
- Change installer prompts or install selection semantics beyond persisting the successful result.
- Make runtime modules depend on desktop modules.
- Persist failed install attempts as reusable completed selections.

## Validation Strategy

- Add unit tests around the shared install-selection store.
- Add CLI tests proving `install apply` persists choices only after successful apply.
- Add desktop datastore/ViewModel tests proving existing first-run preferences migrate or adapt into the shared store.
- Run `(cd runtime-kotlin && ./gradlew check)`.
- Run `skill-bill validate`, `scripts/validate_agent_configs`, and `npx --yes agnix --strict .`.
