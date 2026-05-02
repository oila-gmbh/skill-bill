## [2026-05-02] multi-runtime-subagents-feature-implement-foundation
Areas: skill_bill/install.py, skill_bill/__main__.py, install.sh, uninstall.sh, skills/bill-feature-implement/
- Extended `discover_codex_agent_tomls` and `discover_opencode_agent_mds` (plus their uninstall counterparts) with an additive optional `skills_root: Path | None = None` so subagent definitions co-located with author-owned skills (e.g. `skills/bill-feature-implement/codex-agents/`) are picked up alongside platform packs. Dedup via `dict[Path, None]` insertion order then `sorted()`. reusable
- Wired the new `--skills` CLI argument through all four agent subcommands in `__main__.py`, and extended the `install.sh` / `uninstall.sh` shell fallbacks to walk `$SKILLS_DIR/**/{codex,opencode}-agents/*` in lockstep with the Python primitive — Python invocation always passes `--skills "$SKILLS_DIR"`, the discover_* primitives tolerate non-existent dirs.
- Rewrote the one remaining Claude-specific spawn instruction in `skills/bill-feature-implement/reference.md:180` to runtime-neutral spawn-by-name. Greps confirm zero remaining `Agent tool` / `subagent_type=` / `general-purpose` occurrences in `reference.md` or `content.md`.
- Authored `skills/bill-feature-implement/parsing_tolerance.md` adopting Resolution A (best-effort recovery, single corrective re-spawn, then escalation) and cross-linked it from the Error Recovery section in `reference.md`. Documents that the orchestrator parses `RESULT:` blocks inline; no machine parser exists in `skill_bill/`. reusable
- Foundation for SKILL-33 follow-up 3 subtasks 2 (authoring 14 native subagent files) and 3 (test extensions + Codex smoke test). Existing tests still pass (220 OK); no regression in Kotlin/KMP pack discovery.
Feature flag: N/A
Acceptance criteria: 6/6 implemented

## [2026-05-02] multi-runtime-kotlin-review-feature-verify-subagents
Areas: platform-packs/kotlin/code-review/bill-kotlin-code-review/, skills/bill-feature-verify/, README.md, docs/, agent/history.md
- Extended the SKILL-33 Codex/OpenCode native-subagent precedent from KMP into `bill-kotlin-code-review`: eight Kotlin specialist definitions now ship as Codex TOML and OpenCode markdown under the Kotlin pack, each inlining the specialist content plus the shared F-XXX Risk Register contract because installed native agents cannot rely on repo-local sibling sidecars. reusable
- Kept the restored Kotlin backend review depth from [2026-04-18] intact: architecture and platform-correctness remain the baseline specialists, while security, performance, testing, api-contracts, persistence, and reliability can all be selected without collapsing backend/server coverage to fit one native wave.
- Added runtime-neutral Kotlin orchestrator notes for Claude/Codex/OpenCode spawning and documented stable fan-out chunking: Kotlin can select up to 8 specialists, so Codex/OpenCode runs should use waves of at most 6 specialists and never drop backend/server specialists just to fit one wave.
- Verified `bill-feature-verify` has no verify-specific specialist delegates; it continues to run `bill-code-review` as the child review path while feature-flag, completeness, and verdict audits stay inline through `audit-rubrics.md`. No verify-specific native agent files were created.
Feature flag: N/A
Acceptance criteria: 8/8 implemented

## [2026-05-02] opencode-native-subagents
Areas: skill_bill/install.py, skill_bill/__main__.py, install.sh, uninstall.sh, platform-packs/kmp/code-review/bill-kmp-code-review/, README.md, docs/, tests/
- Mirrored the SKILL-33 Codex native subagent precedent for OpenCode: added `~/.config/opencode/agents/` markdown-agent install primitives, CLI delegates, and shell fallbacks that discover `platform-packs/<slug>/**/opencode-agents/*.md` without hardcoding pack slugs. reusable
- Authored OpenCode markdown agents for the 2 KMP specialist reviewers with filename/name parity, required `mode: subagent` frontmatter, and the shared F-XXX Risk Register contract inlined because global OpenCode agents cannot rely on sibling repo sidecars. reusable
- Kept `bill-kmp-code-review` runtime-neutral and documented that OpenCode resolves specialists by installed markdown name, including manual `@<name>` invocation; OpenCode has no documented conflicting concurrency cap, so the conservative <=6 specialist-wave limit remains.
- Extended install/uninstall and markdown validation coverage alongside README/getting-started docs; baseline Kotlin and other orchestrators remain out of scope.
Feature flag: N/A
Acceptance criteria: 8/8 implemented

## [2026-05-02] codex-native-subagents
Areas: skill_bill/install.py, skill_bill/__main__.py, install.sh, uninstall.sh, platform-packs/kmp/code-review/bill-kmp-code-review/, README.md, docs/, tests/
- Added a `_codex_agents_path` primitive in `skill_bill/install.py` that mirrors the existing `_codex_path` skills primitive: prefers `~/.codex/agents/`, falls back to `~/.agents/agents/`. Exposed it as a secondary Codex install surface (`detect_codex_agents_target`) and added manifest-driven discovery (`discover_codex_agent_tomls`) plus install/uninstall helpers that walk `platform-packs/<slug>/**/codex-agents/*.toml` rather than hardcoding any pack slug. Reuses the same `InstallTransaction` model rather than introducing a parallel transaction system. reusable, follows [2026-04-17] new-skill-scaffolder precedent.
- Wired `install.sh` and `uninstall.sh` to delegate Codex subagent TOML symlinking to the Python module via `python3 -m skill_bill install link-codex-agents` / `unlink-codex-agents`, with inline manifest-driven shell fallbacks (`shopt -s globstar` over `platform-packs/**/codex-agents/*.toml`) so fresh-machine bootstrap and uninstall both work before Python deps are installed. Mirrors the [2026-04-11] opencode-agent-support docs/install precedent.
- Authored TOML subagent defs for the 2 KMP-specific specialists (`bill-kmp-code-review-ui`, `bill-kmp-code-review-ux-accessibility`) under `platform-packs/kmp/code-review/bill-kmp-code-review/codex-agents/`. The F-XXX Risk Register bullet contract from `specialist-contract.md` is inlined VERBATIM into each `developer_instructions` block because Codex agents cannot follow sibling symlinks. No new JSON envelope was introduced; baseline Kotlin specialists remain out of scope per the spec.
- Verified `bill-kmp-code-review`'s orchestrator prose is already runtime-neutral (no `Agent(subagent_type=...)` or `Task tool` literals) and appended a one-paragraph note explaining how Codex resolves each spawn by `name` against installed TOMLs in `~/.codex/agents/` (with `~/.agents/agents/` fallback), plus a one-line statement that KMP fan-out is at most 2 specialists per wave (well under Codex's `agents.max_threads = 6`).
- Documented the new install path and natural-language spawn caveat in `README.md`, `docs/getting-started.md` (added a row to the install-path table), and `docs/getting-started-for-teams.md` (added a paragraph in Runtime Expectations explaining that other orchestrators still run inline on Codex until follow-up issues ship their TOMLs).
- Test coverage: `tests/test_install_script.py` now creates `~/.codex/agents/` in `prepare_agent_homes` and asserts both pilot TOML symlinks materialize when codex is selected; `tests/test_uninstall_script.py` seeds TOMLs into BOTH `~/.codex/agents/` and `~/.agents/agents/` and verifies uninstall removes them via the manifest-driven fallback path; new `tests/test_codex_agents_toml.py` walks `platform-packs/**/codex-agents/*.toml`, parses each with `tomllib`, and asserts required-field presence, `name` matches filename stem, names unique, and no Claude-only references (`Agent(subagent_type=`, `Task tool`).
Feature flag: N/A
Acceptance criteria: 9/9 implemented

