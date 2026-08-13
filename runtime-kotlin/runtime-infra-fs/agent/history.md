# Boundary History — runtime-kotlin/runtime-infra-fs

## [2026-08-14] SKILL-188 subtask 2 — harness coverage and truthful slug projection
Areas: runtime-kotlin/runtime-infra-fs/nativeagent/{composition,rendering,validation}, runtime-kotlin/runtime-infra-fs/install/apply, runtime-kotlin/runtime-infra-fs/infrastructure/fs, runtime-kotlin/runtime-domain/review/plan, platform-packs/kmp
- Composed add-on content now rides the same governed body into every NativeAgentProvider render and install target. Harness files still differ only in provider format; add-on blocks are identical per area.
- `ReviewAddonSelectionPolicy` is the single slug set for launch-plan `addOns`, in-render composition, inline rubric append, and validation. A reported slug is a composed slug; a declared-but-uncomposed target still loud-fails with slug, slot, and absolute path.
- Pattern: `enforceAddonProjectionParity` is a runtime check, not a docs convention — compose, launch-plan projection, and `validateRepoNativeAgents` share it. reusable
- Inline review appends the same policy-selected add-on files, so delegated and inline tiers get equivalent rubrics for the same area without telling any orchestrator to read a sibling sidecar.
- Limitation: already-installed agents refresh only on the next render/install. Regression and docs pinning remain subtask 3. Pack names stay out of shell/runtime code.
Feature flag: N/A
Acceptance criteria: 10/10 implemented

## [2026-08-14] SKILL-188 subtask 1 — compose addon_usage into rendered native agents
Areas: runtime-kotlin/runtime-infra-fs/nativeagent/{composition,rendering}, runtime-kotlin/runtime-contracts/error, orchestration/contracts, platform-packs/ios
- Native-agent rendering now composes add-on `entrypoint` and `companion_pointers` from pack `addon_usage` for the skill-relative directory being rendered. A markdown link in the owning `content.md` is neither required nor the trigger.
- Every target resolves through that directory's `pointers` table; missing, unreadable, or undeclared targets fail with `MissingContentFileError` naming slug, slot, and absolute path. Over-budget output fails with `ComposedNativeAgentBudgetExceededError` and never truncates.
- Pattern: `SidecarInliningSession` claims add-on paths before link rewrite so a file that is both a declared add-on and a link-inlined sidecar appears once, independent of resolution order. reusable
- Stable order is baseline/area body, then add-ons in declared `addon_usage` order (`entrypoint` before companions). Render-evaluable activation is written as a Declared-scope stanza; inherently diff-time conditions still compose and defer to runtime activation.
- Limitation: this seam covers the existing governed-content render path only; per-harness coverage and launch-plan slug reconciliation are subtask 2. Pack slugs stay out of shell/runtime code. No pack `content.md` was edited to activate an add-on.
Feature flag: N/A
Acceptance criteria: 12/12 implemented

## [2026-08-11] Cursor capability projection and spawn-note ordering (review repairs)
Areas: runtime-kotlin/runtime-infra-fs/nativeagent/{composition,rendering}, runtime-kotlin/runtime-infra-fs/scaffold/rendering, runtime-kotlin/runtime-infra-fs tests
- A declared read-only toolset now reaches Cursor as `readonly: true`. Cursor has no `tools` key, so rendering name+description only had silently left every review worker on the host default of every tool the parent can reach — write and recursive-delegation capability the read-only review contract forbids. `declaresReadOnlyToolset` is the projection rule; a toolset holding `Edit`/`Write`/`NotebookEdit`/`Agent` earns no `readonly` claim. reusable: a provider whose capability vocabulary is narrower than the source needs an explicit projection, never a dropped field.
- Claude, Junie, and Cursor share one `renderFrontmatterAgent` envelope and differ only in the capability fields they pass in, so an envelope change cannot reach one provider and skip another. The dead `mode` parameter is gone.
- `yamlNeedsQuoting` now quotes a value ending in `:` and plain-resolvable tokens (`no`, `off`, `~`, numerics); the name pattern permits them, so an unquoted `no` installed as a boolean and stopped matching its file.
- The Codex wave limit renders inside the Codex block instead of after another runtime's paragraph, and Junie gets an explicit "delegated unsupported, use `mode:inline`" paragraph so no installed runtime falls through the runtime-neutral rule and invents a spawn mechanism.
- The rendered Cursor paragraph now splits lane-level from run-level failure: a lane that launches and returns nothing attributable fails that lane; no matching installed agent, or a session that cannot launch by name, stops the run. Its governed phrasing is pinned to `review-delegation/PLAYBOOK.md` by `SubagentSpawnRuntimeNotesTest`, which the renderer cannot read at runtime.
Feature flag: N/A

