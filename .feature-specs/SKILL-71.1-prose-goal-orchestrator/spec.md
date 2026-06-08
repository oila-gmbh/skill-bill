# SKILL-71.1 - Prose goal orchestrator for decomposed feature goals

Created: 2026-06-08
Status: Draft
Issue key: SKILL-71.1
Parent: follow-up to SKILL-71 (local config and Linear spec mode). Adds the
prose-mode counterpart to the runtime `skill-bill goal` loop so a decomposed
goal driven in-session keeps the same durable workflow records.

## Problem

`bill-feature-goal` is documented only as the trigger surface for the runtime
driver (`skill-bill goal`). The runtime is the **only** orchestrator that loops
decomposed subtasks and drives each one through the goal-continuation contract
that writes durable workflow records (per-subtask child workflow + terminal
outcome with commit SHA).

When the runtime is unusable, there is no prose equivalent that maintains those
records. This happened on SKILL-71: the headless `claude --print` implement-phase
child exited status 1 immediately with no output, forcing a switch to a prose
fallback. The fallback was free-handed in-session — subtasks were implemented and
committed directly, but no subtask workflow was ever opened and no terminal
outcome was recorded. The result was a durable-state divergence:

- git history + the on-disk `decomposition-manifest.yaml` said every subtask was
  complete, but
- `skill-bill goal status SKILL-71` still reported subtask 4 `blocked` and
  subtasks 5-6 `pending`, because the durable workflow store (the authority) was
  never written.

It had to be hand-repaired by populating each subtask's `commit_sha` in the
on-disk manifest projection to trip the existing reconciliation heal
(`WorkflowGoalRunnerManifestStore.shouldRefreshFromCompleteProjection` plus
`GoalRunnerStatusService.shouldPreserveCompletedSubtask`). Hand-repair is exactly
the failure mode the runtime-owned worker model (SKILL-56/58/67) was built to
prevent: "do not treat a nested subagent transcript as proof of progress unless
the child workflow has written durable state."

## Key Finding (grounds the design)

The pieces already exist; only the orchestrator is missing.

- **The prose worker already records durable outcomes.**
  `skills/bill-feature-task-prose/content.md` (the goal-continuation section,
  ~lines 70-81) is a complete contract: when entered as a goal-continuation
  subtask it persists `goal_continuation` artifacts, sets `suppress_pr=true`,
  runs the phases, treats a completed `commit_push` as terminal, and persists the
  subtask outcome (`issue_key`, `subtask_id`, `status`, `commit_sha`,
  `workflow_id`, `blocked_reason`, `last_resumable_step`) into durable workflow
  state via `feature_implement_workflow_update`.

- **The continuation entry seam already supports decomposed prose entry.**
  Verified on 2026-06-08:
  - `WorkflowService` routes `continue` to
    `DecompositionWorkflowContinuation.continueDecomposedParentByIssueKey(issueKey, subtaskId)`
    (`WorkflowService.kt:211`).
  - That call finds the decomposed parent workflow, reads the manifest, advances
    already-complete subtasks, selects the next runnable subtask
    (`DecompositionContinuationSelector` -> Resume / Start / Blocked), and on
    Start opens the subtask workflow at `preplan` with `create_branch` completed
    and persists `subtaskStartArtifacts`, which includes the **top-level
    `goal_continuation` artifact** (`enabled`, `issue_key`, `subtask_id`,
    `suppress_pr: true`, `outcome_authority: "workflow_store"`) that the outcome
    store reads (`DecompositionWorkflowContinuation.kt:207-235`).
  - It is reachable from both the MCP tool `feature_implement_workflow_continue`
    and the CLI `skill-bill workflow continue`.

- **The DB<->disk sync already exists.** The reconciliation heal in
  `WorkflowGoalRunnerManifestStore` / `GoalRunnerStatusService` keeps the durable
  store and the on-disk projection consistent and is read-only-safe for status.

So the worker writes records, the entry seam sets up the goal-continuation
context, and the store reconciles. **The only missing piece is a documented
prose orchestrator** that loops subtasks through that entry seam, verifies each
terminal outcome, advances, stops loudly on block/fail, and opens one parent PR.
No runtime/Kotlin change is required.

## Goals

1. Add a first-class, documented prose goal-orchestration flow to
   `bill-feature-goal` (selected via `mode:prose`, mirroring the
   `bill-feature-task` `mode:` convention) that loops the decomposed subtasks in
   dependency order entirely within the invoking agent session.
2. Drive each subtask through the **existing** continuation contract
   (`feature_implement_workflow_continue` / `skill-bill workflow continue` with
   the parent `issue_key` and the selected `subtask_id`), then re-enter
   `bill-feature-task-prose` with the returned continuation payload so the worker
   writes the durable subtask outcome — no new record-writing path.
3. Keep the inherited SKILL-56/58/67 reliability contract intact: durable
   workflow rows are authoritative over prose transcript; each subtask is a fresh
   continuation entry selected by the manifest; a blocked/failed subtask stops the
   loop loudly and leaves resumable state; `goal status` / `goal watch` stay
   read-only; completion requires a durable terminal outcome before advancing or
   opening the parent PR.
4. On clean completion, open exactly one parent PR for the whole goal (the
   per-subtask runs suppress their own PR), and leave the durable store, the
   on-disk manifest projection, and git history in agreement.

## Non-Goals

- Do **not** root-cause or fix the runtime headless `claude --print`
  implement-phase fast-exit. That is a separate runtime reliability issue; this
  feature only makes the prose fallback record-faithful.
