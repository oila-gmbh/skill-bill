# AGENTS.md

## Project context

skill-bill is a governed system for authoring, routing, validating, installing, and measuring AI-agent skills. It ships shared orchestration, validators, installers, scaffolding, telemetry, and stable base shells for review, quality checks, feature work, feature verification, and PR descriptions. Platform packs live under `platform-packs/` and are discovered dynamically through their governed manifests.

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
- Skill Bill is platform-extensible: any team may author and ship a new conforming pack.
- This repo may contain any maintained pack that follows the governed contract; routing and install flows must stay manifest-driven rather than relying on a hardcoded shortlist.
- Each pack ships a manifest. The schema lives in the shell-content-contract playbook under `orchestration/`.
- The current shell contract version is 1.1. Keep it locked across the shell and every pack; version drift must loud-fail.
- Follow `orchestration/shell-content-contract/PLAYBOOK.md` for governed skill shape.
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
- Keep `README.md` catalog data accurate and keep discovery/install flows dynamic when packs are added or removed.

## Adding a new platform

1. For code review, create the new pack root, add a conforming manifest and content, wire the sidecars, update the README catalog, extend pack tests, and run validation.
2. For quality-check, register the manifest entry and ship the governed wrapper plus sidecars. `kmp` still falls back to `kotlin`.
3. For pre-shell families (`feature-implement`, `feature-verify`), keep using the historic `skills/<platform>/` layout until those families are piloted.

## New-skill authoring

- Use the scaffolder for all new skills. Preferred entrypoints are `skill-bill new --interactive`, `skill-bill new --payload <file>`, or `/bill-create-skill`. `skill-bill new-skill` remains the lower-level command name behind `new`.
- For ordinary skill authoring and refinement, use the `skill-bill` CLI instead of manually editing governed skill files.
- Preferred loop: `skill-bill new --interactive` (or `create-and-fill` for one content-managed skill), `skill-bill show <skill-name>`, `skill-bill edit <skill-name>` or `fill <skill-name>`, `skill-bill doctor skill <skill-name>`, `skill-bill validate --skill-name <skill-name>`, then `skill-bill render --skill-name <skill-name>` when wrapper templates change.
- `skill-bill fill <skill-name>` is the scripted/agent-safe write path for authored content. Feed it `--body` or `--body-file`; combine it with `--section <heading>` when only one authored H2 section should change.
- `skill-bill edit <skill-name> --section <heading>` is the targeted interactive path when only one authored section needs refinement.
- `skill-bill show <skill-name>` and `skill-bill explain [<skill-name>]` are the preferred stable read paths for agents; do not grep or hand-parse governed wrappers unless you are changing the scaffold system itself.
- Treat `content.md` as the primary authored surface for governed skills. Do not manually edit generated `SKILL.md` files during normal authoring.
- Direct `SKILL.md` edits are maintainer-only work and are allowed only when intentionally changing the shared wrapper, scaffold, render, or migration system itself.
- The default intake is contextual, not a top-level taxonomy dump. Start with `Platform name:` and branch from there.
- New platform packs always scaffold the pack root plus baseline `bill-<platform>-code-review` and `bill-<platform>-quality-check`. The interactive flow then lets the author choose `none`, `custom subset`, or `all` for approved code-review specialist stubs. It does not auto-create `feature-implement` or `feature-verify` overrides.
- `create-and-fill` is only for one content-managed skill at a time. Do not use it for horizontal skills, pre-shell overrides, or platform-pack bootstrap flows.
- Supported `kind` values:
  - `horizontal`: create a canonical skill under `skills/`.
  - `platform-pack`: create a new `platform-packs/<slug>/` root with a rendered manifest, baseline `code-review`, default `quality-check`, and optional approved specialist stubs.
  - `platform-override-piloted`: create the skill in the selected pack, updating its manifest; for `quality-check`, register `declared_quality_check_file`.
  - `platform-override-piloted` for pre-shell families: create the skill in the platform's legacy `skills/` location and note that it will move when piloted.
  - `code-review-area`: create the specialist in the selected pack and register the area.
  - `add-on`: create a flat add-on file in the selected platform pack's `addons/` directory.
- For `platform-pack`, payloads may either use `skeleton_mode=starter|full` or provide `specialist_areas=[...]` for a custom approved subset, but not both.
- Pre-shell families are defined in the Kotlin scaffold/runtime contract. Keep payload schema and exception catalog aligned with `orchestration/shell-content-contract/SCAFFOLD_PAYLOAD.md`.
- Entry point: the Kotlin `skill-bill new` / `create-and-fill` commands. Payload schema and exception catalog live in `orchestration/shell-content-contract/SCAFFOLD_PAYLOAD.md`.
- The scaffolder is atomic. Validator, manifest-write, or symlink failures must roll the repo back byte-for-byte.
- Keep it byte-identical across governed skills on the same shell contract.

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
(cd runtime-kotlin && ./gradlew check)
npx --yes agnix --strict .
scripts/validate_agent_configs
```
