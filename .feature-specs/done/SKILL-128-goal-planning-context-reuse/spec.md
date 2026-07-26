---
status: Complete
---

# SKILL-128 - Goal planning context reuse

Created: 2026-07-17
Issue key: SKILL-128
Mode: decomposed

## Intended Outcome

A decomposed goal performs repository and governance discovery exactly once and
persists one immutable goal-level `preplan` digest. It then produces one distinct
`plan` per subtask from that shared digest and the governed sub-spec, without
launching another preplan agent or repeating repository discovery. The shared
preplan and each subtask's schema-valid plan are saved in the local workflow
database and later hydrated into that subtask's child workflow. The child begins
execution at `implement` with `preplan` and `plan` already durably completed.

This is a goal-only optimization. A standalone `bill-feature-task` run keeps
its current phase topology: it executes `preplan`, executes `plan`, persists
both outputs in its own workflow, and then advances to `implement`.

## Motivation

`skill-bill goal` currently launches a fresh feature-task runtime for each
subtask. Every child rediscovers the same repository, platform-pack,
architecture, boundary-memory, validation, and goal context before producing
its own preplan and plan. Recent telemetry shows planning and pre-planning are
a material share of goal cost even though the parent spec, decomposition, and
sub-specs already exist before the first implementation starts.

The runtime already treats initial planning as immutable intended-state
context. Implementation reconciles the current tree to the saved plan, and
audit-gap remediation reuses the original completed preplan and plan rather
than regenerating them. Goal planning should therefore own initial planning,
while child task workflows should continue to own implementation and all later
phases.

## Desired Behaviour

After decomposition and the existing confirmation gate, a runtime goal enters
a durable planning-preparation stage. One goal planning agent/context gathers
the shared repository and governance evidence once and emits one schema-valid
goal-level `preplan` output. The runtime validates and checkpoints that digest
before subtask planning begins.

The same planning context then processes the ordered sub-specs. For each
subtask it produces only a distinct `plan` output that consumes the immutable
shared preplan plus that subtask's governed spec and dependency context. It
must not launch a per-subtask preplan phase, rediscover the repository, or copy
the shared discovery work into independently generated digests. Each completed
subtask plan is schema-validated and checkpointed immediately. A crash reuses
the saved shared preplan and completed subtask plans, then resumes at the first
unplanned subtask.

When the goal later selects a subtask for execution, it promotes the same saved
goal-level preplan and that subtask's saved plan into the child feature-task
workflow as the child's real durable `preplan` and `plan` phase records. Normal
runtime dependency, resume, telemetry, and audit-remediation logic then
observes those phases as completed and starts the child at `implement`.

Repository changes made by earlier subtasks do not invalidate later saved
plans. Those changes are the expected execution of the goal, and the mutating
phase contract requires implementation to reconcile the current tree to the
plan's intended state. An unexpected change to the parent spec, a sub-spec,
the decomposition manifest, or the relevant planning/output contract fails
loudly instead of silently replanning or using incompatible data.

## Acceptance Criteria

1. Runtime goal execution has a durable planning-preparation stage after the
   decomposition is accepted and before any goal child enters `implement` or
   another mutating phase.
2. The planning-preparation stage launches repository/governance discovery and
   preplanning exactly once per parent goal workflow. It produces one bounded,
   immutable, schema-valid goal-level `preplan` digest covering repository
   conventions, boundary history and decisions, similar implementations,
   dependencies, reusable patterns, validation commands, rollout guidance,
   platform routing, and selected add-ons.
3. No non-skipped subtask launches its own preplan agent, process, or discovery
   pass. Every subtask plan consumes the same saved goal-level preplan plus its
   governed sub-spec and dependency context. Subtask-specific analysis belongs
   in that subtask's plan, not in a duplicated discovery digest.
4. Every non-skipped subtask receives its own schema-valid `plan` output scoped
   to that sub-spec. The shared preplan is stored once and referenced by every
   subtask planning record rather than independently regenerated per subtask.
5. The shared preplan is checkpointed before subtask planning begins, and each
   completed subtask plan is checkpointed immediately. Durable records identify
   the parent goal workflow, issue key, governed specs, planning contract
   version, shared-preplan identity, and exact saved plan output.
6. Saved `preplan` and `plan` payloads use the existing feature-task runtime
   phase-output contract and pass the same validator used for agent-produced
   child phase outputs. Malformed, wrong-phase, wrong-version, missing, or
   mismatched planning data fails loudly through typed errors at both the save
   and hydrate seams.
7. The goal does not launch implementation for any subtask until planning
   preparation is complete for every non-skipped subtask in the accepted
   decomposition. A blocked or exhausted planning attempt stops the goal
   before mutation and reports the failing subtask and resumable planning
   position.
8. Goal planning preparation is resumable. A restart reuses the valid shared
   preplan and every checkpointed subtask plan, reconstructs the bounded shared
   context from durable data when necessary, and continues at the first missing
   plan without repeating discovery or completed planning.
9. Selecting a prepared subtask atomically creates or updates its child runtime
   workflow with completed `preplan` and `plan` phase records, including
   attempts, outputs, provenance, ledger/step state, and current step needed by
   ordinary runtime resume semantics.
10. A newly launched prepared goal child starts at `implement`; neither the
   runtime nor a child phase agent executes `preplan` or `plan`. The normal DAG
   supplies the completed plan to implementation and retains both original
   planning outputs for later audit-gap remediation and durable inspection.
