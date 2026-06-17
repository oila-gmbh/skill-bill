---
name: bill-feature-task-runtime
description: Use when running a single governed feature spec through the runtime-driven feature-task phase loop via the foreground `skill-bill feature-task` runtime. This is the runtime-backed mode of bill-feature-task. Gathers and confirms the run, presents one confirmation gate, then launches the runtime. Use when user mentions implement feature, build feature, implement spec, run feature-task, or feature from design doc, and the runtime-driven phase loop is preferred over prose in-session orchestration.
---

# Feature Task Content

`bill-feature-task-runtime` is the runtime-backed mode for running a single
governed spec through the runtime-driven feature-task phase loop
(`plan -> implement -> review -> audit -> validate`) owned by the local
`skill-bill feature-task` driver.

Durable workflow rows use the public workflow identity `bill-feature-task` with
`mode=runtime` in the shared feature-task workflow store. Runtime-specific tool
names are compatibility aliases for that mode, not a separate authoritative
workflow family.

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
- the parallel review agent (from args as `parallel-review:<agent>`; absent when not provided)

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

Append `--parallel-review-agent <agent>` when `parallel-review:<agent>` was passed to this skill.

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

### Live Observation

The run is long-lived and the user must see it progress, not wait in silence for
a terminal result. The invoking agent owns surfacing phase transitions in the
conversation as they happen:

- Always launch the driver with `--monitor` so the runtime tees its structured
  per-phase progress.
- When the run is executed in the background (for example because it can outlast
  a single foreground shell window), attach a persistent, line-buffered observer
  to the runtime's progress stream — tailing the run's output — filtered to phase
  starts and completions, schema-gate results, retries, blocked phases,
  non-validation failures, and run completion. Relay each event inline in plain
  language as it arrives.
- Surface a blocked or failed gate loudly and immediately; never narrate a
  blocked run as if it were progressing normally. Validation findings are the
  exception: the runtime reopens `validate` for repair instead of persisting a
  blocked phase. On completion, report the terminal per-phase summary.

This is observation only: the agent reports what the runtime emits and never
re-derives or re-orders the phase loop. The durable workflow state remains
authoritative over any relayed line.

The runtime owns everything after launch: it opens the durable runtime workflow,
runs each phase through its own agent, validates each phase output against the
schema gate, persists per-phase state, and blocks loudly on a failed
non-validation gate or a missing upstream output. Validation findings never
persist `validate` as blocked; they are fed back to the validation agent to fix
and rerun, the same way code-review findings are fixed before the workflow
continues. Treat the durable workflow state as authoritative over any prose.

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

### Rehydrate a missing linear-mode spec before resume

The spec source is an artifact stamp (decomposed → `decomposition-manifest.yaml`
`spec_source`; single_spec → the `spec_source:` line in `spec.md`), defaulting to
`local`. For `spec_source: local`, resume needs no extra step.

For `spec_source: linear`, the local spec scratch is deleted on terminal success,
so before calling `resume` check whether the file at `<spec_path>` (or a needed
subtask spec) exists. If it is missing, rehydrate it first: fetch the parent
issue by `issue_key` and the subtask by its `linear_issue_id` via the Linear MCP,
rewrite the local spec file(s), and only then call `resume`. The runtime read
path is unchanged — it still reads `<spec_path>` once and freezes invariants;
rehydrate only guarantees the file is present first. Rehydrate is agent-side MCP
only; the runtime gains no Linear dependency.
