# SKILL-149 Subtask 1 - Post-Review Unit-Test Value Find-and-Fix Phase

Parent:
`.feature-specs/SKILL-149-post-review-unit-test-value-fix/spec.md`

## Scope

Implement the mandatory post-review `unit_test_value_fix` phase across governed
prose and runtime feature-task paths.

The parent prepares a least-context, versioned projection and launches a
dedicated provider-neutral worker through the selected agent harness. The worker
performs one find-and-fix operation against allowlisted unit-test files, emits a
bounded receipt, and exits. It cannot rediscover inputs, change production code,
block advancement, loop, or verify its own work.

In scope:

- Governed `bill-feature-task` prose/runtime/goal-child phase instructions.
- Runtime workflow definitions, state, persistence, telemetry, projections,
  validators, launch requests, and result mapping.
- Provider-neutral native-agent source plus generated-provider parity.
- Governed `bill-unit-test-value-check` economic-value rubric.
- Exact writable-path enforcement and post-run delta validation.
- Non-blocking disposition handling and one-shot resume/crash semantics.
- Tests and documentation required by changed contracts.

## Acceptance Criteria

1. Prose tasks, runtime tasks, and goal children execute
   `unit_test_value_fix` once after final review and before validation.
2. No changed unit tests produces a durable `skipped_no_unit_tests` receipt
   without launching a worker.
3. With changed unit tests, a dedicated subagent performs exactly one
   classify-and-fix operation using only the parent-supplied projection.
4. The parent projection is versioned and bounded and contains only checkpoint
   identity, changed test inputs, necessary read-only production context,
   relevant acceptance criteria, the unit-test-value rubric, exact writable test
   paths, budgets, and the output contract.
5. The worker cannot rediscover the repository, Git state, paths, routing,
   `AGENTS.md`, skills, add-ons, learnings, telemetry, or MCP tools. It cannot use
   network access or delegate further.
6. Only parent-allowlisted unit-test files are writable. Production context and
   all other paths remain read-only, and the parent rejects any out-of-scope
   resulting delta.
7. The worker does not run a second value check, tests, code review, or quality
   validation after editing. Existing downstream validation owns test execution.
8. Every outcome, including weak tests, partial/no fixes, invalid output,
   timeout, unavailable provider worker, forbidden operations, and rejected
   deltas, produces a bounded receipt and advances.
9. The phase has no retry, remediation, review, implementation, or other
   backward edge and does not consume existing review-fix capacity.
10. A terminal phase receipt prevents duplicate launch on resume or crash
    reconciliation.
11. The receipt contains bounded verdict/action/disposition/provider/count
    fields and excludes raw prompts, complete reports, bodies, hunks, and
    line-level evidence.
12. Provider-specific Codex, Claude Code, Copilot, OpenCode, Junie, Cursor, and
    zcode launch paths preserve identical projection bytes, isolation rules,
    writable allowlist, one-shot behavior, and output schema. Missing launch
    support does not cause inline-parent fallback.
13. Contract/schema changes have version constants, typed errors, loud-fail parse
    seams, parity tests, and legacy/resume coverage where applicable.
14. `bill-feature-verify` workflow and verdict semantics remain unchanged.
15. The governed unit-test-value rubric requires three judgments for every test
    or cohesive group: the meaningful realistic regression caught, survival of
    behavior-preserving internal refactoring, and whether the protected behavior
    justifies lifetime maintenance.
16. The worker identifies and removes or consolidates near-identical
    trivial-branch tests, implementation-restatement, excessive mock
    verification, private-detail coupling, oversized redundant fixtures, and
    passing tests that would not catch a realistic regression.
17. Every retained or rewritten test has a concrete behavioral-regression
    justification. Coverage percentage, branch count, test count, or assertion
    existence is insufficient.
18. The worker produces the smallest valuable behavioral matrix, prioritizing
    important happy paths, boundaries, failures, state transitions, and
    historically fragile interactions over exhaustive generated cases.
19. The receipt distinguishes kept, rewritten, consolidated, and deleted tests
    and includes bounded before/after counts.
20. Governed skill changes use canonical `content.md`; generated runtime/install
    output is not committed, and `./install.sh` refreshes local staging after
    source or renderer changes.
21. All repository validation gates pass:

    ```bash
    skill-bill validate
    (cd runtime-kotlin && ./gradlew check)
    npx --yes agnix --strict .
    scripts/validate_agent_configs
    ```

## Non-Goals

- Blocking task or goal progression because of unit-test quality.
- A second verification or find-and-fix iteration.
- Production-code remediation.
- Re-reviewing test-only edits.
- General coverage improvement or platform-specific testing policy.
- Altering `bill-feature-verify` verdict semantics.
- A fixed preferred number of tests; six strong tests is an illustration of the
  value principle, not a universal threshold.

## Dependency Notes

No feature dependency. Preserve existing review ordering, remediation limits,
and validation behavior.

## Validation Strategy

- Assert workflow ordering, one-shot execution, and no backward edge.
- Exercise every non-blocking terminal disposition.
- Validate minimal projection construction, budgets, and producer/consumer
  parity.
- Exercise economic-value classification across cohesive test groups, including
  near-identical generated tests collapsing to the smallest matrix that
  preserves distinct behavior.
- Attempt all forbidden discovery and mutation operations.
- Verify allowlisted test edits survive and every other path is rejected.
- Run provider launch-contract tests for all supported harnesses, including
  unavailable-worker and malformed-output cases.
- Verify durable terminal receipts suppress duplicate launches after resume.
- Run focused module tests, then the full repository gates.

## Next Path

This is the only subtask. On completion, finish the parent goal.
