# SKILL-61 - goal observability without nested subagent dependence

Created: 2026-06-01
Status: Draft
Issue key: SKILL-61
Parent: follow-up to SKILL-56/SKILL-58 goal runner production work and the 2026-06-01 operator feedback from Ruslan Batukaev

## Decomposition

This feature is decomposed because useful goal observability spans four
separate boundaries:

1. a stable low-cost observability event contract;
2. runtime-owned orchestration and worker boundary rules;
3. operator-facing CLI/watch/diff UX;
4. validation, documentation, and long-run recovery scenarios.

Implement on one branch with a commit per subtask:

1. [Observability Event Contract](./spec_subtask_1_observability-event-contract.md)
2. [Runtime Supervisor + Worker Boundary](./spec_subtask_2_runtime-supervisor-worker-boundary.md)
3. [CLI Watch, Status, and Diff UX](./spec_subtask_3_cli-watch-status-diff-ux.md)
4. [Validation, Docs, and Operator Scenarios](./spec_subtask_4_validation-docs-operator-scenarios.md)

## Sources

- Telegram discussion with Ruslan Batukaev on 2026-06-01:
  - goal resume worked after process death and rate limits;
  - current status reporting is useful but too opaque during long-running work;
  - the most requested visibility is current activity and diffs;
  - nested subagents would improve native UI inspection if reliable;
  - runtime-backed state is still needed for reliable resume and governance.
- Existing goal-runner direction from SKILL-56/SKILL-58:
  - runtime owns durable decomposition state;
  - foreground goal runs emit structured low-noise progress;
  - raw child output is hidden by default unless debug mode is enabled.
- Repo contracts from `AGENTS.md`:
  - orchestration is the shared source of truth for workflow state and shell
    contracts;
  - durable records and runtime contracts fail loudly on drift;
  - generated artifacts are not committed.

## Problem

`skill-bill goal` is now reliable enough to resume real work, but it remains
hard for an operator to understand what is happening inside a long-running
subtask. The current progress stream can say that work is alive without showing
which files are changing, what the implementer is focused on, or whether the
current diff is moving in the desired direction.

The tempting answer is to run implementation through nested native subagents so
the user can switch into each subthread. That is not a good reliability boundary
for Skill Bill. Hidden nested delegation makes durable state, cancellation,
resume, audit, telemetry, and user-facing progress depend on model behavior
inside a worker thread instead of on the runtime supervisor.

Skill Bill needs better observability while preserving the existing runtime
contract: the runtime owns the goal graph and durable state; agents execute
bounded leaves.

## Goals

1. Add a low-token observability stream for goal runs that reports what changed,
   what phase is active, and when the worker last made durable progress.
2. Keep runtime-owned flat orchestration as the default reliability model.
3. Let workers request additional subtasks through structured output, but keep
   spawn/queue/resume decisions in the runtime supervisor.
4. Provide opt-in diff visibility that reads from the local filesystem/git
   state rather than repeatedly sending full diffs through model context.
5. Make foreground progress useful for experienced operators without flooding
   long runs with raw child output.
6. Preserve existing resume, reset, completion, and projection behavior.

## Non-Goals

- Do not make nested subagents the default execution model.
- Do not require provider-specific subagent thread inspection for correctness.
- Do not stream full diffs through the agent prompt/context on every heartbeat.
- Do not add a detached daemon or background supervisor.
- Do not replace the existing decomposition manifest contract unless a missing
  runtime contract requires a versioned schema addition.
- Do not expose generated provider-specific session files as the product API.

## Target User Experience

During a foreground goal run, the default output stays bounded and structured:

```text
goal=SKILL-61 subtask=2 step=implementation status=active
agent=implementation changed_files=4 diff_stat="+120 -18"
activity="editing goal runner progress event projection"
last_progress=2026-06-01T08:46:43Z liveness=durable_progress
```

Operators can request deeper visibility without changing execution semantics:

```bash
skill-bill goal SKILL-61 --watch
skill-bill goal SKILL-61 --show-diff
skill-bill goal SKILL-61 --diff-interval=5m
skill-bill goal SKILL-61 status --watch
```

