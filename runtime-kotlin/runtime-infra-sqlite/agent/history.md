## [2026-07-09] SKILL-109 reliable lifecycle duration telemetry
Areas: runtime-kotlin/runtime-infra-sqlite lifecycle migrations/tests, orchestration/contracts telemetry schema, runtime-mcp goal telemetry parity
- Lifecycle session self-heal now ensures legacy feature implement, feature verify, and quality check tables have `started_at`; feature implement blank starts are recovered from matching workflow rows before falling back to `CURRENT_TIMESTAMP`. reusable
- Feature implement finished telemetry regression coverage builds a pre-column legacy database and asserts migrated duration_seconds reflects real elapsed time instead of a blank-start near-zero value.
- Goal terminal telemetry standardizes on `duration_seconds` at the event/schema boundary while preserving internal millisecond persistence.
- Known limitation: legacy feature verify and quality check blank starts still backfill to migration time because they lack a matching workflow-start recovery source.
Feature flag: N/A
Acceptance criteria: 4/4 implemented
