## [2026-07-09] SKILL-109 terminal telemetry completeness
Areas: runtime-kotlin/runtime-infra-sqlite reconciliation/schema, runtime-kotlin/runtime-application telemetry auto-sync, runtime-kotlin/runtime-ports persistence
- StaleSessionReconciler now runs from TelemetryService.autoSync through a UnitOfWork reconciliation port before outbox sync, making production sync the bounded terminal-event repair trigger. reusable
- SQLite reconciliation selects unfinished/unemitted feature implement, feature-task-runtime, feature verify, quality check, and abandoned goal-issue rows, marks terminal state, then emits through shared finished helpers for exact-once telemetry. reusable
- Goal issue progress stores last activity and last-blocked timestamps so inactive blocked goals can emit `goal_issue_finished` with `status=abandoned`; migrations self-heal legacy rows additively.
- Pattern: late normal finishes must respect already-emitted stale terminals, while feature implement may still count duplicate finish attempts without emitting duplicate terminal telemetry.
- Known limitation: leakage target under 5% still requires post-deploy analytics confirmation after reconciled rows are present in telemetry.
Feature flag: N/A
Acceptance criteria: 5/5 implemented

## [2026-07-09] SKILL-109 reliable lifecycle duration telemetry
Areas: runtime-kotlin/runtime-infra-sqlite lifecycle migrations/tests, orchestration/contracts telemetry schema, runtime-mcp goal telemetry parity
- Lifecycle session self-heal now ensures legacy feature implement, feature verify, and quality check tables have `started_at`; feature implement blank starts are recovered from matching workflow rows before falling back to `CURRENT_TIMESTAMP`. reusable
- Feature implement finished telemetry regression coverage builds a pre-column legacy database and asserts migrated duration_seconds reflects real elapsed time instead of a blank-start near-zero value.
- Goal terminal telemetry standardizes on `duration_seconds` at the event/schema boundary while preserving internal millisecond persistence.
- Known limitation: legacy feature verify and quality check blank starts still backfill to migration time because they lack a matching workflow-start recovery source.
Feature flag: N/A
Acceptance criteria: 4/4 implemented
