# SKILL-65 - experimental feature-task-runtime

Created: 2026-06-02
Status: Draft
Issue key: SKILL-65
Parent: builds on SKILL-51/56/58/61/64 goal-runner + workflow-state work; sibling experiment to `bill-feature-task`

## Decomposition

This feature is decomposed because a fully runtime-driven task pipeline spans
four distinct runtime contracts plus an evaluation gate, in strict dependency
order:

1. the phase workflow definition and the inter-phase handoff contract (domain +
   schema);
2. per-phase state, result, timestamp, and attempt-ledger persistence
   (application + sqlite);
3. the runtime phase-loop runner that launches one agent per phase and gates
   progression on validated output (application);
4. the CLI command, MCP tools, and the thin experimental skill (cli + mcp +
   skills);
5. the comparison harness, guardrails, docs, and the written promote/kill
   criterion (evaluation).

Implement on one branch with a commit per subtask:

1. [Phase Workflow Definition + Handoff Contract](./spec_subtask_1_phase-workflow-definition-and-handoff-contract.md)
2. [Runtime Phase State Persistence](./spec_subtask_2_runtime-phase-state-persistence.md)
3. [Runtime Phase-Loop Runner](./spec_subtask_3_runtime-phase-loop-runner.md)
4. [CLI, MCP Surface, and Experimental Skill](./spec_subtask_4_cli-mcp-surface-and-experimental-skill.md)
5. [Comparison Harness and Promote/Kill Criteria](./spec_subtask_5_comparison-harness-and-promote-kill-criteria.md)

## Sources

- Design discussion on 2026-06-02 about turning the multi-phase
  `bill-feature-task` pipeline into a fully runtime-driven variant:
  - the existing orchestrator is itself an agent following prose to decide what
    runs next, which makes the top-level loop the weakest link under a weaker
    model;
  - moving the phase loop into the runtime (as the goal runner already does for
    subtasks) makes sequencing deterministic and agent-independent, and makes
    per-phase observability ground-truth rather than agent self-report;
  - the conclusion was to build a NEW experimental capability rather than
    restructure the working `bill-feature-task`, with an explicit promote/kill
    decision so the experiment cannot linger as permanent dual maintenance.
- Current runtime facts confirmed during scoping on 2026-06-02:
  - `GoalRunner` (`runtime-application`) already owns a deterministic run loop
    that selects the next unit, launches an agent via
    `AgentRunGoalRunnerSubtaskLauncher` -> `AgentRunService` ->
    `FileSystemAgentRunLauncher`, waits synchronously for launch facts, persists
    state, and resumes — this is the loop to reuse, pointed at phases instead of
    subtasks;
  - `WorkflowEngine` (`runtime-domain`) is stateless logic generic over any
    `WorkflowDefinition`: it merges step/artifact updates, composes continuation
    briefings, and schema-validates snapshots;
  - `FeatureImplementWorkflowDefinition` already encodes the 12 phases and a
    `requiredArtifactsByStep` dependency map — a DAG, not a chain (e.g.
    `implement` needs `plan` AND `preplan_digest`; `commit_push` needs three
    upstream artifacts);
  - workflow state persists to SQLite via `DatabaseSessionFactory` +
    `WorkflowStateRepository`, with `WorkflowFamily` (`IMPLEMENT`, `VERIFY`)
    selecting per-family persistence;
  - today the workflow runtime gate checks artifact PRESENCE, not VALIDITY:
    artifacts are `@OpenBoundaryMap`, and `validateUpdate` performs no ordering
    checks, so an empty/fabricated artifact passes the gate;
  - today step transitions and the "started/finished" timing the workflow
    records are driven by the agent voluntarily calling
    `feature_implement_workflow_update`; the runtime cannot independently verify
    them.
- Boundary contracts from `runtime-kotlin/ARCHITECTURE.md` and
  `RuntimeArchitectureTest`:
  - application must not depend on Clikt/MCP/Compose/JDBC/HTTP/infra packages;
  - domain/ports must not touch filesystem, process env, JDBC, HTTP, or
    entrypoint frameworks;
  - schema validators live in `runtime-infra-fs`, reached only through
    domain-owned ports (`WorkflowSnapshotValidator`,
    `DecompositionManifestValidator`);
  - public data/enum/sealed declarations live in `model` packages;
  - `runtime-core` is the only composition root that knows concrete adapters.
