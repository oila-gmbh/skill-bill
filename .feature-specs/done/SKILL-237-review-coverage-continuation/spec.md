# SKILL-237: review coverage continuation

## Intended Outcome

Incomplete governed evidence coverage is a **continue**, not a terminal review
block, while the lane evidence budget is still open and the catalog still has
undelivered required units.

SKILL-236 made coverage truthful: a worker that prints `verdict: approved`
with remaining required units does not pass the gate. That gate still holds.
This feature changes *when* it fires. `ParallelCodeReviewRunner` currently
launches one inline worker, then maps `Required review evidence remains
undelivered` to an unsuccessful lane as soon as that process exits. Observed
on SKILL-233 subtask 6 (`rvw-20260910-201515-do65`): 604 of 4,467 units
delivered, budget not exhausted, then a goal block at review. Cursor workers
exit; the runtime does not relaunch over the remainder.

The driver keeps the same review pass and the same in-process broker, launches
the next worker slice over **undelivered** units only, merges findings, and
stops only when the catalog is complete, the evidence budget is exhausted, a
real launch/refusal failure occurs, or a slice delivers no new required unit.

Standalone `skill-bill code-review` and goal-child review share this loop.
Chunk workers stay gone. The launch prompt still must not carry path
inventories or hunk bodies.

## Acceptance Criteria

1. When an inline worker process exits with `remaining_units > 0`, the lane
   evidence-byte budget not exhausted, and that slice increased
   `delivered_units`, the driver launches another worker on the **same**
   `review_run_id` and the **same** broker instead of returning an
   unsuccessful lane for undelivered evidence.
2. Continuation discover/read offers only units that are still required and
   undelivered. Already-delivered units stay credited. The parent prompt still
   does not include path inventories or hunk bodies.
3. Findings, register lines, and accounting from successive slices merge onto
   that one review pass. `request_count`, `evidence_bytes`, and
   `delivered_units` accumulate; they do not reset per slice.
4. The unsuccessful coverage gate still fires, and only then, when a slice
   finishes in one of: catalog complete is false and `delivered_units` did not
   increase; lane evidence-byte budget exhausted; or a non-coverage launch
   failure (timeout, spawn failure, interrupt, nonzero exit that is not the
   undelivered-evidence reason). A worker `verdict: approved` with remaining
   units still cannot pass the gate.
5. Complete coverage after one or more continuation slices settles the same
   way a single complete worker already does: `reviewDisposition` complete,
   unsuccessful undelivered-evidence reason absent.
6. Goal-child review uses this driver loop. Incomplete coverage with an open
   budget is not a durable subtask block at `review` solely because the first
   worker exited.
7. A regression names the SKILL-233-shaped bug: first worker delivers a
   nonempty proper subset, budget remains, second worker delivers the rest →
   the run is not unsuccessful for undelivered evidence. A second regression
   names zero-progress: first worker partial, second worker delivers nothing
   new → terminal incomplete, no third slice.

## Constraints

- Keep the two governed evidence operations as the only repository-content
  path. Do not restore sequential chunk workers or specialist fan-out for
  inline.
- Reuse the existing in-process broker and its delivered/remaining sets.
  Do not persist a second catalog or invent a parallel coverage store.
- Existing per-review wall-clock and progress-idle timeouts still bound the
  whole pass, including continuation slices.
- Schema changes, if any, land first under `../../../orchestration/contracts` with
  Kotlin version parity and typed parse failures.
- No comments. `bill-unit-test-value-check` applies: each new test names the
  bug it catches.

## Non-Goals

- SKILL-233 architecture, module moves, or `dbPathOverride` removal.
- Making Cursor restrict the native-agent toolset beyond `readonly: true`.
- Prefetching the catalog into the launch prompt, or raising discover
  `page_size` / `REVIEW_DISCOVERY_MAX_BYTES` as a substitute for continuation.
- Changing finding admission, verification, or adjudication rules.
- Delegated specialist fan-out sequencing.
- Weakening the coverage gate so partial delivery can approve.

## Validation Strategy

- Driver-level tests with a stub launcher and a real or harness broker that
  records delivered units across two (or more) `AgentRunLaunchFacts` returns
  for one `run()`.
- Assert slice two's discover page contains none of slice one's delivered
  selectors, and that merged findings include both slices.
- Assert zero-progress and budget-exhausted terminals do not launch another
  slice.
- Keep SKILL-236 coverage tests: approved-with-remaining is still not
  success; full delivery in one worker still succeeds without a second
  launch.
- If contracts change, schema parity tests fail until versions match.
- Goal-child coverage is proven by the driver loop plus the existing review
  phase mapping; do not add a full goal-runtime test unless the mapping
  itself changes.

## Delivery Plan

1. One implementation pass in `ParallelCodeReviewRunner` / lane launch:
   coverage continuation over undelivered units, merged pass accounting, and
   the regressions in criterion 7.
