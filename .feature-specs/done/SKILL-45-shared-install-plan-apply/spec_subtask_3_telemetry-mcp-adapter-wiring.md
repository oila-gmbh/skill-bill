# SKILL-45 Shared Install Plan/Apply - Subtask 3: Telemetry, MCP, And Adapter Wiring

Parent overview: `.feature-specs/SKILL-45-shared-install-plan-apply/spec.md`

Original parent subtask: `.feature-specs/SKILL-45-initial-install-desktop-wizard/spec_subtask_1_shared-install-plan-apply.md`

Status: Complete

## Scope

Wire telemetry configuration and MCP registration into the shared plan/apply layer, and expose the contract in a way that later CLI and desktop adapters can consume without scraping shell output.

The implementation should reuse existing telemetry level behavior and `McpMutationResult`-style launcher/MCP registration outcomes. The typed shared install contract remains constrained to the supported install agents: `copilot`, `claude`, `codex`, `opencode`, and `junie`. If an existing MCP path supports additional agent identifiers, document the gap in code/tests or adapter mapping without expanding this feature's supported-agent contract.

## Acceptance Criteria

1. Telemetry apply behavior supports typed `anonymous`, `full`, and `off` choices matching existing CLI semantics.
2. MCP registration intent from the plan is executed or skipped according to the typed plan.
3. MCP registration outcomes are returned in structured apply results for later CLI and desktop adapters.
4. Success, warning, skipped, and failure states from telemetry and MCP work are represented without requiring shell-output parsing.
5. Runtime adapter awareness is added only where needed to expose or preserve the shared plan/apply contract; `install.sh` and CLI command migration are still deferred.

## Non-Goals

- Do not build the desktop setup wizard UI or desktop state model.
- Do not migrate `install.sh` or fully replace runtime CLI install command behavior.
- Do not change the supported install-agent set.
- Do not change platform-pack manifest contracts.

## Dependencies

- Depends on subtask 1 for typed plan and result contracts.
- Depends on subtask 2 for the core apply API shape that telemetry and MCP outcomes extend.

## Validation Strategy

Add focused tests for telemetry choices and MCP intent/outcome mapping where practical. Full end-to-end contract and regression coverage remains deferred to subtask 4.

## Recommended Next Prompt

Run bill-feature-implement on `.feature-specs/SKILL-45-shared-install-plan-apply/spec_subtask_3_telemetry-mcp-adapter-wiring.md`.