## [2026-08-11] Cursor agent CLI delegated refusal copy in scaffold spawn notes
Areas: runtime-kotlin/runtime-infra-fs/scaffold/rendering
- `cursorSpawnParagraph` now distinguishes Cursor `agent` CLI (Task built-ins only) from the Cursor IDE agent UI when named specialists are installed but unlaunchable, and tells the operator to re-run `mode:delegated` in the IDE or use `mode:inline` on the CLI.
- Parity/snapshot coverage pins the `agent` CLI harness and IDE agent UI phrases so the warning cannot regress to a generic unavailable line.
Feature flag: N/A

## [2026-08-11] SKILL-182 subtask 2 Cursor native-agent frontmatter vocabulary
Areas: runtime-kotlin/runtime-infra-fs/nativeagent/rendering, runtime-kotlin/runtime-infra-fs/nativeagent tests
- `NativeAgentProvider.Cursor.render` now uses a dedicated `renderCursorAgent` path that emits only `name` and `description` (shared `yamlScalar` quoting), dropping Claude's `tools` key and omitting `model` / `readonly` / `is_background`.
- Claude and Junie still share `renderFrontmatterAgent` and stay byte-identical; Cursor drift is intentional so installed `~/.cursor/agents/` files match Cursor's frontmatter set.
- Pattern: per-provider frontmatter projection over a shared source model — `NativeAgentSource.tools` remains authoritative for consumers that use it; only the Cursor projection changes. reusable
- Tests pin Cursor shape (no `tools:`, no extra keys) and keep Claude/Junie snapshots; Claude/Cursor equality assertion replaced with per-provider assertions.
- Limitation: mapping Skill Bill tools onto Cursor `readonly` / `is_background` is deferred; default model inherit is left implicit (no explicit `model` emit).
Feature flag: N/A
Acceptance criteria: 8/8 implemented

## [2026-08-09] SKILL-174 boundary memory becomes a heading catalog + on-demand body resolve (subtask 2)
Areas: runtime-kotlin/runtime-infra-fs/goalplanning, runtime-kotlin/runtime-ports/goalrunner, runtime-kotlin/runtime-application/goalrunner, runtime-kotlin/runtime-domain/taskruntime, orchestration/contracts, skills/bill-feature-goal
- Discovery no longer ships byte-prefix excerpts of `agent/history.md` / `decisions.md`; it emits a heading-only catalog with stable ids, per-file and total caps, and a deterministic truncation marker.
- New `BoundaryMemoryHeadingParser` (reusable) walks headings for both history and decisions forms, skipping malformed regions without inventing or dropping entries.
- New `GoalPlanningBoundaryBodyResolver` port + FS impl (reusable): preplan picks heading ids, plan gets exactly those bodies; unknown/stale/excluded ids resolve to nothing.
- Exclusion roots centralized in `GoalPlanningRepositoryScope`; pruning applies at any depth and survives symlink canonicalization; platform pack agent trees contribute zero entries.
- Packet VERSION bumped to `0.3` with migrate-by-discard for `0.2`/`0.1` prefix payloads; projection digest gains optional `selected_boundary_headings` (older digests still validate).
- Limitation: heading selection quality depends on the preplan agent; a preplan with no selection field degrades to catalog-only rather than failing the sweep.
Feature flag: N/A
Acceptance criteria: 5/5 implemented

## [2026-08-08] Goal planning packet v0.2 drops platform_packs
Areas: runtime-kotlin/runtime-infra-fs/goalplanning, runtime-kotlin/runtime-ports/goalrunner
- `GoalPlanningContext` no longer carries `platformPacks`; discovery returns only `boundaryMemory` and `validationGuidance`.
- Packet key removal lives with the application-side VERSION `0.2` migrate-on-read; this module only stops inventing an empty map.
Feature flag: N/A
Acceptance criteria: N/A (follow-on to SKILL-172 deferred packet cleanup)

