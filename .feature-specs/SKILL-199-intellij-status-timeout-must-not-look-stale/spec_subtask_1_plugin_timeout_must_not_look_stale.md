# SKILL-199 Subtask 1 — Plugin timeout must not look like a dead run

## Scope

Fix the IntelliJ status widget so a live `work status` snapshot is not rewritten as Stale
when the next poll is slow or fails in transport.

Today three seams cooperate to lie:

1. `DEFAULT_CLI_TIMEOUT_MS = 10_000` races a `work status` call that takes 8.5–9.2s on this
   repository.
2. `CliSkillBillStatusRepository` maps that race to `Unavailable(TIMEOUT)`.
3. `StatusRefreshCoordinator` converts every `Unavailable` / `Incompatible` (and thrown
   failure) into `LastKnownDisplayCache.toStaleOutcome()`, which is always
   `Stale(fromCache = true)`. Presentation then applies `(Stale — not live)`.

This subtask owns all three seams. It does not change the IDE status JSON schema, does not
poll `goal status`, and does not change `IdeStatusFreshnessClassifier`.

Required behaviour:

- Raise the CLI poll timeout to at least 30 seconds and pin that floor in a test.
- After a live Active (or live Paused) observation, a TIMEOUT, CANCELLED, or PROCESS_FAILURE
  poll retains the live outcome for display. It must not call `toStaleOutcome()`.
- Retained live display may add poll-failed copy. It must not use `STALE_NOTE` /
  `(Stale — not live)`.
- Timeout before any live observation remains `Unavailable(TIMEOUT)`.
- Missing executable and misconfigured override may still surface Unavailable (or the
  existing non-live presentation). They must not reuse freshness-Stale copy that claims the
  goal died.
- CLI `freshness: stale` with `lifecycle_state: active|paused` still maps to Stale via
  `IdeStatusJsonMapper` as today.
- Update or replace `unavailable falls back to stale cache only` so it no longer requires
  timeout-after-live to become Stale.

Touched area: `intellij-plugin/` only.

## Acceptance Criteria

1. A mapped Active outcome from `lifecycle_state: active`, `freshness: fresh`,
   `current_step.id: audit` renders as Active with step label audit, not Stale.
2. Given a coordinator that has already emitted Active, the next `Unavailable(TIMEOUT)`
   (or CANCELLED / PROCESS_FAILURE) emission is not `Stale`. The visible step remains the
   last live step.
3. Tooltip/details for that retained snapshot say the poll failed or timed out and that the
   last live snapshot is shown. They do not contain `(Stale — not live)`.
4. A unit test asserts `DEFAULT_CLI_TIMEOUT_MS >= 30_000`.
5. `IdeStatusJsonMapper` still maps `freshness: stale` + `lifecycle_state: active` to
   `SkillBillStatusOutcome.Stale` with `fromCache = false`.
6. First poll `Unavailable(TIMEOUT)` with empty cache emits Unavailable, not Active and not
   a synthetic Stale step.
7. Freshness is not derived from worktree mtime in the plugin.
8. `StatusRefreshCoordinatorTest` no longer requires Unavailable-after-live to become
   cache Stale for TIMEOUT. Tests listed in parent acceptance criterion 8 pass.
9. `./gradlew check` in `intellij-plugin` (or the plugin module’s existing test task) passes.
   No comments are added to changed files.

## Non-Goals

- Runtime `work status` latency.
- Changing the 30-minute `updated_at` freshness window.
- File-mtime implement-stuck heuristics.
- SKILL-168 `no_matching_work` smoothing, except it must keep working.
- A new IDE-status wire field.

## Dependency Notes

- No subtask dependency.
- Builds on SKILL-168’s rule that a bad sample must not overwrite a live display, applied
  here to transport failure instead of `no_matching_work`.

## Validation Strategy

- Unit tests on `StatusRefreshCoordinator`, `StatusUiMapper` /
  `SkillBillStatusBarPresentation`, `IdeStatusJsonMapper`, and the timeout constant.
- Re-run the existing SKILL-168 smoothing tests to prove they still hold.
- Manual check on a live goal: bar tracks `audit`; delayed CLI does not flip to stale.

## Next Path

Prepared for `skill-bill goal SKILL-199`. Implementation starts in `intellij-plugin/`.
