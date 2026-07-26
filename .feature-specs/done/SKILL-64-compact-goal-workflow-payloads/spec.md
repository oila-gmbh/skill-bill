# SKILL-64 - compact goal workflow payloads

Created: 2026-06-01
Status: Draft
Issue key: SKILL-64
Parent: follow-up to SKILL-56/SKILL-58/SKILL-61 goal runner work and the RDN-24/SKILL-63 token-use analyses

## Decomposition

This feature is decomposed because the token reduction spans separate runtime
contracts and adapter surfaces:

1. the workflow continuation payload contract used by goal child sessions;
2. CLI/MCP workflow update response shapes;
3. goal-runner child launch wiring and retry behavior;
4. regression tests, operator docs, and payload-size guardrails.

Implement on one branch with a commit per subtask:

1. [Compact Continuation Contract](./spec_subtask_1_compact-continuation-contract.md)
2. [Compact Workflow Update Acknowledgements](./spec_subtask_2_compact-workflow-update-acks.md)
3. [Goal Runner Integration](./spec_subtask_3_goal-runner-integration.md)
4. [Validation, Docs, and Payload Budgets](./spec_subtask_4_validation-docs-payload-budgets.md)

## Sources

- RDN-24 local session/token analysis discussed on 2026-06-01:
  - most displayed token volume was cached input, but the workflow remained
    genuinely heavy;
  - `workflow continue` was called once per child run in clean cases, but was
    called 22 times during RDN-24 retry/resume loops;
  - one subtask continuation response was about 40 KB and around 12k tool-output
    tokens because it included recovered artifacts and a continuation prompt;
  - read-only `workflow show` for the blocked workflow was about 34 KB;
  - Skill Bill MCP workflow updates return large full workflow snapshots.
- SKILL-63 local session/token analysis discussed on 2026-06-01:
  - the bounded goal run used 26 sessions and about 1,047 model calls;
  - provider-reported total tokens were about 103.6M, with about 98.4M cached
    input, about 4.85M uncached input, about 356k output, and about 80k
    reasoning output;
  - the largest contributors were full `bill-feature-task` implementation
    sessions, foreground/orchestrator sessions, quality-check phases,
    completeness audit phases, and repeated pre-planning;
  - fresh child sessions repeat fixed base context, repo instructions, skill
    context, and phase briefings, often starting around 20k input tokens before
    work begins;
  - long implementation sessions accumulate tool outputs, diffs, file reads,
    and edits across 66-101 model calls;
  - parent foreground monitoring spent about 6.3M reported tokens mostly
    polling and narrating heartbeats;
  - tool output volume was significant, with about 1,640 tool outputs and
    roughly 10.7M characters persisted into logs.
- RDN-24 retry/continue reconstruction discussed on 2026-06-01:
  - the 22 `workflow continue RDN-24` calls were a mix of normal first-entry
    activation, terminal `done` checks, resume/retry activation, and a few
    diagnostic/manual inspection calls;
  - actual retry reasons had to be inferred from previous stop/block state,
    Codex JSONL function-call logs, and workflow rows;
  - recurring stop classes included `no_terminal_store_outcome`, timeout before
    terminal outcome, and pre-run policy blocking when same-branch mode resolved
    to protected branch `main`;
  - Skill Bill had enough runtime information to explain the attempts, but did
    not persist a first-class append-only attempt ledger.
- Current runtime behavior:
  - `workflow continue` activates a resumable workflow and is not read-only;
  - `workflow show` is the correct read-only inspection path;
  - `WorkflowEngine.continueMap` currently nests `resumeMap`, which nests the
    full `snapshotMap`, including all workflow artifacts;
  - CLI and MCP workflow update/open/get success responses currently map through
    full snapshot payloads.
- Repo contracts from `AGENTS.md`:
  - workflow state and routing contracts live under `orchestration/`;
  - runtime contract drift fails loudly with typed errors;
  - generated artifacts are not committed.

## Problem

`skill-bill goal` now keeps long decomposed work resumable, but its workflow
payloads are too expensive for repeated agent boundaries. The largest Skill
Bill-side inefficiency is not a heartbeat loop. It is that activation and
workflow update surfaces routinely echo full workflow state, including large
artifacts that the next model turn often does not need.

In clean execution, `workflow continue` should usually run once per child
session. During real retry, review, and fix loops, it can run many times. Each
call currently returns a payload close to read-only `show` plus continuation
fields. MCP workflow updates have a similar problem: they return full workflow
snapshots even when the caller only needs confirmation that a step/artifact
write succeeded.

