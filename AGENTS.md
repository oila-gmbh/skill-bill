# AGENTS.md

## Project context

skill-bill is a governed suite of AI-agent skills for code review, quality checking, feature implementation, feature verification, and PR description generation. It ships with a local-first SQLite telemetry CLI, an MCP server, and multi-agent installation (Claude Code, Copilot, Codex, OpenCode, GLM). Supported stacks: KMP, backend-Kotlin, Kotlin, PHP, Go, and agent-config.

## Core taxonomy

- `skills/base/` — canonical, user-facing capabilities (both the `bill-code-review` shell and the `bill-quality-check` shell live here)
- `skills/<platform>/` — platform-specific overrides for skills that have not been piloted onto the shell+content contract yet (today: `bill-feature-implement` and `bill-feature-verify`)
- `skills/<platform>/addons/` — governed stack-owned add-on assets that apply only after stack routing
- `platform-packs/<platform>/` — user-owned platform packs for `bill-code-review` and `bill-quality-check` (manifest plus per-area reviewer content and the optional `declared_quality_check_file`, discovered at runtime by each shell)
- `orchestration/` — single source of truth for shared routing, review, delegation, telemetry, and shell+content contracts; skills consume these via sibling symlinks, so edits here change runtime behavior for every linked skill

## Naming rules

- Base skills: `bill-<capability>` (e.g. `bill-code-review`, `bill-feature-implement`)
- Platform overrides: `bill-<platform>-<base-capability>`
- Platform code-review specializations: `bill-<platform>-code-review-<area>`, shipped inside the owning pack at `platform-packs/<platform>/code-review/`
- Approved areas: `architecture`, `performance`, `platform-correctness`, `security`, `testing`, `api-contracts`, `persistence`, `reliability`, `ui`, `ux-accessibility`

## Governed platform packs

- Platform packs live under `platform-packs/<slug>/` and are user-owned — teams are expected to fork or extend them.
- Every pack ships a platform.yaml manifest declaring platform, contract_version, routing_signals, declared_code_review_areas, and declared_files. Packs may also declare the optional `declared_quality_check_file` (a single path string) to register a per-platform quality-check content file. The manifest schema is owned by orchestration/shell-content-contract/PLAYBOOK.md.
- The current shell contract version is 1.0. Packs that declare a different version fail loudly with a contract-version mismatch error; bump the shell constant and every pack together when the contract evolves. The `declared_quality_check_file` key is an additive v1.0 extension: omitting it is valid and packs without the key remain contract-compliant.
- Every declared code-review content file must contain the six required H2 sections: Description, Specialist Scope, Inputs, Outputs Contract, Execution Mode Reporting, Telemetry Ceremony Hooks. Every declared quality-check content file must contain the five required H2 sections: Description, Execution Steps, Fix Strategy, Execution Mode Reporting, Telemetry Ceremony Hooks.
- Loud-fail rules are authoritative: missing manifest, wrong version, missing content file, and missing required section all raise specific named exceptions. Never silently fall back.
- Discovery is manifest-driven: `orchestration/stack-routing/PLAYBOOK.md`, the `bill-code-review` shell, the `bill-quality-check` shell, and the validator all walk `platform-packs/` and read `routing_signals` from each pack. Do not enumerate platform names inline.
- SKILL-14 piloted the split on `bill-code-review`; SKILL-16 piloted the split on `bill-quality-check` via the optional `declared_quality_check_file` manifest key, with an explicit `kmp`/`backend-kotlin` → `kotlin` quality-check fallback. `bill-feature-implement` and `bill-feature-verify` stay on the pre-shell model for now.

## Governed add-ons

- Add-ons are stack-owned supporting assets, not standalone skills.
- Store only under `skills/<platform>/addons/`, flat (no nested directories).
- File names: `<addon-slug>.md` or `<addon-slug>-<area>.md` (lowercase kebab-case).
- Resolve add-ons only after dominant-stack routing selects the owning platform.
- Runtime skills consume add-ons only through sibling supporting files, not repo-relative paths.
- Report selected add-ons explicitly using `Selected add-ons: ...`.
- Every add-on change ships with validator and routing-contract test coverage.

## Non-negotiable rules