11. Goal-child review, audit, validation, history, commit/push, bounded review
    remediation, audit-gap remediation, crash recovery, and terminal outcome
    handling remain behaviorally unchanged after planning hydration.
12. Repository commits created by earlier subtasks do not invalidate or
    regenerate later subtask planning. Later implementation reconciles the
    current working tree to its saved intended-state plan using the existing
    mutating-phase idempotency contract.
13. The preparation and hydration paths verify immutable provenance for the
    parent spec, each sub-spec, the decomposition manifest, and the relevant
    contract versions. A mismatch fails loudly and requires explicit operator
    recovery; the runtime never silently refreshes planning or falls back to
    per-child planning.
14. The workflow database remains the sole continuation authority. Prepared
    planning is not copied into `spec.md`, sub-specs, the decomposition
    manifest, generated support files, or chat history as a competing mutable
    checkpoint.
15. Standalone `bill-feature-task` execution is unchanged: a fresh standalone
    workflow still starts at `preplan`, runs `plan`, saves both outputs in its
    own workflow database records, and advances through the existing phase
    topology.
16. Standalone task lookup, start, resume, audit-gap recovery, and telemetry do
    not read goal-prepared planning records, do not mark planning complete from
    another workflow, and do not gain a path that skips initial `preplan` or
    `plan`.
17. Goal status and progress surfaces expose bounded planning-preparation
   state, including shared-preplan status, planned/total subtask counts, and the
   current planning subtask, without returning raw planning payloads by default.
18. Telemetry distinguishes the single goal-level preplan from per-subtask plan
   production and standalone task-executed planning. Hydration must not
   double-count the shared preplan or a saved plan as child agent executions.
19. Governed `bill-feature-goal` and runtime guidance plus
    `runtime-kotlin/ARCHITECTURE.md` describe goal-owned initial planning,
    database-backed child hydration, immutable planning reuse, and the
    unchanged standalone-task boundary.
20. A hard goal reset clears the parent goal's shared preplan, every saved
   subtask plan, and all related planning/hydration continuation state before
   resetting manifest runtime fields. A subsequent run performs one fresh
   discovery pass. A soft reset preserves schema-valid preparation state and
   rejects incompatible provenance; it must not silently mix stale and revised
   planning records.
21. Regression coverage includes: exactly one preplan-agent launch for a
   multi-subtask goal; distinct per-subtask plans consuming the same preplan;
   shared-preplan and per-plan checkpoint/resume; hard-reset invalidation; no
   implementation before planning completion; child start at `implement`;
   audit-gap reuse; provenance and malformed-output rejection; and a standalone
   task proving its own `preplan` and `plan` agents still execute normally.
22. Maintainer validation passes:

    ```bash
    skill-bill validate
    (cd runtime-kotlin && ./gradlew check)
    npx --yes agnix --strict .
    scripts/validate_agent_configs
    ```

## Constraints

- Preserve the existing feature-task runtime phase-output schema as the
  contract for both prepared and directly executed planning outputs.
- Keep goal-preparation state versioned, typed, DB-backed, transaction-safe,
  and loud-failing at every read/write/import seam.
- Keep one authority after hydration: the child workflow's imported phase
  records are its runtime planning authority; the goal-level shared preplan and
  subtask-plan records remain immutable provenance/checkpoint evidence and are
  never independently edited.
- Do not invalidate plans merely because an earlier dependency subtask changed
  the repository or advanced the feature branch.
- Preserve manifest-driven platform behavior and resolve the routed platform
  and add-ons once in the shared goal planning context.
- Preserve the authored/generated boundary. Change governed `content.md` and
  contract-authorized sources only, and run `./install.sh` when skill or
  generated install behavior changes.
- Runtime-agent differences remain injectable strategies; do not add
  agent-identity branching to the process runner.

## Non-Goals

- Do not change standalone feature-task planning, persistence, or phase order.
- Do not remove preplan or plan from the feature-task runtime workflow
  definition.
- Do not reuse planning between unrelated goals, issue keys, repositories, or
  replacement decompositions.
- Do not turn plans into precomputed patches or require later implementation
  to apply stale line-level edits.
- Do not introduce automatic plan refresh, plan-delta agents, or silent
  replanning after goal execution begins.
- Do not change subtask execution ordering, branch strategy, review policy,
  audit semantics, validation gates, or PR suppression.
- Do not store raw planning outputs in Git-tracked specs or manifests.
- Do not disguise repeated per-subtask discovery as context reuse merely by
  passing the same shared packet to fresh preplan agents.

## Validation Strategy

- Add contract and persistence tests for the singleton shared preplan,
  per-subtask plan uniqueness, checkpoint resume, hard-reset deletion,
  provenance mismatches, and corrupt payloads.
- Add goal-runner tests proving exactly one preplan launch, all subtask plans
  complete before the first child mutates, and one shared planning context
  serves multiple sub-specs.
- Add child-runtime integration tests proving hydrated planning creates normal
  durable completed phase records and launches at `implement`.
- Add standalone feature-task rejection/regression tests proving goal planning
  records cannot influence standalone start or resume behavior.
- Add observability adapter tests for bounded planning status and non-duplicated
  telemetry attribution.
- Run the full maintainer validation suite listed above.
