# SKILL-235: Commit-based subtask review

## Outcome

After audit clears a subtask, the runtime commits its current work locally. Review receives immutable Git revisions and reads the code itself. Repairs amend the same subtask commit; finalization pushes it after the required gates pass.

## Preparation

Mode: single_spec. Spec source: local. One executable subtask delivers this lifecycle change end to end. Commit timing, review input, retry state, and recovery must ship together to avoid an intermediate workflow that reviews stale or uncommitted state.

## Problem and Evidence

SKILL-233 reached review after audit but encountered a provider input-size rejection. Its persisted briefing text contained 171,366 characters, including 841 owned paths and a 512-entry file/hunk index (511 files plus an omission entry). The referenced shared evidence covered 841 files and 3,953 hunks. The review runner also expanded evidence into area bundles. The compact goal_subtask_review_input record itself was only 263 characters. Database serialization size is not the same as the actual agent request size; validation must measure the final launch prompt.

The current workflow postpones final commit/push until after review and quality gates. This requires review to account for dirty worktrees and untracked files. Merely committing earlier will not solve oversized prompts if the driver still eagerly serializes catalogs and bundles. This feature removes both sources of complexity.

## Scope and Design

Use the existing runtime commit ownership and pre-amend checkpoint machinery at the audit-to-review boundary. The runtime owns Git writes; the audit agent reports its outcome. Audit clearance commits the complete eligible subtask state even when audit itself made no edits. An unsuccessful audit does not launch review. Existing repair checkpoint behavior may preserve incomplete work for recovery.

Persist the base and target revisions before dispatch. The normal first review uses the active subtask commit's parent and target SHA. Resume must verify that these represent this subtask. Git supplies file discovery and diff bodies on demand; reviewers may read surrounding committed code as needed. Stored path lists, fingerprints, and coverage evidence remain private runtime data where required.

Review repair changes amend the owned commit and trigger review of the new revision under the existing review-pass policy. A preserved previous SHA may be used for repair-delta review; an ancestry requirement must not reject legitimate comparisons between amended siblings. The cumulative acceptance decision must still cover the complete subtask change.

Build/validation repairs can occur after review. Treat code changes as a new revision requiring the applicable audit/review and quality proof. Boundary history remains a declared finalization output: explicitly limit any exemption to those expected documentation paths and verify that no source change is hidden with them. Persist both reviewed and finalized identities when they differ.

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

## Constraints

Retain same_branch_commit_per_subtask. Reuse runtime-owned staging, ownership trailers, checkpoint refs, and recovery ports. The normal commit boundary is local; push stays in finalization. No agent-issued commit or push command for a goal child. Runtime schemas remain authoritative: change schema/version, Kotlin parsing, parity tests, and legacy recovery together when the wire shape changes. Every fallback, refusal, and migration decision is observable.

This feature changes the earlier staging/finalization behavior where needed to guarantee committed review and prevent unreviewed post-review code from being pushed. Document that contract change explicitly in the governed source and repository guidance.

## Non-goals

No implementation of SKILL-233 architecture work, merging feature branches into main, arbitrary history rewriting, new review rubric taxonomy, or redesign of goal planning. Keep standalone user-requested worktree review available. Do not introduce a second commit service, evidence database, or generic workflow framework for this change.

## Dependency Notes

Base implementation on main. Do not depend on or merge unfinished SKILL-233 work. Use its persisted state as a recovery fixture, never modify the live goal while preparing or testing this spec. Existing runtime commit preservation and review driver are implementation dependencies, not separate deliverables.

## Validation Strategy

Use real temporary Git repositories for lifecycle tests and failure injection at the Git-write/database-record boundary. Cover initial commit, amend, clean retry, dirty retry, deletion, untracked creation, foreign staged content, unexpected HEAD, sibling revision comparison, and preservation/pruning of recovery refs. Test post-review source repair versus message-only and declared history-only finalization. Use a large synthetic change to assert final launch text stays bounded and content is retrieved on demand without losing coverage. Exercise migration on a recorded-shape fixture containing earlier completed subtasks and an ambiguous legacy span. Run relevant tests and the repository's routed quality gate; report pre-existing failures separately.

## Next Path

Execute spec_subtask_1_commit-based-review.md through skill-bill goal SKILL-235. Preparation alone does not start the goal.
