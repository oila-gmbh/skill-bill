# SKILL-191 · Subtask 2 — Durable review stage state and resume boundaries

## Scope

Give the driver durable state for the two new stages, so a crash between stages
resumes at the missing stage instead of re-running the review pass.

`ParallelCodeReviewRunner` already records durable lane dispositions
(`recordLaneDispositions`, `durablyCompleteLanes`) and a separate integration
boundary (`recordIntegrationBoundary`), and `selectLaunchesForResume` re-launches
only lanes without a durable result. Extend that model rather than inventing a
parallel one.

Persist under the existing review run identity (`review_run_id`) in the SQLite
runtime:

- per-finding verdict rows conforming to `finding_verdict`, keyed by review run,
  finding id, and stage
- a stage-boundary row per stage recording reached or not reached, distinct from the
  specialist and integration boundaries already present
- the resolved spec projection reference for the run — spec path, content digest, or
  the recorded `spec_context: none` reason

Extend resume selection so the driver computes, for a given run, which of
`review`, `verification`, and `adjudication` hold durable results, and re-enters at
the first that does not. Stage boundaries are independent: verification completing
does not imply adjudication ran, mirroring how integration completion is already
distinct from specialist completion.

Extend `ReviewSnapshotPruneService` so verdict and boundary rows are pruned with
their review run rather than outliving it.

Column ensures run unconditionally at startup. Appending a column to an
already-applied migration body is a silent no-op on an existing database, so any new
column needs its own unconditional ensure.

## Acceptance Criteria

1. Per-finding verdict rows, per-stage boundary rows, and the run's spec projection reference persist under the existing `review_run_id` identity and survive process restart.
2. Schema changes apply idempotently to an existing database on startup, with new columns added by unconditional ensures rather than by editing an already-applied migration body.
3. Resume selection reports, for a given review run, which of `review`, `verification`, and `adjudication` hold durable results.
4. A run interrupted after the review pass and before verification resumes into verification without re-running the review pass or re-launching any lane.
5. A run interrupted after verification and before adjudication resumes into adjudication, retaining every recorded verification verdict.
6. Verification completion does not mark adjudication complete, and adjudication completion does not backfill a missing verification boundary.
7. Pruning a review run removes its verdict rows, stage-boundary rows, and spec projection reference.

## Non-Goals

- Running the stages. Subtasks 4 and 5 produce the verdicts this subtask stores.
- Reading verdicts downstream; subtask 6 owns consumers.
- Emitting telemetry; subtask 7 owns measurement.
- Changing existing lane-disposition or integration-boundary storage.

## Dependency Notes

Depends on subtask 1 for the `finding_verdict` shape and contract version.

## Validation Strategy

- One migration test asserting the ensures apply cleanly to a database created before
  this change, since a silent no-op here is invisible until a later read fails.
- One restart test per resume boundary (criteria 4 and 5) asserting which stages
  re-enter, at the observable driver boundary rather than by verifying calls.
- One test that verification completion leaves adjudication unreached.
- One prune test asserting no orphaned verdict rows.

## Next Path

Subtask 3 — spec intent projection resolver. Subtask 4 also unblocks from here.
