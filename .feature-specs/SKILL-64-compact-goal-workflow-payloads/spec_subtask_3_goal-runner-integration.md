---
status: Draft
---

# SKILL-64 Subtask 3 - Goal Runner Integration

Parent spec: [.feature-specs/SKILL-64-compact-goal-workflow-payloads/spec.md](./spec.md)
Issue key: SKILL-64

## Scope

Wire the compact continuation contract into `skill-bill goal` child launches,
retry/resume behavior, token/session accounting, append-only attempt/event
recording, and foreground monitoring policy. Goal child prompts should treat the
compact continuation payload as the normal activation contract and should fetch
full read-only state only when the compact payload explicitly says required
context is omitted or truncated.

This subtask also adds review/fix-loop guidance so successful specialist review
results are not repeated inside the same subtask fix loop unless relevant
changed files or risk areas changed, and it introduces a quiet or
transition-only monitoring mode for long goal runs.

## Acceptance Criteria

1. Goal child launch prompts still execute `workflow continue` first.
2. The prompt language expects compact continuation output and no longer implies
   that the child should inspect or reason over full workflow JSON by default.
3. If compact continuation reports missing/truncated required context, the child
   is directed to the read-only full-state command rather than calling
   `continue` again for inspection.
4. Retry/resume loops preserve workflow-state authority while avoiding repeated
   injection of prior plans, reviews, implementation summaries, and unrelated
   decomposition artifacts.
5. Review/fix-loop guidance records when prior clean specialist results can be
   reused inside the same subtask fix loop and when they must be rerun because
   relevant files or risks changed.
6. Goal runs persist best-effort child-session accounting when available,
   including child session path or id, subtask id, phase, model, input tokens,
   cached input tokens, output tokens, reasoning output tokens, and final
   status.
7. Missing or unparsable provider token logs do not fail an otherwise valid goal
   run; the run records that accounting was unavailable.
8. A quiet or transition-only goal monitor mode reports subtask start, phase
   transition, blocked/failed, completion, and sparse liveness events without
   appending every heartbeat to the foreground assistant-visible transcript.
9. Debug/raw child output remains explicit opt-in.
10. Goal runner persists an append-only attempt/event ledger entry for each
   child activation, resume/retry, terminal done check, policy block, timeout,
   interruption, and final reconciled outcome.
11. Attempt/event ledger entries include, when available, issue key, subtask id,
   action, previous workflow id/status/step/blocked reason/latest liveness,
   launch outcome, timeout/interruption flags, child session path or id, final
   reconciled result, stop reason, and timestamps.
12. Continue calls used only for diagnostic/manual inspection are either
   discouraged in prompt/docs or explicitly classified so they are not mistaken
   for retries.
13. Goal child prompts and phase guidance bound broad tool output by default and
   prefer follow-up scoped reads over dumping large command output into model
   history.
14. Tests cover Codex child command construction and any other supported agent
   command builders touched by the change.
15. Existing status/watch/debug-child-output behavior remains unchanged unless
   explicitly covered by this spec.

## Non-Goals

- Do not add `--agent-reasoning-effort`.
- Do not change agent selection semantics.
- Do not make nested subagents the reliability boundary.
- Do not remove, downgrade, or make optional full per-subtask review and
  validation.
- Do not suppress a required review specialist when relevant risk changed.
- Do not make goal completion depend on a provider-private token log format.
- Do not replace full per-subtask review/validation behavior in this subtask.
- Do not require provider JSONL scraping to classify retries or explain attempt
  history.

## Dependency Notes

Depends on:

- Subtask 1 for compact continuation fields.
- Subtask 2 if child behavior relies on compact workflow update
  acknowledgements.

## Validation Strategy

Add launcher tests for prompt text and command construction. Add goal-runner
tests or fixtures for retry/resume flows where continuation output is compact
and full-state inspection is only read-only and opt-in. Add tests for
transition-only monitor output and best-effort token accounting with available,
missing, and malformed provider accounting data. Add tests for attempt/event
ledger entries covering first start, resume after `no_terminal_store_outcome`,
timeout, policy-blocked before child launch, terminal `done` check, and
diagnostic/manual inspection classification.

## Next Path

Run bill-feature-task on spec_subtask_4_validation-docs-payload-budgets.md.

## Spec Path

.feature-specs/SKILL-64-compact-goal-workflow-payloads/spec_subtask_3_goal-runner-integration.md