- Add platform capabilities only as base-capability overrides or approved code-review-`<area>` specializations, declared in the owning pack's manifest.
- Add a new platform pack under `platform-packs/<slug>/` only when behavior is materially different from existing packs.
- Keep add-ons stack-owned under `skills/<platform>/addons/`; do not promote to top-level packages.
- Use sibling supporting files for runtime-shared contracts instead of repo-relative paths.
- Keep `orchestration/` contracts aligned with sibling supporting-file links.
- Keep dominant-stack routing primary. Apply governed add-ons only after stack routing settles.
- Keep the shell+content contract version (`SHELL_CONTRACT_VERSION`) in lockstep across the shell and every platform pack; bumping one without the others produces a loud-fail `ContractVersionMismatchError`.
- The loud-fail loader is authoritative — do not add silent fallbacks when a platform pack is missing or invalid.
- Keep README.md (user-facing only, do not read for agent context) skill counts and catalog entries accurate.
- Update `install.sh` migration rules in the same change when renaming stack-bound skills.

## Adding a new platform

For code review: create a new platform-packs/`<slug>`/ directory with a conforming platform.yaml and content files (six required H2 sections), wire orchestration sidecar symlinks, update the README catalog, extend platform-pack tests, and run the validation commands below.

For quality-check: add `declared_quality_check_file: quality-check/<skill-name>/SKILL.md` to the owning pack's platform.yaml and ship the content file with the five required H2 sections. Wire the two sibling sidecars (`stack-routing.md`, `telemetry-contract.md`). `kmp` and `backend-kotlin` intentionally omit the key and route to `kotlin`'s quality-check today.

For remaining non-shelled platform skills (`feature-implement`, `feature-verify`): place them under skills/`<platform>`/ using the historic naming rules — those families have not been piloted onto the shell+content contract yet.

## New-skill authoring

- All new skills go through the scaffolder: `skill-bill new-skill --payload <file>` (or `--interactive`, or invoke `/bill-new-skill-all-agents` inside an agent). The scaffolder is the only supported path; hand-authoring is discouraged.
- Four supported `kind` values and their destinations:
  - `horizontal` → `skills/base/<name>/SKILL.md`.
  - `platform-override-piloted` (shelled family `code-review`) → `platform-packs/<slug>/code-review/<name>/SKILL.md` + `platform.yaml` edit.
  - `platform-override-piloted` (shelled family `quality-check`) → `platform-packs/<slug>/quality-check/<name>/SKILL.md` + `platform.yaml` edit registering `declared_quality_check_file`.
  - `platform-override-piloted` (pre-shell families `feature-implement`, `feature-verify`) → `skills/<platform>/<name>/SKILL.md`, annotated with an interim-location note ("will move when piloted").
  - `code-review-area` → `platform-packs/<slug>/code-review/<name>/SKILL.md` + manifest area registration.
  - `add-on` → `skills/<platform>/addons/<name>.md` (flat).
- Pre-shell families are defined in `skill_bill/constants.py::PRE_SHELL_FAMILIES`. Adding one requires updating that tuple and `skill_bill/scaffold.py::FAMILY_REGISTRY` in the same change.
- Scaffolder entry point: `skill_bill/scaffold.py`. Payload schema and exception catalog: `orchestration/shell-content-contract/SCAFFOLD_PAYLOAD.md`.
- The scaffolder is atomic. Validator failure, manifest-write failure, and symlink-creation failure all trigger a full rollback and raise a named exception; the repo tree is byte-identical to its pre-run state.
- The two scaffolder-owned sections (`## Execution Mode Reporting`, `## Telemetry Ceremony Hooks`) are emitted from a stored template and must be byte-identical across every specialist in a family. Do not hand-edit them.

## Quality-check guidance

Prefer routing through `bill-quality-check`. If a platform-specific checker does not exist yet, document the fallback explicitly instead of implying dedicated coverage exists.

## Preferred design bias

- stable base commands for users
- platform depth behind the router
- explicit overrides rather than clever implicit conventions
- validator-backed rules instead of tribal knowledge
- tests for both acceptance and rejection paths

## Validation commands

```bash
.venv/bin/python3 -m unittest discover -s tests
npx --yes agnix --strict .
.venv/bin/python3 scripts/validate_agent_configs.py
```
