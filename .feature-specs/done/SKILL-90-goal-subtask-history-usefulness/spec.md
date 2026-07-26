# SKILL-90 — Per-subtask history-usefulness telemetry on `goal_subtask_finished`

Status: Ready for implementation
Issue key: SKILL-90
Mode: single_spec

## Context & Motivation

The goal-decomposition architecture's core claim is that running each subtask in a
fresh context window — with a curated `history.md` / `decisions.md` handoff instead of a
dragged-along transcript — keeps a long, decomposed goal "clean": a 20-subtask, 10-hour
goal reasons as clearly at subtask 20 as at subtask 1. Today that claim is **inferred, not
measured at the subtask grain.**

Live PostHog analysis (project SkillBill, 90 days) established the current instrumentation gap:

- `skillbill_feature_verify_finished` carries a **two-axis** rating: `history_relevance` +
  `history_helpfulness` (enum `none|irrelevant|low|medium|high`). At 90d the axes provably
  diverge (largest signal bucket: `relevance=low / helpfulness=medium`), so the second axis
  carries independent signal.
- `skillbill_feature_implement_finished` (and `feature_task_prose_finished`) carry a
  **single-axis** rating: `boundary_history_value` (same enum) plus a boolean
  `boundary_history_written`. Implement-side data is strong and well-powered (n≈706 rated
  reads, ~96% medium-or-high, ~0.4% irrelevant).
- `skillbill_goal_subtask_finished` carries **no history-usefulness property at all.**

Because a decomposed-goal subtask runs a full feature-task phase loop, the subtask's own
implement/prose run already produces a `boundary_history_value` for the `history.md` it read
on entry — but that rating is not surfaced on the goal subtask event, so it cannot be sliced
per subtask, per goal, or across a goal's subtask sequence. This spec closes that gap.

## Intended Outcome

`skillbill_goal_subtask_finished` emits the history-usefulness rating the subtask produced for
the boundary `history.md` it read at the start of its phase loop, so the decomposition
context-handoff claim can be measured **directly, per subtask**, across an entire goal —
including degradation-over-sequence questions (does usefulness fall as a goal gets longer?).

## Design Decision — single-axis now, two-axis follow-up

The subtask runner (`bill-feature-task-subtask-runner`) executes a feature-task/implement
loop, which already scores **one** axis (`boundary_history_value`). Producing the verify-style
**two** axes (`history_relevance` + `history_helpfulness`) would require teaching the
implement/subtask path to score a second axis — a separate, larger change to scoring behavior,
which Requirement "reuse the existing implement mechanism, do not invent new scoring"
explicitly excludes.

Resolution: this spec **reuses the already-produced `boundary_history_value` (+
`boundary_history_written`)** and surfaces them on `goal_subtask_finished`. Property names
mirror the implement event exactly for cross-event comparability. Adding the second
(`helpfulness`) axis to the implement/subtask path — and thereby to this event — is recorded
as an explicit **follow-up** (see Non-Goals), not part of this pass.

## Scope

1. **Schema contract** (`orchestration/contracts/telemetry-event-schema.yaml`): add two
   **optional** properties to the `goalSubtaskFinishedEvent` branch — `boundary_history_value`
   (`$ref` the shared `historySignalEnum`) and `boundary_history_written` (boolean). Do not add
   them to the `required` array. Decide and apply whether this backward-compatible addition
   warrants a `contract_version` minor bump (`1.1.0` → `1.2.0`); if bumped, update the schema
   const(s), the `TELEMETRY_EVENT_CONTRACT_VERSION` Kotlin constant
   (`runtime-mcp/.../telemetry/TelemetryEventSchemaPaths.kt`), and the contract-version test in
   lockstep.
2. **Capture / persistence**: persist the subtask's `boundary_history_value` +
   `boundary_history_written` onto the goal subtask events row that
   `goalSubtaskFinishedPayload` reads from. Source the values from the subtask's own
   feature-task run (joined by `workflow_id`) at subtask-finish reconciliation
   (`GoalRunnerOutcomeReconciler` / goal telemetry emit path) — reuse the existing
   implement-path values; do not re-score. Add the backing column(s) via the goal telemetry
   migration (`GoalTelemetryMigration.kt`), following the self-healing column-ensure pattern
   so existing DBs gain the column on startup.
3. **Payload builder** (`GoalTelemetryPayloadSupport.kt#goalSubtaskFinishedPayload`): emit the
   two new keys, mirroring `featureImplementFinishedPayload` semantics — `boundary_history_value`
   defaults to `"none"` when blank/absent; `boundary_history_written` is a boolean. Place them
   consistently with the event's existing telemetry-level gating (the non-`full` payload must
   remain valid; if these are treated as `full`-level detail, gate them accordingly).
4. **Parity guards stay green**: `goal_subtask_finished` is a runtime-internal emission event
   (in the `runtimeInternalEmissionEvents` allowlist in `TelemetryEventInputSchemaParityTest`),
   so **no MCP input-schema entry is added**. The schema branch and emitted payload must stay
   structurally consistent so the parity and contract-version tests pass.