- The SKILL-33 mandate-skip decision recorded in `agent/decisions.md`: action
  mandates were silently dropped because a pre-planning subagent abstracted them
  into free-form notes. This is the canonical failure that run-invariant
  injection must structurally prevent.

## Problem

`bill-feature-task` has well-defined phases, but every phase boundary is prose:
an orchestrating agent reads the skill and decides what to run, when a phase is
done, and what context the next phase gets. The runtime records progress only
through voluntary `workflow update` calls, and its gates check artifact
*presence*, not *validity*. Two consequences follow:

1. **Structural integrity depends on agent discipline.** A weaker model can
   skip a phase, advance with an empty/fabricated artifact, declare premature
   completion, or — as in SKILL-33 — silently drop run-level mandates. The
   runtime cannot independently detect these because the orchestration loop and
   the timing it records both live inside the agent's context.
2. **Observability is self-reported, not ground truth.** "Review started at
   11:23" is something the agent tells the ledger, not something the runtime
   measured.

The goal runner already proves the alternative: when the runtime *launches* each
unit of work as a subprocess, it owns the loop, the clock, and the persisted
state. SKILL-65 applies that same model to the task phases — without disturbing
the working `bill-feature-task`.

This capability is explicitly **experimental**. Its value is unproven until it
demonstrates output quality on par with the in-context prose orchestrator while
delivering materially better observability and state reliability. The feature
therefore ships behind its own command and skill, and ships with a written
promote/kill criterion.

## Goals

1. Build a new, fully runtime-driven `feature-task-runtime` capability where the
   Kotlin runtime owns the phase loop and launches one agent per phase.
2. Reuse the existing `GoalRunner` / `AgentRunService` / `WorkflowEngine`
   machinery rather than authoring a second orchestration loop.
3. Make each phase boundary a structured, schema-validated handoff: phase output
   flows forward, downstream phases consume statically-declared upstream
   outputs, and run-invariants are injected into every phase by the runtime.
4. Raise the progression gate from presence to validity: a phase cannot complete
   or advance unless its output validates against a per-phase schema.
5. Make per-phase observability ground truth: the runtime stamps start/finish
   timestamps, durations, agent id, status, and attempt count, and appends an
   auditable per-phase attempt ledger.
6. Support per-phase agent assignment so a phase can run under a different agent
   than the invoker, reusing the established agent-resolution order and the
   SKILL-64 "default to the invoking agent" fix.
7. Keep `bill-feature-task` and its runtime contracts completely unchanged.
8. Make the experiment measurable: provide a same-spec comparison procedure and
   a written promote/kill decision rule.

## Non-Goals

- Do not modify, refactor, or re-route `bill-feature-task`, its workflow
  definition, CLI, MCP tools, skill, or tests.
- Do not implement dual-agent cross-review *merge* (Claude + Codex reconciling
  findings). This feature only enables per-phase agent selection; the merge is a
  deliberate follow-up.
- Do not remove, downgrade, or make optional any existing review, audit, or
  validation gate.
- Do not change the decomposition manifest execution model.
- Do not make `feature-task-runtime` a default path or auto-route work to it; it
  stays opt-in until promote criteria are met.
- Do not build a parallel orchestration loop that diverges from the goal-runner
  machine.
- Do not let run-invariants (spec, acceptance criteria, mandates/overrides) be
  represented as optional or agent-discretionary context.

## Target User Experience

A maintainer runs the experimental pipeline explicitly:

```bash
skill-bill feature-task-runtime SKILL-XX \
  --spec .feature-specs/SKILL-XX-thing/spec.md \
  --format json
```

The runtime drives the phases. Each phase is launched as its own agent with a
mechanically-assembled briefing, and each transition is recorded with
runtime-owned facts:

```json
{
  "workflow_id": "wftr-20260602-...",
  "phases": [
    {"id": "plan", "agent": "claude", "status": "completed",
     "started_at": "2026-06-02T11:23:01Z", "finished_at": "2026-06-02T11:27:44Z",
     "duration_ms": 283000, "output_validated": true, "attempt": 1},
    {"id": "review", "agent": "codex", "status": "completed",
     "started_at": "2026-06-02T11:28:02Z", "finished_at": "2026-06-02T11:34:10Z",
     "duration_ms": 368000, "output_validated": true, "attempt": 1}
  ]
}
```

