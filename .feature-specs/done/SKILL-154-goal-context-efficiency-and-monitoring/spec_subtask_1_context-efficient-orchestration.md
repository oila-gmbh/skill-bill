# SKILL-154 Subtask 1 - Context-Efficient Skill and Goal Orchestration

Parent spec: [.feature-specs/SKILL-154-goal-context-efficiency-and-monitoring/spec.md](spec.md)
Issue key: SKILL-154

## Scope

Define and implement the bounded context contract for skill routing and goal orchestration. Cover duplicate supplied-versus-installed skill identity handling, one-shot launch/terminal messaging, narrow repository inspection, thin top-level state retention, and fresh-conversation follow-up guidance. Keep child payloads and durable evidence out of the parent conversation.

## Acceptance Criteria

1. Matching supplied and installed skill content is accepted through a compact identity check without replaying the full installed source; mismatches fail loudly with the source identities.
2. Goal launch and completion handling expose only the required monitoring block and terminal notification; repeated wait/progress relays are prohibited by the governed contract.
3. Orchestration guidance requires bounded repository searches and retains only manifest metadata plus `{status, commit_sha, workflow_id}` terminal outcomes.
4. Fresh-conversation follow-up guidance names the canonical repository path and issue key as sufficient durable-state handoff data and forbids transcript copying.
5. Contract tests cover identity match, mismatch, bounded messaging, bounded search/output, and the thin-orchestrator retention shape.

## Non-Goals

- Implementing the goal pause protocol.
- Implementing the `bill-monitor` skill.
- Changing child implementation phases or their durable workflow schemas.

## Dependency Notes

Runs first. It establishes the shared context and handoff contracts consumed by the pause/control and monitor-skill work.

## Validation Strategy

Run focused orchestration contract tests, governed skill validation, documentation/catalog validation, `skill-bill validate`, and strict agent-config validation.

## Next Path

Commit this subtask, then execute Subtask 2: deterministic goal pause and completion control.
