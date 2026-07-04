# Skill Source And Generation Model

This document explains the governed source model for Skill Bill skills and the
generated runtime artifacts produced by render, scaffold, install, and native
agent flows.

Use it when changing anything under `skills/`, `platform-packs/`,
`orchestration/`, `runtime-kotlin/.../scaffold`, `runtime-kotlin/.../install`,
or native-agent rendering.

## Core Principle

`content.md` is the authored source. `SKILL.md` and support pointer files are
generated runtime artifacts.

The repository should be easy to review and hard to drift:

- authors edit concise governed source files
- render and install generate runtime-complete skill directories
- validators reject generated artifacts committed back into source
- installed agents see complete `SKILL.md` wrappers and support files without
  source directories carrying generated files

This is also a product UX rule, not just repository hygiene. Authors should see
and edit only the material they own. Wrapper ceremony, runtime wiring, pointer
files, and provider-specific agent artifacts are the system's responsibility.

## Source Layout

Canonical skills under `skills/` are source-only directories.

Allowed files under each `skills/<skill>/` directory:

- `content.md`
- `native-agents/` when the skill owns provider-neutral native-agent sources

Allowed files under `skills/<skill>/native-agents/`:

- `agents.yaml` for a bundled list of native agents
- `<agent-name>.md` for one standalone provider-neutral native-agent source

Do not commit these under `skills/`:

- `SKILL.md`
- `shell-ceremony.md`
- `telemetry-contract.md`
- stack-routing, review, delegation, add-on, or Android support pointer files
- provider-specific generated native-agent directories such as
  `claude-agents/`, `codex-agents/`, `opencode-agents/`, or `junie-agents/`
- extra organization files such as `patterns.md`, `reference.md`, or
  `audit-rubrics.md`

If authored guidance needs more sections, put them in `content.md` as normal
Markdown H2 sections.

Platform packs use their own source layout:

- `platform-packs/<slug>/platform.yaml`
- `platform-packs/<slug>/code-review/<skill>/content.md`
- `platform-packs/<slug>/quality-check/<skill>/content.md` when declared
- `platform-packs/<slug>/addons/*.md` for pack-owned add-ons

Platform pack pointer files declared by `platform.yaml` are generated output,
not source files.

Pack-owned add-ons are consumed through `platform.yaml` `addon_usage`, not
hand-authored skill prose. The manifest maps a skill-relative directory to the
add-on entry pointer and optional companion pointers the installed skill may
open after stack routing. The renderer turns that contract into a generated
`## Governed Add-Ons` wrapper section, and the loader rejects add-on usage that
does not reference generated pointers targeting the same pack's `addons/`
directory.

## `content.md`

`content.md` is the only authored skill body for governed skills. It owns:

- YAML frontmatter with `name` and `description`
- task-specific execution guidance
- workflow phase instructions and structured result contracts
- inline reference material that belongs to the skill
- links or cues for generated support pointers that will exist in installed
  staging

The frontmatter also accepts one optional classification key,
`internal-for: <parent-skill-name>`. Presence classifies the skill as
**internal**: its governed content installs as a `<skill-name>.md` sidecar
inside the parent skill's installed directory instead of as a listed
`SKILL.md` entry, and it is never listed in any agent's `skills_dir`. The
parent invokes an internal skill by reading the sidecar file (a sibling read)
and executing it in-session — the Skill tool cannot resolve unlisted skills.
The authoring/install pipeline loud-fails on an internal skill whose parent is
missing, unknown, itself internal, or self. See `AGENTS.md` → Internal Skills
for the full contract and the feature-execution family worked example.

`content.md` must not contain generated wrapper headings:

- `## Descriptor`
- `## Execution`
- `## Ceremony`

Those headings belong to generated `SKILL.md` wrappers.

`content.md` should also avoid copying shared shell ceremony, telemetry, stack
routing, review orchestration, and delegation contracts inline. Those shared
contracts live under `orchestration/` and are exposed to installed skills via
generated support pointers.

Preferred authoring paths:

- `skill-bill show <skill>`
- `skill-bill explain [skill]`
- `skill-bill fill <skill> --body-file <file>`
- `skill-bill fill <skill> --section <heading> --body-file <file>`
- `skill-bill validate`
- `skill-bill render <skill>` for read-only render checks

Manual edits to `content.md` are acceptable for maintainer work, but ordinary
skill authoring should use the CLI so validation and rollback behavior stay in
the loop.

## Generated `SKILL.md`

`SKILL.md` is runtime-facing output. It is rendered from:

- frontmatter in `content.md`
- generated descriptor metadata
- the authored body rendered into `## Execution`
- generated ceremony text in `## Ceremony`

The renderer is implemented in `runtime-kotlin/runtime-infra-fs` around
`AuthoringRender.kt` and supporting scaffold renderers.

Important rules:

