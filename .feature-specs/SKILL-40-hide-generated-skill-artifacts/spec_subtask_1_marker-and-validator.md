# SKILL-40 Subtask 1: switch skill-discovery marker from SKILL.md to content.md

Parent spec: [./spec.md](./spec.md)

Status: Not started

## Scope

Foundational rewiring step. Move the skill-discovery marker from `SKILL.md` to `content.md` everywhere it is load-bearing for tree-walks and validation, and move frontmatter validation onto `content.md`. After this subtask, skills are still installable because committed `SKILL.md` and pointer files remain on disk — they are simply no longer the marker the runtime uses to find a skill.

This subtask MUST NOT delete any committed `SKILL.md` or pointer file. Mass deletion happens in subtask 4.

## In-scope changes

1. `AuthoringDiscovery.kt` (around line 67) and `RepoValidationRuntime.kt` (around lines 159, 185), plus any other tree-walk filter that compares `path.name == "SKILL.md"`: switch the filter to `content.md`.
2. `AuthoringTarget` (or equivalent in `runtime-core/.../scaffold/`) gains/keeps `contentFile: Path` as the canonical identity. Remove or fold away `skillFile`-shaped fields that are no longer load-bearing once discovery is content-driven. Where `skillFile` is still needed transiently (e.g. drift check until removed), document it.
3. `SkillMdShapeValidator.kt`: validate `content.md` frontmatter directly. Frontmatter shape rules (required keys, fenced-code-block prohibition, required H2 sections) move to operate on the authored file.
4. `AuthoringMutation.kt` "scaffold-managed render drift" check (`hasGenerationDrift` around line 76) and `AuthoringStatus.kt` (around line 63): remove the drift signal. The CI drift check in subtask 3 replaces it; deleting it now is safe because subtask 4 removes the committed render output that the drift check exists to police.
5. Workflow-label sweep: `WorkflowEngine.kt:309-311`, `FeatureImplementWorkflowDefinition.kt` (~12 `"SKILL.md :: Step N"` literals), `FeatureVerifyWorkflowDefinition.kt` — switch from `SKILL.md` to `content.md`. Preserve step ordering and stable IDs (workflow step IDs are durable artifact IDs tied to telemetry and state; if a literal turns out to be a state key, add a `DatabaseMigrations` entry to migrate existing rows). Pre-planning confirmed `continuationReferenceSections` is in-memory only, so no migration is required for this subtask.
   - **Explicitly deferred to subtask 4:** pack manifest `baseline:` strings in `platform.yaml` and the parser in `ShellContentLoader.kt:220` (plus the `validateGovernedSkill` body-shape branch). Reason: `ShellContentLoader.validateGovernedSkill` enforces wrapper-shape constraints (`Descriptor`/`Execution`/`Ceremony` H2s + exact rendered match) on whichever path the manifest's `baseline:` resolves to; flipping the baseline to `content.md` here would route those wrapper-shape constraints at authored content.md, which cannot satisfy them. Subtask 4 retires `validateGovernedSkill`'s wrapper-shape branch alongside the SKILL.md deletion, so the baseline pointer can move cleanly there.
   - `AuthoringOperations.kt:9,45,55` strings are documentation of the wrapper surface (e.g. `generated_surface = listOf("SKILL.md")`, "Regenerate wrappers with render instead of hand-editing SKILL.md") and remain accurate while the wrapper is on disk through subtasks 1–3. They are NOT load-bearing identity references and intentionally remain unchanged in this subtask.