## [2026-08-08] SKILL-172 goal-planning context discovery stops buying platform.yaml (subtask 1)
Areas: runtime-kotlin/runtime-infra-fs/goalplanning, runtime-kotlin/runtime-application (GoalPlanningSweep packet validation), .feature-specs/SKILL-172-goal-planning-burst-and-context
- `FileSystemGoalPlanningContextDiscovery` no longer reads `platform-packs/*/platform.yaml`; `platform_packs` stays in the shared context packet as `{}` so `PACKET_FIELDS` / integrity stay resume-compatible without a VERSION bump.
- Discovery priority under `DiscoveryBudget` is load-bearing and documented: boundary_memory (history then decisions, sorted packs) before validation_guidance (`AGENTS.md`); budget exhaustion omits later categories entirely rather than silent argument-order truncation.
- Nine oversized-pack fixture proves boundary memory and AGENTS guidance survive when platform.yaml would previously exhaust the 32KB budget; allowlist/file-count and symlink-escape tests retargeted to history.md.
- Packet fixture with populated `platform_packs` still passes `GoalPlanningSharedContextPacket.validate` at VERSION `0.1`. reusable pattern: keep empty packet keys until a versioned migration removes them.
- Known limitation: `platform_packs` key and `validation_guidance` name remain until deferred versioned packet work; `MAX_DISCOVERY_*` caps unchanged.
Feature flag: N/A
Acceptance criteria: 7/7 implemented

## [2026-08-07] SKILL-164 checkpoint-keyed shared review evidence store (subtask 1)
Areas: runtime-kotlin/runtime-infra-fs, runtime-kotlin/runtime-ports/taskruntime, runtime-kotlin/runtime-domain/workflow/taskruntime/model
- New derive-once seam: `FeatureTaskRuntimeSharedEvidenceResolverPort` resolves shared review evidence keyed solely on `FeatureTaskRuntimeRepositoryCheckpoint.fingerprint` (+ workflow id); a fingerprint hit returns the stored artifact with zero repository traversal.
- `FileSystemFeatureTaskRuntimeSharedEvidenceStore` persists artifacts under the already-ignored repo-local `.skill-bill/` run store, addressed by workflow id + fingerprint; writes stage-then-replace so an interrupted write leaves nothing a later resolve would serve. No new `.gitignore` entry.
- Outcome contract is exhaustive and documented on the port: hit / absent / fingerprint-mismatch / unreadable-or-truncated all fall through to derivation and never fail the run; only a well-formed envelope whose recorded fingerprint contradicts its address loud-fails, via `FeatureTaskRuntimeSharedEvidenceFingerprintContradictionError` naming both fingerprints.
- Pattern followed: port request/derivation DTOs live in `skillbill.ports.taskruntime.model` (public-model-package rule enforced by `RuntimeArchitectureTest`); the deriver returns raw diff bytes and the store owns materialization. reusable seam for any future checkpoint-keyed derived cache.
- Known limitation: scope is per-workflow only — no cross-run or global cache, and fingerprint equality is the sole invalidation concept. Nothing consumes the store yet (no projection, briefing, review-lane, or telemetry wiring); that lands in later subtasks.
Feature flag: N/A
Acceptance criteria: 9/9 implemented

## [2026-08-05] SKILL-136 declare KMP persistence/reliability areas and route to them (subtask 3)
Areas: runtime-kotlin/runtime-infra-fs (scaffold tests), platform-packs/kmp, .feature-specs/SKILL-136-android-native-review-specialists
- The `kmp` pack now declares `persistence` and `reliability` itself, so `ReviewLaunchPlanPolicy` resolves them at composition depth 0 and shadows the Kotlin baseline's backend-framework lanes.
- Area focus text for both lanes names Android-native frameworks (Room, SQLDelight, DataStore, WorkManager) instead of backend-JVM ones; the Diff-Signal Routing Table gained matching rows.
- `architecture`, `performance`, `security`, `testing`, `api-contracts` still resolve to `bill-kotlin-code-review-*`; `platform-correctness`, `ui`, `ux-accessibility` stay KMP-declared.
- Added `ComposedReviewLaunchPlanTest` — asserts the full composed KMP resolution map and that a non-kmp pack's composed plan is unchanged. reusable shape for future pack-declaration changes.
- Known limitation: shadowing is whole-area, not per-lane additive; declaring an area replaces the baseline specialist outright.
Feature flag: N/A
Acceptance criteria: 8/8 implemented

