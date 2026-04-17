# AGENTS.md

## Project context

skill-bill is a governed suite of AI-agent skills for code review, quality checking, feature implementation, feature verification, and PR description generation. It ships with a local-first SQLite telemetry CLI, an MCP server, and multi-agent installation (Claude Code, Copilot, Codex, OpenCode, GLM). Supported stacks: KMP, backend-Kotlin, Kotlin, Go, and agent-config.

## Core taxonomy

- `skills/base/` — canonical, user-facing capabilities (the `bill-code-review` shell lives here)
- `skills/<platform>/` — platform-specific overrides for skills that have not been piloted onto the shell+content contract yet (quality-check today)
- `skills/<platform>/addons/` — governed stack-owned add-on assets that apply only after stack routing
- `platform-packs/<platform>/` — user-owned platform packs for `bill-code-review` (manifest plus per-area reviewer content, discovered at runtime by the shell)
- `orchestration/` — single source of truth for shared routing, review, delegation, telemetry, and shell+content contracts; skills consume these via sibling symlinks, so edits here change runtime behavior for every linked skill

## Naming rules

- Base skills: `bill-<capability>` (e.g. `bill-code-review`, `bill-feature-implement`)
- Platform overrides: `bill-<platform>-<base-capability>`
- Platform code-review specializations: `bill-<platform>-code-review-<area>`, shipped inside the owning pack at `platform-packs/<platform>/code-review/`
- Approved areas: `architecture`, `performance`, `platform-correctness`, `security`, `testing`, `api-contracts`, `persistence`, `reliability`, `ui`, `ux-accessibility`

## Governed platform packs

- Platform packs live under `platform-packs/<slug>/` and are user-owned — teams are expected to fork or extend them.
- Every pack ships a platform.yaml manifest declaring platform, contract_version, routing_signals, declared_code_review_areas, and declared_files. The manifest schema is owned by orchestration/shell-content-contract/PLAYBOOK.md.
- The current shell contract version is 1.0. Packs that declare a different version fail loudly with a contract-version mismatch error; bump the shell constant and every pack together when the contract evolves.
- Every declared content file must contain the six required H2 sections: Description, Specialist Scope, Inputs, Outputs Contract, Execution Mode Reporting, Telemetry Ceremony Hooks.
- Loud-fail rules are authoritative: missing manifest, wrong version, missing content file, and missing required section all raise specific named exceptions. Never silently fall back.
- Discovery is manifest-driven: `orchestration/stack-routing/PLAYBOOK.md`, the `bill-code-review` shell, and the validator all walk `platform-packs/` and read `routing_signals` from each pack. Do not enumerate platform names inline.
- SKILL-14 piloted the split on `bill-code-review` only. `bill-quality-check`, `bill-feature-implement`, and `bill-feature-verify` stay on the pre-shell model for now.

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

For non-code-review platform skills (e.g. quality-check): place them under skills/`<platform>`/ using the historic naming rules — the shell+content pilot is currently scoped to bill-code-review only.

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
