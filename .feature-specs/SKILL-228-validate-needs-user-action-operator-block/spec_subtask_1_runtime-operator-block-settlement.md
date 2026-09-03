# SKILL-228 · Subtask 1: Runtime operator-block settlement

## Scope

Route validate/build `failure_disposition: needs_user_action` through terminal
operator-block settlement instead of the repair loop. Preserve disposition-less
validate repair behavior for ordinary code findings.

Touch `terminalBlockedReasonFrom`, `terminalOutputAttempt`, validate/build gate
coordinators, `FeatureTaskRuntimeRunLoopAttemptSettlementExtras`, and goal
continuation blocked-reason propagation.

## Acceptance Criteria

1. Validate or build phase output with `status: blocked` or `status: failed` and
   `failure_disposition: needs_user_action` settles once as a durable `BLOCKED`
   phase record; no repair-turn increment and no gate relaunch.
2. Validate `blocked` / `failed` without `failure_disposition` still enters the
   existing repair loop (`RETRYABLE` default).
3. Phase settlement and ledger write complete before any parent-PID hold so the
   operator block is durable even when the agent session is backgrounded.
4. Goal continuation / stop reports expose non-empty `blocked_reason` from phase
   `summary` and structured blocking reasons for `skill-bill goal status`.
5. `FeatureTaskRuntimeRunnerTest` covers repair-loop preservation vs single
   durable operator block for `needs_user_action`.

## Constraints

- Do not guess environmental vs repairable classification from free text; honor
  explicit `failure_disposition` only.
- Preserve `FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION` semantics
  (`retryOnResume: false`).

## Validation Strategy

- `FeatureTaskRuntimeRunnerTest` regression cases for validate repair vs operator
  block.
- Targeted runtime-application module tests for touched settlement and gate files.
