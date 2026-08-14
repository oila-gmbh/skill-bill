# SKILL-190 — Collapse feature-task ceremony into one commit per subtask

## Context

The feature-task runtime commits three separate kinds of thing onto a subtask branch: a forward
"audited implementation checkpoint" at the `audit → review` transition, a "remediation checkpoint"
on every backward edge that re-enters a mutating phase, and a final agent-authored `feat(...)`
commit at `commit_push`. Only the last one describes delivered work. The first two are recovery
scaffolding that the runtime needs during the run and nobody needs afterwards.

The result is a branch history where the ceremony outnumbers the deliverables by more than three to
one, and where reading `git log` tells an operator nothing about what the feature actually did.

The repo owner's requirement is exact: **strictly one commit per subtask**. Review, audit, and
verification changes amend that commit rather than appending to it; the commit is created on first
write if it does not exist yet.

### SKILL-16 incident

Measured on `feat/SKILL-16-runtime-production-hardening` in the `skill-bill-v2` repository, after
nine of fifteen subtasks completed:

| Commit kind | Count |
| --- | --- |
| `feat(SKILL-16): <outcome>` — actual deliverables | 9 |
| `chore(SKILL-16): audited implementation checkpoint` | 12 |
| `chore(SKILL-16): remediation checkpoint` | 19 |
| **Total** | **40** |

Thirty-one of forty commits are ceremony. Two subtasks dominate: process-tree termination burned
five consecutive `review_fix` remediations, and the installer subtask burned four. Every one of
those is a separate commit on the permanent branch history.

The checkpoint commits also carry no subtask identity. The full body of a checkpoint commit is a
single subject line:

```
chore(SKILL-16): audited implementation checkpoint on 'feat/SKILL-16-runtime-production-hardening' [phase=audit generation=0]
```

There is no subtask ID in the message, in a trailer, or anywhere else, so git history alone cannot
answer which subtask a commit belongs to. Any amend-or-create decision therefore needs a durable
marker that does not exist today.

### Why this is not a message-template change

The three commit kinds have different owners.

The two checkpoint commits are runtime-owned Kotlin: `FeatureTaskRuntimeCheckpointMessage.build`
(`FeatureTaskRuntimeCheckpointScope.kt:216-222`) formats them and `WorkflowGitOperations.createCommit`
writes them, driven from `FeatureTaskRuntimeRunLoop.kt:1295-1304` and `:803-812`.

The `feat(...)` subtask commit is **not** runtime-owned. It is authored freely by the agent during
`PHASE_COMMIT_PUSH` under a prose directive (`FeatureTaskRuntimePhasePromptDirectives.kt:388-393`),
and `commitExclusionDirective()` at `:251-252` currently instructs the agent in the opposite
direction: *"do not add, amend, unstage, or uncommit them."*

So "amend into the subtask commit" cannot be delivered by editing a prompt. The subtask commit has
to become a runtime-owned operation, with the agent contributing the message and the runtime owning
staging, amend, sha capture, and push.

There is also no amend, squash, or fixup primitive anywhere in the codebase. The only history-moving
operation that exists is the compensating soft reset at `FeatureTaskRuntimeRunLoop.kt:868-876`.

### The trap: amending silently re-breaks SKILL-189

The `review_fix` loop uses checkpoint commits as its **diff base** — the answer to "what changed
since the last review pass". `FeatureTaskRuntimeGoalContinuationRecorder.reconcileRemediationBaseCoherence`
(`:465-519`) locates that base by walking recorded checkpoint identities and testing ancestry:

```kotlin
identity.loopId == REVIEW_FIX_LOOP_ID &&
  gitOperations.isCommitAncestor(repoRoot, identity.commitSha, headSha) …   // :487-491
```

A naive amend orphans the recorded sha. It is no longer an ancestor of HEAD, so
`latestRemediationOnBranch` resolves to `null`; control falls through to `:504`, the stored base
fails its own ancestry test, `target` becomes `headSha` (`:509`), and the base is rewritten to HEAD
under reason `recorded_but_superseded`.

The next review then diffs HEAD against HEAD and sees nothing. That is exactly the "review loop
forgets what it already fixed" failure that SKILL-189 was built to eliminate, reintroduced through a
different door and harder to spot because the reconciliation reports success.

This program therefore cannot simply swap `createCommit` for an amend. It must keep every checkpoint
state reachable while removing it from branch history.

### The codebase already predicted this change

Two governed boundary records name this work as their revisit trigger:

- `runtime-kotlin/agent/decisions.md:97-98` — *"Revisit when: a runtime-owned history rewrite
  (amend/rebase) is introduced, at which point that path must call the same paired base update."*
- `runtime-kotlin/.../featuretask/agent/history.md:111` — restates the same limitation.

SKILL-190 is that trigger. The paired base update those records demand is subtask 4.

## Intended Outcome

