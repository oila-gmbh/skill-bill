# SKILL-56 - bill-goal: Agent-Independent Autonomous Goal Runner

Created: 2026-05-29
Status: Draft
Issue key: SKILL-56
Parent: none (top-level feature)

## Decomposition

This feature is decomposed because it spans four distinct boundaries with strict
sequencing, and its load-bearing assumption must be proven before the
infrastructure that depends on it is built: (1) a non-interactive continuation
contract for `bill-feature-implement`, (2) an agent-agnostic headless-run
launcher (port + per-agent adapters in the hexagonal runtime), (3) a goal-runner
domain service that walks a decomposition manifest and spawns one fresh process
per subtask, and (4) the CLI surface plus the `bill-goal` skill front that gates
on decomposition and hands off to the driver. Each later stage consumes the
artifact the previous one produces, and the foundations are independently
verifiable before the consumers are touched.

Implement on one branch with a commit per subtask:

1. [Headless Continuation Contract for feature-implement](./spec_subtask_1_headless-continuation-contract.md)
2. [Agent-Agnostic Headless-Run Launcher (port + adapters)](./spec_subtask_2_agent-agnostic-launcher.md)
3. [Goal-Runner Domain Service (manifest walk, fresh process per subtask)](./spec_subtask_3_goal-runner-service.md)
4. [CLI Surface + Status, and the bill-goal Skill Front](./spec_subtask_4_cli-and-bill-goal-skill.md)

## Sources

- Product design discussion 2026-05-29 (this thread). Key conclusions:
  - Codex ships `/goal`: a persistent objective that loops plan→act→test→review
    autonomously to a verifiable stopping condition. The interesting target is
    not parity with the loop, but a *better* version that reuses
    `bill-feature-implement`'s structured per-unit rigor
    (assess→preplan→plan→implement→review→audit→quality-gate).
  - `bill-feature-implement` already decomposes oversized work into ordered
    subtask specs + a `decomposition-manifest.yaml`, and intentionally stops at
    the `plan` step (`completion_status: "abandoned_at_planning"`). What is
    missing is the *outer loop* that walks the manifest start-to-finish.
  - `feature_implement_workflow_continue(issue_key)` already resolves a parent
    manifest and selects the next runnable subtask. The brain exists; only the
    driver is missing.
  - Subagent nesting is not a viable basis for the loop: the feature-implement
    orchestrator is the main loop and already spawns one level of phase
    subagents; wrapping a whole run inside a subagent would be a second level.
    Therefore each subtask must run as a **fresh top-level process**, not a
    nested subagent.
  - A guaranteed-clean context per subtask (not "probably fits in 1M") is a
    requirement, because the point of a goal runner is large tasks run
    start-to-end without context pollution degrading later subtasks. A fresh
    process per subtask is the only hard guarantee.
  - Skill Bill is agent-independent (Claude, Codex, Opencode, and others). The
    launcher must therefore abstract the per-agent headless invocation, not
    hardcode any single agent's CLI.
  - Persistence ("close the terminal and walk away") is explicitly **out of
    scope**. The driver is a foreground process; the terminal stays open and the
    human is present to observe and interrupt. This both shrinks the build (no
    daemon, no IPC, no cross-process locking) and reduces the risk of unattended
    chaining compounding silent mistakes.
- Existing decomposition + continuation machinery this builds on:
  - `skills/bill-feature-implement/content.md` — orchestrator workflow, step
    contracts, continuation semantics, decomposition mode.
  - `orchestration/contracts/decomposition-manifest-schema.yaml` — manifest
    schema (contract version 0.2): `subtasks[]`, `dependencies`, `status`,
    `current_subtask_intent`, `execution_model`.
  - Workflow-state ports/tools: `feature_implement_workflow_open/update/get/`
    `resume/continue`, resolved under `runtime-kotlin/runtime-ports/.../workflow`.
- Hexagonal runtime this extends:
  - `runtime-kotlin/runtime-ports`, `runtime-domain`, `runtime-infra-fs`,
    `runtime-application`, `runtime-cli`, `runtime-mcp` (see
    `runtime-kotlin/ARCHITECTURE.md`).
- Skill authoring + catalog surface: `skills/`, `README.md` skill catalog,
  native-agent codegen + cross-agent sync (`bill-create-skill`).

## Context

`bill-feature-implement` is already a goal-driven orchestrator for a *single*
feature: it takes a spec, scales ceremony to size, runs each heavy phase in a
fresh subagent, and — when a feature is too large for one reliable run —
decomposes it into ordered subtask specs and a manifest, then stops. Today, a
human re-invokes feature-implement once per subtask (a fresh session each time)
to drive a decomposed feature to completion. That manual re-invocation is the
only thing standing between "decomposed" and "done."

