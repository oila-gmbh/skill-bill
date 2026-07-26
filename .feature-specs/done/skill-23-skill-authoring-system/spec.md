# Feature: skill-23-skill-authoring-system
Created: 2026-04-20
Status: In Progress
Sources: User request in chat; Jira issue key SKILL-23; current repo taxonomy in AGENTS.md; existing feature-spec format used by `bill-feature-implement`

## Acceptance Criteria
1. Users can create one concrete skill from the terminal without manually navigating the repo or understanding project ceremony.
2. Each authored skill is split into a generated `SKILL.md` and a user-authored `content.md`, with a strict ownership boundary between project/runtime behavior and business behavior.
3. `content.md` does not contain project-enforced output format, shell ceremony, override mechanics, or other runtime internals that must remain stable for the system to work.
4. The authoring workflow enforces the repo's naming taxonomy, package rules, and approved `code-review` specialization areas during both creation and editing.
5. Users can edit an existing skill from the terminal through a guided workflow, with optional `$EDITOR` support when available, without requiring direct manual edits to `SKILL.md`.
6. Validation detects incomplete content, unresolved TODO placeholders, invalid names/paths, and drift between generated `SKILL.md` files and their templates.
7. The system defines how new skills are scaffolded, rendered, validated, discovered, and synced to agent install targets without breaking the current installer/symlink model.
8. The spec defines migration rules for existing skills and explicitly distinguishes end-user workflows from maintainer-only bulk or taxonomy-changing workflows.

---

## Problem Statement

The repo is evolving from "skills are hand-authored markdown files" to "skills are governed artifacts with protected runtime ceremony and user-editable business content."

That split solves one problem cleanly:

- `SKILL.md` can hold the fixed project behavior the system needs in order to work
- `content.md` can hold the domain-specific behavior users should actually customize

But the current authoring experience still assumes repo-level manual editing. That creates three failures:

1. users have to understand the repo layout and internal conventions
2. terminal-first users are pushed toward ad hoc file editing instead of a guided workflow
3. the scaffolding model is optimized for generating repo structure, not for helping a user create one concrete skill quickly

SKILL-23 defines a terminal-first skill authoring system that makes the split useful in practice.

## Goals

- Make skill creation a command-driven workflow rather than a repo-navigation workflow.
- Preserve the `SKILL.md` and `content.md` boundary as a hard system rule.
- Keep base commands and governed taxonomy stable.
- Let users author business behavior without learning Timetry, project overrides, or other internals.
- Support an iterative terminal workflow for both creating and updating skills.
- Fail loudly when the authored content is incomplete or the generated artifact drifts from the template.

## Non-Goals

- Building a full-screen TUI before the basic CLI workflow exists.
- Letting users directly author runtime/output contracts in `content.md`.
- Generating the full platform/capability matrix by default for ordinary users.
- Replacing the existing install/symlink model with a compiled packaging system.
- Allowing new taxonomy shapes outside the governed naming rules in `AGENTS.md`.

## Core Product Decisions

1. Users create concrete skills one at a time.
2. `SKILL.md` is generated and treated as protected.
3. `content.md` is the primary editable surface.
4. Terminal-first is the default workflow; IDE editing is optional acceleration, not a requirement.
5. Bulk generation of every possible skill remains a maintainer-only workflow, not the primary end-user path.

## User Roles

### End User

Wants to create or edit a skill that fits the governed taxonomy, but should only need to answer business questions such as:

- What is this skill for?
- When should it be used?
- What context does it need?
- What rules should it follow?
- What examples describe correct behavior?

The end user should not need to know:

- shell ceremony details
- output formatting contracts
- override lookup mechanics
- install-path or symlink internals
- which sections in `SKILL.md` are project-protected

### Maintainer

Owns:

- template families
- taxonomy changes
- validator rules
- migration scripts
- bulk scaffolding when the governed catalog itself expands

Maintainers may still use bulk workflows, but those workflows are explicitly outside the ordinary skill-authoring UX.

## System Overview

The system consists of five surfaces:

1. `new`: scaffold one concrete skill
2. `edit`: update skill content or metadata
3. `render`: regenerate protected `SKILL.md` files from templates
4. `validate`: detect taxonomy, completeness, and generation drift problems
5. `list`: show discoverable skills and completion status

The default user path is:

1. run `skill-bill new`
2. answer guided prompts
3. review or edit `content.md`
4. run `skill-bill validate`
5. install or sync through the existing symlink workflow

## Canonical File Model

Each skill directory keeps the existing directory-per-skill layout:

