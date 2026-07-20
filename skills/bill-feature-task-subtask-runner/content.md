---
internal-for: bill-feature
name: bill-feature-task-subtask-runner
description: Level-1 subtask-agent for bill-feature-goal mode:prose. Runs exactly one subtask's full phase loop (preplan → plan → implement → review → audit → validate → history → commit_push) in a fresh context and returns a bounded terminal outcome. Invoked via the Agent tool by the mode:prose goal orchestrator — not directly via the Skill tool.
---

# Feature Task Subtask Runner

Receive only the parent's current structured agent add-on selection. Verify
its recorded source identities and digests before child execution, then forward
the canonical rendered section unchanged to every phase and review lane. Never
parse raw selection tokens, discover the add-on catalogue, reorder entries, or
silently drop an incompatible selection.

`bill-feature-task-subtask-runner` is the Level-1 subtask-agent type for
`bill-feature-goal mode:prose`. It is not invoked directly via the Skill tool.
The `mode:prose` goal orchestrator spawns it via the Agent tool for each
runnable subtask.

## Role

The subtask-runner receives a self-contained briefing (`issue_key`,
`subtask_id`, `workflow_id`, `spec_path`, `code_review_mode`, optional
`parallel_review_agent`, immutable `review_base_sha`,
`baseline_untracked_paths`, `completed_review_pass_count`,
`reserved_review_pass_number`, and `review_cap_disposition`) and runs the full
phase loop for that subtask by calling
`feature_task_prose_workflow_continue` with the parent `issue_key` and
`subtask_id`. It follows the `bill-feature-task-prose` goal-continuation
contract verbatim: `suppress_pr=true`, no install flows, `commit_push` is the
terminal success signal.

For a fresh child, the supplied values are already-durable selections,
baseline, and pass state; the runner must not default, recompute, or replace
them. On resume, omission reuses them. An explicit incompatible mode or lane
is rejected before child work starts and leaves the durable policy unchanged.

For the initial review pass, call `bill-code-review mode:<code_review_mode>`. For the single possible later pass, call `bill-code-review mode:inline context:feature-remediation`
against only the durable child-owned base-to-current delta. Reconstruct that
scope from the immutable `review_base_sha` through the current committed,
staged, and unstaged changes, plus current untracked paths after subtracting
the baseline untracked inventory. This retains newly child-owned untracked
changes while excluding the pre-existing inventory. Do not review
`origin/main...HEAD`, the feature branch, a merge base, or earlier-sibling
subtask changes.

When `parallel_review_agent` is set, start exactly its second full lane with
the same execution mode and exact prepared delta. Invoke both lanes directly;
never pass a parallel argument into either lane, so neither lane recursively
launches parallel review. The coordinated lanes are exactly one pass. Preserve
the exact scope through repair and audit re-entry; the selected mode is initial-pass policy only. Reserve
before launch; when `reserved_review_pass_number` has no completed durable
output, resume that accounted pass instead of reserving another. Carry forward
`completed_review_pass_count` and `review_cap_disposition`, and never run pass
three. Major findings remain durable evidence and do not prevent advancement.
At a two-pass unresolved Blocker cap, preserve complete location-bearing evidence
in durable artifacts and telemetry, return a blocked result with the compact
path-free status to the parent (subtask id, pass number, verdict, severity,
class/symbol-or-sanitized label, and concise text), and do not advance to audit.

## Return contract

Returns exactly one RESULT block with `subtask_id`, `status`
(`completed|blocked|failed`), `commit_sha`, `workflow_id`, `blocked_reason`,
and `last_resumable_step`. No phase artifacts, implementation details, or
narrative in the return payload.

## Agent definition

The agent body is registered in
`skills/bill-feature-task-prose/native-agents/agents.yaml` under the name
`bill-feature-task-subtask-runner`.

## Audit-first review and findings ledger

Subtasks follow `implement -> audit -> review -> validate`, with review gated on a satisfied audit. Review is delegated first, then inline. A Blocker stops advancement; non-blockers advance and are written to the goal-wide unaddressed-findings ledger. Retrieve its location-bearing detail during or after goal execution with `skill-bill goal findings --issue-key <KEY>`.
