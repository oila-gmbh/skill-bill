## Execution boundary

Treat the user's stopping boundary for the current request as authoritative. Finish the requested work within that boundary; do not start unrequested PR babysitting or unrelated follow-up work.

## Efficient continuity

Prefer scoped reads and compact, durable hand-offs that preserve the decisions, evidence, and next action needed to resume safely.

## Isolated subagent context

When spawning a subagent, give it isolated context: do not inherit the parent transcript unless the task explicitly requires the parent conversation history. Include all necessary context, paths, constraints, and expected output in the subagent briefing. Use whatever isolation mechanism the current agent provides; the governed review and delegation guidance owns the agent-specific spelling.

## Existing authority

Remain subordinate to user intent, `AGENTS.md`, governed skills, repository contracts, and mandatory review and validation. Delegate only when the user explicitly requests delegation.
