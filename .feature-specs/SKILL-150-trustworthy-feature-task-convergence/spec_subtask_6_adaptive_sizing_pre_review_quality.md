# SKILL-150 Subtask 6: Adaptive Sizing and Pre-Review Quality

## Scope

Add evidence-based feature sizing and decomposition enforcement, resolve a minimum review depth from risk and change breadth, and run a focused pre-review quality seam so deterministic build failures do not consume review passes.

## Acceptance Criteria

1. Preplan and plan emit bounded complexity signals for task count, dependency depth, module and boundary breadth, persistence or migration work, security or privacy impact, concurrency or lifecycle impact, platform count, and expected changed-path surface.
2. Size and decomposition policy is declared through governed runtime contracts or manifest-driven configuration, not hard-coded platform lists or agent prose.
3. A direct plan exceeding the resolved complexity policy re-enters plan for decomposition or an explicit governed override before implementation launches.
4. Cross-module persistence, privacy, process-boundary, crash-recovery, and CLI work comparable to SKILL-134 resolves as large or decomposed under the default policy.
5. Review routing resolves a minimum substance depth from feature size, affected boundaries, and risk; an explicit inline mode may select execution mode but cannot silently reduce required review coverage.
6. High-risk or broad changes can resolve to delegated or parallel specialist review through existing agent and platform-pack contracts, with the resolved mode and rationale shown at confirmation and persisted.
7. After audit clearance and before the first code-review pass, a focused quality seam runs deterministic format, compilation, static-analysis, schema parity, migration, and changed-module test checks selected from the owned path and platform manifests.
8. A pre-review quality failure returns a structured, durable repair batch to the mutating phase without consuming a code-review pass or erasing audit clearance evidence unrelated to the failure.
9. A successful focused quality result is checkpoint-bound and reused on unchanged resume; any semantic code change invalidates it and requires rerun.
10. Final validation remains mandatory and reruns the complete repository quality commands after review remediation.
11. Status and telemetry distinguish plan decomposition, review-depth escalation, pre-review quality repairs, review passes, and final validation.

## Non-Goals

- Using line count as the sole sizing signal.
- Making every feature use parallel review.
- Treating focused pre-review checks as final validation.
- Hard-coding Gradle commands into generic orchestration when a platform checker owns them.
- Weakening an explicit user request for deeper review.

## Dependency Notes

Depends on Subtasks 2 through 5 so sizing, quality repair, review generations, and scoped checkpoints use truthful and durable state.

## Validation Strategy

- Classify representative small, medium, large, and decomposed plans, including a SKILL-134-shaped fixture.
- Test configuration overrides, invalid configuration, platform-pack routing, and absence of a platform checker.
- Introduce deterministic compile, formatting, schema-version, and migration-list failures; assert they repair before review and do not increment review passes.
- Change code after a passing focused check and assert checkpoint invalidation reruns the check.
- Verify final validation still executes the full configured quality suite.

## Next Path

Continue with Subtask 7 to migrate live state and prove the complete convergence model end to end.

