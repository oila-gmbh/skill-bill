---
issue_key: SKILL-136
subtask_id: 6
name: Review store integrity, outbox signal, and lifecycle
parent_spec: .feature-specs/SKILL-136-android-native-review-specialists/spec.md
---

# Subtask 6 — Review store integrity, outbox signal, and lifecycle

## Intended Outcome

The review store distinguishes real delivery errors from healthy sends, has no
dead-or-broken learnings path, joins its two finding stores, never creates a
schema-less database, and has a documented snapshot retention policy.

## Scope

- `telemetry_outbox.last_error`: write `NULL` on the success path; backfill
  existing empty-string rows. Delivery itself is healthy (0 unsynced) and is
  not changed.
- `learnings` vs `session_learnings`: determine whether `learnings` is dead
  schema or its promotion path is broken, then remove the table or repair the
  promotion.
- Shared key across finding stores: `review_runs`/`findings` (keyed by
  `review_run_id`) and the workflow review loop's
  `review_generation_findings`/`unaddressed_findings`/
  `review_finding_dispositions` (keyed by `workflow_id`+`generation_id`).
  Introduce a shared key so disposition and feedback data attach to the run
  that produced the finding. Widen `feedback_events` coverage beyond its
  current 13% of runs.
- Database initialization: ensure no code path creates a `review-metrics.db`
  without applying migrations. A zero-byte, schema-less database currently
  exists in the working directory even though `DbConstants.defaultDbPath`
  resolves state under `userHome`.
- Snapshot retention: document a policy for `~/.skill-bill/`
  `review-metrics.*.db` snapshots (37 hand-named backups, 2.9 GB) and add an
  opt-in prune command.

## Acceptance Criteria

1. `telemetry_outbox.last_error` is `NULL` on the success path and non-null
   only for real delivery failures, with existing empty-string rows
   backfilled to `NULL`.
2. The `learnings` table is either removed as dead schema or its promotion
   path from `session_learnings` is repaired, with coverage either way, and
   the chosen resolution is justified by evidence recorded in the change.
3. Findings recorded through the workflow review loop and through review-run
   import share a key, so triage dispositions and feedback events join to the
   routed pack.
4. Accepted/rejected outcomes are recorded for every run that produces
   findings, not only the 13% currently covered by `feedback_events`.
5. No code path creates a `review-metrics.db` without applying migrations; a
   database file is either absent or schema-complete, with regression coverage
   for the working-directory path that produced the zero-byte file.
6. A snapshot retention policy for `~/.skill-bill/review-metrics.*.db` is
   documented and an opt-in prune command exists.
7. Existing snapshots are not deleted automatically by any code path.
8. All schema changes ship as migrations that preserve existing rows, verified
   against a copy of a real ~91.5 MB store.
9. Referential integrity remains sound: no orphaned findings, and the outbox
   still drains fully.
10. `(cd runtime-kotlin && ./gradlew check)` passes.

## Non-Goals

- Changing outbox delivery behaviour itself.
- Deleting existing snapshot files.
- Canonical attribution fields (Subtask 4) or per-lane attribution
  (Subtask 5).
- Changing any platform pack.

## Dependencies

Subtask 4 — the shared finding key and disposition joins resolve against the
canonical routed-skill identifier.

## Validation Strategy

Migration tests over a real-store copy for the outbox backfill and the shared
key; a regression test that the working-directory initialization path cannot
produce a schema-less database; coverage for the chosen `learnings`
resolution; a test that the prune command is opt-in and deletes nothing by
default. Then:

```bash
(cd runtime-kotlin && ./gradlew check)
skill-bill validate
```

## Next Path

Continue the goal; Subtask 7 documents the outcome.
