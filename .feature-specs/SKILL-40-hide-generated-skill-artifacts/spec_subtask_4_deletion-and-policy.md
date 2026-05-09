# SKILL-40 Subtask 4: mass deletion, remaining test migration, policy paragraph

Parent spec: [./spec.md](./spec.md)

Status: Not started

## Scope

The terminal subtask. Delete every committed governed `SKILL.md` and every `platform.yaml`-declared pointer file from the source tree, finish the residual test migration that depends on those files being gone, add the load-bearing policy paragraph to `AGENTS.md`, and verify the migration end-to-end. This subtask is safe only because subtask 2 made install render-driven and subtask 3 added the CI drift check + `skill-bill render` CLI to prove every skill still renders correctly from authored input alone.

## In-scope changes

1. **Mass deletion (AC #1):**
   - Delete every governed `SKILL.md` under `skills/`, `platform-packs/`, `addons/` (and any other location currently holding a governed-skill `SKILL.md`).
   - Delete every pointer file declared in any `platform.yaml` `pointers:` block.
   - After this step, `skills/<name>/` and `platform-packs/<pack>/<cat>/<skill>/` contain only authored material: `content.md`, optional `native-agents/`, optional authored sidecars (e.g. `compose-guidelines.md`, `audit-rubrics.md`).
   - Use `git rm` so deletions are tracked.
2. **Pack-manifest baseline + `ShellContentLoader` parser swap (AC #8 remainder, deferred from subtask 1):**
   - Flip every `baseline:` value in `platform-packs/<pack>/platform.yaml` from `code-review/.../SKILL.md` (and any other `<dir>/SKILL.md` form) to `code-review/.../content.md` so pack discovery resolves at the canonical authoring surface.
   - `ShellContentLoader.kt:220` (`parseDeclaredFiles`): the resolver currently calls `packRoot.resolve(baselineRaw).normalize()` on the raw value, which is data-only and survives the `.md` rename without code changes; verify that no surrounding code path enforces the `SKILL.md` filename.
   - `ShellContentLoader.validateGovernedSkill`: retire the wrapper body-shape branch (`Descriptor`/`Execution`/`Ceremony` H2 enforcement and exact rendered match) on the baseline path, since the wrapper no longer exists. Keep the frontmatter check, route it at `content.md` via `validateSkillMdShape(... validateBodyShape = false)`. Subtask 1 already paired wrapper + sibling content.md validation behind the `validateBodyShape` flag, so this is a callsite simplification rather than a rewrite.
   - Add a parity test that loads a pack with a `content.md` baseline and asserts `loadPlatformPack` succeeds against the new shape.
3. **Residual test migration (AC #9 remainder, ~30 sites):**
   - Production-output assertion tests that asserted the presence/path of a checked-in `SKILL.md` (e.g. `ScaffoldServiceParityTest.kt:76,79,133,148,323,342`): update to assert the new "no `SKILL.md` on disk in the source tree" shape.
   - Any remaining `skillDir.resolve("SKILL.md")` references that survived subtask 1 because they were tied to expected committed render output: migrate or delete.
   - Drift tests already removed in subtask 1; verify nothing was missed.
4. **Policy paragraph in `AGENTS.md` (AC #10):** new paragraph stating that generated artifacts derived from authored input are not committed; the install pipeline renders them at install time; authored input lives in `content.md`, `native-agents/`, and explicit sidecars; use `skill-bill render <skill-id>` to inspect what install produces. Place it where existing skill-authoring guidance lives so it is discoverable to new contributors.
5. **`scripts/validate_agent_configs` activation:** the extension shipped in subtask 3 is now load-bearing — confirm it fails loud if anyone re-introduces a committed governed `SKILL.md` or pointer file.
6. **End-to-end verification (AC #11):**
   - Run `skill-bill render` against every skill in the repo and confirm it produces correct output for each.
   - Confirm `(cd runtime-kotlin && ./gradlew check)` passes with the new validation shape against a tree that no longer contains any committed `SKILL.md` or pointer file.
   - Confirm `npx --yes agnix --strict .` and `scripts/validate_agent_configs` pass.
7. The deletion + verification can be a single final commit per AC #11. Squashing into one commit at the end is acceptable; the important property is that the PR's terminal commit shows a clean tree that builds and installs correctly without committed render output.

## Acceptance criteria for this subtask

- AC #1 fully satisfied: every governed `SKILL.md` and every `platform.yaml`-declared pointer file deleted from git.
- AC #8 remainder satisfied: pack manifest `baseline:` strings flipped to `content.md`; `ShellContentLoader.validateGovernedSkill`'s wrapper body-shape branch retired so the baseline path validates only `content.md` frontmatter (the residual deferred from subtask 1).
- AC #9 fully satisfied: production-output assertion tests updated to the no-`SKILL.md`-on-disk shape; residual `resolve("SKILL.md")` sites handled.
- AC #10 fully satisfied: `AGENTS.md` contains the policy paragraph.
- AC #11 fully satisfied: `skill-bill render` produces correct output for every skill; `(cd runtime-kotlin && ./gradlew check)` passes; `npx --yes agnix --strict .` and `scripts/validate_agent_configs` pass.
- A freshly-cloned tree (no generated `SKILL.md` or pointer files have ever existed locally) installs every skill correctly via the staging-driven install pipeline.

## Non-goals

- Do not change `platform.yaml` `pointers:` schema, `content.md` authoring conventions, or frontmatter shape.
- Do not change install pipeline shape (already done in subtask 2).
- Do not change marker, validator, or workflow-label path strings (already done in subtask 1). Pack-manifest baseline strings + `ShellContentLoader` parser branch ARE in scope for this subtask, deferred from subtask 1 because they depend on retiring the wrapper.
- Do not extend the policy paragraph beyond skill artifacts.
- Do not relocate generated files to a parallel `generated/` tree.

## Dependencies

Depends on subtasks 1, 2, and 3. Deletion is only safe after install renders into staging (subtask 2) AND the CI drift check + snapshot tests + render CLI exist (subtask 3) so that every skill is provably re-renderable from authored input alone.

## Validation

`bill-quality-check`: `(cd runtime-kotlin && ./gradlew check)`, `npx --yes agnix --strict .`, `scripts/validate_agent_configs`. All three must pass against the post-deletion tree. Manually verify a clean clone installs without any committed render output.

## Recommended next prompt

Run bill-feature-implement on .feature-specs/SKILL-40-hide-generated-skill-artifacts/spec_subtask_4_deletion-and-policy.md.
