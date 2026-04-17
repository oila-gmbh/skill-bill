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
Areas: install.sh, uninstall.sh, README, skills/base/bill-new-skill-all-agents, tests
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