6. **Frontmatter migration (added during subtask 1 execution):** every governed skill's `content.md` gains the same YAML frontmatter block its sibling `SKILL.md` carries today (the existing `name:` + `description:` keys; nothing else). After this step `content.md` is the canonical authoring surface and the wrapper `SKILL.md` becomes a faithful render of it (see in-scope change #7). Sweep covers all skills under `skills/`, `platform-packs/`, and `addons/` (~26 files at the time of writing).
7. **Renderer canonicalization (added during subtask 1 execution):** `AuthoringRender.renderWrapper` sources frontmatter from `target.contentFile` (the new canonical surface) instead of `target.skillFile`, so every `upgrade()`/`render` keeps `SKILL.md` byte-for-byte aligned with `content.md` through subtasks 2 and 3. `ScaffoldTemplateRendering.renderContentBody` and `AuthoringContentMutation.coerceFullContentText` are updated so newly scaffolded skills emit frontmatter and CLI `edit`/`fill` operations preserve it; both fail loudly when frontmatter is missing or stacked rather than silently corrupting `content.md`.
8. Test migration that is purely about the marker (fixture-write tests in scaffold/discovery test files): switch fixtures from writing `SKILL.md` to writing `content.md`. Delete drift tests along with the drift check.

## Acceptance criteria for this subtask

- AC #2 fully satisfied: every tree-walk filter that matched `SKILL.md` now matches `content.md`; `AuthoringTarget` identifies a skill by its `content.md` path.
- AC #4 fully satisfied: `SkillMdShapeValidator.kt` validates `content.md` frontmatter; `AuthoringMutation.kt`'s render drift check is removed.
- AC #8 partial — workflow-label half satisfied here: `WorkflowEngine.kt:309-311`, `FeatureImplementWorkflowDefinition.kt`, and `FeatureVerifyWorkflowDefinition.kt` reference `content.md`. Workflow step IDs preserve ordering. `continuationReferenceSections` is in-memory only, so no `DatabaseMigrations` entry is required. The pack-manifest baseline + `ShellContentLoader.kt:220` parser portion of AC #8 is explicitly **deferred to subtask 4** for the reason given in in-scope item 5; AuthoringOperations.kt:9/45/55 wrapper-surface strings remain unchanged because they are documentation, not identity references.
- AC #6 (new, introduced by subtask 1's frontmatter migration): every governed `content.md` carries the same `name:` + `description:` frontmatter block its sibling `SKILL.md` does. `renderWrapper` now sources frontmatter from `content.md` so the two stay byte-for-byte aligned for the rest of the migration.
- Test migration partial: fixture-write tests that exercised the validator switched to `content.md`; drift tests deleted; new coverage added for the rewritten validator (`SkillMdShapeValidatorTest.kt` with strict/permissive body-shape mode), the orphan-path discovery branches (`RepoValidationRuntimeTest.kt`), and the failure modes of `renderContentBody`/`coerceFullContentText` (`AuthoringContentMutationTest.kt`, `AuthoringOperationsTest.kt`). `generation_drift` removal is pinned by a strict key-set equality test in `AuthoringOperationsTest.kt`.
- The repo still installs and `(cd runtime-kotlin && ./gradlew check)` passes — committed `SKILL.md` and pointer files are still present on disk so install (which still copies the source tree verbatim at this stage) keeps working.

## Non-goals

- Do not delete any committed `SKILL.md` or pointer file from `skills/`, `platform-packs/`, or `addons/`.
- Do not modify `InstallPrimitives.kt` or the install staging shape (subtask 2).
- Do not add the new `skill-bill render` CLI command (subtask 3).
- Do not introduce snapshot tests or the CI drift check (subtask 3).
- Do not change `platform.yaml` `pointers:` schema, `content.md` authoring conventions, or frontmatter shape.

## Dependencies

None. This is the first subtask.

## Validation

`bill-quality-check`: `(cd runtime-kotlin && ./gradlew check)`, `npx --yes agnix --strict .`, `scripts/validate_agent_configs`. All must pass with committed `SKILL.md` and pointer files still on disk.

## Recommended next prompt

Run bill-feature-implement on .feature-specs/SKILL-40-hide-generated-skill-artifacts/spec_subtask_1_marker-and-validator.md.
