---
status: Complete
---

# SKILL-63 Subtask 1 - Scaffold Kind Surface

Parent spec: [.feature-specs/SKILL-63-scaffold-simplification/spec.md](./spec.md)
Issue key: SKILL-63

## Scope

Remove separate platform-override and code-review-area creation paths from new scaffold creation. Keep existing authored sources and existing pack manifests valid and operable.

This subtask owns the supported scaffold-kind surface in CLI/runtime/desktop adapters:

- remove `platform-override-piloted` from normal scaffold wizard options;
- remove `code-review-area` from normal scaffold wizard options;
- reject both kinds from payload-based creation paths with clear typed errors;
- preserve discovery/render/install/validate/remove behavior for existing source trees.

## Acceptance Criteria

1. `skill-bill new` and `skill-bill new-skill` no longer list `platform-override` or `code-review-area` in the kind prompt.
2. Assisted scaffold mode no longer mentions `platform-override` or `code-review-area`.
3. Wizard kind normalization rejects platform-override aliases instead of routing them to scaffold execution.
4. Wizard kind normalization rejects code-review-area aliases instead of routing them to scaffold execution.
5. Payload parsing/runtime scaffold policy rejects `kind: "platform-override-piloted"` with a typed, actionable error.
6. Payload parsing/runtime scaffold policy rejects `kind: "code-review-area"` with a typed, actionable error.
7. Error messages recommend creating a full platform pack or editing/removing existing pack content instead of creating partial scaffold pieces.
8. Existing platform-override and code-review-area source files remain discoverable, renderable, installable, validatable, and removable.
9. No generated `SKILL.md`, provider-native agent output, or support pointer file is added to source.

## Non-Goals

- Do not delete existing platform override code paths required for render/install/remove compatibility.
- Do not remove add-on scaffolding.
- Do not change platform-pack full-pack behavior in this subtask except where kind-surface errors require shared policy updates.

## Dependency Notes

Depends on: none
This subtask narrows the creation entry points that later subtasks rely on.

## Validation Strategy

Add/update CLI parser tests, CLI wizard tests, runtime scaffold policy tests, and desktop payload adapter tests. Include compatibility coverage proving existing platform-pack area sources still validate.

## Next Path

Run bill-feature-task on spec_subtask_2_full-platform-pack-contract.md.

## Spec Path

.feature-specs/SKILL-63-scaffold-simplification/spec_subtask_1_scaffold-kind-surface.md
