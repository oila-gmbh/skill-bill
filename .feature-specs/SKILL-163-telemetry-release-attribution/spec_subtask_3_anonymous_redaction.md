# SKILL-163 Subtask 3: Redact customer-identifying strings at anonymous level

## Scope

Make the `anonymous` level match its documented promise.

`docs/review-telemetry.md:154` states anonymous sends "Aggregate counts, finding ids with issue
category/severity/confidence/outcome type, anonymized learning references. No file paths,
descriptions, notes, or learning content." Three families of field contradict that:

- `issue_key`, uploaded at every level:
  - `runtime-kotlin/runtime-infra-sqlite/src/main/kotlin/skillbill/db/telemetry/GoalTelemetryPayloadSupport.kt:32,46,63,80`
  - `runtime-kotlin/runtime-infra-sqlite/src/main/kotlin/skillbill/db/telemetry/LifecycleTelemetryPayloadSupport.kt:63`

  These are real tracker keys such as `SKILL-139`, which disclose a customer's internal project
  naming.
- `repo` on `resolve_learnings` — declared at
  `orchestration/contracts/telemetry-event-schema.yaml:1812`.
- `skillbill_runtime_exception` payloads — 12 stack frames with class/method/line plus a 512-char
  `error_message`, at
  `runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/telemetry/RuntimeExceptionTelemetry.kt:11-24`.
  Stack frames within skill-bill's own packages are diagnostic and worth keeping; the risk is
  caller-supplied content reaching `error_message`, and frames outside skill-bill packages.

The existing precedent for level-gated redaction is
`runtime-kotlin/runtime-infra-sqlite/src/main/kotlin/skillbill/infrastructure/sqlite/review/ReviewFinishedPayloadSupport.kt:99-127`,
which blanks finding `location`, `description`, `note`, and learning content unless the level is
`full`. Follow that pattern rather than inventing a second mechanism.

Redaction is applied where the payload is built, so an unredacted value is never written to
`telemetry_outbox`.

Where a field is still needed for correlation, prefer a stable hash over dropping it: correlating
events belonging to one issue must remain possible without disclosing the key. Hash inputs must be
salted with a value that does not travel with the event.

## Acceptance Criteria

1. At `anonymous` level, `issue_key` is not uploaded in raw form on any event that currently
   carries it.
2. At `anonymous` level, events that currently carry `issue_key` remain correlatable to one another
   by issue through a stable, non-reversible substitute.
3. At `anonymous` level, `repo` on `resolve_learnings` is not uploaded in raw form.
4. At `anonymous` level, `skillbill_runtime_exception` does not upload caller-supplied content in
   `error_message`, and does not upload stack frames from outside skill-bill's own packages.
5. At `full` level, all four field families above are unchanged from current behavior.
6. Redaction happens at payload construction, so `telemetry_outbox.payload_json` never contains the
   unredacted value when the level is `anonymous`.
7. Tests assert, per field family, both the redacted `anonymous` output and the unredacted `full`
   output.
8. The affected proxy or dashboard queries that key on raw `issue_key` are identified and reported
   in the subtask summary, so the dashboard change can be sequenced against the release.

## Non-Goals

- Changing which fields `full` collects.
- Redacting `install_id`; it is the intended correlation key.
- Rewriting or purging already-uploaded events at the proxy.
- Changing the `repository_identity` value used in MCP workflow-open envelopes; it is validated
  locally and never uploaded.

## Dependency Notes

Independent of subtasks 1 and 2.

This subtask changes what dashboards receive. Subtask 4 documents the resulting per-level
collection table, so subtask 4 must land after this one.

## Validation Strategy

- Payload-builder unit tests per field family, asserting anonymous and full output separately.
- A test asserting the persisted `payload_json` at anonymous level contains no raw `issue_key`.
- A test asserting the hashed substitute is stable across two emissions for the same issue and
  differs across issues.
- `(cd runtime-kotlin && ./gradlew check)`.

## Next Path

Proceed to subtask 4.
