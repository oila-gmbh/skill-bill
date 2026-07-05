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
**internal** — see the Internal Skills section below for the full contract.

## Internal Skills

This section is the normative contract; for the end-to-end architecture and
routing walkthrough see
[internal-skills-architecture.md](internal-skills-architecture.md).

Most governed skills are **listed**: they install as a `SKILL.md` entry in each
agent's `skills_dir` and are invocable by users via the Skill tool. A skill can
instead be classified **internal**: its governed content still installs, but as
a markdown sidecar inside another (parent) skill's installed directory, and it
is never listed or directly invocable. Internal skills exist because some
governed content is a dispatch target selected by a parent router, not a user
entry point, and listing every dispatch target dilutes trigger-phrase matching
and misrepresents the product surface.

### Frontmatter contract (PD1)

A skill is internal when its `content.md` frontmatter carries exactly one
optional key:

```yaml
internal-for: <parent-skill-name>
```

Presence of the key makes the skill internal; absence means listed. There are no
other frontmatter keys, no `config.yaml` switches, no per-agent overrides, and no
manifest-level internality flag — the platform pack manifest (`platform.yaml`)
is never consulted for classification. Both base skills under `skills/` and
platform-pack skills under `platform-packs/<slug>/` may carry the key; the only
parent-side restriction is that the parent itself must be a **listed base
skill** (`skills/<parent>/`), never a platform-pack skill and never another
internal skill. One shared rule evaluator backs the authoring seam, the
install-plan seam, and `skill-bill validate`, so the same declaration fails
identically everywhere it is read.

### Parent rules and loud-fail behavior

The pipeline loud-fails with a typed, actionable error
(`InvalidInternalSkillClassificationError`; the same rules surface as issues at
`skill-bill validate` time) when an internal skill:

- has a **missing** value — the `internal-for` key is present with no value;
- declares an **unknown** parent — no discovered skill matches the name;
- declares a parent that is **itself internal** — chaining is not allowed;
  depth is 1, so a sidecar cannot host another sidecar;
- declares **itself** as parent;
- declares a **platform-pack** parent — the parent must be a listed base skill.

A separate collision guard (`InternalSkillSidecarCollisionError`) fails staging
when an authored file in the parent's source directory already occupies a
would-be sidecar name (e.g. an authored `bill-feature/bill-feature-task.md`);
`skill-bill validate` surfaces the same collision before install. Validation
also checks every `` `<skill-name>.md` `` sidecar reference inside a skill's
content.md: the referenced skill must be internal and share the referencing
skill's effective parent, or the reference would break at session time with a
file-not-found.

Direct installs of an internal skill are refused: `skill-bill link-skill`
against an internal skill's directory fails with the same typed error and
directs to the parent, so an internal skill can never gain its own
`skills_dir` entry through a side path.

### Installed layout (PD2 / PD6)

Install renders an internal skill's governed content as a markdown sidecar named
`<skill-name>.md` (the full skill name, e.g. `bill-feature-task.md`, never an
abbreviated form) placed at the top level of the parent skill's installed
directory, next to the parent's `SKILL.md`. The sidecar carries the same
governed wrapper a listed skill's `SKILL.md` would carry (frontmatter,
descriptor, class sections, `## Execution` body, ceremony) — behavior parity
over token savings; there is no trimmed sidecar format. Critically, install
creates **no `SKILL.md` directory entry** for the internal skill in any agent's
`skills_dir`: the agent skill list never contains an internal skill, and the
sidecar's bytes are folded into the parent's content hash so reinstall
re-renders and idempotency holds. Cache reuse re-verifies that every expected
sidecar file is present, so an externally pruned sidecar triggers a re-render
instead of being reused broken.

### Flatten rule for multi-level families (PD2)

When a family has more than one logical level — a router parent, stack entry
skills, and area specialists — every internal member declares the **same
parent** rather than chaining through the intermediate level. Depth stays at 1
(PD1: a sidecar is a file, not a directory; the staging model cannot express a
sidecar hosting another sidecar), and sibling co-location is exactly what a
router flow needs: the parent's installed directory holds the routed entry
sidecar and the specialist sidecars it reads as siblings, all resolvable as
"a file next to this `SKILL.md`" with no per-agent path knowledge. The
code-review family is the worked example: 34 review-pack skills — four stack
entries plus their 30 specialists — all carry `internal-for: bill-code-review`
and all install as siblings inside `bill-code-review/`. The stack entry skills
do **not** become parents of their specialists.

### Selection-aware sidecars for platform-pack internals (PD3)

