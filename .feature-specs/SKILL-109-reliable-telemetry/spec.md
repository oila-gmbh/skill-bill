---
status: In Progress
issue_key: SKILL-109
source: PostHog telemetry analysis (2026-06-08 → 2026-07-08, 45-day issue window), Claude Code session
---

# SKILL-109: Make skill-bill telemetry reliable

## Problem

skill-bill telemetry cannot be read at face value. A PostHog analysis of the past 30–45 days
(excluding `test-install-id` and null installs) surfaced two classes of defect: (1) the goal
outcome is actively **misframed**, and (2) several fields are **unreliable or unpopulated**.
Together they make every telemetry-derived metric misleading unless an analyst hand-rolls bespoke
SQL. The intended outcome is telemetry that is trustworthy end-to-end: a single countable row per
goal issue, plausible durations, populated fields, normalized labels, and terminal events for
every started session.

## Findings (evidence)

### Goal outcome is misframed (per-invocation, not per-issue)
- `workflow_id` on goal events is **per-segment** (`"${parentWorkflowId}:seg:${segmentStartedAt}"`,
  `GoalRunnerTelemetryEmitter.kt:38`); `issue_key` is the real unit of work and is immutable
  (`DecompositionManifestModels.kt:67`). `parentWorkflowId` is constant across all invocations of a
  goal.
- 149 goal invocations (past 30d) collapse to **31–33 distinct `issue_key`**. By invocation the goal
  flow reports **87% blocked / 13% completed**; by issue the true completion rate is **60.6%**.
- 96% of issues that hit a block are resumed afterward; only ~4% are truly abandoned; ~50% of
  incomplete issues are still active. A completed issue averages 4.6 invocations / 6.1 resumes.
- `goal_finished.status` (`GoalRunnerTelemetryEmitter.kt:103`) collapses every non-`Completed`
  report to `"blocked"` with no transient-vs-durable distinction, though the underlying
  `GoalRunnerStopReason` enum (`GoalRunnerModels.kt:17-26`) carries `BLOCKED`, `POLICY_BLOCKED`,
  `INTERRUPTED`, `TIMEOUT`, `FAILED`, `NO_TERMINAL_STORE_OUTCOME`, `PULL_REQUEST_FAILED`,
  `DEPENDENCIES_BLOCKED`.
- There is **no issue-level terminal event** and no cheap issue-keyed aggregation: the telemetry
  outbox is not indexed by `issue_key`, and the workflow store only holds the *current* manifest.

### Unreliable / unpopulated fields
- **`duration_seconds` broken on `skillbill_feature_implement_finished`**: completed MEDIUM runs
  p50 ≈ 1s, max ≈ 146h (LARGE is sane). Root cause: legacy DBs lack the `started_at` column on
  `feature_implement_sessions` (no migration), so `LifecycleTelemetryDurationSupport.durationSeconds()`
  returns 0 when `started_at` is blank. The multi-day maxes are legitimate long sessions
  (`started_at` is preserved across the finished-at UPDATE).
- **`was_edited_by_user`** on `skillbill_pr_description_generated` is `false` on 57/57 all-time
  (0/15 last 30d). Read from MCP args (`McpLifecycleToolHandlers.kt:170`); the caller never detects
  or passes `true`. No edit-detection exists at the call site.
- **`last_incomplete_phase`** empty on ~33% of `skillbill_feature_task_runtime_finished`.
  `FeatureTaskRuntimeLifecycleTelemetry.kt:61` casts to `Blocked`; `Completed`/`Decomposed` → empty.
- **`blocked_reason`** empty/uncategorized on ~50% of blocked runtime + goal-subtask outcomes.
  Sourced from `artifacts["blocked_reason"]` with generic fallbacks
  (`DecompositionManifestRuntimeStateSupport.kt:104`); many blocked-exit paths never populate it.
- **Terminal-event leakage**: feature_verify 43%, feature_implement (dead since 2026-06-19), prose
  flows; the runtime flow is the model at 1.4%. `StaleSessionReconciler` exists but is tests-only
  and marks stale without emitting a terminal `finished` event.