## [2026-05-01] python-runtime-retirement
Areas: skill_bill.launcher, pyproject entrypoints, docs/migrations, tests
- Retired Python CLI/MCP runtime selection: `skill-bill` and `skill-bill-mcp` now enter packaged Kotlin through `skill_bill.launcher`, and removed runtime env vars have no effect.
- Deleted the Python runtime entry files and their active Python-side runtime tests after the Kotlin CLI/MCP contract net and 3b bridge teardown covered normal-use paths. reusable
- Rollback guidance changed to install the previous release; do not reintroduce a Python runtime selector for rollback.
Feature flag: N/A
Acceptance criteria: 11/11 implemented

## [2026-04-30] feature-implement-large-work-decomposition
Areas: skills/bill-feature-implement/, runtime-kotlin/runtime-domain/, tests/
- Added a planning-stage decomposition mode for oversized `bill-feature-implement` runs: Step 3 now returns either `mode: "implement"` or terminal `mode: "decompose"` with ordered subtask specs and acceptance criteria. reusable
- Decomposition writes resumable `.feature-specs/.../spec_subtask_<n>_<slug>.md` handoff specs, presents dependency order, and closes before implementation as intentional planning-stage scope governance.
- Updated the Kotlin workflow definition so the primary runtime resume/continue text treats the plan artifact as either an implementation plan or a terminal decomposition package. reusable
- Known limit: full runtime-kotlin `check` still fails on an unrelated `runtime-application` detekt ReturnCount issue in `TelemetryService.autoSync`; affected `runtime-domain` checks pass.
Feature flag: N/A
Acceptance criteria: 4/4 implemented

## [2026-04-26] canonical-skill-md-shape
Areas: skills/, platform-packs/, scripts/validate_agent_configs.py, skill_bill/, tests/, orchestration/shell-content-contract/
- Locked SKILL.md to a single canonical shape across every skill: frontmatter + `## Descriptor` + `## Execution` + `## Ceremony` only, with a body banlist (fenced code, tables, `## Step N:` headings, embedded templates, install gates, telemetry instructions, routing rules, run-context placeholders). All substantive prose moves to content.md. reusable
- Reverses the 2026-04-21 feature-implement/verify-shell-pilot direction (step prose lived in SKILL.md): SKILL.md is now scaffold-only and content.md owns step prose with inline `Step id: \`X\`` bindings cross-checked against `skill_bill/constants.py`. reusable
- New `validate_skill_md_shape` (loud-fail via `InvalidSkillMdShapeError`); existing validators retargeted from SKILL.md to content.md (`FEATURE_IMPLEMENT_SHELL_REQUIRED_MARKERS`, `validate_feature_implement_shell_contract`, `validate_feature_verify_shell_contract`, `validate_workflow_driven_skills`). `PROJECT_OVERRIDES_HEADING` enforcement dropped; `.agents/skill-overrides.md` rule inherits via shell-ceremony.md. reusable
- Family taxonomy locked at 4 (`code-review`, `quality-check`, `workflow`, `advisor`) with no path-based exceptions; `HORIZONTAL_SKILL_FAMILIES` lookup added in `skill_bill/upgrade.py` so `skill-bill upgrade` cannot silently corrupt migrated SKILL.md files. reusable
- Known limit: `bill-editorial-assignment-desk` (lives on unmerged SKILL-30 branch) is out of scope; canonical shape applies there as a SKILL-30 follow-up. 9 deferred Minor validator/test cleanups (F-002 through F-010) tracked in workflow review_result.
Feature flag: N/A
Acceptance criteria: 8/8 implemented

## [2026-04-25] editorial-workflows-readian
Areas: skills/bill-editorial-assignment-desk/, runtime-kotlin/runtime-mcp/, scripts/, tests/, README.md
- Added the first non-coding governed workflow as `bill-editorial-assignment-desk`: a skill-only Readian-backed editorial assignment desk with stable step ids/artifacts, candidate-board pause, and story-pack boundary. reusable
- Established editorial contracts for ranking, source verification, social signal, ethics/risk, and selected story packs without introducing platform packs or durable editorial workflow state yet. reusable
- Aligned Readian MCP boundary tools with the real client fetch modes (`readian_get_spotlight`, `readian_get_articles_for_topic_query`) plus recursive secret redaction so auth/session material stays below MCP and unauthenticated calls return `auth_required`. reusable
- Validator and tests now pin editorial workflow markers, source/ranking contract markers, Readian auth-required behavior, token redaction, and README catalog presence.
Feature flag: N/A
Acceptance criteria: 15/15 implemented

