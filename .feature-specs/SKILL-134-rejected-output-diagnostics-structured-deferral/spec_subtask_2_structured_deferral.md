# SKILL-134 Subtask 2: Structured Audit Deferral

## Scope

Replace recursive lexical deferral detection with explicit structured remediation semantics while preserving every existing correctness gate.

## Acceptance Criteria

1. Remediation output explicitly represents deferred or remaining repair work; completed output requires an empty representation and blocked output identifies the exact unresolvable repair item.
2. The runtime validates exact repair-item identifiers, terminal outcomes, dependency order, changed-path/symbol evidence, executed verification, result evidence, and blocked-item consistency.
3. No runtime gate recursively scans summaries, evidence, verification descriptions, or other free text for words associated with later review, audit, validation, or testing.
4. Legitimate future-phase descriptions pass when structured repair results are exhaustive and terminal.
5. Actual structured deferral, missing/nonterminal results, mismatched identifiers, invalid ordering, or contradictory blocked/terminal results fail with stable rule IDs and actionable JSON paths.
6. Contract versioning, durable compatibility behavior, standalone runtime, goal-child execution, retry/resume, and diagnostic integration are covered by acceptance and rejection tests.

## Non-Goals

- Weakening exhaustive repair execution.
- Allowing later phases to substitute for carried repair items.
- Removing typed unresolvable-item blocking.
- Persisting raw rejected responses outside the diagnostic boundary from subtask 1.

## Dependency Notes

Depends on subtask 1 for shared diagnostic rule/path reporting and rejected-output capture.

## Validation Strategy

Add schema, application, runtime-loop, false-positive phrase, true-deferral, crash/resume, standalone, and goal-child tests, then run complete repository validation.

## Next Path

Finish SKILL-134 after full validation and operator documentation succeed.
