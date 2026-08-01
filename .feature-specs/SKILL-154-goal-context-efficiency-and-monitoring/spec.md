# SKILL-154 - Goal Context Efficiency and Monitor-Only Control

## Mode

decomposed

## Intended Outcome

Goal orchestration keeps the main agent context bounded and gives operators deterministic, read-only monitoring and safe pause control. Supplied skill content is not redundantly replayed from disk, long runs do not produce repeated orchestration commentary or polling, and a goal can pause cleanly between completed subtasks without racing the next child launch.

## Overview

This feature addresses the context pollution and control failures observed during SKILL-153. It covers the skill-loading boundary, top-level goal orchestration, bounded process completion handling, durable pause-at-subtask behavior, and a dedicated `bill-monitor` entry point. Child implementation contexts and the workflow database remain authoritative; the change must not move planning or implementation payloads into the top-level conversation.

## Acceptance Criteria

1. When a session already contains supplied skill content and the installed skill identity matches, the routing boundary verifies the installed source with compact path, digest, and metadata without replaying the full duplicate `SKILL.md`; an identity mismatch fails loudly.
2. Goal orchestration emits one launch notice, one monitoring-command block, and one terminal result, with no repeated wait or progress commentary while the goal runs; status snapshots are produced only for explicit user status requests.
3. A goal supports a predeclared `--stop-after-subtask <id>` option and a durable operator pause request for an already-running goal; the pause is applied after the targeted subtask's terminal outcome is durably saved and before the next subtask is selected or launched.
4. Pausing preserves planning checkpoints, workflow identity, commits, and resumable state; resuming continues with the first pending runnable subtask without regenerating settled plans.
5. The orchestrator awaits the original goal process through one bounded completion wait and treats its terminal result as authoritative; it does not poll status, logs, workflow state, or changed files.
6. Repository searches used by the orchestration path are narrow and bounded, returning targeted file names or excerpts rather than broad recursive output that is truncated or replayed into the main context.
7. The top-level orchestrator retains only manifest metadata, the current subtask index, and bounded terminal outcomes (`status`, `commit_sha`, and `workflow_id`); planning payloads, implementation summaries, audits, reviews, and diagnostics remain in child context or durable storage.
8. The implementation-follow-up workflow is documented for fresh conversations: a new session can use the issue key and canonical repository path to inspect or resume durable state without receiving the prior orchestration transcript.
9. A `bill-monitor <issue-key>` skill provides a small read-only monitoring entry point that performs at most one `skill-bill goal status` snapshot per explicit request and may print the user-owned `goal watch` command.
10. `bill-monitor` never launches, resumes, resets, accepts, modifies, polls, sleeps, tails logs, or rereads workflow state in a loop, and reports only bounded counts, current subtask and step, liveness, and resumable state.
11. The monitor skill defines a same-thread read-only contract for follow-up queries, while re-invocation remains the safe cross-turn activation mechanism.
12. Tests cover duplicate skill identity handling, bounded output, narrow search behavior, thin orchestrator retention, pause requests before launch and during child execution, duplicate pause requests, crash/restart, resume, and monitor read-only refusal paths.
13. Existing goal execution, child continuation, telemetry redaction, workflow leases, and provider-neutral behavior remain intact outside the new bounded control and monitoring contracts.
14. `skill-bill validate`, the runtime Kotlin checks, strict agent-config validation, and the relevant documentation/catalog checks pass for the completed implementation.

## Constraints

- The workflow database and durable child outcomes remain authoritative over chat output and checked-in projections.
- Pause requests must use existing owner, lease, generation, and atomic persistence conventions; an OS signal is only an interruption fallback, not the pause protocol.
- Context reduction must not drop required governed instructions, acceptance criteria, continuation projections, or security/privacy boundaries.
- Normal status, watch, telemetry, and PR surfaces remain path-free and free of raw child output.
- The new monitor skill is read-only and must not become an alternate goal executor.
- Preserve the current agent-neutral runtime strategy model; do not add provider-specific branches.

## Non-Goals

- Rewriting or semantically compressing governed skill content.
- Changing feature decomposition, planning semantics, child phase ordering, or review policy.
- Automatically opening a new conversation or transferring a conversation transcript between agents.
- Replacing the workflow database with temporary files or chat state.
- Adding general-purpose repository search, log aggregation, or live progress relaying to the monitor skill.

## Validation Strategy

Run focused runtime application/domain/infrastructure tests for pause requests, boundary ordering, leases, continuation, and compact handoffs; run skill and monitor contract tests; validate generated catalog/install output; then run `skill-bill validate`, `(cd runtime-kotlin && ./gradlew check)`, `npx --yes agnix --strict .`, and `scripts/validate_agent_configs`.

