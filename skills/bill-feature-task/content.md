---
name: bill-feature-task
description: Use when running a single governed feature spec through the runtime-driven feature-task phase loop via the foreground `skill-bill feature-task` runtime. Gathers and confirms the run, presents one confirmation gate, then launches the runtime. Use when user mentions implement feature, build feature, implement spec, run feature-task, or feature from design doc.
---

# Feature Task Content

`bill-feature-task` is the front door for running a single governed spec through
the runtime-driven feature-task phase loop
(`plan -> implement -> review -> audit -> validate`) owned by the local
`skill-bill feature-task` driver.

`bill-feature-task` is only the trigger surface: it gathers and confirms the
spec, presents exactly one confirmation gate, and then launches the runtime
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

Ask exactly one confirmation question: whether to proceed and start the
foreground runtime phase loop.

Do not launch `skill-bill feature-task` while the run is unconfirmed. If the
user declines, stop. The confirmation gate is the only user interaction required
before execution starts.

## Confirmed Handoff

After confirmation, execute the foreground driver directly in the current agent
session, always passing `--agent` set to the agent currently executing this
skill:

```bash
skill-bill feature-task run <issue_key> <spec_path> --agent <currently-executing-agent>
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
skill-bill feature-task status <workflow_id>
```

Report the complete, pending, and blocked phase counts, the current phase, and
each phase's status exactly as returned. Do not mutate state during a
status-only request.

To resume an interrupted run against its existing workflow id:

```bash
skill-bill feature-task resume <workflow_id> <issue_key> <spec_path> --agent <currently-executing-agent>
```

Resume re-runs the runtime phase loop, which deterministically skips
already-complete phases from the durable per-phase records. If the runtime blocks
a phase, summarize the blocked phase and reason rather than continuing the loop
manually.