Skill Bill needs payload discipline and cheaper supervision: mutating
operations should return compact operation-specific contracts, read-only
inspection should remain available through explicit `show`/status commands, and
foreground goal monitoring should avoid turning every heartbeat into persistent
assistant context. It also needs an auditable attempt trail so operators can
understand why goal children started, resumed, retried, timed out, or stopped
without scraping provider session logs.

## Goals

1. Keep `workflow continue` mutating/activating, but make its default response
   compact enough to feed directly into a child agent.
2. Preserve `workflow show` as the explicit read-only full inspection path.
3. Make workflow update MCP calls return compact acknowledgements by default.
4. Avoid adding model reasoning-effort overrides as a cost-control mechanism.
5. Keep all compact/full payload shapes explicit, tested, and documented.
6. Reduce repeated review/fix loop token amplification without weakening
   workflow-state authority or loud-fail contracts.
7. Persist enough goal-run token/session accounting to diagnose future cost
   without scraping provider JSONL logs.
8. Add a quiet or transition-only goal monitoring mode so long runs report
   important state changes without repeatedly appending heartbeat narration to
   the parent session.
9. Persist an append-only goal attempt/event ledger that explains each child
   activation, resume, retry, terminal check, timeout, policy block, and final
   reconciled outcome.

## Non-Goals

- Do not add `--agent-reasoning-effort` or provider-specific reasoning controls.
- Do not make `workflow continue` read-only.
- Do not remove full workflow inspection; full state remains available through
  explicit read-only commands.
- Do not hide malformed workflow state behind truncation or best-effort parsing.
- Do not change the decomposition manifest execution model.
- Do not rely on cached-input billing behavior as the product solution.
- Do not remove, downgrade, or make optional the existing full per-subtask
  review and validation gates. They are part of the value of goal execution.

## Target User Experience

Goal child sessions start from a compact activation payload:

```bash
skill-bill workflow continue SKILL-64 --subtask-id 2 --format json
```

The response includes the continuation entry prompt, current step, status,
required artifact keys, compact artifact summaries, and explicit instructions
for fetching full read-only state only when needed. It does not inline the full
workflow snapshot or every historical artifact by default.

Operators still inspect complete workflow state with read-only commands:

```bash
skill-bill workflow list --limit 10 --format json
skill-bill workflow show wfl-20260601-153215-he7y --format json
```

Workflow update tools acknowledge writes without echoing every artifact:

```json
{
  "status": "ok",
  "workflow_id": "wfl-...",
  "workflow_status": "running",
  "current_step_id": "review",
  "updated_artifact_keys": ["review_result"],
  "db_path": "..."
}
```

Goal runs can also be monitored without turning every heartbeat into chat
history:

```bash
skill-bill goal SKILL-64 --monitor-mode transitions
```

The transition-only mode reports subtask starts, phase transitions, blocked or
failed states, completion, and sparse liveness when needed. It does not expose
raw child output unless debug output is explicitly enabled.

## Acceptance Criteria

1. `workflow continue` remains the mutating activation command and is documented
   as unsafe for read-only inspection.
2. Default continuation output no longer embeds the full workflow snapshot.
3. Continuation output includes enough information for a goal child to resume:
   issue key when available, workflow id, continue status, resume step, step
   directive, continuation prompt, required artifact keys, available artifact
   keys, compact current-step artifact summaries, and full-inspection guidance.
4. Large recovered artifacts are summarized or referenced instead of blindly
   inlined. Any truncation or omission is explicit in the JSON payload.
5. A full continuation/debug payload remains available only through an explicit
   opt-in flag or read-only inspection path.
6. `workflow show` remains read-only and continues to expose full workflow state
   for operators and diagnostics.
7. MCP workflow update success responses return compact acknowledgements by
   default, including workflow id, status, current step, updated step ids,
   updated artifact keys, and db path.
8. Any caller that still needs a full snapshot must request it explicitly or
   call the read-only get/show path.
9. CLI and MCP mappers have matching compact/full semantics, with deliberate
   golden updates.
10. Goal runner child launch prompts use the compact continuation contract and
    do not ask child agents to inspect full JSON unless the compact payload
    reports missing or truncated required context.
11. Retry/resume loops do not repeatedly inject full prior plans, reviews,
    implementation summaries, and decomposition state into child context.