## [2026-08-05] SKILL-139 drop redundant staged content.md
Areas: runtime-kotlin/runtime-infra-fs/install/staging, docs, orchestration/shell-content-contract, AGENTS.md
- Listed-skill staging no longer copies `content.md` into the installed dir; staged layout is `SKILL.md`, `.content-hash`, pointers, optional `native-agents/` only.
- Install content hash still reads source `content.md`, so authored-body edits still force re-stage; reconcile/intent name sets match the new layout.
- Internal sidecar staging drops any redundant verbatim `content.md`; rendered `<skill-name>.md` wrappers stay full and self-contained.
- Pattern: authored source remains the hash/input contract; generated install output must not re-ship source bodies agents already have inlined in `SKILL.md`. reusable
- Ceremony and docs no longer tell agents to read a sibling staged `content.md` as the body source.
Feature flag: N/A
Acceptance criteria: 8/8 implemented

## [2026-08-01] SKILL-153 phase-output structural repair (subtask 2)
Areas: runtime-kotlin/{runtime-application,runtime-contracts,runtime-domain,runtime-infra-fs,runtime-ports}, .feature-specs/SKILL-153-phase-output-structural-repair
- Decomposition manifests now use typed construction, canonical schema/coherence validation, YAML read-back, and atomic persistence; reusable.
- Syntax-only read failures enter a bounded deterministic repair seam: exactly one candidate is reparsed and revalidated, while ambiguity and semantic/type/coherence failures remain typed rejection.
- File-store and preparation-writer seams preserve no-partial-write behavior and redacted repair evidence with digests, format, operation, location, and contract version.
- Tests cover generation/read-back, syntax repair and ambiguity, schema/coherence failures, and atomicity.
- Known limitation: repair is syntactic only; semantic guessing and changes to manifest values, types, dependencies, statuses, or intent remain unsupported.
Feature flag: N/A
Acceptance criteria: 8/8 implemented

## [2026-08-01] SKILL-153 phase-output structural repair (subtask 1)
Areas: runtime-kotlin/{runtime-infra-fs,runtime-domain,runtime-contracts}, .feature-specs/SKILL-153-phase-output-structural-repair
- Added typed unchanged/repaired/rejected phase-output results and redacted repair evidence with format, contract version, digests, operation, and source location. reusable
- Structural repair uses bounded JSON/YAML candidates, preserves scalar content, requires exactly one candidate, reparses strictly, then follows the existing schema path. reusable
- Embedded envelopes retain their source bounds so a valid inner payload is not masked by an invalid surrounding response; regression tests cover extra/missing delimiters, strings, ambiguity, and evidence digests.
- Known limitation: YAML repair remains restricted to conservative parser-supported flow structures; semantic, indentation, quoting, anchors, duplicate-key, and block-structure changes stay rejected.
Feature flag: N/A
Acceptance criteria: subtask 1: 8/8 implemented

## [2026-07-27] SKILL-132 Orphan runtime contract asset audit (subtask 3)
Areas: runtime-kotlin/runtime-infra-fs, .feature-specs/SKILL-132-runtime-kotlin-dead-code-sweep
- Audited four bundled contract schemas (execution-identity, worker-ownership, goal-subtask-review-state, review-context) for orphan status; all four reached an `active` disposition, so nothing was removed.
- Producer-consumer-validation traces are recorded in the spec's evidence ledger; absence of file-name references was explicitly rejected as removal evidence.
- Added `FeatureTaskExecutionIdentitySchemaContractVersionTest`, closing the last retained-schema gap in version-parity coverage (pattern: `PlatformPackSchemaContractVersionTest`).
- Reusable: audit shape for contract assets — trace read seams, durable records, migrations, copy tasks, and `jar tf` bundling before proposing deletion; clean-rebuild + jar inspection proves the removal delta.
- Known limitation: removal delta was vacuous this subtask; configuration-cache and migration surfaces were untouched.
Feature flag: N/A
Acceptance criteria: 6/6 implemented

