---
issue_key: SKILL-109
subtask_id: 3
---

# Subtask 3 — Field population (was_edited_by_user, last_incomplete_phase, blocked_reason, goal_started.status)

## Scope

Make four routinely-empty telemetry fields reliably populated so analysts do not see
ambiguous-null or always-false values.

## Root cause

- **`was_edited_by_user`** (`McpLifecycleToolHandlers.kt:170`) is read from MCP args; the caller
  never detects edits and always passes `false`. No edit-detection exists at the call site.
- **`last_incomplete_phase`** (`FeatureTaskRuntimeLifecycleTelemetry.kt:61`) casts the report to
  `Blocked`; `Completed`/`Decomposed` collapse to empty, and blocked runs without a
  `lastIncompletePhase` are also empty.
- **`blocked_reason`** is sourced from `artifacts["blocked_reason"]` with generic fallbacks
  (`DecompositionManifestRuntimeStateSupport.kt:104`); many blocked-exit paths never populate it,
  leaving ~50% of blocked outcomes empty.
- **`goal_started.status`** is always empty (the started event carries no status).

## Proposed solution

- **`was_edited_by_user`**: detect edits at the **runtime PR-creation path** (compare the generated
  description against the actual PR body after creation/open) and pass `true` when they differ. Do
  not rely solely on the skill layer. If no PR-creation comparison is reachable in the runtime,
  fall back to explicit skill-layer detection in `bill-pr-description` and document it.
- **`last_incomplete_phase`**: for blocked runs, populate from `FeatureTaskRuntimeRunReport.Blocked.lastIncompletePhase`
  and ensure the blocked reporters always set it; for completed runs, emit a sentinel (e.g.
  `"completed"`) or omit the field per a documented rule so it is never ambiguous-null.
- **`blocked_reason`**: thread a meaningful reason through every blocked-exit path; provide a strong
  typed default in `DecompositionManifestRuntimeStateSupport.blockedReasonFrom()` and the runtime
  blocked reporters so a blocked outcome never carries an empty reason. Prefer a categorized prefix
  (e.g. `limit:`, `validation:`, `fix_loop:`, `git:`, `store_lock:`, `needs_human:`) to enable
  bucketing.
- **`goal_started.status`**: set the runtime status at start time (e.g. `"running"`) or document in
  the schema/parity test why it is intentionally absent.

## Acceptance Criteria

1. `was_edited_by_user` is observed `true` on a `skillbill_pr_description_generated` event when the
   PR body differs from the generated description.
2. `last_incomplete_phase` is non-empty for every blocked `feature_task_runtime_finished` outcome,
   and is either omitted or a documented sentinel for completed outcomes.
3. Every blocked `feature_task_runtime_finished` and `goal_subtask_finished` outcome carries a
   non-empty `blocked_reason`.
4. `blocked_reason` values use a documented category prefix so they can be bucketed in analysis.
5. `goal_started.status` is either populated with a runtime status or documented as intentionally
   absent in the schema.

## Non-goals

- Redesigning the blocked-reason taxonomy beyond a category prefix.
- Changing MCP argument schemas beyond adding/clarifying these fields.

## Dependencies

None. Feeds Subtask 6 (contract test asserts these fields are populated).

## Validation strategy

- Unit/integration tests: force each blocked-exit path and assert `blocked_reason` non-empty;
  create a PR with an edited body and assert `was_edited_by_user = true`.
- `(cd runtime-kotlin && ./gradlew check)` green.

## Next path

Subtask 4.
