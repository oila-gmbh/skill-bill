---
status: Complete
---

# SKILL-64 Subtask 3 - Goal Runner Integration

Parent spec: [.feature-specs/SKILL-64-compact-goal-workflow-payloads/spec.md](./spec.md)
Issue key: SKILL-64

## Scope

Wire the compact continuation contract into `skill-bill goal` child launches,
invoking-agent-based child agent defaulting, retry/resume behavior,
token/session accounting, append-only attempt/event recording, and foreground
monitoring policy. Goal child prompts should treat the
compact continuation payload as the normal activation contract and should fetch
full read-only state only when the compact payload explicitly says required
context is omitted or truncated.

This subtask also adds review/fix-loop guidance so successful specialist review
results are not repeated inside the same subtask fix loop unless relevant
changed files or risk areas changed, and it introduces a quiet or
transition-only monitoring mode for long goal runs, including a
machine-consumable transition event stream that an orchestrating agent can watch
without parsing or deduplicating per-tick heartbeats.

It also makes goal-runner liveness and timeout decisions deterministic: the
worker declares what it is doing as durable, monotonic progress events, and the
supervisor decides liveness and timeout outcomes purely from those declared
facts and process liveness rather than inferring activity from source-file
mtimes, stdout chatter, or token movement. A child inside a declared, still-live
long operation (such as `gradlew check` or a test run) must never be classified
idle or killed by the progress-idle timeout.

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
16. The transition-only monitor emits a machine-consumable event line on each
   meaningful change only — subtask change, phase/step transition, blocked,
   failed, completion, and terminal reconciliation — distinct from the per-tick
   `heartbeat` and `goal_observability` lines. The line uses a stable prefix
   (`goal_event:`) and stable `key=value` field keys (at minimum issue key,
   subtask id, previous and current step/status, event kind, and a monotonic
   sequence number) so an orchestrating agent can consume transitions without
   deduplicating heartbeats or scraping free-form text. The schema is documented.
17. The `bill-feature-goal` skill guidance documents the orchestrator watch
   pattern: long goal runs may exceed a foreground command timeout, so the driver
   should be run detached and progress consumed through the read-only
   `goal status`/`goal watch` commands or the `goal_event:` transition stream.
   Raw per-tick heartbeats must not be relayed verbatim into the parent
   assistant-visible transcript.
18. Goal child runs default to the agent that invoked the goal rather than a
   hardcoded `codex` fallback. The child agent is resolved in order: explicit
   `--agent-override`, then explicit `--agent`, then `SKILL_BILL_AGENT`, then a
   best-effort detection of the invoking agent's execution context, and only then
   a documented last-resort default. Launching from Claude Code resolves to
   `claude`, from Codex to `codex`, and from OpenCode to `opencode`.
   `--agent-override` continues to win when set.
19. The `bill-feature-goal` skill is updated to always pass `--agent` set to the
   agent currently executing the skill, so the invoking agent — not a hardcoded
   default — drives child subtask runs unless the user explicitly chooses an
   override.
20. Goal-runner liveness and idle-timeout decisions are computed deterministically
   from explicit, durable, monotonic progress events plus child/process liveness.
   Source-file mtimes, stdout chatter, and token movement are not authoritative
   liveness signals; if retained at all they are non-authoritative hints only.
21. The worker emits durable, timestamped, monotonically-sequenced progress
   events at each phase boundary (`phase_started`, `phase_completed`) and around
   long operations (`operation_started`, `operation_heartbeat`,
   `operation_completed`), each carrying at least phase/step, operation name and
   kind, an `expected_long` flag, a process-alive signal, and outcome.
22. A declared long operation suspends the progress-idle timeout (or replaces it
   with a documented operation-specific deadline) while the operation is active
   and its process/heartbeat is alive. A child inside a declared, live long
   operation is classified `working`, is never classified idle, and is never
   killed by the progress-idle timeout before its operation deadline or the
   subtask wall-clock cap.
23. Liveness is a documented, testable taxonomy derived only from declared facts:
   `working` (a declared operation is active and alive), `progressing` (a durable
   workflow event advanced within the interval), `idle` (no active declared
   operation and no durable advance within the idle window — the only state that
   arms the idle timeout), and `unresponsive` (the child/process is gone or a
   declared operation overran its deadline — a deterministic block, not an
   inference).
24. Heartbeat, `goal_observability:`, and `goal_event:` surfaces report phase/step
   from the authoritative durable workflow store, never a stale local default,
   eliminating the mislabel where a child resumed at one step is reported under an
   earlier phase.
25. Known long-running phase commands (for example `gradlew check`, test runs) are
   wrapped so the `operation_started`/`operation_heartbeat`/`operation_completed`
   events are emitted automatically from process lifecycle, so "alive but quiet"
   is provably distinguished from "hung" without the phase agent having to
   self-report.

## Non-Goals

- Do not add `--agent-reasoning-effort`.
- Do not add new agent ids or change how `--agent-override` wins. The only
  agent-selection change is defaulting the child agent to the invoking agent
  instead of a hardcoded fallback (AC 18-19).
- Do not make nested subagents the reliability boundary.
- Do not remove, downgrade, or make optional full per-subtask review and
  validation.
- Do not suppress a required review specialist when relevant risk changed.
- Do not make goal completion depend on a provider-private token log format.
- Do not replace full per-subtask review/validation behavior in this subtask.
- Do not require provider JSONL scraping to classify retries or explain attempt
  history.
- Do not rely on source-file mtimes, stdout chatter, or token movement as the
  authoritative liveness signal.
- Do not kill a child that is inside a declared, still-live long operation solely
  because no workflow-state write has occurred.

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
missing, and malformed provider accounting data. Add a test asserting the stable
`goal_event:` transition schema (stable prefix and required field keys) is
emitted only on meaningful change and not once per heartbeat. Add tests for
invoking-agent child-agent defaulting that assert resolution order
(`--agent-override` > `--agent` > `SKILL_BILL_AGENT` > detected context >
documented last-resort default) and that no silent hardcoded `codex` default is
used when an invoking agent is determinable. Add deterministic-liveness tests:
each liveness state (`working`, `progressing`, `idle`, `unresponsive`) maps to a
documented arming/disarming of the idle timeout; a declared long operation with
no durable workflow write is classified `working` and survives past the former
idle window up to its operation deadline or the wall-clock cap; an overrun or a
dead child process produces a deterministic `unresponsive` block; and every
progress surface reports the authoritative durable step with no stale phase
label. Add tests for attempt/event ledger entries covering first start, resume
after `no_terminal_store_outcome`, timeout, policy-blocked before child launch,
terminal `done` check, and diagnostic/manual inspection classification.

## Next Path

Run bill-feature-task on spec_subtask_4_validation-docs-payload-budgets.md.

## Spec Path

.feature-specs/SKILL-64-compact-goal-workflow-payloads/spec_subtask_3_goal-runner-integration.md
