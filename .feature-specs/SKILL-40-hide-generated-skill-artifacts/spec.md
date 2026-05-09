# SKILL-40: hide generated skill artifacts from the source tree

Status: In Progress

Sources: conversation 2026-05-08; originating Linear/issue key SKILL-40. Builds on SKILL-37/38/39 codegen patterns in `runtime-kotlin/runtime-core/src/main/kotlin/skillbill/{nativeagent,scaffold}/`.

## Context

A new contributor opening `platform-packs/kmp/code-review/bill-kmp-code-review/` today sees 17 entries:

- **3 authored**: `content.md` (8 KB), `native-agents/bill-kmp-code-review-ui.md` (7 KB), `native-agents/bill-kmp-code-review-ux-accessibility.md` (5 KB).
- **15 generated**: `SKILL.md` (header rendered from `content.md` frontmatter), 7 orchestration pointer symlinks, 7 addon pointer symlinks (each 33–66 bytes).

The 15 generated files are pure functions of authored input — pointer files are derived from `platform.yaml` (SKILL-39), and `SKILL.md` is derived from `content.md` frontmatter. They carry no human decision; checking them in is checking in compile output. They obscure the surface that authors actually own and inflate every PR diff that touches the renderer.

Today, install copies the source tree verbatim into the agent skill dir, so generated files must exist on disk for install to work. After this feature, the install pipeline renders them just-in-time into the staging dir, and the source tree contains only authored input.

This is a one-way door: once committed, the install pipeline becomes the canonical source of truth for what an installed skill looks like. Three pieces of supporting infrastructure are P0 acceptance criteria, not follow-up work.

## Acceptance criteria

1. **Generated files removed from source tree.** Delete from git: every `SKILL.md` under `skills/`, `platform-packs/`, `addons/` (and any other location currently holding a governed-skill `SKILL.md`); every pointer file declared in any `platform.yaml` `pointers:` block. After this PR, `skills/<name>/` and `platform-packs/<pack>/<cat>/<skill>/` contain only authored material (`content.md`, optional `native-agents/`, optional authored sidecars like `compose-guidelines.md`, `audit-rubrics.md`).

2. **Skill-discovery marker moves from `SKILL.md` to `content.md`.** Every callsite that today filters tree walks by `path.name == "SKILL.md"` (`AuthoringDiscovery.kt:67`, `RepoValidationRuntime.kt:159,185`, plus any others) switches to `content.md`. Update `AuthoringTarget` (or equivalent) to identify a skill by its `content.md` path; remove `skillFile`/`SKILL.md`-shaped fields where they're no longer load-bearing.

3. **Install pipeline renders generated artifacts into the staging dir.** Locate the install staging step (`runtime-kotlin/runtime-core/src/main/kotlin/skillbill/install/InstallPrimitives.kt` and any orchestrator above it). Today it copies the source skill dir verbatim. After this feature, install:
   - Copies authored material verbatim (`content.md`, `native-agents/`, authored sidecars).
   - Calls the existing renderer to produce `SKILL.md` from `content.md` frontmatter, written to the staging dir only.
   - Calls the existing pointer renderer (SKILL-39) to materialize pointer files in the staging dir only.
   - Never writes back to the source tree.

