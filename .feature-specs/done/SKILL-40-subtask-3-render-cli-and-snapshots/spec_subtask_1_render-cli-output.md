# Subtask 1: render CLI output

Parent overview: `.feature-specs/SKILL-40-render-cli-and-snapshots/spec.md`

Status: Compete

## Scope

Implement the non-writing render command surface for `skill-bill render <skill-id>` and `skill-bill render --dry-run <skill-id>`. Resolve the existing `RenderSkillsCommand` command-name collision deliberately so this surface prints deterministic output instead of routing through the authoring upgrade/write path.

The command must print rendered `SKILL.md` followed by any rendered pointer file contents with clear separator headers. It must use `renderWrapper` for wrapper output and `renderPointer` for pointer output. Pointer blocks for platform-pack skills must follow `platform.yaml` pointer declaration order, reusing existing manifest parsing behavior that preserves pointer order rather than the write-path sorting used for deterministic regeneration.

Keep behavior manifest-driven and respect existing `content.md` discovery/frontmatter rules. Do not write to the source tree for either normal render or `--dry-run`.

## Acceptance criteria

- `skill-bill render <skill-id>` prints deterministic `SKILL.md` plus pointer contents with separator headers and never writes to disk.
- `skill-bill render --dry-run <skill-id>` behaves the same way and never writes to disk.
- Output uses `renderWrapper` and `renderPointer`.
- Pointer blocks appear in `platform.yaml` declaration order.

## Non-goals

- Do not delete committed generated files.
- Do not change install pipeline behavior.
- Do not change discovery marker, validator, workflow path strings, pointer schema, or `content.md` frontmatter shape.
- Do not add drift checks or snapshot infrastructure in this subtask.

## Dependencies

None. This subtask establishes the runtime command behavior needed by later drift and snapshot validation.

## Validation strategy

Run the focused runtime CLI/core tests added for this command if present, then run `(cd runtime-kotlin && ./gradlew check)` before handoff if the implementation is complete enough. Full repository validation is deferred to subtask 3.

## Recommended next prompt

Run bill-feature-implement on `.feature-specs/SKILL-40-render-cli-and-snapshots/spec_subtask_1_render-cli-output.md`.
