# SKILL-80 · Subtask 3 — Verify finish reliability + skipped-gate telemetry semantics

## Scope

Two related telemetry-correctness problems:

**A. Verify runs stall without a terminal event.** ~50% of `bill-feature-verify` runs
emit `feature_verify_started` but never `feature_verify_finished` (remote 22/11,
PostHog 16/7). Emission is orchestrator-driven from
`skills/bill-feature-verify/content.md` (started after step 2, finished after step 8);
abandonment between those steps (code-review loop overruns, completeness-audit loop
overruns, errors, user hand-off) leaves no finished event. `emitOnce`
(`runtime-infra-sqlite/.../LifecycleTelemetryEmitSupport.kt` ~lines 90–101) only
de-dupes — it does not guarantee a terminal emit. There is no
`abandoned`/`error`-terminal path that reliably fires `finished` for verify the way
feature-implement records `abandoned_at_*`.

**B. Skipped-gate metric is misleading.** `audit_result` / `validation_result` default
blank → `"skipped"` in `LifecycleTelemetryPayloadSupport.kt` (~lines 37, 39). For
early-abandoned implement runs (`abandoned_at_planning` / `abandoned_at_review`) the
gates are legitimately never reached, so `"skipped"` is expected — but the stat
conflates "skipped because the run abandoned before the gate" with "gate skipped on a
run that otherwise proceeded," making the ~19% unreadable.

## Acceptance Criteria

1. `bill-feature-verify` has a defined terminal-emission contract: every run that
   emits `feature_verify_started` subsequently emits `feature_verify_finished` with an
   appropriate `completion_status` (`completed`, `abandoned_at_review`,
   `abandoned_at_audit`, or `error`) on its abandonment/error paths — mirroring the
   feature-implement abandonment telemetry pattern. The verify skill content and the
   workflow definition (`runtime-domain/.../workflow/verify/FeatureVerifyWorkflowDefinition.kt`)
   are updated so abandonment/error steps fire the finished event.
2. The `feature_verify_finished` validator accepts the abandonment/error
   `completion_status` values used in (1) without rejecting them.
3. Skipped-gate telemetry is disambiguated: a finished implement payload distinguishes
   "gate not reached due to early abandonment" from "gate genuinely skipped," either by
   constraining `audit_result`/`validation_result` to be `"skipped"` only when
   `completion_status == "completed"` is impossible, or by adding an explicit
   `not_reached` (or equivalent) value / boolean so downstream stats can separate the
   two. The chosen scheme is documented in the subtask history.
4. `feature_implement_stats` / `feature_task_stats` (and the goal stats they feed) can
   report "gates skipped on completed-or-progressing runs" separately from
   "gates not reached on abandoned runs" — i.e. the existing aggregation no longer
   counts early-abandonment as gate-skipping. Add/adjust the stat field(s) accordingly.
5. New tests cover: a verify run that abandons mid-flow still produces a `finished`
   event with the correct status; and an early-abandoned implement run is categorized
   as "not reached" rather than "skipped" in the disambiguated stat.
6. `(cd runtime-kotlin && ./gradlew check)` passes.

## Non-goals

- Forcing verify to always reach a verdict (abandonment is legitimate; the fix is
  *recording* it, not preventing it).
- Changing what the audit/validate gates actually do.
- Backfilling historical rows.

## Dependency notes

Independent of subtasks 1 and 2. Conceptually adjacent to subtask 4 (both touch
session terminal-state recording) but they edit different code paths
(telemetry-emission/validators here vs. stale-session reconciliation + exception
capture there); keep changes non-overlapping. If both land, ensure the reconciliation
in subtask 4 and the terminal-emission contract here agree on terminal
`completion_status` values for abandoned verify/implement runs.

## Validation strategy

- Unit tests for the verify terminal-emission paths and the implement
  skipped-vs-not-reached classification.
- Re-run `feature_verify_stats` / `feature_task_stats` against a seeded local DB and
  confirm the new categorization.
- `(cd runtime-kotlin && ./gradlew check)`.

## Next path

```bash
skill-bill goal SKILL-80
```
