# SKILL-137 Subtask 6 - Feature Verification Evaluator Isolation

Parent spec: [.feature-specs/SKILL-137-phase-context-minimization/spec.md](./spec.md)
Issue key: SKILL-137

## Scope

Apply least-context projections to `bill-feature-verify`. Keep feature-flag audit, code review, unit-test value checking, and completeness audit independent, then feed the consolidated verdict compact evaluator receipts rather than complete evaluator outputs.

## Acceptance Criteria

1. Input collection emits a bounded input identity/reference contract; criteria extraction receives only the spec/reference fields needed to extract acceptance criteria.
2. Diff gathering receives target identity and criteria only where criteria affect scope. It emits a deterministic diff projection containing base/head/checkpoint, normalized changed paths, statistics, owned untracked metadata, and a private reference rather than an embedded full diff body.
3. Feature-flag audit receives relevant rollout criteria and authoritative diff/repository scope but not code-review, test-value, or completeness outputs.
4. Code review receives criteria, exact diff scope/checkpoint, review policy, and applicable rubrics/add-ons; it does not receive feature-flag audit output unless an explicit bounded rollout constraint is part of the review contract.
5. Unit-test value checking receives changed test paths, relevant production paths/symbols, criteria/test obligations when useful, and exact diff scope. It does not receive the complete review result or review narrative.
6. Completeness audit receives acceptance criteria, authoritative repository/diff scope, and rollout constraints when relevant. It does not receive review findings or unit-test-value results, preserving evaluator independence.
7. Each evaluator emits a typed compact receipt containing verdict, bounded finding ids/counts, checkpoint, and private evidence references. Complete evaluator output stays private.
8. Consolidated verdict receives criteria identity plus compact feature-flag, review, test-value, and completeness receipts. It can resolve overall disposition without receiving raw reports, prompts, diffs, or telemetry.
9. The final/finish step receives only the consolidated verdict receipt and workflow identity required to close the run.
10. Verify continuation uses consumer projections identical to fresh execution and never defaults to the complete artifact map.
11. A materially changed target checkpoint invalidates all dependent evaluator receipts through explicit staleness rules; it does not combine results produced against different diffs.
12. Existing rollout-relevance decision rules, review/test/audit skill behavior, telemetry names, status output, and terminal verdict semantics remain intact.
13. Tests prove evaluator independence by asserting review fields are absent from test-value/completeness prompts and test-value fields are absent from completeness prompts, while consolidated verdict receives every required compact receipt.
14. Workflow definition, continuation directives, governed content, schemas, persistence fixtures, MCP output, and telemetry agree on the projected artifacts.

## Non-Goals

- Changing the substantive review, unit-test value, completeness, or feature-flag rubrics.
- Combining evaluators or changing the consolidated verdict policy.
- Reusing feature-task audit clearance as feature-verification evidence across different checkpoints.

## Dependency Notes

Depends on: 1.

Uses the generic projection, checkpoint, budget, and private-evidence foundation but can proceed independently of feature-task phase-specific projections.

## Validation Strategy

- Projection matrix and prompt snapshot tests for every verify step.
- Evaluator-independence forbidden-field assertions.
- Mixed-checkpoint rejection and continuation parity tests.
- Consolidated verdict receipt tests.
- Workflow/MCP golden and telemetry privacy tests.
- Focused `bill-feature-verify` runtime tests.

## Next Path

Continue with subtask 8 after subtasks 2 through 5 and 7 are also complete.

## Spec Path

.feature-specs/SKILL-137-phase-context-minimization/spec_subtask_6_feature-verification-evaluator-isolation.md
