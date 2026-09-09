# SKILL-235 subtask 1: Commit before review and review immutable revisions

## Scope

Deliver the complete parent behavior in one independently reviewable commit. Add the runtime-owned local create/amend boundary after successful audit and before every review of repaired state. Reduce the review handoff to immutable Git coordinates, spec reference, and configuration; remove eager path/hunk delivery from this goal-child flow. Integrate approval invalidation, finalization, interrupted persistence recovery, and safe legacy-run migration in the same change.

Reuse the existing subtask commit resolver and checkpoint-preservation path. Trace feature-task phase dispatch, review-input preparation, review driver mapping, final launch construction, remediation loops, and finalization before editing. Main currently has extracted run-loop collaborators; file layout may evolve, so resolve current owners by symbols rather than importing SKILL-233's consolidation.

## Acceptance Criteria

1. Before launching review after a successful audit, the runtime creates the active subtask commit if absent or amends its proven owned HEAD commit. The commit captures eligible implementation and audit changes, including additions and deletions. A failed commit or identity persistence prevents review launch; an unchanged retry reuses the existing SHA.
2. Review evaluates an immutable committed revision. The runtime supplies the exact base and target SHA, spec reference, and review configuration. The first pass reviews the subtask commit relative to its parent; it never substitutes origin/main, a merge base, accumulated branch history, or the current dirty worktree. A later pass may compare preserved pre-repair and post-repair revisions even when amendment makes them non-ancestor commits.
3. Review launch prompts contain references and instructions whose size does not grow with the number of changed files or hunks. Remove inline checkpoint path inventories, shared file/hunk catalogs, repeated per-area path lists, and eagerly expanded hunk bundles from this flow. The reviewer reads Git content on demand, with bounded or paged reads; runtime routing and durable coverage accounting may retain private metadata without replaying it into the prompt.
4. Review fixes amend the same subtask commit before the next review pass. Findings and approvals retain the exact evaluated SHA and tree identity. Later build or validation code repairs invalidate prior approval and route the changed revision through the required audit/review gates before finalization. No stale approval authorizes changed code.
5. Finalization preserves one commit per subtask, incorporates the declared boundary-history output, pushes the final revision, and records its exact SHA in the decomposition manifest. Message-only amendments may carry approval when the tree is unchanged. History-only changes have an explicit narrow policy; any other post-review file change re-enters review. Finalization never silently absorbs unreviewed code.
6. Staging and amendment honor proven subtask ownership and the repository exclusion policy. Exclude .feature-specs/ and runtime-private artifacts, preserve unrelated user edits and staged content, and refuse to amend another subtask or an unexpected HEAD. Preserve pre-amend revisions under existing checkpoint refs; prune only after the pushed final SHA is recorded.
7. Resume recovers interruption before commit, after commit but before durable identity persistence, and after amendment but before review settlement. Reconcile against existing ownership trailers/checkpoint identities and tree state, without creating a duplicate subtask commit, launching review for an unrecorded revision, or trusting a stale verdict. Git or database failures emit actionable typed errors and observability records.
8. Existing unfinished runs, including SKILL-233 with accumulated commits and dirty repairs, have an explicit migration path. If the active subtask span and ownership are provable, normalize that span to one commit while preserving earlier subtasks and recovery refs. If proof is unavailable, stop with the exact ambiguity and required operator decision. Never infer ownership from an old broad review base, squash unrelated history, rewrite completed subtasks, or reset shared main.
9. Focused regression coverage proves the commit boundary, amendment and retry behavior, correct SHA selection, exclusion of unrelated changes, invalidation after code repairs, recovery of interrupted persistence, and bounded review launch size on a large change. Update affected runtime contracts, source instructions, and documentation together, and remove obsolete worktree-review plumbing that has no remaining consumer.

## Implementation Boundaries

The audit agent never owns git commit. Runtime commit success and durable identity settlement precede review dispatch. Never mark a review as covering a later changed tree merely because the subtask identity matches. Keep worktree scope support for independent standalone review callers while removing redundant goal-child paths. Prefer deleting obsolete adapters to adding another compatibility layer.

Review must fetch committed files/diffs itself. Moving the same huge bundle from the phase briefing into a review parent prompt does not satisfy this task. Measure the final provider-bound prompt and account for inherited context and rubric framing separately from diff size.

Migration requires ownership proof. Preserve pre-normalization history under checkpoint refs before rewriting a proven active subtask span. Missing evidence is an actionable blocked result, not permission to broaden scope or squash every commit since an old base. Do not operate on the real SKILL-233 branch during automated verification.

## Non-goals

No implementation of SKILL-233 architecture work, merging feature branches into main, arbitrary history rewriting, new review rubric taxonomy, or redesign of goal planning. Keep standalone user-requested worktree review available. Do not introduce a second commit service, evidence database, or generic workflow framework for this change.

## Dependency Notes

No prior subtask. Parent contract: spec.md in this directory. Keep schema, implementation, prompts, and regression tests together so the single resulting commit is usable.

## Validation Strategy

Name the concrete failure before writing each test. Use focused real-Git integration tests for lost writes, duplicate commits, wrong amend targets, stale approvals, accidental staging, and legacy normalization. Assert observable review requests and final recorded/pushed SHAs. A large-diff regression must inspect the final agent request and demonstrate on-demand evidence retrieval; a test of an intermediate short DTO is insufficient. Run contract parity and affected lifecycle tests, then the applicable quality gate. Follow bill-unit-test-value-check for test review.

## Next Path

After all acceptance criteria and gates pass, let runtime finalization push the subtask commit and record its SHA. There is no next implementation subtask.
