# SKILL-157 Subtask 2 - Shared Loop Warning Threshold

Parent spec: [.feature-specs/SKILL-157-unbounded-blocker-remediation-loops/spec.md](./spec.md)
Issue key: SKILL-157

## Scope

Add one user-visible, resume-safe warning when either semantic remediation loop
crosses above three iterations, with no effect on control flow.

In scope:

- Declare a single semantic remediation warning threshold of `3` and associate it
  with both `review_fix` and `audit_gap` without giving either loop a cap.
- At the shared backward-edge entry seam, detect the transition into remediation
  iteration `4`. Emit `RuntimeDiagnostics.warning` with the loop id, issue/workflow
  and subtask identity available at that seam, threshold `3`, current iteration,
  and an explicit statement that remediation will continue.
- Persist or deterministically derive enough acknowledgement state that a crash or
  parent resume does not print the same threshold-crossing warning twice. Each
  loop has independent warning state for the same subtask.
- Preserve durable iteration counters and their existing status/finished telemetry
  meaning for arbitrarily high values.
- Make warning delivery best-effort diagnostics: it must not change the chosen
  transition, consume a retry, overwrite a phase outcome, or block the workflow.

## Acceptance Criteria

1. Entering `review_fix` iteration 4 emits a user-visible warning that names
   `review_fix`, says the warning threshold of 3 was exceeded, gives iteration 4,
   identifies the current work, and says remediation will continue.
2. Entering `audit_gap` iteration 4 emits the equivalent warning for `audit_gap`.
3. Iterations 1 through 3 emit no threshold warning, and iteration 5 or later does
   not duplicate the already-emitted crossing warning for that loop and subtask.
4. A crash or parent resume before, during, or after iteration 4 produces at most
   one warning for that loop's threshold crossing and resumes the correct phase.
5. Warning emission never changes the backward-edge destination, edge iteration,
   verdict, phase status, unresolved item state, or final completion outcome.
6. `review_fix` and `audit_gap` warning acknowledgements are independent: each can
   warn once in the same subtask.
7. Status and finished telemetry accept and report loop iteration counts above
   three without truncation or cap-exhaustion wording.

## Non-Goals

- Periodic warnings on every later iteration; this feature warns when the loop
  crosses the threshold.
- Adding a hidden hard ceiling or changing non-semantic retry budgets.
- Adding a new output channel or agent-specific process-runner behavior.

## Dependency Notes

Depends on: 1

Subtask 1 supplies the unbounded durable iteration/pass state. This unit consumes
that state and must not reintroduce a cap while adding visibility.

## Validation Strategy

Use fake diagnostics and deterministic run-loop fixtures for iterations 1 through
5 of both loops. Assert exact warning content, independent loop acknowledgement,
no duplicate after resume, and byte-for-byte-equivalent transition outcomes with
diagnostics enabled or disabled. Add telemetry/status tests for counts above
three. Run `(cd runtime-kotlin && ./gradlew check)`.

## Next Path

Subtask 3 - governed prose and end-to-end parity.

## Spec Path

.feature-specs/SKILL-157-unbounded-blocker-remediation-loops/spec_subtask_2_shared-loop-warning-threshold.md
