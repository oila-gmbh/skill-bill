---
name: scaffold-payload
description: Payload schema for the new-skill scaffolder (SKILL-15). Documents the JSON contract consumed by `skill-bill new-skill --payload`, the `new_skill_scaffold` MCP tool, and the `bill-create-skill` skill.
---

# Scaffold Payload Contract

This is the canonical payload schema for the new-skill scaffolder. Every
caller of `skillbill.scaffold.ScaffoldService.scaffold(payload)` (in `runtime-core`) — the `runtime-cli` `new-skill` command, the `runtime-mcp` `new_skill_scaffold` tool,
and the `bill-create-skill` skill — ships a payload that conforms to
this schema. Mismatches raise specific named exceptions and abort the run;
no silent coercion.

## Versioning

- Required key: `scaffold_payload_version`.
- Current value: `"1.0"`.
- Any payload that declares a different version raises
  `ScaffoldPayloadVersionMismatchError`. Bump the scaffolder and every caller
  in lockstep when the contract changes.
- Version follows `MAJOR.MINOR`. Minor changes are additive; major changes
  are breaking.

## Required Keys

Every payload MUST include:

- `scaffold_payload_version` — exact match for the scaffolder's expected
  version string.
- `kind` — one of:
  - `"horizontal"` — placed under `skills/<name>/SKILL.md`.
  - `"platform-override-piloted"` — placed under
    `platform-packs/<slug>/<family>/<name>/SKILL.md` plus a manifest edit
    for shelled families. Pre-shell families are placed under
    `skills/<platform>/<name>/SKILL.md` with an interim-location note.
  - `"platform-pack"` — creates a new `platform-packs/<slug>/` root with a
    generated baseline `code-review` skill, a default `quality-check` skill,
    and a freshly rendered `platform.yaml`.
  - `"code-review-area"` — placed under
    `platform-packs/<slug>/code-review/<name>/SKILL.md` plus additions to
    `declared_code_review_areas`, `declared_files.areas`, and
    `area_metadata` in the owning `platform.yaml`.
  - `"add-on"` — placed at `platform-packs/<platform>/addons/<name>.md` (flat; no
    sub-directory).
- `name` — the canonical `bill-...` slug for the new skill. For
  `platform-pack` and `code-review-area`, the scaffolder derives canonical
  names when this key is omitted; if provided, the value must still match the
  canonical shape.

## Conditionally Required Keys

- `platform` — required for `platform-override-piloted`, `code-review-area`,
  `platform-pack`, and `add-on`.
  - For `platform-override-piloted`, `code-review-area`, and `add-on`, it
    must name an existing platform slug (e.g. `kotlin`, `kmp`).
  - For `platform-pack`, it is the new platform slug to create.
- `family` — required for `platform-override-piloted`. One of the known
  families:
  - Shelled: `code-review`, `quality-check`.
  - Pre-shell (see the pre-shell family registry in `skillbill.scaffold` (`runtime-core`)):
    `feature-implement`, `feature-verify`.
- `area` — required for `code-review-area`. Must be one of the approved
  areas in the approved code-review area set declared in `skillbill.scaffold` (`runtime-core`):
  `architecture`, `performance`, `platform-correctness`, `security`,
  `testing`, `api-contracts`, `persistence`, `reliability`, `ui`,
  `ux-accessibility`.
- `routing_signals` — required for `platform-pack` only when the platform
  does not have a built-in preset. Must be a mapping with a non-empty
  `strong` list and optional `tie_breakers` list. For
  known platforms such as `java` and `php`, the scaffolder can infer these
  defaults.

## Optional Keys

- `description` — one-line description copied into the frontmatter.
- `content_body` — authored markdown body for governed skills. When
  provided for shelled families (`code-review`, `quality-check`), the
  scaffolder writes it into the sibling `content.md` instead of the default
  maintainer starter content.
- `display_name` — human-friendly label for `platform-pack`. Defaults to a
  title-cased version of `platform`.
- `skeleton_mode` — `starter` or `full` for `platform-pack`. Defaults to
  `full`.
  - `starter` creates the pack root, baseline `code-review`, and default
    `quality-check`.
  - `full` also creates bare specialist stubs for every approved
    code-review area and registers them in the generated manifest, including
    the `area_metadata` entries used to auto-render governed `## Descriptor`
    sections.
- `body` — optional string for `add-on`. When provided, the scaffolder
  writes this markdown body verbatim to the target add-on file instead of
  rendering the default placeholder template.
- `repo_root` — absolute path override used by tests. Defaults to the
  current working directory.
- `subagent_specialists` — list of specialist subagent names to scaffold
  alongside an orchestrator skill. Each name must match
  `^[a-z][a-z0-9-]*$`, be non-empty, and unique within the list. Honored
  ONLY for orchestrator kinds: `horizontal`, `platform-override-piloted`,
  and `platform-pack`. For `platform-pack`, stubs are attached to the
  baseline orchestrator skill (`bill-<platform>-code-review`) only — never
  to the per-area specialist skills or the quality-check skill. Supplying
  this field for `code-review-area` or `add-on` raises
  `InvalidScaffoldPayloadError`. Default: `[]`.
- `no_subagents` — boolean opt-out. When `true`, the scaffolder skips the
  default subagent stub emission for an orchestrator kind even when
  `subagent_specialists` is empty. Setting `no_subagents: true` together
  with a non-empty `subagent_specialists` raises
  `InvalidScaffoldPayloadError`. Default: `false`.

