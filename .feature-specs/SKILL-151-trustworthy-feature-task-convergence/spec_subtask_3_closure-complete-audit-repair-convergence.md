# SKILL-151 Subtask 3 - Closure-complete audit repair convergence

Parent spec: [.feature-specs/SKILL-151-trustworthy-feature-task-convergence/spec.md](./spec.md)
Issue key: SKILL-151

## Scope

Update audit convergence repositories, repair continuation, reconciliation, run-loop gates, prompt projections, and telemetry. Build exactly one active closure-complete unresolved repair batch for audit re-entry. Carry recurring gap identities and every unresolved repair item across generations, and require subsequent audits to disposition every carried gap before clearance. Treat file, checkpoint, delta, or commit-hash changes as re-evaluation inputs rather than blockers or schema failures. Preserve the audit production-behavior boundary.

## Acceptance Criteria

1. Audit re-entry receives one complete unresolved repair batch with dependency order and bounded evidence.
2. Every subsequent audit dispositions all carried gaps before the phase clears.
3. Repeated claims cannot close a recurring production defect without distinct durable repair evidence.
4. File, delta, checkpoint, or commit changes may create a new audit generation but cannot block audit merely because identity changed.
5. A correct closure-complete repair requires at most one subsequent audit verification pass.
6. Prior audit evidence remains append-only across re-entry, retries, resume, and repository changes.

## Non-Goals

- Weakening audit to reduce iteration count.
- Moving validation concerns into completeness audit.
- Adding or modifying tests or test infrastructure.

## Dependency Notes

Depends on: 1, 2

Audit convergence consumes durable evidence from subtask 1 and truthful implementation continuations from subtask 2.

## Validation Strategy

Run existing focused module checks and repository validation commands. Add or modify no tests or fixtures.

## Next Path

Proceed to review generations, carried findings, and approval gating.

## Spec Path

.feature-specs/SKILL-151-trustworthy-feature-task-convergence/spec_subtask_3_closure-complete-audit-repair-convergence.md
