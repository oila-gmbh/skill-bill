# AGENTS.md

## Project Context

skill-bill governs authoring, routing, validation, installation, and measurement of agent skills. It ships shared orchestration, tooling, telemetry, workflow state, and shells for review, quality checks, feature work, verification, and PR descriptions.

Non-negotiable contracts:

- Authored governed skill source is `content.md`, except for documented governed sidecar contracts; generated `SKILL.md` wrappers are runtime/install output.
- Skill sources use `skills/<skill>/content.md`, optional `native-agents/`, and contract-authorized sidecars.
- Platform behavior lives in manifest-declared platform packs under `platform-packs/<slug>/`.
- `orchestration/` owns shared routing, review, delegation, telemetry, workflow, and shell contracts.
- `agent-addons/<slug>/` contains only user-owned `agent-addon.yaml` and `content.md`.
- Generated support pointer files, provider-specific native-agent outputs, and installed staging artifacts are not committed.
- Discovery, install, routing, validation, and desktop surfaces stay dynamic and manifest-driven.
- Missing manifests, wrong contract versions, missing content files, and missing required sections fail loudly with typed errors.

## Product Intent

`bill-feature-task` is the feature-task router: it accepts `mode:runtime` (default) or `mode:prose`, presents one confirmation gate, then delegates. `bill-feature-task-prose` runs the full phase loop in-session. `bill-feature-task-runtime` launches the foreground `skill-bill feature-task` driver with durable workflow state, telemetry, platform packs, add-ons, and native subagents.

Bundled skills and packs are defaults, not the framework boundary. Teams may replace them while retaining governed source shape, generated-output boundaries, manifests, install staging, validators, dynamic discovery, and loud-fail behavior.

## Taxonomy

- `skills/`: canonical user-facing skill sources.
- `platform-packs/<platform>/addons/`: flat pack-owned add-ons applied after routing.
- `platform-packs/<platform>/`: user-owned pack roots for code review and quality-check behavior.
- `platform-packs/<platform>/agent/`: pack-owned boundary memory; `history.md` and `decisions.md` are tracked in the repo but excluded from installs and generated runtime output.
- `orchestration/contracts/`: runtime contract schemas.

Naming:

- Base skills: `bill-<capability>`
- Platform overrides: `bill-<platform>-<base-capability>`
- Platform review areas: `bill-<platform>-code-review-<area>`
- Approved areas: `architecture`, `performance`, `platform-correctness`, `security`, `testing`, `api-contracts`, `persistence`, `reliability`, `ui`, `ux-accessibility`

## Source And Generated Files

Read `docs/skill-source-generation.md` before changing skills, scaffolding, rendering, install staging, native-agent generation, or support pointer behavior.

Generated files forbidden in source:

- governed `SKILL.md` wrappers under `skills/` or platform-pack skill directories
- generated support pointers such as `shell-ceremony.md`, `telemetry-contract.md`, `stack-routing.md`, review/delegation pointers, and add-on pointers
- provider-specific `claude-agents/`, `codex-agents/`, `opencode-agents/`, and `junie-agents/` outputs

Native-agent source is provider-neutral and lives under `native-agents/agents.yaml` or `native-agents/<name>.md`. New and rendered sources include `contract_version`; the parser still accepts older sources for gradual fixture migration.

If a skill needs more authored guidance, add H2 sections to `content.md`. Do not add extra organization files such as `patterns.md`, `reference.md`, or `audit-rubrics.md` under `skills/<skill>/`.

Run `./install.sh` after changing source skills, renderer behavior, or support pointer generation so local agent installs use the new staging hash.

## Platform Packs

Platform packs are the extension surface. Any maintained pack in this repo is valid when it follows the governed contract. Routing and install flows read pack manifests rather than hard-coded platform lists.

The canonical shape for `platform-packs/<slug>/platform.yaml` is `orchestration/contracts/platform-pack-schema.yaml` (Draft 2020-12 YAML-authored JSON Schema). Field additions, type changes, constraints, and enums land there first. `ShellContentLoader.buildPack` rejects malformed manifests through `InvalidManifestSchemaError`.

The shell contract version is `1.2`. `SHELL_CONTRACT_VERSION` and the schema `contract_version.const` are pinned by `PlatformPackSchemaContractVersionTest`.

Cross-field rules JSON Schema cannot express live in Kotlin and are documented under `x-coherence-checks`, including slug parity, declared-area parity, pointer uniqueness, baseline composition, and governed add-on usage.

Per-repo customization:

- top-level custom fields are allowed in `platform-pack-schema.yaml`
- runtime-consumed top-level fields carry `x-runtime-anchored: true`
- `PlatformPackSchemaAnchoredBijectionTest` enforces schema-to-Kotlin parity
- non-anchored top-level fields flow into `PlatformManifest.customFields`
- nested objects remain strict with `additionalProperties: false`

Product versus extension surface:

- horizontal `bill-*` skills under `skills/bill-*/` are protected product surfaces
- shipped `kotlin` and `kmp` packs are removable; no paired `skills/<platform>/` pre-shell trees exist
- platform packs under `platform-packs/<slug>/` are user-removable extension surfaces, including shipped `kotlin` and `kmp`
- `.bill-shared` is protected on every axis
- maintainers may remove deprecated shipped surfaces only through the CLI `--allow-shipped` path in this repo

`kmp` adds platform-correctness, UI, and UX/accessibility to Kotlin's seven review areas. Quality checks route to `bill-kmp-code-check` without Kotlin fallback. `bill-feature-verify` remains pre-shell.

## Runtime Contract Schemas

Every YAML under `orchestration/contracts/` is a runtime contract. New contract YAML follows this recipe:

