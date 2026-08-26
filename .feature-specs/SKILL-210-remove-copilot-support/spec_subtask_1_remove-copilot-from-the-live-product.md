# SKILL-210 Subtask 1 - Remove Copilot from the live product

Parent spec: [.feature-specs/SKILL-210-remove-copilot-support/spec.md](./spec.md)
Issue key: SKILL-210

## Scope

Delete GitHub Copilot from the live Skill Bill product in one pass: domain enum,
install detect/plan/apply/link, MCP supported-agent dispatch, install-plan
schema, shell install/uninstall, docs, smokes, and tests. Keep leftover uninstall
of Skill Bill skill links at `~/.copilot/skills` as a historical path. Do not
reintroduce `InstallAgent.COPILOT`.

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

## Non-Goals

- Adding a Copilot headless launcher or native-agent provider.
- Removing Junie.
- Changing Claude, Codex, Cursor, or Junie install or launch paths except where a Copilot branch is deleted.
- Rewriting historical specs, launch-kit copy, or decision logs.
- Guaranteeing cleanup of operator-owned files under `~/.copilot` other than Skill Bill skill links named in the leftover-uninstall criterion.
- Changing Agnix instruction-file rules.

## Dependency Notes

Depends on: none

This is the only subtask. The parent inventory is the removal checklist.

## Validation Strategy

Retarget installer interactive tests that currently type `copilot` (it is first in
`SUPPORTED_AGENTS` today; after the cut the first remaining agent is `claude`).
Retarget or delete `.copilot` home fixtures, MCP Copilot cases, and the
skill-vs-native Copilot distinction test. Keep one assertion that
`fromId("copilot")` is unknown. Run focused install/MCP/schema tests, then
`skill-bill validate` and `(cd runtime-kotlin && ./gradlew check)`. Grep live
product sources for the agent id with the parent allowlist.

## Next Path

This is the only subtask. After it completes, the goal is done.

## Spec Path

.feature-specs/SKILL-210-remove-copilot-support/spec_subtask_1_remove-copilot-from-the-live-product.md
