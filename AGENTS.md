# AGENTS.md

## Project Context

skill-bill governs authoring, routing, validation, installation, and measurement of agent skills. It ships shared orchestration, tooling, telemetry, workflow state, and shells for review, quality checks, feature work, verification, and PRs.

Non-negotiable contracts:

- Authored source is `content.md` (plus governed sidecars); generated `SKILL.md` is install output. Listed-skill staging: `SKILL.md`, `.content-hash`, pointers, optional `native-agents/` — no `content.md` copy.
- Skill sources: `skills/<skill>/content.md`, optional `native-agents/`, contract-authorized sidecars.
- Platform behavior lives in manifest-declared packs under `platform-packs/<slug>/`.
- `orchestration/` owns shared routing, review, delegation, telemetry, workflow, and shell contracts.
- `agent-addons/<slug>/` contains only user-owned `agent-addon.yaml` and `content.md`.
- Generated support pointers, provider-specific native-agent outputs, and install staging artifacts are not committed.
- Discovery, install, routing, and validation stay dynamic and manifest-driven.
- Missing manifests, wrong contract versions, missing content, and missing required sections fail loudly with typed errors.
- Every fallback, degradation, or swallowed failure emits a record; see `docs/observability-policy.md`.

## Product Intent

`bill-feature` presents one confirmation gate, then delegates to the foreground runtime driver with durable state, telemetry, packs, add-ons, and native subagents.

Bundled skills and packs are defaults, not the framework boundary. Teams may replace them while retaining governed source shape, generated-output boundaries, manifests, install staging, validators, dynamic discovery, and loud-fail.

## Taxonomy

- `skills/` — canonical user-facing skill sources
- `platform-packs/<platform>/` — pack roots for review and quality-check only; `addons/` flat pack-owned add-ons. Packs are excluded from goal-planning discovery; eligible `agent/history.md` and `agent/decisions.md` reach planning as a heading catalog only — bodies arrive for headings preplanning selected.
- `orchestration/contracts/` — runtime contract schemas

Naming: `bill-<capability>`; overrides `bill-<platform>-<base-capability>`; review areas `bill-<platform>-code-review-<area>`. Approved areas: `architecture`, `performance`, `platform-correctness`, `security`, `testing`, `api-contracts`, `persistence`, `reliability`, `ui`, `ux-accessibility`.

## Source And Generated Files

Read `docs/skill-source-generation.md` before changing skills, scaffolding, rendering, install staging, native-agent generation, or support pointers.

Forbidden in source: governed `SKILL.md` wrappers; generated support pointers (`shell-ceremony.md`, `telemetry-contract.md`, `stack-routing.md`, review/delegation/add-on pointers); provider-specific `*-agents/` outputs (`claude-agents/`, `codex-agents/`, `junie-agents/`, `cursor-agents/`).

Native-agent source is provider-neutral under `native-agents/agents.yaml` or `native-agents/<name>.md` with `contract_version` on new/rendered sources (older sources still parse for fixture migration). Extra authored guidance goes in `content.md` H2 sections — not sibling org files like `patterns.md` under `skills/<skill>/`.

Run `./install.sh` after changing source skills, renderer behavior, or support pointer generation.

## Platform Packs

Packs are the extension surface; routing and install read manifests, not hard-coded platform lists. Canonical shape: `orchestration/contracts/platform-pack-schema.yaml`. Schema changes land there first; `ShellContentLoader.buildPack` rejects malformed manifests via `InvalidManifestSchemaError`. Cross-field rules JSON Schema cannot express live in Kotlin under `x-coherence-checks`.

Per-repo customization: top-level custom fields allowed; runtime-consumed fields use `x-runtime-anchored: true`; non-anchored fields flow to `PlatformManifest.customFields`. Product vs extension: horizontal `skills/bill-*/` and `.bill-shared` are protected; `platform-packs/<slug>/` (including shipped `kotlin`/`kmp`) are removable — no paired `skills/<platform>/` trees.

`kmp` covers Android and Kotlin Multiplatform on the Kotlin baseline and routes quality checks to `bill-kmp-code-check` (no Kotlin fallback). `bill-feature-verify` remains pre-shell.

## Runtime Contract Schemas

Every YAML under `orchestration/contracts/` is a runtime contract. New contracts: Draft 2020-12 schema in YAML → Kotlin `*_CONTRACT_VERSION` → parity test → typed `Invalid<Contract>SchemaError` → loud-fail at every parse seam. Detail: `runtime-kotlin/ARCHITECTURE.md`.

Schema bumps loud-fail legacy records; runtime quarantines and regenerates in-band. Producer-side gate: feature-task phases owning a bounded planning projection (`preplan`, `plan`, `implement`) re-enter their own fix loop when completed output fails the projection contract.

## Add-ons

Pack-owned files (not skills): flat under `platform-packs/<slug>/addons/`, lowercase kebab-case, resolved only after dominant-stack routing. Declare consumers in the pack manifest (`addon_usage` / `feature_addon_usage`); do not hand-author selection tables in `content.md`. Changes need validator and routing-contract coverage.