When `subagent_specialists` is non-empty (and not suppressed), the
scaffolder emits one Codex TOML stub at
`<orchestrator-skill-dir>/codex-agents/<name>.toml` and one OpenCode
markdown stub at
`<orchestrator-skill-dir>/opencode-agents/<name>.md` per specialist. It
also injects a `## Subagent Spawn Runtime Notes` section into the
orchestrator's `content.md` that documents how Claude, Codex, and
OpenCode each resolve specialist spawns. Stubs ship with a `TODO:`
description and body plus a free-text pointer to
`specialist-contract.md` for the F-XXX Risk Register format used by
review specialists; they do NOT include literal `F-XXX` markers.
Authors fill in the TODO placeholders before shipping.

## Worked Examples

### Horizontal skill

```json
{
  "scaffold_payload_version": "1.0",
  "kind": "horizontal",
  "name": "bill-new-horizontal",
  "description": "Use for ..."
}
```

### Platform-override (piloted, code-review family)

```json
{
  "scaffold_payload_version": "1.0",
  "kind": "platform-override-piloted",
  "name": "bill-kotlin-code-review-new",
  "platform": "kotlin",
  "family": "code-review",
  "description": "..."
}
```

### Platform-override (piloted, quality-check family)

```json
{
  "scaffold_payload_version": "1.0",
  "kind": "platform-override-piloted",
  "name": "bill-kotlin-quality-check",
  "platform": "kotlin",
  "family": "quality-check"
}
```

This lands the skill at
`platform-packs/kotlin/quality-check/bill-kotlin-quality-check/SKILL.md` and edits
the owning pack's `platform.yaml` to register
`declared_quality_check_file: quality-check/bill-kotlin-quality-check/SKILL.md`.
The scaffolded skill links the governed sibling sidecars
`stack-routing.md`, `telemetry-contract.md`, and `shell-ceremony.md`.

### New platform pack

```json
{
  "scaffold_payload_version": "1.0",
  "kind": "platform-pack",
  "platform": "java",
  "display_name": "Java",
  "description": "Use when reviewing Java server and library changes."
}
```

This creates `platform-packs/java/platform.yaml`,
`platform-packs/java/code-review/bill-java-code-review/SKILL.md`, and
`platform-packs/java/quality-check/bill-java-quality-check/SKILL.md`, plus
bare specialist stubs for every approved code-review area. The built-in
`java` preset supplies the routing signals.

### Starter platform pack override

```json
{
  "scaffold_payload_version": "1.0",
  "kind": "platform-pack",
  "platform": "java",
  "skeleton_mode": "starter"
}
```

This creates only the baseline Java pack without the approved specialist
stubs. Direct payload callers can still opt into `starter`, but the
user-facing intake defaults to `full`. The generated files are intentionally
minimal so the user can enrich the authored
sidecars afterwards.

### Governed skill with concrete authored content

```json
{
  "scaffold_payload_version": "1.0",
  "kind": "code-review-area",
  "platform": "kotlin",
  "area": "api-contracts",
  "content_body": "## Focus\n\nReview API boundary regressions.\n\n## Review Guidance\n\n- Prefer client-visible contract issues.\n- Call out backward-compatibility breaks explicitly.\n"
}
```

### Code-review area

```json
{
  "scaffold_payload_version": "1.0",
  "kind": "code-review-area",
  "name": "bill-kotlin-code-review-api-contracts",
  "platform": "kotlin",
  "area": "api-contracts"
}
```

### Add-on

```json
{
  "scaffold_payload_version": "1.0",
  "kind": "add-on",
  "name": "android-paging",
  "platform": "kmp"
}
```

## Loud-Fail Exception Catalog

All exceptions derive from `skillbill.contracts.ShellContentContractException` and the scaffold error types declared in `runtime-contracts`:

- `ScaffoldPayloadVersionMismatchError` — `scaffold_payload_version`
  disagrees with the scaffolder.
- `InvalidScaffoldPayloadError` — missing required key, malformed value, or
  unapproved area slug.
- `UnknownSkillKindError` — `kind` is not one of the supported kinds.
- `UnknownPreShellFamilyError` — pre-shell family not in
  `PRE_SHELL_FAMILIES`.
- `MissingPlatformPackError` — platform pack (`platform-packs/<slug>/`)
  does not exist; create a conforming `platform.yaml` before retrying.
- `MissingSupportingFileTargetError` — a file name declared in
  `RUNTIME_SUPPORTING_FILES` for this skill is not registered in
  `SUPPORTING_FILE_TARGETS`; register the target or drop the reference.
  The scaffolder never silently skips supporting-file symlinks.
- `SkillAlreadyExistsError` — target path already occupied.
- `ScaffoldValidatorError` — post-scaffold validator run failed; all
  staged changes are rolled back.
- `ScaffoldRollbackError` — rollback itself failed (the only failure mode
  that may leave the repo partially mutated).

## SKILL-21: Shell+content split (v1.1)

SKILL-21 added sibling `content.md` authoring and bumped the shell contract
to `1.1`. The output changes:

- Every kind that produces a `SKILL.md` now also writes a sibling
  `content.md` under the same skill directory.
- The generated `SKILL.md` carries a new required `## Execution` H2 whose
  body is byte-identical across every governed skill and links to
  `content.md`.
- End-user creation flows may also provide `content_body` so governed
  skills are concrete at scaffold time instead of requiring later wrapper
  edits.

Loud-fail exceptions added to the shell contract catalog (raised by the
loader and surfaced by the validator, not by the scaffolder itself):

- `ContractVersionMismatchError` — pack declares an outdated
  `contract_version`; the message points at
  `scripts/migrate_to_content_md.md`.
- `MissingContentFileError` — sibling `content.md` is missing for a
  governed skill.
- `InvalidExecutionSectionError` — the `## Execution` section body is not
  the canonical byte-identical form.
- `InvalidCeremonySectionError` — the `## Ceremony` section body drifted
  from the wrapper template.
