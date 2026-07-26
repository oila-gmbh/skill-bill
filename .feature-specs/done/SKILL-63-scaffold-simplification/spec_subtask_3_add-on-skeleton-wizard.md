---
status: Complete
---

# SKILL-63 Subtask 3 - Add-On Skeleton Wizard

Parent spec: [.feature-specs/SKILL-63-scaffold-simplification/spec.md](./spec.md)
Issue key: SKILL-63

## Scope

Simplify add-on scaffold creation so users create a skeleton first and author the body afterward. Replace confusing raw consumer-directory prompts with an understandable default or selection model.

This subtask owns add-on wizard UX, add-on payload mapping, skeleton content, docs, and tests.

## Acceptance Criteria

1. `skill-bill new` / `skill-bill new-skill` add-on flow no longer prompts for `Body`.
2. Desktop add-on scaffold flow no longer prompts for body text during creation.
3. Add-on scaffold creates a skeleton markdown file with a clear TODO body placeholder.
4. The command output or notes direct users to edit the generated add-on file afterward.
5. The normal wizard no longer prompts for raw "Consumer skill dirs, comma-separated".
6. Normal add-on creation uses a safe default consumer selection when possible, matching existing runtime behavior for the owning pack baseline.
7. If explicit consumer selection is still exposed in a user-facing flow, it uses understandable labels or skill names, not raw pack-relative directories.
8. Scripted payload support for `body` and `consumer_skill_dirs` remains deterministic if retained, but docs distinguish it as advanced/scripted usage rather than normal wizard input.
9. Invalid consumer selections still fail loudly before any file or manifest mutation.
10. Add-on manifest registration remains governed by `platform.yaml` `addon_usage`; no per-skill prose selection tables are introduced.
11. Documentation explains the add-on authoring sequence:
    - create skeleton;
    - edit the add-on markdown body;
    - validate/render/install.
12. Tests cover CLI wizard add-on flow, desktop add-on payload mapping, skeleton file content, default consumer registration, and invalid consumer rejection.

## Non-Goals

- Do not remove add-on scaffolding.
- Do not change add-on runtime resolution semantics.
- Do not hand-author add-on usage tables in `content.md`.
- Do not make add-ons standalone skills.

## Dependency Notes

Depends on: 1, 2
The add-on wizard should be simplified after the scaffold kind and platform-pack creation model are stable.

## Validation Strategy

Add/update CLI wizard tests, desktop payload tests, scaffold add-on governance tests, and docs checks. Run focused add-on scaffold tests, then the broader validation gate from the parent spec.

## Next Path

Run final validation for SKILL-63 and prepare the PR description.

## Spec Path

.feature-specs/SKILL-63-scaffold-simplification/spec_subtask_3_add-on-skeleton-wizard.md