## Internal Skills

`internal-for: <parent>` in `content.md` frontmatter installs as `<skill-name>.md` sidecar in the parent directory (not listed); parent reads the sibling in-session. Contract: `docs/skill-source-generation.md`.

## Skill Authoring

Scaffold with `skill-bill new` (or `--payload <file>`). Author via `skill-bill show`, `fill`, `edit`, `validate`, and `render`. `create-and-fill` is one content-managed skill at a time. Kinds: `horizontal`, `platform-pack`, `add-on`. Align payloads with `orchestration/shell-content-contract/SCAFFOLD_PAYLOAD.md`. Scaffolding is atomic (validator/manifest/install/link failures roll back).

## Adding Platforms

Code review: pack root + conforming manifest/`content.md`, manifest-registered pointers, README catalog, pack tests, validate. Quality-check: manifest entry + governed `content.md`; every pack routes to its own checker. Feature-task/verify: stay on horizontal + manifest surfaces — no legacy `skills/<platform>/` overrides.

## Runtime Agent Behavior

Agent-specific behavior uses injectable strategies on `AgentRunProcessRequest`, not identity branching in the process runner: `progressProbe`, `declaredProgressProbe`, `activityProbe`, `progressEmitter`, `idlePolicy` (`HEARTBEAT_EXTENDED` | `DB_PROGRESS_ONLY`). `ProcessWaitLoop` calls strategies only; new agents add a strategy constant. Crash reconciliation: `FeatureTaskRuntimeWorkerSupervisor` self-heals expired-lease rows to resumable at startup.

When goal routing selects the build quality gate and the dominant platform pack declares `validation_gate.build_command`, build is one agent session that runs only that pack's `build_command` (Kotlin: `./gradlew compileKotlin`), reads that output, fixes every finding in that session, then runs `cache_bypassing_build_command` once to confirm. Build is compile/buildability proof only: no suite tests, no full check, no substitute agent-run gate. Do not run `collect_all_full_gate_command`, `./gradlew check`, `check --continue`, `skill-bill validate`, `bill-code-check`, or any other repo-root checklist. The runtime does not start another agent for repair turns. It may still run one cache-bypassing verify after the agent signals complete; remaining findings persist `findings_open` and block, and an operator resume starts one new build session. Do not rerun the pack build command after each individual finding, and do not launch delegated subagents. Targeted compile tasks are allowed while repairing when they are part of that same pack gate. Default standalone runs skip build (`review -> validate`); only goal children stamped for build use `review -> build -> write_history`.

## Commit Structure (feature-task / goal subtasks)

Decomposed goal runs use `same_branch_commit_per_subtask`: each completed subtask leaves exactly one commit on the feature branch, not a chain of checkpoint commits in branch history.

- The runtime owns finalisation: it stages every dirty non-ignored worktree path except `.feature-specs/`, amends (or creates) the subtask commit, captures the post-amend sha, pushes, and records `commit_sha` into the decomposition manifest. The agent supplies the outcome message in `commit_push_result` (`changed_paths` is advisory); it does not run `git commit` or `git push` for a subtask.
- Checkpoint history lives under `refs/skill-bill/checkpoints/<issue-key>/<subtask-id>/<sequence>`. Those refs preserve pre-amend commits the branch no longer names; they are not reachable through `git log` on the branch without an explicit ref argument.
- Pruning deletes a subtask's checkpoint refs only after that subtask's commit is pushed and its manifest entry records a non-blank `commit_sha`. Pruning is idempotent; a hard manifest reset prunes the refs of the subtasks it reset. Blocked or abandoned subtasks keep their refs for recovery.

## Writing And Comments

Write direct, active prose; drop filler, stale phrases, praise, and repetition, but preserve names, numbers, and qualifications. Commits/PRs/docs: lead with the outcome, state what changed and why, and avoid unsupported terms such as "successfully", "perfect", "comprehensive", and "robust". Prefer clear names and small functions over comments; comment only a non-obvious *why* code cannot express — never explain *what* code does.

## Testing

Write few, high-value tests; name the realistic bug each would catch before authoring. Assert observable boundaries, not implementation structure. `bill-unit-test-value-check` is the review gate.

## Comments

NO COMMENTS, DON'T WRITE ANY NEW COMMENTS. NONE!!! IF YOU SEE A COMMENT - REMOVE IT!!!

## Coding Conventions

Follow `docs/code-principles.md` for Kotlin patterns, package clustering, imports, file-size limits, and architecture guards. Mechanical enforcement lives under `runtime-kotlin/runtime-core/src/test/kotlin/skillbill/architecture/` (`InlineFqnArchitectureTest`, `ProductionFileLineCeilingArchitectureTest`, `PrincipleEnforcementInventory`, and siblings).

## Quality Checks

Prefer `bill-code-check`; document fallback if no platform checker. Bias: stable base commands, platform depth behind routers, explicit overrides, validator-backed rules, acceptance and rejection tests.
