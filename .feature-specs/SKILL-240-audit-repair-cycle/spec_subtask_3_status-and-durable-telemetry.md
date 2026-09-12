# Subtask 3: Consistent audit status and durable lifecycle telemetry

## Scope

Close the observable state boundary and verify the assembled audit-repair behavior. Feature-task and goal status must describe one real database state. Lifecycle events must commit with the transitions they describe and retry delivery without repeating state mutations.

Own the failure-attribution portion of parent AC 7 and assemble parent ACs 9 and 10. Verify every parent criterion against the settled session and checkpoint behavior from subtasks 1 and 2. Tests remain with the behavior they protect.

## Acceptance Criteria

1. Feature-task and goal status derive workflow pause, cycle stage, latest assessment, repair rounds, checkpoint and execution attribution from one bounded SQLite read snapshot. A concurrent transition cannot produce a mixture of pre-transition and post-transition state.
2. Unresolved ACs come from the latest durable assessment. First-pass convergence and repair iteration counts agree with the cycle when legacy progress records are absent. Status distinguishes diagnosis, active repair, paused, satisfied-but-unsettled and completed audit state.
3. Each committed diagnosis, repair authorization, checkpoint attachment, final assessment, pause and recovery emits its required payload-free event through the telemetry outbox in the same transaction as the owning mutation. Rollback leaves neither the transition nor its event.
4. Replayed requests and recovery do not duplicate transition events. Delivery failure retries the durable event without rerunning the cycle mutation. No external telemetry transport runs inside the state transaction.
5. Cycle lookup, snapshot or projection failures produce typed results and an independent diagnostics record. They do not silently become no cycle, zero gaps, successful completion or getOrNull defaults.
6. Audit-repair failures retain distinct phase and failure attribution from review and validation. Downstream execution still receives only the guarded satisfied final assessment. Telemetry excludes evidence bodies, prompts, repair prose and code.
7. Focused regressions exercise concurrent status reads, rollback with outbox failure, event replay, interrupted recovery, initially satisfied and repaired convergence, and projection failures through production adapters. The assembled path covers all parent ACs without weakening earlier tests.
8. The touched modules pass the configured quality gate. Before declaring the parent complete, record the focused-test results and full quality-check evidence required by parent AC-009 and AC-010. A compile-only build receipt does not satisfy either obligation.

## Implementation Baseline

The current tree has a bounded audit status snapshot, an explicitly owning cycle pointer, cycle-based counters and transition events written through the existing SQLite outbox. It also has rollback/replay tests and typed diagnostic paths for cycle-read failures.

Use investigation.md section AC-007 as historical context. Verify concurrent snapshot consistency, failed delivery and projection failures directly; the existence of a snapshot method or a passing outbox test is not proof of every case.

## Implementation Plan

1. Trace feature-task and goal status through the same bounded snapshot. Prove pause state, latest assessment, repair rounds, checkpoint and attribution come from one read transaction.
2. Verify every required stage, pause and recovery event commits with its owning mutation. Preserve payload-free event fields and keep external transport outside the database transaction.
3. Check duplicate requests, interrupted recovery and failed delivery. Event retry must neither duplicate the transition event nor repeat repairs or consume another operator grant.
4. Check cycle absence, corruption and projection failure separately. Preserve cancellation, emit diagnostics and keep audit failures distinct from review and validation outcomes.
5. Verify counters and status for initially satisfied, repaired, paused, satisfied-but-unsettled and completed cycles, including records without legacy progress artifacts.
6. Assemble the parent acceptance matrix with repository evidence, test results and the final validated source state. Repair remaining in-scope gaps and leave any unmet parent criterion explicit.

## Non-Goals

- No new telemetry backend, transcript store, generic event framework or unrelated status redesign.
- No telemetry-driven domain decisions or weakened final audit eligibility.
- No changes to review, build or validation ownership.
- No acceptance based solely on receipt prose or historical test counts.

## Dependency Notes

Requires subtasks 1 and 2. Extend their transaction and checkpoint owners instead of introducing another state writer. Both status entry points consume the same snapshot contract.

## Validation Strategy

Use production SQLite with controlled concurrent readers and writers to prove a status read cannot combine states across a transition. Inject outbox persistence failure and transport failure separately. Prove rollback, deduplicated replay and eventual delivery without repeated mutations.

Exercise the assembled production launch-binding, stage, Git checkpoint, settlement, status and outbox path, including reopen at durable boundaries. Record tests and the full Kotlin quality command against the final source state. Keep compile-only goal checks separate from suite-test and full-quality evidence.

## Mandates and Overrides

- Treat the existing working-tree changes as the implementation baseline. Preserve correct behavior and close only demonstrated acceptance gaps. Do not infer acceptance from existing code, repair prose or a passing compile.
- Preserve source and generated-output boundaries, manifest-driven routing, typed contract failures, cancellation and diagnostics required by AGENTS.md.
- Audit owns diagnosis, authorized repair and final repository assessment in one attributable cycle. Stop after a stage rejection or paused acknowledgement. Do not return to an unrelated implement session to repair audit gaps.
- Keep builds and test execution with their authorized validation phase. A goal build-only gate runs only its declared build commands. Existing external validation evidence must name its tested source state and cannot establish later untested changes.
- Do not install, reset workflow state, change acceptance criteria, or edit SQLite from a goal child. The runtime owns subtask commits and pushes under same_branch_commit_per_subtask.

## Next Path

Settle the parent only after all three subtask outcomes and commits are durable and the complete parent acceptance matrix has passing evidence. If a criterion or required validation remains open, report it and keep parent acceptance incomplete.
