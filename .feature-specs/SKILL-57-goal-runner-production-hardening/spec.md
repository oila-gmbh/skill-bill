# SKILL-57 - bill-goal production hardening: durable progress, stall diagnostics, and agent compatibility

Created: 2026-05-30
Status: Draft
Issue key: SKILL-57
Parent: none (top-level follow-up to SKILL-56 live runs)

## Decomposition

This feature is decomposed because it hardens four different seams that must be
verified independently:

1. a workflow-state progress contract that gives child agents a durable
   heartbeat channel;
2. `bill-feature-implement` orchestration changes so long phases emit durable
   progress while subagents are working;
3. goal-runner supervision and status diagnostics that distinguish durable
   progress, file activity, process output, and true stalls;
4. compatibility checks for each supported headless agent, including subagent
   spawning and inline-fallback policy.

Implement on one branch with a commit per subtask:

1. [Durable Workflow Progress Contract](./spec_subtask_1_durable-progress-contract.md)
2. [Feature-Implement Phase Heartbeats](./spec_subtask_2_feature-implement-heartbeats.md)
3. [Goal-Runner Supervision and Diagnostics](./spec_subtask_3_goal-runner-supervision.md)
4. [Headless Agent Compatibility and Fallback Policy](./spec_subtask_4_agent-compatibility-policy.md)

## Sources

- SKILL-56 live `bill-goal` runs on ZERO-85, observed 2026-05-30.
- Fixed runtime issues already found during the live run:
  - stale Codex CLI flags;
  - Codex stdin left open;
  - ambiguous issue-key selection choosing the wrong decomposition lineage;
  - explicit `--subtask-id` ignored;
  - terminal child outcomes not reconciled;
  - parent marking a goal blocked while child workflow remained running;
  - stale parent status showing `preplan` while child workflow was at
    `implement`;
  - missing `attempt_count` guidance in workflow updates;
  - pure timeout behavior that did not account for real implementation work.
- Current runtime follow-up already prototyped in the working tree:
  - durable child workflow progress probe in the parent;
  - parent-owned workflow progress log lines;
  - worktree file-activity liveness fallback;
  - shared launcher path for Claude, Codex, Opencode, and Junie.

## Problem

`bill-goal` now has the right architecture: the CLI driver launches a fresh
headless child agent per subtask, the child runs `bill-feature-implement`, and
the parent reconciles against durable workflow state instead of stdout. The live
ZERO-85 runs exposed the remaining production-hardening gap:

- Long `implement` phases can edit files for minutes before the child persists
  the next workflow artifact. During that window, durable workflow state is
  quiet even though real work is happening.
- File activity is useful liveness evidence but not an authoritative workflow
  outcome. It prevents premature idle kills, but it cannot tell the parent what
  phase/task is actually progressing.
- `bill-feature-implement` subagent spawning can fail in some headless contexts.
  When that happens, the child may continue inline and lose the intended
  phase-level context isolation.
- The status/reporting surface does not yet tell the operator which signal is
  fresh: durable workflow progress, file activity, child output, or no signal.
- Parent projection and child workflow state can temporarily disagree. Operators
  saw terminal child state while `goal status` still showed stale pending/current
  step data.
- Workflow step reporting can regress in confusing ways. A live run showed
  `commit_push -> finish -> audit -> finish`; loop-backs must be explicit rather
  than accidental stale writes.
- Final projection cleanup is part of completion. A run that exits "complete"
  must not leave the checked-in decomposition manifest dirty.
- Default output is too noisy for supervision when huge diffs, prompt bodies, or
  full skill content are printed inline.
- Agent support is still behavioral, not contract-tested. Codex, Claude, Junie,
  and Opencode can have different headless skill and subagent capabilities.

The fix is not to use a larger wall-clock timeout. The fix is to make progress
observable through a durable contract, use file activity only as secondary
liveness, and fail loudly when an agent cannot provide the workflow semantics
the driver depends on.

## Goals

1. Add a durable workflow progress channel that can be written during long
   phases without implying terminal success.
2. Update `bill-feature-implement` so heavy phase subagents receive the
   workflow progress contract and emit progress at phase/task boundaries and
   during long-running work.
3. Make `skill-bill goal` and `skill-bill goal status` report liveness signal
   quality clearly: durable progress, file activity, output-only, or idle.
4. Add supported-agent compatibility checks for headless skill invocation,
   subagent spawning, workflow progress writes, and terminal outcome writes.
5. Define a strict fallback policy: inline work after subagent-spawn failure is
   either explicitly allowed and reported, or fail-fast with a blocked workflow.
6. Make workflow transitions monotonic by default, with explicit persisted
   loop-back metadata for any intentional return to an earlier step.
7. Make completion atomic from an operator point of view: durable terminal
   outcome, parent projection, final manifest write, commit/push state, and
   worktree cleanliness must agree before the runner exits complete.
