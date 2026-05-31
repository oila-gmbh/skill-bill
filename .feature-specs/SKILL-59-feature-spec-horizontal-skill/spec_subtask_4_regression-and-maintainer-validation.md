---
status: Complete
---

# SKILL-59 Subtask 4 - Regression + Maintainer Validation

Parent spec: [.feature-specs/SKILL-59-feature-spec-horizontal-skill/spec.md](./spec.md)
Issue key: SKILL-59

## Scope

Close remaining regression, catalog, and validation gaps across the full feature. This subtask should not introduce a second implementation path; it should prove the shared preparation path is the only path used by `bill-feature-spec`, `bill-feature-implement`, and `bill-goal` orchestration, and that generated-output boundaries remain clean.

Run the maintainer validation command set and fix root causes of failures.

## Acceptance Criteria

1. Regression coverage proves there is one shared preparation path.
2. Regression coverage proves `single_spec` stays manifest-free and guards mode conflicts.
3. Regression coverage proves decomposed preparation writes valid manifests and runnable goal packages.
4. Regression coverage proves `bill-goal` has one confirmation gate and `skill-bill goal` remains consumer-only.
5. Docs and README/catalog mention `bill-feature-spec` standalone preparation if not already completed.
6. Maintainer validation command set passes: `skill-bill validate`, `(cd runtime-kotlin && ./gradlew check)`, `npx --yes agnix --strict .`, and `scripts/validate_agent_configs`.

## Non-Goals

- Do not broaden feature scope beyond the SKILL-59 acceptance criteria.
- Do not add manifest schema changes unless a prior subtask documented them as required.
- Do not commit generated wrappers, support pointers, provider-native outputs, install staging artifacts, or unrelated cleanup.

## Dependencies

Depends on subtasks 1, 2, and 3 because it validates the integrated behavior and closes any remaining documentation/catalog coverage.

## Validation Strategy

Run `bill-quality-check` first, then the full maintainer validation command set listed above. Record any unavoidable environment-only failures with exact command output and affected acceptance criteria.

## Next Path

This is the final SKILL-59 subtask. After completion, run the parent goal continuation or PR workflow for the whole feature branch.

