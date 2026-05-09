# SKILL-40 Subtask 2: install pipeline renders generated artifacts into a staging dir

Parent spec: [./spec.md](./spec.md)

Status: Not started

## Scope

Rewire the install pipeline so it no longer relies on committed `SKILL.md` or pointer files in the source tree. After this subtask, `InstallPrimitives.installSkill` (and the orchestrator above it) copy authored material into a per-skill staging directory, render `SKILL.md` (from `content.md` frontmatter) and pointer files (from `platform.yaml` `pointers:`) into the staging dir only, then symlink the staging dir as the installed skill. The source tree is never written back to.

The committed `SKILL.md` and pointer files are still present on disk through this subtask — they are simply ignored by install. Their deletion happens in subtask 4.

## In-scope changes

1. New staging cache root, mirroring SKILL-38 native-agent cache shape: `~/.skill-bill/installed-skills/<slug>-<hash>/`. Hash is the deterministic content hash of authored input so re-renders are content-addressable. Cache root MUST live outside the repo.
2. `InstallPrimitives.installSkill` (around line 67):
   - Compute staging dir path under the new cache root.
   - Copy authored material verbatim into staging: `content.md`, `native-agents/` (if present), authored sidecars (e.g. `compose-guidelines.md`, `audit-rubrics.md`, anything that is not a governed `SKILL.md` or a pointer declared in `platform.yaml`).
   - Call existing `renderWrapper(target: AuthoringTarget)` from `runtime-core/.../scaffold/AuthoringRender.kt` to materialize `SKILL.md` into staging from `content.md` frontmatter.
   - Call existing `renderPointer(...)` from `runtime-core/.../scaffold/PointerRendering.kt` for every pointer declared in `platform.yaml` `pointers:`, materializing pointer files into staging.
   - Symlink staging as the installed skill (replacing the previous "symlink the source tree" step).
   - Never write to the source tree under any condition.
3. Render error handling: failures in `renderWrapper` or `renderPointer` MUST throw via `require(...)` so the existing `runWithUpgradeRollback` envelope catches them (it only catches `SkillBillRuntimeException`/`IOException`/`IllegalArgumentException`). Install must fail closed on render error — never produce a partial staging dir.
4. Public DTOs introduced to support the new flow (e.g. `RenderedSkill`, `StagingResult`) live in `runtime-domain/.../model/` per repo conventions.
5. Determinism guards consistent with SKILL-39 patterns: `Files.walk(...).sorted()` everywhere; explicit `append('\n')` not `appendLine`; `trimEnd('\n','\r')` for CRLF; `Path.relativize` + forward-slash for any embedded relative paths.
6. Defense-in-depth path traversal checks on staging writes (no `..` escape; staging dir is the only writable target).
7. Update install-pipeline tests (and any orchestrator tests above `InstallPrimitives`) to assert: authored files copied verbatim; `SKILL.md` and pointer files materialized in staging; source tree untouched after install; symlink target is the staging dir; install fails closed when render throws.

## Acceptance criteria for this subtask

- AC #3 fully satisfied: install pipeline copies authored material verbatim, renders `SKILL.md` and pointer files into staging only, never writes back to the source tree, fails install on render error.
- Installed skill directories remain byte-identical to today (non-goal preserved).
- New staging cache root lives outside the repo.
- `runWithUpgradeRollback` properly catches render failures (require-based throws).
- `(cd runtime-kotlin && ./gradlew check)` passes against the existing tree (committed `SKILL.md` and pointer files still present but now ignored by install).

## Non-goals

- Do not delete any committed `SKILL.md` or pointer file (subtask 4).
- Do not introduce snapshot tests or the CI drift check (subtask 3).
- Do not add the `skill-bill render` CLI command (subtask 3).
- Do not change the `platform.yaml` `pointers:` schema or `content.md` frontmatter shape.
- Do not change native-agent rendering paths.
- Do not introduce a parallel `generated/` tree.
- Do not add a new build step.

## Dependencies

Depends on subtask 1 (skill-discovery marker is `content.md`; `AuthoringTarget` carries `contentFile`). The install pipeline rewrite uses `AuthoringTarget` shape that subtask 1 finalizes.

## Validation

`bill-quality-check`: `(cd runtime-kotlin && ./gradlew check)`, `npx --yes agnix --strict .`, `scripts/validate_agent_configs`. Manually verify that installing a skill with the source tree's committed `SKILL.md` deleted locally still produces a working installed skill (proves install no longer relies on committed render output).

## Recommended next prompt

Run bill-feature-implement on .feature-specs/SKILL-40-hide-generated-skill-artifacts/spec_subtask_2_install-staging.md.
