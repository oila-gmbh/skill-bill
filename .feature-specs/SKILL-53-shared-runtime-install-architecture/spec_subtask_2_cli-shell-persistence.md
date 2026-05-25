# SKILL-53 Shared Runtime Install Architecture - Subtask 2: CLI And Shell Persistence

Parent overview: [spec.md](spec.md)  
Issue key: SKILL-53  
Branch model: same-branch (`feat/SKILL-53-shared-runtime-install-architecture`); commit on completion before subtask 3.

## Scope

Wire successful runtime install apply operations to the shared install-selection store from CLI and shell entry points.

This subtask owns the non-desktop write path:

- Update `skill-bill install apply` so it persists latest install choices only after a successful typed apply result.
- Keep `runtime-cli` free of any dependency on `runtime-desktop:*`; use the shared runtime store/API introduced by subtask 1.
- Persist selected agents, selected platform packs, telemetry level, and MCP registration choice from the existing install request/plan/apply flow.
- For detected-agent installs, prefer actual resolved installed agents from the successful typed apply result when that data is available; fall back to planned agents only when the apply result does not provide stronger evidence.
- Ensure failed apply attempts do not overwrite the reusable latest successful selection.
- Keep `install.sh` delegated through the runtime CLI or shared runtime API after successful apply; it must not hand-write desktop preference internals or `firstRun.*` keys.

## Acceptance Criteria

1. `skill-bill install apply` can persist latest successful install choices without introducing a dependency on desktop modules.
2. `install.sh` delegates persistence through the runtime CLI or shared runtime API after successful apply and does not hand-write desktop preference internals.
3. Detected-agent installs persist the actual resolved installed agents when available from the typed apply result.
4. Manual agent selections, selected platform packs, telemetry level, and MCP opt-out choices persist through the shared store.
5. Failed install attempts are not persisted as completed reusable selections.

## Non-Goals

- Do not change installer prompts, option names, or install selection semantics beyond persisting the successful result.
- Do not change desktop first-run or post-publish reinstall behavior in this subtask.
- Do not add desktop modules to CLI/runtime dependencies.
- Do not change shared persistence format unless subtask 1 proves the foundation is insufficient.

## Dependencies

Depends on subtask 1 because the CLI and shell paths must write through the shared install-selection model and persistence implementation instead of creating a second format.

## Validation Strategy

Primary: `bill-quality-check`.

Recommended local focus before the full check:

```bash
(cd runtime-kotlin && ./gradlew :runtime-cli:test :runtime-application:test)
```

## Recommended Next Prompt

Run `bill-feature-implement` on `.feature-specs/SKILL-53-shared-runtime-install-architecture/spec_subtask_2_cli-shell-persistence.md`.

