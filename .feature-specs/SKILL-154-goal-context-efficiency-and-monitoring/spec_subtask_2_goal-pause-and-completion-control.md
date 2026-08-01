# SKILL-154 Subtask 2 - Deterministic Goal Pause and Completion Control

Parent spec: [.feature-specs/SKILL-154-goal-context-efficiency-and-monitoring/spec.md](./spec.md)
Issue key: SKILL-154

## Scope

Add deterministic pause-at-subtask control to the decomposed goal runtime and align long-run handoff with one bounded completion signal. Support a predeclared stop boundary and a durable operator request for an already-running goal, with atomic boundary persistence and resumable continuation.

## Acceptance Criteria

1. The goal CLI accepts `--stop-after-subtask <id>` and persists or carries the policy for the active parent run without changing the existing review or agent selections.
2. An operator can request a pause for an already-running goal through a durable, idempotent control boundary that is visible to the goal parent without status polling.
3. After a targeted subtask reaches a durable terminal success, the parent atomically records the completed subtask and paused state before selecting or launching any dependent subtask.
4. A pause requested before launch, during child execution, or more than once produces one stable paused state; an OS interrupt cannot cause the runtime to skip the boundary or launch the next child.
5. Resume preserves the parent workflow identity, planning checkpoints, review policy, commits, and child continuation state, then starts at the first pending runnable subtask.
6. The foreground driver uses the original process terminal result as the completion signal and does not require repeated status, log, filesystem, or database polling.
7. Tests cover boundary ordering, CLI parsing, durable request idempotency, crash/restart reconciliation, stale leases, resume, and the guarantee that subtask N+1 is not launched before the requested pause is recorded.

## Non-Goals

- Changing child phase semantics, review policy, planning decomposition, or PR behavior.
- Replacing crash reconciliation or making OS signals the normal operator-control protocol.
- Adding live progress relaying to the parent agent.

## Dependency Notes

Depends on Subtask 1's bounded orchestration and handoff contract. Reuse existing goal manifest stores, workflow leases, owner-token/generation fencing, and terminal outcome reconciliation.

## Validation Strategy

Run focused runtime-domain, runtime-application, CLI, lease/reconciliation, and integration tests. Then run `(cd runtime-kotlin && ./gradlew check)` and the repository validation commands.

## Next Path

Commit this subtask, then execute Subtask 3: monitor-only skill and integration conformance.
