# SKILL-146 Subtask 7: Cross-surface acceptance matrix and repository gates

## Scope

Complete the integrated acceptance matrix across runtime, prose continuation, feature verification, delegated review, goal-child, standalone, remediation, retry, crash-resume, and end-to-end execution. Fix integration-only failures without widening contracts, prove required-field presence and forbidden-context absence, and run every repository gate.

## Acceptance Criteria

1. Parent AC 1–24 and 27–30 hold together across all launch, continuation, remediation, provider, and persistence surfaces.
2. Parent AC 25 has a complete presence-and-absence proof for each consumer.
3. Parent AC 26 has cross-surface documentation, contract, source, mapping, fixture, and golden agreement.
4. Parent AC 31 focused suites pass.
5. `skill-bill validate` passes.
6. `(cd runtime-kotlin && ./gradlew check)` passes.
7. `npx --yes agnix --strict .` passes.
8. `scripts/validate_agent_configs` passes.
9. No generated wrapper, support pointer, provider-native output, or installed staging artifact is committed, and unrelated working-tree changes remain intact.

## Non-Goals

- Unrelated refactoring, weakened assertions, silent truncation, or widened projections.
- Installer execution during goal continuation.

## Dependency Notes

Depends on Subtasks 1–6 and is the terminal integration unit. Repair failures in their owning boundaries.

## Validation Strategy

- Run focused contract, domain, application, persistence, runtime, prose, verification, review, goal-child, standalone, retry, resume, and end-to-end suites.
- Run the four repository gates listed above.
- Inspect provider goldens and Git status for forbidden context and generated/unrelated changes.

## Next Path

Complete the goal after all gates pass and the final subtask is committed.

