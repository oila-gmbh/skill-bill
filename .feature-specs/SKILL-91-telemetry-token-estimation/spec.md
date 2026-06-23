# SKILL-91 Token-Usage Estimation in Feature-Task Telemetry

## Status

Complete

## Problem Statement

`review-metrics.db` has **no** token, cost, or usage column in any of its tables, and
neither `feature_task_prose_stats` nor `feature_task_runtime_stats` reports a token figure.
As a result the telemetry loop — the product's compounding moat — cannot answer the most
basic cost question, including the concrete one that motivated this spec: *does the runtime
mode actually use fewer tokens than the prose mode?* The only available proxy today is
wall-clock duration, which is confounded by feature size, which agent ran, machine load,
and orchestrator latency, and therefore cannot stand in for token cost.

The two modes also capture telemetry through different paths, so a single capture point is
impossible:

- **Runtime mode** runs each phase by spawning an agent CLI process from Kotlin. Both the
  input prompt and the agent's stdout/stderr are already materialized in
  `JvmAgentRunProcessRunner.runStartedProcess()` (the `AgentRunProcessResult` holds
  `stdinText`-derived input plus `stdout.text()` / `stderr.text()`). This is the natural
  seam to estimate per-phase tokens.
- **Prose mode** runs entirely in the invoking agent session and reports telemetry only via
  the `feature_task_prose_started` / `feature_task_prose_finished` MCP tools. The Kotlin
  runtime never sees the prose subagents' I/O, so prose token figures must be estimated by
  the prose orchestrator skill itself and passed through the existing MCP payload.

Both paths must converge on the same additive schema and the same estimation method so the
two stats aggregators report comparable numbers.

## Desired End State

- A single shared estimator defines the canonical heuristic
  `estimateTokens(text) = ceil(utf8ByteCount(text) / 4)` used by every capture site, so
  prose and runtime numbers are produced the same way and remain comparable.
- For runtime runs, each phase's estimated input and output tokens are captured at the
  agent process seam and accumulated into the run's per-phase rollup and per-run total,
  persisted on `feature_task_runtime_sessions`.
- For prose runs, the prose orchestrator estimates per-phase input/output tokens from the
  briefings it sends to subagents and the RESULT text they return, and passes a per-phase
  breakdown plus per-run totals into `feature_task_prose_finished`, persisted on the prose
  session record.
- Both session tables gain additive-optional token columns added via the self-healing
  column-ensure path, so existing databases upgrade in place and rows predating the feature
  read as `null` (never `0`).
- The telemetry event-schema contract gains optional token fields on both finished events,
  with the contract version bumped and the Kotlin version constant kept in lockstep.
- `feature_task_prose_stats` and `feature_task_runtime_stats` each surface estimated token
  aggregates (per-run total plus an average across runs that carry a figure), computed only
  over rows that have a non-null estimate, and the MCP payloads expose them.

## Token Source and Estimation Method (resolved)

- **Source decision:** estimate from text size, not exact provider usage. Rationale: it is
  transport-agnostic (identical for claude / codex / opencode), requires no change to the
  PTY-backed stdio spawn path, and gives a single consistent method across both modes —
  which is what the prose-vs-runtime comparison needs. Exact per-provider usage was
  explicitly rejected for this iteration because mixed-agent runs would yield mixed-accuracy
  numbers that cannot be compared.
- **Heuristic:** `estimateTokens(text) = ceil(utf8ByteCount(text) / 4)`, measured with
  UTF-8 byte length (the codebase already measures payload size this way in
  `FeatureTaskRuntimePhaseBriefingAssembler`). The divisor lives as a single named constant
  so the heuristic can be tuned in one place.
- **Honesty:** every surfaced field name and its contract description must mark the value as
  an estimate (`estimated_*`), never implying billing accuracy.
- **Granularity:** capture per-phase input and output estimates; roll up to a per-run total.
  Per-phase breakdown is persisted (so "where do the tokens go" is answerable); the
  aggregators surface the per-run totals.

## Implementation Map (reference, non-binding)

- Shared estimator: new small utility in a domain/application location reachable by both the
  runtime capture path and the prose persistence path; referenced byte-count pattern in
  `FeatureTaskRuntimePhaseBriefingAssembler.kt`.
- Runtime capture seam: `JvmAgentRunProcessRunner.kt` (`runStartedProcess`, where
  `AgentRunProcessResult` input/stdout/stderr are all visible) feeding per-phase totals up
  through `FeatureTaskRuntimeRunner.kt` into the runtime finished record.
