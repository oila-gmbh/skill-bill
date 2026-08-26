# SKILL-210 - Remove Copilot support

## Mode

single_spec

## Intended Outcome

GitHub Copilot is fully removed as a Skill Bill agent. Install, detect, skill
linking, MCP registration, schemas, docs, smokes, and tests no longer treat
`copilot` as a supported harness. A later Copilot integration starts clean.
Remaining install agents are Claude Code, Codex, Cursor, and Junie.

## Overview

Copilot is an install target only. Skills land under `~/.copilot/skills` and MCP
at `~/.copilot/mcp-config.json`. It is not a `NativeAgentProvider`, not in
`AGENT_LAUNCHER_CLIS`, and not in invoking-agent context signals, so
`skill-bill goal` never runs on it. Docs and the installer still present it as a
supported harness. Tests treat it as the skill-only agent versus native
providers.

This work deletes that install tier. Same shape as the OpenCode/zcode live-product
purge: no refuse-tier leftover and no `InstallAgent.COPILOT` enum member. Uninstall
may still remove leftover Skill Bill skill links at the historical
`~/.copilot/skills` path by path string, not by reintroducing the enum.

### Live surfaces

| Surface | Action |
|---------|--------|
| `InstallAgent.COPILOT` | Delete. Preserve remaining order: claude, codex, junie, cursor. |
| `InstallPrimitives` skill path and detect signals for `.copilot` | Delete |
| `McpRegistrationOperations` Copilot cases and `~/.copilot/mcp-config.json` | Delete from supported-agent dispatch. Keep `McpJsonConfig` for Junie/Cursor/Claude. |
| `install.sh` `SUPPORTED_AGENTS` and help/detect copy | Drop `copilot` |
| `uninstall.sh` / `UninstallCommand` | Stop listing Copilot as supported. Keep leftover skill-link removal at `~/.copilot/skills` as a historical path. Leftover MCP unregister must not call `InstallAgent.fromId("copilot")`. |
| `install-plan-schema.yaml` `agentId` enum | Remove `copilot`. Bump `contract_version` with `INSTALL_PLAN_CONTRACT_VERSION`, typed error, and parity test. |
| `docs/getting-started.md`, `docs/capabilities.md` | Drop Copilot from supported-agent lists and the install-path table |
| `orchestration/review-delegation/PLAYBOOK.md` `## GitHub Copilot CLI` | Delete the section. Remaining harness sections stay. |
| Installer shell tests that type `copilot` as the interactive agent | Retarget to a remaining agent (claude is first after this cut) |
| Tests that seed `.copilot` or assert Copilot-vs-native distinction | Delete or retarget |
| `scripts/install_smoke_test.sh`, `scripts/agent_install_smoke_test.sh` | Drop `copilot` from matrices |
| `agent/history.md` | Append one removal entry. Do not rewrite older entries. |

`.agnix.toml` `copilot = true` is an Agnix instruction-file rule, not Skill Bill
agent support. Leave it.

## Acceptance Criteria

1. `InstallAgent.COPILOT` is gone. `InstallAgent.fromId("copilot")` fails as an unknown agent. Remaining ids are `claude`, `codex`, `junie`, `cursor` in that relative order.
2. Install detection, planning, apply, and skill linking no longer use `~/.copilot` or the agent id `copilot`.
3. Supported-agent MCP register and unregister no longer target `~/.copilot/mcp-config.json` and no longer switch on `InstallAgent.COPILOT`.
4. `install.sh` no longer lists, detects, or offers `copilot`.
5. Uninstall still removes leftover Skill Bill skill links under `~/.copilot/skills` when they exist, using a historical path rather than `InstallAgent.COPILOT`. Copilot is not advertised as a supported agent.
6. `install-plan-schema.yaml` `agentId` no longer includes `copilot`. Schema `contract_version`, Kotlin `INSTALL_PLAN_CONTRACT_VERSION`, typed schema error, and parity test stay in lockstep. Fixtures that encoded `copilot` as a planned agent are retargeted or deleted.
7. Live docs no longer present Copilot as a Skill Bill agent: `docs/getting-started.md`, `docs/capabilities.md`, and the Copilot harness section in `orchestration/review-delegation/PLAYBOOK.md`.
8. Tests and smokes that seed `.copilot` or assert Copilot skill-vs-native distinction are deleted or retargeted to a remaining agent.
9. Live-product greps for `copilot` as a Skill Bill agent id are clean outside allowlisted archives (`.feature-specs/done/`, historical `agent/history.md` / `agent/decisions.md` bodies, this spec tree), leftover uninstall historical paths, and the Agnix `copilot = true` rule.
10. `agent/history.md` records that Copilot was removed as an install-only harness with no runtime launch path.
11. `skill-bill validate` and `(cd runtime-kotlin && ./gradlew check)` pass.

## Constraints

- Canonical remaining-agent order is not alphabetical. Drop `copilot` from the front of the current list. Do not sort.
- `McpJsonConfig` stays. Junie, Cursor, and Claude still use JSON MCP configs.
- Leftover uninstall must not reintroduce `InstallAgent.COPILOT` or call `fromId("copilot")`.
- Do not disable `.agnix.toml` `copilot = true`.
- Schema bumps loud-fail legacy install-plan records with the typed error. Quarantine or regenerate in-band per existing contract policy.
- Do not rewrite `.feature-specs/done/` or prior history/decision bodies. Historical delegated-review Copilot rows under `docs/delegated-review/` stay as SKILL-159 record.

## Non-Goals

- Adding a Copilot headless launcher or native-agent provider.
- Removing Junie.
- Changing Claude, Codex, Cursor, or Junie install or launch paths except where a Copilot branch is deleted.
- Rewriting historical specs, launch-kit copy, or decision logs.
- Guaranteeing cleanup of operator-owned files under `~/.copilot` other than Skill Bill skill links named in AC 5.
- Changing Agnix instruction-file rules.

## Validation Strategy

Retarget or delete Copilot install, MCP, schema, and smoke coverage so the suite
no longer depends on `InstallAgent.COPILOT`. Keep one assertion that
`fromId("copilot")` is unknown. Then `skill-bill validate` and
`(cd runtime-kotlin && ./gradlew check)`. Grep live product sources for the agent
id with the allowlist in AC 9.
