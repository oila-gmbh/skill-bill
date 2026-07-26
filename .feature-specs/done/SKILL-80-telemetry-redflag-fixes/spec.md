# SKILL-80 — Telemetry red-flag fixes

## Context

A telemetry review of skill-bill over the trailing month (2026-05-12 → 2026-06-12),
drawing on both the local review-metrics DB and the PostHog "SkillBill" project,
surfaced four independent reliability/observability problems. This feature
decomposes the remediation into four independently resumable subtasks.

### Evidence summary (trailing month, org-wide unless noted)

- **Decomposed-goal runs almost never complete.** `skillbill_goal_finished`:
  19 `blocked` vs 2 `completed`; `skillbill_goal_subtask_finished`: 8 `blocked` /
  9 `complete`. Local DB agrees (`completed_rate` 0.10). The dominant blocked
  reason is the validate-phase `./gradlew check` failing on **detekt** rules
  (`max-line-length`, `ReturnCount`, `NestedBlockDepth`), concentrated in
  `RuntimeEnforcementHardeningArchitectureTest.kt`. Heavily skewed by repeated
  attempts on a single issue (SKILL-52.4) across 4–5 users.
- **Remote stats endpoint is broken for `bill-feature-implement`.** The proxy's
  live `capabilities` advertises `bill-feature-implement` as supported, but the
  stats handler's workflow allowlist only accepts `bill-feature-task` /
  `bill-feature-verify`. Unrecognized workflows fall through to the **ingest**
  event-schema validator, returning a large, misleading `event_name must be the
  constant value …` error instead of stats or a clean rejection.
- **Verify runs stall and gate-skip metrics are misleading.** `bill-feature-verify`:
  ~50% of runs emit `started` but never `finished` (remote proxy 22 started / 11
  finished; PostHog 16 / 7). Separately, ~19% of feature-implement runs report
  `validation_result=skipped` / `audit_result=skipped`; investigation shows this
  is mostly *expected* early-abandonment runs (`abandoned_at_planning` /
  `abandoned_at_review`) whose blank columns default to `"skipped"` — the metric
  conflates "skipped because abandoned" with "skipped despite completing."
- **Zombie runs and no exception capture.** A `feature_implement` run recorded a
  742-minute (~12.4h) duration; there is no per-run timeout or stale-session
  reconciliation for `feature_implement_sessions` / `feature_task_runtime_sessions`
  (only the goal-runner reconciles its own workflows). Separately, the PostHog
  project captures **no `$exception` events** — telemetry is named-events-only via
  the generic HTTP proxy, so unhandled errors are an observability blind spot.

## Intended outcome

Each red flag is addressed by one subtask with its own acceptance criteria,
runnable and resumable independently. After all four:

- Goal runs are no longer routinely blocked by the detekt validate gate.
- `telemetry_remote_stats` returns data for every workflow the proxy advertises,
  and never surfaces the ingest-schema error for a stats query.
- Verify runs emit a terminal `finished` event (or are otherwise reconciled), and
  skipped-gate telemetry distinguishes abandonment from a genuinely skipped gate.
- Long-running/zombie sessions are reconciled so duration metrics are trustworthy,
  and unhandled exceptions are captured as telemetry.

## Acceptance Criteria

1. Subtask 1 (detekt validate gate) is complete: goal/feature-task validate phase
   no longer hard-fails routinely on detekt `max-line-length` / `ReturnCount` /
   `NestedBlockDepth`, per its own spec.
2. Subtask 2 (telemetry proxy stats contract) is complete: workflow naming is
   consistent end-to-end and stats queries never hit the ingest validator, per its
   own spec.
3. Subtask 3 (verify finish + gate telemetry) is complete: verify emits a terminal
   event reliably and skipped-gate semantics are unambiguous, per its own spec.
4. Subtask 4 (zombie-run reconciliation + exception capture) is complete: stale
   sessions are reconciled and exception telemetry is emitted, per its own spec.

## Non-goals

- Re-architecting the telemetry pipeline or migrating off the generic HTTP proxy.
- Adopting a third-party PostHog SDK (exception capture rides the existing outbox).
- Retroactively repairing historical telemetry rows.
- Broad detekt rule rewrites unrelated to the three blocking rules.

## Constraints

- Spec source: local. Issue key: SKILL-80.
- Kotlin runtime changes must pass `(cd runtime-kotlin && ./gradlew check)`.
- Cloudflare worker changes live in `docs/cloudflare-telemetry-proxy/` and must not
  break the existing ingest path.

## Subtasks

1. `spec_subtask_1_detekt-validate-gate.md` — Detekt validate-gate blocker
2. `spec_subtask_2_telemetry-proxy-stats-contract.md` — Proxy stats workflow contract
3. `spec_subtask_3_verify-finish-and-gate-telemetry.md` — Verify finish + skipped-gate telemetry
4. `spec_subtask_4_zombie-run-and-exception-observability.md` — Zombie-run reconciliation + exception capture

Subtasks are independent (no hard ordering dependencies); ordered by impact.

## Next path

```bash
skill-bill goal SKILL-80
```
