# SKILL-228 · Subtask 2 — IdeStatus and goal blocked visibility

## Scope

Project runtime operator blocks to surfaces the operator actually watches.

Deliver:

- `IdeStatusProjector`: when child workflow current phase is validate/build
  with durable `BLOCKED` + `NEEDS_USER_ACTION`, set `lifecycle_state: blocked`
  (not `active` while parent lease is live).
- `summary` and/or `pauseReason` include the phase `blockedReason` text so
  IntelliJ details and `skill-bill work status` show what to do.
- Extend `operatorDecisionPause` (or parallel field) to read `BLOCKED` +
  `NEEDS_USER_ACTION` quality-gate records — today only `PAUSED` +
  `NEEDS_USER_ACTION` is surfaced.
- Golden / contract tests in `IdeStatusSchemaValidatorTest` and
  `IdeStatusProjector` tests for blocked validate with operator reason.
- IntelliJ plugin: details row shows blocked reason when lifecycle is `blocked`
  from operator-action disposition (minimal mapping change if JSON already
  carries `pauseReason` / summary).

## Acceptance Criteria

1. `skill-bill work status` for a goal blocked on validate `needs_user_action`
   reports `lifecycle_state: blocked` and a summary containing the operator
   action (not *"active on validate"*).
2. `operatorDecisionPause` (or equivalent wire field) is populated for
   `BLOCKED` + `NEEDS_USER_ACTION` on validate/build, not only `PAUSED`.
3. IdeStatus schema parity: contract, Kotlin models, validator, and plugin
   mapping agree on blocked + reason fields.
4. IntelliJ status details show the blocked reason without requiring the
   operator to open the Cursor agent transcript.
5. Active goals without operator blocks remain `lifecycle_state: active`
   (no regression for in-progress repair loops).

## Non-Goals

- VS Code extension.
- Push notifications.
- Changing Stop / Pause button behavior beyond displaying blocked reason.
- Runtime settlement logic (subtask 1).

## Dependency Notes

Requires subtask 1 — durable blocked phase records and goal `blocked_reason`
must exist to project.

## Validation Strategy

- `IdeStatusGoldenFixturesTest` / `IdeStatusProjector` goal projection tests.
- Plugin mapping test for blocked lifecycle + reason row.
- Manual: reproduce WE-4364-shaped state → IntelliJ shows blocked + auth hint.