## [2026-04-23] kotlin-runtime-port phase 4
Areas: runtime-kotlin/, docs/migrations/SKILL-27-kotlin-runtime-port.md, .feature-specs/SKILL-27-surface-integration/
- Replaced the marker-only `runtime-kotlin` CLI and MCP surfaces with real adapters for the review/learnings/stats/telemetry slice while keeping command/tool names, payload fields, and orchestrated review semantics aligned with the Python oracle. reusable
- Split the CLI port into command-support files (`CliReviewCommands`, `CliLearningCommands`, `CliTelemetryCommands`, output/helpers) so later phases can extend the surfaced command tree without rebuilding one monolith. reusable
- Added Kotlin-side telemetry config mutation helpers plus parity tests for representative CLI outputs, MCP payloads, remote-stats proxy traffic, and alternate `userHome` config resolution. reusable
- Known limit: production `skill-bill` / `skill-bill-mcp` entrypoints, workflow runtime, loader/scaffolder, install behavior, and launcher/cutover wiring remain Python-owned after this phase.
Feature flag: N/A
Acceptance criteria: 5/5 implemented

## [2026-04-22] kotlin-runtime-port phase 1
Areas: runtime-kotlin/, docs/migrations/SKILL-27-kotlin-runtime-port.md, agent/history.md
- Added a standalone JVM-only `runtime-kotlin/` Gradle module with local wrapper scripts, JDK 17 toolchain setup, version-catalog dependency management, a local `build-logic/` included build, and a package scaffold for future CLI, MCP, DB, telemetry, workflow, scaffold, and install ports. reusable
- Added shared Kotlin contract/error primitives plus initial smoke tests so later subsystem ports can reuse one local foundation instead of inventing per-area scaffolding. reusable
- Wired module-local quality gates from day one: 2-space Kotlin formatting via `.editorconfig`, `spotless` for formatting, and `detekt` for static analysis, all validated through the wrapper-based Gradle path. reusable
- Recorded the Phase 1 carryover in `docs/migrations/SKILL-27-kotlin-runtime-port.md`, keeping Python as the active runtime source of truth and pointing the next session at Phase 2 persistence work.
Feature flag: N/A
Acceptance criteria: 7/7 implemented

## [2026-04-21] feature-verify-shell-pilot
Areas: skills/bill-feature-verify/, scripts/, tests/, README.md, docs/getting-started-for-teams.md
- Split `bill-feature-verify` into a workflow shell in `SKILL.md` and an author-owned `content.md`, keeping workflow-state, continuation, stable artifact names, and telemetry ownership in the shell while moving phase-by-phase verify guidance into the sibling content file. reusable
- Added a dedicated validator hook for `bill-feature-verify` so missing `content.md`, missing shell workflow markers, or a missing execution pointer fail loudly without widening the generic workflow-shell rules yet. reusable
- Extended contract coverage and docs so fixture repos, acceptance/rejection tests, README, and team docs now treat `bill-feature-verify` like `bill-feature-implement`: shell-owned contract in `SKILL.md`, authored behavior in `content.md`. reusable
Feature flag: N/A
Acceptance criteria: 10/10 implemented

## [2026-04-21] feature-implement-shell-pilot
Areas: skills/bill-feature-implement/, scripts/, tests/, README.md, orchestration/workflow-contract/
- Split `bill-feature-implement` into a workflow shell in `SKILL.md` and an author-owned `content.md`, keeping workflow-state, continuation, stable artifact names, and telemetry ownership in the shell while moving phase-by-phase execution guidance into the sibling content file. reusable
- Keep `commit_push_result` as a reserved shell-owned artifact name until runtime persistence actually exists; document the name in the shell/reference contract but do not widen workflow storage just to satisfy the docs split. reusable
- Add a dedicated validator hook for `bill-feature-implement` so missing `content.md`, missing shell workflow markers, or a missing execution pointer fail loudly even though this workflow pilot does not use the generic governed wrapper contract. reusable
- Cover the split with both routing-contract assertions and validator fixture failures so future prompt edits cannot quietly collapse the shell back into a single authored file. reusable
Feature flag: N/A
Acceptance criteria: 10/10 implemented

## [2026-04-21] feature-implement-workflow-runtime
Areas: skill_bill/, skills/bill-feature-implement/, orchestration/workflow-contract/, tests/
- Added a durable `bill-feature-implement` workflow runtime with persisted workflow ids, step state, attempt counts, named artifacts, and a dedicated `feature_implement_workflows` store behind CLI and MCP surfaces for open/get/update/list/latest/resume/continue. reusable
- Wired `bill-feature-implement` itself to treat workflow state as authoritative for phase handoff and continuation, so interrupted runs can resume from persisted `assessment`, `plan`, `implementation_summary`, `review_result`, `audit_report`, and adjacent artifacts instead of reconstructing state from chat history. reusable
- Standardized continuation as a governed contract: resume/continue now return step-specific directives, required/missing artifact checks, recovered session context, and a continuation entry prompt for re-entering the skill at `plan`, `implement`, `review`, `audit`, `validate`, or terminal cleanup. reusable
- Added deterministic agent-resume tests plus opt-in subprocess E2E coverage across CLI and MCP, including `--latest`, latest-order parity, telemetry-linked sessions, blocked/missing-artifact cases, abandoned/completed terminal states, and review/audit loop recovery. reusable
- Known limit: continuation is now operational and testable, but the highest-fidelity coverage still uses a governed harness rather than a live model in default CI.
Feature flag: N/A
Acceptance criteria: 4/4 implemented

## [2026-04-21] workflow-contract-pilot
Areas: orchestration/, README.md, docs/, agent/
- Added a governed `workflow-contract` playbook for top-level orchestrators so long-running parent commands now have one source of truth for step ids, artifact handoff, retry/resume rules, and parent-owned telemetry. reusable
- Drew a hard boundary between reusable skills and workflows: leaf skills stay standalone, routed, and portable; only top-level orchestrators such as `bill-feature-implement` should adopt workflow state. reusable
- Wired the new contract into README and roadmap language so the repo now describes workflows as a governance layer on top of existing skills instead of as implicit prompt choreography.
Feature flag: N/A
Acceptance criteria: 3/3 implemented

