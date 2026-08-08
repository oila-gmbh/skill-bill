# SKILL-170 — CLI telemetry outbox never drains

## Intended Outcome

Telemetry queued by a CLI-driven runtime run must actually reach the proxy, and an install
that is queuing-but-not-sending must be diagnosable locally without querying PostHog.

Today neither holds. Every telemetry row in the project's PostHog traces to a single
`install_id`, so the telemetry loop — described in the project's own positioning as the
compounding moat — is running at N=1 against its author's dev machine. No user install is
transmitting.

## Evidence

**Fleet-wide non-transmission (confirmed, 2026-08-08).** Over the trailing 21 days the
`skillbill_feature_task_runtime_projection_measurement` event carries 10,690 rows across 148
distinct `workflow_id` values and exactly **one** distinct `install_id`:

```sql
SELECT countDistinct(properties.install_id), countDistinct(properties.workflow_id)
FROM events
WHERE event = 'skillbill_feature_task_runtime_projection_measurement'
  AND timestamp > now() - INTERVAL 21 DAY
→ installs: 1    workflows: 148
```

Repository paths inside the payloads identify that install as the maintainer's machine. A
user-reported blocker on workflow `wftr-20260807-123754-11fb` returned zero rows for every
event type, while workflows immediately before (`12:12:38`) and after (`12:46:20`) that
timestamp are present — the absent workflow belongs to a non-transmitting install, not to a
gap in the event stream.

**The drain is only wired into the MCP server (confirmed).** `TelemetryService.autoSync`
(`runtime-application/.../telemetry/TelemetryService.kt:64`) has exactly three callers, all in
`runtime-mcp/.../core/McpRuntime.kt` (lines 93, 126, 220). A grep for `telemetryService` across
`runtime-cli/src/main/kotlin` returns nothing. The CLI ships a manual
`skill-bill telemetry sync` command (`TelemetryCliCommands.kt:57`), but nothing invokes a drain
automatically on a CLI path.

The consequence: a user who runs `skill-bill goal` / the feature-task runtime without also
running the MCP server enqueues telemetry into a local SQLite outbox that is never flushed.
Events are written and retained; they simply never leave the machine.

**Ruled out.** A blank `proxy_url` in `defaultLocalTelemetryConfig`
(`TelemetryConfigRules.kt:12`) is *not* the cause — `runtime-kotlin/agent/history.md:2256`
records that blank correctly resolves to the hosted relay. The default level is `anonymous`,
not `off` (`TelemetryConfigRules.kt:11`), so the default config is opted in.

**Not yet confirmed.** Whether the missing CLI drain is the *sole* cause. Users may also be
setting `level: off`, or never completing an install that enables telemetry. Nothing today
distinguishes "queued and stuck" from "never queued" from outside the machine, which is why
this was invisible until a PostHog cardinality check surfaced it.

## Scope

Two independent halves:

- **Drain.** A CLI runtime run flushes its own outbox, on the same guarded path the MCP
  server already uses, so queued events leave the machine without the operator running a
  manual command.
- **Observability.** `skill-bill telemetry status` reports pending outbox depth and last
  successful sync, so a stuck outbox is a local, one-command diagnosis rather than an
  inference from missing rows in a remote analytics project.

## Acceptance Criteria

1. A CLI-driven feature-task-runtime or goal run flushes pending telemetry through the same
   guarded drain path the MCP server uses, without the operator invoking
   `skill-bill telemetry sync` manually.
2. The drain never fails, delays, or alters a runtime run: a sync error, an unreachable
   proxy, or an absent database leaves the run's outcome and exit code unchanged.
3. The drain respects the resolved telemetry level: an install at `level: off` transmits
   nothing, and the level-independent measurement events keep their existing behaviour.
4. `skill-bill telemetry status` reports the pending outbox event count and the last
   successful sync timestamp, so a queued-but-unsent install is diagnosable locally.
5. A regression test proves a CLI runtime completion drains a non-empty outbox, and would
   fail against the current code where no CLI path calls a drain.
6. The confirmed root cause and whatever the investigation finds about remaining causes are
   recorded in the runtime boundary history, including any cause the drain gap does *not*
   explain.

## Constraints

- Telemetry must never fail the run. Every existing emission seam swallows its own errors
  (`FeatureTaskRuntimePhaseRecorder.recordSharedEvidenceMeasurement` is the local pattern);
  the drain must hold the same property at a coarser granularity.
- Do not widen what is transmitted. This feature changes *when* the outbox flushes and what
  the operator can see locally — not the event set, the payload fields, or the privacy level
  semantics.
- Reuse `TelemetryService.autoSync` and `TelemetrySyncRuntime.autoSyncTelemetry` rather than
  authoring a second sync path. Note that `autoSync` reaches the reconciler with the default
  cadence while manual sync passes `cadenceSeconds = 0` (`TelemetryService.kt:51,67`); keep
  that distinction rather than collapsing it.
- No new network calls on a read-only or status-only command path.

## Non-Goals

- Changing the default telemetry level, the consent flow, or install-time opt-in copy.
- Adding retry, backoff, or a background daemon for failed syncs.
- Backfilling telemetry already queued on user machines.
- Fixing the PostHog-side schema or dashboards.
- The `bill-feature-verify` proxy `workflow` argument defect found alongside this
  (every enum value resolves to `feature-task-prose`) — separate issue, separate key.

## Validation Strategy

`cd runtime-kotlin && ./gradlew check` is the gate. Note that `check` may already be red on
`main` for reasons unrelated to this work; establish the baseline before attributing a
failure to this change.
