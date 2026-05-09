# SKILL-41 native-agent-composition decomposition

Parent feature overview: `.feature-specs/SKILL-41-content-md-authored-surface/spec.md`

Parent subtask: `.feature-specs/SKILL-41-content-md-authored-surface/spec_subtask_3_native-agent-composition.md`

Status: Planned

This decomposition splits native-agent composition into smaller feature-implement runs. The work crosses native-agent parsing, manifest/content resolution, install rendering, platform-pack native-agent sources, repository validation, and scripts, so it should land in dependency order.

## Acceptance criteria

1. Native-agent files remain source files and are not deleted or generated away.
2. Direct specialist-native agents can compose from the corresponding `content.md` without duplicating large execution prose.
3. Installed provider-native agents remain self-contained after native-agent composition and rendering.
4. Native-agent validation rejects broken composition directives, missing target content, or output that depends on repo-local files at install time.
5. Composition behavior remains manifest-driven or explicitly declared; do not hard-code platform shortlists.

## Non-goals

- Do not change provider-specific native-agent install locations unless required by composition.
- Do not implement unrelated native-agent provider features.
- Do not reintroduce generated governed `SKILL.md` source files.
- Do not remove legitimate provider-neutral native-agent source definitions.

## Subtasks

1. Composition foundation: add explicit, manifest-driven native-agent composition parsing and content target resolution.
2. Rendering and source migration: expand composed content into installed provider-native agents and migrate direct specialist-native source files to composition directives.
3. Validation and final coverage: reject broken composition paths and repo-local install dependencies, then run final quality gates.