## [2026-04-20] skill-authoring-terminal-loop
Areas: skill_bill/, README.md, tests/
- Added a terminal-first authoring loop on top of the existing split-wrapper scaffold: `skill-bill new`, guided `skill-bill edit`, `skill-bill list`, scoped `skill-bill validate`, and `skill-bill render`/`upgrade` now form the concrete single-skill workflow instead of forcing repo navigation. reusable
- The guided editor treats `content.md` as the only editable surface, preserves authored H2 order, supports replace / append / clear / skip actions per section, and regenerates only the targeted wrapper before re-running validation. reusable
- Added completion and drift inspection for content-managed skills plus targeted wrapper regeneration/validation helpers so maintainers can audit one skill at a time without running the entire repo workflow. reusable
- Maintainer-only bulk workflows remain separate: the migration script is still the path for legacy one-shot conversion, while end users stay on the single-skill terminal loop.
Feature flag: N/A
Acceptance criteria: 8/8 implemented

## [2026-04-19] collapse-skill-md-to-shared-ceremony
Areas: orchestration/shell-content-contract/, skill_bill/, scripts/, skills/, platform-packs/, docs/, tests/
- Collapsed the governed shell contract to `Descriptor` / `Execution` / `Ceremony`, with `SKILL.md` reduced to a thin wrapper and authored execution moved into sibling `content.md`. reusable
- Added shared `shell-ceremony.md`, descriptor drift validation, manifest `area_metadata`, and loud-fail checks for missing ceremony/content sidecars so governed skills now share one ceremony source of truth. reusable
- Updated the scaffolder, manifest editor, sidecar registry, repo docs, and in-repo pack fixtures to generate and validate the new shape, including platform-specific pre-shell feature stubs receiving the same shell-ceremony and telemetry sidecars as the root feature skills. reusable
- Controlled behavior stayed stable: README/catalog validation, `agnix --strict`, `validate_agent_configs.py`, and the full unit suite all pass after the reshape.
Feature flag: N/A
Acceptance criteria: 20/20 implemented

## [2026-04-18] flatten-canonical-skills-out-of-skills-base
Areas: skills/, scripts/, skill_bill/, README.md, AGENTS.md, docs/, tests/
- Moved canonical user-facing skills out of `skills/base/` and into `skills/<bill-skill>/`, leaving `skills/<platform>/...` only for legacy pre-shell platform overrides. reusable
- Updated installer and validator topology rules so root-level `skills/bill-*` directories are treated as the canonical/base set, while `skills/base/...` now counts as a legacy-invalid layout instead of a second supported home. reusable
- Retargeted moved skill sidecars, changed the scaffolder `horizontal` destination to `skills/<name>/`, and refreshed docs/tests so path assertions, fixture repos, and shell-contract references all match the flattened layout. reusable
Feature flag: N/A
Acceptance criteria: 4/4 implemented

## [2026-04-18] move-governed-addons-into-platform-packs
Areas: platform-packs/kmp/addons, scripts/, skill_bill/, README.md, AGENTS.md, tests/
- Moved governed KMP add-ons from `skills/kmp/addons/` into `platform-packs/kmp/addons/` so pack-owned runtime content now lives entirely under the owning platform pack instead of being split across `skills/` and `platform-packs/`. reusable
- Rewired KMP review and feature-implement sidecars to the new add-on targets, and updated `scripts/skill_repo_contracts.py`, the validator, and the scaffolder so `platform-packs/<platform>/addons/` is the canonical add-on topology while the old `skills/<platform>/addons/` layout now fails validation. reusable
- Updated AGENTS/README/scaffold docs and contract tests to describe add-ons as pack-owned assets, and added fixture coverage so future topology changes must keep pack manifests, sidecars, scaffold output, and validator expectations aligned. reusable
Feature flag: N/A
Acceptance criteria: 4/4 implemented

## [2026-04-18] restore-kotlin-backend-review-depth
Areas: platform-packs/kotlin, README.md, tests/
- Restored backend review depth inside the built-in `kotlin` pack instead of reviving a separate `backend-kotlin` pack: added `api-contracts`, `persistence`, and `reliability` specialist skills under `platform-packs/kotlin/code-review/`. reusable
- Updated `platform-packs/kotlin/platform.yaml` and `bill-kotlin-code-review` so backend/server signals stay on the Kotlin route but now select the backend specialists explicitly, preserving the simpler two-pack repo story while bringing server review depth back. reusable
- Expanded README and contract tests to pin the Kotlin pack at 9 code-review skills plus `bill-kotlin-quality-check`, and to assert the backend-specialist routing text so future pack simplifications do not silently drop server review coverage again. reusable
Feature flag: N/A
Acceptance criteria: 4/4 implemented

## [2026-04-18] narrow-built-in-packs-to-kotlin-and-kmp
Areas: README.md, AGENTS.md, CLAUDE.md, install.sh, platform-packs/{kotlin,kmp}, platform-packs/{agent-config,backend-kotlin,go,php}, scripts/, skill_bill/, skills/base/bill-quality-check/, tests/, docs/
- Reframed the repo as the governed skill-management system plus two built-in reference packs only: `kotlin` and `kmp`. Removed the shipped `agent-config`, `backend-kotlin`, `go`, and `php` pack roots, narrowed installer/catalog/help surfaces to the remaining built-ins, and kept `platform-packs/` as the pack home. reusable
- Folded backend Kotlin coverage back into the Kotlin baseline instead of preserving a separate built-in backend pack: Kotlin now owns backend/server routing notes, KMP always uses the Kotlin baseline, and the quality-check fallback is now only `kmp` -> `kotlin`. reusable
- Tightened shipped-surface validation to match the new product story: `scripts/skill_repo_contracts.py` only treats built-in Kotlin/KMP review skills as shipped portable-review inventory, while `scripts/validate_agent_configs.py` still discovers external scaffolded packages from live `skills/` and `platform-packs/` layouts so non-built-in stacks remain authorable. reusable
- Kept `CLAUDE.md` as a symlink to `AGENTS.md`; trimmed the shared instruction text just enough for `npx --yes agnix --strict .` to return zero warnings instead of breaking the link relationship. reusable
Feature flag: N/A
Acceptance criteria: 7/7 implemented

