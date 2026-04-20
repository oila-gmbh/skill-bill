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

- Packs live under `platform-packs/`. Repo ships `kotlin` and `kmp` as first-party references.
- Each pack ships a manifest per the shell-content-contract playbook under `orchestration/`.
- The current shell contract version is 1.1 (SKILL-21); drift loud-fails pointing at `scripts/migrate_to_content_md.py`.
- Code-review H2s: Description, Specialist Scope, Inputs, Outputs Contract, Execution, Execution Mode Reporting, Telemetry Ceremony Hooks.
- Quality-check H2s: Description, Execution Steps, Fix Strategy, Execution, Execution Mode Reporting, Telemetry Ceremony Hooks.
- SKILL.md is generated; every governed skill has a sibling `content.md` with the author-owned body. Edit `content.md` only. It holds author skill knowledge (signals, rubrics, routing tables, project rules); shell ceremony stays in SKILL.md. Taxonomy + blacklist in `orchestration/shell-content-contract/PLAYBOOK.md`.
- Missing manifest, wrong version, missing file, missing section, missing `content.md`, or edited `## Execution` body must raise the named loud-fail exception.
- Discovery is manifest-driven (`routing_signals`), not hard-coded.
- `kmp` routes quality-check to `kotlin`. `bill-feature-implement`/`bill-feature-verify` remain pre-shell.

## Governed add-ons

- Add-ons are pack-owned files, not standalone skills. Keep them flat in the owning pack's `addons/` directory, use lowercase kebab-case names, and resolve them only after dominant-stack routing.
- Runtime skills consume add-ons through sibling supporting files, report them as `Selected add-ons: ...`, and every add-on change needs validator plus routing-contract coverage.

## Non-negotiable rules

- Add platform behavior only as manifest-declared overrides or approved code-review areas.
- Add a new pack only when behavior materially differs from an existing pack.
- Keep add-ons pack-owned, use sibling supporting files for shared contracts, and keep `orchestration/` aligned with those links.
- Route by dominant stack first, then apply governed add-ons.
- Keep `SHELL_CONTRACT_VERSION` in lockstep across shell and packs, and treat the loud-fail loader as authoritative.
- Keep `README.md` catalog data accurate and update `install.sh` migration rules when renaming stack-bound skills.

## Adding a new platform

1. Code review: create pack root, add conforming manifest + content, wire sidecars, update README catalog, extend pack tests, run validation.
2. Quality-check: register the pack's quality-check skill in the manifest, ship the five contract H2 sections, wire routing + telemetry sidecars. `kmp` still falls back to `kotlin`.
3. Pre-shell families (`feature-implement`, `feature-verify`): keep the historic `skills/<platform>/` layout until those families are piloted.

## New-skill authoring

- Use the scaffolder: `skill-bill new-skill --payload <file>`, `--interactive`, or `/bill-skill-scaffold`.
- `kind` values: `horizontal`, `platform-override-piloted`, `code-review-area`, `platform-pack`, `add-on`. See `orchestration/shell-content-contract/SCAFFOLD_PAYLOAD.md` for the schema, `PRE_SHELL_FAMILIES` in `skill_bill/constants.py` for the pre-shell list, and `skill_bill/scaffold.py` for the entry point.
- Scaffolder is atomic: validator, manifest-write, or symlink failures roll the repo back byte-for-byte (including the sibling `content.md`).
- `## Execution`, `## Execution Mode Reporting`, `## Telemetry Ceremony Hooks` are stored templates and stay byte-identical within a family.
- Optional `content_body` payload field: verbatim when present, deterministic placeholder when absent.

## SKILL.md vs. content.md (v1.1)

- SKILL.md: generated shell with version-stamped frontmatter and a byte-identical `## Execution` body. Open `content.md` with `skill-bill edit <name>`.
- `skill-bill upgrade` regenerates stale shells and leaves `content.md` untouched. `skill-bill doctor` reports missing content.md (error) and template drift (warning).
- Migrate v1.0 with `scripts/migrate_to_content_md.py` (idempotent, backup-first, per-skill rollback).

## Quality-check guidance

Prefer `bill-quality-check` routing. Always document an explicit fallback when a platform-specific checker is missing. `kmp` still falls back to `kotlin` in the built-in set.

## Design bias

- stable base commands; platform depth behind the router; explicit overrides; validator-backed rules; tests for accept + reject paths.

## Validation commands

```bash
.venv/bin/python3 -m unittest discover -s tests
npx --yes agnix --strict .
.venv/bin/python3 scripts/validate_agent_configs.py
```