## [2026-07-27] SKILL-138 Cursor MCP install/uninstall, replay, and smoke tests (subtask 3)
Areas: runtime-kotlin/runtime-{cli,infra-fs}, scripts
- Cursor MCP registration targets `~/.cursor/mcp.json` with standard `mcpServers` merge semantics; register/unregister/config-path branches now cover CURSOR case in `McpJsonConfig` and `McpRegistrationOperations`.
- UninstallCommand extended to remove only managed Cursor skill links, native links, and Skill Bill's MCP entry while preserving user-owned Cursor content.
- Selection replay round-trips Cursor state and malformed input fails loudly through typed validation.
- Install-plan/apply and shell delegation allowlists now include Cursor in canonical order.
- Test coverage: MCP registration operations (idempotency, unrelated-key preservation, malformed-input failure), install plan application, and smoke test expectations all pass with isolated homes.
Feature flag: N/A
Acceptance criteria: 6/6 implemented

## [2026-07-27] SKILL-138 Cursor native agents, inventory, CLI, and removal (subtask 2)
Areas: runtime-kotlin/runtime-{application,cli,domain,infra-fs,ports,desktop}, orchestration/contracts, docs, AGENTS.md
- NativeAgentProvider.Cursor renders valid YAML-frontmatter Markdown targeting ~/.cursor/agents; provider loops now exhaustively cover Cursor in link/unlink operations, inventory schema, validation preflight, and skill removal execution.
- Added cursor-agents-path, link-cursor-agents, and unlink-cursor-agents CLI commands registered through runtime-surface with continuation guards and platform-aware path resolution.
- Repository validation rejects committed cursor-agents output and packaging excludes it while retaining provider-neutral native-agent sources under native-agents/.
- Desktop removal previews/mappings include Cursor with typed failure handling; uninstall primitives exhaustively cover cursor-agents cleanup.
- Pattern: new native-agent provider addition updates six enums (InstallAgent, NativeAgentProviderId, NativeAgentProvider, NativeAgentLinkProvider, FirstRunSetupAgent, AgentSymlinkProvider) plus link/unlink operations and inventory/preflight coverage. reusable
Feature flag: N/A
Acceptance criteria: 7/7 implemented

## [2026-07-22] SKILL-129 native-agent reconciliation and preflight (subtask 4)
Areas: runtime-kotlin/runtime-{application,domain,infra-fs,ports}, platform-packs/{kotlin,kmp}/code-review
- Provider-neutral declarations and flattened launch plans now share one complete logical worker set; undeclared workers, duplicate provider targets, and misleading baseline identities fail before launch.
- Native-agent install stages provider artifacts atomically, verifies logical identity, digest, readability, and current-generation ownership, then reconciles the complete managed-link inventory while preserving unmanaged files.
- Pattern: classify canonical current, obsolete cache, and legacy governed artifact targets through one reusable predicate before replacing or pruning links; never infer ownership from filename alone. reusable
- Delegated review preflight resolves every selected logical worker from current installed staging and returns a typed failure with the repair command instead of falling back to `general-purpose`.
- Reconciliation regressions cover dangling Kotlin/KMP links, stale generations, missing artifacts, duplicate targets, unmanaged collisions, legacy layouts, and every supported native-agent provider.
Feature flag: N/A
Acceptance criteria: subtask 4: 5/5 implemented

## [2026-07-14] SKILL-122 agent add-on delivery and scaffolding
Areas: runtime-infra-fs/{agentaddon,install,scaffold}, runtime-cli/scaffold, runtime-desktop/feature/skillbill, install.sh, uninstall.sh
- Agent-addon delivery discovers validated declarations dynamically and generates deterministic consumer pointers for `bill-feature` and its internal sidecars only in staged output; targets must remain regular repository files and collisions, self-reference, malformed paths, or missing targets loud-fail before promotion.
- `InstallStagingIntentBuilder` is the reusable staging composition seam shared by plan, apply, and direct staging; `InstallContentHash` folds manifests, bodies, and generated pointers into only the affected installed-skill identities. reusable
- Authored-path namespace checks use normalized staging-relative paths, so nested source basenames do not falsely collide with flat generated pointers; generated `agent-addon-*.md` files committed under skills are rejected.
- Normal install and reconciliation now preserve the `agent-addons` source tree, including deterministic empty-tree handling, without changing platform-pack overlays, selection, or source-generation behavior.
- The `agent-addon` payload and CLI/desktop wizard create only `agent-addon.yaml` plus `content.md`; dry-run reports both paths and render, validation, or install failure participates in byte-for-byte scaffold rollback.
- Known limit: local install refresh remains deferred while the feature-task runtime workflow store is active; the runtime guard must not be bypassed.
Feature flag: N/A
Acceptance criteria: 7/7 implemented