- Do not commit generated `SKILL.md` under `skills/`.
- Do not edit generated `SKILL.md` during normal skill authoring.
- Change renderer code and tests when wrapper shape changes.
- Use `skill-bill render` to inspect generated output.
- Install staging writes `SKILL.md` into the installed cache.

## Generated Support Pointers

Installed skills need local filenames such as `shell-ceremony.md`,
`telemetry-contract.md`, `stack-routing.md`, `review-orchestrator.md`, and
pack add-on pointers. These files are generated into installed staging rather
than committed under `skills/`.

There are two pointer families:

- Static runtime support pointers for canonical `skills/` entries. Their
  targets are defined by `requiredSupportingFilesForSkill` and
  `supportingFileTargets` in `ScaffoldSupport.kt`.
- Platform pack manifest pointers declared in each pack's `platform.yaml`.

Install staging materializes support pointer files as normalized relative
pointer text. Targets must be regular files inside the repo and must not point
back to themselves. Missing target files fail install/render validation.

If a skill needs a new support file:

1. Put the canonical source under `orchestration/` or the owning
   `platform-packs/<slug>/addons/` directory.
2. Register the support pointer target or manifest pointer.
3. Add validator and install-staging coverage.
4. Do not add the pointer file under `skills/<skill>/`.

If a platform-pack skill needs governed add-ons, declare their generated
pointer filenames in the pack's `addon_usage` block. Keep add-on selection
cues and topic indexes in the pack-owned add-on files, not duplicated in
`content.md`.

## Install Staging

Content-managed skills install through staging.

Install flow:

1. Resolve the source skill directory.
2. Render `SKILL.md`.
3. Copy authored source files such as `content.md` and `native-agents/`.
4. Generate platform pointers and static support pointers.
5. Compute a content hash from authored files and pointer target declarations.
6. Promote an atomic staging directory under the Skill Bill installed-skills
   cache.
7. Link each selected agent's skill entry to the staged directory.

The installed directory is runtime-complete and may contain:

- `SKILL.md`
- `content.md`
- `.content-hash`
- generated support pointer files
- generated platform pointer files
- copied `native-agents/` source files
- internal-skill sidecars (`<skill-name>.md`) for any internal skills that
  declare this skill as their parent via `internal-for`; these are rendered
  into the parent's staged directory and folded into the parent's content
  hash, and the internal skills themselves get no `skills_dir` entry

Agent install directories therefore point to staging, not directly to the repo
source skill directory.

Re-run `./install.sh` after source, renderer, or support pointer changes so
installed agents receive a fresh staging hash.

The terminal installer and desktop first-run wizard both use this same
runtime-owned install plan/apply path. Their choices may change which agents,
platform packs, telemetry level, or MCP registrations are applied, but they do
not change the governed source shape. Base skills remain part of every install;
optional platform packs are selected from manifests; generated wrappers,
support pointers, provider-native agent files, and packaged desktop outputs
remain install/render artifacts instead of authored source.

## Native-Agent Generation

Provider-neutral native-agent sources live under `native-agents/` in source
skill directories or platform pack skill directories.

Supported source forms:

- `native-agents/<agent-name>.md`
- `native-agents/agents.yaml`

Native-agent sources can define their own body or use:

```yaml
compose: governed-content
```

`compose: governed-content` means the provider-specific native-agent artifact
is composed from the matching governed `content.md`, producing a self-contained
provider artifact at install time. The generated artifact must not depend on
repo-local `content.md` at runtime.

Newly scaffolded and rendered provider-neutral sources include a top-level
`contract_version: "0.1"` pin. The native-agent schema keeps that key optional
for legacy parsing tolerance, but when it is present the value is pinned to
`NATIVE_AGENT_COMPOSITION_CONTRACT_VERSION` by schema validation and parity
tests.

Install renders provider-specific artifacts into the Skill Bill native-agents
cache, then links them into runtime-specific directories:

- Claude: Markdown/YAML custom agents under `~/.claude/agents/`
- Codex: TOML custom agents under `~/.codex/agents/`
- OpenCode: Markdown custom agents under `~/.config/opencode/agents/`
- Junie: Markdown/YAML custom agents under `~/.junie/agents/`

`~/.agents/agents/` exists only as a Skill Bill compatibility path for Codex
homes that do not have a `.codex` root. It is not the primary documented Codex
custom-agent location.

Generated provider artifacts are never committed to the repo.

## Scaffolding

The scaffolder is the supported way to create new governed source.

Entrypoints:

- `skill-bill new`
- `skill-bill new --interactive`
- `skill-bill new --payload <file>` for scripted automation

Supported scaffold kinds:

- `horizontal`: creates `skills/<name>/content.md`
- `platform-pack`: creates `platform-packs/<slug>/platform.yaml`, baseline
  code-review content, quality-check content, and specialist content stubs for
  every approved code-review area. Payloads may also declare `baseline_layers`;
  the scaffolder validates those references before mutation and writes them as
  `code_review_composition.baseline_layers` in the new manifest.
