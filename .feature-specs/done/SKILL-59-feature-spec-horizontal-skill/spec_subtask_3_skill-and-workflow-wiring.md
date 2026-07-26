---
status: Complete
---

# SKILL-59 Subtask 3 - Skill + Workflow Wiring

Parent spec: [.feature-specs/SKILL-59-feature-spec-horizontal-skill/spec.md](./spec.md)
Issue key: SKILL-59

## Scope

Add the new horizontal `bill-feature-spec` governed source and wire existing workflows to the shared preparation path. The authored source must live at `skills/bill-feature-spec/content.md`; no generated `SKILL.md` should be committed. Update `bill-feature-implement` to reuse shared preparation when preparing parent specs and decomposition packages, and update `bill-goal` orchestration so it can direct users through shared preparation when a decomposition is missing while preserving the runtime `skill-bill goal` command as a consumer-only manifest runner.

Keep `bill-goal` to one confirmation gate.

## Acceptance Criteria

1. New horizontal skill source exists at `skills/bill-feature-spec/content.md`.
2. The source is governed `content.md` only, with no committed generated `SKILL.md`.
3. `bill-feature-spec` describes required intake for issue key, outcome, criteria, and constraints and loud-fails missing issue key.
4. `bill-feature-spec` supports `single_spec` and `decomposed` preparation modes.
5. `bill-feature-implement` reuses the shared preparation path rather than its own decomposition preparation logic.
6. `bill-goal` reuses the shared preparation path when decomposition is missing at the skill/orchestration layer.
7. The `skill-bill goal` CLI remains consumer-only and loud-fails when a decomposition manifest is missing.
8. `bill-goal` retains one confirmation gate.
9. Documentation and catalog entries mention standalone `bill-feature-spec` preparation.

## Non-Goals

- Do not add platform-specific decomposition skills or platform-pack entries.
- Do not add `skill-bill goal` prose-to-spec synthesis.
- Do not commit generated wrappers, support pointers, native-agent outputs, or install staging artifacts.

## Dependencies

Depends on subtasks 1 and 2 because workflow wording and routing should reference the implemented shared preparation behavior and persistence semantics.

## Validation Strategy

Run `bill-quality-check`. Include skill validation/render checks for `bill-feature-spec`, workflow-content tests or snapshots that prove `bill-feature-implement` and `bill-goal` reference the shared preparation path, and CLI/runtime tests proving `skill-bill goal` still loud-fails as consumer-only when no manifest exists.

## Next Path

After this subtask completes and is committed, run `bill-feature-implement` on `.feature-specs/SKILL-59-feature-spec-horizontal-skill/spec_subtask_4_regression-and-maintainer-validation.md`.

