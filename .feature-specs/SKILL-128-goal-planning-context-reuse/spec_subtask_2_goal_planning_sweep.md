# SKILL-128 Subtask 2 - Durable goal planning sweep

## Scope

Add the goal-runner preparation stage that gathers shared repository and
governance context once, checkpoints one goal-level preplan, then produces and
checkpoints only a distinct plan for each ordered sub-spec before subtask
implementation begins. The stage must resume from durable shared-preplan and
per-subtask plan state after interruption.

## Acceptance Criteria

1. After the accepted decomposition is loaded, runtime goal execution enters
   planning preparation before selecting or launching a mutating child phase.
2. One goal planning session/context receives the shared repository,
   platform-pack, boundary-memory, validation, decomposition, and parent-spec
   context once and emits one schema-valid shared preplan before processing the
   ordered sub-specs.
3. Exactly one preplan agent/process is launched for the parent goal. No
   per-subtask preplan launch or repository rediscovery is permitted.
4. For each non-skipped subtask, the planner produces only a distinct
   schema-valid plan from the saved shared preplan, governed sub-spec, and
   dependency context.
5. The shared preplan and each completed subtask plan are persisted through the
   subtask-1 port immediately, so process termination resumes at the first
   missing plan without repeating discovery or completed planning.
6. No child implementation launch occurs until the shared preplan and every
   non-skipped subtask plan are valid and durable. A blocked, exhausted,
   malformed, or persistence-failed planning attempt stops before mutation with
   a clear current subtask and resumable state.
7. Dependency ordering informs planning context, but planning all sub-specs up
   front does not execute, simulate, or mutate the repository for any
   dependency.
8. Repository commits from a later execution stage are not treated as planning
   invalidation. Only incompatible immutable spec/decomposition/contract
   provenance is rejected.
9. Goal-runner tests cover a multi-subtask sweep, a crash after shared preplan
   and between subtask plans, blocked planning, malformed phase output,
   persistence rollback, all-plans gate enforcement, and exact proof that
   discovery/preplan launches once while plan launches once per subtask.

## Non-Goals

- Do not launch implementation, review, audit, or validation from the planning
  context.
- Do not hydrate child workflow phase records in this subtask.
- Do not add plan-refresh or repository-delta behavior.
- Do not change standalone feature-task launch behavior.

## Dependency Notes

Depends on subtask 1 for the typed goal-planning contract and persistence port.

## Validation Strategy

- Add application/goal-runner tests with fake planning launchers and a durable
  fake or temporary SQLite preparation store.
- Assert exact launch counts and context identities: one preplan total and one
  plan per non-skipped subtask, all consuming the same preplan identity.
- Assert no branch or child mutating process launches before the planning gate
  completes.
- Run affected goal-runner and runtime-application tests.

## Next Path

After completion, continue with
`spec_subtask_3_child_planning_hydration.md`.
