# SKILL-149 - Mandatory Post-Review Unit-Test Value Fix

## Mode

single_spec

One implementation unit adds a mandatory, non-blocking, one-shot unit-test value
find-and-fix phase after feature-task review.

## Intended Outcome

Every `bill-feature` task or goal child runs `bill-unit-test-value-check` after
review through a dedicated subagent. The subagent receives an authoritative,
bounded projection prepared by the parent, identifies low-value unit tests,
applies test-only fixes once, emits a typed receipt, and lets the workflow
continue.

The phase is mandatory in the workflow graph but never becomes a quality gate:
it cannot block, reopen implementation, trigger another review, or verify its own
changes.

## Problem

`bill-feature-task` currently reaches unit-test-value checking only indirectly
when a platform review pack chooses that lens. This makes the behavior
pack-dependent and folds test-value assessment into the review topology.
`bill-feature-verify` has an independent unit-test-value step, but the feature
implementation workflow has no equivalent mandatory post-review phase.

A naïve subagent launch would also violate the least-context runtime boundary.
The worker must not rediscover repository state, changed files, skills, routing,
guidance, or MCP capabilities. Different agent harnesses expose different
default tools and subagent launch mechanics, so the restriction must be carried
and enforced consistently by every supported provider adapter.

## Scope

- Add a stable post-review phase to prose, runtime, task, and goal-child feature
  flows.
- Launch a dedicated unit-test-value worker for that phase.
- Have the parent resolve the immutable checkpoint, changed unit-test files and
  hunks, relevant production-code context, applicable acceptance criteria,
  governed unit-test-value rubric, writable test paths, budgets, and output
  contract before launch.
- Deliver those inputs through one versioned, bounded projection.
- Allow the worker to modify only parent-declared unit-test paths.
- Persist a compact typed receipt covering findings, actions, skipped work, and
  launch or output failures.
- Strengthen the governed `bill-unit-test-value-check` rubric around economic
  value, refactor resilience, duplication, and the lifetime cost of owned tests.
- Preserve equivalent behavior across supported Codex, Claude Code, Copilot,
  OpenCode, Junie, Cursor, and zcode launch adapters.
- Update governed skill content, runtime workflow contracts, telemetry/state
  schemas, launch wiring, validators, tests, and generated-output parity.

## Unit-Test Economic Value

Generated tests are cheap to produce but not free to own. Future comprehension,
execution time, flakiness, fixture upkeep, and refactoring friction count against
a test's value. Passing tests and increased coverage do not by themselves
justify that cost.

The governed rubric and worker judge each test or cohesive test group with three
questions:

1. What meaningful and realistic regression would this catch?
2. Would it survive an internal refactor when externally visible behavior stays
   unchanged?
3. Is the protected behavior important enough to justify maintaining the test?

A weak answer to any question is a signal to delete, consolidate, or rewrite the
test. The preferred result is the smallest behavioral test matrix that protects
important outcomes, not exhaustive mechanically generated coverage.

The rubric identifies near-identical tests for trivial branches, tests that
restate implementation steps, excessive mock-interaction verification, coupling
to private implementation details, oversized fixtures for already-covered
behavior, and passing tests that would not fail for a realistic regression.

For ordinary business features, selection prioritizes the happy path, important
boundaries, meaningful failures, state transitions, and a small number of
historically fragile interactions. Setters, individual mapping lines, generated
boilerplate, impossible states, and framework behavior do not earn tests merely
because generating them is inexpensive.

## Acceptance Criteria

1. Every feature-task workflow graph contains a stable
   `unit_test_value_fix` phase immediately after its final review phase and
   before validation or the next existing phase. Prose tasks, runtime tasks, and
   goal children preserve the same ordering.
2. The phase is always visited exactly once per task or goal child. If the
   authoritative diff contains no unit tests, it records `skipped_no_unit_tests`
   and advances without launching a worker.
