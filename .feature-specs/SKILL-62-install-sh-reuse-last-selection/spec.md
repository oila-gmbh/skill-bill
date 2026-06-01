---
status: Complete
---

# SKILL-62 - install-sh-reuse-last-selection

## Mode

single_spec

## Intended Outcome

Add an `install.sh` option that reuses the latest successful install selections instead of prompting for agents, platform packs, telemetry, and MCP registration again.

## Overview

`install.sh` currently prompts every full install even though successful installs already persist a runtime-owned selection record at `~/.skill-bill/install-selection.json`. Desktop can reuse that shared record for post-publish reinstall, but the shell installer cannot.

Add a non-interactive reuse path for repeat installs after source changes, renderer changes, or scaffold work. The shell should still perform the normal runtime installation, clean-slate reset, staging, linking, telemetry, MCP, and optional desktop-app work; only the installer choice collection should be skipped.

The saved selection must be resolved before `run_pre_install_uninstall`, because that reset wipes `~/.skill-bill`. Missing, malformed, or no-longer-valid saved selections must fail loudly before destructive cleanup.

## Acceptance Criteria

1. `./install.sh --reuse-last-selection` is accepted by usage text and argument parsing.
2. When `--reuse-last-selection` is provided, the full installer skips these prompts:
   - agent selection mode and manual agent list;
   - platform pack selection;
   - telemetry level;
   - MCP registration choice.
3. Reuse reads the latest successful runtime install selection from the shared install-selection store, not from any desktop-only preference file.
4. Reuse resolves and validates the saved selection before `run_pre_install_uninstall` deletes `~/.skill-bill`.
5. If the saved selection record is missing, malformed, unreadable, or contract-version incompatible, the installer exits non-zero before cleanup and prints a remediation hint to run `./install.sh` without `--reuse-last-selection`.
6. Saved selected agents are replayed as the installer’s manual agent list, preserving existing `get_agent_path` target resolution and duplicate handling.
7. Saved platform pack selection is replayed exactly:
   - `none` means base skills only;
   - `all` means all currently available platform packs;
   - `selected` means only the saved slugs.
8. Reuse fails before cleanup if a saved `selected` platform slug is not present in the current `platform-packs/` manifest-backed list.
9. Saved telemetry level is replayed as the runtime install telemetry setting.
10. Saved MCP registration replays the saved `register` boolean, but uses the freshly installed runtime MCP binary path for the current install rather than trusting a stale stored binary path.
11. `--reuse-last-selection` remains compatible with install-source and desktop options, including:
    - `--from-source`;
    - `--release TAG`;
    - `--with-desktop-app`;
    - `--no-desktop-app`;
    - `--desktop-app-dir PATH`.
12. `--reuse-last-selection` is rejected with `--desktop-app-only`, because desktop-only install intentionally skips full runtime install selection.
13. The final install summary clearly indicates that saved selections were reused and still reports agents, platforms, telemetry, MCP, and desktop app outcome.
14. The runtime apply path still persists the latest successful selection after a reused install, so subsequent reuse reflects actual resolved installed agents.
15. Tests cover:
    - happy-path reuse from a canonical shared selection record;
    - missing/malformed record exits before cleanup;
    - stale selected platform slug exits before cleanup;
    - MCP registration uses the current runtime MCP binary path;
    - incompatible `--desktop-app-only --reuse-last-selection` argument rejection;
    - usage text documents the new option.

## Constraints

- Do not hand-write or duplicate desktop preference internals.
- Prefer routing shared selection parsing through runtime-owned code or a runtime CLI entry point rather than ad hoc shell JSON parsing.
- Preserve the existing clean-slate install behavior after a saved selection has been successfully captured and validated.
- Do not change the install-selection JSON schema unless strictly required.
- Do not change default `./install.sh` behavior; prompting remains the default.
- Keep generated runtime artifacts and install staging output out of source.

## Non-Goals

- No automatic reuse without an explicit flag.
- No desktop first-run UI changes.
- No change to `skill-bill install apply` defaults unless needed as the runtime-owned seam for `install.sh`.
- No migration of old desktop-only preference records beyond what existing shared selection persistence already supports.

## Validation Strategy

Add focused installer/runtime tests around the new replay path, then run:

- targeted installer shell tests or architecture tests covering `install.sh`;
- targeted runtime install-selection tests if a runtime CLI replay/read seam is added;
- `(cd runtime-kotlin && ./gradlew check)` if the runtime CLI or shared install code changes;
- `skill-bill validate`;
- `scripts/validate_agent_configs`.
