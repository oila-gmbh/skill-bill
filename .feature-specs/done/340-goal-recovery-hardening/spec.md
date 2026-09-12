# Issue 340: Goal recovery hardening for review citations and repair writes

## Intended Outcome

Close two production defects that strand decomposed goals during review and
recovery. File-level review finding citations that report `line: 0` must ingest
without aborting the review phase or checkpointing an unrecoverable exception.
`goal repair --apply` must complete durable wedge repairs without self-deadlocking
on `SQLITE_BUSY` when no external process holds the database.

## Acceptance Criteria

1. A review finding that cites a whole file with `line: 0` (or equivalent
   non-positive numeric input) is normalized at the agent-output boundary so
   review ingestion continues and the citation remains usable.
2. A single malformed field in one agent-reported finding degrades that
   citation or finding at the boundary; it does not fail the entire review pass
   with an uncaught `IllegalArgumentException`.
3. `goal repair --apply` can write wedge repairs while the same command has
   already performed inspect reads, without `SQLITE_BUSY` when no other process
   holds the database.
4. The `completed_upstream_missing_output` wedge path that previously failed
   repair apply is covered by a regression test.
5. When `repair` or `operator-decision` refuse to act on a wedged review outcome,
   the failure message points operators to scoped recovery via
   `goal reset --subtask` and `--delete-child-workflow` where applicable.
6. Focused domain, engine, and CLI regression tests pass, and the
   dominant-stack quality check reports no new findings on touched modules.
7. The feature-spec manifest and all executable subtask specs remain
   schema-valid and acceptance-criteria extractable by the goal runtime.

## Constraints

- Normalize untrusted agent output at ingestion boundaries; keep domain invariants
  for persisted citations (`line >= 1`).
- Preserve loud failure for malformed review envelopes, invalid repository paths,
  and incompatible phase-output contract versions.
- Fix the repair apply path by releasing read connections before writes; do not
  weaken SQLite locking semantics globally.
- Prefer high-value regression tests over broad refactors.

## Non-Goals

- Redesigning review prompts or provider citation formats.
- Replacing `goal replan` as the heavy recovery path for incompatible contract
  versions.
- Changing unrelated telemetry, operator-decision semantics, or review pass
  accounting.

## Affected Areas

- `../../../runtime-kotlin/runtime-domain` review citation decoding and models.
- `../../../runtime-kotlin/runtime-application` claim verification ingestion.
- `../../../runtime-kotlin/runtime-engine` goal repair coordination and child wedge repair
  persistence.
- `../../../runtime-kotlin/runtime-cli` repair command messaging.
- Focused tests in domain, engine, and CLI modules.

## Validation Strategy

- Run focused citation decoding and review ingestion tests.
- Run `GoalRunnerRepairTest` and CLI repair apply coverage for the busy-database
  failure mode.
- Run the dominant-stack Kotlin quality check after implementation.

## Delivery Plan

1. Normalize file-level citation lines and harden per-finding degradation at the
   review boundary.
2. Fix repair apply database connection lifecycle and add operator escalation
   pointers.
