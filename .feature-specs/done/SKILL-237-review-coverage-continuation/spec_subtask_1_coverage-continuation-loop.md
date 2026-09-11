# SKILL-237 · Subtask 1: Coverage continuation loop

## Scope

Make `ParallelCodeReviewRunner` continue an inline review pass when the worker
exits with remaining required evidence and an open lane evidence-byte budget.

The production seam is `runLanes` / `launchedParentOutcome` in
`ParallelCodeReviewRunnerLaneLaunch`: one worker exit with
`Required review evidence remains undelivered` currently becomes lane
`success = false` and, for a goal child, a durable review block. Keep that
string as the *terminal* coverage failure after continuation is exhausted, not
as the first-slice outcome.

Hold the broker across slices in that `run()` so `delivered` / `remaining()`
survive Cursor process exit. Launch the next worker with the same
`review_run_id`. Discover pages for a continuation slice must not re-offer
already-delivered required units. Merge stdout/findings and accumulate
accounting. Stop on complete coverage, zero-progress slice, evidence-budget
exhaustion, or a non-coverage launch failure.

## Acceptance Criteria

1. A first inline worker that delivers a nonempty proper subset of required
   units, with lane evidence-byte budget remaining, causes a second worker
   launch on the same `review_run_id` and the same broker. The driver does
   not return unsuccessful-for-undelivered-evidence after that first exit.
2. The continuation worker's discover catalog contains only still-undelivered
   required units. Slice-one delivered selectors are absent from that page and
   remain credited in `delivered_units`.
3. When slice two delivers the remainder, the pass settles complete: merged
   findings include both slices, `delivered_units` equals `required_units`,
   and the undelivered-evidence failure reason is absent.
4. When slice two delivers no additional required unit, the pass is terminal
   incomplete with the existing undelivered-evidence reason and no third
   slice.
5. Evidence-byte budget exhaustion on a slice is terminal incomplete and does
   not launch another worker. Timeout, spawn failure, interrupt, and nonzero
   exit other than coverage-incomplete stay terminal without continuation.
6. A worker `verdict: approved` on a partial slice still cannot make
   `success = true` while `remaining_units > 0`.
7. Goal-child review going through this driver no longer durable-blocks at
   review solely because the first worker exited incomplete with budget left.

## Non-Goals

- Prompt dumping of catalogs or hunks.
- Sequential chunk workers or delegated fan-out.
- Cursor tool-allowlist enforcement.
- SKILL-233 structural work.
- Raising discover page size as the fix.

## Dependency Notes

None. Single subtask. SKILL-236 coverage truth stays: the gate still requires
full delivery; this subtask only continues until that gate can be decided.

## Validation Strategy

Name the realistic bug in each test before writing it.

- Partial then complete: stub launcher returns incomplete facts, then
  complete facts; `run()` is not unsuccessful for undelivered evidence;
  two launches; merged findings.
- Zero-progress: second facts add no `delivered_units`; one extra launch
  only; terminal undelivered-evidence reason present.
- Budget exhausted: no continuation launch.
- Approved-with-remaining still fails the gate until remaining hits zero.
- Existing one-shot complete worker still launches once.

Use the review recording harness or broker test doubles that expose
`remaining()` / delivered selectors. Do not assert implementation type
structure. Do not run `./gradlew check` as a substitute for these cases.

## Next Path

```bash
skill-bill goal SKILL-237
```
