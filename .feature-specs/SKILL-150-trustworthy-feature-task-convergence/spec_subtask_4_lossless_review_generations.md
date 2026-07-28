# SKILL-150 Subtask 4: Lossless Review Generations

## Scope

Replace destructive review invalidation with generation-aware review state. Repository changes may make prior findings stale, but no unresolved Blocker may disappear without durable re-verification and a governed terminal disposition.

## Acceptance Criteria

1. Each review pass belongs to an immutable generation identified by workflow, review base, reviewed delta digest, pass number, and repository checkpoint.
2. Findings have stable durable identities and retain severity, category, bounded location and summary, source generation, and current disposition across later generations.
3. Reopening a paused or capped review for a changed delta creates a new generation without clearing prior review results or deleting the unresolved-findings ledger.
4. Every unresolved prior Blocker is carried into the next review generation for explicit `resolved`, `still_present`, `superseded`, `accepted`, or other governed terminal disposition with repository evidence.
5. Review approval requires a durable query proving zero unresolved Blockers across all non-superseded generations, not merely an empty findings array in the latest pass.
6. Runtime-owned manifest status changes and checkpoint metadata are classified separately from semantic implementation changes so bookkeeping alone does not discard or restart a valid remediation review.
7. When semantic code changes invalidate prior evidence, the next review receives both the changed delta and the carried findings that require re-verification.
8. Remediation review verifies every carried Blocker disposition and inspects the remediation delta for introduced Blockers before advancing.
9. Review caps, pauses, retry-fix decisions, accept-and-advance decisions, abandon decisions, and crash resume preserve generation and finding history idempotently.
10. A regression based on the SKILL-134 producer-attribution finding proves that a Blocker found in one generation remains advancement-blocking after two unrelated delta changes until code or an explicit operator disposition resolves it.
11. Review status, watch, and telemetry expose current generation, pass, carried Blocker count, new Blocker count, and terminal dispositions without exposing full raw review output.

## Non-Goals

- Automatically accepting findings from a stale checkpoint.
- Re-running an unchanged review indefinitely.
- Making review findings authoritative over explicit operator acceptance.
- Storing complete review prose or complete source diffs in normalized tables.

## Dependency Notes

Depends on Subtask 1 for durable generation and finding records. It may proceed independently of audit behavior after that foundation exists.

## Validation Strategy

- Seed two passes with unresolved Blockers, change only manifest bookkeeping, and assert the findings remain carried without a fresh full review.
- Change semantic code, open a new generation, and verify prior Blockers require evidence-backed dispositions.
- Reproduce pause, cap, operator retry, accept, abandon, crash, and resume paths.
- Assert approval fails when the latest findings array is empty but an earlier durable Blocker is unresolved.
- Verify existing review state and `unaddressed_findings` data migrate without loss or duplication.

## Next Path

Continue with Subtask 5 to prevent foreign worktree changes from entering workflow checkpoints and review deltas.

