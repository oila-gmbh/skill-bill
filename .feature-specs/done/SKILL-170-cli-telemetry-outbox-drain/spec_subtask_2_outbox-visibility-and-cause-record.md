# SKILL-170 — Subtask 2: local outbox visibility and recorded cause

## Scope

Make "queued but never sent" diagnosable on the machine it happens on, and record what the
investigation concluded.

The reason this went unnoticed for the life of the project is that non-transmission has no
local symptom: events are written successfully, the run reports nothing wrong, and the only
evidence is an absence of rows in a remote analytics project that few people query. A
one-command local read closes that gap.

In scope:

- `skill-bill telemetry status` reports pending outbox event count and last successful sync
  timestamp alongside the fields it already prints.
- Record in the runtime boundary history what was confirmed (the MCP-only drain wiring) and
  what the drain gap does **not** explain, if anything remains after subtask 1 lands.

`TelemetrySyncRuntime` already computes `pendingEvents` and `latestError` when building a
sync result (`TelemetrySyncRuntime.kt:50-54`); prefer surfacing those existing values over
introducing a parallel query.

## Acceptance Criteria

1. `skill-bill telemetry status` reports the pending outbox event count.
2. `skill-bill telemetry status` reports the last successful sync timestamp, and distinguishes
   "never synced" from "synced but the outbox is non-empty".
3. `telemetry status` performs no network call — it remains a local read.
4. The status surface reports these fields for every resolved telemetry level, including
   `off`, so an operator can see that events are queued and deliberately not being sent.
5. The runtime boundary history records the confirmed root cause, the PostHog cardinality
   evidence that surfaced it, and any residual cause the drain gap does not account for.

## Non-Goals

- A new dashboard, daemon, or periodic reporter.
- Alerting or nagging the operator about a non-empty outbox.
- Changing the status command's existing field names or output contract beyond additions.
- Backfilling or re-sending telemetry already stranded on user machines.

## Dependency Notes

Optional dependency on subtask 1. It reads more usefully after the drain exists (a non-empty
outbox then means something is genuinely wrong rather than being the normal steady state),
but it does not require subtask 1 to land first and can be executed independently.

## Validation Strategy

`cd runtime-kotlin && ./gradlew check`.

Additionally exercise the command against three states — empty outbox, non-empty outbox with
a prior successful sync, and non-empty outbox never synced — and confirm each renders
distinguishably.

## Next Path

Feature complete. If residual non-transmission remains after subtask 1, the history record
written here is the input to whatever issue follows.
