---
status: In Progress
---

# SKILL-63 - scaffold-simplification

## Mode

decomposed

## Intended Outcome

Simplify new scaffold creation into a smaller, clearer set of choices: horizontal skill, full platform pack, and add-on skeleton.

## Overview

The current scaffold wizard asks users to understand implementation details at creation time:

- `platform-override-piloted` creates individual family overrides even though platform packs are the supported extension surface.
- `code-review-area` creates individual specialist areas separately from pack creation, encouraging partial pack assembly.
- `platform-pack` asks users to choose starter, full, or a custom specialist subset before they know what a coherent pack should contain.
- `add-on` asks for a body and raw consumer skill directories such as `code-review/bill-kmp-code-review-ui`, which is not understandable from the wizard.

SKILL-63 makes scaffold creation opinionated and easier to recover from:

- platform packs are always created as complete packs;
- separate platform override and code-review-area creation paths are removed from new scaffold creation;
- add-ons are created as skeleton files and edited afterward;
- add-on consumer selection becomes understandable or defaults safely.

Existing authored source must keep working. This feature changes what new scaffold commands can create; it must not break discovery, validation, install, render, or removal for existing platform overrides, existing specialist areas, or existing non-full packs.

## Acceptance Criteria

1. `skill-bill new` and related scaffold creation surfaces expose only understandable creation paths:
   - horizontal skill;
   - full platform pack;
   - add-on skeleton.
2. New `platform-override-piloted` scaffolds are rejected from user-facing and payload-based creation paths with a clear typed error.
3. New `code-review-area` scaffolds are rejected from user-facing and payload-based creation paths with a clear typed error.
4. Platform-pack scaffolding always creates the full pack:
   - baseline `code-review`;
   - default `quality-check`;
   - every approved code-review specialist area;
   - manifest entries for every generated specialist.
5. Platform-pack scaffold requests reject `skeleton_mode` and `specialist_areas` with actionable migration messages.
6. Add-on scaffolding creates a skeleton add-on file only; wizard users are not asked to type the body during creation.
7. Add-on consumer selection no longer exposes raw pack-relative directory strings in the normal wizard. The normal path either uses a safe default or presents understandable labels/skill names.
8. Advanced scripted payloads remain deterministic and validated, but confusing wizard-only prompts are removed.
9. Existing platform-override sources, code-review-area content files, and non-full platform packs remain readable, renderable, installable, valid, and removable.
10. Documentation reflects the simplified model:
    - create a full platform pack first;
    - delete unwanted focus areas afterward through governed removal paths;
    - create add-on skeletons first and author their body afterward.
11. Regression tests cover CLI wizard behavior, payload rejection behavior, desktop scaffold payload behavior, full-pack generation, add-on skeleton generation, and existing-source compatibility.

## Constraints

- Do not delete existing source support unless a separate cleanup spec explicitly authorizes it.
- Do not break existing platform pack manifests that have fewer than all approved specialist areas.
- Keep platform packs manifest-driven; do not hard-code shipped platform slugs as the only allowed packs.
- Preserve add-on scaffolding.
- Preserve `content.md` source-only rules and generated-output boundaries.
- Keep scaffold behavior atomic: rejected inputs must not leave partial files, manifest edits, README edits, or agent links.

## Non-Goals

- No deletion of existing `skills/<platform>/` pre-shell family trees.
- No automatic removal of existing starter/custom platform packs.
- No broad redesign of the remove command.
- No change to platform-pack runtime routing semantics.
- No change to add-on runtime semantics beyond creation ergonomics.

## Subtasks

1. `spec_subtask_1_scaffold-kind-surface.md` - remove separate platform override and code-review-area creation paths while preserving existing-source compatibility.
2. `spec_subtask_2_full-platform-pack-contract.md` - make platform-pack creation full-pack only across CLI, runtime policy, desktop payloads, docs, and tests.
3. `spec_subtask_3_add-on-skeleton-wizard.md` - simplify add-on creation to skeleton-first authoring and understandable/default consumer selection.

## Validation Strategy

- Run focused scaffold CLI/runtime/desktop tests for each subtask.
- Run `skill-bill validate`.
- Run `(cd runtime-kotlin && ./gradlew check)`.
- Run `npx --yes agnix --strict .`.
- Run `scripts/validate_agent_configs`.
