---
name: shell-content-contract
description: Versioned schema contract between the governed code-review shell and user-owned platform packs. Platform packs declare the contract version they target; the shell loader validates manifests and content against this schema.
---

# Shared Shell Content Contract

This is the canonical shell+content contract. The governed code-review shell
(`skills/bill-code-review/SKILL.md`) owns ceremony, orchestration, output
structure, telemetry, and contract enforcement. Platform packs under
`platform-packs/<platform>/` own reviewer reasoning. This file specifies the
boundary between the two.

Skills consume this file through sibling symlinks (e.g. `shell-content-contract.md`
inside the shell skill directory), so changes here propagate to every linked
skill immediately.

Do not reference this repo-relative path directly from installable skills — use
the sibling symlink instead.

## Contract Version

The current shell contract version is **`1.1`**.

- The shell pins its target version. Platform packs must declare the same version.
- Any platform pack whose `contract_version` does not equal the shell's version
  must cause the shell loader to fail loudly with a migration message that
  includes both versions and the offending pack slug. Under v1.1 the message
  must also point at `.venv/bin/python3 scripts/migrate_to_content_md.py`.
- Contract versions follow `MAJOR.MINOR`. Major changes are breaking; minor
  changes are additive and do not break existing packs.
- The `SHELL_CONTRACT_VERSION` and `TEMPLATE_VERSION` constants are the
  single source of truth, declared in `skill_bill/constants.py`. Every
  scaffolded SKILL.md carries both values as frontmatter stamps so
  ``skill-bill upgrade`` and ``skill-bill doctor`` can detect drift.

### v1.1 additions (SKILL-21)

v1.1 splits the governance shell from the author-owned skill body:

- Every governed skill directory must contain a sibling `content.md` file
  next to `SKILL.md`. The author-editable prompt body lives in `content.md`;
  `SKILL.md` remains the contract-enforced shell with the required H2 set.
- Every SKILL.md carries a new required H2 `## Execution` whose body is
  byte-identical across every governed skill:

  ```
  ## Execution

  Follow the instructions in [content.md](content.md).
  ```
- Every SKILL.md carries `shell_contract_version` and `template_version`
  frontmatter keys. Contract-version mismatches loud-fail; template-version
  drift is upgrade-actionable only (not a runtime failure).
- `content.md` has no H2 requirement, no frontmatter requirement, and no
  minimum length. The loader validates only that it exists.
- `content.md` carries **only** author-owned skill knowledge: signals,
  rubrics, routing tables, project-specific rules, classification cues,
  add-on selection rules, and per-specialist scope heuristics. See the
  content.md taxonomy below for the split rule.
- `## Project Overrides` is shell governance, not author content. The
  section records the overrides precedence rule (per-skill overrides >
  project-wide overrides > built-in defaults) and lives in SKILL.md for
  every governed skill, including platform-pack baselines,
  code-review-area specialists, and shelled quality-check overrides.
  Authors must not copy the heading into `content.md`; the migration
  script and scaffolder refuse to emit it there.

### content.md taxonomy (pass 2)

The split rule for `content.md` vs `SKILL.md` is mechanical:

- **SKILL.md / shell owns** — anything the skill-bill framework
  maintains: output contracts (session/run IDs, severity/confidence
  scales, risk-register row format, verdict format), orchestration
  (delegation/inline mode descriptions, scope-determination bullet
  lists), telemetry sidecar pointers, learnings resolution, sidecar
  file references (`stack-routing.md`, `review-orchestrator.md`,
  `review-delegation.md`, `telemetry-contract.md`,
  `specialist-contract.md`, `shell-content-contract.md`), and
  `## Project Overrides` precedence.
- **content.md / author owns** — skill-specific signal markers,
  classification cues (`build.gradle*`, `@Composable`, etc.),
  specialist routing tables, add-on selection rules, per-specialist
  scope heuristics, review rubrics (Focus / Ignore / Applicability),
  and `## Project-Specific Rules` blocks.

The following H2 headings are free-form ceremony (not in the
required-section set, but shell-owned by taxonomy). The migration
script's blacklist drops them on the floor rather than copying them
into `content.md`; the hygiene test walks every shipped `content.md`
and fails if any reappears:

- `## Setup` (generic scope-determination bullet list)
- `## Additional Resources` (sidecar-file pointers)
- `## Local Review Learnings`
- `## Output Format` (generic risk-register row format)
- `## Output Rules` (severity/confidence scale restatements)
- `## Review Output` (session/run ID + telemetry pointers)
- `## Delegated Mode` / `## Inline Mode` (shell shape descriptors)
- `## Routing Rules`
- `## Shared Stack Detection`
- `## Execution Contract`
- `## Overview` (when it duplicates SKILL.md `## Description`)

