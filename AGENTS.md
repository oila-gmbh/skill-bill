# AGENTS.md

## Project context

skill-bill is a governed system for authoring, routing, validating, installing, and measuring AI-agent skills. It ships shared orchestration, validators, installers, scaffolding, telemetry, and stable base shells for review, quality checks, feature work, feature verification, and PR descriptions. Platform packs live under `platform-packs/` and are discovered dynamically through their governed manifests.

## Product intent

- `bill-feature-implement` is the flagship bundled workflow. It composes planning, implementation, code review, quality checks, history, PR description, workflow state, telemetry, platform packs, add-ons, and native subagents into a governed spec-to-PR path.
- Other bundled skills are reusable workflow components and standalone phase entry points.
- Bundled skills and reference packs are not sacred. Teams may delete, fork, or replace them and still use Skill Bill as the governed workflow platform.
- The framework contracts are sacred: source shape, generated-output boundaries, manifests, install staging, validator-backed rules, dynamic discovery, and loud-fail behavior.

## Core taxonomy

- `skills/` holds canonical user-facing skill source directories. Each source skill directory may contain only `content.md` and, when needed, `native-agents/`.
- `skills/<platform>/` is reserved for legacy/pre-shell platform-specific overrides when a family has not moved to platform packs yet.
- `platform-packs/<platform>/addons/` holds pack-owned add-ons applied after routing.
- `platform-packs/<platform>/` holds user-owned packs for code review and quality-check behavior.
- `orchestration/` is the shared source of truth for routing, review, delegation, telemetry, and shell contracts.

## Source vs generated artifacts

- Read `docs/skill-source-generation.md` before changing skills, scaffolding, rendering, install staging, native-agent generation, or support pointer behavior.
- `content.md` is the only authored source body for governed skills.
- Generated `SKILL.md` wrappers are runtime/install output. Do not commit them under `skills/` or platform-pack skill directories.
- Generated support pointer files such as `shell-ceremony.md`, `telemetry-contract.md`, `stack-routing.md`, review/delegation pointers, and add-on pointers are install/render output. Do not commit them under `skills/`.
- Installed skills are symlinks to rendered staging directories under the Skill Bill installed-skills cache, not direct symlinks to source skill directories.
- Native-agent source is provider-neutral and lives under `native-agents/agents.yaml` or `native-agents/<name>.md`. Provider-specific `claude-agents/`, `codex-agents/`, `opencode-agents/`, and `junie-agents/` outputs are generated cache artifacts and must not be committed.
- If a skill needs more authored guidance, add H2 sections to `content.md`; do not create extra organization files like `patterns.md`, `reference.md`, or `audit-rubrics.md` under `skills/<skill>/`.
- Re-run `./install.sh` after changing source skills, renderer behavior, or support pointer generation so local agent installs pick up the new staging hash.

## Naming rules

- Base skills: `bill-<capability>`
- Platform overrides: `bill-<platform>-<base-capability>`
- Platform code-review specializations: `bill-<platform>-code-review-<area>`
- Approved areas: `architecture`, `performance`, `platform-correctness`, `security`, `testing`, `api-contracts`, `persistence`, `reliability`, `ui`, `ux-accessibility`

## Governed platform packs

- Packs live under `platform-packs/` and are user-owned.
- Skill Bill is platform-extensible: any team may author and ship a new conforming pack.
- This repo may contain any maintained pack that follows the governed contract; routing and install flows must stay manifest-driven rather than relying on a hardcoded shortlist.
- Each pack ships a manifest. **The canonical source of truth for `platform-packs/<slug>/platform.yaml` shape is `orchestration/contracts/platform-pack-schema.yaml`** — a JSON Schema (Draft 2020-12) authored as YAML. New fields, type changes, value constraints, and field-level enums land in that file first; the runtime parser (`ShellContentLoader.buildPack`) loads the schema at runtime and rejects malformed manifests via `InvalidManifestSchemaError`.
- Cross-field coherence rules that JSON Schema cannot express live in Kotlin and are documented under `x-coherence-checks` in the schema file. The five named rules are `slug-parity`, `areas-require-baseline`, `areas-equal-declared`, `area-metadata-keys-subset-declared`, and `pointers-unique-name-per-dir`.
- The current shell contract version is 1.1. `SHELL_CONTRACT_VERSION` (Kotlin) and the schema's `contract_version.const` are pinned in lockstep by `PlatformPackSchemaContractVersionTest`. Bumping the contract means bumping BOTH; the build breaks if they drift.
- **Runtime contracts directory rule.** Every YAML under `orchestration/contracts/` is part of the runtime contract surface. Adding a new contract YAML requires the same five-part recipe used for the platform-pack schema:
  1. Author the file as a Draft 2020-12 JSON Schema in YAML (mirror the `$schema` / `$id` / `title` / `description` / `additionalProperties: false` / `contract_version` const / `x-coherence-checks` block used by `orchestration/contracts/platform-pack-schema.yaml`).
  2. Pin a Kotlin `<contract>_CONTRACT_VERSION` constant equal to the schema's `properties.contract_version.const`.
  3. Add a parity test that fails the build when the two diverge (`PlatformPackSchemaContractVersionTest` is the canonical pattern; `runtime-kotlin/runtime-core/src/test/kotlin/skillbill/scaffold/PlatformPackSchemaContractVersionTest.kt`).
  4. Ship a typed `Invalid<Contract>SchemaError` extending `ShellContentContractException` and loud-fail at every parse seam (`InvalidManifestSchemaError` and `InvalidWorkflowStateSchemaError` are the canonical pattern).
  5. Bundle the YAML onto the JVM classpath via a configuration-cache-friendly Gradle `Copy` task (the existence guard lives inside `doFirst {}` or an `inputs.file` declaration, not at configuration time).
