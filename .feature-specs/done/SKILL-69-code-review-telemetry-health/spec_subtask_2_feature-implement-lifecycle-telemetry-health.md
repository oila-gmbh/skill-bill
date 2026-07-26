---
status: Complete
---

# SKILL-69 Subtask 2 - Feature-implement Lifecycle Telemetry Health

Parent spec: [.feature-specs/SKILL-69-code-review-telemetry-health/spec.md](./spec.md)
Issue key: SKILL-69

## Scope

Harden `bill-feature-task` / feature-implement telemetry so health metrics can
separate real workflow behavior from telemetry data quality. This subtask owns
test/synthetic marking, session accounting, duration semantics, and child-step
payload completeness for feature-implement started/finished events.

- Add or persist a source marker sufficient to identify test/synthetic
  telemetry without hard-coded remote-query values such as `test-install-id` or
  `fis-00000000-000000-test`.
- Enforce valid feature-implement session ids and report malformed ids as
  data-quality debt in stats inputs.
- Ensure one authoritative terminal `skillbill_feature_implement_finished`
  event per session for health reporting; duplicate terminal emissions must be
  detectable and excluded from deduped health rates.
- Clarify duration semantics so stats can distinguish synthetic zero-duration
  records, normal runs, and long-running/resumed wall-clock spans.
- Enforce child-step completeness on `skillbill_feature_implement_finished`:
  non-blank canonical child `skill`, complete review child payloads, quality
  check result/iteration/failure fields, and PR-description PR/commit fields
  when available.
- Preserve parent-owned telemetry semantics and existing workflow behavior.

## Acceptance Criteria

1. Feature-implement telemetry includes a test/synthetic marker or equivalent
   persisted source classification that local/remote stats can filter by
   default.
2. Health accounting dedupes by `session_id`, flags duplicate terminal finished
   events, and does not treat every started-without-finished session as failed.
3. Malformed and blank session ids are excluded from health rates and counted as
   data-quality debt.
4. Duration aggregation has clear fields or reporting rules for normal,
   synthetic zero-duration, and resumed/long-running runs.
5. Feature-implement child steps never use blank `skill` names; known child
   skills use canonical names.
6. Review child steps include the subtask 1 review fields, including
   `rejected_findings`, `rejected_rate`, and per-finding `issue_category`.
7. Quality-check child steps include result, iterations, initial/final failure
   counts, and failing check details when telemetry level allows.
8. PR-description child steps include PR-created state and available commit/count
   fields.
9. Tests cover production/test telemetry, duplicate terminal events, malformed
   session ids, open sessions, completed/abandoned/error sessions, duration
   buckets, and child-step required fields.

## Non-Goals

- Do not change review category assignment; subtask 1 owns that.
- Do not build the final stats CLI/MCP/reporting surface; subtask 3 owns that.
- Do not redesign the feature-task runtime loop or alter phase execution
  behavior beyond telemetry contract hardening.
- Do not backfill historical PostHog events.

## Dependency Notes

Depends on subtask 1 for the canonical review child payload shape. Produces the
feature-implement health inputs that subtask 3 aggregates and documents.

## Validation Strategy

- Feature-implement telemetry mapping tests with mixed normal/test/malformed
  records.
- Workflow or MCP golden tests proving `child_steps` use canonical names and
  complete payloads.
- Deduplication tests for repeated terminal `finished` emissions.
- Duration aggregation fixture tests covering zero-duration and long-running
  resumed sessions.

## Next Path

Subtask 3 adds user-facing stats/reporting and documentation over the hardened
review and feature-implement telemetry contracts.

## Spec Path

.feature-specs/SKILL-69-code-review-telemetry-health/spec_subtask_2_feature-implement-lifecycle-telemetry-health.md
