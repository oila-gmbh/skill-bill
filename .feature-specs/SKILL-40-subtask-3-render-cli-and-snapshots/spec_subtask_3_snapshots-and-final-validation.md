# Subtask 3: snapshots and final validation

Parent overview: `.feature-specs/SKILL-40-render-cli-and-snapshots/spec.md`

## Scope

Add snapshot infrastructure and fixtures for rendered governed output. Snapshot tests must cover one standalone `skills/<name>/`, `bill-kotlin-code-review`, and `bill-kmp-code-review-ui` including its `native-agents/` pointer source. Note that the `bill-kmp-code-review-ui` native-agent source currently lives under `platform-packs/kmp/code-review/bill-kmp-code-review/native-agents/bill-kmp-code-review-ui.md`, not under the specialist directory itself.

Introduce or reuse a deterministic `-Pupdate-snapshots` update path. Snapshot failures must identify the offending fixture path and tell the developer to use `-Pupdate-snapshots`. Keep snapshots focused on rendered wrapper and pointer content, using `renderWrapper` and `renderPointer` rather than duplicating formatting logic.

Run the full required validation set after snapshots are in place.

## Acceptance criteria

- Snapshot tests cover one standalone `skills/<name>/`, `bill-kotlin-code-review`, and `bill-kmp-code-review-ui` with `native-agents/`.
- Snapshot failures mention `-Pupdate-snapshots` and identify the offending fixture path.
- Validation passes: `(cd runtime-kotlin && ./gradlew check)`, `npx --yes agnix --strict .`, `scripts/validate_agent_configs`.
- Snapshot coverage protects the render CLI/drift behavior from subtasks 1 and 2, including render determinism and pointer declaration order where applicable.

## Non-goals

- Do not delete committed `SKILL.md` or pointer files.
- Do not change install pipeline behavior.
- Do not add a build step beyond existing `./gradlew check`.
- Do not change discovery marker, validator, workflow path strings, pointer schema, or `content.md` frontmatter shape.

## Dependencies

Depends on subtask 1 for render output behavior and subtask 2 for the Gradle drift check wiring that snapshots must coexist with.

## Validation strategy

Run the full gate: `(cd runtime-kotlin && ./gradlew check)`, `npx --yes agnix --strict .`, and `scripts/validate_agent_configs`.

## Recommended next prompt

Run bill-feature-implement on `.feature-specs/SKILL-40-render-cli-and-snapshots/spec_subtask_3_snapshots-and-final-validation.md`.
