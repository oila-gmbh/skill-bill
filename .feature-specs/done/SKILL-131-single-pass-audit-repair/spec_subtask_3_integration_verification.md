# SKILL-131 Subtask 3: Integration and Verification

## Scope

Complete operator guidance, status and telemetry projection, regression coverage, end-to-end validation, and generated-install synchronization for the audit-repair workflow.

## Acceptance Criteria

1. Status artifacts and telemetry expose first-pass convergence, recurring/new gap counts, attempted/resolved repair-item counts, and audit-gap iteration count using compact structured evidence only.
2. Crash/resume tests cover interruption before plan persistence, during repair, after repair before phase completion, and after audit completion without duplicate fixes, lost gaps, renamed identifiers, or invalid advancement.
3. A SKILL-128-derived regression proves multiple broad gaps create one complete plan and cannot complete after partial production-only repair or deferred integration/tests.
4. A Markdown-prefixed `gaps_found` regression proves the canonical envelope takes the audit-gap backward edge rather than advancing to validation.
5. Governed `content.md` guidance and operator documentation describe structured audit planning, exhaustive remediation, recurring/new gaps, and non-progress failure; generated wrappers remain uncommitted and local installs are refreshed when required.
6. Contract, domain, application, persistence, CLI/status, telemetry, standalone, goal-child, and end-to-end acceptance and rejection tests pass, followed by `skill-bill validate`, `./gradlew check`, strict Agnix validation, and agent-config validation.

## Non-Goals

- Guaranteeing repair of externally blocked or unknowable defects.
- Changing feature decomposition, PR behavior, review severity, or unrelated platform behavior.
- Removing subsequent audit verification.

## Dependency Notes

Depends on subtasks 1 and 2. It verifies their integrated behavior and completes delivery surfaces.

## Validation Strategy

Run focused regressions first, then all repository validation gates. Refresh local installs after governed skill or renderer changes and verify generated-source boundaries remain clean.

## Next Path

Finish the SKILL-131 goal after all validation gates and delivery artifacts succeed.
