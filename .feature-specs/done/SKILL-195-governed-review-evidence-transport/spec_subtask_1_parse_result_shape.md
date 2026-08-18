# SKILL-195 Subtask 1 — Parse result shape: admitted findings and structured rejections

## Scope

Give `ParallelReviewFindingParser` a return type that can express *why* a register produced no
findings. Today `parse` returns `List<ParallelReviewRawFinding>` and drops every rejected match
inside `runCatching { parseMatch(match) }.getOrNull()`
(`runtime-domain/.../review/ParallelReviewFindingParser.kt:29-31`), so an empty list is
indistinguishable from a register that was present and malformed.

Deliver:

- A parse result carrying admitted findings **and** structured rejections. Each rejection names the
  offending line, its 1-indexed position in the lane output, and a typed reason (unrecognized
  severity, non-positive line number, unparseable structured path, no admissible location).
- A permissive candidate probe — a `\[F-\d+\]` scan independent of `parallelFindingPattern` — whose
  count distinguishes *no register candidates at all* from *candidates present, none admitted*.
- Per-match tolerance is retained: one garbled line must not discard an otherwise good register.
  What changes is that the rejection is now returned instead of erased.

This subtask changes the parser's shape and its callers' compile surface only. It does not change
which lines are admitted, and it does not yet change any user-facing message — that is subtask 2.

## Acceptance Criteria

1. `ParallelReviewFindingParser.parse` returns admitted findings and structured rejections; no
   rejection path discards its reason.
2. A rejection carries the offending line text, its position in the output, and a typed reason.
3. The candidate probe reports a non-zero count for output containing `[F-1]`, `[F-0001]`, or a
   bolded/table-wrapped `[F-001]` that `parallelFindingPattern` does not admit.
4. The probe reports zero for output containing no `[F-` token.
5. Admission behaviour is unchanged: every input that produced a finding before produces the same
   finding now, including the legacy `file:line` form and the `- ` list prefix.
6. `(cd runtime-kotlin && ./gradlew check)` passes.

## Non-Goals

- Changing `parallelFindingPattern` to admit new formats. Widening admission is a separate decision;
  this subtask makes the current boundary observable, not more permissive.
- Changing the F-XXX register format, severity vocabulary, or confidence scale.
- Emitting any observability record. Records land in subtask 3.
- Altering `registerAbsenceReason` messaging. That is subtask 2.

## Dependency Notes

None. This is the foundation subtask; subtasks 2 and 3 consume the result shape it introduces.

## Validation Strategy

Name the realistic bug each test catches; assert observable parse outcomes, never internal structure.

- A register whose ids are `[F-1]` (not three digits) yields zero admitted findings, a non-zero
  candidate count, and one rejection naming that line. This is the exact drift class that produced a
  false "the review did not execute" during SKILL-194.
- A register with an unrecognized severity yields a rejection with the severity reason, and sibling
  well-formed lines on either side are still admitted.
- Prose output with no `[F-` token yields zero admitted, zero candidates, zero rejections.
- A well-formed mixed register using both `path="..." | line=N` and legacy `file:line` admits both,
  matching pre-change behaviour.

One test per rule. Do not write literal-variation siblings across every severity or confidence
value. Then `(cd runtime-kotlin && ./gradlew check)`.

## Next Path

```bash
skill-bill goal SKILL-195
```
