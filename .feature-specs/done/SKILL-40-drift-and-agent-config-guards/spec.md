# SKILL-40 drift-and-agent-config-guards

Status: Complete

## Sources

- `.feature-specs/SKILL-40-subtask-3-render-cli-and-snapshots/spec_subtask_2_drift-and-agent-config-guards.md`
- Parent overview: `.feature-specs/SKILL-40-render-cli-and-snapshots/spec.md`

## Acceptance Criteria

1. CI drift check runs across every governed skill.
2. Drift check verifies parse/render validity.
3. Drift check verifies pointer resolvability.
4. Drift check verifies byte-identical repeated rendering.
5. Drift check is wired into `(cd runtime-kotlin && ./gradlew check)`.
6. `scripts/validate_agent_configs` fails loud on newly committed governed `SKILL.md` outputs.
7. `scripts/validate_agent_configs` fails loud on newly committed `platform.yaml` pointer files.
8. The agent-config guard remains dormant while current committed generated render outputs still exist.

## Non-goals

- Do not delete committed `SKILL.md` or pointer files.
- Do not change install pipeline behavior.
- Do not change workflow path strings, pointer schema, or `content.md` frontmatter shape.
- Do not add snapshot fixture coverage in this subtask.
- Keep `kmp` quality-check fallback behavior untouched.

## Consolidated Spec

# Subtask 2: drift and agent-config guards

Parent overview: `.feature-specs/SKILL-40-render-cli-and-snapshots/spec.md`

## Scope

Add the CI drift validation path across every governed skill. The check must verify parse/render validity, pointer resolvability, and byte-identical repeated rendering. Wire this into the existing Gradle lifecycle so `(cd runtime-kotlin && ./gradlew check)` runs it without introducing a new external build step.

Update `scripts/validate_agent_configs` and its Kotlin validation path so it fails loud on newly committed governed `SKILL.md` outputs or `platform.yaml` pointer files, while remaining dormant as long as the current committed generated render outputs still exist. This is an enforcement guard for future deletion policy, not a cleanup task.

Use the existing repo validation/runtime parsing patterns where possible. Preserve loud-fail behavior for invalid manifests, missing content, missing sections, invalid pointers, or schema drift. Keep `kmp` quality-check fallback behavior untouched.

## Acceptance criteria

- CI drift check runs across every governed skill and verifies parse/render validity, pointer resolvability, and byte-identical repeated rendering.
- Drift check is wired into `(cd runtime-kotlin && ./gradlew check)`.
- `scripts/validate_agent_configs` fails loud on committed governed `SKILL.md` or `platform.yaml` pointer files, but remains dormant while current committed render outputs still exist.

## Non-goals

- Do not delete committed `SKILL.md` or pointer files.
- Do not change install pipeline behavior.
- Do not change workflow path strings, pointer schema, or `content.md` frontmatter shape.
- Do not add snapshot fixture coverage in this subtask.

## Dependencies

Depends on subtask 1 for stable render functions and CLI semantics that the drift behavior should agree with.

## Validation strategy

Run `(cd runtime-kotlin && ./gradlew check)` and `scripts/validate_agent_configs`. Full snapshot coverage and `agnix` validation are deferred to subtask 3.