- Runtime persistence: `feature_task_runtime_sessions` in `DatabaseSchema.kt`;
  `FeatureTaskRuntimeFinishedRecord` in `LifecycleTelemetryModels.kt`;
  `LifecycleTelemetryRuntimeSaveSupport.kt` (INSERT/UPDATE); self-healing columns in
  `DatabaseColumnMigrations.kt` (`ensureFeatureTaskRuntimeSessionColumns`).
- Prose path: MCP input schema in `McpToolRegistry.kt` (`feature_task_prose_finished`);
  argument extraction in `McpLifecycleToolHandlers.kt`; `FeatureImplementFinishedRecord` in
  `LifecycleTelemetryModels.kt`; `LifecycleTelemetryService.kt`; prose-session columns +
  ensure path; `skills/bill-feature-task-prose/content.md` "Record Finished" / Step 9
  telemetry section where the per-phase token tally is computed and passed.
- Stats: `ReviewWorkflowStatsSupport.kt` (`buildFeatureTaskRuntimeStats` and the prose
  builder), `ReviewStatsRuntime.kt`, the `FeatureTaskRuntimeWorkflowStats` /
  prose stats models, and their `toMcpMap()` payloads.
- Contract: `orchestration/contracts/telemetry-event-schema.yaml` (both finished events +
  `contract_version`) kept in lockstep with the Kotlin
  `TELEMETRY_EVENT_CONTRACT_VERSION` constant.

## Acceptance Criteria

1. A single shared token-estimation function exists, implementing
   `ceil(utf8ByteCount(text) / 4)` with the divisor as a named constant, and every token
   capture site (runtime and prose) uses it rather than re-deriving the heuristic.
2. Runtime mode captures per-phase estimated input and output tokens at the agent process
   seam and persists, on `feature_task_runtime_sessions`, both a per-phase breakdown and a
   per-run estimated total via additive-optional columns.
3. Prose mode estimates per-phase input/output tokens in the prose orchestrator and passes a
   per-phase breakdown plus per-run totals through `feature_task_prose_finished`, persisted
   on the prose session record via additive-optional columns.
4. New token columns on both session tables are created on existing databases through the
   self-healing column-ensure path; a database created before this feature upgrades in place
   without a destructive migration, and rows predating the feature read as `null` rather
   than `0`.
5. The telemetry event-schema contract
   (`orchestration/contracts/telemetry-event-schema.yaml`) adds the optional token fields to
   both the prose-finished and runtime-finished events, the `contract_version` is bumped,
   and the Kotlin contract-version constant is updated to match so validation passes.
6. `feature_task_runtime_stats` reports estimated token aggregates (at minimum a per-run
   total and an average across runs that carry a figure), computed only over rows with a
   non-null estimate so legacy rows neither divide-by-zero nor depress the average toward 0.
7. `feature_task_prose_stats` reports the equivalent estimated token aggregates over prose
   runs, using the same field names and the same null-exclusion rule, so the two payloads
   are directly comparable for a prose-vs-runtime cost comparison.
8. Every surfaced token field name and contract description marks the value as an estimate
   (e.g. `estimated_total_tokens`) and never claims billing accuracy.
9. Tests cover: the estimator (including empty string, multi-byte UTF-8, and rounding-up
   behavior); runtime per-phase capture rolling up to a correct per-run total; prose payload
   extraction persisting the passed figures; the self-healing migration adding columns to a
   pre-existing database; and each aggregator excluding null-token rows from its average.

## Non-Goals

- Billing-accurate or provider-reported token counts; parsing `stream-json` or any
  structured agent output; the PTY-stdio spawn transport is untouched.
- Cache-read / cache-write token classes, and any conversion of tokens to currency cost.
- Retroactive backfill of token estimates for runs that completed before this feature ships.
- Changing the phase loop, agent selection, or any runtime supervision behavior; this is a
  pure observation/telemetry addition.

## Validation Strategy

- Unit-test the estimator and both aggregators' null-exclusion math.
- Integration-test the runtime capture end-to-end against a stub agent process with known
  input/output sizes, asserting the persisted per-run total equals the summed per-phase
  estimates.
- Integration-test the prose MCP path by invoking `feature_task_prose_finished` with a token
  payload and asserting persistence and stats surfacing.
- Run the column-ensure path against a database seeded on the pre-feature schema and assert
  the new columns appear and existing rows read `null`.
- Run `./gradlew check` and confirm contract-version lockstep validation passes.

## Next

```bash
Run bill-feature-task on .feature-specs/SKILL-91-telemetry-token-estimation/spec.md
```
