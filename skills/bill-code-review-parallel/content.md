---
name: bill-code-review-parallel
description: Use when running two review agents in parallel on the same diff and merging their findings. Both agents receive the same diff; findings are coalesced where both agents agree and labeled with provenance ([agentId]). Use when user mentions parallel review, dual review, two-agent review, or asks to run code-review-parallel.
---

# Parallel Code Review

`bill-code-review-parallel` (SKILL-70) runs two review agents concurrently on the
same diff and merges their findings into a unified risk register. Coalesced findings
(found by both agents) appear first within each severity tier and carry both agent IDs.

This skill is the trigger surface only: it gathers the scope and agent pair, presents
one confirmation gate, and then invokes the CLI runtime command. It does **not** dispatch
review work via the Agent tool — all agent execution goes through
`skill-bill code-review-parallel` and the `GoalRunnerSubtaskLauncher` port.

## Intake

Gather:

- The **scope** of the diff to review: `staged`, `unstaged`, `branch` (default), or `pr`.
- The **alternative agent** (`--agent2`). The invoking agent is used for lane 1 by default.
- Optionally: `--repo-root` (defaults to `.`) and `--timeout-minutes`.

If the user does not supply `--agent2`, prompt:

> Which alternative agent should review the diff?
> Supported agents: claude, codex, opencode, copilot, junie

Do not proceed until `--agent2` is provided.

## Single Confirmation Gate

Present one concise confirmation showing:

- The diff scope
- Lane 1 agent (invoking agent or `--agent1` override)
- Lane 2 agent (`--agent2`)

Ask exactly one confirmation question: whether to proceed.

Do not launch the CLI while the run is unconfirmed. If the user declines, stop.

## Confirmed Handoff

After confirmation, run the CLI command directly in the current agent session:

```bash
skill-bill code-review-parallel \
  --agent1 <invoking-agent-id> \
  --agent2 <selected-agent-id> \
  --scope <scope>
```

Append `--repo-root <path>` and `--timeout-minutes <n>` when the user supplies them.

Always pass `--agent1` set to the agent currently executing this skill so the invoking
agent drives lane 1. Only omit `--agent1` if the user explicitly selected a different
agent for lane 1 (uncommon).

The CLI command:
- Resolves the diff from the specified scope
- Detects the dominant stack via `platform-packs/` manifests
- Launches both agents concurrently with the same diff + stack-aware prompt
- Merges findings: coalesced entries (both agents agree) before single-lane entries
  within each severity tier, with provenance labels (`[agentId]` or `[agent1, agent2]`)
- Exits 0 when both lanes succeed; non-zero when either lane fails

## Output Format

Each finding follows:

```
- [F-NNN] [agentId] Severity | Confidence | file:line | description
```

Coalesced findings (found by both agents):

```
- [F-NNN] [claude, codex] Major | High | file:line | description
```

Severity ordering: Blocker → Major → Minor → Nit. Coalesced findings appear before
single-lane findings within the same tier.