- `add-on`: creates one pack-owned skeleton add-on under
  `platform-packs/<slug>/addons/`

Retired partial scaffold kinds are rejected for new creation:
`platform-override-piloted`, `platform-override`, `override`,
`code-review-area`, `area`, and `specialist`. The error recommends creating a
full `platform-pack`, or editing/removing existing pack content through normal
authoring and removal flows. Existing platform override and code-review area
source files remain discoverable, renderable, installable, validatable, and
removable.

Scaffolding writes source files only. It does not stage generated `SKILL.md`
wrappers or support pointer files into source.

Use `skill-bill new --dry-run` for the guided path, or
`skill-bill new --payload <file> --dry-run` for scripted payloads, to inspect
planned files and manifest edits before mutation. For platform-pack composition
payloads, dry-run output includes a preview of the generated `platform.yaml` block. After
creation, `skill-bill show <baseline-skill>` surfaces any manifest-declared
review composition so users and agents can understand which baseline review
layers run. New platform-pack scaffolds are full packs; remove unwanted focus
areas afterward through governed removal paths. Legacy platform-pack payloads
that omit `baseline_layers` remain valid and generate no
`code_review_composition` section.

For add-ons, the normal wizard creates a skeleton markdown file and registers
the generated pointer through the owning pack's `platform.yaml` `addon_usage`.
When `addon_location_path` is provided, the wizard instead creates the skeleton
in that external add-on source directory and creates or updates the source's
`addon-manifest.yaml`; the owning pack still provides the validated/defaulted
consumer skill directories. It does not ask for body text or raw consumer
directories. The authoring sequence is: create the skeleton, edit the generated
add-on markdown file, then validate/render/install through the normal repo
checks. The desktop wizard registers external add-on sources in
`external_addon_sources` after successful creation so the app tree and install
overlay can discover them; scripted callers that invoke the raw scaffold payload
directly still need to register the source themselves. Scripted payloads may
still provide `body` or `consumer_skill_dirs` for deterministic automation.
Omitted consumers default to the owning pack
baseline when one exists, otherwise to the pack's only manifest-declared skill
directory; packs with no unambiguous default require scripted
`consumer_skill_dirs` and fail before any add-on file or manifest mutation.
These fields are advanced inputs and explicit consumers are validated before
any add-on file or manifest mutation.
Do not hand-author per-skill add-on usage tables in `content.md`.

When `subagent_specialists` are requested for orchestrator scaffolds, the
scaffolder writes `native-agents/agents.yaml` stubs. Render and install output
generate the runtime spawn notes into `SKILL.md`; authors must fill the TODOs
before shipping.

The scaffolder is atomic: if validation, manifest updates, install, or rollback
fails, it must not leave partial source changes.

## Validation And Guards

Use the normal authoring check before shipping authored content changes:

```bash
skill-bill validate
```

Use the full maintainer gate before shipping runtime, scaffold, contract, docs,
or agent-config changes:

```bash
skill-bill validate
(cd runtime-kotlin && ./gradlew check)
npx --yes agnix --strict .
scripts/validate_agent_configs
```

Key guards:

- source skill directories under `skills/` may contain only `content.md` and
  optional `native-agents/`
- committed generated `SKILL.md` wrappers are rejected
- committed generated support pointer files are rejected
- committed provider-specific native-agent artifacts are rejected
- platform manifests must match shell contract version `1.1`
- manifest-declared files must exist and be valid `content.md`
- pointer target parity is validated against platform manifests
- native-agent composition must render self-contained provider output

## Changing The Generation System

When changing wrapper, support pointer, install, scaffold, or native-agent
generation:

1. Update the generator code.
2. Update validators so stale source shapes fail loudly.
3. Update tests and golden fixtures.
4. Update `docs/skill-source-generation.md`, `AGENTS.md`, and onboarding docs
   if the source/runtime boundary changes.
5. Run the full validation gate.
6. Rebuild packaged distributions with `(cd runtime-kotlin && ./gradlew installDist)`.
7. Re-run `./install.sh` or the appropriate install commands to refresh local
   staged installs and native-agent links.

## Practical Do And Do Not

Do:

- edit authored skill guidance in `content.md`
- keep support contracts under `orchestration/`
- keep add-ons under `platform-packs/<slug>/addons/`
- use generated support pointers for installed runtime files
- use `native-agents/agents.yaml` for bundled orchestrator native agents
- validate after every source-shape or generation change

Do not:

- commit `skills/<skill>/SKILL.md`
- commit support pointer files under `skills/<skill>/`
- split ordinary skill guidance into extra files under `skills/<skill>/`
- commit `claude-agents/`, `codex-agents/`, `opencode-agents/`, or
  `junie-agents/`
- manually edit generated runtime staging directories as source
- rely on direct source symlinks for content-managed skills
