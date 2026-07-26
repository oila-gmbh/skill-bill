# SKILL-103 — goal status attribution + zcode prose-only runtime refusal

## Status

- Status: Complete
- Agent: opencode

## Summary

Two display/attribution fixes and one runtime eligibility correction from the first real zcode goal run (SKILL-102, 2026-07-04). zcode must be treated like opencode for feature-task and goal runtime mode in this harness: prose is the supported path, and explicit runtime mode refuses loudly before launching.

1. **`goal status` must report the run's agent, not the caller's.** `GoalRunnerStatusService.status()` currently derives `active_agent` from the status invocation's own resolution chain (`--agent-override`/`--agent`/`SKILL_BILL_AGENT`/detection/default), so it can report an agent that never ran. It printed `active_agent: codex` while the SKILL-102 children demonstrably ran zcode (`resolved_agent_id: zcode` in the persisted phase ledger).
2. **zcode sessions are undetectable.** SKILL-100 added the `ZCODE` enum entries but skipped the `INVOKING_AGENT_CONTEXT_SIGNALS` entry recommended by `.feature-specs/zcode-agent-support-investigation.md` §2. Any skill-bill invocation from a zcode session that omits `--agent` falls through detection to the last-resort default `codex` — which is both the second half of the observed display bug (the flag-less status call defaulted to codex) and a live mis-attribution hazard for flag-less status/run requests from zcode.
3. **zcode runtime children are non-viable in this harness.** Foreground runtime runs exceed the harness's foreground Bash ceiling for normal subtasks, while background/detached runs spawn zcode child CLI processes that produce zero output and are killed as unresponsive. This is an execution-vehicle incompatibility, not a feature implementation defect. Runtime mode must refuse for zcode instead of entering a bounded retry loop that cannot make progress.

## Background

The SKILL-102 goal run was launched from a zcode session with `--agent zcode`. The children resolved and ran zcode correctly, but a flag-less `goal status` from the same session showed `active_agent: codex`, sending the investigation down a false "runtime ignored my flag" path. Root causes, confirmed in code:

- `GoalRunnerStatusService.kt` resolves `effectiveAgent` from the status request and passes it to the projection as `activeAgent` — persisted run state is never consulted for this field, even though it exists (`feature_task_runtime_phase_ledger[].resolved_agent_id` per phase in the child workflow artifacts, and `finalizing_agent_id` / `participating_agent_ids` on goal outcome records and `goal_subtask_events`).
- `INVOKING_AGENT_CONTEXT_SIGNALS` in `InstallModels.kt` has entries for claude/codex/opencode only. zcode sets unambiguous markers in every process it spawns (`ZCODE_APP_VERSION`, `ZCODE_BASE_URL`), documented in the investigation briefing.

## Intended Outcome

`goal status` tells the truth about which agent is/was executing, sourced from persisted run state, regardless of who asks. A zcode session invoking skill-bill without `--agent` resolves to zcode everywhere the detection chain is consulted. Feature-task and goal runtime mode then refuse zcode at the same boundaries as opencode, while prose mode remains the supported execution path.

## Acceptance Criteria

1. `goal status` no longer sources `active_agent` from the status caller's resolution chain. It reports, in order: the current subtask's active workflow agent from the persisted phase ledger (`resolved_agent_id` of the latest phase), else the subtask's recorded finalizing/participating agent from the goal outcome records, else omits the field. It never falls back to the caller's `--agent`/env/detected/default value, and never invents a value when nothing is persisted.
2. A regression test reproduces the observed failure: a goal run persisted with zcode phase records, queried by a status call whose own resolution chain would yield codex, reports `active_agent: zcode`; a run with no persisted agent omits the field.
3. `INVOKING_AGENT_CONTEXT_SIGNALS` gains a zcode entry keyed on `ZCODE_APP_VERSION` (with `ZCODE_BASE_URL` as a secondary marker), ordered so existing claude/codex/opencode detection is unchanged; a unit test covers zcode-marker detection and precedence.
4. With the signal in place, a flag-less invocation from a zcode-marked environment resolves the invoking agent to zcode on every path that uses the detection chain (goal run, goal status, feature-task CLI), covered by at least one test at the resolution seam.
5. zcode is prose-only for feature-task and goal execution, matching opencode's safety shape:
   - `bill-feature-task` and `bill-feature-goal` resolve omitted mode to `prose` when the current agent is zcode.
   - Explicit `mode:runtime` on zcode refuses before delegation/launch with an actionable message pointing to prose.
   - Direct `bill-feature-task-runtime` invocation refuses zcode before the confirmation gate.
   - The `skill-bill feature-task` and `skill-bill goal` CLIs refuse whenever the resolved runtime agent is zcode by any route (`--agent`, detected invoking agent, `SKILL_BILL_AGENT`, `--agent-override`, and per-phase/parallel review agent overrides where applicable).
