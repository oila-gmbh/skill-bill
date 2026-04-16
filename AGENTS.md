# AGENTS.md

This file is the project-wide guidance for AI agents working in this repository.

## Purpose

Treat this repo as governed skill infrastructure, not a loose prompt collection.

The goal is to keep the skill suite:

- focused
- composable
- portable across agents
- safe to extend without naming drift or structural entropy

## Core taxonomy

The repository uses a strict governed model:

- `skills/base/` — canonical, user-facing capabilities
- `skills/<platform>/` — platform-specific overrides and approved platform-owned subskills
- `skills/<platform>/addons/` — governed stack-owned add-on assets that apply only after stack routing
- `orchestration/` — maintainer-facing reference snapshots for shared routing, review, delegation, and telemetry contracts; not a runtime dependency for installed skills

## Naming rules

### Base skills

Base skills are flexible, but they must stay neutral:

- allowed shape: `bill-<capability>`
- examples: `bill-code-review`, `bill-quality-check`, `bill-feature-implement`

### Platform skills

Platform skills are strict:

- override shape: `bill-<platform>-<base-capability>`
- approved deeper specialization shape: `bill-<platform>-code-review-<area>`

Use only these two platform naming patterns unless the taxonomy itself is intentionally expanded.

### Approved `code-review` areas

- `architecture`
- `performance`
- `platform-correctness`
- `security`
- `testing`
- `api-contracts`
- `persistence`
- `reliability`
- `ui`
- `ux-accessibility`

## Governed add-ons

- Add-ons are stack-owned supporting assets, not standalone skills.
- Store add-ons only under `skills/<platform>/addons/`.
- Keep add-on files flat inside `addons/`; do not create nested add-on directories.
- Use lowercase kebab-case file names in one of these forms:
  - `<addon-slug>.md`
  - `<addon-slug>-<area>.md`
- Conventional area names such as `implementation` and `review` are allowed when they are the governed add-on facets.
- The shared `<addon-slug>` identifies one governed add-on. Area-scoped files refine that add-on only when materially needed.
- Add-ons do not create new packages, slash commands, or install targets by default.
- Resolve add-ons only after dominant-stack routing chooses the owning platform. Keep add-on detection owned by that platform and keep add-ons out of top-level stack classification.
- Runtime-facing skills may consume governed add-ons only through sibling supporting files inside the consuming skill directory; do not rely on repo-relative runtime paths.
- When an add-on is selected, report it explicitly in routing or review output using `Selected add-ons: ...`.
- Every add-on change ships with validator coverage for accepted and rejected paths plus routing-contract coverage for detection/reporting behavior.

## Non-negotiable rules

- Add platform capabilities only as base-capability overrides or approved `code-review-<area>` specializations.
- Add a new package only when behavior is materially different from existing packages.
- Keep add-ons stack-owned under `skills/<platform>/addons/`; do not promote them to top-level packages.
- Runtime-facing skills may reference sibling supporting files inside the same skill directory.
- Use sibling supporting files for runtime-shared routing, review, delegation, and telemetry contracts instead of repo-relative or install-root-relative playbook paths.
- Use sibling supporting-file links for governed add-on assets too; do not wire runtime add-on reads directly to repo-relative paths.
- Keep `orchestration/` snapshots aligned with the sibling supporting-file contracts when shared routing, review, delegation, or telemetry behavior changes.
- Preserve stable base entry points even when a platform needs more depth behind the router.
- Keep dominant-stack routing primary. Apply governed add-ons only after stack routing settles on the owning package.
- Keep README.md (user-facing only, do not read for agent context) skill counts and catalog entries accurate whenever skills change.
- Update `install.sh` migration rules in the same change when renaming stack-bound skills.

## Adding a new platform or language

Only add a new platform package when there is real platform-specific behavior, heuristics, or tooling that cannot be expressed cleanly with existing packages.

### Platform decision checklist

Before adding a new package, confirm:

1. the platform needs distinct review or validation behavior
2. the new skills are true overrides of existing base capabilities, not random new commands
3. the routing taxonomy recognizes the new platform as a first-class package

### Platform implementation checklist

When adding a new platform or language package:

1. Add the package under `skills/<platform>/`.
2. Keep names in one of the allowed platform forms:
   - `bill-<platform>-<base-capability>`
   - `bill-<platform>-code-review-<approved-area>`
3. Update `scripts/validate_agent_configs.py`:
   - `ALLOWED_PACKAGES`
   - any package-specific validation logic
   - any tests or assumptions tied to current package names
4. Update maintainer reference snapshots when shared routing, review, or telemetry behavior changes:
   - `orchestration/stack-routing/PLAYBOOK.md`
   - `orchestration/review-orchestrator/PLAYBOOK.md`
   - `orchestration/review-delegation/PLAYBOOK.md`
   - `orchestration/telemetry-contract/PLAYBOOK.md`
5. Update base routers if needed:
   - `skills/base/bill-code-review/SKILL.md`
   - `skills/base/bill-quality-check/SKILL.md`
6. Add or update platform overrides, not duplicate base workflows unnecessarily.
7. If the platform needs governed add-ons, place them under `skills/<platform>/addons/`, keep names flat, and update validator/routing/reporting coverage in the same change.
8. Update `README.md` (user-facing only, do not read for agent context):
   - project description if platform support meaningfully changes the pitch
   - current platform list
   - skill catalog
   - naming/enforcement explanation if the allowed shapes changed
9. Update `install.sh` if a rename or migration path is involved.
10. Add tests:
   - validator e2e coverage for accepted and rejected names
   - routing contract coverage when routing behavior changes
11. Run validation before finishing.

## Quality-check guidance

Prefer routing through `bill-quality-check` from shared workflows.

If a new platform does not yet need a dedicated quality-check implementation, it may temporarily fall back to an existing implementation, but that fallback should be explicit in router docs and easy to remove later.

If a platform-specific checker does not exist yet, document the fallback explicitly instead of implying dedicated coverage exists.

## Preferred design bias

- stable base commands for users
- platform depth behind the router
- explicit overrides rather than clever implicit conventions
- validator-backed rules instead of tribal knowledge
- tests for both acceptance and rejection paths

## Validation commands

Run these after taxonomy, docs, routing, or skill changes:

```bash
python3 -m unittest discover -s tests
npx --yes agnix --strict .
python3 scripts/validate_agent_configs.py
```

## Practical example

If someone adds a new platform package, the expected shape is:

- base-facing override names such as `bill-acme-code-review` or `bill-acme-quality-check`
- optional approved code-review subskills such as `bill-acme-code-review-security`
- no names like `bill-acme-framework-code-review` unless the naming rules themselves are intentionally changed and validated
