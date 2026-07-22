# SKILL-137 Subtask 5 - Prose Workflow and Continuation Parity

Parent spec: [.feature-specs/SKILL-137-phase-context-minimization/spec.md](./spec.md)
Issue key: SKILL-137

## Scope

Bring the in-session prose feature-task workflow onto the same least-context contracts as runtime mode. Correct its stale workflow graph, replace whole-artifact briefing templates and continuation dependencies with named receipts/projections, and make fresh and resumed phase briefings semantically identical.

## Acceptance Criteria

1. `FeatureImplementWorkflowDefinition` and governed prose `content.md` agree on `assess -> create_branch -> preplan -> plan -> implement -> audit -> review -> validate -> write_history -> commit_push -> pr_description -> finish`.
2. The durable prose dependency graph no longer declares `review_result` as an audit input, no longer orders review before audit, and cannot resume audit from review output.
3. Prose canonical artifacts use the shared semantic contracts: preplan digest, executable plan, implementation receipt, audit clearance/repair request, review receipt/repair request, validation receipt, change/history/commit/PR receipts.
4. Implementation continuation receives the executable plan and phase-local contract only; it does not receive preplan digest or complete assessment artifacts merely because they exist.
5. The completeness-audit briefing embeds the plan commitment and implementation receipt rather than the full implementation return JSON, and always supplies an exact diff/checkpoint for every feature size.
6. The initial review briefing uses acceptance criteria, exact diff scope, review policy, and audit clearance; it does not use implementation summary as review evidence.
7. Review and audit fix briefings use the bounded repair requests defined by subtask 3 and exclude unrelated/full reports.
8. Validation, history, commit, and PR prose briefings use the finalization projections defined by subtask 4. PR description no longer embeds the full implementation return JSON.
9. Review and validation telemetry payloads and progress-write failures are stored separately from domain artifacts and are not included in later briefings.
10. `workflow continue` returns the exact current-step projection inline when it fits its contract budget or a declared reference when it does not. It does not advise the phase agent to fetch the complete artifact map.
11. `workflow show` may retain operator-facing private diagnostic state, but continuation prompts label it non-phase context and never paste it automatically.
12. Fresh launch and continuation use one shared projection assembler or shared contract logic; tests compare their semantic payloads for every phase.
13. Backward-loop continuation retains only the latest valid repair request/receipt/checkpoint and never reconstructs context from chat history or sibling-subtask artifacts.
14. Prose workflow contract versioning loud-fails stale durable records whose graph or artifact semantics cannot be interpreted safely, with a restart/migration message.
15. Governed `content.md` briefing principles explicitly state that self-contained means complete for the declared phase contract, not inclusion of all prior artifacts.
16. Prose telemetry token estimation measures the actual projected briefing/result boundaries and does not require retaining raw prompts or full phase responses downstream.
17. Workflow-engine, MCP continuation, golden, content validation, fresh/resume, retry, audit-gap, review-fix, goal-child, and terminal-path tests pass.

## Non-Goals

- Converting prose mode to the external runtime process model.
- Removing operator access to full durable state through explicit diagnostics.
- Changing phase ownership between orchestrator and subagents except where needed to use shared projections.
- Changing prose telemetry event names or workflow identity strings.

## Dependency Notes

Depends on: 2, 3, 4.

The prose flow adopts the complete forward/backward and finalization projection set delivered by those subtasks.

## Validation Strategy

- Workflow-definition order/dependency tests and stale-order rejection tests.
- Briefing snapshots for every prose phase and both repair loops.
- Fresh-versus-continue semantic equivalence tests.
- Negative assertions for complete artifact maps, implementation JSON, review reports, telemetry, and raw output.
- MCP/golden updates and goal-child isolation tests.
- `skill-bill validate --skill-name bill-feature-task-prose` plus focused Gradle tests.

## Next Path

Continue with subtask 8 after subtasks 6 and 7 are also complete.

## Spec Path

.feature-specs/SKILL-137-phase-context-minimization/spec_subtask_5_prose-workflow-and-continuation-parity.md