6. Runtime refusal is unbypassable at the spawn boundary: zcode is added to the shared runtime-refused agent policy and the zcode headless runtime adapter is removed or disabled so no code path can spawn zcode for a runtime phase if an outer guard is bypassed.
7. The refusal message documents the observed harness failure mode: foreground runtime is too long for the Bash ceiling, and detached child zcode emits no harvestable output before the supervisor kills it as unresponsive. The message must direct users to `bill-feature-task-prose` for a single feature task or `bill-feature-goal mode:prose` for decomposed work.
8. opencode's existing refusal behavior/message remains valid and unchanged except for any shared wording/refactoring needed to support both refused agents.
9. The zcode headless credential note, if kept, is demoted from a runtime enablement requirement to historical troubleshooting context; it must not imply zcode runtime mode is supported.
10. Tests updated and `./gradlew check` passes.

## Non-Goals

- Making zcode runtime mode work in this harness.
- Plumbing or validating `ANTHROPIC_API_KEY` at runtime (preflight checks, env forwarding changes).
- Fixing agent attribution in other status surfaces (`feature-task-runtime status`, desktop) — follow-up if the same caller-resolution pattern exists there.
- Changing detection for claude/codex/opencode or any resolution precedence beyond adding the zcode entry.

## Constraints

- The status fix must not invent data: when no agent is persisted, omit rather than guess.
- Signal ordering: the new zcode entry must not shadow existing markers (zcode child processes of other agents, or vice versa, keep resolving as today for claude/codex/opencode markers).
- Keep the change surface minimal — this is an attribution fix, not a status-projection redesign.

## Affected Areas (implementation guidance, not binding)

- `runtime-application/.../goalrunner/GoalRunnerStatusService.kt` (`effectiveAgent` at lines ~35-57), `GoalRunnerStatusProjector`, and the outcome/progress stores that already load the current subtask's workflow — extend the progress projection to carry the persisted agent rather than adding a second DB read.
- `runtime-domain/.../install/model/InstallModels.kt` — `INVOKING_AGENT_CONTEXT_SIGNALS`.
- Existing opencode prose-only surfaces and runtime refusal policy — generalize the policy/message to include zcode without weakening opencode coverage.
- `skills/bill-feature-task/content.md`, `skills/bill-feature-goal/content.md`, and `skills/bill-feature-task-runtime/content.md` — update governed skill source so router/runtime skills refuse zcode the same way they refuse opencode.
- `runtime-cli/.../goal/GoalCliCommands.kt` — no logic change expected; `resolveInvokedAgentId` picks up the new signal via `InvokingAgentContextResolver.detect`.
- Tests under `runtime-application` (status service/projector) and `runtime-domain` (detection signals).
- Tests around feature-task/goal CLI preflight, runtime adapter registration, and refused-agent launch fallbacks currently covering opencode.
- Docs: runtime support notes for zcode prose-only behavior and the historical credential caveat.

## Validation Strategy

- `./gradlew check` green.
- DB-fixture path: against a run persisted with zcode phase records, `goal status` executed with a codex-resolving environment prints `active_agent: zcode`.
- Detection path: `InvokingAgentContextResolver.detect` returns zcode for an environment carrying `ZCODE_APP_VERSION`, and existing claude/codex/opencode fixtures still pass.
- Refusal path: from a zcode-resolving environment, `bill-feature-task mode:runtime`, `bill-feature-goal mode:runtime`, direct `bill-feature-task-runtime`, `skill-bill feature-task run --agent zcode`, and `skill-bill goal run --agent zcode` all refuse before spawning a zcode runtime child and point to prose mode.
- Manual smoke: from a zcode session, flag-less `skill-bill goal status SKILL-102` shows the persisted agent, not codex; flag-less runtime starts resolve to zcode and refuse rather than falling through to codex.

## Next

```bash
Run bill-feature-task mode:prose on .feature-specs/SKILL-103-goal-status-agent-attribution/spec.md
```
