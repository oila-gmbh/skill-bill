# SKILL-168 · Subtask 4 — Expose pause signals on the ide-status contract

## Scope

Make "a pause is requested but has not landed yet" and "the pause took effect at this time"
visible to ide-status consumers. Without them the IDE cannot disable its pause control after
a click, because `lifecycle_state` only turns `paused` once the pause is *consumed* at a
boundary — which can be many minutes later, or never if the goal is stopped first.

Files:

- `orchestration/contracts/ide-status-schema.yaml` — additive optional properties.
- `runtime-application/.../work/IdeStatusProjector.kt` — populate them in `projectGoal`.
- `runtime-application/.../model/IdeStatusModels.kt` — carry them on `IdeStatusSnapshot`
  and its wire map.
- Golden fixtures and schema tests under `runtime-infra-fs/src/test/.../workflow/`.

### Design decision: additive optional fields, no version bump

`lifecycle_state` is a governed enum and already carries `paused`; its meaning must not
change. Add sibling optional fields instead, so an older consumer that ignores them still
behaves exactly as today. This keeps `contract_version` at `0.1` — the existing
`IDE_STATUS_CONTRACT_VERSION` pin in the plugin (`domain/Constants.kt:7`) stays valid and
no plugin/runtime version handshake breaks.

### Design decision: report request state, not a second lifecycle

Do not introduce a `pause_requested` lifecycle value. A goal with a pending pause is still
genuinely active — it is running its current subtask — and collapsing it into a paused-ish
lifecycle would reintroduce exactly the "says stopped while working" lie this feature
exists to remove. The pending request is a *modifier* on an active goal.

### Design decision: source of truth

`GoalRunnerStatusProjection` already surfaces `paused` and `pauseRequested`, and
`IdeStatusProjector.goalLifecycle` already reads them (`:138-143`). Take the request flag
from there rather than re-reading control state, so lifecycle and the new field cannot
disagree. Take the pause timestamp from the durable control state added in subtask 3.

Emit both only for the `feature-goal` family; other families have no goal pause controls
and must not grow phantom fields.

### Interaction with the landed lease-expiry inference

A goal whose lease expired projects as `paused` without any durable pause record. In that
case there is no durable pause timestamp, and the field must be omitted rather than
back-filled from `heartbeat_at` — `updated_at` already carries the inferred stop time, and
inventing a durable-looking timestamp would misrepresent an inference as a record. Once
subtask 3 lands, a genuinely stopped goal carries a real timestamp and that one is used.

## Acceptance Criteria

1. `ide-status-schema.yaml` declares an optional boolean for "a pause is requested but not
   yet consumed" and an optional timestamp for "when the pause took effect", both additive
   and neither added to the schema's required list.
2. `contract_version` remains `0.1`, and the plugin's pinned
   `IDE_STATUS_CONTRACT_VERSION` continues to match without change.
3. For a `feature-goal` with a pause requested and not yet consumed, the payload reports the
   request flag as true while `lifecycle_state` remains `active`.
4. Once the pause is consumed, `lifecycle_state` becomes `paused` and the payload reports
   the pause timestamp from the durable control state.
5. A goal projected as `paused` purely by lease-expiry inference, with no durable pause
   record, omits the pause timestamp rather than synthesizing one; `updated_at` still
   carries the inferred stop anchor.
6. Non-`feature-goal` families never emit either field.
7. A consumer that ignores both fields observes byte-identical behavior to today for every
   existing scenario, and the schema validator accepts payloads both with and without them.
8. Golden fixtures cover: pause-requested-not-consumed, pause-consumed-with-timestamp, and
   lease-expired-without-timestamp; `IdeStatusGoldenFixturesTest`,
   `IdeStatusSchemaValidatorTest`, and the schema contract-version test stay green.

## Non-Goals

- No new `lifecycle_state` enum value.
- No `contract_version` bump.
- No change to `no_matching_work`, retention windows, or selection ordering.
- No plugin consumption; that is subtask 5.
- No exposure of lease internals (pid, host identity, owner token) on the wire — they are
  process-control details, not status.

## Dependency Notes

Depends on subtask 3 for the durable pause timestamp and the pause-reason vocabulary. The
request flag alone could ship without subtask 3, but splitting them would mean two contract
edits and two fixture regenerations, so they are sequenced rather than parallelised.

No dependency on subtasks 1 or 2.

## Validation Strategy

- Schema tests asserting both fields are optional and absent from `required`, and that
  payloads with and without them validate (AC 1, 7).
- Projector unit tests in `IdeStatusServiceTest` for each of AC 3, 4, 5, and 6, using the
  existing `StubGoalManifestStore` / `TrackingDatabase` seams — note the stub already
  accepts an execution lease for the expiry case.
- A test asserting the pinned contract version is unchanged (AC 2).
- Golden fixture regeneration for the three scenarios in AC 8, reviewed as a diff so any
  unintended field movement in existing fixtures is caught.
- Full runtime suite plus `runtime-infra-fs` contract tests.

## Next Path

Subtask 5 — the plugin renders the controls and consumes these signals.
