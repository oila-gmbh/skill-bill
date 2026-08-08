# SKILL-170 — Subtask 1: CLI runtime drains its own telemetry outbox

## Scope

Wire the existing guarded drain into the CLI runtime paths, so a run that completes without
an MCP server present still flushes what it queued.

`TelemetryService.autoSync` (`runtime-application/.../telemetry/TelemetryService.kt:64`) is
already the correct entry point: it resolves settings, returns early when telemetry is
disabled or the database is absent, reconciles stale sessions, and delegates to
`TelemetrySyncRuntime.autoSyncTelemetry`. Its only callers today are
`runtime-mcp/.../core/McpRuntime.kt:93,126,220`.

The work is to give the CLI an equivalent call site at runtime-completion boundaries, and to
guarantee it cannot affect the run.

In scope:

- Identify the CLI completion boundaries that should drain (the feature-task-runtime run and
  the goal run at minimum) and call the existing `autoSync` there.
- Guarantee failure isolation: wrap so that a throwing or slow sync cannot change the run's
  outcome, exit code, or stdout contract.
- A regression test that fails against current code.

Out of scope: any change to what is emitted, to sync internals, or to the reconciler cadence.

## Acceptance Criteria

1. A CLI-driven feature-task-runtime run and a CLI-driven goal run each invoke the existing
   guarded drain at completion, without the operator running `skill-bill telemetry sync`.
2. The drain reuses `TelemetryService.autoSync`; no second sync path, settings resolution, or
   outbox query is introduced.
3. A drain that throws, or whose proxy is unreachable, leaves the run's reported outcome and
   process exit code byte-for-byte unchanged, and emits nothing to stdout.
4. An install whose resolved telemetry level is `off` transmits nothing on the new path.
5. A regression test drives a CLI runtime completion with a non-empty outbox and asserts the
   outbox drained; the same test fails when the new call site is removed.
6. A test asserts the failure-isolation property directly by making the sync path throw and
   asserting the run outcome is unaffected.

## Non-Goals

- Retry, backoff, or scheduling for a failed sync.
- Draining on non-runtime CLI commands (status, config, review) — completion boundaries only.
- Changing `autoSync`'s reconciler cadence, or collapsing it into the manual-sync
  `cadenceSeconds = 0` path.
- Altering the emitted event set or payloads.

## Dependency Notes

None. This subtask is independent of subtask 2 and can land alone; it is the half that
actually restores transmission.

## Validation Strategy

`cd runtime-kotlin && ./gradlew check`.

Beyond the gate, verify the mechanism rather than only the unit test: run a CLI runtime
against a scratch state dir with telemetry enabled and a deliberately unreachable proxy
URL, confirm the run completes normally, then confirm the outbox still holds its rows.
Repeat against a reachable target and confirm the rows leave.

## Next Path

Subtask 2 (`telemetry status` outbox visibility), or land alone if transmission is the only
thing needed now.