3. When unit tests are present, the parent launches a dedicated
   unit-test-value subagent. The phase performs one bounded sequence only:
   inspect the supplied projection, classify meaningful test-value findings,
   apply eligible test-only fixes, and emit its receipt.
4. The phase never blocks workflow advancement. `Strong`, `Mixed`, `Weak`,
   partial-fix, no-fix, launch-failure, timeout, malformed-output, and internal
   error dispositions all produce bounded terminal receipts and advance to the
   next phase.
5. The phase has no backward edge. It cannot reopen implementation, request
   review remediation, consume the feature review-fix allowance, invoke another
   review, or create a phase-local retry/fix loop.
6. The worker does not re-run or re-verify unit-test-value checking after its
   edits. It also does not run the repository quality gate or tests; the normal
   downstream validation phase remains responsible for detecting invalid edits.
7. Before launch, the parent materializes a versioned projection containing
   only:
   - immutable repository checkpoint and comparison identity;
   - changed unit-test paths with line-addressable hunks or bounded file bodies;
   - bounded production-code bodies needed to understand the exercised behavior;
   - relevant acceptance criteria;
   - the governed `bill-unit-test-value-check` rubric and classification/output
     rules;
   - the exact writable unit-test path allowlist;
   - output schema, byte/collection budgets, and phase-local instructions.
8. The projection excludes code-review findings and reports, completeness or
   feature-flag output, unrelated repository files, complete artifact maps,
   credentials, MCP catalogues, and unrelated skill content.
9. Projection production and launch consumption share one validator. Oversized,
   missing, stale-checkpoint, out-of-allowlist, or schema-invalid projections
   fail before worker launch, produce a non-blocking phase receipt, and advance.
   The runtime does not truncate or fall back to broader repository access.
10. The worker treats the supplied projection as authoritative. It must not
    rediscover repository status, branches, diffs, paths, stack or platform
    routing, `AGENTS.md`, skills, add-ons, learnings, telemetry ownership, or MCP
    capabilities.
11. Worker tool policy denies MCP calls, skill discovery/loading, broad
    filesystem search, Git discovery, network access, subagent delegation, and
    writes outside the exact unit-test path allowlist. Any attempted forbidden
    operation is rejected and recorded in the receipt without blocking the
    workflow.
12. Production-code context is read-only. A proposed or attempted production
    code edit is rejected; only files explicitly classified and allowlisted by
    the parent as unit-test files may change.
13. The parent verifies the worker's resulting file delta against the writable
    allowlist before accepting it. Out-of-scope changes are not retained, the
    receipt records `rejected_out_of_scope_delta`, and the workflow advances.
14. The typed bounded receipt records the original value verdict, finding count,
    fixed/deleted/unchanged counts, touched test paths, non-applied reason counts,
    forbidden-operation count, launch provider, terminal disposition, and
    output-contract version. It does not persist raw prompts, full reports,
    production bodies, hunks, or line-level evidence in workflow state.
15. Provider-neutral worker instructions are authored once and rendered into
    provider-specific launch artifacts. Codex, Claude Code, Copilot, OpenCode,
    Junie, Cursor, and zcode adapters preserve identical projection bytes,
    writable allowlist, forbidden-operation policy, one-shot semantics, and
    receipt schema; adapter behavior does not depend on prose-only convention.
16. Harness-specific tests cover tool-surface differences, unavailable native
    subagent support, process/output framing, cancellation, timeout, and agents
    that otherwise default to repository or skill discovery. Unsupported or
    unavailable launch capability resolves to a non-blocking receipt rather than
    substituting a broader worker or running the phase in the parent.
17. Existing `bill-feature-verify` unit-test-value workflow behavior remains
    independent. Shared improvements to the governed
    `bill-unit-test-value-check` rubric apply consistently wherever the skill is
    used, but the new feature-task worker receives no verify-workflow artifacts.
18. Telemetry and operator status expose only phase id, provider, counts,
    duration, and terminal disposition. Goal-facing output does not expose paths,
    hunks, raw worker output, or private evidence.