A base-skill internal sidecar stages whenever its parent stages, because base
skills are always installed. A platform-pack internal sidecar stages **only when
its pack is selected** (`PlatformPackSelection`: `NONE`/`SELECTED`/`ALL`). The
staging sidecar discovery consults the install plan's selected pack skills
(which already carry each skill's `sourceDir` and parsed `internalFor`) rather
than re-scanning `platform-packs/` independently of selection. The parent's
content hash folds exactly the selected sidecars, so changing platform selection
re-stages the parent and cache reuse stays correct. With `NONE` of a parent's
internal packs selected, the parent stages byte-identically to a repo with no
internal pack skills — unselected packs contribute no sidecar and no hash
bytes. `ALL` selection stages every opted-in internal sidecar.

### Baseline co-presence guard (PD8)

A pack manifest may declare required baseline layers
(`code_review_composition.baseline_layers[].required = true`, resolved as a
sibling sidecar at review time — see the KMP pack's `bill-kotlin-code-review`
baseline). Once both layers are internal sidecars, selecting the dependent pack
without its baseline pack would leave the baseline sidecar missing at review
time. Install planning therefore loud-fails with a typed error
(`MissingBaselinePlatformSelectionError`) when the selection includes a pack
that declares a required baseline layer in an unselected pack. There is no
silent auto-include — consistent with the shell's never-silently-substitute
ethos. `ALL` selection is trivially safe because every required baseline is
selected too.

### File-read invocation contract (PD5)

The Skill tool on every supported agent resolves only listed skills; there is no
invocable-but-hidden state. A listed parent therefore invokes an internal skill
by **reading the sidecar file from its own installed directory** (a sibling file
read) and executing its instructions in the current session, passing the same
argument conventions as before (`mode:runtime` / `mode:prose`,
`parallel-review:<agent>`, issue key, spec path). The Skill tool is never used
to invoke an internal skill. This file-read pattern is already established for
other sibling sidecars (`shell-ceremony.md`, `compose-guidelines.md`) and is
more portable across agents than Skill-tool mechanics.

The contract extends verbatim to review routing, where the dispatch target is
itself an internal sidecar. The routed dispatch resolves the dominant pack's
entry sidecar (e.g. `bill-kotlin-code-review.md`); a stack orchestrator sidecar
resolves its specialist rubrics as sibling sidecars (e.g.
`bill-kotlin-code-review-security.md`); and a KMP baseline layer resolves
`bill-kotlin-code-review.md` as a sibling sidecar. Delegated review workers keep
receiving rendered runtime instructions and rubric content/paths from the parent
orchestrator — no worker ever resolves a hidden skill via the Skill tool or a
standalone `skills_dir` path. Lane-2 parallel reviews keep invoking
`/bill-code-review`, which remains listed.

### Worked example: the feature-execution family

The feature-execution family is the canonical worked example. Exactly five
skills are internal, all with parent `bill-feature`: `bill-feature-task`,
`bill-feature-task-runtime`, `bill-feature-task-prose`,
`bill-feature-task-subtask-runner`, and `bill-feature-goal`. After install, the
agent skill list shows `bill-feature` (and the standalone listed
`bill-feature-spec`) but none of the five. `bill-feature` dispatches to task and
goal execution by reading the rendered sidecar files installed inside its own
directory (`bill-feature-task.md`, `bill-feature-goal.md`) and executing them.
`bill-feature-spec` stays listed and standalone because it is a different kind
of skill (spec preparation without implementation), so it is still invoked via
the Skill tool. Repo source directories for the internal skills do not move or
rename (PD3): `skills/bill-feature-task/content.md`,
`skills/bill-feature-task-prose/content.md`, etc. stay exactly where they are;
only frontmatter and prose inside them change. Workflow identity strings, the DB
`workflow_name` CHECK constraint, telemetry constants, and MCP tool names are
byte-for-byte unchanged (PD4) even though the skills are no longer listed.

### Worked example: the code-review family

The code-review family is the platform-pack worked example. Exactly 22
review-pack skills are internal — every skill under
`platform-packs/{ios,kotlin,kmp}/code-review/`: three stack entry skills
(`bill-ios-code-review`, `bill-kotlin-code-review`, `bill-kmp-code-review`)
plus their 19 area specialists. All 22 carry
`internal-for: bill-code-review` and install as siblings inside
`bill-code-review/`'s staged directory; the three stack entries do **not** become
parents of their specialists (PD2 flatten rule). After install with all packs
selected, the agent skill list shows `bill-code-review` (plus the listed
`bill-code-review-parallel` and `bill-code-check`) but none of the 22.
`bill-code-review` reads the dominant pack's entry sidecar, which reads its
specialist rubric sidecars as siblings. With only the Kotlin pack selected,
exactly the 9 Kotlin sidecars (`bill-kotlin-code-review.md` plus its 8
specialists) stage; the other 13 contribute nothing (PD3). The KMP pack declares
`bill-kotlin-code-review` as a required baseline layer, so selecting KMP without
Kotlin fails install planning with the typed baseline-co-presence error (PD8).
SKILL-105 applies the same platform-pack internal-sidecar mechanism to the
quality-check family: pack quality-check skills (`bill-ios-code-check`,
`bill-kotlin-code-check`) carry `internal-for: bill-code-check`, install as
selected-pack sidecars inside `bill-code-check/`, and are not listed commands.
Their routed skill names remain stable identity strings for manifests, routing
output, and telemetry.

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
