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

**opencode is prose-only; refuse before launch.** This skill can be invoked
directly (bypassing the `bill-feature-task` router), so it must refuse on its own
when the agent currently executing it is opencode: stop before the Single
Confirmation Gate and the foreground launch and emit the actionable refusal
pointing to `bill-feature-task-prose`:

> Runtime mode is not supported on opencode: its foreground Bash tool is hard-killed at 120s before a phase can finish, and per-phase output cannot be harvested back. Use prose instead — run bill-feature-task-prose for a single feature task, or bill-feature-goal mode:prose for a decomposed goal.

Do not launch `skill-bill feature-task` on opencode — the runtime CLI refuses the
same way whenever the resolved runtime agent is opencode, and runtime mode is
non-viable there (the 120s foreground kill and the un-harvestable PTY output).
For a prose run instead, use `bill-feature-task-prose`.

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

## Review-driven implement-fix loop

The runtime closes a bounded remediation loop around `review`. The `review`
phase emits a structured verdict derived from its findings: `approved` when no
unresolved Blocker or Major findings remain, or `changes_requested` when any are
present. The runtime evaluates that verdict — prose alone cannot advance past a
Blocker/Major finding.

- On `approved`, the run advances to `audit` (a clean run never launches a fix).
- On `changes_requested`, the runtime takes a backward edge to a dedicated
  `implement_fix` phase, which addresses the carried review findings on the
  current working tree as incremental reconciliation (not a plan re-application),
  then re-runs `review`. This `review` → `implement_fix` → `review` cycle is
  capped at 3 iterations via a durable per-edge counter. The first `approved`
  verdict in the loop advances the run to `audit`.
- If the loop exhausts its cap without an `approved` verdict, the run blocks
  loudly rather than advancing: it records a durable terminal blocked phase plus
  an observability/ledger event carrying the loop id `review_fix`, the iteration
  count, and the unresolved findings. It never advances to `audit` on unresolved
  Blocker/Major findings. Surface this block like any other blocked gate.

The loop is crash-safe: a death during `implement_fix` or a re-`review` resumes
at the correct phase and iteration with no double-applied mutations, and a loop
that already burned its cap re-blocks on resume rather than re-entering past the
cap. Each `implement_fix` launch and re-`review` carries the `review_fix` loop id
and iteration in the ledger and status output, and finished telemetry reflects
the review-fix iteration count.

## Audit-gap re-plan/re-implement loop

The runtime closes a second, wider bounded remediation loop around `audit`. The
`audit` phase emits a structured verdict derived from the acceptance criteria it
checked: `satisfied` when every criterion is met, or `gaps_found` when one or
more remain unmet. The runtime evaluates that verdict — prose alone cannot
advance past an unmet acceptance criterion.

- On `satisfied`, the run advances to `validate`.
- On `gaps_found`, the runtime takes a backward edge re-entering `plan`, then
  `implement`, then `review`, then `audit`. The handoff into the re-entered
  `plan` and `implement` is scoped to the failing criteria the audit carried, so
  the loop addresses the gaps rather than redoing settled content. This
  `audit` → `plan` → `implement` → `review` → `audit` cycle is capped at 2
  audit-gap iterations via a durable per-edge counter. The first `satisfied`
  verdict in the loop advances the run to `validate`.
- If the loop exhausts its cap without a `satisfied` verdict, the run blocks
  loudly rather than advancing: it records a durable terminal blocked phase plus
  an observability/ledger event carrying the loop id `audit_gap`, the iteration
  count, and the unmet criteria. It never advances to `validate` on unmet
  acceptance criteria. Surface this block like any other blocked gate.

The re-entered `implement` is idempotent: it reconciles the working tree toward
the updated plan without double-applying, and a crash mid-loopback resumes at the
correct phase and iteration, preserving the `audit_gap` watermark and re-blocking
a cap-exhausted loop on resume rather than re-entering past the cap.

The two loops compose without double-counting. Each audit-gap iteration re-passes
through `review` — including its own `review_fix` loop — before re-`audit`, so
audit-driven changes are themselves reviewed. The `review_fix` counter resets per
audit-gap iteration (each re-review is a fresh verification), while the
`audit_gap` counter is independent and accrues across the whole run. Each backward
edge carries the `audit_gap` loop id and iteration in the ledger and status
output, and finished telemetry reflects the audit-gap iteration count alongside
the review-fix count.

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