A completed feature-task subtask leaves exactly one commit on the branch. That commit is created by
the runtime the first time the subtask writes anything, is amended in place by every subsequent
audit, review, and remediation pass, carries a durable `Skill-Bill-Subtask` trailer, and ends with
the agent-authored outcome message and the manifest-recorded `commit_sha`.

Recovery capability is preserved rather than traded away. Each checkpoint state that used to be a
branch commit becomes a commit reachable from a runtime-private ref outside the branch. The
`review_fix` loop keeps a real diff base across amends, resume keeps its reconstruction points, and
`git log` on the feature branch shows one line per subtask.

## Acceptance Criteria

1. A subtask that completes normally leaves exactly one commit on the feature branch, regardless of
   how many audit, review, remediation, or verification passes ran inside it.
2. The runtime creates the subtask commit on the first checkpoint that has staged content, and
   amends that same commit on every later checkpoint within the subtask; no checkpoint appends a
   second branch commit.
3. Every runtime-written subtask commit carries a `Skill-Bill-Subtask: <issue-key>/<subtask-id>`
   trailer, and the amend-or-create decision is made from durable workflow state with the trailer as
   the recovery fallback when state is unavailable.
4. The first checkpoint writes a provisional subject derived from the manifest subtask `name`, and
   finalisation amends the message to the agent-authored outcome; no commit reaches a pushed state
   still carrying the provisional subject.
5. Every checkpoint state that is no longer a distinct branch commit remains reachable through a
   runtime-private ref under `refs/skill-bill/checkpoints/<issue-key>/<subtask-id>/<sequence>`, is
   protected from garbage collection for the life of the subtask, and never appears in branch
   history or in `git log` without an explicit ref argument.
6. `reconcileRemediationBaseCoherence` resolves the `review_fix` base through checkpoint refs rather
   than branch ancestry, and returns the same base across an amend that it would have returned
   before the amend; the SKILL-189 regression scenario resolves a non-empty diff base.
7. The checkpoint-identity contract is versioned up, replaces `commit_shas_are_unique` with
   amend-aware invariants, records the checkpoint ref and subtask ID, and rejects legacy records
   with a typed error rather than silently accepting them.
8. `commit_sha` recorded into the decomposition manifest is the final post-amend sha of the subtask
   commit, and every existing reader continues to work unchanged, including the planning-cascade
   gate at `GoalPlanningCascadeEligibility.kt:11-14` and the CLI and MCP display surfaces.
9. The subtask commit is written by the runtime, not the agent: `PHASE_COMMIT_PUSH` directives
   instruct the agent to emit a message and an enumerated path set, and the runtime performs
   staging, amend, sha capture, and push.
10. `commitExclusionDirective()` no longer forbids amend outright; it forbids amending commits the
    runtime does not own, and continues to forbid staging any `.feature-specs/` path.
11. Push happens once per subtask at finalisation and never force-pushes on the normal path;
    `--force-with-lease` is used only when a already-pushed subtask is legitimately reopened, and
    that case is recorded.
12. `rollbackRemediationCheckpointCommit` is redefined under amend semantics so a compensating
    rollback restores the prior checkpoint state from its ref instead of soft-resetting past a
    commit that no longer exists as a distinct object.
13. Checkpoint refs for a subtask are pruned only after that subtask's commit is pushed and its
    manifest entry records a non-blank `commit_sha`; an interrupted prune is idempotent.
14. A resume that begins mid-subtask, after process death, reconstructs the amend target from
    durable state or the trailer and does not start a second commit for the same subtask.
15. Every fallback or degradation introduced by this program emits a record per
    `docs/observability-policy.md`, including ref-lookup misses, trailer-based recovery, and
    force-with-lease use.

## Scope

- Add amend and ref-manipulation primitives to the `WorkflowGitOperations` port and its
  `GitWorkflowGitOperations` adapter; no such primitive exists today.
- Version the checkpoint-identity contract to amend-aware invariants with a typed rejection error
  and a schema-to-Kotlin parity check.
- Move subtask-commit ownership from the agent's `commit_push` prose into the runtime, leaving the
  agent responsible for the message and the enumerated path set.
- Introduce the `refs/skill-bill/checkpoints/` namespace, its write path, its lookup path, and its
  pruning lifecycle.
- Rewrite `review_fix` base reconciliation from branch ancestry to ref lookup, and redefine the
  compensating rollback path under amend semantics.
- Update the governed boundary records that name this change as their revisit trigger.

## Constraints

- Forward-only. Do not rewrite existing history in any repository, including the live
  `feat/SKILL-16-runtime-production-hardening` branch in `skill-bill-v2`.
- Do not weaken the recovery guarantees the checkpoint commits currently provide. Every state that
  is reachable today must remain reachable, through a ref rather than through branch history.
- Never stage or commit any `.feature-specs/` path; that exclusion survives this change unchanged.
- No transaction may remain open across a git process invocation.
- The manifest `commit_sha` contract is load-bearing for the planning cascade; it must never be
  recorded as an intermediate pre-amend sha.
