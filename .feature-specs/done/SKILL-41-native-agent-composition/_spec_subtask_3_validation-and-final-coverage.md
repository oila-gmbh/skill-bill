# Subtask 3: validation and final coverage

Parent overview: `.feature-specs/SKILL-41-native-agent-composition/spec.md`

Status: Complete

## Scope

Complete repository validation and final coverage for native-agent composition. Wire composition checks into the existing Kotlin-backed repository validation flow used by `validateRepoNativeAgents`, `RepoValidationRuntime`, runtime CLI validation tests, and `scripts/validate_agent_configs`.

Validation must reject malformed or unresolved composition directives, missing target `content.md`, missing or version-mismatched manifests, and rendered/install output that would depend on repo-local files at runtime. Keep loud-fail behavior aligned with the shell-content contract instead of adding silent fallback. Preserve the documented exception behavior such as the existing `kmp` quality-check fallback to `kotlin`; do not use that fallback as a general platform shortlist.

This subtask is also the final quality gate for the decomposed work. It should update or add regression tests that cover parser failures, manifest-driven positive paths, install self-containment, source-file preservation, and script-level agent-config validation.

## Acceptance criteria

- Native-agent files remain source files and repository validation does not delete or generate them away.
- Direct specialist-native agents can compose from corresponding `content.md` while installed provider-native agents remain self-contained.
- Native-agent validation rejects broken composition directives, missing target content, and output that depends on repo-local files at install time.
- Composition behavior remains manifest-driven or explicitly declared and avoids hard-coded platform shortlists.

## Non-goals

- Do not implement additional native-agent providers or provider-specific features.
- Do not change provider install locations unless an earlier subtask already proved it necessary.
- Do not reintroduce generated governed `SKILL.md` source files.
- Do not remove legitimate provider-neutral native-agent source definitions.

## Dependencies

Depends on subtasks 1 and 2. The final validation run needs the directive model, content resolver, composed renderer, and migrated source definitions in place.

## Validation strategy

Run focused runtime-core/native-agent and runtime-cli validation tests, then run the final gates: `(cd runtime-kotlin && ./gradlew check)`, `npx --yes agnix --strict .`, and `scripts/validate_agent_configs`. Use `bill-quality-check` as the final validation umbrella.

## Recommended next prompt

Run bill-feature-implement on `.feature-specs/SKILL-41-native-agent-composition/spec_subtask_3_validation-and-final-coverage.md`.
