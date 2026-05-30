# SKILL-57 Subtask 2 - Feature-Implement Phase Heartbeats

Status: Draft
Parent spec: [.feature-specs/SKILL-57-goal-runner-production-hardening/spec.md](./spec.md)
Issue key: SKILL-57
Subtask order: 2 of 4
Depends on: subtask 1
Branch model: same-branch, commit per subtask

## Purpose

Teach `bill-feature-implement` to use the durable progress contract during
long-running phases, especially `implement`, so parent goal runners do not have
to infer liveness from quiet workflow rows or file mtimes.

## Scope

In scope:

- Update `skills/bill-feature-implement/content.md` continuation rules so the
  continuation payload includes progress-write instructions.
- Update heavy phase subagent briefings for:
  - preplan
  - plan
  - implement
  - audit
  - validate
  - pr_description
- Require progress events at:
  - phase start;
  - each task start;
  - each task completion;
  - bounded heartbeat interval during work that exceeds the interval;
  - phase completion before returning the structured `RESULT:`.
- Include `workflow_id`, `step_id`, and next `attempt_count` in each briefing.
- Make progress writes best-effort but visible: if a progress write fails, the
  child must report the failure in its final return block and, when possible,
  block the workflow instead of silently continuing.
- Preserve existing interactive and continuation behavior except for additive
  progress instructions.

Out of scope:

- Parent goal-runner liveness classification and status rendering. That is
  subtask 3.
- Agent capability checks for whether subagents can write progress directly.
  That is subtask 4.

## Acceptance Criteria

1. Every heavy phase briefing is self-contained and includes the durable
   progress write contract.
2. Implementation subagents are explicitly instructed to write progress at task
   boundaries and bounded intervals.
3. Progress write failures are not silent: they appear in the phase result and
   are available to the orchestrator for blocking/retry decisions.
4. Existing `bill-feature-implement` return contracts remain valid.
5. `content.md` remains the authored source of truth; no generated `SKILL.md`
   or provider-specific outputs are committed.
6. Rendering/validation tests and golden payloads are updated where the
   continuation prompt now includes progress guidance.

## Validation

```bash
skill-bill validate --skill-name bill-feature-implement
(cd runtime-kotlin && ./gradlew :runtime-mcp:test :runtime-infra-fs:test)
npx --yes agnix --strict .
```
