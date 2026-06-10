# Boundary History — runtime-kotlin/runtime-infra-fs

## [2026-06-09] SKILL-75 claude-mcp-registration-per-profile
Areas: runtime-infra-fs/launcher, runtime-infra-fs/install, runtime-domain/install/model, runtime-cli, uninstall.sh
- Extends SKILL-74's `claudeConfigRoots(home, environment)` fan-out to MCP registration. `McpRegistrationOperations.register/unregister` fan out across profiles for the CLAUDE branch ONLY; non-claude agents stay single-target via `configPathFor`. No second discovery path — reuses `claudeConfigRoots` and `McpJsonConfig` (no forked JSON merge)
- Per-profile config-file mapping is asymmetric and lives in `claudeProfileConfigPaths`: default root (`home/.claude`) → `$HOME/.claude.json` (sibling, NOT `~/.claude/.claude.json`); every named/`CLAUDE_CONFIG_DIR` root → `<root>/.claude.json`. Compare against a normalized `home.resolve(".claude")` or the default/named split misclassifies
- Loud-fail isolation pattern (reusable): `claudeFanOut` is collect-and-surface — attempt every profile, write siblings, collect failures, then throw. The thrown `ClaudeMcpProfileFailure(message, succeeded)` carries already-written profiles so callers report partial state truthfully instead of total failure
- Pitfall caught in review: a typed exception caught by runtime-cli CANNOT live in runtime-infra-fs (`RuntimeAdapterDependencyAllowlistTest` forbids cli→infra-fs). Put it in runtime-domain (`skillbill.install.model`, exempt from the implementation-import ban) and extend `IllegalArgumentException` so `CliRuntime` still maps it to exit 1
- AC9 summary: human-facing CLI/uninstall text filters to `changed` profiles; the structured payload (`mcpProfilesMap`, apply `outcomes[].profiles[]`) keeps every profile with its `changed` flag. install.sh/uninstall.sh argv unchanged (no shell-side profile loop); uninstall.sh captures stdout to surface removed + partially-removed paths
Feature flag: N/A
Acceptance criteria: 10/10 implemented

## [2026-06-09] SKILL-74 auto-detect-claude-profiles
Areas: runtime-infra-fs/install, runtime-infra-fs/nativeagent, runtime-domain/install/policy, runtime-cli, install.sh, uninstall.sh
- `claudeConfigRoots(home, environment)` in `ClaudeConfigPaths.kt` is the single source of truth for the claude profile set: default `~/.claude` first, then marker-filtered top-level `$HOME/.claude-<name>` dirs (markers `.claude.json`/`.credentials.json`/`commands`/`agents`/`history.jsonl`), then a distinct non-blank `CLAUDE_CONFIG_DIR`; deduped by normalized abs path. The single-root `claudeConfigRoot` stays for AC9 (`agent-path claude`/`claude-agents-path` return only the active root)
- Multi-root fan-out is achieved at the plan-target seam, NOT by changing apply iterators: claude expands to N `InstallAgentTarget` rows (one `<root>/commands` each) in `InstallPlanBuilder`/`InstallPlanPolicy`, so `linkPlannedSkill`, the orphan sweep, and `InstallApplyCleanup` fan out unchanged. Pattern reusable: to make one agent target many dirs, expand the target list upstream and leave the `plan.agents` iterators alone
- `requireNoDuplicate*Targets` re-keyed from `agent` to `(agent, normalized path)` so N claude rows at distinct roots are legal while true same-path duplicates still fail
- Native subagents fan out via `NativeAgentProvider.Claude.homeAgentDirs` returning every `<root>/agents` (single source for both link and unlink); `linkClaudeAgents` must materialize EVERY resolved root uniformly (no per-root existence gate) or commands and agents diverge into a half-installed default root
- Pitfall caught in review/audit: env-default leaks. `claudeConfigRoots` env contribution and `InstallAgentService.claudeRoots` must take `environment` explicitly (threaded from `state.environment` at the CLI) — defaulting to `System.getenv()` in the application layer fails `RuntimeArchitectureTest`; infra-fs may keep the `System.getenv()` default
- Pitfall: shell→runtime contract changes break `InstallerShellDelegationTest`. New `run_runtime_cli install <subcommand>` calls (here `claude-roots`, `unlink-claude-agents`) must be whitelisted in BOTH fake-CLI stubs, and dropping a `--agent-target` pin (claude) requires updating the expected-argv test
- Runtime owns discovery: `install claude-roots` CLI command (port→service→adapter→`InstallOperations.claudeRoots`) is the only enumerator; install.sh drops its claude `--agent-target` pin and uninstall.sh loops the command for per-root commands+agents cleanup — neither shell re-globs `$HOME`
Feature flag: N/A
Acceptance criteria: 14/14 implemented

## [2026-06-08] orchestration-content-delivery
Areas: runtime-infra-fs/install, runtime-infra-fs/scaffold, runtime-domain/install/model, runtime-cli, skills/bill-feature-spec, skills/bill-feature-task-prose
- `writeRenderedSupportPointerFiles` now inlines the canonical orchestration doc bytes (via `normalizeMarkdownLineEndings + trimEnd + "\n"`) instead of a repo-relative path — the cache is detached so relative paths dangled
- `computeInstallContentHash` folds `Files.readAllBytes(pointer.target)` for each support pointer (was the relative-path string) — doc edits now invalidate the cache and force a re-inline
- `OrchestrationLinkStatus`, `OrchestrationLinkOutcome`, `ORCHESTRATION_LINK_FAILED`, `orchestrationLinks` field, `applyOrchestrationLinks`, `InstallApplyOrchestrationLinks.kt`, cleanup block, CLI mapping all removed — the symlink was near-vestigial once sidecars became self-contained
- `validateNoOrchestrationPathsInSkillBodies` added to `RepoValidationRuntime` — scans `skills/*/content.md` and platform-pack content files for bare `orchestration/[\w/.-]+` tokens; wired into `validateRepo`
- Pattern: install-cache content must be self-contained; orchestration content the runtime needs is bundled as a classpath resource (`*SchemaPaths`), not accessed by agents via paths
- Pattern reusable: any new sidecar or pointer that references an external file should inline the content at render time, not carry a path
Feature flag: N/A
Acceptance criteria: 8/8 implemented
