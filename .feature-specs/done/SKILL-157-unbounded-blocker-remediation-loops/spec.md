# SKILL-157 - Unbounded Blocker Remediation Loops With A Warning Threshold

## Mode

decomposed

Three dependency-ordered units:

1. Remove count-based termination from blocker-driven review remediation and
   widen its durable pass state while preserving audit's already-uncapped edge.
2. Add one shared, resume-safe warning threshold for the audit and review
   remediation loops without changing their control flow.
3. Align governed prose, generated runtime guidance, and end-to-end validation
   with the runtime policy.

## Intended Outcome

Feature-task remediation does not leave a known Blocker or blocking audit gap
unfixed because an iteration counter ran out. Review continues through
`implement_fix` and re-review until no Blocker remains. Audit continues through
implementation repair and re-audit until its blocking gaps are cleared. Each
loop warns the user when it exceeds the threshold of three remediation
iterations, then continues fixing and advances normally once its blockers are
gone.

## Current Behavior

- The runtime `review_fix` backward edge has `perEdgeCap = 1`, so review permits
  only one remediation iteration.
- `GoalSubtaskReviewState` and its schema cap the durable review sequence at two
  total passes, and runtime/prose paths settle or pause when that cap is reached.
- The runtime `audit_gap` edge already has `perEdgeCap = null`, and prose already
  says audit has no fixed iteration cap. That behavior needs regression
  protection and the same long-loop warning as review.
- The runtime has durable loop counters but no user-facing warning when either
  semantic remediation loop runs longer than expected.

## Acceptance Criteria

1. A review result containing one or more unresolved Blocker findings always
   re-enters `implement_fix` and re-runs review, regardless of the current pass or
   `review_fix` iteration count; no finite pass or edge cap pauses, blocks, or
   advances the run merely because the count was reached.
2. An audit result containing blocking acceptance gaps always re-enters
   implementation repair and re-runs audit, regardless of the `audit_gap`
   iteration count; no finite count terminates that semantic remediation loop.
3. Review advances only after no Blocker remains, and audit advances only after
   no blocking audit gap remains. Existing handling of Major, Minor, and Nit
   review findings remains unchanged.
4. When either `review_fix` or `audit_gap` enters its fourth remediation
   iteration, the runtime prints a user-visible warning that names the loop,
   states that it exceeded the warning threshold of three, identifies the
   current iteration, and states that remediation will continue.
5. The warning is informational only: crossing the threshold never changes the
   transition, verdict, phase status, retry allowance, or final outcome.
6. Crash recovery and parent resume preserve unbounded pass/iteration accounting
   and do not duplicate the warning for a threshold crossing already emitted;
   remediation mutations are not double-applied.
7. Count-based bounds for malformed phase-output correction, durable-record
   regeneration, process failures, and other non-semantic repair paths remain
   unchanged. Existing evidence-based non-convergence or explicit operator stop
   paths are not converted into count caps.
8. Governed feature-task runtime, prose, goal, subtask-runner, and native-agent
   guidance describe the same unbounded-on-Blocker behavior and threshold-three
   warning policy, with parity tests preventing the old two-pass wording from
   returning.
9. `skill-bill validate`, `(cd runtime-kotlin && ./gradlew check)`,
   `npx --yes agnix --strict .`, and `scripts/validate_agent_configs` pass.

## Constraints

- Declare the warning threshold once in the runtime's semantic loop policy and
  apply it to both `review_fix` and `audit_gap`.
- Emit warnings through the existing `RuntimeDiagnostics.warning` surface; do
  not add agent-specific branching to the process runner.
- Keep transition behavior declarative and loop-generic. Review-specific state
  may supply the unresolved-Blocker signal, but the transition function must not
  identify review by phase name.
- Widening `GoalSubtaskReviewState` requires updating
  `goal-subtask-review-state-schema.yaml`, its Kotlin contract-version constant,
  loud-fail behavior, and contract parity tests according to the runtime schema
  policy.
- Review pass one retains its selected execution mode. Every remediation review
  remains inline and scoped to that round's remediation delta.
- Run `./install.sh` after governed skill or native-agent source changes so local
  installed staging reflects the new source hash; generated install output stays
  uncommitted.

## Non-Goals

- Removing or widening `FeatureTaskRuntimeFixLoopPolicy.MAX_FIX_LOOP_ITERATIONS`,
  malformed-output correction budgets, record-regeneration caps, timeouts, or
  process retry limits.
- Changing severity calibration, the definition of a review Blocker, or what
  constitutes a blocking audit gap.
- Making Major, Minor, or Nit review findings reopen remediation.
- Changing standalone `bill-code-review` or `bill-feature-verify` behavior.
- Automatically accepting unresolved blockers or removing explicit operator
  controls that are unrelated to iteration-count exhaustion.

## Validation Strategy

Use focused domain, schema, run-loop, crash/resume, diagnostics, and governed
content tests. Exercise clean completion after more than three review and audit
iterations, verify a single threshold-crossing warning for each loop, prove the
warning cannot alter control flow, and retain regression coverage for every
unrelated bounded retry path. Finish with the repository validation commands in
Acceptance Criterion 9.

## Next Path

```bash
skill-bill goal SKILL-157
```
