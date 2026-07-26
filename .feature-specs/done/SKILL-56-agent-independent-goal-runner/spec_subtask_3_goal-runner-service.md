# SKILL-56 Subtask 3 - Goal-Runner Domain Service (manifest walk, fresh process per subtask)

Parent spec: [.feature-specs/SKILL-56-agent-independent-goal-runner/spec.md](./spec.md)
Issue key: SKILL-56
Subtask order: 3 of 4
Depends on: subtask 1, subtask 2
Branch model: same-branch, commit per subtask

## Purpose

Implement the **outer loop** itself: a domain service that, given a decomposed
parent `issue_key`, walks the decomposition manifest in dependency order,
launches **one fresh process per subtask** via the `AgentRunLauncher` (subtask 2)
using the non-interactive continuation contract (subtask 1), advances the
manifest after each subtask, **stops-and-reports** on the first failed/blocked
subtask, and opens a **single PR** for the whole goal when all subtasks complete.
This is where the clean-context guarantee is realized end-to-end: the service
holds only manifest state and compact per-subtask outcomes; all heavy work lives
in the fresh child processes.

The service is pure domain logic over ports (launcher, workflow/manifest, VCS),
testable without a real agent.

## Scope

In scope:

- A **`GoalRunner` domain service** in `runtime-kotlin/runtime-domain` with a
  loop that, until the manifest is drained:
  1. selects the next runnable subtask (pending + dependencies complete) using
     the existing manifest/`workflow_continue` resolution;
  2. marks it `in_progress` and updates `current_subtask_intent`;
  3. launches a fresh headless run for it via `AgentRunLauncher`;
  4. when the launch returns, reads the subtask's **authoritative outcome from
     the workflow store** (subtask 1 records it there) and reconciles it with the
     launch-level outcome — e.g. a clean store `complete` is authoritative; a
     process that timed out / spawn-failed / exited without the store reaching a
     terminal `complete` is treated as `blocked` (needs attention); then updates
     the manifest (`status`, `commit_sha`, `workflow_id`, `last_resumable_step`);
  5. on `complete` → continue; on `failed`/`blocked`/timeout/no-terminal-outcome
     → **stop**, leave `current_subtask_intent` on that subtask, record
     `blocked_reason`, and report.
- **Single-PR finalization**: when all subtasks reach `complete`, open exactly
  one PR for the whole goal (the per-subtask runs already suppressed their own
  PRs via subtask 1). Reuse existing PR-creation plumbing
  (`bill-pr-description` / the runtime's VCS port) rather than inventing new PR
  logic; the PR body summarizes the goal and its subtask commits.
- **Resumability**: re-invoking the service on a partially-complete manifest
  resumes from `current_subtask_intent` — completed subtasks are skipped, the
  blocked/next subtask is attempted; no subtask runs twice due to resume.
- **Failure/timeout policy** lives here (the parent decision is "stop and
  report"): no auto-retry by default; a single clearly-bounded behavior, with
  any timeout surfaced as a blocked-style stop.
- A structured **goal-run status projection** (counts of complete/pending/blocked,
  current subtask + step, active agent) that subtask 4's `status` command and any
  progress output read from. The manifest remains the single source of truth;
  this is a read projection over it.

Out of scope:

- The CLI command surface and human-readable progress rendering (subtask 4).
- The `bill-goal` skill front and the decomposition gate (subtask 4).
- Launcher mechanics (subtask 2) and the continuation contract (subtask 1).
- Any background/persistent execution; the service runs to completion or stop
  within a single foreground invocation.

## Acceptance Criteria

1. `GoalRunner` walks a decomposition manifest in dependency order, launching one
   fresh process per subtask via `AgentRunLauncher`, reads each subtask's
   authoritative outcome from the **workflow store** (reconciled with the
   launch-level outcome), and advances the manifest (`status`, `commit_sha`,
   `workflow_id`, `current_subtask_intent`) after each subtask.
2. On the first `failed`/`blocked`/timeout/no-terminal-store-outcome, the service
   stops, leaves `current_subtask_intent` on that subtask with a recorded
   `blocked_reason`, and returns a structured stop report; it does **not** run
   later subtasks.
3. When all subtasks are `complete`, the service opens **exactly one** PR for the
   whole goal, reusing existing PR plumbing; no per-subtask PRs are created.
4. Re-invoking on a partially-complete manifest resumes from
   `current_subtask_intent`: completed subtasks are skipped and no subtask is run
   twice; the blocked/next subtask is attempted.
5. The service is pure domain logic over ports and is fully unit-tested with a
   fake launcher and in-memory manifest: happy path to single PR, mid-goal stop
   on a forced failure, and resume-after-stop, all asserted.
6. A read-only status projection over the manifest reports
   complete/pending/blocked counts, the current subtask + its step, and the
   active agent, for consumption by subtask 4.

## Validation

```bash
(cd runtime-kotlin && ./gradlew :runtime-domain:test)
(cd runtime-kotlin && ./gradlew check)

# Domain tests use a fake AgentRunLauncher and an in-memory/temp manifest:
#  - N trivial subtasks all "complete" → single PR opened once, manifest drained
#  - subtask k returns "blocked" → loop stops at k, pointer on k, no k+1 run
#  - resume after stop → starts at k, skips 1..k-1, never reruns a completed one
```

## Implementation Notes

- The manifest is the single source of truth and the only durable state. The
  service must update it transactionally per subtask so an interrupted run
  resumes cleanly (consistent with SKILL-51 workflow-state semantics). The YAML
  manifest should remain a projection of the workflow store where both exist —
  do not introduce a second writer with divergent state.
- Keep the runner's own footprint tiny: hold the manifest view + the latest
  compact per-subtask outcome, not child stdout transcripts. This is what makes
  context flat regardless of subtask count — the design's core advantage over a
  single-session loop.
- "Stop and report" is deliberate (parent non-goal: no unattended auto-retry
  that could compound silent mistakes). If a future need for bounded retry
  appears, add it as explicit policy, not as a default.
- Single-PR finalization depends on per-subtask runs having suppressed their own
  PRs (subtask 1, AC2). Assert that invariant rather than assuming it.
- Do not reach for parallelism: subtasks have dependencies and share a branch;
  the loop is intentionally sequential.
