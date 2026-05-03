# Skill Remove Content

## Description

Use this skill when removing an existing Skill Bill skill, add-on, platform override, code-review area, or an entire scaffolded platform pack.

The job is to remove the target safely and completely:

- identify what kind of thing the user wants removed from the filesystem layout rather than making them speak in repo taxonomy
- accept a bare platform slug such as `java` as shorthand for "remove everything scaffolded for this platform"
- remove the repo content
- remove any manifest entries or catalog rows that must change with it
- remove agent symlinks for the deleted skill directories
- run the repo validator and report any follow-up cleanup still required

Prefer precise deletion over broad cleanup. Do not run `uninstall.sh` unless the user explicitly wants to remove all Skill Bill installs from all agents.

## Specialist Scope

This skill covers these removal scopes:

- horizontal skill under `skills/<name>/`
- pre-shell platform override under `skills/<platform>/<name>/`
- shelled quality-check override under `platform-packs/<platform>/quality-check/<name>/`
- code-review area under `platform-packs/<platform>/code-review/<name>/`
- governed add-on under `platform-packs/<platform>/addons/<name>.md`
- full scaffolded platform pack under `platform-packs/<platform>/` plus its paired `skills/<platform>/` pre-shell stubs

Default behavior:

- infer the scope from the target path or skill name
- if the user provides only a platform slug, treat that as a request to remove the full scaffolded platform pack plus paired pre-shell stubs for that platform
- remove only the requested target
- preserve unrelated built-in packs and skills

Escalate before proceeding if the request would remove built-in first-party shipped surfaces such as `kotlin` or `kmp`, because that also requires docs, catalog, and test updates beyond ordinary scaffold cleanup.

## Inputs

Collect only what is needed:

- target to remove: skill name, path, platform slug, or "remove this platform pack"
- whether the user wants one skill removed or the whole platform pack
- whether agent installs should be cleaned up too

Interpretation rule: a bare platform slug like `java` means "remove everything for this platform" unless the user explicitly narrows it to one skill or one file.

Infer the rest from repo structure:

- `skills/<name>/` with no platform segment means horizontal skill
- `skills/<platform>/bill-<platform>-feature-*` means pre-shell platform override
- `platform-packs/<platform>/code-review/<skill>/` means code-review baseline or area
- `platform-packs/<platform>/quality-check/<skill>/` means shelled quality-check override
- `platform-packs/<platform>/addons/<name>.md` means governed add-on
- `platform-packs/<platform>/platform.yaml` plus sibling review/quality-check directories implies platform-pack scope

When a platform slug is given, the default delete set is:

- `platform-packs/<platform>/`
- `skills/<platform>/`
- matching agent symlinks for skill directories that live under those trees

Use repo-local Kotlin-backed commands for helper commands and validation:

- prefer repo-local scripts such as `scripts/validate_agent_configs`
- for Skill Bill CLI operations, use the repo-provided `skill-bill` launcher path already active in the working tree

## Outputs Contract

Return a short, concrete removal report:

- inferred scope
- repo paths removed
- manifest or README entries updated
- agent symlinks removed
- validation commands run and whether they passed
- any remaining manual follow-up

When the request is ambiguous, stop before deletion and ask one focused clarifying question. When deletion is performed, be explicit about whether it was complete or partial.

## Removal Workflow

1. Resolve scope from the target and inspect the real files before deleting anything. If the target is a bare platform slug, first check for `platform-packs/<platform>/` and `skills/<platform>/`. If both are absent, stop and report that nothing matching that platform exists.
2. Build the exact delete set.
3. Remove agent symlinks for every deleted skill directory: supported install roots are managed through `skill_bill/install.py`. Remove only symlinks matching the deleted skill directory names.
4. Remove repo content with `apply_patch` for tracked files and directories.
5. Update owning metadata:
   - horizontal skill: remove its README catalog row and section count
   - code-review area: remove the area from `declared_code_review_areas` and `declared_files.areas` in the owning `platform.yaml`
   - quality-check override: remove `declared_quality_check_file` from the owning `platform.yaml`
   - platform pack: remove the `platform-packs/<platform>/` tree and the paired `skills/<platform>/` tree; only update docs if that platform is documented as shipped
6. Run `scripts/validate_agent_configs`.
7. If available and relevant, run targeted tests for the touched validation or scaffold paths.

Guardrails:

- never remove `.bill-shared` or every installed skill unless the user explicitly asked for a full uninstall
- never silently leave stale manifest entries behind
- never leave README catalog counts wrong for horizontal skills
- do not delete governed add-ons by removing the whole pack unless that was the actual request

## Execution Mode Reporting

When this skill runs, report the execution mode on its own line:

```
Execution mode: inline | delegated
```

- `inline` — the current agent handled the work directly.
- `delegated` — the current agent dispatched the work to a specialist subagent or a sibling skill.
