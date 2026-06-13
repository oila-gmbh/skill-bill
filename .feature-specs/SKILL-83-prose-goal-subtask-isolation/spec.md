# SKILL-83 — Subtask-agent isolation for mode:prose goal orchestration

status: Draft

## Problem

In `bill-feature-goal mode:prose`, the goal orchestrator runs each subtask's
full phase loop **inline in its own session**. Context from every phase of every
subtask accumulates in the orchestrator: preplan digests, plans, implementation
summaries, review reports, audit reports. For a 5-subtask goal this is roughly
5× a single subtask's context budget, and the orchestrator hits the auto-compact
wall well before the goal is done.

`mode:runtime` solves this with `claude -p` (OS-level process isolation per
subtask) but exposes a different risk: the Anthropic/Claude Code $100
hard-stop spending limit applies to `claude -p` invocations and cannot be
interactively paused — a runaway goal loop terminates abruptly.

## Solution

Spawn one **subtask-agent** (via the Agent tool) per subtask. The orchestrator
delegates the full phase loop to that agent and waits for a terminal signal.
The subtask-agent enters the existing `bill-feature-task-prose` continuation
contract exactly as the orchestrator does today — the only change is _where_ the
continuation runs.

### Nesting depth

```
Level 0  goal orchestrator          (thin loop — manifest + spawn + verify)
Level 1  subtask-agent              (new — one per subtask, fresh 1M context)
Level 2  phase agents               (preplan, plan, implement, audit, quality-check)
         code-review specialists    (spawned inline from Level 1, same as today from Level 0)
```

Adding exactly **one** level over the current architecture (which runs
code-review specialists at Level 1 from the orchestrator at Level 0). The
code-review step continues to run inline in whichever session is currently
acting as "the orchestrator for that subtask" — Level 1 in the new design.

## Acceptance Criteria

1. **Orchestrator stays thin.** The Level-0 goal orchestrator holds only: the
   decomposition manifest, per-subtask terminal outcomes (status + commit SHA),
   and the current subtask index. It does NOT accumulate phase artifacts
   (preplan digests, plans, implementation summaries, review reports) from any
   subtask.

2. **One subtask-agent per subtask.** For each runnable subtask the orchestrator
   spawns exactly one Level-1 sub-agent via the Agent tool with a self-contained
   briefing (issue key, subtask ID, workflow ID to continue, spec path,
   goal_continuation contract).

3. **Subtask-agent uses the existing continuation contract.** The Level-1 agent
   calls `feature_implement_workflow_continue` (or `skill-bill workflow
   continue`) with the parent `issue_key` and `subtask_id`, and follows the
   `bill-feature-task-prose` goal-continuation rules verbatim:
   `suppress_pr=true`, no install flows, `commit_push` is the terminal signal.

4. **Phase agents and code-review specialists are unchanged.** The Level-1 agent
   spawns Level-2 phase agents (preplan, plan, implement, audit, quality-check)
   and runs `bill-code-review` inline (which spawns Level-2 specialists) exactly
   as the current Level-0 orchestrator does. No changes to any phase agent or
   reviewer.

5. **Durable-state authority is unchanged.** After each subtask-agent returns,
   the orchestrator verifies the terminal outcome via
   `feature_implement_workflow_get` or `skill-bill goal status` before advancing.
   The in-session return value is a signal only.

6. **Stop-loudly on failure.** If a subtask-agent returns blocked or failed, the
   orchestrator stops immediately, surfaces subtask ID, `blocked_reason`,
   workflow ID, and `last_resumable_step`. Does NOT advance to the next subtask.

7. **Nesting smoke test passes before implementation begins.** A standalone test
   (shell script or Kotlin integration test) verifies that a Level-0 agent can
   spawn a Level-1 agent that itself spawns a Level-2 agent via the Agent tool,
   and that all three levels complete successfully. This test is the go/no-go
   gate for the entire feature — if 3-level nesting fails, implementation stops.

8. **Existing tests pass.** `InstallerShellDelegationTest`,
   `InstallerShellReuseLastSelectionTest`, `InstallerShellReconcileTest`, and
   the goal-runner integration tests remain green.

9. **Single-task `bill-feature-task` (no goal loop) is unchanged.** The
   subtask-agent layer only applies to `bill-feature-goal mode:prose`.

10. **PR suppression.** Each subtask commits but does not open a PR. The goal
    orchestrator opens exactly one parent PR after all subtasks complete, via the
    standard `bill-pr-description` + `gh` path.

## Non-goals

- Changes to `mode:runtime` — it uses `claude -p`; unaffected.
- Changing the phase-agent or code-review-specialist structure.
- Level-1 subtask-agent context recovery (durable state handles resume).
- Eliminating the Agent tool's nesting limit — we verify the limit works; we
  don't change Claude Code internals.
- Any UI or desktop-app changes.

## Open Questions

1. **Does Claude Code support Level-0 → Level-1 → Level-2 Agent tool nesting?**
   Unknown. The smoke test (AC7) is the authoritative answer. If it fails, the
   feature is blocked and the alternatives are:
   - Keep mode:prose with `/clear` between subtasks (manual, works today).
   - Use mode:runtime with a conservative API spending cap set on the Anthropic
     console.

2. **Does the Level-1 agent have access to all MCP tools needed for
   `feature_implement_workflow_continue`?** MCP tool availability in sub-agents
   must be verified in the smoke test.

3. **What is the right `agentType` for the Level-1 subtask-agent?** Options:
   `bill-feature-task-implementation` (existing), or a new
   `bill-feature-task-subtask-runner` type. Using an existing type avoids
   creating new registry entries but may carry unwanted system-prompt scope.

## Validation Strategy

- AC7 smoke test: write a minimal Agent-nesting harness (outside install.sh)
  that verifies 3-level depth works before any prose-skill changes.
- Kotlin: `(cd runtime-kotlin && ./gradlew check)` must pass.
- Manual: run a 2-subtask goal in `mode:prose` end-to-end and confirm the
  orchestrator context does not grow between subtasks.

## Dependencies

- Requires Claude Code to support ≥ 3 levels of Agent tool nesting (unverified;
  gated by AC7).
- No dependency on SKILL-82 or earlier work.

## Size

MEDIUM — one new orchestration layer, one smoke test, no runtime Kotlin changes.