4. **Frontmatter validation moves to `content.md`.** `SkillMdShapeValidator.kt` validates the rendered SKILL.md frontmatter today; after this feature it validates `content.md` frontmatter directly (since that's now the source of truth for the header). `AuthoringMutation.kt`'s "scaffold-managed render drift" check is removed (there is no committed render to drift from); replace it with the CI drift check in (5).

5. **CI drift check (P0).** A test in `runtime-core` (or a top-level Gradle task) runs the full renderer end-to-end across every skill in the repo and asserts:
   - Every `content.md` parses and renders to a valid `SKILL.md` (frontmatter, required H2 sections, no fenced code blocks per existing `SkillMdShapeValidator` rules).
   - Every `platform.yaml` `pointers:` declaration resolves: target file exists, relative path is computable.
   - Render is deterministic: running it twice produces byte-identical output.
   This replaces "committed SKILL.md matches render" with "render succeeds and is consistent." Wires into `(cd runtime-kotlin && ./gradlew check)`.

6. **Snapshot tests (P0).** Pin renderer output for a representative set of skills (at minimum: one standalone `skills/<name>/`, one platform-pack baseline like `bill-kotlin-code-review`, one platform-pack specialist like `bill-kmp-code-review-ui` with `native-agents/`). Snapshots live as fixtures under `runtime-core/src/test/resources/`. Failing a snapshot is a test failure with a clear "rerun with `-Pupdate-snapshots`" message.

7. **Inspection command (P0).** Add `skill-bill render <skill-id>` (or `skill-bill render --dry-run <skill-id>`) that prints the rendered `SKILL.md` and pointer file contents to stdout without writing to disk. This is the maintainer's tool for "what does install produce for skill X?" — load-bearing for debugging once committed renders are gone. Wire into `runtime-kotlin/runtime-cli/src/main/kotlin/skillbill/cli/`.

8. **Path-string updates.** Find/replace user-facing references to `SKILL.md` paths in error messages, workflow step labels, and documentation strings to point at `content.md` instead. Known callsites: `WorkflowEngine.kt:309-311`, `FeatureImplementWorkflowDefinition.kt` (~30 `"SKILL.md :: Step N"` literals), `FeatureVerifyWorkflowDefinition.kt`, `AuthoringOperations.kt:9,45,55`. Pack manifest path strings (`baseline: "code-review/.../SKILL.md"` in pack `platform.yaml` and corresponding parser) update to `content.md`.

9. **Test migration.** ~60 test references to `skillDir.resolve("SKILL.md")` need review:
   - **Fixture-write tests** (`Files.writeString(dir.resolve("SKILL.md"), ...)`) that exercise the validator on synthetic input keep working in spirit — but should switch to writing `content.md` to match the new marker convention.
   - **Production-output assertion tests** (e.g. `ScaffoldServiceParityTest.kt:76,79,133,148,323,342` asserting paths in scaffold output) update to assert the new (no-SKILL.md-on-disk) shape.
   - **Drift tests** (testing the scaffold-managed render drift error) are deleted with the check.

10. **Policy paragraph in `AGENTS.md`.** One paragraph stating: "Generated artifacts derived deterministically from authored input are not committed. The install pipeline renders them into the agent skill dir at install time. Authored input lives in `content.md`, `native-agents/`, and any explicit sidecars; everything else is the system's responsibility. Use `skill-bill render <skill-id>` to inspect what install produces." This is the load-bearing rule that prevents future drift back toward committed render output.

11. **Migration commit.** A single commit (or final commit in the PR) that:
    - Deletes all generated `SKILL.md` and pointer files identified in (1).
    - Runs `skill-bill render` against every skill and confirms install-time render produces correct output for all of them.
    - Confirms `(cd runtime-kotlin && ./gradlew check)` passes with the new validation shape.

## Non-goals

- Do not change `platform.yaml` `pointers:` schema (SKILL-39's contract stays).
- Do not change `content.md` authoring conventions or frontmatter shape.
- Do not change install output beyond moving SKILL.md and pointer rendering from "copy from source" to "render to staging." User-visible installed skill directories remain byte-identical to today.
- Do not change native-agent rendering paths (SKILL-37/38 already write to `~/.skill-bill/native-agents/` cache; out of scope here).
- Do not introduce a new build step. Renderer runs inside the existing install / regenerate / test flows.
- Do not relocate generated files to a parallel `generated/` tree (that was the alternative considered and rejected; this feature commits to "not in git at all").
- Do not change the skill discovery contract beyond the marker filename (e.g. don't introduce a manifest for standalone skills under `skills/`).
- Do not extend the policy beyond skill artifacts in this PR. Other generated files in the repo (if any) can adopt the same policy in follow-up work.

## Validation

`(cd runtime-kotlin && ./gradlew check)` plus `npx --yes agnix --strict .` and `scripts/validate_agent_configs`. All three must pass against a freshly-cloned tree where no generated `SKILL.md` or pointer files have ever existed (i.e. the install pipeline genuinely produces a working skill from authored input alone).
