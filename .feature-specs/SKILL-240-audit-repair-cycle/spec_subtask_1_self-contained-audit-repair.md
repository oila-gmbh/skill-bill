# Subtask 1: Durable session ownership, recovery and operator control

## Scope

Close the complete diagnosis, provider continuation and stage-state boundary in the existing implementation. An active cycle must retain its producer identity while a new worker acquires a current fence. Each stage acknowledgement must represent a durable transition, and replay must preserve both evidence and later operator decisions.

Own parent ACs 1, 2 and 6, the session and state portions of AC 5, and the corresponding compatibility and test obligations. Preserve final eligibility checks while subtask 2 verifies the Git boundary.

## Acceptance Criteria

1. A fresh audit launch durably records its provider continuation identity before diagnosis or repair authorization. Bootstrap validates the exact launch and active worker fence without requiring an already recorded provider ID. Identical identity recording is idempotent; changed identity is rejected.
2. Production SQLite stage requests, including replay, validate workflow, owning cycle, execution, session and live lease under one transaction snapshot. No inherited no-op can bypass those checks. Duplicate requests return the original acknowledgement; mismatched evidence or stale callers fail through typed errors.
3. Each supported provider uses its actual structured session-start event and correct noninteractive continuation command. Identity persistence failure cleans up the owned child and records a failure. Unsupported continuation pauses through the existing operator path and never silently starts an unrelated repair session.
4. Recovery selects and rebinds the owning cycle before briefing composition, preserves original producer attribution, and restores versioned structured evidence. The resumed prompt uses the current lease and retains diagnosis, repair outcomes, checkpoints, pending validation and final assessment.
5. Every interrupted or paused stage has an explicit recovery outcome. Recovery reconciles current content and existing checkpoint intent before mutation, does not reapply a recorded completed repair, and carries satisfied but unsettled evidence forward without another repair session.
6. One fenced SQLite transaction owns cycle revision, coupled workflow pause, progress accounting and retry grant consumption. Crash or rollback leaves either all changes or none. Each new pause permits a fresh retry_fix or abandon_subtask; accept_and_advance remains rejected for unmet ACs.
7. Repair rounds use runtime-verified content evidence and audited unresolved criteria through the existing progress policy. Unavailable fingerprints use the recorded AC-reduction fallback from main. Revision counts, replay and file churn cannot reset or fabricate round accounting.
8. Focused regressions exercise fresh production binding, stale replay, callback failure, same-session continuation, interrupted partial repair, every paused-stage resume, and atomic retry consumption through the real SQLite adapter. Required schema, codec and migration checks cover any changed durable fields and typed compatibility recovery.
9. The changed source is compilable and the subtask's configured quality gate passes. Validation claims identify the commands actually run. A compile-only build cannot be reported as test or full-quality proof.

## Implementation Baseline

The current tree includes provider-owned structured session decoding, noninteractive Codex resume, initial identity bootstrap, stage ownership transactions, structured recovery prompts, retry grant consumption and repair-round accounting. The SQLite lease check now uses the workflow's current audit phase independently of the phase where the worker first acquired its lease.

Use investigation.md sections AC-002, AC-005 and AC-006 to understand the original failure modes. Use repair-validation.md and the current code to determine which requirements already have evidence. Neither document closes this subtask's census by assertion.

## Implementation Plan

1. Trace a fresh launch through prompt composition, provider identity persistence and diagnosis. Prove the current worker fence and authoritative AC census at the production stage entry point.
2. Check duplicate and stale requests inside the mutation transaction. Preserve the original acknowledgement and producer identity, and reject changed evidence or provider identity through typed errors.
3. Trace each provider's event decoder and continuation command. Check chunking, misleading nested payloads, failed identity persistence, cancellation and owned-child cleanup. Unsupported continuation must have an actionable durable outcome.
4. Build a recovery matrix for diagnosis, authorized repair, partial repair, checkpoint pending, final audit, pause and satisfied-but-unsettled state. Preserve completed repair receipts, pending validation and current lease metadata without repeating mutations.
5. Verify atomic revision, progress, pause and retry grant updates. Prove that file churn, replay and stage revision numbers cannot manufacture AC progress or another repair grant.
6. Repair only demonstrated gaps and retain meaningful existing tests. Record any unresolved Git-specific obligation for subtask 2 without claiming the parent complete.

## Non-Goals

- No new provider family, unrelated process-runner redesign, platform command change or arbitrary retry cap.
- No replacement of Git final eligibility, review, build or validation.
- No status or telemetry redesign owned by subtask 3.
- No lowering acceptance criteria or inheriting acceptance from deleted workflows.

## Dependency Notes

No preceding feature subtask. The regenerated durable manifest and fresh shared planning are execution prerequisites. Inspect the existing AC-reduction behavior directly; do not blindly cherry-pick a historical main commit or recreate a fix already present in this branch.

## Validation Strategy

Exercise production binding and replay with live, expired and replaced worker fences. Inject failure at the state/grant boundary, reopen storage and prove all-or-none persistence. Exercise structured provider identity and process cleanup at their actual interfaces.

Verify each recovery origin with persisted evidence, including a satisfied cycle awaiting final settlement. Verify recurring gaps preserve repair IDs and stop for operator action. Add tests only for an uncovered realistic failure, then run the configured gate in its authorized context.

## Mandates and Overrides

- Treat the existing working-tree changes as the implementation baseline. Preserve correct behavior and close only demonstrated acceptance gaps. Do not infer acceptance from existing code, repair prose or a passing compile.
- Preserve source and generated-output boundaries, manifest-driven routing, typed contract failures, cancellation and diagnostics required by AGENTS.md.
- Audit owns diagnosis, authorized repair and final repository assessment in one attributable cycle. Stop after a stage rejection or paused acknowledgement. Do not return to an unrelated implement session to repair audit gaps.
- Keep builds and test execution with their authorized validation phase. A goal build-only gate runs only its declared build commands. Existing external validation evidence must name its tested source state and cannot establish later untested changes.
- Do not install, reset workflow state, change acceptance criteria, or edit SQLite from a goal child. The runtime owns subtask commits and pushes under same_branch_commit_per_subtask.

## Next Path

After this subtask passes its audit and review and the runtime records its commit, continue to subtask 2. Parent acceptance remains open.
