# SKILL-40 render-cli-and-snapshots decomposition

Parent feature overview: `.feature-specs/SKILL-40-hide-generated-skill-artifacts/spec.md`

Status: Complete

Completion note: All three decomposed runs are complete. The render CLI output,
drift/agent-config guards, and snapshot/final-validation work satisfy the broad
parent subtask `.feature-specs/SKILL-40-hide-generated-skill-artifacts/spec_subtask_3_render-cli-and-snapshots.md`.

This decomposition splits the render CLI, drift validation, and snapshot coverage work from `.feature-specs/SKILL-40-hide-generated-skill-artifacts/spec_subtask_3_render-cli-and-snapshots.md` into smaller feature-implement runs. The work crosses runtime CLI, runtime core, tests, build logic, resources, and agent-config validation, so it should land in dependency order.

## Acceptance criteria

1. CI drift check runs across every skill and verifies parse/render validity, pointer resolvability, and byte-identical repeated rendering.
2. Drift check is wired into `(cd runtime-kotlin && ./gradlew check)`.
3. Snapshot tests cover one standalone `skills/<name>/`, `bill-kotlin-code-review`, and `bill-kmp-code-review-ui` with `native-agents/`.
4. Snapshot failures mention `-Pupdate-snapshots` and identify the offending fixture path.
5. `skill-bill render <skill-id>` and `skill-bill render --dry-run <skill-id>` print deterministic `SKILL.md` plus pointer contents with separator headers, never writing to disk.
6. Output uses `renderWrapper` and `renderPointer`, with pointer blocks in `platform.yaml` declaration order.
7. `scripts/validate_agent_configs` fails loud on committed governed `SKILL.md` or `platform.yaml` pointer files, but remains dormant while current committed render outputs still exist.
8. Validation passes: `(cd runtime-kotlin && ./gradlew check)`, `npx --yes agnix --strict .`, `scripts/validate_agent_configs`.

## Non-goals

- Do not delete committed `SKILL.md` or pointer files.
- Do not change install pipeline behavior.
- Do not change discovery marker, validator, workflow path strings, pointer schema, or `content.md` frontmatter shape.
- Do not add a new build step beyond existing `./gradlew check`.