1. Author Draft 2020-12 JSON Schema in YAML, mirroring `$schema`, `$id`, `title`, `description`, strict `additionalProperties` where applicable, `contract_version` const, and `x-coherence-checks` where applicable.
2. Add a Kotlin `<contract>_CONTRACT_VERSION` constant equal to the schema const.
3. Add a parity test using `PlatformPackSchemaContractVersionTest` as the pattern.
4. Add a typed `Invalid<Contract>SchemaError` extending `ShellContentContractException`.
5. Loud-fail at every parse seam.
6. Bundle the YAML onto the JVM classpath with a configuration-cache-friendly Gradle `Copy` task using `inputs.file` and an execution-time `doFirst {}` existence guard.

Runtime contract schemas are internal implementation details and stay out of the desktop user-facing tree.

Schema introductions and version bumps intentionally loud-fail legacy durable records. Operators recover by deleting or migrating affected workflow rows out of band. `WorkflowRecordMapping.toSnapshot` does not validate; the next `WorkflowEngine` read seam rejects drift through `InvalidWorkflowStateSchemaError`.

## Add-ons

Add-ons are pack-owned files, not standalone skills. Keep them flat in `platform-packs/<slug>/addons/`, use lowercase kebab-case names, and resolve them only after dominant-stack routing.

Declare review/check add-on consumers in the owning pack manifest under `addon_usage`. Declare feature-task add-on consumers under manifest-owned `feature_addon_usage`. Do not hand-author per-skill add-on selection tables in `content.md`; renderers emit governed add-on usage from manifest-owned declarations. Add-on changes need validator and routing-contract coverage.

## Internal Skills

A base or platform-pack skill declaring `internal-for: <parent-skill-name>` in `content.md` frontmatter installs as a `<skill-name>.md` sidecar inside the parent's installed directory instead of being listed; the parent invokes it by reading that sibling file in-session. Full contract and worked examples: `docs/skill-source-generation.md`.

## Skill Authoring

Use the scaffolder for new skills:

- `skill-bill new`
- `skill-bill new --payload <file>` for scripted automation

For normal authoring use CLI reads and writes:

- inspect: `skill-bill show <skill-name>` or `skill-bill explain [<skill-name>]`
- write: `skill-bill fill <skill-name> --body-file <file>` or `--section <heading>`
- edit interactively: `skill-bill edit <skill-name> --section <heading>`
- validate: `skill-bill validate --skill-name <skill-name>`
- render preview: `skill-bill render --skill-name <skill-name>`

`create-and-fill` is for one content-managed skill at a time. It is not for horizontal skills, pre-shell overrides, or platform-pack bootstrap flows.

Supported scaffold `kind` values:

- `horizontal`: canonical skill under `skills/`
- `platform-pack`: pack root plus baseline code-review, default quality-check, and approved specialists
- `add-on`: flat add-on file in the selected pack

Keep payload schema and exception catalog aligned with `orchestration/shell-content-contract/SCAFFOLD_PAYLOAD.md`.

The scaffolder is atomic: validator, manifest-write, install, or generated-link failures roll the repo back byte-for-byte.

## Adding Platforms

For code review, create the pack root, add a conforming manifest and `content.md` files, register generated pointers through the manifest, update the README catalog, extend pack tests, and run validation.

For quality-check, register the manifest entry and ship the governed `content.md`. Every maintained pack, including `kmp`, routes directly to its own manifest-declared checker.

For feature-task and feature-verify, keep platform-specific behavior on current horizontal and manifest-driven surfaces; do not add legacy `skills/<platform>/` overrides.

## Runtime Agent Behavior

Agent-specific runtime behavior is expressed through injectable strategies, not conditional branching inside the process runner.

`AgentRunProcessRequest` carries named strategy objects that the process runner calls without knowing which agent is active:

- `progressProbe` — how to read workflow DB token changes
- `declaredProgressProbe` — how to read declared progress events
- `activityProbe` — how to detect file-system activity
- `progressEmitter` — how to write lifecycle events
- `idlePolicy` — whether a confirmed-alive process heartbeat extends the idle window (`HEARTBEAT_EXTENDED`) or only DB token changes count (`DB_PROGRESS_ONLY`)
- `usePtyStdio` — whether to use a PTY pair instead of separate stdout/stderr streams

Each `AgentRunCommandBuilder` sets the right combination for its agent. `ProcessWaitLoop` calls the strategies without branching on agent identity. When a new agent needs different behavior, add a named strategy constant and set it in its command builder — do not add if/else inside the runner.

## Writing Policy

Write clear, direct prose for the intended reader. Prefer short, familiar words and active voice when they preserve meaning. Remove filler, stale phrases, vague praise, and repeated claims. Keep technical terms when simpler wording would lose precision. State concrete facts, outcomes, failures, and next actions. Preserve all names, numbers, and material qualifications. Break any rule when following it would make the text less accurate or less natural.

For commit messages, PR descriptions, documentation, and progress reports, state what changed and why in plain words. Avoid achievement language such as "successfully," "perfect," "comprehensive," and "robust" unless it conveys a specific, supported fact. Lead with the outcome and include detail only when it helps the reader decide or act.

## Comments Policy

Comments, especially inline ones, are a code smell. Prefer clearer naming, smaller functions, or refactoring. Add a comment only when a non-obvious *why* cannot be encoded in the code, such as an external constraint, subtle invariant, or known-bug workaround. Never comment merely to explain *what* the code does.

## Quality Checks

Prefer routing through `bill-code-check`. If no platform checker exists, document the fallback explicitly.

Design bias:

- stable base commands
- platform depth behind routers
- explicit overrides
- validator-backed rules
- acceptance and rejection tests

## Validation Commands

```bash
skill-bill validate
(cd runtime-kotlin && ./gradlew check)
npx --yes agnix --strict .
scripts/validate_agent_configs
```