`### Telemetry` and `### Implementation Mode Notes` are also shell
ceremony and must not appear in `content.md`.

The blacklist is canonicalized in
`skill_bill.shell_content_contract.CEREMONY_FREE_FORM_H2S`. Add new
ceremony headings there (not in ad-hoc string lists) so the migration
script, the scaffolder, and the hygiene test stay in lockstep.

## Required Platform Manifest (`platform.yaml`)

Every platform pack lives at `platform-packs/<platform-slug>/platform.yaml`.

Required top-level fields:

- `platform` — the platform slug. Must match the enclosing directory name.
- `contract_version` — the shell contract version this pack targets. Must be
  the string `1.0` today. The loader rejects mismatches.
- `routing_signals` — an object declaring how the router should detect this
  platform. Required sub-fields:
  - `strong` — list of strings. Each entry is a strong signal (a path marker,
    file extension, dependency coordinate, or language-level marker) that
    indicates the platform when seen in the review scope.
  - `tie_breakers` — list of strings describing post-detection rules that
    disambiguate this platform against overlapping ones (e.g. Kotlin vs. KMP
    vs. backend-Kotlin).
  - `addon_signals` — optional list of signal hints used by governed add-ons
    that belong to this platform. May be an empty list.
- `declared_code_review_areas` — list of area slugs. Each entry must be one of
  the approved areas: `architecture`, `performance`, `platform-correctness`,
  `security`, `testing`, `api-contracts`, `persistence`, `reliability`, `ui`,
  `ux-accessibility`. The list may be empty for meta packs (e.g. self-config)
  that have no specialist areas.
- `declared_files` — object mapping logical content slots to content file
  paths, relative to the platform pack root. Required keys:
  - `baseline` — the per-platform baseline review content file path (the
    orchestrator-equivalent skill content).
  - `areas` — object mapping each entry of `declared_code_review_areas` to
    its content file path.
