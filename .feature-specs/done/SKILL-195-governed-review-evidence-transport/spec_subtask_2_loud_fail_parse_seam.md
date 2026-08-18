# SKILL-195 Subtask 2 — Loud-fail the parse seam and diagnose register absence

## Scope

Stop collapsing three different failures into one message.

`attributeInlineFindings` wraps the entire parse in
`runCatching { ParallelReviewFindingParser.parse(stdout) }.getOrDefault(emptyList())`
(`runtime-application/.../review/ParallelCodeReviewRunner.kt:1473`). A parser fault, a malformed
register, and a lane that never ran all reduce to the same empty list, and then to the same
sentence from `registerAbsenceReason` (`:1545`): *"the review did not execute."* The runner holds
`rawOutput` (`:1250`) and discards it.

Deliver:

- Removal of the blanket `runCatching` in `attributeInlineFindings`. A throw from the parser is a
  runtime defect and must fail loudly with a typed error, not degrade into a false negative about
  the worker.
- A `registerAbsenceReason` that reports which state occurred, using the subtask 1 result:
  - no register candidates → the lane produced no register; report byte count and a bounded excerpt
  - candidates present, none admitted → **format drift**; name the first offending line and its
    rejection reason
  - parser fault → loud failure, not an absence verdict
- A bounded excerpt of the lane's actual output on every absence verdict, so the failure is
  diagnosable from the register alone without reproducing the launch by hand.

An interim version of the excerpt exists uncommitted on main from the SKILL-194 diagnosis; fold it
into this subtask rather than shipping it separately.

## Acceptance Criteria

1. `attributeInlineFindings` contains no blanket `runCatching`; a parser throw propagates as a typed
   error naming the seam.
2. A lane returning prose with no `[F-` token reports the no-candidates state, its byte count, and a
   bounded excerpt.
3. A lane returning `[F-1] Major | High | a.kt:3 | x` reports the format-drift state, names that
   line, and gives its rejection reason — it must not say the review did not execute.
4. A lane returning an admissible register reports no absence verdict and no excerpt.
5. The excerpt is bounded and never emits the full lane output.
6. `REGISTER_ABSENT_TERMINAL_STATUS` continues to be recorded for genuine absence, and is **not**
   recorded for a parser fault.
7. `(cd runtime-kotlin && ./gradlew check)` passes.

## Non-Goals

- Widening what the parser admits, or reconciling the `parentPrompt` / `content.md` format conflict.
  Subtask 2 makes the drift visible; fixing the conflict is Next Path in the parent spec.
- Emitting telemetry or degradation records — subtask 3.
- Retrying or repairing a drifted register automatically.

## Dependency Notes

Depends on subtask 1 for the parse result shape and candidate probe. Cannot be verified before it.

## Validation Strategy

- A prose-only lane result yields the no-candidates verdict with a byte count. Catches the SKILL-194
  misdiagnosis directly.
- A near-miss register yields the format-drift verdict naming the offending line, and does **not**
  claim the review did not execute. This is the highest-value test in the subtask: it is the exact
  false negative that blocked a goal.
- A parser fault surfaces as a typed error, not as an absence verdict.
- An admissible register produces findings and no excerpt, proving the diagnostic path is inert on
  the happy path.

One test per rule; assert the observable verdict, not message wording beyond the distinguishing
token. Then `(cd runtime-kotlin && ./gradlew check)`.

## Next Path

```bash
skill-bill goal SKILL-195
```