- Do **not** build a second authoritative store or have the orchestrator
  hand-write workflow DB rows or fabricate child workflows. All durable writes go
  through the existing continuation seam and the prose worker's outcome write.
- Do **not** change the runtime `skill-bill goal` loop semantics, the
  continuation seam, or the manifest schema.
- Do **not** add a runtime/Kotlin code path; this is skill-content orchestration
  over seams that already exist. (If scoping later proves a seam gap, that becomes
  a separately-keyed runtime change, not part of this spec.)

## Target User Experience

- A maintainer whose runtime goal loop is unavailable runs
  `bill-feature-goal <issue_key> mode:prose`. After the single decomposition
  confirmation gate, the orchestrator: reads the manifest, and for each runnable
  subtask calls `skill-bill workflow continue <issue_key> --subtask <id>` (or the
  MCP equivalent), re-enters `bill-feature-task-prose` with the returned
  continuation payload, lets the worker run and record its terminal outcome,
  surfaces the transition, and advances.
- When every subtask is complete, the orchestrator opens one parent PR and
  reports the terminal per-subtask summary.
- Afterwards `skill-bill goal status <issue_key>` reports the true state
  (complete/blocked counts) with no hand-repair, identical to a runtime-driven
  goal.
- If a subtask blocks or fails, the orchestrator stops immediately, surfaces the
  blocked subtask, reason, workflow id, and resumable step, and does not continue
  the loop manually.

## Acceptance Criteria

1. `bill-feature-goal` documents a `mode:prose` goal-orchestration flow that loops
   the decomposed subtasks in dependency order and enters each one through the
   existing `feature_implement_workflow_continue` / `skill-bill workflow continue`
   contract (parent `issue_key` + selected `subtask_id`); it never synthesizes a
   second record-writing path.
2. Each completed prose subtask leaves a durable terminal outcome in the workflow
   store (`status`, `commit_sha`, `workflow_id`) such that
   `skill-bill goal status <issue_key>` reflects it with no hand-repair (no
   manual `commit_sha` projection edit, no manual heal trigger).
3. A blocked or failed prose subtask stops the loop loudly — surfacing subtask id,
   reason, workflow id, and resumable step — and leaves resumable durable state;
   the orchestrator does not continue manually.
4. On clean completion the durable store, the on-disk
   `decomposition-manifest.yaml` projection, and git history agree, and exactly
   one parent PR is opened (per-subtask runs keep `suppress_pr=true`).
5. The flow is agent-agnostic: it is documented in governed skill content that
   installs for all supported agents, with only entry/launch mechanics differing
   per agent; no agent-specific orchestration is hardcoded.
6. The relationship to the runtime path is explicit: `mode:runtime` (default)
   continues to use `skill-bill goal`; `mode:prose` uses this in-session loop;
   both honor the same one-confirmation gate and the same durable-record
   authority.
7. Maintainer validation passes: `skill-bill validate`, `npx --yes agnix --strict .`,
   `scripts/validate_agent_configs` (and `(cd runtime-kotlin && ./gradlew check)`
   if any runtime file is touched — expected none).

## Design Notes

- **Mode convention.** Reuse the existing `mode:prose` / `mode:runtime` argument
  convention already used by `bill-feature-task` so the two goal modes are
  discoverable and symmetric. `mode:runtime` stays the default.
- **One orchestrator, not two implementations.** The prose loop must select the
  next subtask the same way the runtime does — by reading the manifest /
  continuation selection — rather than re-deriving ordering. Prefer letting
  `workflow continue` perform the selection (it already advances completed
  subtasks and returns the selected/blocked subtask), so the prose loop is a thin
  driver over the runtime's own selection logic and cannot drift from it.
- **Terminal-outcome verification.** After each subtask the orchestrator must
  confirm the durable terminal outcome (e.g. via `feature_implement_workflow_get`
  / `skill-bill workflow show` or `skill-bill goal status`) before advancing —
  the prose transcript alone is not proof.
- **Parent PR.** The per-subtask runs suppress their PR; the orchestrator opens
  the single parent PR at the end via the standard `bill-pr-description` / `gh`
  path used elsewhere. Confirm whether a `skill-bill` parent-PR helper should be
  reused for parity with the runtime's `GoalPullRequestPort`.
- **Blocked/failed handling** mirrors the runtime contract: stop, summarize, do
  not loop manually; the durable state remains resumable (by runtime or by a
  later prose continuation).
- **Documentation placement.** The orchestration lives in
  `bill-feature-goal/content.md`; `bill-feature-task-prose` already documents the
  worker side and needs at most a cross-reference, not new behavior.

## Validation Strategy

- Author-level: `skill-bill validate`, `npx --yes agnix --strict .`,
  `scripts/validate_agent_configs` to confirm the governed skill content is valid
  and installs for all agents.
- Behavioral walkthrough: on a small decomposed fixture goal, run
  `mode:prose` and assert that after completion `skill-bill goal status`
  reports the true complete counts with no manual projection edit, and that a
  deliberately-blocked subtask stops the loop with the durable blocked record
  intact.
- Regression: `mode:runtime` (default) behavior is unchanged.

## Open Questions

- Does the prose orchestrator open the parent PR via `bill-pr-description` + `gh`,
  or should a `skill-bill` parent-PR command be reused for exact parity with the
  runtime's `GoalPullRequestPort`? Default to `bill-pr-description` + `gh` unless a
  reusable command already exists.
- Should `mode:prose` be selectable mid-goal as a fallback when `mode:runtime`
  blocks on infrastructure (e.g. a child-agent spawn failure), or only chosen up
  front at the confirmation gate? Default to up-front selection for this spec;
  mid-goal handoff can be a later increment.