- `governs_addons` — optional boolean. Packs that own governed add-ons must
  set this to `true`. Defaults to `false` when omitted. (Used by internal
  tooling; does not affect the shell's loud-fail behavior.)

Optional top-level fields:

- `display_name` — human-readable label for installers and docs.
- `notes` — free-form maintainer notes.
- `declared_quality_check_file` — path (string) to a per-platform
  quality-check SKILL.md file, relative to the platform pack root. When
  present, the shell loader validates the referenced file against the
  quality-check content contract (see below). Omitting the key is valid —
  the shell contract version stays `1.0` and packs without the key remain
  contract-compliant. Today the `kmp` pack intentionally omits the key; the
  `bill-quality-check` shell falls back to the `kotlin` pack for that slug.

## Required Content Files

Every path declared in `declared_files` must exist on disk relative to the
platform pack root.

Each declared content file must be a Markdown file with a YAML frontmatter
block (`---` ... `---`) and must contain all of the following H2 sections:

- `## Description`
- `## Specialist Scope`
- `## Inputs`
- `## Outputs Contract`
- `## Execution`
- `## Execution Mode Reporting`
- `## Telemetry Ceremony Hooks`

Additionally, a sibling `content.md` file must exist in the same directory
as every declared SKILL.md. The `## Execution` body MUST be byte-identical
to the canonical form documented in the v1.1 contract version section
above. Missing content.md or an edited Execution body loud-fails with
`MissingContentBodyFileError` or `InvalidExecutionSectionError`.

Section order is not enforced, but each section heading must appear exactly as
written (case-sensitive, H2 only).

Content files may include additional H2 sections beyond the required set.

## Required Content File (quality-check)

When a platform pack declares the optional `declared_quality_check_file`
top-level key, the referenced Markdown file must contain all of the
following H2 sections:

- `## Description`
- `## Execution Steps`
- `## Fix Strategy`
- `## Execution`
- `## Execution Mode Reporting`
- `## Telemetry Ceremony Hooks`

`## Execution` is the SKILL-21 required link to the sibling `content.md`;
the body is byte-identical to the canonical form described above.

The quality-check content contract is intentionally narrower than the
code-review contract: the shared `bill-quality-check` shell is horizontal
and does not require the `## Specialist Scope`, `## Inputs`, or
`## Outputs Contract` sections.

Section order is not enforced, but each section heading must appear
exactly as written (case-sensitive, H2 only). Content files may include
additional H2 sections beyond the required set.

## Loud-Fail Rules

The shell loader must refuse to run when any of the following conditions
apply. Each condition maps to a specific named exception. No silent fallback
is ever permitted.

- Missing `platform.yaml` → `MissingManifestError`.
- `contract_version` missing, malformed, or not equal to the shell's version
  → `ContractVersionMismatchError`. The message must include both the
  shell's expected version and the pack's declared version.
- Required manifest field missing or invalid (missing `platform`, invalid
  `declared_code_review_areas`, invalid `declared_files` map, etc.) →
  `InvalidManifestSchemaError`.
- A file path declared under `declared_files` does not exist →
  `MissingContentFileError`. The message must include the slot key and the
  resolved path.
- A declared content file is missing one of the required H2 sections →
  `MissingRequiredSectionError`. The message must include the missing
  section heading and the file path.
- A governed skill directory has no `content.md` sibling →
  `MissingContentBodyFileError`. The message points the operator at
  `scripts/migrate_to_content_md.py`.
- The `## Execution` body differs from the canonical byte-identical form →
  `InvalidExecutionSectionError`. The message includes the file path and
  the canonical body so the operator can restore it.

### Failure precedence

The loader and the validator enforce the same first-error order so the
same pack fails identically through every entry point:

1. Contract-version (`ContractVersionMismatchError`)
2. Manifest-schema (`InvalidManifestSchemaError`, `MissingManifestError`)
3. Content-file presence (`MissingContentFileError`)
4. Content-section H2 set (`MissingRequiredSectionError`)
5. Execution-link body (`InvalidExecutionSectionError`)
6. Content-sibling presence (`MissingContentBodyFileError`)

Every error message must name the specific artifact at fault (pack slug,
file path, section heading, or version string) so operators can repair the
issue without guessing.

### Loud-Fail Rules (quality-check)

The `bill-quality-check` shell resolves the per-platform quality-check file
through a dedicated loader (`skill_bill.shell_content_contract.load_quality_check_content`).
The loader enforces two additional loud-fail rules when a pack declares the
optional `declared_quality_check_file` key:

- The file referenced by `declared_quality_check_file` does not exist →
  `MissingContentFileError`. The message must include the pack slug and the
  resolved file path.
- The declared quality-check content file is missing one of the required H2
  sections listed above → `MissingRequiredSectionError`. The message must
  include the missing section heading and the file path.

Calling `load_quality_check_content` on a pack whose
`declared_quality_check_file` is `None` also raises
`MissingContentFileError` rather than silently returning nothing — callers
must gate the call on `pack.declared_quality_check_file is not None`. The
shell never silently substitutes a different pack's quality-check file
except via the explicit `kmp` → `kotlin` fallback noted above.

## Discovery Semantics

The shell loader, validator, and stack-routing playbook all share a common
discovery algorithm:

1. Walk `platform-packs/` for immediate subdirectories.
2. For each candidate slug, load `platform-packs/<slug>/platform.yaml` via the
   loader.
3. Validate each pack against this contract.
4. The routed skill name for a platform pack with slug `<slug>` is
   `bill-<slug>-code-review`. Installers and runtime skills must preserve this
   contract so existing user-facing commands keep working.

Discovery must not hardcode platform names. Any routing decision that cares
about ordering must read priority from each pack's manifest, not from an
enumerated list.

## New-skill Scaffolding

The payload contract that drives the new-skill scaffolder lives in the
sibling `SCAFFOLD_PAYLOAD.md`. It specifies the required JSON shape, the
version handshake, the supported `kind` values, the pre-shell family list,
and the loud-fail exception catalog. The scaffolder refuses to run when the
payload does not conform to that contract.

## v1.0 → v1.1 migration

`scripts/migrate_to_content_md.py` rewrites every governed SKILL.md under a
shelled family (code-review or quality-check) to the v1.1 shape:

1. Parse existing SKILL.md into frontmatter + H2 sections.
2. Free-form H2s plus author-edited required sections flow into `content.md`.
3. SKILL.md is regenerated from the current v1.1 template, preserving the
   existing `name` and `description` frontmatter.
4. Per-skill rollback on any validation failure keeps the tree byte-identical
   for the failing skill; the rest of the tree continues.
5. `_migration_backup/<timestamp>/` captures the pre-migration bytes before
   the first rewrite. The script is idempotent — a second run on a
   migrated tree is a no-op unless `--force` is passed.

`skill-bill upgrade` regenerates SKILL.md shells whose `template_version`
has drifted from the current template, without touching `content.md`.
`skill-bill edit <name>` opens `content.md` in `$VISUAL` / `$EDITOR`.
`skill-bill doctor` reports missing content.md (error) and template-version
drift (warning with the exact upgrade command to run).

## Relationship To Stack Routing

`orchestration/stack-routing/PLAYBOOK.md` is the user-facing routing
playbook. It defines the signal collection order and the tie-breaker rules in
prose. The discovery algorithm above is the machine-readable mirror of that
prose contract. Stack-routing authors must keep the two in sync: when a pack
changes `routing_signals`, the prose description in stack-routing.md must not
contradict it.