Read-only inspection and resume mirror the goal commands:

```bash
skill-bill feature-task-runtime status SKILL-XX --format json
skill-bill feature-task-runtime resume SKILL-XX
```

If a phase produces output that fails its schema, the run blocks loudly with the
phase id and the validation failure — it does not advance with an invalid
artifact. Run-invariants (the spec, acceptance criteria, and any
mandates/overrides) are present in every phase's briefing regardless of what the
phase "needs."

## Acceptance Criteria

1. A new experimental capability `feature-task-runtime` exists and is fully
   runtime-driven: the Kotlin runtime owns the phase loop and launches exactly
   one agent per phase, reusing `AgentRunService` / the goal-runner launcher and
   `WorkflowEngine` rather than a new orchestration loop or process adapter.
2. `bill-feature-task` — skill, `FeatureImplementWorkflowDefinition`, CLI, MCP
   tools, mappers, and tests — is unchanged by this feature.
3. Each phase receives a three-layer handoff: (a) run-invariants always injected
   by the runtime, (b) statically-declared upstream outputs (latest iteration
   for fix loops), (c) statically-declared per-phase derived context. The
   executing agent never selects its own inputs.
4. Run-invariants (spec reference, acceptance criteria, mandates/overrides) are
   structurally non-optional and injected into every phase.
5. Each phase output is validated against a per-phase schema; a phase cannot be
   marked complete or advanced unless its output validates. Invalid output
   blocks loudly and observably.
6. The runtime records per-phase ground-truth observability — start/finish
   timestamps, duration, agent id, status, attempt count — plus an append-only
   per-phase attempt/event ledger.
7. Per-phase agent assignment is supported via static configuration plus
   override, resolved through the existing order (override -> per-phase ->
   invoked -> `SKILL_BILL_AGENT` -> documented default), defaulting to the
   invoking agent.
8. A run resumes from the last incomplete phase using persisted state; a missing
   required upstream output blocks loudly instead of running a phase blind.
9. A documented same-spec comparison procedure and a written promote/kill
   criterion exist; the experiment is explicitly not a default path.
10. Architecture boundaries hold: `RuntimeArchitectureTest` passes and no new
    layer violations are introduced.
11. Maintainer validation passes:
    - `skill-bill validate`
    - `(cd runtime-kotlin && ./gradlew check)`
    - `npx --yes agnix --strict .`
    - `scripts/validate_agent_configs`

## Design Notes

- Reuse, do not fork. The phase loop is `GoalRunner`'s loop pointed at the
  ordered `stepIds` of a new `WorkflowDefinition` instead of at subtask
  selection. The launcher, agent resolution, synchronous wait, and SQLite
  persistence are reused as-is through their existing ports.
- The handoff is an accumulating, schema-validated artifact store over the phase
  DAG, not a baton passed from N-1 to N. Consumers pull their declared upstream
  dependency set (often more than the immediately previous phase).
- Dependency and context declarations are design-time properties of the phase
  definition, authored and enforced by the runtime — never decided by the
  running agent. This is the property that makes the pipeline robust to weaker
  models at the structural level.
- Move the gate up the strength ladder: presence -> schema. Schema validation
  catches the empty/fabricated artifact; it does not and cannot judge content
  quality. Output *quality* still scales with the launched model; the runtime's
  contribution is to make a weak model fail loudly instead of silently.
- Run-invariants get their own injection path precisely because SKILL-33 proved
  that anything left to phase discretion can be silently dropped.
- Per-phase agent assignment is the seam that later enables cross-model review;
  this feature wires the seam but does not build the merge.
- Keep the experimental capability strictly additive. Default routing,
  promotion, or removal of `bill-feature-task` is out of scope and gated on the
  promote/kill decision.

## Validation Strategy

- Domain + infra-fs tests for the phase definition, the handoff contract, the
  per-phase output schemas, latest-iteration selection, and run-invariant
  enforcement.
- Infra-sqlite repository/migration tests and an application persistence-port
  test for the new workflow family and the attempt ledger.
- Application tests with a fake launcher proving deterministic phase ordering,
  handoff assembly contents, schema-gated rejection, bounded fix loops,
  per-phase agent resolution, resume-from-phase, and run-invariant injection.
