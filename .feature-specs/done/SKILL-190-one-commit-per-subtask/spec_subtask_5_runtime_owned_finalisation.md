# SKILL-190 Subtask 5 — Runtime-owned finalisation, message handoff, and push

## Intended Outcome

The `feat(...)` subtask commit stops being an agent-authored git operation and becomes a runtime
operation that amends the existing subtask commit with the agent's outcome message. The agent keeps
what it is good at — describing what it did and enumerating what it touched — and stops driving
`git commit` directly.

## Scope

- Change the `PHASE_COMMIT_PUSH` task directive
  (`FeatureTaskRuntimePhasePromptDirectives.kt:388-393`) so the agent emits an outcome message and an
  enumerated path set in `commit_push_result`, and does not run `git commit` or `git push` itself.
- Amend `commitExclusionDirective()` (`:238-254`). Its current blanket prohibition at `:251-252` —
  "do not add, amend, unstage, or uncommit them" — becomes scoped: never amend a commit the runtime
  does not own, and never stage any `../..` path. The runtime's own subtask commit is
  amendable by the runtime.
- Perform staging, amend, sha capture, and push in the runtime, reusing the scoped staging path
  (`GitScopedStagingOperations.kt:47`) rather than `git add -A`.
- Amend the subtask commit's message from provisional to the agent-authored outcome, preserving the
  `Skill-Bill-Subtask` trailer and the checkpoint metadata body.
- Create the subtask commit at this point if no checkpoint ever fired — a subtask that produced work
  without hitting a checkpoint must still end with exactly one commit.
- Push once, at finalisation. The normal path never force-pushes, because the amends only touched an
  unpushed HEAD. A legitimately reopened, already-pushed subtask uses `--force-with-lease` and
  records the degradation.
- Record the final post-amend sha as the manifest `commit_sha` through the existing extraction path
  (`FeatureTaskRuntimeGoalContinuationOutcomeSupport.kt:139-159`).

## Acceptance Criteria

1. The runtime performs the finalisation staging, amend, sha capture, and push; the agent runs no
   git command in `commit_push`.
2. The agent supplies the outcome message and an enumerated path set through `commit_push_result`,
   and a missing or empty message fails loudly rather than committing a provisional subject.
3. Finalisation amends the existing subtask commit rather than creating a new one, and creates the
   commit when no checkpoint fired during the subtask.
4. The final commit carries the agent-authored subject, the `Skill-Bill-Subtask` trailer, and the
   checkpoint metadata body.
5. `commitExclusionDirective()` forbids amending commits the runtime does not own instead of
   forbidding amend outright, and still forbids staging any `../..` path.
6. Staging uses the scoped enumerated-path operation; no path in this ceremony runs `git add -A` or
   `git add .`.
7. Push happens once per subtask at finalisation and does not force-push on the normal path.
8. A reopened, already-pushed subtask pushes with `--force-with-lease`, aborts if the remote moved
   under it, and emits an observability record.
9. The manifest `commit_sha` is the final post-amend sha, never an intermediate one.
10. Existing `commit_sha` readers work unchanged, including the planning-cascade gate at
    `GoalPlanningCascadeEligibility.kt:11-14`, `GoalRunnerStatusService`, `GoalRunnerWorkflowStores`,
    `GoalRunner`, `DecompositionWorkflowContinuation`, and the CLI and MCP display surfaces.
11. The divergence check between `commit_push_result.commit_sha` and
    `goal_continuation_outcome.commit_sha` in `DecompositionManifestRuntimeStateSupport.kt:153-163`
    holds under the new flow.
12. A human-authored commit is never amended, reset, or restaged by any path in this subtask.

## Non-Goals

- The goal-level `stageCommitAndPushAll` commit in `GoalRunner.kt:1505`.
- PR creation and the `pr` phase.
- Changing `commit_push_result`'s reserved status or its position in the workflow contract
  (`orchestration/workflow-contract/PLAYBOOK.md:295`, `:323`, `:334`) beyond the field content.
- Stacked-branch execution.

## Dependency Notes

Depends on subtask 3 for the subtask commit and its trailer, and on subtask 4 because finalisation
must not run while reconciliation still reads branch ancestry.

This subtask changes the agent-facing contract for `commit_push`. The prose directive and the
runtime behaviour must land together; an agent told to stop committing while the runtime does not yet
commit would end a subtask with uncommitted work.

## Validation Strategy

- A full subtask with checkpoints ends with one commit carrying the agent's message.
- A subtask with no checkpoint at all ends with one commit.
- An empty or missing agent message fails loudly and does not push a provisional subject.
- The pushed sha equals the manifest `commit_sha`.
- A reopened pushed subtask takes the force-with-lease path, and aborts when the remote moved.
- `../..` stays unstaged, including when it is dirty.
- A human-authored HEAD commit is refused as an amend target.

Then the `runtime-application`, `runtime-cli`, and `runtime-mcp` module checks.

## Next Path

Subtask 6 prunes the checkpoint refs, updates documentation, and runs integrated verification.