## [2026-07-14] SKILL-122 agent add-on contract
Areas: orchestration/contracts, runtime-contracts, runtime-domain/agentaddon, runtime-infra-fs/{agentaddon,scaffold,validation}, docs
- Agent add-ons are user-owned `agent-addons/<slug>/agent-addon.yaml` plus `content.md`; the Draft 2020-12 schema pins contract 1.0, stays strict, documents cross-field coherence checks, and is copied onto the runtime classpath with configuration-cache-safe task inputs and an execution-time existence guard. reusable
- `AgentAddonSourceLoader` discovers declarations in deterministic slug order, treats a missing or empty root as valid, and exposes a required lookup that raises `MissingAgentAddonDeclarationError` only when a caller demands an absent declaration. reusable
- Parse and discovery failures surface through typed `InvalidAgentAddonSchemaError` variants, including malformed roots, schema drift, slug/source mismatch, content-file violations, duplicate identities, descriptions, consumers, and agents.
- Agent ids are validated through the existing `InstallAgent` registry; contract 1.0 intentionally accepts only `bill-feature` as a consumer.
- Repository validation and generated-artifact guards include agent add-ons while leaving skills, platform packs, and pack add-ons unchanged when the root is absent.
- Known limit: this contract establishes declaration/discovery/validation only; applying agent add-ons during feature execution is follow-up behavior.
Feature flag: N/A
Acceptance criteria: 6/6 implemented

## [2026-07-12] SKILL-118 unified use license
Areas: LICENSE and policy docs, GitHub release workflow, scripts, runtime-kotlin/{build-logic,runtime-application,runtime-cli,runtime-ports,runtime-infra-fs,runtime-desktop}
- `LicenseRef-Skill-Bill-Use-1.0` governs v0.1.2 prereleases distributed with it, v0.1.2, and later releases: lawful commercial use is free before stable v1.0.0; afterwards personal and qualifying open-source-project use remain free while other commercial use requires a purchased agreement. reusable
- Documented skills, packs, and orchestration materials may be customized for permitted internal use; executable runtime modification and public redistribution remain outside the public grant.
- Release refs are canonical `v`-prefixed SemVer. Stable and post-v1 releases require holder approval tied to the exact normalized governing-license hash; placeholders or alternate successor text do not pass. reusable
- Artifact verification compares staged and packaged license bytes for CLI, MCP, skills, and desktop artifacts; test positive, missing-license, duplicate-path, and byte-drift cases on every host-native extractor. reusable
- Draft releases target the triggering commit, resume only a matching draft, verify existing asset bytes, upload only missing assets, and publish last; release retries must preserve that order. reusable
- Known release gate: Braian Gapur must explicitly approve the final root-LICENSE hashes before `v0.1.2` and stable `v1.0.0`; pending records are intentional and must not be filled by automation.
Feature flag: N/A
Acceptance criteria: 8/8 review findings fixed; original 12/12 implemented

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
- detekt: `InstallPlanPolicy` and `stageInstalledSkill` crossed thresholds (TooManyFunctions / LongParameterList+LongMethod) — a `@Suppress` with one-line rationale was applied for the threshold breach; that records what landed, not a preferred pattern over root-cause fixes.
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
- detekt `TooManyFunctions` trips on `InstallOperations` and `InstallNativeAgentOperations` once the zcode path resolver + link/unlink pair land — a `@Suppress("TooManyFunctions")` with one-line rationale was applied for the threshold breach; that records what landed and does not rule out refactoring or prescribe suppression as the preferred fix.
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