- CLI tests and MCP golden tests for the new surface; agnix and
  `validate_agent_configs` for the experimental skill and native-agent configs.
- A reproducible same-spec comparison procedure plus guardrail tests for
  observability completeness and handoff payload budgets.
- The full maintainer command set for the final gate.

## Open Questions

- Should the runtime-driven task reuse the exact phase set of
  `FeatureImplementWorkflowDefinition`, or start with a reduced phase set
  (assess/plan/implement/review/audit/validate) to keep the first experiment
  small?
- Should the new workflow family be a distinct `WorkflowFamily` case
  (`TASK_RUNTIME`) with its own SQLite table, or reuse the implement family's
  storage with a discriminator? Distinct is cleaner for isolation; reuse is less
  migration surface.
- Should per-phase output schemas be authored per phase up front, or should the
  first iteration validate only the highest-value phases (plan, review, audit)
  and treat the rest as presence-checked until proven?
- Where should the promote/kill criterion live — in this parent spec, in
  `agent/decisions.md`, or both with one authoritative source?
- What is the documented per-phase handoff payload budget, and should it reuse
  the SKILL-64 compaction projection directly?
- Should resume restore each phase's briefing from persisted upstream outputs
  only, or also re-validate prior outputs against current schemas on resume?

## Promote / Kill Criterion (Authoritative)

This is the single authoritative source for the `feature-task-runtime`
promote/kill decision (SKILL-65 Subtask 5, AC3). Other docs (notably
`runtime-kotlin/ARCHITECTURE.md`, the skill catalog, and root
`agent/decisions.md`) point here and must not restate the rule, so the experiment
cannot drift into permanent dual maintenance.

**Deciding evidence.** The decision is made only on the dated evidence produced
by the reproducible same-spec comparison procedure in
`runtime-kotlin/docs/architecture/feature-task-runtime-comparison.md`: the
per-phase timing tables, the runtime-owned-vs-self-reported observability
classification, the state-reliability/resume outcomes, the token/session cost
deltas, and the per-criterion output-quality table. A decision may not be made on
impressions or on a single run; it requires comparison evidence across at least
**three** representative governed specs run through both paths under the rules in
that procedure (no gate weakened, model held constant for quality).

**Who decides.** The skill-bill maintainer (repository owner) decides, on that
evidence. No automated routing change is implied by this rule; promotion or
retirement is an explicit maintainer action.

**Promote** `feature-task-runtime` to replace the prose orchestrator only when
the evidence shows ALL of:

1. **Output quality on par or better** — across the evaluated specs, the
   runtime-driven path satisfies the same acceptance criteria and passes the same
   unmodified maintainer gate as `bill-feature-task`, with no criterion regressing
   from satisfied to partial/missing.
2. **Materially better observability** — per-phase timings, agent id, status, and
   attempt count are runtime-owned ground truth (not self-reported) on every
   phase, plus the append-only attempt ledger, with no field silently absent.
3. **Better or equal state reliability** — resume reliably skips completed phases,
   never loses or silently re-runs completed work, and the validity gate (not
   mere presence) blocks invalid/fabricated artifacts loudly. A missing required
   upstream output blocks rather than running blind.
4. **Acceptable cost** — the token/session cost delta from per-phase agent
   fan-out is within a maintainer-accepted bound for the quality/observability
   gained.

On promotion, `bill-feature-task` is migrated/retired in a follow-up; the dual
state ends.

**Kill** (retire `feature-task-runtime`) when the evidence shows ANY of:

1. Output quality regresses relative to `bill-feature-task` on the evaluated specs
   and the gap is not closable without weakening a gate.
2. The observability/state-reliability advantage does not materialize in practice
   (e.g. runtime-owned fields are not meaningfully more trustworthy or complete).
3. The cost or operational overhead is disproportionate to the gain.

On a kill decision, the experimental command, skill, and workflow family are
removed; the experiment does not linger.

**No indefinite dual maintenance.** Maintaining both `bill-feature-task` and
`feature-task-runtime` indefinitely is explicitly forbidden. The experiment is
time- and evidence-bounded: once the comparison evidence across the evaluated
specs is in hand, the maintainer must record a promote or kill decision rather
than leave both paths in permanent parallel maintenance.
