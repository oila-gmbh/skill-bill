# SKILL-80 · Subtask 4 — Zombie-run reconciliation + exception capture

## Scope

Two observability gaps:

**A. Zombie / never-terminating sessions.** A `feature_implement` run logged a
742-minute (~12.4h) duration. `durationSeconds()`
(`runtime-infra-sqlite/.../LifecycleTelemetryDurationSupport.kt` ~lines 6–20) is
computed at emission time from `started_at`/`finished_at`; sessions in
`feature_implement_sessions` / `feature_task_runtime_sessions`
(`runtime-infra-sqlite/.../DatabaseSchema.kt`) can keep `finished_at = NULL`
indefinitely. The only reconciliation that exists is goal-runner-scoped
(`runtime-application/.../GoalRunnerWorkflowStores.kt` `reconcileAuthoritativeOutcomes()`
~lines 254–321); there is no per-run timeout or stale-session closure for the
feature-implement / feature-task-runtime session tables, so abandoned sessions inflate
duration metrics.

**B. No exception capture.** The PostHog project records zero `$exception` events.
Telemetry is named-events-only through the generic HTTP proxy (`TelemetryClient` →
`HttpTelemetryClient` → outbox `telemetry_outbox`). Unhandled errors in runtime
execution are never captured as telemetry, so crashes are invisible in PostHog.

## Acceptance Criteria

1. A stale-session reconciliation exists for `feature_implement_sessions` and
   `feature_task_runtime_sessions`: sessions that have a `started_at` but no
   `finished_at` beyond a configurable staleness threshold are closed with a terminal
   status (e.g. `completion_status = "abandoned"`/`"stale"`) and a `finished_at`, so
   they stop counting as in-progress and stop inflating duration aggregates. The
   threshold has a sensible documented default.
2. Duration aggregation excludes or floors reconciled/stale sessions so a single zombie
   run can no longer dominate `average_duration_seconds`/max-duration metrics (e.g.
   reconciled sessions are recorded with their reconciliation time, and the stats layer
   can distinguish reconciled-stale from genuinely-completed durations).
3. An exception/error telemetry event is emitted through the existing outbox → proxy
   path when the runtime hits an unhandled error, carrying at minimum: a stable event
   name, a workflow/phase identifier, an error type/message, and (where available) a
   redacted stack trace. No secrets or full file contents are included in the payload.
4. The proxy maps the new error event to a PostHog `$exception` (or an explicitly named
   error event) so it is queryable in the SkillBill project; the proxy ingest path
   accepts the new event without breaking existing events. (Mapping lives in
   `docs/cloudflare-telemetry-proxy/worker.js`.)
5. Tests cover: a stale session is reconciled to a terminal state after the threshold;
   duration aggregation is not skewed by a reconciled stale session; and an unhandled
   runtime error enqueues exactly one exception telemetry event with the expected
   (secret-free) fields.
6. `(cd runtime-kotlin && ./gradlew check)` passes; the proxy worker tests pass.

## Non-goals

- Adopting a third-party PostHog SDK or client-side crash reporter.
- Real-time alerting/monitors in PostHog (capture only; dashboards are follow-up).
- Backfilling or repairing historical zombie rows.
- Per-phase wall-clock timeouts (a per-phase timeout already exists for parallel
  review lanes; this subtask is session-level reconciliation, not phase timeouts).

## Dependency notes

Independent of subtasks 1 and 2. Coordinate terminal `completion_status` values with
subtask 3 if both land (shared notion of "abandoned"/terminal state for sessions).

## Validation strategy

- Unit tests for reconciliation threshold behavior and duration aggregation.
- Unit test for exception-event enqueue (assert single event, correct fields, no
  secrets).
- Worker test for the `$exception` mapping and unchanged existing-event ingest.
- `(cd runtime-kotlin && ./gradlew check)`.

## Next path

```bash
skill-bill goal SKILL-80
```