## Acceptance Criteria

1. The `goalSubtaskFinishedEvent` branch in `telemetry-event-schema.yaml` declares
   `boundary_history_value` (referencing the shared `historySignalEnum`:
   `none|irrelevant|low|medium|high`) and `boundary_history_written` (boolean) as **optional**
   properties (absent from `required`).
2. When a decomposed-goal subtask finishes, `skillbill_goal_subtask_finished` is emitted with
   `boundary_history_value` and `boundary_history_written` reflecting the `history.md` the
   subtask read at the start of its phase loop, sourced from that subtask's own feature-task
   run (no new scoring logic introduced).
3. The values match what the same subtask run reports on its
   `skillbill_feature_implement_finished` / `feature_task_prose_finished` event for the same
   `workflow_id` (cross-event consistency).
4. The behavior is backward compatible: a subtask that read no boundary history emits
   `boundary_history_value = "none"` and `boundary_history_written = false`; existing dashboards
   and the telemetry proxy continue to ingest the event without error.
5. Existing DBs gain the backing column(s) on startup via the self-healing column-ensure
   migration path (no manual migration step, no data loss).
6. `TelemetryEventInputSchemaParityTest` and `TelemetryEventSchemaContractVersionTest` pass;
   `goal_subtask_finished` remains in the runtime-internal-emission allowlist (no MCP tool
   added). If `contract_version` is bumped, the schema const and
   `TELEMETRY_EVENT_CONTRACT_VERSION` are updated together and the contract-version test
   reflects the new value.
7. A test asserts the emitted `goal_subtask_finished` payload includes the two new properties
   with correct values for both the history-read and no-history-read cases.

## Non-Goals / Follow-ups

- **Two-axis on the subtask/goal path.** Adding `history_helpfulness` as an independent second
  axis to the implement/subtask scoring (and thus to this event) is a deliberate follow-up,
  not part of SKILL-90. This spec ships single-axis parity with the implement event.
- **Changing how usefulness is scored** on any existing event (`verify`, `implement`,
  `prose`). Reuse only.
- **Backfilling** historical `goal_subtask_finished` events.
- **Telemetry proxy stats-endpoint aggregation** of the new properties (e.g. exposing a
  per-goal usefulness rollup via `telemetry_remote_stats`) — separate follow-up.
- **Dashboards / insights** in PostHog consuming the new property — out of scope here.

## Constraints

- Reuse the shared `historySignalEnum`; do not define a new enum.
- New properties optional/nullable; absence reads as "no history read".
- Keep schema ↔ Kotlin-constant ↔ payload structurally in parity (locked by tests).
- Follow the self-healing migration convention (column-ensure runs unconditionally at startup;
  appending to an already-applied migration body is a no-op for existing DBs).
- No unnecessary comments in changed code.

## Validation Strategy

- Unit test on `goalSubtaskFinishedPayload` covering history-read and no-history-read cases
  (criterion 7).
- Run the telemetry parity + contract-version tests (criterion 6).
- Schema-validate a sample `goal_subtask_finished` envelope (with and without the new
  properties) against the updated contract.
- `./gradlew check` green.
- Post-merge confirmation via PostHog: `skillbill_goal_subtask_finished` events begin carrying
  `boundary_history_value`, and a per-goal slice (`group by workflow_id`, ordered by
  `subtask_id`) is queryable — the measurement this spec exists to enable.

## Affected Files (map)

- `orchestration/contracts/telemetry-event-schema.yaml` — `goalSubtaskFinishedEvent` branch
  (~L1281–1336); shared `historySignalEnum` (~L154–161).
- `runtime-kotlin/runtime-infra-sqlite/.../telemetry/GoalTelemetryPayloadSupport.kt` —
  `goalSubtaskFinishedPayload`.
- `runtime-kotlin/runtime-infra-sqlite/.../telemetry/GoalTelemetryEmitSupport.kt` —
  `emitGoalSubtaskFinished` / `goalSubtaskEventRow` (row source).
- `runtime-kotlin/runtime-infra-sqlite/.../telemetry/GoalTelemetryMigration.kt` — backing
  column(s).
- `runtime-kotlin/runtime-domain/.../goalrunner/GoalRunnerPolicy.kt` —
  `GoalRunnerOutcomeReconciler` (capture point at subtask-finish).
- `runtime-kotlin/runtime-mcp/.../telemetry/TelemetryEventSchemaPaths.kt` —
  `TELEMETRY_EVENT_CONTRACT_VERSION` (only if version bumped).
- Tests: `runtime-mcp/.../TelemetryEventInputSchemaParityTest.kt`,
  `TelemetryEventSchemaContractVersionTest.kt`, plus a new/extended payload test.

## Next

```bash
Run bill-feature-task on .feature-specs/SKILL-90-goal-subtask-history-usefulness/spec.md
```
