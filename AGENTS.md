# AGENTS.md

## Project context

skill-bill is a governed suite of AI-agent skills for code review, quality checking, feature implementation, feature verification, and PR description generation. It ships with a local-first SQLite telemetry CLI, an MCP server, and multi-agent installation (Claude Code, Copilot, Codex, OpenCode, GLM). Supported stacks: KMP, backend-Kotlin, Kotlin, Go, and agent-config.

## Core taxonomy

- `skills/base/` — canonical, user-facing capabilities
- `skills/<platform>/` — platform-specific overrides and approved platform-owned subskills
- `skills/<platform>/addons/` — governed stack-owned add-on assets that apply only after stack routing
- `orchestration/` — single source of truth for shared routing, review, delegation, and telemetry contracts; skills consume these via sibling symlinks, so edits here change runtime behavior for every linked skill

## Naming rules

- Base skills: `bill-<capability>` (e.g. `bill-code-review`, `bill-feature-implement`)
- Platform overrides: `bill-<platform>-<base-capability>`
- Platform code-review specializations: `bill-<platform>-code-review-<area>`
- Approved areas: `architecture`, `performance`, `platform-correctness`, `security`, `testing`, `api-contracts`, `persistence`, `reliability`, `ui`, `ux-accessibility`

## Governed add-ons

- Add-ons are stack-owned supporting assets, not standalone skills.
- Store only under `skills/<platform>/addons/`, flat (no nested directories).
- File names: `<addon-slug>.md` or `<addon-slug>-<area>.md` (lowercase kebab-case).
- Resolve add-ons only after dominant-stack routing selects the owning platform.
- Runtime skills consume add-ons only through sibling supporting files, not repo-relative paths.
- Report selected add-ons explicitly using `Selected add-ons: ...`.
- Every add-on change ships with validator and routing-contract test coverage.

## Non-negotiable rules

- Add platform capabilities only as base-capability overrides or approved `code-review-<area>` specializations.
- Add a new package only when behavior is materially different from existing packages.
- Keep add-ons stack-owned under `skills/<platform>/addons/`; do not promote to top-level packages.
- Use sibling supporting files for runtime-shared contracts instead of repo-relative paths.
- Keep `orchestration/` contracts aligned with sibling supporting-file links.
- Keep dominant-stack routing primary. Apply governed add-ons only after stack routing settles.
- Keep README.md (user-facing only, do not read for agent context) skill counts and catalog entries accurate.
- Update `install.sh` migration rules in the same change when renaming stack-bound skills.

## Adding a new platform

Only add a new platform package when there is real platform-specific behavior that cannot be expressed with existing packages. Before adding: confirm distinct review/validation behavior is needed, skills are true overrides of base capabilities, and the routing taxonomy recognizes the new platform.

When adding: place under `skills/<platform>/`, use allowed naming forms, update `scripts/validate_agent_configs.py` (ALLOWED_PACKAGES + validation logic), update orchestration contracts (stack-routing, review-orchestrator, review-delegation, telemetry-contract), update base routers if needed, update README.md catalog, update `install.sh` if renaming, and add tests (validator e2e + routing contract coverage). Run validation before finishing.

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