- Amends are confined to commits the runtime owns and has not pushed. A commit authored by a human
  operator is never amended, reset, or restaged.
- Schema bumps loud-fail legacy records; no silent compatibility fallback on the normal path.

## Non-Goals

- Rewriting or squashing history produced by earlier bundles.
- Introducing rebase, interactive history editing, or cross-subtask squashing.
- Changing the phase graph, loop identifiers, convergence limits, or which transitions earn a
  checkpoint. This program changes how a checkpoint is stored, not when one is taken.
- Changing the goal-level finalisation commit in `GoalRunner.stageCommitAndPushAll`.
- Migrating the legacy prose-engine decomposition commit path in
  `DecompositionWorkflowContinuation.kt:365-368`.
- Altering `stacked_branches` execution; this bundle targets `same_branch_commit_per_subtask`.

## Diagnostic Evidence

Producers of the commits being collapsed:

- `FeatureTaskRuntimeCheckpointScope.kt:204-222` — identity `toString()` and message builder, plus
  `INTENT_AUDITED_IMPLEMENTATION` and `INTENT_REMEDIATION`.
- `FeatureTaskRuntimeRunLoop.kt:660-700` — `nextTransitionTarget`, the ceremony dispatcher.
- `FeatureTaskRuntimeRunLoop.kt:714-726` — `establishForwardCheckpoint`; the forward checkpoint is a
  hardcoded `audit → review` pair.
- `FeatureTaskRuntimeRunLoop.kt:737-775` — `establishRemediationCheckpoint`, shared by `audit_gap`
  and `review_fix`.
- `FeatureTaskRuntimeRunLoop.kt:850-865` — `recordRemediationBaseIfNeeded`; only `review_fix`
  records a base, `audit_gap` is exempt.

Git seam:

- `GitProcessSupport.kt:74-75` — the sole `ProcessBuilder` for workflow git.
- `GitWorkflowGitOperations.kt:125-128` `createCommit`, `:130-136` `pushBranch`, `:164`
  `headCommitSha`, `:166-172` `resetSoftToCommit`, `:174-189` `isCommitAncestor`.
- `WorkflowGitOperations.kt:33-68`, `:105-171` — port declarations, including `ScopedStagingGitOperations`.

Contract that amending violates:

- `orchestration/contracts/feature-task-runtime-checkpoint-identity-schema.yaml:84-90` —
  `sequence_numbers_are_unique` and `commit_shas_are_unique`, both written on the assumption that
  commits are never rewritten.

Consumer that breaks silently:

- `FeatureTaskRuntimeGoalContinuationRecorder.kt:465-519`, specifically the ancestry filter at
  `:487-491` and the `target = headSha` fallthrough at `:504-513`.

Boundary records naming this change as their trigger:

- `runtime-kotlin/agent/decisions.md:97-98`
- `runtime-kotlin/.../featuretask/agent/history.md:111`

## Open Tension To Resolve During Execution

`CLAUDE.md` states a blanket no-new-tests policy. The same file's runtime-contract rules require, for
every contract version bump, a parity test on the pattern of `PlatformPackSchemaContractVersionTest`.
Subtask 2 bumps a contract version, so the two rules collide.

This spec does not resolve the conflict silently in either direction. Subtask 2 must surface the
choice to the operator and proceed on the recorded decision. The default, absent a decision, is to
follow the contract rule, because a version bump without a parity check is the failure mode the rule
exists to prevent.

## Subtasks

1. Amend and checkpoint-ref git primitives.
2. Amend-aware checkpoint-identity contract.
3. Runtime-owned subtask commit identity and amend ceremony.
4. Remediation base reconciliation and rollback under amend.
5. Runtime-owned finalisation, message handoff, and push.
6. Ref lifecycle, documentation, and integrated verification.

## Validation Strategy

Each subtask validates its own seam, then the module checks. The behaviour that matters is
observable at the git layer, so verification is done against real temporary repositories rather than
by asserting on message strings.

The load-bearing scenarios:

- A subtask driven through one forward checkpoint and three remediation loops leaves one branch
  commit and four reachable checkpoint refs.
- The SKILL-189 scenario: two consecutive `review_fix` passes resolve a non-empty diff base across
  the amend boundary.
- Process death between a stage and an amend resumes onto the same commit rather than creating a
  second.
- A legacy checkpoint-identity record is rejected with the typed error, not silently accepted.
- A pushed-then-reopened subtask takes the `--force-with-lease` path and records the degradation.
- `.feature-specs/` remains unstaged across every path.

Close with `skill-bill validate`, `(cd runtime-kotlin && ./gradlew check)`, and
`scripts/validate_agent_configs`. Note the known pre-existing `:runtime-infra-fs:sourcesJar` failure
on `./gradlew build`; verify with `-x sourcesJar` if that path is used.

## Next Path

```bash
skill-bill goal SKILL-190
```