If a worker believes the goal should split further, it emits a structured
subtask request. The runtime records that request and either queues a sibling
worker, blocks for operator confirmation, or keeps the work in the current
subtask. From the manifest and workflow store perspective, added work is always
runtime-owned, not hidden under a nested agent.

## Acceptance Criteria

1. Goal runs emit structured observability events separate from raw child
   stdout/stderr.
2. Each observability event includes, when available:
   - issue key;
   - subtask id;
   - workflow step or phase;
   - worker/agent role;
   - liveness class;
   - changed-file count and file list summary;
   - git diff stat;
   - short activity summary;
   - timestamp and monotonic sequence number.
3. Default foreground output remains low-noise and does not print raw child
   output unless explicit debug mode is enabled.
4. Diff visibility is opt-in and sourced from local git/filesystem state, not
   from repeated full-diff model messages.
5. Operators can request current diff stat and selected hunks during active
   runs without corrupting durable workflow state.
6. The runtime supervisor remains the only component that can:
   - mark decomposition subtasks started, blocked, skipped, or complete;
   - spawn or queue additional worker runs;
   - persist resume checkpoints;
   - reconcile terminal child state.
7. Worker agents may emit structured subtask requests, but they cannot create
   hidden nested runtime state. Runtime either records, rejects, queues, or
   asks for confirmation using a typed outcome.
8. Goal status/watch output can show the latest observability event per active
   subtask and a concise changed-files/diff-stat summary.
9. Resume after interruption preserves the last known observability state and
   continues sequence numbering or clearly starts a new run segment.
10. Reset and completion flows reconcile observability artifacts so stale
    in-progress activity is not reported after terminal state.
11. Provider-specific native session paths may be included as optional debug
    metadata, but no correctness path depends on reading them.
12. Any new durable observability artifact or schema follows the runtime
    contract rules in `AGENTS.md`, including version constants, schema parity
    tests, and typed loud-fail errors.
13. Tests cover:
    - default low-noise foreground output;
    - opt-in diff stat/hunk output;
    - worker subtask request handling;
    - resume after interruption;
    - stale observability cleanup on reset/completion.
14. Documentation explains why Skill Bill uses runtime-owned flat worker
    orchestration rather than nested subagents for reliability.
15. Maintainer validation passes:
    - `skill-bill validate`
    - `(cd runtime-kotlin && ./gradlew check)`
    - `npx --yes agnix --strict .`
    - `scripts/validate_agent_configs`

## Design Notes

- Treat observability as a runtime event stream backed by durable workflow
  state and local repo inspection. It should be cheap to emit and cheap to
  render.
- Keep the model prompt focused on task execution. File lists and diff stats can
  be computed by adapters without spending model tokens.
- Nested native subagents can remain an optional provider convenience for manual
  inspection, but they must not become the source of truth for decomposition,
  resume, or completion.
- The useful abstraction is not "worker can spawn child workers"; it is "worker
  can request more work and the runtime decides."
- Prefer line-oriented, machine-readable events that are also acceptable to read
  in a terminal.

## Validation Strategy

- Unit tests for event creation, sequence ordering, and stale-state
  reconciliation.
- CLI/runtime tests for foreground output with default, `--watch`,
  `--show-diff`, and debug-child-output modes.
- Workflow tests proving resume/reset/completion keep observability state
  consistent with the decomposition manifest.
- Adapter tests for git diff stat and file-list collection with dirty,
  clean, renamed, and deleted files.
- Documentation/catalog validation as part of the standard maintainer command
  set.

## Open Questions

- Should observability events be part of workflow state, a sidecar artifact, or
  both with one authoritative source?
- Should `--watch` be a modifier on `goal status`, the default foreground run,
  or both?
- What is the right default cadence for diff stat collection so it is useful
  but not noisy on six-hour runs?
- Should runtime allow dynamic sibling subtask insertion, or should worker
  subtask requests block for a follow-up spec update first?
