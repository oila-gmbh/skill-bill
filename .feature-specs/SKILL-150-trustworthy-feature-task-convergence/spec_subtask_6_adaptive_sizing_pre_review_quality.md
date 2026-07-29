# SKILL-150 Subtask 6: Adaptive Sizing and Pre-Review Quality

## Scope

Add evidence-based feature sizing and decomposition enforcement, resolve a minimum review depth from risk and change breadth, and make non-test build checks available during implementation, review, and validation while keeping test execution exclusive to validation.

## Acceptance Criteria

1. Preplan and plan emit bounded complexity signals for task count, dependency depth, module and boundary breadth, persistence or migration work, security or privacy impact, concurrency or lifecycle impact, platform count, and expected changed-path surface.
2. Size and decomposition policy is declared through governed runtime contracts or manifest-driven configuration, not hard-coded platform lists or agent prose.
3. A direct plan exceeding the resolved complexity policy re-enters plan for decomposition or an explicit governed override before implementation launches.
4. Cross-module persistence, privacy, process-boundary, crash-recovery, and CLI work comparable to SKILL-134 resolves as large or decomposed under the default policy.
5. Review routing resolves a minimum substance depth from feature size, affected boundaries, and risk; an explicit inline mode may select execution mode but cannot silently reduce required review coverage.
6. High-risk or broad changes can resolve to delegated or parallel specialist review through existing agent and platform-pack contracts, with the resolved mode and rationale shown at confirmation and persisted.
7. Implementation, review, and validation may run deterministic non-test checks, including build, compilation, formatting, static-analysis, schema-parity, and migration checks selected through platform manifests.
8. Commands that execute tests run only during validation. A build remains permitted before validation when it compiles production or test sources without executing tests.
9. A non-test quality failure found during implementation or review returns a structured, durable repair batch to the mutating phase without consuming an additional code-review pass or erasing unrelated audit-clearance evidence.
10. A successful non-test quality result is checkpoint-bound and reused on unchanged resume; any semantic code change invalidates it and requires rerun.
11. Final validation remains mandatory and runs the complete repository quality commands, including all required tests, after review remediation.
12. Status and telemetry distinguish plan decomposition, review-depth escalation, non-test quality repairs, review passes, and final validation.

## Non-Goals

- Using line count as the sole sizing signal.
- Making every feature use parallel review.
- Treating non-test checks from implementation or review as final validation.
- Hard-coding Gradle commands into generic orchestration when a platform checker owns them.
- Weakening an explicit user request for deeper review.
- Restricting production wiring or quality repair to a previously captured path inventory.

## Dependency Notes

Depends on Subtasks 2 through 5 so sizing, quality repair, review generations, and scoped checkpoints use truthful and durable state.

## Validation Strategy

- Classify representative small, medium, large, and decomposed plans, including a SKILL-134-shaped fixture.
- Test configuration overrides, invalid configuration, platform-pack routing, and absence of a platform checker.
- Introduce deterministic compile, formatting, schema-version, and migration-list failures; assert implementation and review may detect and repair them without running tests.
- Assert test-executing commands are rejected outside validation while build commands that only compile test sources remain allowed.
- Change code after a passing focused check and assert checkpoint invalidation reruns the check.
- Verify final validation still executes the full configured quality suite.

## Next Path

Continue with Subtask 7 to migrate live state and prove the complete convergence model end to end.