`bill-goal` automates exactly that outer loop, and does it in a way that is
structurally stronger than a single long-running agent loop: each subtask is a
**fresh top-level process**, so context never accumulates across subtasks
regardless of goal size, and each unit still gets the full structured
feature-implement pipeline. The loop is owned by an **agent-agnostic foreground
runtime driver** (not a nested subagent, not a pure in-session skill), so it
works for whichever agent the user runs and never relies on context compaction
to stay correct.

## Problem

There is no supported way to run a decomposed feature from its first subtask to
its last automatically:

- **The outer loop does not exist.** Decomposition stops at `plan`; advancing
  through subtasks is a manual, human-fired re-invocation per subtask.
- **A pure in-session skill loop cannot guarantee clean context.** One agent
  conversation is one context; a skill cannot truly reset its own context
  mid-loop (only lossy compaction), and a subagent-per-subtask is blocked by the
  one-level nesting limit. For large goals, accumulated context is a correctness
  and quality risk, not just a cost.
- **No agent-agnostic way to launch a headless run exists.** Skill Bill installs
  skills across multiple agents, but nothing in the runtime can spawn a
  non-interactive run of a skill for "whichever agent the user uses."
- **feature-implement has no machine-consumable continuation result.** A driver
  needs a structured, parseable outcome per subtask (status, commit, blocked
  reason); today the outcome is conversational.

## Goals

1. Make `bill-feature-implement` enterable **non-interactively** for "the next
   runnable subtask of `issue_key`," with PR creation suppressed, returning a
   structured machine-readable result.
2. Provide an **agent-agnostic headless-run launcher** in the runtime: a port
   plus per-agent adapters (Claude, Codex, Opencode, and any future agent),
   defaulting to **the agent the user invoked `bill-goal` from** (overridable),
   never a hardcoded CLI.
3. Provide a **goal-runner domain service** that walks a decomposition manifest
   in dependency order, launches **one fresh process per subtask** via the
   launcher (guaranteeing clean context for any goal size), advances the
   manifest, stops-and-reports on failure/blocked, and opens a **single PR** for
   the whole goal at the end.
4. Expose the driver as a **foreground CLI command** (`skill-bill goal
   <issue_key>`) plus a read-only **status** command, and ship the **`bill-goal`
   skill** that runs the interactive decomposition, gates on a single human
   confirmation, then hands `issue_key` to the driver.
5. Preserve all existing behavior: feature-implement's interactive flow,
   decomposition contract, manifest schema, workflow state, and every `bill-*`
   skill stay unchanged except for the additive non-interactive entry path.

## Non-Goals

- **No persistence / no background daemon.** The driver is foreground; the
  terminal stays open. "Close the terminal and it keeps running," cross-process
  status from a cold agent, and any always-on supervisor are explicitly out of
  scope (possible follow-up).
- **No single-session / compaction-based loop.** Clean context per subtask is a
  hard requirement met by fresh processes; an in-session inline loop is rejected.
- **No subagent-nested execution.** Subtasks run as top-level processes, never as
  a subagent of the calling agent.
- **No change to the decomposition contract or manifest schema** beyond fields
  the runner needs to record progress (which already exist in schema 0.2:
  `status`, `commit_sha`, `workflow_id`, `current_subtask_intent`).
- **No change to feature-implement's interactive behavior.** The non-interactive
  entry is additive; humans running feature-implement directly see no change.
- **No automatic launch decision.** The human confirms the decomposition once
  before the autonomous loop begins; `bill-goal` never starts the loop on
  unreviewed decomposition.
- **No new agent onboarding.** Launcher adapters cover currently-supported
  agents; agents without a headless skill-run path are reported as unsupported,
  not silently skipped.

## Target Outcome

```text
# In your agent (Claude / Codex / Opencode), interactively:
/bill-goal  <design doc or issue key>
# → feature-implement decomposes the goal into N ordered subtasks + manifest
# → bill-goal shows the decomposition and asks for ONE confirmation
# → on confirm, hands off to the foreground runtime driver:

skill-bill goal SKILL-NN
# → subtask 1/ N  [agent: codex]  running … → complete (commit abc123)
# → subtask 2/ N  [agent: codex]  running … → complete (commit def456)
# → …each subtask is a FRESH process: zero context carried across subtasks…
# → all subtasks complete → opens ONE PR for the whole goal
#   (or: subtask k blocked → STOP, report which + why, manifest pointer left)

# Any time, read-only:
skill-bill goal status SKILL-NN
# → 4/7 complete · current: subtask 5 "runtime-state" (step: implement) · agent: codex
```

## Acceptance Criteria (parent-level; satisfied collectively by subtasks)

1. `bill-feature-implement` can be entered non-interactively for a decomposed
   parent `issue_key`, runs exactly the next runnable subtask (pending + deps
   complete) with PR creation suppressed, and **records a structured outcome in
   the durable workflow store** (the authoritative result channel; process stdout
   is diagnostic only) containing at least: `subtask_id`, terminal `status`
   (`complete`/`failed`/`blocked`), `commit_sha` (when complete), and
   `blocked_reason` (when blocked).
