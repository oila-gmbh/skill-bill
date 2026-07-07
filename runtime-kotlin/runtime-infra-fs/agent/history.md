# Boundary History — runtime-kotlin/runtime-infra-fs

## [2026-07-06] SKILL-107 feature add-on usage schema
Areas: orchestration/contracts, platform-packs/{go,ios,kotlin,kmp,php,python}, runtime-domain/scaffold, runtime-infra-fs/{scaffold,install,validation}
- Platform-pack shell contract bumped to 1.2 and `feature_addon_usage` became a manifest-backed, runtime-anchored field; schema/Kotlin parity and 1.1 rejection fixtures must move together. reusable
- Feature-task Android add-ons moved out of `orchestration/skill-classes/feature-task.yaml` and into the KMP pack manifest, so routed support pointers now compose class ceremony pointers plus selected platform feature add-ons. reusable
- Loader/validator rule: consuming a feature add-on pointer without a matching `feature_addon_usage.feature-task` declaration, or pointing at a missing add-on file, loud-fails through manifest/schema validation.
- Install staging now carries selected-platform context into support-pointer generation; keep apply-time and preview-time staging paths aligned when adding future routed pointer families.
- Known limit: `./install.sh` refused during runtime goal continuation, so post-goal install sync is still needed before relying on the user-level `skill-bill` launcher for contract 1.2 validation.
Feature flag: N/A
Acceptance criteria: 7/7 implemented