12. Review fanout reuse guidance is added only inside review/fix loops so a
    clean specialist result is not repeated when its relevant files and risk
    areas are unchanged. Every subtask still receives the normal full review and
    validation gates.
13. Goal runs persist child-session accounting when available, including child
    session path or id, subtask id, phase, model, input tokens, cached input
    tokens, output tokens, reasoning output tokens, and final status.
14. Goal runs persist an append-only attempt/event ledger. Each entry includes,
    when available:
    - issue key;
    - subtask id;
    - action such as `start`, `resume`, `retry`, `done_check`,
      `policy_blocked`, or `diagnostic`;
    - previous workflow id, status, step, blocked reason, and latest liveness;
    - launch outcome;
    - timeout/interruption flags;
    - child session path or id;
    - final reconciled result and stop reason;
    - timestamps.
15. Goal status or a read-only inspection command can summarize the attempt
    ledger well enough to answer why a subtask retried or stopped without
    requiring provider JSONL scraping.
16. Goal output supports a quiet or transition-only monitor mode that reports
    meaningful state transitions without emitting every heartbeat to the parent
    assistant-visible transcript.
17. Phase prompts or launcher guidance bound broad tool output by default and
    prefer scoped follow-up reads for large `rg`, Gradle, test, git diff, and
    log outputs.
18. Payload-size regression tests cover representative large workflow artifacts
    and fail when compact continuation/update payloads exceed the documented
    budget without explicit debug/full opt-in.
19. Existing loud-fail validation behavior is preserved for malformed workflow
    snapshots and invalid update payloads.
20. Maintainer validation passes:
    - `skill-bill validate`
    - `(cd runtime-kotlin && ./gradlew check)`
    - `npx --yes agnix --strict .`
    - `scripts/validate_agent_configs`

## Design Notes

- Treat full workflow state as durable data, not as routine model context.
- Keep compact payloads semantic rather than purely byte-truncated. A child
  should know what was omitted and how to fetch it.
- Prefer explicit fields such as `artifact_summaries`,
  `omitted_artifact_keys`, and `full_state_command` over ambiguous truncation.
- `workflow continue` should return a continuation contract, not a disguised
  `show` response.
- Workflow update acknowledgements should be operation results. Snapshot reads
  belong to read-only get/show commands.
- Do not solve this by exposing reasoning-effort knobs. The product should spend
  fewer tokens by default.
- Token accounting should be best-effort where provider logs are available, but
  it must not make goal completion depend on parsing a provider-private log
  format.
- The attempt ledger should use runtime-owned facts first. Provider session ids
  can enrich the record, but retry classification should not depend on provider
  JSONL scraping.
- Transition-only monitoring should be an output policy, not a new execution
  model. Durable workflow state remains authoritative.

## Validation Strategy

- Unit tests for compact continuation projection from large workflow snapshots.
- CLI and MCP golden tests for compact default and explicit full/debug modes.
- Goal-runner launcher tests proving child prompts call the compact activation
  path and preserve `continue` mutating semantics.
- Regression tests with large plan/review/artifact payloads to enforce byte
  budgets and truncation metadata.
- Workflow service tests proving update acknowledgements reflect persisted state
  without serializing full artifacts.
- Goal-runner tests for transition-only monitoring and best-effort token/session
  accounting.
- Goal-runner tests for attempt ledger entries covering first start, resume,
  timeout, no-terminal-store-outcome, policy-blocked, terminal done check, and
  diagnostic/read-only inspection behavior.
- Documentation/catalog validation through the standard maintainer command set.

## Open Questions

- Should the full continuation payload be exposed by `--include-snapshot`,
  `--debug-full`, or only by a separate read-only `show` command?
- What compact payload byte budget should be enforced for representative goal
  child starts?
- Should artifact summaries be generated generically by JSON shape/size, or
  should known workflow artifacts such as `plan`, `review_result`, and
  `audit_report` get artifact-specific summaries?
- Should compact update acknowledgements become default immediately, or should
  MCP support a temporary compatibility mode for older installed agents?
- Should shared goal-level preplanning become a follow-up feature that prepares
  one compact parent context slice for all subtasks?
- Should a minimal child-launch profile reduce fixed prompt surface for backend
  runtime work, or is that too provider-specific for the current goal runner?
- How should Skill Bill detect that a prior clean specialist result is still
  valid inside the same subtask fix loop, without weakening the required full
  review and validation gates for each subtask?
- Should the attempt ledger be stored as a workflow artifact, a sidecar table,
  or both with one authoritative source?
