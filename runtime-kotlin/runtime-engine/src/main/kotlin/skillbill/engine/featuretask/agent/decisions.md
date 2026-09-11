# featuretask runtime boundary decisions

## [2026-09-11] Active subtask owns every dirty path
Context: commit_push blocked needs_human when any dirty file was missing from the frozen ownership inventory, including required `agent/history.md` writes.
Decision: The active subtask owns every non-runtime-private dirty path. Checkpoints and commit_push stage them. Review identity does not treat extra dirt as foreign.
Reason: The inventory was a branch-setup snapshot, so later legal writes looked like someone else's work and stalled the goal. Operator intent is that whatever is dirty on this subtask is this subtask's.
Revisit when: a run must share a worktree with a second in-flight subtask that must keep its own uncommitted files out of this commit.

## [2026-09-10] write_history and commit_push never reopen earlier phases
Context: After the bounded review_fix round, implement_fix leaves owned dirty files. commit_push treated that as a stale review, wiped audit and later phases, and the drive loop bounced back to audit.
Decision: `write_history` and `commit_push` stay forward-only. Owned implement/implement_fix paths plus declared boundary-history may be finalised. Foreign dirty content blocks needs_human. Matching subtask trailer on HEAD keeps review identity across tree drift.
Reason: The shipped topology already allows only `audit_gap` and `review_fix`. Replaying audit/review from finalisation discarded a completed review-fix round and could not converge while the same owned files stayed dirty.
Alternatives considered: Keep re-entering audit for any non-history dirty path (rejected: infinite bounce after a legal implement_fix). Stage history-only and leave implement_fix files dirty (rejected: the bounded fix round would never land).
Revisit when: a later phase needs a declared backward edge, which would be a topology change rather than a finalisation side path.
Superseded by: Active subtask owns every dirty path (2026-09-11)