## [2026-07-05] SKILL-104 internal review packs (subtask 2: review-pack migration and call-site rewrite)
Areas: platform-packs/{ios,kotlin,kmp,python}/code-review (34 content.md), orchestration/review-delegation
- Flattened all 34 review-pack skills to `internal-for: bill-code-review` (PD2): 4 stack entries + 30 specialists, one-line frontmatter addition each, nothing else changed in those blocks.
- Call-site rewrite to the SKILL-102 sibling-sidecar file-read contract (PD5): `orchestration/review-delegation/PLAYBOOK.md` (Claude line 43, Codex line 53) and the specialist-read lines in `bill-kotlin-code-review` (Step 6) + `bill-kmp-code-review` (Step 4) now read `<name>.md` co-located with `bill-code-review`'s `SKILL.md` with "Do not use the Skill tool — internal skill, not listed". Wording contract copied verbatim from `skills/bill-feature/content.md` / `skills/bill-feature-task/content.md`. reusable
- Inventory sweep by skill NAME (not phrasing — SKILL-102's post-merge miss lesson): source dirs + a from-source staging install (all packs). Native-agent spawn references (`subagent_type`, `@name`) are PD6 identity strings, left untouched; specialist-selection tables, `routed_skill` contract, telemetry values byte-unchanged (PD4). Copilot delegation line 32 left unchanged (refers to parent-forwarded rendered runtime instructions, not standalone resolution). reusable
- KMP baseline composition renderer text UNCHANGED: `renderBaselineLayerLabel` emits `kotlin/bill-kotlin-code-review` as an identity string (`platform/skill`), not a path or Skill-tool call — Step 2.4 criterion 5 says leave identity-only text alone; `PlatformPackCompositionTest` already covers it.
- Rendered-output sweep confirmed all 34 sidecars co-located in `bill-code-review`'s staged directory alongside `SKILL.md`; no Skill-tool/slash-command/standalone-skills-dir resolution of the 34 in staged wrappers or support pointers.
- Validation: `skill-bill validate` PASS (0 issues, via rebuilt runtime-cli with subtask-1 mechanism); `agnix --strict` PASS; `scripts/validate_agent_configs` PASS (57 skills, 38 native agents); `./gradlew check` — only the pre-existing `CliFeatureTaskRuntimeRuntimeTest > feature-task-runtime run requires issue key and spec path` failure (environmental zcode prose-only refusal; markdown-only change cannot affect it).
Feature flag: N/A
Acceptance criteria: subtask-2 7/7 implemented

## [2026-07-05] SKILL-104 internal review packs (subtask 1: pack-aware internal-skill mechanism)
Areas: runtime-infra-fs/scaffold/authoring, runtime-infra-fs/install/plan, runtime-infra-fs/install/staging, runtime-infra-fs/install/apply, runtime-infra-fs/scaffold/runtime, runtime-domain/install/policy, runtime-domain/install/model, runtime-contracts/error
- `InternalSkillClassification.kt` base-skill-only violation removed (PD1); the `isBaseSkill` flag now feeds only the parent-side rule. Every preserved rule keeps its exact message; tests add pack-skill variants for blank/self/unknown/pack-parent/chained.
- `discoverInternalSidecarTargets` gains a `selectedPackSkills` param (PD3): union of skills-root scan + selected pack skills declaring `internal-for == parent`. Renders through the SAME `discoverTargets`+`renderWrapper` path (full governed wrapper, SKILL-102 PD6 parity). Three callers thread it: `InstallPlanBuilder.buildStagingIntent`, `InstallApplyStaging.materializeValidatedPlannedStaging`, `InstallStaging.stageInstalledSkill`. reusable
- Inertness: with no opted-in pack skill the new arg is empty and behavior is byte-identical (test + real-repo `skill-bill validate` 0 issues).
- PD8: new `MissingBaselinePlatformSelectionError` (carries selecting/required slugs + manifest path) + plan-time guard in `InstallPlanPolicy.buildPlanDraft`. ALL selection is trivially safe; packs without baseline layers unaffected.
- Validate-seam parity: `validateInternalSidecarReferences` and `internalSkillNames` (README exclusion) now scan the union of base+pack skill files; `validateInternalSidecarCollisions` already unioned both.
- detekt: `InstallPlanPolicy` and `stageInstalledSkill` crossed thresholds (TooManyFunctions / LongParameterList+LongMethod) — resolved with `@Suppress` + one-line rationale (established repo pattern).
- Pre-existing unrelated red: `CliFeatureTaskRuntimeRuntimeTest "feature-task-runtime run requires issue key and spec path"` fails on the clean tree too (zcode prose-only env refusal precedes the issue_key check) — not caused by this change.
Feature flag: N/A
Acceptance criteria: subtask-1 10/10 implemented

## [2026-07-03] SKILL-100 zcode agent support (subtask 2: native-agent-mcp-runtime)
Areas: runtime-infra-fs/install/apply, runtime-infra-fs/launcher/agentrun, runtime-infra-fs/launcher/mcp
- Closed the six exhaustive-`when` sites subtask 1 flagged as won't-compile: `linkZcodeAgents`/`unlinkZcodeAgents` (modeled on Junie) wired into `FileSystemInstallAdapters` link/unlink + a `nativeAgentInstallers` registry entry.
- New `McpZcodeConfig` reads/writes `~/.zcode/cli/config.json` under a nested `mcp.servers.skill-bill` key — a different on-disk shape than the flat `mcpServers` key every other agent uses; reuses `McpJsonConfig`'s shared read/write/mutable-map helpers. reusable
- `McpRegistrationOperations.register/unregister/configPathFor` add a ZCODE case; `unregister` drops `servers` then `mcp` only when both go empty, preserving sibling JSON keys at both nesting levels.
- New `ZcodeAgentRunCommandBuilder` builds `zcode --prompt <p> --json --cwd <dir> --mode yolo --no-color`, inheriting `usePtyStdio=false`/`idlePolicy=DB_PROGRESS_ONLY` defaults (no override needed, unlike opencode's PTY path); registered in `headlessAgentRunAdapters()` and survives `RUNTIME_REFUSED_AGENTS` (only OPENCODE is refused).
- Known non-blocking gap flagged in-code: the `--json` envelope parser shape is UNCONFIRMED against a live ZCode session — follow-up verification needed before relying on structured output.
- Tests extended in lockstep: `AgentRunLauncherTest` (adapter-presence + command-shape for ZCODE alongside CLAUDE/CODEX/JUNIE) and `McpRegistrationOperationsTest` ("non-claude agents stay single-target" expected-paths map).
Feature flag: N/A
Acceptance criteria: subtask-2 10/10 implemented

## [2026-07-03] SKILL-100 zcode agent support
Areas: runtime-infra-fs/install, runtime-infra-fs/nativeagent, runtime-domain/install/model, runtime-desktop/core/domain, orchestration/contracts
- Adding a new supported agent = fan out across SIX enums, modeled on the adjacent Junie entry (reusable checklist): InstallAgent, NativeAgentProviderId, NativeAgentProvider, NativeAgentLinkProvider, FirstRunSetupAgent (+ its `supportedIds` companion), AgentSymlinkProvider & DesktopAgentSymlinkProvider.
- `NativeAgentProvider.Zcode("zcode-agents","md")` uses `render = renderFrontmatterAgent(mode = null)` and `homeAgentDirs = listOf(home.resolve(".zcode/agents"))` — mode=null (like Junie, unlike Claude).
- Install-path layer: `SUPPORTED_AGENTS += "zcode"`; `agentPaths` "zcode"→`.zcode/skills`; `agentIsPresent` "zcode"→`listOf(.zcode)`; `agentDirectory` "zcode"→`InstallOperations.zcodeAgentsPath(home)`, backed by new `zcodeAgentsPath(home)=home.resolve(".zcode/agents")`.
- Deliberate non-changes: `RUNTIME_REFUSED_AGENTS` untouched (only OPENCODE refused); `INVOKING_AGENT_CONTEXT_SIGNALS` left CLAUDE/CODEX/OPENCODE only — zcode has no distinct invoking-context signal yet (Decision C, AC14).
- Exhaustive-`when` sites that WON'T compile until a ZCODE branch is added: McpRegistrationOperations (register/unregister/configPathFor), SkillRemoveJvmFileSystem.nativeProvider, JvmRuntimeSkillRemoveGateway, ConfirmDeletionDialog.displayLabelFor, FileSystemInstallAdapters NativeAgentLinkProvider link/unlink (+ new Junie-modeled `InstallNativeAgentOperations.link/unlinkZcodeAgents`).
- detekt `TooManyFunctions` trips on `InstallOperations` and `InstallNativeAgentOperations` once the zcode path resolver + link/unlink pair land — resolve with `@Suppress("TooManyFunctions")` + one-line rationale (established 67-file pattern), not a refactor.
- Tests extended in lockstep: FirstRunSetupModelsTest (supportedIds contains "zcode"), InstallPlanContractCoverageTest + InstallPlanSchemaValidatesExistingFixturesTest (add `.zcode` fixture-dir literal), and hardcoded provider-COUNT asserts bumped 5→6 (SkillRemoveTest, InstallPlanModelTest).
- Pre-existing unrelated red: `InstallerShellDelegationTest "install plan summary is printed before any mutation"` fails on the base commit too — not caused by this change.
Feature flag: N/A
Acceptance criteria: 14/14 implemented

## [2026-06-23] SKILL-89 per-subtask agent attribution — Seam D fs adapter
Areas: runtime-infra-fs/fs (new `FileSystemFeatureTaskRuntimeSpecStatusWriter`)
- `FileSystemFeatureTaskRuntimeSpecStatusWriter` implements the new `FeatureTaskRuntimeSpecStatusWriter` port; writes an idempotent `Agent: <id>` line immediately after the `Status:` line under `## Status` in a tracked `spec.md`. Three cases: (1) `## Status` heading absent → no-op; (2) `Agent:` line present → update in place; (3) heading present, no `Agent:` line → insert on the next line.
- File read/write is line-by-line via `readLines()`/`writeText`; no regex replace — plain index insertion avoids clobbering adjacent heading content. reusable
- Accepts a null `specPath` gracefully (port contract); wired into `FeatureTaskRuntimeSpecGate.finalizeSingleSpecOnTerminal` where it is called only when `specPath != null`. No loud-fail on a missing spec path — SMALL/no-spec runs are a no-op. reusable
- The acceptance-criteria reader (`FileSystemFeatureTaskRuntimeRunInvariantsSource`) keys off `## Acceptance Criteria`; `Agent:` lives only under `## Status`, so the two headings never collide.
Feature flag: N/A
Acceptance criteria: part of SKILL-89 12/12 — see runtime-kotlin/agent/history.md

## [2026-06-22] SKILL-88 opencode-pty-stdio
Areas: runtime-infra-fs/launcher/process, runtime-infra-fs/launcher/agentrun, runtime-application/featuretask
- PTY-backed stdio spawn path for opencode: `startPtyProcess()` in `JvmAgentRunProcessRunner` allocates a POSIX master fd via JNA (`PosixCLibrary` / `PosixLib`), builds the child process with `redirectInput/Output/Error(File(slavePath))`, and reads output through `PtyMasterInputStream` → `CappedUtf8Drain`. Fixes Bun-compiled opencode aborting status 1 when its stdout is a JVM pipe
- `ProcessStart.PtyStarted` seals alongside `Started`/`Failed`; `runStartedProcess()` receives `stdoutStream`, `stderrStream`, `ptyMasterCloseable` params; for PTY: stdoutStream=ptyMasterStream, stderrStream=nullInputStream (PTY master fd merges stdout+stderr)
- Fd lifecycle: `masterCloseable.close()` called BEFORE stdout/stderr drain joins to send EIO and unblock the drain — ordering is critical
- `usePtyStdio: Boolean = false` threaded through `AgentRunCommand` → `AgentRunProcessRequest` → `JvmAgentRunProcessRunner`; `OpencodeAgentRunCommandBuilder` sets it true; claude/codex/junie unchanged
- JNA 5.13.0 added to `runtime-infra-fs/build.gradle.kts` (version in `libs.versions.toml`) — provides `Native.load("c", PosixCLibrary::class.java)` without pty4j (unavailable offline)
- Linux-only guard: `check(os.name.startsWith("linux"))` is the first call in `startPtyProcess()` — macOS lacks `ptsname_r` and has a different `O_NOCTTY` constant
- `openMasterFd()` wraps `grantpt`/`unlockpt` in try/catch that closes the fd before rethrowing ISE (prevents fd leak on partial PTY init)
- `infraFailureReason` in `FeatureTaskRuntimeRunner` now appends a bounded stderr/stdout excerpt (reuses `stderrExcerpt` / `GoalRunnerLaunchFacts.STDERR_EXCERPT_MAX_CHARS`) when a phase agent exits non-zero
Feature flag: N/A
Acceptance criteria: 7/7 implemented

## [2026-06-10] SKILL-76 baseline-reconciliation (subtask 2)
Areas: runtime-infra-fs/install, runtime-domain/install/model, runtime-ports/install, runtime-application/install, runtime-cli, install.sh
- Reconcile-on-reinstall is dpkg-conffile semantics over installed skills: per-skill compare of three `computeInstallContentHash` values — upstream(staged candidate) / local(`~/.skill-bill` copy) / baseline(last-copied-in) — yielding one of five outcomes. `classifySkill` (`InstallReconcilePolicy.kt`) is the single classifier; `applyReconciliation` (`InstallReconcileApply.kt`) RE-derives the SAME plan from the same inputs rather than carrying it across the process boundary, so compute and apply can never disagree
- Outcome table (exhaustive, mutually exclusive): local==baseline→Adopt (take upstream + refresh baseline); local!=baseline & upstream==baseline→KeepLocal (no churn); local!=baseline & upstream!=baseline→Conflict; no upstream counterpart→LocallyAuthored (NEVER deleted); baseline present + both==baseline→KeepLocal no-op. `baselineRefreshPaths` = Adopt+NewUpstream+Conflict is the ONE refresh-eligibility set, reused by `refreshBaselineFromPlan` so refresh logic lives in one place
- Pitfall (Blocker caught in review): the shell must NOT bulk-`cp -R` the candidate platform-packs over live before the runtime apply — that overwrites edited platform-pack skill content and silently defeats keep-local/conflict for pack skills. The runtime per-skill apply is the SOLE writer of every reconciled skill dir in BOTH `skills/` AND `platform-packs/`; `adoptPlatformPackNonSkillFiles` copies only the NON-skill pack files (exclude enumerated skill `sourceDir`s). `adopt_non_skill_source_trees` in install.sh now only does the orchestration wholesale replace
- Pitfall (Major): `baselineHash==null` + divergent local must classify Conflict, not NewUpstream — silent overwrite at the migration window (existing populated `~/.skill-bill/skills`, no manifest yet) is data loss. `classifyNoBaseline`: local null or local==upstream→NewUpstream; else→Conflict. True first install (live skills dir absent → localHash null) is unaffected
- Pitfall (Major): per-skill replace must be crash-safe. `replaceSkillDirAtomically` renames the live dir ASIDE to a sibling backup, moves the staged copy in, drops the backup in `finally`, and restores the backup if the move-in throws — never delete-then-move (a crash in that window destroys the live skill irrecoverably)
- Conflict UX ordering (AC-7): detection + accept/abort decision happen BEFORE any live mutation. install.sh stages to `.candidate-*`, runs compute-only `install reconcile`, prompts, and only then runs `--apply`; abort discards the candidate and changes nothing. NO-TTY → abort with a clear message (never silent accept). Test seam `SKILL_BILL_RECONCILE_CONFLICT_CHOICE` bypasses ONLY the TTY check when set (prod behavior byte-identical when unset), since piped-stdin tests otherwise hit the no-TTY abort
- Baseline manifest `~/.skill-bill/baseline-manifest.json` ({contract_version, baselines: sorted path→16hex}) persists via the InstallSelectionPersistence-mirror trio (port + FS adapter + wire codec), atomic temp+ATOMIC_MOVE, sorted keys for byte-stable idempotent writes (AC-9). It is in uninstall.sh preserve-mode allowlist so it survives the pre-install wipe (subtask-1 reserved the path); explicit `./uninstall.sh` still removes it
- Shell↔runtime line-report contract: emit machine fields as `key=value` with the FREE-FORM token (a skill path that may contain spaces) LAST on the line — `reconcile_outcome: kind=<k> [upstream_hash=<hex>] path=<p>`. The shell anchors `grep '^reconcile_outcome: kind=conflict '` and extracts the path via `sed 's/^.* path=//'`, so spaces survive and the kind filter can't collide with a path. Gate the decision on the typed summary count (`conflict_count`), not the per-line grep
- Ownership: the application service (`InstallService`) refreshes the baseline from the returned plan; the infra adapter does per-skill file ops + conflict gating only. Keep port KDoc aligned with that split — a doc that claims the adapter refreshes invites a double-write
Feature flag: N/A
Acceptance criteria: subtask-2 AC-3/5/6/7/8/9 implemented + covered

## [2026-06-10] SKILL-76 migration + parity closeout (subtask 3)
Areas: runtime-infra-fs/install (tests), README
- Migration to the copied-source model needed NO new production code: `--replace-existing-skill-bill-links` (`InstallSymlinkReplacement.createManagedSymlinkWithGuidance(replaceExisting=true)` + `readSymlinkTargetOrNull`) already repoints a clone-pointing managed agent link onto the copy and leaves no dangling clone link. Locked by an `InstallApplyReplacementCleanupTest` case that seeds a link into a sibling clone and asserts the repoint resolves under `~/.skill-bill/installed-skills` with zero surviving links into the clone (AC-10)
- The SKILL-74 claude multi-profile fan-out, SKILL-75 per-profile MCP registration, and `CLAUDE_CONFIG_DIR` honoring are all SOURCE-LOCATION-AGNOSTIC: they key off `home`, not `--repo-root`. Moving `--repo-root` to the copy changed nothing. Pattern for locking this: a `copiedSourceFixture` that copies the seed repoRoot under `~/.skill-bill` and re-runs the existing multi-root assertions, plus a guard that `repoRoot.startsWith(home/.skill-bill)` — proves the invariant without forking the fan-out (AC-11)
- AC-4/AC-12 gap closed: the non-content-managed fallback test previously could only prove verbatim pass-through; strengthened to materialize an identically-named skill in a sibling clone and assert the fallback target resolves under the copy and NEVER into the clone
Feature flag: N/A
Acceptance criteria: subtask-3 AC-10/AC-11/AC-12 verified + covered; AC-3 clone-deletable guarantee documented

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
