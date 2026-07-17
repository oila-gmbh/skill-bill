# SKILL-128 Subtask 2 - Durable goal planning sweep

## Scope

Add the goal-runner preparation stage that gathers shared repository and
governance context once, then produces and checkpoints a separate preplan and
plan for each ordered sub-spec before subtask implementation begins. The stage
must resume from durable per-subtask preparation state after interruption.

## Acceptance Criteria

1. After the accepted decomposition is loaded, runtime goal execution enters
   planning preparation before selecting or launching a mutating child phase.
2. One goal planning session/context receives the shared repository,
   platform-pack, boundary-memory, validation, decomposition, and parent-spec
   context once and reuses it while processing all ordered sub-specs.
3. For each non-skipped subtask, the planner produces preplan first and plan
   second, with distinct schema-valid phase payloads scoped to that sub-spec.
4. Each completed pair is persisted through the subtask-1 port immediately, so
   process termination after subtask N resumes at N+1 without repeating
   completed planning or repository discovery.
5. No child implementation launch occurs until every non-skipped subtask has a
   valid prepared pair. A blocked, exhausted, malformed, or persistence-failed
   planning attempt stops before mutation with a clear current subtask and
   resumable state.
6. Dependency ordering informs planning context, but planning all sub-specs up
   front does not execute, simulate, or mutate the repository for any
   dependency.
7. Repository commits from a later execution stage are not treated as planning
   invalidation. Only incompatible immutable spec/decomposition/contract
   provenance is rejected.
8. Goal-runner tests cover a multi-subtask sweep, a crash between pairs,
   blocked planning, malformed phase output, persistence rollback, all-plans
   gate enforcement, and proof that shared discovery happens once.

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
- Assert exact launch counts and context identities to prove one shared
  discovery context serves every subtask.
- Assert no branch or child mutating process launches before the planning gate
  completes.
- Run affected goal-runner and runtime-application tests.

## Next Path

After completion, continue with
`spec_subtask_3_child_planning_hydration.md`.

