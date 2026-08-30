# SKILL-226 · Subtask 2 — Runtime implement repair fan-out

## Scope

Execute a validated repair partition plan inside the feature-task implement /
audit-gap implement path: runtime-owned workers, merge, one settlement, resume
safety, and observability — without allowing the phase agent to spawn
delegated subagents.

Deliver:

- When the active plan has N>1 file-disjoint partitions, launch N implement (or
  audit-gap implement) workers with partition-scoped briefings (only that
  partition's refs + paths).
- Wait for completion (or durable block) of all partitions; merge worktree
  outcomes under the parent attempt; emit **one** implement settlement /
  implementation receipt.
- Single-partition plans keep today's single-agent launch path.
- Crash / pause / process-retry: partial partition progress reconcilable via
  existing mutating-phase idempotency (converge to target, no blind re-apply);
  parent lease and attempt accounting remain coherent.
- Prompt directives for implement, audit-gap implement, build, and validate
  still forbid agent-spawned delegated subagents; only this runtime path may
  parallelize.
- Goal / feature-task status (or observability events) expose partition count,
  per-partition status, and merge outcome.
- Wire audit-gap re-entry so a gaps_found audit that can produce a partition
  plan feeds this path on the next implement attempt.

Isolation strategy: start with proven file-disjoint edits on the shared
worktree if that is sufficient; only introduce heavier isolation (worktrees /
clones) if required to meet correctness — document the choice in the boundary
decision log if non-obvious.

## Acceptance Criteria

1. N>1 disjoint partitions cause N runtime-launched workers and exactly one
   parent implement settlement / receipt when all succeed.
2. Single-partition (or collapsed) plans behave like today's single implement
   agent session.
3. Overlapping partitions cannot execute concurrently; invalid plans fail closed
   before launch.
4. Phase prompts still contain the delegated-subagent ban for implement,
   audit-gap implement, build, and validate.
5. Interrupted multi-partition runs resume without duplicate apply and without
   orphaning the parent attempt's lease / attempt_count semantics.
6. Status/observability answers "implement is running partition i/N" (or
   equivalent) for live multi-partition work.
7. Audit-gap implement consumes a partition plan when audit produced usable
   path-bearing gaps; otherwise falls back to single-partition remediation.
8. Focused runner / integration tests cover multi-partition success, collapse
   to single, and at least one resume-after-interrupt case.

## Non-Goals

- Agent-managed Task fan-out or "analyze then spawn kids" prompts.
- Redesign of parallel review lanes.
- Parallel validate gate orchestration as a separate product (may note a
  follow-up; do not block this subtask on it).
- Changing SKILL-221 suppression policy.

## Dependency Notes

- Depends on subtask 1 (partition contract + planner must exist and validate).

## Validation Strategy

- Runner / integration tests with fake agent launches for N partitions → one
  settlement.
- Prompt composer tests for the subagent ban.
- Resume/idempotency test for partial partition completion.
- Targeted Gradle tests; `skill-bill validate`.

## Next Path

`skill-bill goal SKILL-226` (after both subtasks are prepared).
