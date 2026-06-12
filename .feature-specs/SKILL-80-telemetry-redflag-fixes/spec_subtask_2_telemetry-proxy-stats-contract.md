# SKILL-80 · Subtask 2 — Telemetry proxy stats workflow contract

## Scope

Calling `telemetry_remote_stats` for `bill-feature-implement` fails with a large,
misleading `event_name must be the constant value …` error — the ingest event-schema
validator, not a stats response. Root cause is a workflow-naming contract mismatch
across four layers plus a fall-through routing defect:

- Proxy worker `docs/cloudflare-telemetry-proxy/worker.js`:
  - `validateStatsRequest()` (~lines 89–106, allowlist check ~line 93) only accepts
    `["bill-feature-verify", "bill-feature-task"]`.
  - `capabilitiesPayload()` (~lines 116–118) advertises `supported_workflows`; the
    **deployed** worker returns `["bill-feature-verify", "bill-feature-implement"]`
    (observed via live `capabilities`), which disagrees with both the source and the
    stats allowlist.
  - The ingest path `isValidEvent()` (~lines 17–28) validates `event_name` constants;
    a stats request for an unrecognized workflow ends up validated against this schema
    instead of being cleanly rejected.
- Client constants `runtime-kotlin/.../skillbill/telemetry/TelemetryConstants.kt`:
  `remoteStatsWorkflows = ["bill-feature-task", "bill-feature-verify",
  "feature-task-runtime", "bill-feature-goal"]`.
- Client mapping `runtime-kotlin/.../skillbill/application/TelemetrySupport.kt`
  (~lines 37–43): `implement → bill-feature-task`, `verify → bill-feature-verify`, etc.
- MCP tool enum `runtime-kotlin/.../skillbill/mcp/McpInputSchemas.kt`
  (`remoteStatsWorkflowSchema`, ~lines 13–25) and the `telemetry_remote_stats` tool.

## Acceptance Criteria

1. There is a single documented canonical set of workflow identifiers accepted by the
   stats path, and the proxy stats allowlist (`validateStatsRequest`), the proxy
   `capabilities` (`capabilitiesPayload`), the client `remoteStatsWorkflows`, the
   client mapping, and the MCP tool enum all agree on that set (no advertised workflow
   is rejected by the stats handler, and no stats-accepted workflow is unadvertised).
2. A stats query for a workflow the proxy advertises in `capabilities` returns a stats
   payload (HTTP 200 with the stats shape), not an error.
3. A stats query for an unrecognized/unsupported workflow returns a clean,
   stats-shaped error (e.g. `workflow must be one of: …`) and is **never** routed
   through the ingest `event_name`-constant validator (the `event_name must be the
   constant value …` error must not appear for any stats request).
4. The `bill-feature-implement` ⇄ `bill-feature-task` naming relationship is resolved
   explicitly: either the proxy accepts the legacy `bill-feature-implement` alias
   (mapping it to the task workflow) or it is removed from `capabilities`; either way
   the live behavior matches what `capabilities` advertises.
5. Worker stats-endpoint tests exist under `docs/cloudflare-telemetry-proxy/` (or the
   established proxy test location) covering: an advertised workflow → 200 stats, and
   an unsupported workflow → clean rejection (asserting the ingest-schema error is not
   returned). Existing Kotlin client tests in `runtime-mcp/.../McpTelemetryRuntimeTest.kt`
   are updated to match the canonical set and pass.
6. The ingest path behavior is unchanged for real ingest events.

## Non-goals

- Adding new stats metrics or new workflows beyond aligning the existing set.
- Changing auth (`TELEMETRY_PROXY_STATS_TOKEN`) or the outbox sync path.

## Dependency notes

Independent. No dependency on subtasks 1, 3, or 4. Note: the deployed worker differs
from `worker.js` in source — reconcile source as the authority and call out that a
redeploy is required for the live proxy to match (deployment itself is operational,
not part of this code change unless trivially scripted).

## Validation strategy

- Worker tests (node/wrangler test harness as established in the proxy dir) pass.
- `(cd runtime-kotlin && ./gradlew check)` passes with updated client tests.
- Manual: a stats call for each advertised workflow returns a stats payload locally
  (or via the worker test harness); an unsupported workflow returns the clean error.

## Next path

```bash
skill-bill goal SKILL-80
```