```text
skills/<package>/<slug>/
  SKILL.md
  content.md
  ...optional sibling support files...
```

### `SKILL.md`

`SKILL.md` is generated and protected. It contains:

- canonical frontmatter such as `name` and `description`
- project-overrides lookup behavior
- fixed runtime ceremony required by this repo
- the template-selected routing/delegation behavior for the skill family
- instructions telling the runtime to read `content.md` as the authoritative business behavior

Users should not be asked to hand-edit this file during normal authoring.

### `content.md`

`content.md` is user-authored. It contains only business behavior.

It must never contain:

- output contract details enforced by the project
- shell ceremony
- override lookup mechanics
- install/sync instructions
- template plumbing
- repository governance rules that are already enforced elsewhere

## Canonical `content.md` Schema

The authoring schema is intentionally narrow so the terminal editor can manage it safely.

```markdown
# Purpose

## When To Use

## Required Context

## Business Rules

## Constraints

## Examples
```

### Section Semantics

- `Purpose`: what the skill exists to accomplish
- `When To Use`: the situations that should trigger the skill
- `Required Context`: the inputs, repo context, or user information the skill needs
- `Business Rules`: the domain-specific decision logic the skill should follow
- `Constraints`: domain guardrails and scope limits specific to the skill's job
- `Examples`: concrete scenarios, invocations, or expected judgments

### Explicit Exclusions

The schema intentionally excludes `Output Contract` because output shape is part of protected project behavior. If changing a section would risk breaking the runtime mechanics of the repo, that content belongs in `SKILL.md`, not `content.md`.

## Template Families

The system must choose a template family based on the governed skill shape.

Initial families:

1. Base skill template
2. Platform override template
3. Platform `code-review` specialist template

Each family controls:

- generated `SKILL.md` structure
- fixed runtime instructions
- any required sibling support-file references
- validation rules specific to that family

This keeps end-user authoring simple while preserving room for maintainers to evolve the protected ceremony behind stable templates.

## Metadata Ownership

Metadata required for generation remains CLI-managed, not free-form user prose.

Required metadata:

- skill slug
- package
- description
- family type
- platform when applicable
- base capability when applicable
- approved `code-review` area when applicable

The CLI may read this from generated files or an internal registry, but users should edit metadata through commands, not by patching generated ceremony manually.

For MVP, metadata may live in generated `SKILL.md` frontmatter plus path conventions. A separate manifest file is not required.

## CLI Surface

### `skill-bill new`

Purpose: scaffold one concrete skill.

Prompt flow:

1. Choose skill family:
   - base skill
   - platform override
   - platform code-review specialist
2. Collect the user-facing inputs:
   - name or capability
   - one-line description
   - package or platform when required
   - approved specialization area when required
3. Validate the name against the governed taxonomy.
4. Derive the package path and slug.
5. Create the skill directory.
6. Generate protected `SKILL.md`.
7. Create `content.md` with the canonical headings and TODO placeholders.
8. Offer immediate next actions:
   - guided edit now
   - open in `$EDITOR`
   - finish and validate later

Key rule: `new` creates one concrete skill, not the entire catalog.

### `skill-bill edit <skill>`

Purpose: edit business content or metadata without manual repo navigation.

Default mode is guided terminal editing. The command:

- resolves the skill by slug or path
- shows completion status by section
- offers section-by-section editing for `content.md`
- supports replace, append, clear, skip, and done actions
- updates metadata through explicit prompts when the user chooses metadata editing
- regenerates `SKILL.md` when metadata changes affect the generated output

If `$EDITOR` is set, the command may offer:

- open `content.md` directly in the editor
- return to validation after the editor exits

The guided mode remains the default so the system works even in pure terminal sessions.

### `skill-bill render [skill|--all]`

Purpose: regenerate protected `SKILL.md` files from templates.

Use cases:

- template family changed
- metadata changed
- maintainer wants to normalize generated files
- validator detected drift

### `skill-bill validate [skill|--all]`

Purpose: enforce repository rules and authoring completeness.

Validation includes:

- skill directory matches governed package and slug rules
- required files exist
- `content.md` headings match the canonical schema
- TODO placeholders are unresolved only when explicitly allowed in draft mode
- no forbidden protected sections appear in `content.md`
- generated `SKILL.md` still matches the expected template for its family
- README/catalog/install references remain valid when catalog-level changes are made

### `skill-bill list`

Purpose: surface discoverable skills and authoring state.

Each row should show at minimum:

- slug
- package
- family
- completion status
- whether generation drift exists

