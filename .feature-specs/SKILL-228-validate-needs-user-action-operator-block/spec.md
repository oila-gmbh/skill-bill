# SKILL-228: Validate needs_user_action operator block

## Intended Outcome

When validate or build hits a **non-repairable environmental blocker** (missing
credentials, unavailable toolchain, failed install before any check can run),
the feature-task runtime must **stop and tell the operator** — not enter another
repair turn, not keep goal/IDE status on "active / running on validate", and not
bury the reason inside an agent session the operator is not watching.

Repair loops remain correct for fixable validation failures (lint, tests, format).
`failure_disposition: needs_user_action` means the agent cannot proceed without
operator setup; that is a **durable block with a human-readable reason**, not
retry fodder.

Motivation: WE-4364 on Capmo (2026-08-31). Validate agent emitted
`blocked` + `needs_user_action` for missing `GITHUB_REGISTRY_AUTH` / failed
`npm run ci:safe`. Goal status stayed `blocked: 0`, IDE showed *active on
validate*, Cursor showed *Running* on a parent-PID hold. Operator was never
notified.

## Acceptance Criteria

1. When validate or build settles phase output with `status: blocked` or
   `status: failed` and `failure_disposition: needs_user_action`, the runtime
   persists a durable `BLOCKED` phase record (not `PAUSED`, not another repair
   turn) and does **not** relaunch the phase for repair.
2. Validate `blocked` / `failed` output **without** an explicit
   `failure_disposition` may still enter the existing repair loop
   (`RETRYABLE` default for validate); behavior for code findings is unchanged.
3. Goal continuation / subtask status surfaces the block: operator-visible
   `blocked_reason` (from phase `summary` and structured blocking reasons) is
   available via `skill-bill goal status` and goal issue progress — not only in
   agent chat JSON.
4. IdeStatus projects operator-visible blocked state: when the current child
   workflow has a validate/build phase `BLOCKED` with
   `NEEDS_USER_ACTION`, `lifecycle_state` is `blocked` (not `active`) and
   `summary` / pause reason includes the actionable text (e.g. set
   `GITHUB_REGISTRY_AUTH`, run `npm run ci:safe`).
5. Parent-PID / goal-runner hold (`tail --pid`) does not prevent phase settlement:
   validate/build `needs_user_action` output is durably recorded before the
   agent session enters hold, or hold is skipped when the phase is terminal
   blocked.
6. `operatorDecisionPause` (or equivalent IDE projection) surfaces
   `NEEDS_USER_ACTION` on **blocked** quality-gate phases, not only on
   `PAUSED` records.
7. Regression tests cover: (a) validate `blocked` without disposition → repair
   loop preserved; (b) validate `blocked` + `needs_user_action` → single durable
   block, no relaunch; (c) IdeStatus / goal status wire the reason without
   `blockedCount == 0` while blocked.

## Constraints

- Do not weaken repair-loop behavior for ordinary code/test findings.
- Environmental vs repairable classification must use explicit
   `failure_disposition` from the agent when present; do not guess from free
   text alone.
- Preserve `FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION` semantics
   (`retryOnResume: false`).
- IntelliJ and `skill-bill work status` are in scope; VS Code extension parity
   is optional follow-up.
- Touch `terminalBlockedReasonFrom`, `terminalOutputAttempt`,
   `operatorDecisionPause`, `IdeStatusProjector.goalLifecycle`, and goal
   continuation blocked propagation — not a parallel status system.

## Non-Goals

- Fixing consumer-repo setup (Capmo `GITHUB_REGISTRY_AUTH` docs).
- Changing validate repair turn count for code findings.
- Subtask 1 quality-gate skip on resume (separate issue).
- Operator push notifications / email / Slack.
- Auto-installing dependencies or minting credentials for the operator.

## Validation Strategy

- Extend `FeatureTaskRuntimeRunnerTest` (validate repair vs operator block).
- IdeStatus projector / golden fixture tests for `lifecycle_state: blocked` with
  operator reason when child validate is `BLOCKED` + `NEEDS_USER_ACTION`.
- Goal status integration test: WE-4364-shaped fixture → non-zero blocked or
  explicit blocked reason after validate `needs_user_action`.
- `skill-bill validate` and targeted runtime-application / runtime-infra-fs
  module tests for touched files.

## Delivery Plan

1. Runtime: settle `needs_user_action` on validate/build as durable block; no
   repair relaunch; settlement before parent-PID hold.
2. Projection: goal + IdeStatus show blocked lifecycle and operator reason;
   extend `operatorDecisionPause` to blocked quality-gate phases.
