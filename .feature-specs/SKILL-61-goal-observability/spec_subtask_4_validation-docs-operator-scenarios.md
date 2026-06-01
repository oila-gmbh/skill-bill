---
status: Pending
---

# SKILL-61 Subtask 4 - Validation, Docs, and Operator Scenarios

Parent spec: [.feature-specs/SKILL-61-goal-observability/spec.md](./spec.md)
Issue key: SKILL-61

## Scope

Complete cross-boundary validation and documentation for the observability
feature. Prove the feature works across interruption, resume, reset, completion,
dirty worktrees, and long-running output without depending on nested subagents.

## Acceptance Criteria

1. End-to-end tests cover an interrupted goal run that resumes with coherent
   observability state.
2. End-to-end tests cover reset/completion cleanup so stale active events are
   not displayed after terminal state.
3. Git/file activity tests cover clean, dirty, renamed, deleted, and untracked
   file scenarios as supported by the selected implementation.
4. Documentation explains the runtime-owned flat worker model and why nested
   subagents are optional debug convenience rather than a reliability contract.
5. Documentation includes examples for default progress, watch/status, diff
   stat, and bounded hunk output.
6. Existing SKILL-56/SKILL-58 goal-runner behavior remains documented and does
   not regress.
7. Maintainer validation passes:
   - `skill-bill validate`
   - `(cd runtime-kotlin && ./gradlew check)`
   - `npx --yes agnix --strict .`
   - `scripts/validate_agent_configs`

## Non-Goals

- Do not expand observability into remote telemetry dashboards.
- Do not add launch/release automation unrelated to goal observability.
- Do not retrofit historical workflow rows beyond current loud-fail behavior.

## Dependency Notes

Depends on: 3
This is the final integration, docs, and validation pass.

## Validation Strategy

Run the full maintainer validation command set and include targeted regression
tests for the operator scenarios listed above.

## Next Path

Run final quality check and PR description for SKILL-61.

## Spec Path

.feature-specs/SKILL-61-goal-observability/spec_subtask_4_validation-docs-operator-scenarios.md