This gives terminal users a navigable overview without requiring filesystem exploration.

## Guided Editing UX

The guided editor should optimize for users who do not want to drop into Vim, Nano, or a full IDE.

Recommended behavior:

1. show the current section value
2. ask whether to replace, append, clear, or skip
3. accept multiline input until an explicit sentinel such as `.done`
4. write the section back into `content.md`
5. move to the next section

The editor must preserve section order and headings exactly so validation and future automation stay simple.

## Draft vs Complete State

Scaffolds may start incomplete, but the system must distinguish draft from complete explicitly.

Rules:

- `skill-bill new` may create TODO placeholders
- `skill-bill validate` fails by default if TODO placeholders remain
- `skill-bill list` marks incomplete skills clearly
- maintainers may have an explicit draft mode, but draft state must never be silent

## Naming and Taxonomy Enforcement

The system must enforce the repo rules already defined in `AGENTS.md`:

- base skills: `bill-<capability>`
- platform overrides: `bill-<platform>-<base-capability>`
- approved deeper specialization only for `code-review`: `bill-<platform>-code-review-<area>`

Approved `code-review` areas remain:

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

The CLI must reject unapproved shapes rather than scaffolding them and hoping validation catches the problem later.

## Installer and Sync Model

This feature does not replace the current symlink-based installer.

Expected behavior:

- a skill directory remains the canonical unit of installation
- supported agents still receive symlinks to canonical skill directories
- generated `SKILL.md` and authored `content.md` both live inside the same canonical directory

No new packaging format is introduced in this feature.

## Migration Strategy

Existing skills must be migrated into the split model without losing behavior.

Migration phases:

1. Introduce template families and generation tooling.
2. Add `content.md` beside existing skills.
3. Extract business behavior from legacy `SKILL.md` bodies into `content.md`.
4. Regenerate `SKILL.md` using the protected template family.
5. Validate the migrated skill.
6. Update docs and examples to treat `content.md` as the editable surface.

If a legacy skill cannot be safely auto-split, mark it as maintainer-migration-required rather than emitting a lossy transform.

## Documentation Changes

README and related docs should explain:

- the new `SKILL.md` and `content.md` ownership model
- the terminal-first authoring flow
- the difference between ordinary user authoring and maintainer catalog work
- how validation reports incomplete skills

The docs should explicitly discourage opening `SKILL.md` to edit business behavior directly.

## Implementation Plan

### Phase 1: Core authoring model

1. Define template families for base, platform override, and platform `code-review` specialist skills.
2. Define the canonical `content.md` schema and forbidden protected content rules.
3. Implement generation of protected `SKILL.md` files from template families.

### Phase 2: CLI authoring workflow

4. Implement `skill-bill new` for one-skill scaffolding.
5. Implement guided `skill-bill edit`.
6. Add optional `$EDITOR` handoff that returns to validation afterward.
7. Implement `skill-bill list`.

### Phase 3: Enforcement

8. Implement `skill-bill validate`.
9. Add drift detection between templates and generated `SKILL.md`.
10. Wire taxonomy validation into create and edit flows, not only post hoc validation.

### Phase 4: Migration and docs

11. Add migration tooling or a documented migration playbook for existing skills.
12. Update README and install guidance for the new authoring model.
13. Add tests covering both acceptance and rejection paths.

## Test Plan

Required automated coverage:

- valid base skill scaffolding
- valid platform override scaffolding
- valid platform `code-review` specialist scaffolding
- rejection of invalid names and unapproved specialization areas
- generation of canonical `content.md` headings
- regeneration of `SKILL.md` after metadata edits
- validation failure on unresolved TODO placeholders
- validation failure when forbidden protected sections appear in `content.md`
- drift detection when `SKILL.md` is hand-edited
- listing of completion state for draft vs complete skills

## Open Questions

1. Should metadata stay only in generated `SKILL.md` frontmatter for MVP, or should maintainers introduce a separate machine-friendly manifest later if templates become more complex?
2. Does the repo want a single generic base-skill template for all new base skills, or should certain governed capabilities have dedicated template families from the start?
3. Should `skill-bill new` immediately install/symlink the new skill when running in a configured local environment, or should installation remain an explicit separate step?

## Recommendation

Ship SKILL-23 as a CLI-first authoring system with a narrow schema and strict generated-file boundaries. Do not optimize first for bulk scaffolding or IDE workflows. If users can create, edit, list, and validate one concrete skill entirely from the terminal, the `SKILL.md`/`content.md` split becomes a product feature instead of just an internal refactor.
