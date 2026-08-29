# SKILL-224 Subtask 2: MCP phase settlement

## Outcome

Agents settle prose phases by calling typed MCP tools. The parent prefers the
durable settlement record over stdout JSON.

## Work

1. Application API: `complete`, `block`, `auditSettle` — write settlement and
   produce a canonical envelope map.
2. SQLite store + migration for settlements (workflow_id, phase_id, attempt,
   kind, payload, recorded_at); last-write wins per attempt.
3. MCP tools: `feature_task_phase_complete`, `feature_task_phase_block`,
   `feature_task_audit_settle` — registry, dispatcher, telemetry schema.
4. `gateOutput` prefers settlement for the current attempt; else stdout +
   synthesizer.
5. Pass `workflow_id` and attempt into phase launch briefing/env.
6. Update getting-started docs for the settlement carve-out; prompts prefer MCP.

## Acceptance

Matches parent AC 4–8. No silent advance without settlement or recoverable
value.
