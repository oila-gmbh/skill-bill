# SKILL-233 · Subtask 7: Unused-parameter gate cleanup

## Scope

Remove the remaining `UnusedParameter` findings exposed by the architecture gate after the identifier, status, vocabulary, and runtime-context migrations.

## Acceptance Criteria

1. The exact-head `./gradlew check` reports zero `UnusedParameter` findings at the listed SQLite, application, goal-planning, learning, review, telemetry, workflow, and work locations.
2. Obsolete parameters are removed through the RuntimeContext migration or from the owning scope; no suppression, exemption, or baseline entry is added.
3. `skill-bill validate` passes and all affected callers and tests compile.

## Non-Goals

- Do not refactor unrelated code or change observable behavior.
- Do not suppress a finding to make the gate pass.

## Dependency Notes

Depends on subtask 6 because most findings are obsolete path or context parameters.

## Validation Strategy

Run the exact-head `./gradlew check`, `skill-bill validate`, and targeted tests for every changed service boundary.

## Next Path

`skill-bill goal SKILL-233`
