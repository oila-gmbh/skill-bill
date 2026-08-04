# SKILL-154 Subtask 3 - Monitor-Only Skill and Integration Conformance

Parent spec: [.feature-specs/SKILL-154-goal-context-efficiency-and-monitoring/spec.md](spec.md)
Issue key: SKILL-154

## Scope

Create the governed `bill-monitor` skill as a read-only entry point for decomposed goal status. Register it through the normal catalog/install path and verify that its same-thread monitor contract remains separate from feature execution and resume flows.

## Acceptance Criteria

1. `bill-monitor <issue-key>` resolves the canonical repository and performs at most one read-only goal status snapshot for each explicit request.
2. The skill reports only complete, pending, and blocked counts; current subtask and step; execution liveness; and resumable state, with bounded output.
3. The skill may print a copyable user-owned `skill-bill goal watch` command but never invokes watch, launches a goal, resumes a workflow, resets state, accepts a subtask, or writes to the repository or workflow database.
4. The skill refuses or stops on malformed, ambiguous, missing, or unsupported issue selections without falling through to implementation behavior.
5. Same-thread follow-up guidance keeps the session read-only until the user explicitly exits monitor mode; a later invocation is required to re-establish the contract across a new turn or fresh conversation.
6. Skill source, catalog, install/render output, agent configuration, and documentation checks cover the new entry point without replaying large child or skill payloads into the main orchestrator context.
7. Tests cover one-call status behavior, no-mutation/no-launch behavior, bounded output, invalid issue handling, explicit status follow-up, and monitor-to-implementation separation.

## Non-Goals

- Implementing a second workflow database or live monitoring service.
- Starting, resuming, pausing, resetting, or repairing goals from `bill-monitor`.
- Replacing the existing `goal status` or user-owned `goal watch` commands.

## Dependency Notes

Depends on Subtasks 1 and 2 for the bounded orchestration contract and deterministic goal state transitions. Use the existing dynamic skill discovery, install staging, and validator surfaces.

## Validation Strategy

Run focused monitor skill and CLI tests, `skill-bill validate`, install/render validation, `npx --yes agnix --strict .`, and `scripts/validate_agent_configs`.

## Next Path

After validation passes, prepare the feature's review and delivery artifacts.