2. The runtime exposes an agent-agnostic launcher that, given an agent
   identifier and a skill-run request, spawns a fresh headless process for the
   correct agent and returns its captured outcome; the agent is
   configured/detected, never hardcoded; an agent with no headless path is
   reported as unsupported.
3. A goal-runner service walks a decomposition manifest in dependency order,
   launches one fresh process per subtask via the launcher, updates the manifest
   (`status`, `commit_sha`, `workflow_id`, `current_subtask_intent`) after each,
   stops-and-reports on the first failed/blocked subtask leaving the pointer on
   it, and — when all subtasks complete — opens a single PR covering the whole
   goal.
4. Re-running the goal runner on a partially-complete manifest resumes from
   `current_subtask_intent` (completed subtasks skipped, the blocked/next one
   attempted); no subtask is run twice as a result of resume.
5. `skill-bill goal <issue_key>` runs the loop in the foreground with
   human-readable progress; `skill-bill goal status <issue_key>` is read-only and
   reports completed/current/blocked counts and the active agent.
6. The `bill-goal` skill exists (content + README catalog row + cross-agent
   sync): it runs the interactive decomposition, presents the decomposition for a
   single confirmation, and on confirmation hands `issue_key` to the driver; it
   does not start the loop on unconfirmed decomposition, and on a small goal that
   decomposition declines it completes the single feature directly.
7. All existing behavior is intact: interactive feature-implement, the manifest
   schema, workflow-state tools, and every other `bill-*` skill are unchanged
   except for the additive non-interactive entry path.
8. The maintainer validation gate passes:
   - `skill-bill validate`
   - `(cd runtime-kotlin && ./gradlew check)`
   - `npx --yes agnix --strict .`
   - `scripts/validate_agent_configs`

## Validation Strategy

Per-subtask validation lives in each subtask spec. Two cross-cutting checks
anchor the feature:

- **Load-bearing de-risk (subtask 1).** Before any launcher/driver work, prove
  the non-interactive continuation against a *real existing decomposed manifest*
  (e.g. a fresh scratch issue, or a dry-run mode) and confirm the structured
  result is produced and parseable. If the headless trigger is unreliable, the
  whole feature is unreliable; this must be demonstrated, not assumed.
- **End-to-end goal run (subtask 3/4).** Drive a small purpose-built decomposed
  feature (2–3 trivial subtasks) from first to last through `skill-bill goal`,
  asserting: a fresh process per subtask, manifest advanced correctly, single PR
  at the end, and a clean stop-and-report when one subtask is forced to fail.
  Where a live multi-agent matrix is unavailable, document the agents actually
  exercised instead of claiming coverage.

## Key Risks & Assumptions

These are load-bearing; subtask 1 exists to prove them before the runtime is
built on top:

1. **Skill + MCP availability in headless runs.** The whole feature assumes a
   headless agent process (`claude -p`, `codex exec`, `opencode run`, …) has
   *both* the `bill-feature-implement` skill *and* the skill-bill MCP server
   loaded. The harness explicitly warns that interactively-authenticated MCP
   servers may be absent in headless/cron runs; skill-bill's MCP is local stdio
   and *should* survive, but this must be **verified per agent**, not assumed. If
   it does not hold, the launcher adapters (subtask 2) must inject skill + MCP
   configuration per launch.
2. **Result channel = the durable workflow store, not stdout.** A top-level
   headless run interleaves prose and tool output; scraping its stdout for a
   `RESULT:` block is brittle. The authoritative outcome is what the run writes
   to the workflow store / manifest via the tools it already calls
   (`feature_implement_finished`, manifest update on commit). Stdout is a
   liveness/diagnostic stream only.
3. **Decomposition quality is an upstream dependency.** Fresh-process-per-subtask
   means **zero shared memory** between subtasks — subtask N succeeds only if its
   spec is genuinely self-contained and its dependency notes are accurate. That
   is the *decomposer's* responsibility (feature-implement planning), not this
   feature's. Clean context makes thin specs *more* likely to fail, not less,
   because there is no conversational context to compensate. The subtask-1
   de-risk therefore runs against a **real, already-decomposed manifest**, not a
   toy, so realistic spec quality is exercised.
4. **Branch idempotency on shared-branch decomposition.** Subtask 1 of a
   decomposed feature creates the feature branch; subtasks 2..N must check out
   the existing branch, not recreate it. The goal loop depends on
   feature-implement's `create_branch` being idempotent for the decomposed case;
   this is asserted, not assumed (subtask 1 scope).
5. **Observability trade-off.** Headless children do not offer the interactive UI
   a human can step into mid-run. The "human present" benefit is preserved only
   if the driver tees each child's live output (subtask 4); otherwise a subtask
   can fail invisibly for minutes.

## Recommended Next Prompt

Run `bill-feature-implement` on:

```text
.feature-specs/SKILL-56-agent-independent-goal-runner/spec.md
```