19. Contract changes loud-fail at parse seams with typed errors and include
    schema/version parity, persistence, resume, crash-recovery, and legacy-record
    tests where durable state shape changes.
20. Governed source remains in `content.md`; generated wrappers, support
    pointers, and provider-specific native-agent outputs are not committed.
21. `bill-unit-test-value-check` makes the three economic-value questions
    normative: meaningful regression caught, resilience to behavior-preserving
    internal refactoring, and importance sufficient to justify lifetime
    maintenance.
22. The rubric explicitly detects near-duplicate trivial-branch tests,
    implementation-restatement, excessive mock verification, private-detail
    coupling, oversized redundant fixtures, and passing tests with no realistic
    regression sensitivity.
23. Worker actions prefer deletion or consolidation when protection overlaps or
    lacks economic value. Rewriting is reserved for important behavior whose
    current test is coupled, weak, or mechanically asserted.
24. The worker reasons over cohesive groups as well as individual tests so
    several individually plausible but near-identical cases can become one
    table-driven or parameterized behavioral case, or be removed when already
    covered.
25. Every retained or rewritten test has a concrete behavioral-regression
    justification. Coverage percentage, branch count, test count, or assertion
    existence is insufficient.
26. Test selection favors a small behavioral matrix covering important happy
    paths, boundaries, failures, state transitions, and historically fragile
    interactions. It does not pursue exhaustive tests for setters, mapping
    lines, generated boilerplate, impossible states, or framework behavior.
27. The receipt distinguishes `kept`, `rewritten`, `consolidated`, and `deleted`
    actions and records before/after test-case counts without storing raw test
    content.
28. Repository validation gates pass:

    ```bash
    skill-bill validate
    (cd runtime-kotlin && ./gradlew check)
    npx --yes agnix --strict .
    scripts/validate_agent_configs
    ```

## Constraints

- Preserve the immutable review baseline and existing review remediation cap.
- Parent-owned discovery happens once; workers cannot widen their own context.
- The no-block and no-reverification guarantees apply even when the worker
  reports weak tests or fails to apply a fix.
- Runtime and prose modes must expose the same phase semantics and bounded
  receipt.
- Existing dirty worktree changes are outside this feature's ownership.

## Non-Goals

- Turning unit-test value into an approval gate.
- Re-running code review after test-only edits.
- Adding a second unit-test-value pass.
- Modifying production code in response to test-value findings.
- Expanding the worker into general testing, coverage, or repository-quality
  validation.
- Changing the independent `bill-feature-verify` verdict policy.
- Adding platform-specific test-value rubrics.
- Treating a fixed numerical test-count target as a quality rule; value and
  distinct behavioral protection determine the appropriate matrix.

## Validation Strategy

- Workflow-definition tests assert the exact
  `review -> unit_test_value_fix -> validate` ordering and absence of backward
  edges.
- Prose/runtime parity tests assert one visit, skip behavior, and non-blocking
  advancement for every terminal disposition.
- Projection producer/consumer contract tests cover exact allowed fields,
  budgets, checkpoint identity, stale inputs, missing context, and forbidden
  broad fallback.
- Isolation tests attempt Git, filesystem discovery, skill loading, MCP,
  network, delegation, production edits, and non-allowlisted test edits.
- Delta-guard tests accept allowlisted test-only edits and reject every
  out-of-scope path without blocking the workflow.
- Economic-value tests cover duplicate generated cases, implementation-coupled
  assertions, mock-only verification, redundant fixtures, and consolidation to
  the smallest matrix preserving distinct behavior.
- Provider matrix tests exercise native launch and output framing for every
  supported harness and the unavailable-capability receipt path.
- Resume and crash tests prove the one-shot phase is not launched twice after a
  durable terminal receipt.
- Focused Gradle and governed-content tests run before the full repository gates.

## Next Path

Prepare and execute the single governed subtask through:

```bash
skill-bill goal SKILL-149
```
