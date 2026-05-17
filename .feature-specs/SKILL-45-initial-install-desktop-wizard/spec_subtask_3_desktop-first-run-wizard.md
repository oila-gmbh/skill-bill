# SKILL-45 Subtask 3: Desktop First-Run Setup Wizard

Parent spec: [spec.md](./spec.md)

Status: Complete

## Scope

Build the desktop first-launch setup wizard state, gateway, and UI on top of the shared install plan/apply backend from Subtask 1. The desktop must behave as an adapter over runtime contracts and must not scrape interactive shell output or grow repo-browser services into install orchestration.

The wizard should guide the user through agent selection, platform pack selection, telemetry level, and MCP registration, then apply the selected install plan through the typed gateway. It must support detected agents and manual selection for all supported agents.

Likely files/boundaries:

- `runtime-kotlin/runtime-desktop/src/main/kotlin/**/domain/**`
- `runtime-kotlin/runtime-desktop/src/main/kotlin/**/data/**`
- `runtime-kotlin/runtime-desktop/src/main/kotlin/**/ui/**`
- `runtime-kotlin/runtime-desktop/src/main/kotlin/**/state/**`
- `runtime-kotlin/runtime-desktop/src/test/kotlin/**`
- Existing desktop gateway contracts adjacent to `begin/run/finish`

## Acceptance Criteria

1. First launch offers a setup wizard for agent selection, platform pack selection, telemetry level, and MCP registration.
2. Wizard state is modeled explicitly and is testable without launching the full desktop app.
3. The desktop gateway calls the shared install plan/apply backend rather than parsing shell output.
4. Platform packs shown in the wizard come from dynamic `platform-packs` discovery; base skills are included automatically and are not presented as optional packs.
5. Telemetry choices are `anonymous`, `full`, and `off`, matching the existing CLI behavior.
6. Supported agents are `copilot`, `claude`, `codex`, `opencode`, and `junie`; the UI includes detection results plus manual selection.
7. The setup flow applies installs through the existing `~/.skill-bill/installed-skills` staging model.
8. Wizard result handling presents structured success, warning, and failure states, including Windows symlink/elevation outcomes when surfaced by the shared backend.
9. Tests cover wizard state transitions, gateway input/output mapping, agent/platform selection, telemetry selection, MCP registration choice, and install outcome rendering.

## Non-Goals

- Do not create native OS installer packages in this subtask.
- Do not migrate `install.sh`; that belongs to Subtask 2.
- Do not implement a remote pack marketplace.
- Do not change platform-pack manifests or supported-agent contracts except to adapt to the shared backend from Subtask 1.
- Do not commit generated governed artifacts.

## Dependencies

Depends on Subtask 1 for the shared install plan/apply API. It can run before or after Subtask 2, but it must not depend on shell installer behavior.

## Validation Strategy

Run desktop unit tests for wizard state/gateway behavior, then run:

```bash
(cd runtime-kotlin && ./gradlew check)
```

For UI work, also run or inspect the desktop app locally enough to verify the first-run flow is reachable and uses the typed gateway.

## Handoff Prompt

Run `bill-feature-implement` on `.feature-specs/SKILL-45-initial-install-desktop-wizard/spec_subtask_3_desktop-first-run-wizard.md`.
