# SKILL-198 Subtask 2: Runtime Repair Window Owns Check Execution

## Intended Outcome

When a pack declares `validation_gate`, the v1 runtime owns every gate run. After it hands the agent a complete finding set, it must not run collect-all, full gate, build-only, or any other declared gate argv until the agent reports that finding set fully repaired. The next runtime action is one cache-bypassing verification gate.

Prose from subtask 1 is not enough. The validate loop that just burned time did so by running targeted Gradle while the briefing already said not to rerun the gate.

## Scope

- Introduce an explicit repair-window state on the validate (and standalone quality-check) path: `findings_open` until the agent reports the current set repaired.
- While `findings_open`, the runtime does not execute `collect_all_*`, `full_gate_command`, `cache_bypassing_full_gate_command`, or `build_only_command`.
- On repaired, the runtime runs exactly one `cache_bypassing_full_gate_command`. Failure opens a new `findings_open` over that complete set.
- Stop describing or using `full_gate_command` as an intermediate repair-cycle run.
- Agent-run fallback remains prompt-only for stacks with no `validation_gate`; this subtask does not intercept arbitrary shell Gradle.

## Applicable Architectural Invariants

- Gate argv stays pack-declared. The runtime never hardcodes `--continue` or `--rerun-tasks`.
- The runtime extracts bounded findings and does not hand raw logs to the agent.
- Transport or telemetry failure must not skip the repair window or force an extra gate run.

## Acceptance Criteria

1. After a collect-all (or verification) run that produced findings, validate state is `findings_open` with that complete set as the only repair input.
2. While `findings_open`, the runtime does not invoke any `validation_gate` argv, including collect-all, full gate, cache-bypassing full gate, and build-only.
3. The agent cannot cause those argv to run by asking, by calling a quality-check skill, or by spawning a subagent. The runtime is the only executor of declared gate commands on this path.
4. The agent signals that the current set is fully repaired without running a check to "confirm" first. That signal is the only way to leave `findings_open`.
5. Leaving `findings_open` runs exactly one `cache_bypassing_full_gate_command`. A clean result closes validate. A failing result replaces the finding set and re-enters `findings_open`.
6. `full_gate_command` is not invoked between individual fixes and is not documented as an intermediate repair-cycle run.
7. Goal `build_only` depth still runs only as a runtime-owned gate outside an open finding-set repair window, never as an agent-run compile during repair.
8. A focused runtime test proves: findings present → gate argv not executed → repaired signal → exactly one cache-bypassing verify. A second test proves a failing verify reopens `findings_open` without any targeted module command.

## Failure And Recovery Behavior

- Missing repaired signal: validate stays open; the runtime does not poll with extra gates.
- Agent-run fallback (no `validation_gate`): do not pretend to intercept Gradle; keep the subtask 1 contract as the binding rule and surface the same degradation the runtime already emits when a pack has no gate.
- Collect-all failure with no extracted findings: loud-fail; do not fall back to agent-run module checks.

## Non-Goals

- Intercepting every possible shell command in agent-run fallback.
- Redesigning finding artifact formats.
- Changing pack collect-all argv.

## Dependency Notes

Depends on subtask 1 so agent-facing text and runtime state use the same vocabulary (`findings_open`, repair window, one verify gate).

## Validation Strategy

Contract-test the validate state machine. Grep runtime and schema for "intermediate repair-cycle". Run a fixture pack through collect-all → open findings → attempted extra gate (must not run) → repaired → one verify.

## Next Path

Feature complete after this subtask. No further SKILL-198 work.
