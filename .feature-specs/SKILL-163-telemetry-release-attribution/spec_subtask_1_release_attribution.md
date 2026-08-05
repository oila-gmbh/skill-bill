# SKILL-163 Subtask 1: Release attribution on every telemetry event

## Scope

Record the emitting release version on every telemetry event at enqueue time and project it onto
the uploaded payload.

Design: capture the version when the outbox row is inserted, not when it is uploaded. A build that
queues an event and is then upgraded before the next sync must still report the version that
emitted the event. Upload-time stamping fails exactly at release boundaries, which is the boundary
this feature exists to measure.

Relevant seams:

- `runtime-kotlin/runtime-infra-sqlite/src/main/kotlin/skillbill/db/core/DatabaseSchema.kt:204-216`
  — `telemetry_outbox` DDL: `id, event_name, payload_json, created_at, synced_at, last_error`.
- `runtime-kotlin/runtime-infra-sqlite/src/main/kotlin/skillbill/db/telemetry/TelemetryOutboxStore.kt:12-25`
  — the single `enqueue` implementation; inserts `(event_name, payload_json)` only.
- `runtime-kotlin/runtime-ports/src/main/kotlin/skillbill/ports/persistence/model/TelemetryOutboxRecord.kt:3-10`
  — record model.
- `runtime-kotlin/runtime-infra-http/src/main/kotlin/skillbill/infrastructure/http/TelemetryProxyPayloadMappers.kt:9-31`
  — where `install_id` and `$process_person_profile` are injected into `properties`; the version
  joins them here.
- `runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/SkillBillVersion.kt:5-7` — the existing
  release-derived constant.
- `runtime-kotlin/runtime-infra-sqlite/src/main/kotlin/skillbill/db/core/DatabaseMigrations.kt` —
  column migration.

Note the project convention that column ensures run unconditionally at every startup; appending a
column to an already-applied migration body is a silent no-op for existing databases.

## Acceptance Criteria

1. `telemetry_outbox` has a column recording the skill-bill version, populated at insert time from
   `SkillBillVersion.VALUE`.
2. The column is added by a migration that applies to existing databases, not only to freshly
   created ones.
3. `TelemetryOutboxStore.enqueue` writes the version without callers having to pass it, so no
   payload-builder call site needs to change.
4. `TelemetryProxyPayloadMappers` injects the row's recorded version into the uploaded event
   `properties` as `skill_bill_version`, alongside the existing `install_id`.
5. Rows that predate the migration and therefore carry no version sync successfully; they are
   neither dropped nor left blocking the pending queue.
6. A test proves emit-time semantics: a row enqueued while the version reports one value, then
   uploaded while it reports a different value, is uploaded with the enqueue-time value.
7. A test asserts a pre-migration row with no recorded version uploads without error.
8. No `contract_version` in `orchestration/contracts/telemetry-event-schema.yaml` is changed.

## Non-Goals

- Backfilling a version onto rows enqueued before this change.
- Adding the version to the MCP tool-call envelope in `McpToolDispatcher.telemetryEnvelope`; that
  path is validated but not uploaded.
- Replacing the hardcoded `User-Agent: skill-bill-telemetry/1.0` in `HttpTelemetryClient.kt:241`.

## Dependency Notes

None. This subtask is independent and can run first.

Subtask 4 documents the field this subtask adds, so it must land before subtask 4 finalizes.

## Validation Strategy

- Unit test over `TelemetryOutboxStore` asserting the version column is populated on insert.
- Migration test asserting an existing database without the column gains it on startup.
- Mapper test covering both the populated and the absent/empty version cases.
- `(cd runtime-kotlin && ./gradlew check)`.

## Next Path

Proceed to subtask 2.