## [2026-04-17] boundary-history-value-rubric
Areas: skills/base/bill-feature-implement/, tests/
- Anchored the `boundary_history_value` telemetry field with a five-value rubric (`none` | `irrelevant` | `low` | `medium` | `high`) nested under step 3 of the pre-planning briefing's Instructions list in `reference.md`. Added a citation guardrail: `medium`/`high` ratings MUST cite a specific past entry in `boundary_history_digest`, otherwise downgrade to `low`. Enum, DB schema, validation, MCP signature, and platform-pack manifests all unchanged — the field is pass-through so this is a prompt-level fix, not a contract change. reusable
- Fixed `SKILL.md:171` `none` overload: clarified it means "no history existed at pre-read time" and documented that `boundary_history_written=true` paired with `value=none` is a legal combination (fresh boundary where `bill-boundary-history` creates the first entry post-completion). Full rubric stays in `reference.md`; SKILL.md keeps a one-line gloss per value.
- Motivated by a 180-day PostHog distribution on `skillbill_feature_implement_finished` (553 runs: 89% `medium`, 1.1% `high`, 0.4% `none`, 0.2% `low`, 9.4% unreported) — classic central-tendency bias from an unanchored 5-point scale. Expect post-merge distribution to shift; split the time series at merge date when analyzing.
- Added a round-trip test in `FeatureImplementEnabledTest` that loops every `BOUNDARY_HISTORY_VALUES` entry through `feature_implement_started` + `feature_implement_finished` and asserts persistence in both the `feature_implement_sessions` row and the emitted `skillbill_feature_implement_finished` outbox payload, pinning the enum tuple shape. reusable
Feature flag: N/A
Acceptance criteria: 10/10 implemented

## [2026-04-17] quality-check-shell-pilot
Areas: orchestration/shell-content-contract/, skill_bill/, skills/base/bill-quality-check/, platform-packs/{agent-config,go,kotlin,php}/quality-check/, platform-packs/{agent-config,go,kotlin,php}/platform.yaml, scripts/, install.sh, uninstall.sh, tests/, README.md, docs/getting-started-for-teams.md, CLAUDE.md
- Promoted `bill-quality-check` onto the shell+content contract via an additive extension: the shell contract version stays `1.0` and gains a new optional top-level manifest key, `declared_quality_check_file`, that points at a per-platform `platform-packs/<slug>/quality-check/<name>/SKILL.md`. Packs without the key remain contract-compliant. reusable
- Added `skill_bill.shell_content_contract.REQUIRED_QUALITY_CHECK_SECTIONS` (Description, Execution Steps, Fix Strategy, Execution Mode Reporting, Telemetry Ceremony Hooks — narrower than the six-section code-review set because the shell is horizontal) and `load_quality_check_content(pack) -> Path`. The loader raises `MissingContentFileError` when the key is unset or the referenced file is absent and `MissingRequiredSectionError` when any required H2 is missing. No silent fallback. reusable
- Relocated four quality-check SKILL.md files via `git mv` from `skills/<platform>/bill-<platform>-quality-check/` to `platform-packs/<platform>/quality-check/bill-<platform>-quality-check/` (agent-config, go, kotlin, php), added Description/EMR/TCH sections, and wired sibling `stack-routing.md` + `telemetry-contract.md` symlinks per relocated skill. `kmp` and `backend-kotlin` packs intentionally omit `declared_quality_check_file`; the shell hard-codes an explicit `kmp`/`backend-kotlin` → `kotlin` fallback route so existing KMP/backend-kotlin quality-check behavior stays identical.
- Rewrote `skills/base/bill-quality-check/SKILL.md` as a manifest-driven governed shell mirroring the post-SKILL-14 `bill-code-review` shell: dominant-stack routing via sibling `stack-routing.md`, manifest-driven resolution via `load_quality_check_content`, explicit `kmp`/`backend-kotlin`→`kotlin` fallback, loud-fail contract enforcement with the two named exceptions surfaced verbatim. Removed the prose `Delegation Rules` block that enumerated stacks.
- Flipped the scaffolder: `skill_bill.constants.PRE_SHELL_FAMILIES` drops `quality-check` (only `feature-implement` and `feature-verify` remain); `skill_bill.scaffold.SHELLED_FAMILIES` and `FAMILY_REGISTRY` promote `quality-check` to shelled with path template `platform-packs/{platform}/quality-check/{name}/SKILL.md`. Added `skill_bill.scaffold_manifest.set_declared_quality_check_file` (idempotent append/replace on the manifest, preserves key order and comments) and wired it into the atomic scaffolder flow. Manifest-write failure and symlink failure roll back the whole scaffold.
- Extended `scripts/validate_agent_configs.py::validate_platform_packs()` to invoke `load_quality_check_content(pack)` after loading any pack that declares the optional key; errors surface verbatim. Discovery stays manifest-driven — no platform slugs are enumerated in the validator. Extended `scripts/skill_repo_contracts.py::RUNTIME_SUPPORTING_FILES` with per-platform entries for all four relocated skills so `validate_platform_pack_skill_file` enforces the two sidecar symlinks. reusable
- `install.sh` + `uninstall.sh`: pure relocations whose skill names stay the same did NOT require new `RENAMED_SKILL_PAIRS` entries (the installer walks both `skills/` and `platform-packs/`; the uninstaller removes by skill name). Both scripts now carry a documentary comment above the array explaining the SKILL-14/SKILL-16 pattern so future relocations follow suit.
- Tests: added four new fixtures under `tests/fixtures/shell_content_contract/` (quality_check_only, code_review_and_quality_check, quality_check_missing_file, quality_check_missing_section) and five new test cases in `tests/test_shell_content_contract.py` covering acceptance + both rejection paths + the `None` case. `tests/test_scaffold.py`: flipped the pre-shell interim-note case to `feature-implement`, added `test_shelled_quality_check_family` (asserts path, manifest entry, correct required-H2 set, no Project Overrides) and a manifest-write-failure rollback test for the shelled quality-check flow.
- Narrowed SKILL-14-era prose in `CLAUDE.md`/AGENTS.md, `README.md`, and `docs/getting-started-for-teams.md` to reflect the new pilot scope: shell+content covers `bill-code-review` and `bill-quality-check`; `bill-feature-implement` and `bill-feature-verify` remain pre-shell.
Feature flag: N/A
Acceptance criteria: 19/19 implemented

