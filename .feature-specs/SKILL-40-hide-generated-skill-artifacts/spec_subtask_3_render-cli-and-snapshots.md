# SKILL-40 Subtask 3: render CLI, snapshot tests, CI drift check

Parent spec: [./spec.md](./spec.md)

Status: Not started

## Scope

Build the maintainer-facing inspection surface and the CI safety nets required before mass deletion of committed render output. After this subtask, the repo can prove that re-rendering authored input produces correct, deterministic output for every skill — which is the precondition for safely deleting committed `SKILL.md` and pointer files in subtask 4.

## In-scope changes

1. New CLI command in `runtime-kotlin/runtime-cli/src/main/kotlin/skillbill/cli/`:
   - `skill-bill render <skill-id>` and `skill-bill render --dry-run <skill-id>` (default behavior is dry-run; both forms acceptable).
   - Prints rendered `SKILL.md` content first, then pointer file contents in `platform.yaml` declaration order.
   - Each block separated by deterministic separator headers (e.g. `===== SKILL.md =====`, `===== pointers/<name> =====`) so output is diff-friendly and stable.
   - Never writes to disk.
   - Distinct from existing `RenderSkillsCommand` (which writes to disk) — this is a new CLI surface.
   - Reuse `renderWrapper` and `renderPointer` so output is identical to what install staging produces.
2. Snapshot test framework under `runtime-core/src/test/resources/`:
   - File-based fixtures (one directory per snapshotted skill, holding the expected `SKILL.md` and pointer file outputs).
   - `-Pupdate-snapshots` Gradle property hook so a maintainer can regenerate fixtures intentionally.
   - Test failure message clearly says "rerun with `-Pupdate-snapshots`" and points at the offending fixture path.
3. Snapshot coverage (minimum set per AC #6):
   - One standalone `skills/<name>/`.
   - One platform-pack baseline: `bill-kotlin-code-review`.
   - One platform-pack specialist with `native-agents/`: `bill-kmp-code-review-ui`.
4. CI drift check (P0) wired into `(cd runtime-kotlin && ./gradlew check)`:
   - Runtime-core test (or top-level Gradle task) that walks every skill in the repo.
   - For every skill: `content.md` parses and renders to a valid `SKILL.md` (frontmatter, required H2 sections, no fenced code blocks per existing `SkillMdShapeValidator` rules).
   - For every `platform.yaml` `pointers:` declaration: target file exists; relative path is computable.
   - Determinism: rendering twice produces byte-identical output.
5. Extend `scripts/validate_agent_configs` to fail loud if any committed governed `SKILL.md` or `platform.yaml`-declared pointer file is present in the working tree. (This guard goes live in subtask 4 after deletion, but the script change ships here so the rule exists in the codebase the moment deletion happens.)

## Acceptance criteria for this subtask

- AC #5 fully satisfied: CI drift check runs end-to-end across every skill, asserts parse+render validity, pointer resolvability, and byte-identical re-render. Wired into `./gradlew check`.
- AC #6 fully satisfied: snapshot tests pin renderer output for the three required skill shapes; failure messages reference `-Pupdate-snapshots`.
- AC #7 fully satisfied: `skill-bill render <skill-id>` (with `--dry-run`) prints `SKILL.md` and pointer contents to stdout deterministically with separator headers, in declaration order, without writing to disk.
- `scripts/validate_agent_configs` extended to fail loud on committed governed render output (guard inert until subtask 4 deletes the files).
- `(cd runtime-kotlin && ./gradlew check)` passes against the existing tree (committed `SKILL.md` and pointer files still present; `validate_agent_configs` extension is dormant until they are removed).

## Non-goals

- Do not delete any committed `SKILL.md` or pointer file (subtask 4).
- Do not change install pipeline behavior (subtask 2 already done).
- Do not change skill-discovery marker, validator, or workflow path strings (subtask 1 already done).
- Do not change `platform.yaml` `pointers:` schema or `content.md` frontmatter shape.
- Do not introduce a new build step beyond hooking the drift check into existing `./gradlew check`.

## Dependencies

Depends on subtask 1 (validator on `content.md`, marker `content.md`) and subtask 2 (install pipeline already render-driven so the CLI and drift check produce output that matches what install would stage). The CLI and drift check both reuse the same `renderWrapper`/`renderPointer` paths the install pipeline now consumes.

## Validation

`bill-quality-check`: `(cd runtime-kotlin && ./gradlew check)`, `npx --yes agnix --strict .`, `scripts/validate_agent_configs`. The drift check failing for any skill blocks merge — which means at this point every skill in the repo must already render cleanly from authored input alone.

## Recommended next prompt

Run bill-feature-implement on .feature-specs/SKILL-40-hide-generated-skill-artifacts/spec_subtask_3_render-cli-and-snapshots.md.
