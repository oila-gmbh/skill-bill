# SKILL-156 Subtask 3 - Remediation Loop Warning Threshold

## Scope

Make a long-running remediation loop visible without changing control flow.

In scope:

- `REMEDIATION_LOOP_WARNING_THRESHOLD: Int = 3`, declared once in
  `FeatureTaskRuntimePhaseWorkflowDefinition` and applied to both `review_fix` and
  `audit_gap`.
- Emission at the backward-edge seam in `FeatureTaskRuntimeRunLoop`: entering
  iteration `> REMEDIATION_LOOP_WARNING_THRESHOLD` writes a
  `diagnostics.warning` naming the loop id, the workflow/subtask identity, the
  iteration being entered, the threshold, and the unresolved Blocker or gap
  identifiers driving the round. The warning states that the run continues.
- One warning per entered iteration: the highest already-warned iteration is
  recorded durably per loop, so a resume or crash recovery does not re-warn an
  iteration that already warned, and each new iteration past the threshold warns
  once.
- Surfacing beyond the console: the durable warning state is readable by goal status
  and included in finished telemetry alongside the existing per-loop iteration
  counts, so a long loop is visible after the fact.
- No control-flow effect: crossing the threshold never blocks, pauses, advances,
  changes review depth, or alters the stall guard.

## Acceptance Criteria

1. Entering iteration 4 or higher of `review_fix` or `audit_gap` emits exactly one
   operator-visible warning per iteration, naming the loop id, subtask identity,
   iteration, threshold, and the unresolved identifiers.
2. Iterations 1 through 3 of either loop emit no warning.
3. The warning has no control-flow effect: a run that crosses the threshold reaches
   the same terminal outcome it would reach with warnings suppressed.
4. Resume and crash recovery do not re-emit a warning for an already-warned
   iteration, and do emit one for each new iteration past the threshold.
5. The threshold is a single named constant shared by both loops, and a test pins
   the value at 3 and asserts both loops read it.
6. Goal status and finished telemetry expose the warning state and the cumulative
   per-loop iteration counts for the subtask.

## Non-Goals

- Any behavior change at the threshold beyond emitting the warning.
- A configurable or per-repo threshold.
- New output channels; emission goes through the existing diagnostics seam.

## Dependency Notes

Depends on Subtask 1 for the unbounded loop accounting. Independent of Subtask 2's
convergence policy, but shares the backward-edge seam, so land it after Subtask 2 to
avoid conflicting edits at that seam.

## Validation Strategy

- Run-loop tests asserting no warning at iterations 1–3 and one warning per
  iteration at 4, 5, and 6, for both loops.
- A test asserting the terminal outcome is identical with and without warnings.
- Resume test: a run resumed at an already-warned iteration emits no duplicate.
- Telemetry and status assertions for the warning state and iteration counts.
- `(cd runtime-kotlin && ./gradlew check)`.

## Next Path

Subtask 4 — governed prose and contract parity.