## [2026-04-17] new-skill-scaffolder
Areas: skill_bill/, skills/base/bill-skill-scaffold/, orchestration/shell-content-contract/, install.sh, tests/, docs/, AGENTS.md, README.md
- Turned the shell+content contract from SKILL-14 into a one-shot authoring flow. `skill_bill/scaffold.py` exposes a pure-Python deterministic `scaffold(payload) -> ScaffoldResult` that supports four skill kinds (horizontal, platform-override-piloted, code-review-area, add-on), edits `platform.yaml` atomically (regex-based text patching so key order and comments survive), wires sidecar symlinks from `scripts/skill_repo_contracts.py::RUNTIME_SUPPORTING_FILES`, runs the validator, and auto-installs into detected local agents. reusable
- Loud-fail by design: new named exception catalog in `skill_bill/scaffold_exceptions.py` (`ScaffoldPayloadVersionMismatchError`, `InvalidScaffoldPayloadError`, `SkillAlreadyExistsError`, `ScaffoldValidatorError`, `ScaffoldRollbackError`, `UnknownSkillKindError`, `UnknownPreShellFamilyError`, `MissingPlatformPackError`, `MissingSupportingFileTargetError`). Every branch that cannot proceed raises; no silent fallbacks. Payload versioned at `scaffold_payload_version: "1.0"` and documented in `orchestration/shell-content-contract/SCAFFOLD_PAYLOAD.md`. reusable
- Atomic rollback across files, manifests, symlinks, and install targets: `_ScaffoldTransaction` records every mutation and `_rollback` unwinds install → symlinks → manifests → files → empty dirs. Validator failure triggers the full unwind; post-rollback tree is byte-identical to pre-run. Tests assert byte-identical restoration for validator-fail, manifest-fail, symlink-fail (both patched and real `Path.symlink_to`), and missing-supporting-target paths.
- Pre-shell family registry (`skill_bill/constants.py::PRE_SHELL_FAMILIES` + `skill_bill/scaffold.py::FAMILY_REGISTRY`) keeps `quality-check`, `feature-implement`, `feature-verify` overrides on the historic `skills/<platform>/bill-<platform>-<capability>/` layout and emits a "will move when piloted" note. Adding a pre-shell family requires updating both the tuple and the registry in the same change. reusable
- Scaffolder-owned sections (`## Execution Mode Reporting`, `## Telemetry Ceremony Hooks`) are emitted from stored templates in `skill_bill/scaffold_template.py` and are byte-identical across every specialist in a family; `## Project Overrides` is also rendered from template for `skills/`-tree kinds so `validate_skill_file` accepts the output. Author-owned sections (`## Description`, `## Specialist Scope`, `## Inputs`, `## Outputs Contract`) ship as minimal stubs that humans fill in.
- Shared install primitive in `skill_bill/install.py` (`detect_agents`, `install_skill`, `uninstall_skill`, `InstallTransaction`). Both the scaffolder and `install.sh` now delegate to this module via `python3 -m skill_bill install agent-path|detect-agents|link-skill`, with an inline shell fallback for fresh-machine bootstrap. Agent detection covers Claude Code, Copilot, GLM, Codex (with `~/.agents/skills` fallback), and OpenCode; no-agents-detected returns a skip note pointing at `./install.sh`. reusable
- CLI `skill-bill new-skill` added with `--payload`, `--interactive` (no-LLM 4-prompt fallback), and `--dry-run`. Typed error handling maps `ScaffoldError` subclasses to stable exit codes (2/3/4/5/6). MCP tool `new_skill_scaffold` registered with the orchestrated-flag pattern; new events `skillbill_new_skill_scaffold_started/_finished`. `skills/base/bill-skill-scaffold/SKILL.md` wholesale rewritten as an LLM-guided wrapper that paste → preview with synthesized markers → yes/edit/redo → subprocess `skill-bill new-skill --payload <tempfile>`.
- Test suite grew from 217 → 236 passing. `tests/test_scaffold.py` covers all four happy paths, payload rejection, version mismatch, three rollback modes (including real `symlink_to` failure), missing-supporting-target rollback, idempotency (`SkillAlreadyExistsError`), agent detection with 0/1/all-five home layouts, byte-identical scaffolder-owned sections, and CLI exit-code mapping.
Feature flag: N/A
Acceptance criteria: 19/19 implemented

## [2026-04-16] code-review-shell-pilot
Areas: skills/base/bill-code-review/, orchestration/shell-content-contract/, orchestration/stack-routing/, platform-packs/, skills/<platform>/ (code-review relocations), skill_bill/, scripts/, install.sh, uninstall.sh, tests/, README.md, AGENTS.md, docs/
- Split `bill-code-review` into a governed shell (`skills/base/bill-code-review/`) that owns ceremony, output structure, severity scales, telemetry, and contract enforcement, and user-owned platform packs under `platform-packs/<slug>/` that own reviewer reasoning. The pilot intentionally narrows the split to `bill-code-review` only; `bill-quality-check`, `bill-feature-implement`, `bill-feature-verify` remain on the pre-shell model for follow-ups. reusable
- Introduced the versioned shell+content contract at `orchestration/shell-content-contract/PLAYBOOK.md` (current version `1.0`) with a canonical sibling-sidecar link into the shell. Every pack declares `contract_version`, `routing_signals`, `declared_code_review_areas`, `declared_files` in `platform.yaml`; missing or mismatched artifacts raise specific named exceptions (`MissingManifestError`, `InvalidManifestSchemaError`, `ContractVersionMismatchError`, `MissingContentFileError`, `MissingRequiredSectionError`, `PyYAMLMissingError`) — no silent fallback. reusable
- Built `skill_bill/shell_content_contract.py` as the runtime authority: strict loader, discovery walk (`discover_platform_packs`), line-by-line H2 scan that skips fenced code blocks, lazy `_import_yaml` with a friendly missing-dep error. The loader is the single source of truth consumed by the shell, the validator, and the tests. reusable
- Rewrote `orchestration/stack-routing/PLAYBOOK.md` and `scripts/validate_agent_configs.py` to be fully manifest-driven: no platform slugs survive in routing playbooks or validator constants; `ALLOWED_PACKAGES` is computed from on-disk `skills/*` ∪ `platform-packs/*`. Tests assert AC9 continuously by enumerating live slugs and rejecting any that appear as section headings in the routing playbook. reusable
- Relocated 32 platform code-review SKILL.md files from `skills/<platform>/bill-<platform>-code-review*/` to `platform-packs/<platform>/code-review/` via `git mv` (history preserved via ≥69% rename similarity). Installer + uninstaller now walk both `skills/` and `platform-packs/`; base skills always install; platform packs are selectable.
- Test suite grew from 210 → 217 passing. Fixture-based accept/reject pattern covers 11 platform-pack shapes (valid + 10 rejection modes including extra-area, heading-in-fence, wrong-type governs_addons, unapproved area). Tag `v0.x-pre-shell-split` on `main` preserves the pre-relocation state permanently.
Feature flag: N/A
Acceptance criteria: 14/14 implemented