- The desktop "Contracts" tree auto-lists every `*.yaml` under `orchestration/contracts/` — no desktop code change is required when a new contract ships.
- **Product surface vs. extension surface.** Horizontal `bill-*` skills under `skills/bill-*/` (e.g. `bill-code-review`, `bill-feature-implement`, `bill-quality-check`) are the runtime's own product and are non-removable from the desktop UI and from the `bill-skill-remove` skill. The `kotlin` / `kmp` **horizontal pre-shells** under `skills/kotlin/` and `skills/kmp/` are similarly gated on the HorizontalSkill axis because removing the pre-shell alone would orphan the matching platform pack — `SkillRemovalTarget.isProtectedHorizontalName` and its desktop mirror are the load-bearing predicate. Platform packs under `platform-packs/<slug>/` are the user-authored extension surface and ARE user-removable, including the shipped `kotlin` / `kmp` packs (forks that don't need them can drop them through the desktop tree's PlatformPack node, which cascades the paired pre-shell tree). Only `.bill-shared` stays unconditionally protected on both axes (`isProtectedPlatformName`). Maintainers may still remove deprecated horizontal `bill-*` skills or `kotlin` / `kmp` pre-shells via the CLI's `--allow-shipped` flag in this repo only.
- **Per-repo schema customization.** Forks may add top-level fields to `orchestration/contracts/platform-pack-schema.yaml` without patching the Kotlin runtime. Fields the runtime consumes by name MUST carry `x-runtime-anchored: true`; the bijection test `PlatformPackSchemaAnchoredBijectionTest` asserts schema↔Kotlin parity in both directions, so a typo in an anchored field name still loud-fails with the offending field path through `InvalidManifestSchemaError`. Non-anchored top-level fields flow verbatim into `PlatformManifest.customFields: Map<String, Any?>` so pack authors can ship fork-specific keys without runtime support being added first. `customFields` is intentionally untyped — generating Kotlin types for these fields is out of scope. Nested objects (`routing_signals`, `declared_files`, `area_metadata.<area>`, etc.) remain strict (`additionalProperties: false`); per-repo extensions only relax the top-level mapping.
- **Runtime contract backward compatibility.** Schema introductions and version bumps are loud-fail for legacy durable records by design. Pre-existing rows whose fields no longer match the new per-skill enums (e.g. an obsolete `workflow_status` or a removed `step_id` after a definition change) are intentionally rejected at the read seam (`WorkflowEngine.fullPayload` / `summaryPayload` / `openRecord` / `updateRecord`), surfacing an `InvalidWorkflowStateSchemaError` instead of being silently coerced. The runtime ships no backward-compat shim; operators recover by deleting or migrating the affected workflow rows out-of-band before the next deploy. `WorkflowRecordMapping.toSnapshot` is the seam at which the durable record becomes an in-process snapshot — it deliberately does NOT validate, so a drifted legacy record propagates one step further and loud-fails at the next `WorkflowEngine` read seam after deploy.
- Follow `orchestration/shell-content-contract/PLAYBOOK.md` for governed skill shape.
- Missing manifest, wrong version, missing content file, or missing section must raise the named loud-fail exceptions. Do not add silent fallback.
- Discovery is manifest-driven. The shells, routing playbook, and validator read `routing_signals` from pack manifests instead of hard-coding platform names.
- `kmp` currently routes quality-check work to `kotlin`. `bill-feature-implement` and `bill-feature-verify` remain pre-shell.

## Governed add-ons

- Add-ons are pack-owned files, not standalone skills.
- Keep them flat in the owning pack's `addons/` directory, use lowercase kebab-case names, and resolve them only after dominant-stack routing.
- Runtime skills consume add-ons through generated support pointer files in installed staging, report them as `Selected add-ons: ...`, and every add-on change needs validator plus routing-contract coverage.

## Non-negotiable rules

- Add platform behavior only as manifest-declared overrides or approved code-review areas.
- Add a new pack only when behavior materially differs from an existing pack.
- Keep add-ons pack-owned, use generated support pointers for shared contracts, and keep `orchestration/` aligned with those generated links.
- Route by dominant stack first, then apply governed add-ons.
- Keep `SHELL_CONTRACT_VERSION` in lockstep across shell and packs, and treat the loud-fail loader as authoritative.
- Keep `README.md` catalog data accurate and keep discovery/install flows dynamic when packs are added or removed.
- Keep the source shape under `skills/` strict: only `content.md` plus optional `native-agents/`.

