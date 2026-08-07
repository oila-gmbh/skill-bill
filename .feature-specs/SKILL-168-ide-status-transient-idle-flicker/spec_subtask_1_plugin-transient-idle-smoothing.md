# SKILL-168 · Subtask 1 — Plugin transient-idle smoothing

## Scope

Stop a single transient `no_matching_work` sample from erasing a live goal display in the
IntelliJ status bar widget. Entirely within the plugin (`intellij-plugin/`); no runtime or
wire change.

Three files carry the change:

- `src/main/kotlin/dev/skillbill/intellij/infrastructure/cli/IdeStatusJsonMapper.kt` — mark
  the `no_matching_work`-derived `Idle` so downstream policy can recognize it.
- `src/main/kotlin/dev/skillbill/intellij/application/StatusRefreshCoordinator.kt` — apply
  the corroboration policy in `refreshOnce`, where the emit decision already lives
  (`:103-112`).
- `src/main/kotlin/dev/skillbill/intellij/domain/SkillBillStatusOutcome.kt` — only if a
  marker field is needed beyond the existing `diagnostic` carrier.

### Design decision: marker, not a new outcome type

`SkillBillStatusOutcome.Idle` already carries `diagnostic: StatusDiagnostic?`, and
`StatusDiagnostic` already has a `reasonCode: String?`. Set
`diagnostic = StatusDiagnostic(reasonCode = "no_matching_work")` on the `Idle` returned at
`IdeStatusJsonMapper.kt:98-104`. Prefer this over introducing a new outcome variant or a new
boolean: it reuses the established diagnostic channel, keeps the presentation `when`
exhaustive without new branches, and leaves `UnavailableReason.NO_MATCHING_WORK` (retained
for wire compatibility only) untouched.

### Design decision: where the policy lives

Put the policy in `StatusRefreshCoordinator.refreshOnce`, not in the mapper and not in the
presentation layer. The mapper is a pure per-response translation with no memory of the
previous sample, and the presentation layer must stay a pure function of one outcome. The
coordinator already owns the emit decision, the cache read/write, and the only sequential
state in the pipeline.

### Design decision: corroboration policy

Hold state in the coordinator: the last emitted outcome, plus a counter of consecutive
unconfirmed `no_matching_work` samples.

A `no_matching_work` `Idle` is **unconfirmed** when the last emitted outcome was live —
`Active`, `Blocked`, `Failed`, or `Stale` (and `Paused` once SKILL-165 lands; see
Dependency Notes). While unconfirmed and the tolerance is not exhausted, re-emit the
previously emitted outcome unchanged and do not touch the persisted cache.

`UNCORROBORATED_IDLE_TOLERANCE = 1`: tolerate one unconfirmed sample, commit to `Idle` on
the second consecutive one. At the default poll interval this settles genuine idleness within
roughly two polls while absorbing the observed single-sample glitch.

Reset the counter to zero on any non-`no_matching_work` outcome.

When the last emitted outcome was **not** live — session start (`_outcomes.value == null`),
or an already-idle/unavailable/incompatible state — the sample is **confirmed immediately**
and `Idle` is emitted on the first poll. This is what keeps a genuinely empty repository
prompt.

The held value is the previous **in-memory** emitted outcome, not a read of
`LastKnownDisplayCache`. So holding does not surface a persisted cache entry as
authoritative-active, and the cache contract ("cached display may surface only as `Stale`")
is untouched. Do not write the held outcome back to the cache during a hold — that would
advance the cache's `observedAt` on a sample that observed nothing.

## Acceptance Criteria

1. The `Idle` outcome produced from problem code `no_matching_work`
   (`IdeStatusJsonMapper.kt:98-104`) carries a diagnostic marker identifying that origin, and
   an `Idle` produced from a `lifecycle_state` of `idle`/`terminal` does not.
2. Given a last emitted live outcome, a single `no_matching_work` sample causes the
   coordinator to emit the previously emitted outcome unchanged; the widget display does not
   change.
3. Two consecutive `no_matching_work` samples cause the coordinator to emit the `Idle`
   outcome, so a goal that genuinely completes or is removed settles to idle within a bounded
   number of polls.
4. With no prior live outcome — first poll of a session, or a preceding idle/unavailable/
   incompatible outcome — a `no_matching_work` sample emits `Idle` immediately with no hold.
5. Any non-`no_matching_work` outcome resets the consecutive-unconfirmed counter, so two
   glitches separated by a good sample never combine to trip the threshold.
6. During a hold, the persisted `LastKnownDisplayCache` is neither written nor advanced, and
   no held or cached display is emitted as `Active`.
7. Presentation and UI mapping are unchanged: no new branch is added to the presentation
   `when`, and existing `StatusUiMapper` / presentation behavior for every outcome is
   preserved.
8. The plugin still depends only on `com.intellij.modules.platform`, and no new CLI flag,
   command, or wire field is introduced.

## Non-Goals

- No runtime, CLI, wire, or schema change — the bad sample itself is subtask 2's problem.
- No change to the `Unavailable` / `Incompatible` cache-fallback path at
  `StatusRefreshCoordinator.kt:103-106`.
- No settings UI or user-configurable tolerance; the threshold is a named constant.
- No change to `UnavailableReason.NO_MATCHING_WORK`, which stays retained-for-compatibility.
- No attempt to distinguish *why* the runtime returned `no_matching_work`.

## Dependency Notes

- No dependency on subtask 2. This subtask alone removes the user-visible flicker.
- **Must be implemented after SKILL-165 merges.** SKILL-165 subtask 2 edits
  `IdeStatusJsonMapper` and the plugin presentation seam, and adds a `Paused` outcome variant
  plus `GoalPlanningInfo` that do not exist on `main`. When enumerating "live" outcomes for
  the unconfirmed check, include `Paused` — a paused goal is live work and must be held
  through a transient sample exactly like an active one.

## Validation Strategy

- Unit tests on `StatusRefreshCoordinator` with a fake `StatusRepository` (the existing
  `TestFakes.kt` already provides the seam) driving scripted outcome sequences:
  live → nmw → live (display never changes); live → nmw → nmw (commits to `Idle`);
  null → nmw (immediate `Idle`); live → nmw → live → nmw (counter resets, no commit).
- Unit test on `IdeStatusJsonMapper` asserting the diagnostic marker is present for a
  `no_matching_work` payload and absent for a `lifecycle_state: idle` payload.
- Assert via the fake `PreferenceCachePort` that no cache write occurs during a hold.
- Run the full existing plugin test suite; `StatusRefreshCoordinatorTest`,
  `ProcessRunnerAndMapperTest`, `StatusUiMapperTest`, and the presentation tests must stay
  green unmodified except where they encode the old blank-on-first-nmw behavior.

## Next Path

Subtask 2 — runtime read snapshot consistency — removes the bad sample at its source.
