# SKILL-57 agent-compatibility-policy

Status: In Progress
Issue key: SKILL-57
Feature: agent-compatibility-policy
Feature size: MEDIUM
Rollout needed: false
Workflow id: wfl-20260530-185813-hwp5
Step id: preplan
Attempt count: 2

## Sources

- `.feature-specs/SKILL-57-goal-runner-production-hardening/spec_subtask_4_agent-compatibility-policy.md`
- User briefing acceptance criteria and non-goals in preplan prompt

## Acceptance Criteria

1. `skill-bill goal --agent <agent>` can report whether the selected agent has the required headless goal-runner capabilities.
2. Unsupported capabilities produce actionable errors naming the missing capability and agent.
3. Subagent-spawn failure cannot silently degrade to inline work in `bill-goal` mode.
4. Explicit degraded inline fallback, if implemented, is opt-in and records durable `degraded_mode` progress plus terminal outcome metadata.
5. Tests cover each supported adapter's command shape and capability-report mapping.
6. Manual smoke documentation records real-agent evidence for Claude, Codex, Opencode, and Junie on a decomposed test manifest.
7. Existing installs and interactive skills remain unaffected.
8. A parent-forced inline continuation after subagent failure is never silent: status, progress history, terminal outcome metadata, and final report all include degraded-mode context.

## Consolidated Spec Content

# SKILL-57 Subtask 4 - Headless Agent Compatibility and Fallback Policy

Status: Draft
Parent spec: [.feature-specs/SKILL-57-goal-runner-production-hardening/spec.md](./spec.md)
Issue key: SKILL-57
Subtask order: 4 of 4
Depends on: subtasks 1, 2, and 3
Branch model: same-branch, commit per subtask

## Purpose

Make supported-agent behavior explicit. `bill-goal` should know whether a
headless child can invoke skills, spawn `bill-feature-implement` phase
subagents, write durable progress, and write terminal outcomes. If those
capabilities are unavailable, the runtime must fail loudly or enter a clearly
configured degraded mode.

## Scope

In scope:

- Define capability checks for each headless agent adapter:
  - skill invocation available;
  - Skill Bill CLI/MCP workflow tools available;
  - phase subagent spawning available;
  - durable progress write available;
  - terminal outcome write available;
  - stdin/argv contract cannot block waiting for extra input.
- Add command-shape and capability-reporting tests for Claude, Codex, Opencode,
  and Junie.
- Add a runtime preflight path for `skill-bill goal` that can report unsupported
  or degraded capabilities before launching a long child run.
- Define fallback behavior:
  - default for `bill-goal`: fail-fast and mark the child workflow blocked when
    required subagent spawning or durable progress writes are unavailable;
  - optional explicit degraded mode: allow inline phase execution, record
    `degraded_mode` progress, and include degraded-mode metadata in terminal
    outcome.
- Require every subagent-spawn failure or parent-forced inline continuation to
  produce a durable capability/supervision record that names the agent, missing
  or failed capability, phase, continuation mode, and user-visible reason.
- Document manual smoke evidence for real installed agents where CI cannot
  execute their binaries.
- Update `bill-goal` and `bill-feature-implement` docs/skill content so users
  understand the difference between fresh child process context and internal
  phase subagent context.

Out of scope:

- Adding support for a new agent family beyond the agents already represented
  in the launcher.
- Making unsupported agents silently work by bypassing `bill-feature-implement`
  subagent semantics.

## Acceptance Criteria

1. `skill-bill goal --agent <agent>` can report whether the selected agent has
   the required headless goal-runner capabilities.
2. Unsupported capabilities produce actionable errors naming the missing
   capability and agent.
3. Subagent-spawn failure cannot silently degrade to inline work in `bill-goal`
   mode.
4. Explicit degraded inline fallback, if implemented, is opt-in and records
   durable `degraded_mode` progress plus terminal outcome metadata.
5. Tests cover each supported adapter's command shape and capability-report
   mapping.
6. Manual smoke documentation records real-agent evidence for Claude, Codex,
   Opencode, and Junie on a decomposed test manifest.
7. Existing installs and interactive skills remain unaffected.
8. A parent-forced inline continuation after subagent failure is never silent:
   status, progress history, terminal outcome metadata, and final report all
   include degraded-mode context.

## Validation

```bash
(cd runtime-kotlin && ./gradlew \
  :runtime-infra-fs:test --tests skillbill.launcher.AgentRunLauncherTest \
  :runtime-cli:test --tests skillbill.cli.CliGoalRuntimeTest \
  :runtime-core:test)
skill-bill validate --skill-name bill-goal
scripts/validate_agent_configs
```