8. Reduce default output to structured progress events and summaries, with full
   prompts, diffs, and skill bodies only available through explicit debug mode
   or artifact paths.

## Non-Goals

- No background daemon or detached supervisor. `bill-goal` remains a foreground
  CLI driver.
- No terminal success from file activity, stdout, or process exit alone.
  Terminal outcome remains durable workflow state.
- No rewrite of `bill-feature-implement` into a pure runtime engine.
- No requirement that all agents support every optional feature. Unsupported
  agents must fail loudly with actionable diagnostics.
- No schema migration for old durable workflow rows beyond the existing
  loud-fail behavior for invalid records.

## Acceptance Criteria

1. Workflow state exposes a durable progress event/heartbeat channel with typed
   fields including `workflow_id`, `step_id`, `attempt_count`, `source`,
   `kind`, `message`, `sequence`, and timestamp.
2. Progress events are explicitly non-terminal. They reset parent liveness and
   inform status, but cannot mark a subtask complete, failed, or blocked.
3. `bill-feature-implement` continuation payloads and phase subagent briefings
   include the progress-write contract. Long phases write progress at phase
   start, task start/finish, and at a bounded heartbeat interval while work is
   ongoing.
4. The parent goal runner watches durable progress events in addition to the
   existing workflow snapshot token. New progress events reset the idle
   watchdog and print parent-owned progress lines.
5. File activity remains a secondary liveness signal. It can reset the idle
   watchdog and print a distinct "file activity observed" line, but status and
   terminal reconciliation never treat it as workflow progress.
6. `skill-bill goal status <issue_key>` reports the current subtask, current
   workflow step, last durable progress event, last file activity observation,
   last child output timestamp when available, and whether the parent currently
   considers the child active, stalled, blocked, or terminal.
7. A child process that cannot spawn required `bill-feature-implement` phase
   subagents either:
   - records an explicit blocked outcome naming the unsupported capability; or
   - uses an explicitly configured inline fallback and records that degraded
     mode in workflow progress and final outcome.
   Silent inline continuation is not allowed.
8. Compatibility tests or smoke fixtures cover Claude, Codex, Opencode, and
   Junie command shapes and capability reporting. Real-agent manual evidence is
   documented where automated execution cannot be deterministic in CI.
9. Existing SKILL-56 behavior remains intact: fresh child process per subtask,
   durable terminal outcome reconciliation, checked-in manifest recovery,
   explicit `--subtask-id` constraint, and single PR at parent completion.
10. Maintainer validation passes:
    - `skill-bill validate`
    - `(cd runtime-kotlin && ./gradlew check)`
    - `npx --yes agnix --strict .`
    - `scripts/validate_agent_configs`
11. Workflow step updates are monotonic unless the update explicitly declares a
    loop-back reason. Regressive updates without loop-back metadata are rejected
    or ignored with a durable diagnostic.
12. Parent goal status is reconciled from the authoritative child terminal
    outcome before falling back to the manifest projection, so terminal child
    completion cannot appear as pending/in-progress in status.
13. `workflow get <workflow_id>` remains reliable for active child workflows
    throughout a parent run; if the workflow is missing, diagnostics include the
    db path, known parent issue key, known subtask id, and nearest matching
    workflow rows.
14. `bill-goal` completion is not reported until the final generated
    decomposition projection is written and either included in the subtask/final
    commit path or the runner stops with a finalization error.
15. Default foreground output is quiet and structured. It prints bounded event
    lines for phase start/end, heartbeat, persisted artifact, validation
    start/end, stop, commit, and final status. Full prompts, full skill content,
    and large diffs require explicit debug output or artifact links.

## Open Questions

- Should the durable progress channel be a first-class workflow artifact
  (`progress_events`) or a new workflow-store table? Recommendation: start as a
  typed workflow artifact with bounded retention, then promote to a table only
  if query volume or history size requires it.
- Should phase subagents write progress through MCP tools directly, or should
  the feature-implement orchestrator proxy progress for them? Recommendation:
  subagents write directly when the agent runtime exposes tools in subagents;
  otherwise fail capability check and use explicit inline fallback only when
  configured.
- What is the default inline fallback policy? Recommendation: default fail-fast
  for `bill-goal`; allow inline fallback only behind an explicit CLI/config flag
  because it weakens context isolation.
- How much progress history should status show? Recommendation: keep the latest
  event in the status summary and retain a bounded list in the workflow artifact
  for diagnostics.

## Validation Strategy

Each subtask has its own focused tests. The parent feature is complete only when:

- a synthetic long-running child produces durable progress events that keep the
  parent alive without file changes;
- a synthetic file-changing child keeps liveness but never becomes terminal;
- an unsupported subagent-spawn capability produces a blocked workflow outcome;
- `goal status` distinguishes durable progress from file activity;
- the full runtime check and repo validation gates pass.
