---
name: bill-feature-task-runtime
description: EXPERIMENTAL. Use only when explicitly running the runtime-driven feature-task phase loop through the foreground `skill-bill feature-task-runtime` runtime. Not a default path; prefer the standard feature workflow for normal feature work.
---

# Feature Task Runtime Content (EXPERIMENTAL)

`bill-feature-task-runtime` is an **experimental** front door for running a
single governed spec through the runtime-driven feature-task phase loop
(`plan -> implement -> review -> audit -> validate`) owned by the local
`skill-bill feature-task-runtime` driver.

> **Experimental — not a default path.** This capability exists to evaluate the
> runtime-driven phase loop against the established feature-task flow. Do not use
> it as the default way to implement features and do not route normal work here.
> For ordinary feature work, use the standard feature entry point. This skill
> neither replaces nor alters that flow.

`bill-feature-task-runtime` is only the trigger surface: it gathers and confirms
the spec, presents exactly one confirmation gate, and then launches the runtime
command. It does **not** re-implement phase orchestration in prose — the runtime
owns the phase loop, the per-phase handoff, the schema gates, and the durable
state. This skill must never restate or re-derive that orchestration.

## Intake

Gather enough to identify and confirm the run:

- the issue key
- the governed spec path the run implements
- the agent currently executing this skill

If the issue key or spec path is missing, stop and ask for it. Do not invent
either one. The runtime sources the run-invariants (spec reference, acceptance
criteria, mandates and overrides) directly from the spec at launch — this skill
does not parse or restate them.

## Single Confirmation Gate

Present one concise confirmation that includes:

- the issue key and spec path
- the agent that will run each phase, including any explicit override
- an explicit reminder that this is the experimental runtime-driven path

Ask exactly one confirmation question: whether to proceed and start the
foreground runtime phase loop.

Do not launch `skill-bill feature-task-runtime` while the run is unconfirmed. If
the user declines, stop. The confirmation gate is the only user interaction
required before execution starts.

## Confirmed Handoff

After confirmation, execute the foreground driver directly in the current agent
session, always passing `--agent` set to the agent currently executing this
skill:

```bash
skill-bill feature-task-runtime run <issue_key> <spec_path> --agent <currently-executing-agent>
```

Always pass `--agent` set to the agent currently running this skill (for example
`claude` from Claude Code, `codex` from Codex, `opencode` from OpenCode), so the
invoking agent — not a hardcoded default — drives the phase runs. Only use
`--agent-override` when the user explicitly selected a different agent;
`--agent-override` wins over `--agent`. An optional repeatable
`--phase-agent <phase-id>=<agent-id>` (for example `--phase-agent plan=claude`)
assigns a specific agent to one phase.

Do not ask the user to run this command manually. Keep the run in the foreground
unless the user asks otherwise; pass `--monitor` to tee phase transitions to the
terminal.

The runtime owns everything after launch: it opens the durable runtime workflow,
runs each phase through its own agent, validates each phase output against the
schema gate, persists per-phase state, and blocks loudly on a failed gate or a
missing upstream output. Treat the durable workflow state as authoritative over
any prose.

## Status and Resume

Status is read-only and never starts a run:

```bash
skill-bill feature-task-runtime status <workflow_id>
```

Report the complete, pending, and blocked phase counts, the current phase, and
each phase's status exactly as returned. Do not mutate state during a
status-only request.

To resume an interrupted run against its existing workflow id:

```bash
skill-bill feature-task-runtime resume <workflow_id> <issue_key> <spec_path> --agent <currently-executing-agent>
```

Resume re-runs the runtime phase loop, which deterministically skips
already-complete phases from the durable per-phase records. If the runtime blocks
a phase, summarize the blocked phase and reason rather than continuing the loop
manually.