## Adding a new platform

1. For code review, create the new pack root, add a conforming manifest and `content.md` files, register any generated pointers through the manifest, update the README catalog, extend pack tests, and run validation.
2. For quality-check, register the manifest entry and ship the governed `content.md`. `kmp` still falls back to `kotlin`.
3. For pre-shell families (`feature-implement`, `feature-verify`), keep using the historic `skills/<platform>/` layout until those families are piloted.

## New-skill authoring

- Use the scaffolder for all new skills. Preferred entrypoints are `skill-bill new --interactive`, `skill-bill new --payload <file>`, or `/bill-create-skill`. `skill-bill new-skill` remains the lower-level command name behind `new`.
- For ordinary skill authoring and refinement, use the `skill-bill` CLI instead of manually editing governed skill files.
- Preferred loop: `skill-bill new --interactive` (or `create-and-fill` for one content-managed skill), `skill-bill show <skill-name>`, `skill-bill edit <skill-name>` or `fill <skill-name>`, `skill-bill validate --skill-name <skill-name>`, then `skill-bill render --skill-name <skill-name>` when wrapper templates change.
- `skill-bill fill <skill-name>` is the scripted/agent-safe write path for authored content. Feed it `--body` or `--body-file`; combine it with `--section <heading>` when only one authored H2 section should change.
- `skill-bill edit <skill-name> --section <heading>` is the targeted interactive path when only one authored section needs refinement.
- `skill-bill show <skill-name>` and `skill-bill explain [<skill-name>]` are the preferred stable read paths for agents; do not grep or hand-parse governed wrappers unless you are changing the scaffold system itself.
- Treat `content.md` as the primary authored surface for governed skills. Do not manually edit generated `SKILL.md` files during normal authoring.
- Direct `SKILL.md` edits are maintainer-only work and are allowed only when intentionally changing the shared wrapper, scaffold, render, or migration system itself.
- Governed generated skill artifacts are render/install output, not source artifacts. Do not commit generated governed `SKILL.md` wrappers, static support pointer files, platform.yaml-declared pointer files, or provider-specific native-agent outputs. Keep only authored `content.md`, `native-agents/` source, manifests, add-ons, renderer code, validators, and install-staging behavior in git.
- The default intake is contextual, not a top-level taxonomy dump. Start with `Platform name:` and branch from there.
- New platform packs always scaffold the pack root plus baseline `bill-<platform>-code-review` and `bill-<platform>-quality-check`. The interactive flow then lets the author choose `none`, `custom subset`, or `all` for approved code-review specialist stubs. It does not auto-create `feature-implement` or `feature-verify` overrides.
- `create-and-fill` is only for one content-managed skill at a time. Do not use it for horizontal skills, pre-shell overrides, or platform-pack bootstrap flows.
- Supported `kind` values:
  - `horizontal`: create a canonical skill under `skills/`.
  - `platform-pack`: create a new `platform-packs/<slug>/` root with a rendered manifest, baseline `code-review`, default `quality-check`, and optional approved specialist stubs.
  - `platform-override-piloted`: create the skill in the selected pack, updating its manifest; for `quality-check`, register `declared_quality_check_file`.
  - `platform-override-piloted` for pre-shell families: create the skill in the platform's legacy `skills/` location and note that it will move when piloted.
  - `code-review-area`: create the specialist in the selected pack and register the area.
  - `add-on`: create a flat add-on file in the selected platform pack's `addons/` directory.
- For `platform-pack`, payloads may either use `skeleton_mode=starter|full` or provide `specialist_areas=[...]` for a custom approved subset, but not both.
- Pre-shell families are defined in the Kotlin scaffold/runtime contract. Keep payload schema and exception catalog aligned with `orchestration/shell-content-contract/SCAFFOLD_PAYLOAD.md`.
- Entry point: the Kotlin `skill-bill new` / `create-and-fill` commands. Payload schema and exception catalog live in `orchestration/shell-content-contract/SCAFFOLD_PAYLOAD.md`.
- The scaffolder is atomic. Validator, manifest-write, install, or generated-link failures must roll the repo back byte-for-byte.
- Keep it byte-identical across governed skills on the same shell contract.

## Quality-check guidance

Prefer routing through `bill-quality-check`. If a platform-specific checker does not exist yet, document the fallback explicitly. In the built-in set, `kmp` still falls back to `kotlin`.

## Preferred design bias

- stable base commands
- platform depth behind the router
- explicit overrides
- validator-backed rules
- tests for acceptance and rejection paths

## Validation commands

```bash
skill-bill validate
(cd runtime-kotlin && ./gradlew check)
npx --yes agnix --strict .
scripts/validate_agent_configs
```
