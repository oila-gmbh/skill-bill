# AGENTS.md

## Project context

skill-bill is a governed system for authoring, routing, validating, installing, and measuring AI-agent skills. The core product is the shared orchestration playbooks, validators, installers, scaffolder, telemetry, and stable base shells for code review, quality checks, feature work, feature verification, and PR descriptions. This repo keeps only two built-in reference platform packs: `kotlin` and `kmp`. Other stacks should be authored as separate platform packs with the scaffolder.

## Core taxonomy

- `skills/` holds canonical user-facing skills, including the `bill-code-review` and `bill-quality-check` shells.
- `skills/<platform>/` holds pre-shell platform overrides such as `bill-feature-implement` and `bill-feature-verify`.
- `platform-packs/<platform>/addons/` holds pack-owned add-ons applied after routing.
- `platform-packs/<platform>/` holds user-owned packs for code review and quality-check behavior.
- `orchestration/` is the shared source of truth for routing, review, delegation, telemetry, and shell contracts.

## Naming rules

- Base skills: `bill-<capability>`
- Platform overrides: `bill-<platform>-<base-capability>`
- Platform code-review specializations: `bill-<platform>-code-review-<area>`
- Approved areas: `architecture`, `performance`, `platform-correctness`, `security`, `testing`, `api-contracts`, `persistence`, `reliability`, `ui`, `ux-accessibility`

## Governed platform packs

- Packs live under `platform-packs/` and are user-owned.
- This repo ships `kotlin` and `kmp` as the first-party reference implementations for the governed pack model.
- Each pack ships a manifest. The schema lives in the shell-content-contract playbook under `orchestration/`.
- The current shell contract version is 1.0. Keep it locked across the shell and every pack; version drift must loud-fail.
- Code-review content needs six contract H2s: Description, Specialist Scope, Inputs, Outputs Contract, Execution Mode Reporting, Telemetry Ceremony Hooks.
- Quality-check content needs five contract H2s: Description, Execution Steps, Fix Strategy, Execution Mode Reporting, Telemetry Ceremony Hooks.
- Missing manifest, wrong version, missing content file, or missing section must raise the named loud-fail exceptions. Do not add silent fallback.
- Discovery is manifest-driven. The shells, routing playbook, and validator read `routing_signals` from pack manifests instead of hard-coding platform names.
- `kmp` currently routes quality-check work to `kotlin`. `bill-feature-implement` and `bill-feature-verify` remain pre-shell.

## Governed add-ons

- Add-ons are pack-owned files, not standalone skills.
- Keep them flat in the owning pack's `addons/` directory, use lowercase kebab-case names, and resolve them only after dominant-stack routing.
- Runtime skills consume add-ons through sibling supporting files, report them as `Selected add-ons: ...`, and every add-on change needs validator plus routing-contract coverage.

## Non-negotiable rules

- Add platform behavior only as manifest-declared overrides or approved code-review areas.
- Add a new pack only when behavior materially differs from an existing pack.
- Keep add-ons pack-owned, use sibling supporting files for shared contracts, and keep `orchestration/` aligned with those links.
- Route by dominant stack first, then apply governed add-ons.
- Keep `SHELL_CONTRACT_VERSION` in lockstep across shell and packs, and treat the loud-fail loader as authoritative.
- Keep `README.md` catalog data accurate and update `install.sh` migration rules when renaming stack-bound skills.

## Adding a new platform

1. For code review, create the new pack root, add a conforming manifest and content, wire the sidecars, update the README catalog, extend pack tests, and run validation.
2. For quality-check, register the pack's quality-check skill in the manifest, ship the five contract H2 sections, and wire the routing plus telemetry sidecars. The built-in `kmp` reference pack still falls back to `kotlin`.
3. For pre-shell families (`feature-implement`, `feature-verify`), keep using the historic `skills/<platform>/` layout until those families are piloted.

## New-skill authoring

- Use the scaffolder for all new skills: `skill-bill new-skill --payload <file>`, `--interactive`, or `/bill-skill-scaffold`.
- Supported `kind` values:
  - `horizontal`: create a canonical skill under `skills/`.
  - `platform-override-piloted`: create the skill in the selected pack, updating its manifest; for `quality-check`, register `declared_quality_check_file`.
  - `platform-override-piloted` for pre-shell families: create the skill in the platform's legacy `skills/` location and note that it will move when piloted.
  - `code-review-area`: create the specialist in the selected pack and register the area.
  - `add-on`: create a flat add-on file in the selected platform pack's `addons/` directory.
- Pre-shell families are defined in `skill_bill/constants.py`; add the family there and in `skill_bill/scaffold.py` together.
- Entry point: `skill_bill/scaffold.py`. Payload schema and exception catalog live in `orchestration/shell-content-contract/SCAFFOLD_PAYLOAD.md`.
- The scaffolder is atomic. Validator, manifest-write, or symlink failures must roll the repo back byte-for-byte.
- `## Execution Mode Reporting` and `## Telemetry Ceremony Hooks` come from a stored template and stay byte-identical within a family.

## Quality-check guidance

Prefer routing through `bill-quality-check`. If a platform-specific checker does not exist yet, document the fallback explicitly. In the built-in set, `kmp` still falls back to `kotlin`.

## Preferred design bias

- stable base commands
- platform depth behind the router
- explicit overrides
- validator-backed rules
- tests for acceptance and rejection paths

## Validation commands

```bash
.venv/bin/python3 -m unittest discover -s tests
npx --yes agnix --strict .
.venv/bin/python3 scripts/validate_agent_configs.py
```