## [2026-04-16] sunset-legacy-feature-implement
Areas: skills/base/, scripts/, orchestration/, tests/, install.sh, README.md
- Sunset the orchestrator-centric `bill-feature-implement` and promoted the subagent-based `bill-feature-implement-agentic` to become the canonical `bill-feature-implement`. The subagent architecture (pre-planning, planning, implementation, audit, quality-check, and PR-description phases each in their own subagent) is now the default and only implementation. reusable
- Added `bill-feature-implement-agentic:bill-feature-implement` migration rule in `install.sh` so existing installs automatically clean up the old `-agentic` symlink on next install.
- Stripped all "experimental" and "-agentic" language from SKILL.md and reference.md. Updated validator, contracts, orchestration telemetry playbook, README catalog (49→48 skills), and routing-contract tests.
Feature flag: N/A
Acceptance criteria: 10/10 implemented

## [2026-04-15] addon-system
Areas: AGENTS.md, scripts/, orchestration/, skills/base/, skills/kmp/, tests/, install.sh, README.md
- Added a governed stack-owned add-on layer under `skills/<platform>/addons/` so framework/runtime depth stays behind dominant-stack routing instead of becoming new top-level packages or slash commands. reusable
- Established the reusable pattern: runtime skills consume add-ons through sibling supporting-file symlinks, while orchestration snapshots and validator rules define naming, ownership, reporting, and test expectations. reusable
- Piloted the model in `kmp` with `android-compose` implementation/review indexes plus topic files for edge-to-edge, navigation/results, and adaptive layouts, with recipe-style source pointers back to the official Android skills. reusable
- Base feature-implement and KMP review workflows now scan add-on indexes first and open only the linked topic files whose cues match the work, reducing token use while keeping Android-specific depth available. reusable
- Validator and routing-contract coverage now lock flat add-on paths under `skills/<stack>/addons/`, reject top-level/package confusion, and preserve future names such as area-scoped add-ons.
Feature flag: N/A
Acceptance criteria: 7/7 implemented

