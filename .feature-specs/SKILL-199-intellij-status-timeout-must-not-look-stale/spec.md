# SKILL-199 — Do not render a live goal as stale because a status poll was slow

## Context

On 2026-08-19, during the live SKILL-196 goal, `skill-bill goal status SKILL-196` reported
`execution_liveness: live` and `current_step: audit`. `skill-bill work status --repo-root
<repo> --format json` reported `lifecycle_state: active`, `freshness: fresh`, and
`current_step.id: audit`. The IntelliJ status widget still showed **stale**, often frozen on
an earlier step (`implement`).

Those two CLI snapshots are the authority the widget is supposed to display. The widget did
not display them.

### Cause 1 — a slow poll is remapped to freshness-stale

The plugin polls `skill-bill work status --repo-root <canonical> --format json` with
`DEFAULT_CLI_TIMEOUT_MS = 10_000`. On this repository that command took **8.5–9.2s** in the
same session. A timeout becomes `SkillBillStatusOutcome.Unavailable(TIMEOUT)`.

`StatusRefreshCoordinator.refreshOnce` then replaces `Unavailable` (and thrown failures)
with `preferences.getLastKnownDisplayCache()?.toStaleOutcome()`. That helper always builds
`SkillBillStatusOutcome.Stale(fromCache = true)` with diagnostic `cache_fallback`.

`IdeStatusJsonMapper` also maps CLI `freshness: stale` plus `lifecycle_state: active|paused`
to `Stale`, which **replaces** the live lifecycle. The status bar then renders
`Skill Bill · stale · …` and tooltip `(Stale — not live)`.

A timed-out poll of a still-live goal is therefore indistinguishable from “the run went
quiet and we no longer trust it.” The last successful step stays on the bar (often
`implement`) after the child has already moved to `audit`.

The existing test `unavailable falls back to stale cache only` locks this defect in.

SKILL-168 already taught the plugin not to blank a live display on a single
`no_matching_work` sample. Timeout-to-stale is the same class of lie on a different
failure code.

### Cause 2 — the 10s budget has no headroom

Even when the poll *succeeds*, 9s vs a 10s cap is a race. Under load it loses, and Cause 1
fires. Raising the timeout without changing the fallback still leaves every other
Unavailable reason (process failure, cancel) looking like a dead run.

### Not this bug

- `goal status` vs `work status`: the widget must keep polling `work status`. That is not a
  defect.
- CLI `freshness: stale` after a true 30-minute `updated_at` gap: `IdeStatusFreshnessClassifier`
  (`FRESH_WINDOW` = 30 minutes) is a separate product rule. This ticket does not shrink that
  window and does not switch freshness to worktree mtime. Audit/review/validate are
  read-only; silence there is not implement-stuck.
- An installed plugin binary older than this repo’s `intellij-plugin` sources: operators
  still need to install a build that contains the fix; this spec is the fix.

## Intended Outcome

While a goal is live, the IntelliJ status bar shows the latest successful `work status`
lifecycle and current step. A slow, timed-out, or cancelled poll does not relabel that live
observation as **stale** or freeze the bar on a superseded step as if the run had died.

Poll failure and contract freshness stay distinct in the UI.

## Acceptance Criteria

1. A successful `work status` payload with `lifecycle_state: active` and `freshness: fresh`
   renders as live Active, including `current_step` values such as `audit`, `review`, and
   `validate`, not as Stale.
2. After the widget has shown a live Active snapshot, a subsequent poll that times out,
   is cancelled, or fails to start does not emit `SkillBillStatusOutcome.Stale` from the
   last-known cache. The previously displayed live step and lifecycle remain visible.
3. That retained live display is not labelled `(Stale — not live)`. Tooltip and details copy
   distinguish “poll failed / timed out, showing last live snapshot” from CLI
   `freshness: stale`.
4. `DEFAULT_CLI_TIMEOUT_MS` (or its successor) is large enough that a `work status` call
   taking 9.2s on this repository succeeds with headroom; a regression test pins the
   timeout at no less than 30 seconds.
5. A CLI payload that itself carries `freshness: stale` with `lifecycle_state: active` still
   maps to the existing freshness-Stale presentation. This ticket does not remove that
   contract mapping.
6. Freshness classification stays on the contract `updated_at` window. The plugin does not
   treat worktree mtime, or “no file write for 10 minutes in implement,” as the stale
   signal.
7. A poll that times out before any live snapshot exists still surfaces Unavailable
   (timeout), not a fabricated Active.
8. Plugin unit tests cover: fresh+active audit stays Active; timeout after Active retains
   Active with poll-failed copy; timeout with empty cache stays Unavailable; CLI
   `freshness: stale` still becomes Stale; the timeout constant floor.

## Constraints

- Keep consuming `skill-bill work status --format json` only. Do not add a `goal status`
  poll path.
- Preserve SKILL-168 transient-idle smoothing for `no_matching_work`.
- Additive UI copy only; no `contract_version` bump unless a new wire field is strictly
  required (it should not be — timeout is a transport outcome).
- Plugin remains an external CLI consumer: no SQLite, no runtime-kotlin imports.
- No comments added to changed files.

## Non-Goals

- Speeding up `work status` itself (follow-up if p95 stays near the new timeout).
- Changing `IdeStatusFreshnessClassifier.FRESH_WINDOW`.
- Implement-stuck detection from file mtime.
- Forcing operators to rebuild the plugin is documented as install, not spec scope.
- Changing `goal status` liveness semantics.

## Diagnostic Evidence

- `intellij-plugin/src/main/kotlin/dev/skillbill/intellij/domain/Constants.kt` —
  `DEFAULT_CLI_TIMEOUT_MS = 10_000L`.
- `CliSkillBillStatusRepository.kt` — timeout → `UnavailableReason.TIMEOUT`.
- `StatusRefreshCoordinator.kt:124-127` — `Unavailable` / `Incompatible` →
  `toStaleOutcome()`.
- `LastKnownDisplayCache.kt:167-184` — cache fallback is always `Stale(fromCache = true)`.
- `StatusRefreshCoordinatorTest` — `unavailable falls back to stale cache only`.
- `IdeStatusJsonMapper.kt:151-157` — CLI `freshness: stale` replaces live lifecycle.
- `SkillBillStatusBarPresentation.STALE_NOTE` — `(Stale — not live)`.
- Observed 2026-08-19: `work status` 8515ms then 9232ms; payload `fresh` / `active` /
  `audit` while the widget showed stale.

## Subtask Decomposition

One implementation unit: plugin timeout budget, coordinator fallback, and presentation copy.

## Validation Strategy

- Plugin unit tests listed in acceptance criterion 8.
- Manual: with a live goal on `audit`, confirm the bar shows `audit` (or current step) as
  live; kill or delay the CLI past the old 10s window and confirm the bar does not flip to
  `(Stale — not live)`.
