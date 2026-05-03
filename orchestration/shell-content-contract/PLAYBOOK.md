---
name: shell-content-contract
description: Versioned schema contract between the governed code-review shell and user-owned platform packs. Platform packs declare the contract version they target; the shell loader validates manifests and content against this schema.
---

# Shared Shell Content Contract

This is the canonical shell+content contract. Governed platform-pack skills now
use a thin wrapper `SKILL.md` plus sibling sidecars: `content.md` for
platform-authored execution content and `shell-ceremony.md` for shared
project-overrides, execution-mode reporting, and telemetry rules. This file
specifies that boundary.

Skills consume this file through sibling symlinks (e.g. `shell-content-contract.md`
inside the shell skill directory), so changes here propagate to every linked
skill immediately.

Do not reference this repo-relative path directly from installable skills — use
the sibling symlink instead.

## Contract Version

The current shell contract version is **`1.1`**.

- The shell pins its target version. Platform packs must declare the same version.
- Any platform pack whose `contract_version` does not equal the shell's version
  must cause the shell loader to fail loudly with a message that includes both
  versions and the offending pack slug.
- Contract versions follow `MAJOR.MINOR`. Major changes are breaking; minor
  changes are additive and do not break existing packs.

## Required Platform Manifest (`platform.yaml`)

Every platform pack lives at `platform-packs/<platform-slug>/platform.yaml`.

Required top-level fields:

- `platform` — the platform slug. Must match the enclosing directory name.
- `contract_version` — the shell contract version this pack targets. Must be
  the string `1.1` today. The loader rejects mismatches.
- `routing_signals` — an object declaring how the router should detect this
  platform. Required sub-fields:
  - `strong` — list of strings. Each entry is a strong signal (a path marker,
    file extension, dependency coordinate, or language-level marker) that
    indicates the platform when seen in the review scope.
  - `tie_breakers` — list of strings describing post-detection rules that
    disambiguate this platform against overlapping ones (e.g. Kotlin vs. KMP
    vs. backend-Kotlin).
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
- `area_metadata` — object mapping each entry of `declared_code_review_areas`
  to descriptor metadata used to auto-render the governed `## Descriptor`
  section. Required sub-fields:
  - `focus` — non-empty string describing the area's review focus.
Optional top-level fields:

- `display_name` — human-readable label for installers and docs.
- `notes` — free-form maintainer notes.
- `declared_quality_check_file` — path (string) to a per-platform
  quality-check SKILL.md file, relative to the platform pack root. When
  present, the shell loader validates the referenced file against the
  quality-check content contract (see below). Omitting the key is valid —
  the shell contract version stays `1.1` and packs without the key remain
  contract-compliant. Today the `kmp` pack intentionally omits the key; the
  `bill-quality-check` shell falls back to the `kotlin` pack for that slug.

## Required Content Files

Every path declared in `declared_files` must exist on disk relative to the
platform pack root.

Each declared content file must be a Markdown file with a YAML frontmatter
block (`---` ... `---`) and must contain all of the following H2 sections:

- `## Descriptor`
- `## Execution`
- `## Ceremony`

Section order is not enforced, but each section heading must appear exactly as
written (case-sensitive, H2 only). The `## Descriptor` section is scaffolded
from skill context plus `area_metadata` and is loader-validated for exact-body
drift.

Each governed skill directory must also contain:

- `content.md` — platform-authored execution content referenced by the thin
  `## Execution` pointer in `SKILL.md`.
- `shell-ceremony.md` — shared ceremony sidecar, usually a sibling symlink to
  `orchestration/shell-content-contract/shell-ceremony.md`.

`content.md` is the author-owned surface. It must not re-state shell-owned
output formats, telemetry mechanics, override precedence, execution-mode
reporting, or required sidecar references. Those stay in the generated
`SKILL.md` wrapper or shared sidecars so the runtime contract can be upgraded
without rewriting authored business guidance.

## Governed Add-On Consumption

Governed add-ons are pack-owned supporting files under
`platform-packs/<platform>/addons/`. They are not standalone skills, routed
entry points, or cross-pack assets.

After stack routing selects a platform pack, any platform-owned governed skill
in that same pack may reference the pack's add-ons from its sibling
`content.md` as supporting guidance.

- Add-on selection happens after stack routing, never before it.
- Add-ons enrich the already-routed platform skill; they do not bypass that
  skill or create a new command surface.
- Specialist or child skills inside the same pack may consume already-selected
  add-ons, but they must still treat them as subordinate supporting files.

## Required Content File (quality-check)

When a platform pack declares the optional `declared_quality_check_file`
top-level key, the referenced Markdown file must contain all of the
following H2 sections:

- `## Descriptor`
- `## Execution`
- `## Ceremony`

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
- A governed skill is missing its sibling `content.md` →
  `MissingContentFileError`.
- A governed skill is missing its sibling `shell-ceremony.md` →
  `MissingShellCeremonyFileError`.
- A governed skill's `## Execution` body drifts from the canonical wrapper
  template → `InvalidExecutionSectionError`.
- A governed skill's `## Ceremony` body drifts from the canonical wrapper
  template → `InvalidCeremonySectionError`.
- A governed skill's `## Descriptor` body drifts from the scaffolded render
  derived from skill context plus `area_metadata` →
  `InvalidDescriptorSectionError`.

Every error message must name the specific artifact at fault (pack slug,
file path, section heading, or version string) so operators can repair the
issue without guessing.

### Loud-Fail Order

Loader precedence is authoritative and must stay stable:

1. Manifest presence and YAML validity.
2. Manifest schema validation.
3. Contract-version mismatch.
4. Declared `SKILL.md` file presence.
5. Required governed H2 section presence.
6. Sibling `content.md` presence.
7. Sibling `shell-ceremony.md` presence.
8. `## Execution` body drift.
9. `## Ceremony` body drift.
10. `## Descriptor` body drift.

### Loud-Fail Rules (quality-check)

The `bill-quality-check` shell resolves the per-platform quality-check file
through a dedicated loader (`skillbill.scaffold.ShellContentLoader.loadQualityCheckContent` in `runtime-core`).
The loader enforces two additional loud-fail rules when a pack declares the
optional `declared_quality_check_file` key:

- The file referenced by `declared_quality_check_file` does not exist →
  `MissingContentFileError`. The message must include the pack slug and the
  resolved file path.
- The declared quality-check `SKILL.md` is missing one of the required H2
  sections listed above → `MissingRequiredSectionError`. The message must
  include the missing section heading and the file path.
- The governed quality-check skill is missing sibling `content.md` →
  `MissingContentFileError`.
- The governed quality-check skill is missing sibling `shell-ceremony.md` →
  `MissingShellCeremonyFileError`.
- The governed quality-check skill's `## Execution` body drifts →
  `InvalidExecutionSectionError`.
- The governed quality-check skill's `## Ceremony` body drifts →
  `InvalidCeremonySectionError`.
- The governed quality-check skill's `## Descriptor` body drifts →
  `InvalidDescriptorSectionError`.

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

## Relationship To Stack Routing

`orchestration/stack-routing/PLAYBOOK.md` is the user-facing routing
playbook. It defines the signal collection order and the tie-breaker rules in
prose. The discovery algorithm above is the machine-readable mirror of that
prose contract. Stack-routing authors must keep the two in sync: when a pack
changes `routing_signals`, the prose description in stack-routing.md must not
contradict it.
