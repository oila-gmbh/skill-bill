---
status: Complete
---

# SKILL-75 - claude MCP registration per profile

## Mode

single_spec

## Intended Outcome

`./install.sh` registers (and `./uninstall.sh` unregisters) the skill-bill MCP
server in **every** local Claude profile — the default `~/.claude` plus every
discovered `~/.claude-<name>` profile and any distinct `CLAUDE_CONFIG_DIR` — so a
session under any profile (e.g. `cc work` → `~/.claude-work`) has the skill-bill
MCP tools available. This closes the gap where work-profile runs of
`bill-feature-task`/`bill-feature-task-prose` silently produced no durable
workflow state because the `feature_implement_workflow_*` / `feature_task_*` MCP
tools were not registered in that profile.

## Overview

Durable workflow state (and all other skill-bill MCP tools) is served by the
skill-bill MCP stdio server registered in the agent's MCP config. For Claude Code
that config is a JSON file with an `mcpServers` map.

Today claude MCP registration is single-profile AND env-blind. `McpRegistrationOperations`
maps `InstallAgent.CLAUDE -> home.resolve(".claude.json")` (McpRegistrationOperations.kt:38),
i.e. it only ever writes `$HOME/.claude.json` (the default profile's config file).
It does not honor `CLAUDE_CONFIG_DIR` and does not fan out across the discovered
profile set. Result: `~/.claude-work/.claude.json` never receives the skill-bill
server, so `cc work` sessions have zero skill-bill MCP tools — observed concretely
when WE-4434 ran under the work profile and produced no durable workflow state
("Implementation recovered from working tree; no prior durable workflow state
existed").

SKILL-74 made skill links and native subagents fan out across the profile set via
`claudeConfigRoots(home, environment)`, but explicitly left MCP registration out
of scope. This feature extends the same fan-out to MCP registration, reusing
`claudeConfigRoots` as the single source of truth for the root set.

### Per-profile MCP config file mapping (important)

The Claude MCP config file path is NOT uniformly `<root>/.claude.json`:

- default profile (root `~/.claude`): config file is `$HOME/.claude.json`
  (sibling to `~/.claude/`, the current `home.resolve(".claude.json")`);
- named / `CLAUDE_CONFIG_DIR` profile (root `~/.claude-<name>`): config file is
  `<root>/.claude.json` (e.g. `~/.claude-work/.claude.json`, verified on disk).

The implementation must resolve each profile root to its correct config-file path
rather than assuming `<root>/.claude.json` for all roots. This asymmetry must be
confirmed against Claude Code's actual resolution during planning (see Open
Questions).

## Acceptance Criteria

1. Claude MCP registration resolves its target profile set from the SKILL-74
   `claudeConfigRoots(home, environment)` resolver — no second discovery path, no
   re-globbing `$HOME`.
2. For each resolved profile root, registration writes the skill-bill MCP server
   entry into that profile's correct MCP config file: `$HOME/.claude.json` for the
   default `~/.claude` root, `<root>/.claude.json` for named / `CLAUDE_CONFIG_DIR`
   roots.
3. `./install.sh` with `--mcp register` registers the skill-bill server in every
   resolved profile's config in one run; the entry uses the freshly installed
   `runtime-mcp` binary path (consistent with existing single-profile behavior).
4. `./uninstall.sh` removes the skill-bill MCP server entry from every resolved
   profile's config, leaving other servers and unrelated config untouched.
5. Registration and unregistration are idempotent per profile: re-running does not
   duplicate the entry, and a profile created after a prior install is picked up on
   the next run.
6. Existing JSON config is preserved exactly (formatting tolerance per the current
   `McpJsonConfig` behavior); only the `mcpServers.skill-bill` key is added/removed.
   Malformed profile config fails loudly for that profile without corrupting it,
   matching today's single-profile error behavior.
7. Non-claude agents' MCP registration (codex/opencode/junie) is unchanged and
   stays single-target.
8. When only the default `~/.claude` profile exists, behavior is byte-for-byte
   identical to the current single-profile registration (writes `$HOME/.claude.json`).
9. The install/uninstall summary reports which profiles received / lost the MCP
   registration.
10. Tests cover: register fans into default + a named profile's correct config
    files; unregister removes from every profile; the default→`$HOME/.claude.json`
    vs named→`<root>/.claude.json` path mapping; idempotent re-register;
    single-profile parity; malformed-profile-config loud-fail isolation; non-claude
    agents unchanged.

## Constraints

- Reuse `claudeConfigRoots` (SKILL-74) as the single source of truth for the root
  set; do not introduce a second discovery mechanism.
- Reuse the existing `McpJsonConfig` read/merge/write path per profile; do not fork
  JSON-merge logic.
- Thread `environment` the way the codebase does (defaulted
  `environment: Map<String,String> = System.getenv()`), keeping the application
  layer free of process-global env reads (per the SKILL-74 / RuntimeArchitectureTest
  precedent).
- Do not change the skill-bill MCP server entry shape (`type: stdio`, `command`,
  `args`) or the runtime-mcp binary resolution.
- Do not change non-claude MCP registration.

## Non-Goals

- Multi-profile MCP registration for non-claude agents.
- Creating profile directories that do not already exist (other than the default
  `~/.claude`, consistent with SKILL-74).
- Changing the MCP server entry contents or the runtime-mcp launcher.
- Migrating or repairing pre-existing malformed profile configs beyond loud-failing.
- Registering MCP into Claude profiles outside `$HOME` except via an explicit
  `CLAUDE_CONFIG_DIR`.

## Open Questions

1. **Default-profile config path.** RESOLVED: the default profile root `~/.claude`
   maps to `$HOME/.claude.json` (sibling to `~/.claude/`, the existing
   single-profile path), and each named / `CLAUDE_CONFIG_DIR` root maps to
   `<root>/.claude.json`. `McpRegistrationOperations.claudeProfileConfigPaths`
   resolves this mapping; the default-only case stays byte-for-byte identical to the
   prior single-profile behavior.
2. **Shell vs runtime fan-out seam.** RESOLVED: the runtime owns the per-profile MCP
   registration loop. `McpRegistrationOperations.register/unregister` fan out
   internally across `claudeConfigRoots(home, environment)` for the CLAUDE branch;
   the `install register-mcp` / `unregister-mcp` CLI command surface and the
   `install.sh` / `uninstall.sh` argv are unchanged. `uninstall.sh` captures the
   per-profile stdout to report removed paths without a shell-side loop.

## Validation Strategy

- Add focused runtime tests for per-profile MCP register/unregister fan-out and the
  default-vs-named config-path mapping (extend the existing MCP registration test
  sources and reuse the SKILL-74 multi-root test fixtures).
- `(cd runtime-kotlin && ./gradlew check)`.
- `skill-bill validate`.
- `scripts/validate_agent_configs`.
- Manual: after a plain `./install.sh`, confirm `mcpServers.skill-bill` exists in
  both `$HOME/.claude.json` and `~/.claude-work/.claude.json`; after `./uninstall.sh`,
  confirm it is removed from both.

Run bill-feature-task on .feature-specs/SKILL-75-claude-mcp-registration-per-profile/spec.md