## [2026-04-13] telemetry-contract-sidecar
Areas: orchestration/telemetry-contract/, scripts/, skills/base/*, skills/<platform>/*, docs/, CLAUDE.md, README.md, tests/
- Extracted shared telemetry-contract text (orchestrated flag semantics, child_steps aggregation, Telemetry Ownership, Triage Ownership, Routers never emit, graceful degradation) from 12 telemeterable skill files into one canonical source: `orchestration/telemetry-contract/PLAYBOOK.md`.
- Each telemeterable skill now carries a `telemetry-contract.md` symlink (the 4th shared sidecar type, following the stack-routing/review-orchestrator/review-delegation convention). All 12 symlinks resolve to the same PLAYBOOK.md. reusable
- Validator updated: `validate_no_inline_telemetry_contract_drift` scans SKILL.md + reference.md for INLINE_TELEMETRY_CONTRACT_MARKERS and rejects re-inlined contract text. `validate_runtime_supporting_files` enforces sidecar presence/symlink/reference for all 12 skills via the RUNTIME_SUPPORTING_FILES registry. reusable
- PORTABLE_REVIEW_TELEMETRY_REQUIREMENTS and REVIEW_ORCHESTRATOR_TELEMETRY_REQUIREMENTS now check the telemetry-contract playbook (not SKILL.md or review-orchestrator playbook) for their required strings.
- Three new e2e tests: accept-with-sidecar, reject-without-sidecar, reject-with-inline-drift. Routing contract tests updated to verify symlink resolution.
- Skill-specific telemetry fields (feature_size, acceptance_criteria_count, review_session_id, routed_skill, etc.) stay in the respective SKILL.md files; only shared contract text moved.
Feature flag: N/A
Acceptance criteria: 9/9 implemented

## [2026-04-13] feature-implement-agentic
Areas: skills/base/bill-feature-implement-agentic/, scripts/validate_agent_configs.py, README.md
- Added experimental `bill-feature-implement-agentic` peer of `bill-feature-implement`. Same end-to-end workflow, but pre-planning, planning, implementation, completeness audit, quality check, and PR description each run inside a dedicated `Agent` subagent to keep the orchestrator context small. Code review stays in the orchestrator because `bill-code-review` already spawns specialist subagents.
- Reusable pattern: per-phase subagent briefing templates + strict `RESULT:` JSON return contracts (see reference.md). Orchestrator keeps only the structured returns in context. reusable
- Subagent runs are sequential in the same worktree (no parallelism, no worktree isolation). Quality-check and PR-description subagents are responsible for calling their MCP tools with `orchestrated=true` themselves and returning the `telemetry_payload` up to the parent.
- Registered the new skill in validator's `ORCHESTRATOR_SKILLS` so `validate_orchestrator_passthrough` enforces the `orchestrated=true` instruction for it too.
- Classic `bill-feature-implement` stays as the default; agentic variant is opt-in for users willing to trade inline visibility for a smaller orchestrator context window.
Feature flag: N/A
Acceptance criteria: 10/10 implemented

## [2026-04-13] skill-telemetry-orchestration-contract
Areas: skill_bill/, skills/base/*, docs/review-telemetry.md, scripts/validate_agent_configs.py, tests/
- Introduced an `orchestrated` flag on every telemeterable MCP tool so nested skills return a `telemetry_payload` to the parent instead of emitting their own events. Reusable pattern: standalone mode persists+emits, orchestrated mode no-ops and returns a structured payload with a `skill` field.
- Added 5 new events (`skillbill_quality_check_started/_finished`, `skillbill_feature_verify_started/_finished`, `skillbill_pr_description_generated`) behind new domain modules `skill_bill/quality_check.py`, `skill_bill/feature_verify.py`, `skill_bill/pr_description.py`. Each domain module mirrors the `feature_implement.py` pattern (generate-id / validate / save / build_payload / emit). reusable
- Extended `skillbill_feature_implement_finished` with a `child_steps` list aggregated from child tool returns, stored in a new `child_steps_json` column. One user-initiated workflow now produces exactly one telemetry event.
- Retrofitted `import_review` / `triage_findings` with the same `orchestrated` flag via a new `orchestrated_run` column on `review_runs`. `update_review_finished_telemetry_state` now returns the built payload so orchestrated callers can embed it; standalone callers still emit `skillbill_review_finished` to the outbox as before.
- Added a validator rule (`validate_orchestrator_passthrough`) that every orchestrator skill's `SKILL.md`/`reference.md` must contain the literal `orchestrated=true` pass-through instruction. Pattern: silently skip when the orchestrator skill directory is absent so fixture repos for unrelated tests keep passing. reusable
- Documented the contract in `docs/review-telemetry.md` under "Session correlation" with a full event catalog and schema tables for the new events.
Feature flag: N/A
Acceptance criteria: 14/14 implemented

## [2026-04-11] opencode-agent-support
Areas: install.sh, uninstall.sh, README, skills/base/bill-skill-scaffold, tests
- Added OpenCode as a first-class installer target with skills installed into its global skills directory and included in supported-agent docs and skill-sync guidance.
- Registered Skill Bill in the OpenCode global config using the `mcp.skill-bill` local-command shape instead of the existing `mcpServers`/TOML patterns used by other agents.
- Added JSONC-aware OpenCode config handling (comments and trailing commas) so MCP registration/removal stays compatible with real user configs. reusable
- Extended installer and uninstaller regression coverage to lock the OpenCode path and MCP contract in place.
Feature flag: N/A
Acceptance criteria: 6/6 implemented

## [2026-04-05] mcp-server
Areas: skill_bill/, skills/base/bill-code-review, install.sh, pyproject.toml, tests/
- Added MCP server (FastMCP/stdio) exposing 5 tools: import_review, triage_findings, resolve_learnings, review_stats, doctor
- Tools wrap existing skill_bill.* module functions directly — zero logic duplication (reusable)
- bill-code-review SKILL.md Auto-Import/Auto-Triage now prefer MCP tools with CLI fallback
- .mcp.json in repo root for Claude Code auto-discovery; install.sh confirms registration
- pyproject.toml adds `mcp` as first external dependency
Feature flag: N/A
Acceptance criteria: 10/10 implemented

## [2026-04-04] runtime-package-migration
Areas: skill_bill/, scripts/, skills/base/bill-code-review, install.sh, .github/workflows/, tests/
- Migrated monolithic scripts/review_metrics.py into skill_bill/ Python package with 10 domain modules (constants, db, config, review, triage, learnings, stats, sync, output, cli)
- CLI entrypoint `skill-bill` replaces direct `python3 scripts/review_metrics.py` invocations everywhere
- bill-code-review Auto-Import now calls `skill-bill import-review` instead of resolving script paths (reusable)
- install.sh telemetry setup uses `python3 -m skill_bill` for enable/disable
- pyproject.toml with zero external dependencies; CI installs via `pip install -e .`
- All behavior preserved exactly; pure structural migration
Feature flag: N/A
Acceptance criteria: 13/13 implemented

## [2026-04-02] review-acceptance-metrics
Areas: repo-root governance, orchestration/review-orchestrator, orchestration/review-delegation, skills/base/bill-code-review, stack review skills, scripts, tests, README
- Added a local-first review telemetry contract with `review_run_id` output and machine-readable `finding_id` risk-register lines for code-review flows.
- Added `scripts/review_metrics.py` as a reusable SQLite helper for importing review outputs, recording explicit accepted/dismissed/fix_requested events, and reporting stats.
- Added governance coverage so review contracts now enforce review-run id generation and delegated review-run id reuse across routed reviews.
- Documented the local telemetry workflow and default database location in README.
Feature flag: N/A
Acceptance criteria: 6/6 implemented

## [2026-04-03] review-acceptance-metrics phase 2
Areas: scripts/review_metrics.py, README, tests
- Added a number-based triage workflow so users can respond with `1 fix` or `2 skip - intentional` instead of raw finding ids.
- Added a separate local learnings layer with list/show/edit/disable/delete management commands so reusable review preferences stay user-reviewable and removable.
- Kept learnings separate from raw feedback event history so preferences can be changed or wiped without losing the telemetry baseline.
Feature flag: N/A
Acceptance criteria: 6/6 implemented

## [2026-04-03] review-learnings-application
Areas: scripts/review_metrics.py, orchestration/review-orchestrator, orchestration/review-delegation, skills/base/bill-code-review, stack review skills, README, tests
- Added scope-aware learnings resolution so active learnings can be resolved for `global`, `repo`, and `skill` review contexts with deterministic precedence. reusable
- Updated shared review contracts so routed and delegated reviews treat learnings as explicit context, pass them through delegation, and surface `Applied learnings` in the summary instead of hiding the behavior.
- Added validator and regression coverage so future review-skill edits cannot drop the auditable learnings contract silently.
Feature flag: N/A
Acceptance criteria: 5/5 implemented

## [2026-04-03] review-telemetry-remote-sync
Areas: scripts/review_metrics.py, install.sh, README, tests
- Added a local telemetry outbox plus optional remote batch sync so SQLite stays canonical while cross-install product analytics can be reported later.
- Added default-on installer telemetry preference handling and helper commands to inspect status, enable or disable sync, and flush pending events manually.
- Kept the remote payload privacy-scoped by excluding repo identity and raw review text while still reporting skill, feedback, and applied-learning metadata.
Feature flag: N/A
Acceptance criteria: 5/5 implemented

## [2026-04-03] review-telemetry-proxy-sync
Areas: scripts/review_metrics.py, install.sh, docs/cloudflare-telemetry-proxy, README, tests
- Added proxy-aware telemetry transport so installs can keep the same local outbox flow while sending batches to a configured relay.
- Added a Cloudflare Worker example that accepts Skill Bill telemetry batches, validates them lightly, and forwards them to the example backend with the credential stored server-side.
- Kept the telemetry privacy boundary: no repo identity leaves the client. Learning content is included in the `skillbill_review_finished` event.
Feature flag: N/A
Acceptance criteria: 6/6 implemented
