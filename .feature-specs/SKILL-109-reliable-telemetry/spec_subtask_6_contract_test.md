---
issue_key: SKILL-109
subtask_id: 6
---

# Subtask 6 — Reliability contract test + hygiene

## Scope

Lock in the reliability guarantees from subtasks 1–5 with a data-driven architecture/contract test,
audit vestigial fields, and default telemetry dashboards/queries to exclude test installs.

## Root cause

The telemetry outbox performs **no per-event validation at enqueue**; reliability rests entirely on
parity tests and correct payload construction. There is no test asserting the *behavioral*
reliability contract (issue-level terminal completeness, no blank routing, plausible durations,
populated blocked reasons). Vestigial fields (`duplicate_terminal_finished_events`,
`unresolved_findings`) are unverified. `test-install-id` and null installs pollute production
dashboards (GH #43 precedent).

## Proposed solution

- Add a reliability contract/architecture test (reuse the data-driven pattern in
  `TelemetryEventSchemaValidatesAllEventsTest.kt`) asserting, across representative emitted
  payloads:
  - every completed goal issue emits a `skillbill_goal_issue_finished`;
  - no finished event has blank `routed_skill`/`detected_stack`/`platform_slug`;
  - `duration_seconds` is plausible for completed runs (> 0, bounded);
  - `stop_reason`/`status` adhere to their enums;
  - `blocked_reason` is non-empty on every blocked outcome;
  - `was_edited_by_user` can be exercised `true`.
- Audit `duplicate_terminal_finished_events` and `unresolved_findings`: confirm they are captured
  and exercised by a test, or remove them from the schema/emission.
- Set the default filter for telemetry dashboards/queries to exclude `test-install-id` and null
  installs; document the filter in the spec parent or a telemetry README.

## Acceptance Criteria

1. A reliability contract test asserts the issue-level terminal-completeness guarantee.
2. The contract test asserts no blank `routed_skill`/`detected_stack`/`platform_slug` and plausible
   `duration_seconds` bounds.
3. The contract test asserts `stop_reason`/`status` enum adherence and non-empty `blocked_reason`
   on blocked outcomes.
4. `duplicate_terminal_finished_events` and `unresolved_findings` are either covered by a test or
   removed.
5. Telemetry dashboards/queries default to excluding `test-install-id` and null installs, with the
   filter documented.
6. `(cd runtime-kotlin && ./gradlew check)` is green with the new test.

## Non-goals

- Building a full telemetry observability dashboard suite.
- Changing the proxy/sync runtime.

## Dependencies

Depends on subtasks 1–5 (it asserts their guarantees).

## Validation strategy

- The contract test itself is the primary verification; it must fail when any asserted guarantee is
  violated by a deliberately-broken payload (mutation check).
- `(cd runtime-kotlin && ./gradlew check)` green.

## Next path

```bash
skill-bill goal SKILL-109
```
