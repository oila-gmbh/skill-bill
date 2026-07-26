# SKILL-45 Shared Install Plan/Apply Decomposition

Parent feature: SKILL-45 initial install desktop wizard

Original subtask spec: `.feature-specs/SKILL-45-initial-install-desktop-wizard/spec_subtask_1_shared-install-plan-apply.md`

Status: Complete

This decomposition splits the shared install plan/apply layer into resumable implementation specs because the original subtask spans runtime-domain install modeling, runtime-core install staging, launcher/MCP registration, native-agent linking, telemetry, platform discovery, CLI install awareness, and install.sh parity behavior.

## Acceptance Criteria

1. Exposes a typed install plan API that accepts agent selection, platform pack selection, telemetry level, MCP registration choice, runtime distribution inputs, and installation target paths.
2. Exposes a typed install apply API that consumes the plan and returns structured success, warning, and failure outcomes without requiring callers to parse shell output.
3. Base skills are always included in the generated plan, while platform packs are discovered dynamically from platform-packs.
4. Supported agents are copilot, claude, codex, opencode, and junie; the shared layer supports both detection results and manual selection.
5. Telemetry choices are anonymous, full, and off, matching existing CLI behavior.
6. Install application uses the existing staging model under `~/.skill-bill/installed-skills` and does not write generated governed artifacts into source directories.
7. The plan/apply contract includes MCP registration intent and outcome data for later CLI and desktop adapters.
8. Windows symlink/elevation behavior is represented explicitly in the plan/apply outcomes, including clear fallback or failure states.
9. Tests cover install planning, apply behavior, platform selection, agent selection, telemetry configuration, staging paths, and Windows symlink/elevation branches.

## Non-Goals

- Do not migrate `install.sh` or runtime CLI commands in this subtask.
- Do not build the desktop setup wizard UI or desktop state model.
- Do not change platform-pack manifest contracts or skill authoring rules.
- Do not commit generated `SKILL.md`, support pointer files, or provider-specific native-agent outputs.
- Do not add a remote marketplace or remote pack discovery.
- Do not change the supported-agent set unless a runtime contract gap is discovered and documented.

## Subtasks

1. `spec_subtask_1_contract-plan-builder.md`: define the shared typed contract and pure install plan generation.
2. `spec_subtask_2_apply-staging-agent-links.md`: implement side-effecting install application for staged skills, platform selections, agent links, native-agent links, and structured filesystem outcomes.
3. `spec_subtask_3_telemetry-mcp-adapter-wiring.md`: connect telemetry and MCP registration intent/outcomes and expose the shared layer cleanly to CLI/desktop adapters.
4. `spec_subtask_4_validation-contract-coverage.md`: add focused regression coverage and run the quality gate for the whole shared install layer.

Run the subtasks in order.
