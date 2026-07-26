# Subtask 1: composition foundation

Parent overview: `.feature-specs/SKILL-41-native-agent-composition/spec.md`

Status: Complete

## Scope

Add the native-agent composition foundation in the Kotlin runtime without changing installed output yet. Extend the strict native-agent source parser to recognize an explicitly declared composition directive that allows a provider-neutral native-agent source to compose from the corresponding governed `content.md`.

The resolver must use existing manifest-driven skill/content metadata, including `platform.yaml` declared files and shell-content loader behavior, rather than hard-coded platform or specialist shortlists. It must keep current native-agent source discovery intact under `skills/*/native-agents` and `platform-packs/*/code-review/*/native-agents`, continue excluding symlinks, and preserve deterministic ordering.

Define the directive contract in `orchestration/shell-content-contract` so later validation and authorship rules have a single documented source of truth. Parser failures should stay strict and deterministic: malformed directives, unsupported frontmatter, missing manifests, wrong shell contract versions, or unresolved content targets must loud-fail through the existing Kotlin validation paths.

## Acceptance criteria

- Native-agent files remain source files and are discovered in place; this subtask must not delete, replace, or generate away `native-agents/*.md`.
- Direct specialist-native agents have an explicit composition mechanism available that can target their corresponding `content.md`.
- Composition target lookup is manifest-driven or explicitly declared and avoids hard-coded platform shortlists.
- Broken composition directives or missing target content are represented as validation failures in the runtime model.

## Non-goals

- Do not migrate existing direct specialist-native agent files to composition directives in this subtask.
- Do not change provider-specific install locations.
- Do not implement provider-specific native-agent output changes beyond the model needed by later subtasks.
- Do not reintroduce governed generated `SKILL.md` source files.

## Dependencies

None. This subtask establishes the parser, directive contract, and manifest-driven content resolver required by rendering and validation work.

## Validation strategy

Add focused runtime-core tests for directive parsing and manifest-driven content resolution, including rejection of malformed directives and missing target content. Run the focused tests, then run `(cd runtime-kotlin && ./gradlew check)` if the implementation surface is complete enough. Full repository validation is deferred to subtask 3.

## Recommended next prompt

Run bill-feature-implement on `.feature-specs/SKILL-41-native-agent-composition/spec_subtask_1_composition-foundation.md`.