- **Free-text labels**: `review_platform` echoes the caller's free-text stack fingerprint (24
  variants for 10 real `platform_slug`s); `detected_stack` embeds fallback prose
  ("kmp -> kotlin quality-check fallback"); `routed_skill` once carried a `skill-bill:` namespace
  prefix; several finished events have blank routing fields.
- **Unit drift / vestigial fields**: goal events use `duration_ms`, everything else
  `duration_seconds`; `duplicate_terminal_finished_events` is 0 everywhere; review
  `unresolved_findings` is 0.0 in every week.

## Design decisions

1. **New dedicated event `skillbill_goal_issue_finished`** (runtime-internal emission, not an MCP
   tool). Emitted when an issue reaches a terminal outcome: `status = completed` when a goal
   segment returns `GoalRunnerRunReport.Completed`; `status = abandoned` when the stale-session
   reconciler determines an issue has had no activity for N days and its last segment was blocked
   (retrospective — the only reliable way to detect abandonment). Carries `issue_key`,
   `parent_workflow_id`, `status`, final `subtasks_complete/blocked/skipped`, `total_invocations`,
   `total_blocks`, `total_resumes`, `first_started_at`, `finished_at`, `duration_ms`.
2. **Issue aggregates need a small store.** Add a new `goal_issue_progress` table keyed by
   `parent_workflow_id` (+`issue_key`), incremented on `goalStarted`/`goalFinished`, read at
   completion. Chosen over mutating `GoalRunnerManifestState` (layering smell + manifest contract
   risk) and over outbox JSON queries (retention-fragile). New table = additive = migration-safe.
3. **Sub-categorize `blocked`** by adding `stop_reason` (the existing `GoalRunnerStopReason` enum)
   to `goal_finished`, keeping `status` for back-compat, and adding `abandoned` to
   `goalFinishedStatusEnum` in the schema.
4. **Telemetry uses the lighter-weight contract pattern** (`orchestration/contracts/telemetry-event-schema.yaml`
   v1.3.0 + `TELEMETRY_EVENT_CONTRACT_VERSION` + parity tests + `InvalidTelemetryEventSchemaError`).
   The outbox does no per-event validation at enqueue, so reliability rests on parity tests +
   correct payload construction — each new field/event gets a parity/assertion test.

## Subtask map

1. (P0) Goal issue-level terminal event + blocked sub-categorization — `spec_subtask_1_goal_issue_event.md`
2. (P0) Fix `duration_seconds` + unit consistency — `spec_subtask_2_duration_fix.md`
3. (P0/P1) Field population (`was_edited_by_user`, `last_incomplete_phase`, `blocked_reason`, `goal_started.status`) — `spec_subtask_3_field_population.md`
4. (P1) Normalize platform/stack labels + `routed_skill` prefix — `spec_subtask_4_label_normalization.md`
5. (P1) Terminal-event completeness (kill leakage) — `spec_subtask_5_terminal_completeness.md`
6. (P2) Reliability contract test + hygiene — `spec_subtask_6_contract_test.md` (depends on 1–5)

## Non-goals

- SQLite `SQLITE_BUSY` workflow-store locking (31 exceptions, 2 installs) — reliability-adjacent
  but a distinct fix (WAL / retry-on-busy). Track separately.
- `feature_implement` flow sunset (silent since 2026-06-19) — already tracked as SKILL-13.
- Redesigning the telemetry transport/proxy or outbox retention policy.

## Validation strategy

See each subtask's Validation Strategy. Cross-cutting: `(cd runtime-kotlin && ./gradlew check)`
must stay green, including new parity + reliability tests; `skill-bill validate`,
`npx --yes agnix --strict .`, and `scripts/validate_agent_configs` must pass. Post-deploy, re-run
the PostHog aggregations and confirm goal issue-completion reads from a single event, session
leakage < 5%, and no blank routing fields.

## Next path

```bash
skill-bill goal SKILL-109
```
