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

The repository uses a strict three-layer model:

- `skills/base/` — canonical, user-facing capabilities
- `skills/<platform>/` — platform-specific overrides and approved platform-owned subskills
- `orchestration/` — shared internal routing and decision logic; not user-facing slash commands

Keep shared contracts in `skills/base/`, stack-specific behavior in `skills/<platform>/`, and reusable routing logic in `orchestration/`.

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

## Non-negotiable rules

- Add platform capabilities only as base-capability overrides or approved `code-review-<area>` specializations.
- Add a new package only when behavior is materially different from existing packages.
- Put shared routing logic in `orchestration/`, not in installable skills.
- Preserve stable base entry points even when a platform needs more depth behind the router.
- Keep README skill counts and catalog entries accurate whenever skills change.
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
4. Update `orchestration/stack-routing/PLAYBOOK.md`:
   - add the platform to taxonomy
   - add stack signals
   - add routing decision rules
5. Update base routers if needed:
   - `skills/base/bill-code-review/SKILL.md`
   - `skills/base/bill-quality-check/SKILL.md`
6. Add or update platform overrides, not duplicate base workflows unnecessarily.
7. Update `README.md`:
   - project description if platform support meaningfully changes the pitch
   - current platform list
   - skill catalog
   - naming/enforcement explanation if the allowed shapes changed
8. Update `install.sh` if a rename or migration path is involved.
9. Add tests:
   - validator e2e coverage for accepted and rejected names
   - routing contract coverage when routing behavior changes
10. Run validation before finishing.

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

If someone adds PHP support, the expected shape is:

- base-facing override names such as `bill-php-code-review` or `bill-php-quality-check`
- optional approved code-review subskills such as `bill-php-code-review-security`
- no names like `bill-php-laravel-code-review` unless the naming rules themselves are intentionally changed and validated
