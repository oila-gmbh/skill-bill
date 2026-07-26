# SKILL-53 Shared Runtime Install Architecture - Subtask 1: Shared Install Selection Foundation

Parent overview: [spec.md](spec.md)  
Issue key: SKILL-53  
Branch model: same-branch (`feat/SKILL-53-shared-runtime-install-architecture`); commit on completion before subtask 2.

## Scope

Create the shared runtime-owned install-selection foundation outside `runtime-desktop:*`.

This subtask owns the data/model layer only:

- Introduce a shared install-selection model and store contract in runtime JVM modules usable by CLI and desktop code without any desktop dependency. Prefer the existing install boundary shape from `runtime-domain/install`, `runtime-ports/install`, and `runtime-infra-fs/install` instead of adding desktop-owned abstractions.
- Represent the latest successful install choices: selected agents, selected platform packs, telemetry level, and MCP registration choice. Keep paths inert outside adapters/composition.
- Add a file-backed persistence implementation under shared runtime infrastructure using repo-standard typed errors/loud-fail behavior for malformed persisted data.
- Add mapping helpers only where they belong in shared runtime code. If deriving the exact installed agents from an apply result requires orchestration context, define the typed seam here and leave call-site wiring to subtask 2.
- Keep desktop-only preferences such as recent repo path and UI state out of the shared model.

## Acceptance Criteria

1. A shared runtime install-selection model exists outside `runtime-desktop:*` and is usable by both CLI and desktop JVM code.
2. A shared persistence implementation writes and reads latest successful install choices, including selected agents, selected platform packs, telemetry level, and MCP registration choice.
3. The shared model does not include desktop-only preferences such as recent repo path or UI-only state.
4. The persistence implementation follows existing runtime loud-fail conventions for unreadable or malformed shared install-selection records.
5. Any typed seam needed to prefer resolved installed agents from successful apply results is present without wiring CLI or desktop behavior yet.

## Non-Goals

- Do not wire `skill-bill install apply`, `install.sh`, or desktop flows in this subtask.
- Do not migrate existing desktop `firstRun.*` preferences yet.
- Do not rework the full install runtime or move unrelated install apply mechanics.
- Do not change installer prompts or install selection semantics.
- Do not introduce dependencies from runtime modules to `runtime-desktop:*`.

## Dependencies

No subtask dependencies. This runs first because CLI and desktop need a shared model and store before either can persist or read the latest install selection.

## Validation Strategy

Primary: `bill-quality-check`.

Recommended local focus before the full check:

```bash
(cd runtime-kotlin && ./gradlew :runtime-domain:test :runtime-ports:test :runtime-infra-fs:test)
```

## Recommended Next Prompt

Run `bill-feature-implement` on `.feature-specs/SKILL-53-shared-runtime-install-architecture/spec_subtask_1_shared-install-selection-foundation.md`.

