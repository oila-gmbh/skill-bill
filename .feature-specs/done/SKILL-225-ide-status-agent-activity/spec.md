# SKILL-225: IdeStatus agent activity pulse

## Intended Outcome

While a feature-task or goal phase agent is running, the IntelliJ status plugin
shows real agent activity, not only runtime lease / workflow freshness.

Surface a dedicated row:

`Agent activity: <short label> | <time>`

backed by mid-run fields `last_agent_activity_at` and
`last_agent_activity_label` on IdeStatus. Keep existing `updated_at` as the
authoritative freshness / stale clock (lease heartbeat and durable workflow
writes). Do not repurpose `updated_at` for agent activity.

## Acceptance Criteria

1. During an active agent-backed phase, IdeStatus JSON can carry
   `last_agent_activity_at` (ISO-8601 instant) and `last_agent_activity_label`
   (closed short vocabulary) when the wait loop or a runtime-owned evidence
   seam has stamped at least once; both fields are omitted together when no
   stamp exists.
2. Allowed labels are a closed set covering at least: worktree write, stdout,
   durable progress, evidence read, and tool stream (exact wire strings fixed
   in the IdeStatus schema). Free-form tool names and Cursor transcript paths
   are not labels.
3. The process wait loop persists activity stamps mid-run from existing probes
   (worktree activity, stdout / stream ticks, durable progress) so IdeStatus
   polls can observe them without waiting for the agent run to finish.
4. Governed review evidence MCP reads (or equivalent runtime-owned review
   evidence calls) stamp activity as `evidence read` without requiring Cursor
   transcript file watching.
5. IdeStatus `updated_at` / freshness classification behavior stays lease- and
   workflow-driven; activity stamps do not keep a run "fresh" by themselves
   and do not make a quiet-but-alive think look stale.
6. The IntelliJ status details UI shows
   `Agent activity: <label> | <formatted time>` when both fields are present,
   and omits the row when they are absent.
7. Contract parity holds: `orchestration/contracts/ide-status-schema.yaml`,
   Kotlin IdeStatus models / projector / validator, and plugin JSON mapping
   agree on the new fields.

## Constraints

- Prefer extending `AgentRunActivityProbe` / wait-loop stamps and review
  evidence seams the runtime already owns; do not key primary activity off
  Cursor `~/.cursor/projects/.../agent-transcripts` paths.
- Persist enough for cross-process IdeStatus polls (SQLite or existing status
  projection store), not only in-memory `AgentRunLivenessSnapshot`.
- Keep label vocabulary short and scannable for the status bar / details popup.
- Do not read or mutate the user's unrelated dirty worktree beyond what the
  existing worktree activity probe already scans for liveness.

## Non-Goals

- Streaming full agent transcripts or tool payloads into the plugin.
- Per-tool free-form labels in the status row.
- Changing Stop / Pause / elapsed / planning presentation beyond adding the
  activity row.
- VS Code extension parity in this issue (follow-up if needed).
- Replacing execution_liveness / latest_liveness_signal CLI goal-status fields
  unless IdeStatus needs a thin mirror for the same stamp.

## Validation Strategy

- Contract / golden tests for IdeStatus schema and wire map including present
  and omitted activity fields.
- Unit tests that a wait-loop / probe stamp updates the durable activity
  fields and that IdeStatus projects them without changing `updated_at`
  freshness rules.
- Plugin mapping test for the details row format and omission when fields are
  absent.
- `skill-bill validate` and targeted runtime / plugin tests for the touched
  modules.

## Delivery Plan

1. One implement pass: durable activity stamps, IdeStatus contract +
   projection, IntelliJ details row.
