# SKILL-85 - runtime feature-task remediation loops (M1 + M2)

Created: 2026-06-18
Status: Complete
Issue key: SKILL-85
Parent: completes SKILL-65 Subtask 3 AC5 (bounded review/audit fix loops, de-scoped at implementation); builds on the promoted runtime feature-task path (SKILL-67) and the goal-runner / workflow-state machinery (SKILL-56/58/61/64)

## Decomposition

This feature is decomposed because restoring prose-parity remediation loops to
the runtime spans three distinct architectural changes plus the two loops they
enable, in strict dependency order. The runtime is today a strict forward DAG
executor (`for (phaseId in phasesFor(request)) { if (loop.advance(phaseId))
break }`, `FeatureTaskRuntimeRunner.kt:95-99`) with no backward edge and no
re-entry; a phase re-run means "re-apply from scratch", which is why `implement`
is fenced out of any retry (`FeatureTaskRuntimeFixLoopPolicy.kt:19-27`). Adding
backward edges safely requires fixing resume integrity first, then the control
flow, then mutating-phase idempotency, before either loop can be built in best
shape:

1. resume & durable state-model integrity so a crash mid-loop resumes at the
   correct phase/iteration instead of wedging (application + domain);
2. a bounded, verdict-driven cyclic phase executor with a durable per-edge
   iteration ledger (application + domain);
3. reconcile-on-resume idempotency for mutating phases so re-entering or
   resuming a mutating phase never double-applies (application + contract);
4. **M1** — the review -> implement_fix -> review remediation loop (application +
   schema + skill content);
5. **M2** — the audit-gap -> plan -> implement -> review -> audit loopback
   (application + schema + skill content).

Implement on one branch with a commit per subtask:

1. [Resume & State-Model Integrity](./spec_subtask_1_resume-and-state-model-integrity.md)
2. [Bounded Cyclic Phase Executor](./spec_subtask_2_bounded-cyclic-phase-executor.md)
3. [Reconcile-on-Resume Idempotency for Mutating Phases](./spec_subtask_3_reconcile-on-resume-idempotency.md)
4. [M1 - Review-Driven Implement-Fix Loop](./spec_subtask_4_review-driven-implement-fix-loop.md)
5. [M2 - Audit-Gap Re-Plan/Re-Implement Loopback](./spec_subtask_5_audit-gap-loopback.md)

## Sources

- Parity audit on 2026-06-18 comparing the runtime feature-task path against the
  prose path, which found two large behavioral gaps the runtime carries relative
  to prose:
  - **M1** - prose re-spawns a distinct `bill-feature-task-implementation-fix`
    agent on Blocker/Major review findings and re-runs review, up to 3 times;
    the runtime has no cross-phase fix loop. Its "fix loop" is only a same-phase
    schema-gate retry, and `implement` is explicitly excluded
    (`FeatureTaskRuntimeFixLoopPolicy.kt:19-27`).
  - **M2** - prose loops audit findings back through plan -> implement -> review
    -> audit, up to 2 times; the runtime only scales audit ceremony by feature
    size and has no backward edge.
- SKILL-65 Subtask 3 **AC5** already specified these loops: "Bounded fix loops
  are implemented (review -> fix -> review, audit -> fix) with latest-iteration
  artifact semantics and a documented iteration cap consistent with the existing
  max-3 convention." The shipped implementation narrowed this to same-phase
  schema retries and excluded `implement`. SKILL-85 finishes that original
  contract; it is not new scope.
- Runtime facts confirmed during scoping on 2026-06-18:
  - the run loop is strictly forward and linear: `FeatureTaskRuntimeRunner`'s
    `RunLoop` iterates `phasesFor(request)` and `advance()` can only skip a
    complete phase, run a phase, or stop (block/decompose); there is no
    construct for re-entering an earlier phase
    (`FeatureTaskRuntimeRunner.kt:95-156`);
  - the same-phase schema fix loop and its bounded counters already exist
    (`FeatureTaskRuntimeFixLoopPolicy`, `MAX_FIX_LOOP_ITERATIONS = 3`,
    `decideAfterFailure`), and the handoff contract already resolves
    latest-iteration upstream outputs — these are the seams to extend, not
    replace;
  - the prose fix agent is a **distinct** agent that addresses specific findings
    on the already-mutated tree (registry: "respawned by the code-review step to
    address Blocker/Major findings"); it is incremental corrective work, not a
    re-application of the whole plan. The runtime's `implement` exclusion targets
    plan re-application, not incremental remediation - so M1 needs a new phase,
    not relaxation of that exclusion;
  - the runtime maintains a private ledger (`feature_task_runtime_phase_records`)
    but never the shared workflow model: every recorder write passes
    `stepUpdates = null` and only patches `feature_task_runtime_*` keys
    (`FeatureTaskRuntimePhaseRecorder.kt:242-264`), so `steps[]` is permanently
    all-`pending` and the canonical per-phase artifact keys that the runtime's
    own `requiredArtifactsByStep` references are never written
    (`FeatureTaskRuntimePhaseWorkflowDefinition.kt:66-77`). The generic resume
    gate therefore computes `canResume = false` for every phase past `preplan`
    (`WorkflowEngine.kt:187-189`), making generic continue/resume structurally
    dead for the runtime family and step-based reconciliation
    (`blockedStepId`, `terminalStatus` fallback) inert
    (`GoalRunnerWorkflowStores.kt:1198-1208, 983-999`). A bounded loop that
    cannot survive a crash mid-iteration is not "best shape", so this defect
    class is the foundation subtask, not a separate concern.
- Boundary contracts from `runtime-kotlin/ARCHITECTURE.md` and
  `RuntimeArchitectureTest` (unchanged from SKILL-65): application must not
  depend on Clikt/MCP/Compose/JDBC/HTTP/infra; domain/ports must not touch
  filesystem, process env, JDBC, HTTP, or entrypoint frameworks; schema
  validators live in `runtime-infra-fs` reached only through domain-owned ports;
  public data/enum/sealed types live in `model` packages; `runtime-core` is the
  only composition root.

## Problem

The runtime feature-task path was promoted to replace the prose orchestrator
(SKILL-67) on the premise that it is a deterministic *superset* of prose. It is
not: it silently dropped prose's two backward-edge remediation loops. In prose,
when review finds Blocker/Major issues or audit finds acceptance-criteria gaps,
the orchestrator sends work backward - re-spawning a fix agent (M1) or
re-planning and re-implementing (M2) - and then re-verifies, bounded by an
iteration cap. The runtime cannot do this at all: its executor only moves
forward, and `implement` is fenced out of every retry. So the runtime's only
remediation is whatever the review/audit agent fixes in-place within its own
single sealed launch - which loses the separation prose relies on (an
independent fix pass, then an independent re-review).

Two structural facts block a clean fix:

1. **The executor has no backward edge.** It is a linear `for` loop over a fixed
   phase list; there is no verdict-driven re-entry and no per-edge iteration
   accounting.
2. **Mutating phases are not safe to re-enter.** Resume re-runs the last
   incomplete phase "from scratch", and `implement` re-application is
   non-idempotent - the exact reason it is excluded today. A backward edge into
   a mutating phase is unsafe until that assumption is replaced.

And underneath both, the runtime does not maintain the shared workflow model
(`steps[]`, canonical artifacts), so generic resume/continue is structurally
dead for the family - meaning even today's forward-only runtime cannot be
resumed through the sanctioned generic tools, and a process death mid-run wedges
the workflow. A bounded loop layered on top of a resume path that does not work
would inherit that fragility.

## Goals

1. Restore prose parity for review-driven remediation (M1) and audit-gap
   remediation (M2) in the runtime, bounded and deterministic, with
   runtime-owned (not agent-self-reported) iteration accounting.
2. Make the runtime executor a bounded, verdict-driven cyclic state machine that
   can re-enter an earlier phase a capped number of times, while remaining a
   loud-failing forward pipeline in the common case.
3. Make mutating phases safe to re-enter and to resume by replacing
   "re-apply from scratch" with a reconcile-on-resume contract, removing the
   blanket `implement` retry exclusion's root justification.
4. Make the verdict that drives each loop a **structured, schema-validated**
   property of the verifying phase's output (review findings severity; audit
   gap verdict) - the runtime decides whether to loop, never the agent.
5. Make every loop crash-safe: a death mid-iteration resumes at the correct
   phase and iteration, never double-applying mutations and never wedging - which
   requires closing the resume/state-model integrity defects first.
6. Keep the prose path (`WorkflowFamily.IMPLEMENT`, `FeatureImplementWorkflow
   Definition`, its skill, CLI, MCP tools, tests) completely unchanged.
7. Preserve every existing gate: no review, audit, or validate gate is removed,
   downgraded, or made optional; the loops only add bounded backward edges.

## Non-Goals

- Do not modify or re-route the prose path or its contracts.
- Do not change the decomposition-at-planning model, the branch-setup model, the
  spec-source model, or per-phase agent assignment.
- Do not make the loops unbounded; both have explicit caps (M1 <=3, M2 <=2)
  consistent with prose and `MAX_FIX_LOOP_ITERATIONS`.
- Do not let the loop decision be an agent-discretionary free-text signal; the
  loop trigger is a structured verdict the runtime evaluates.
- Do not introduce a second orchestration loop divergent from the existing
  runner; extend the existing `RunLoop` into a state machine.
- Do not change `validate`'s intentionally-unbounded repair semantics.
- Do not weaken the loud-fail behavior on missing required upstream outputs.

## Target User Experience

A maintainer runs the runtime path exactly as today. When review finds
Blocker/Major issues, the runtime re-spawns a fix phase and re-reviews,
bounded; when audit finds gaps, it loops back through plan/implement, bounded.
Each backward edge is runtime-owned ground truth in the phase ledger and status
output:

```json
{
  "workflow_id": "wftr-20260618-...",
  "phases": [
    {"id": "implement", "status": "completed", "attempt": 1},
    {"id": "review", "status": "completed", "attempt": 1, "verdict": "changes_requested"},
    {"id": "implement_fix", "status": "completed", "loop": "review_fix", "iteration": 1},
    {"id": "review", "status": "completed", "attempt": 2, "verdict": "approved"},
    {"id": "audit", "status": "completed", "verdict": "gaps_found"},
    {"id": "plan", "status": "completed", "loop": "audit_gap", "iteration": 1},
    {"id": "implement", "status": "completed", "loop": "audit_gap", "iteration": 1},
    {"id": "review", "status": "completed", "attempt": 3, "verdict": "approved"},
    {"id": "audit", "status": "completed", "verdict": "satisfied"}
  ]
}
```

If a loop exhausts its cap without converging, the run **blocks loudly** with the
loop id, the iteration count, and the unresolved verdict - it does not advance
on unresolved Blocker/Major findings or unmet acceptance criteria. A crash at
any point resumes at the exact phase and iteration, with no double-applied
mutations.

## Acceptance Criteria

1. The runtime supports M1: when `review` returns a structured verdict of
   `changes_requested` (Blocker/Major findings present), the runtime re-spawns a
   dedicated `implement_fix` phase that addresses those findings on the current
   tree, then re-runs `review`, bounded to at most 3 review->fix iterations; on
   the first `approved` verdict it advances; on cap exhaustion it blocks loudly.
2. The runtime supports M2: when `audit` returns a structured verdict of
   `gaps_found`, the runtime loops back through `plan` -> `implement` ->
   `review` -> `audit`, bounded to at most 2 audit-gap iterations; on a
   `satisfied` verdict it advances; on cap exhaustion it blocks loudly.
3. Both loop triggers are structured, schema-validated fields of the verifying
   phase's output; the runtime evaluates them. An agent cannot advance past
   unresolved Blocker/Major findings or unmet criteria by emitting prose.
4. Mutating phases (`implement`, `implement_fix`) are idempotent on re-entry and
   resume: re-running them reconciles the tree toward the intended state rather
   than blindly re-applying, so a crash mid-phase or a backward edge never
   double-applies mutations. The blanket `implement` retry exclusion is removed
   and replaced by this contract.
5. Loop iteration counts are runtime-owned and durable (per-edge counters in the
   phase ledger), survive resume, and enforce the caps across crashes (a loop
   that already burned its cap re-blocks on resume rather than relaunching).
6. The runtime maintains the shared workflow model so resume works: per-step
   `steps[]` statuses advance and the canonical per-phase artifact keys are
   present (or the resume gate resolves presence from the runtime's records).
   Generic `feature_task_runtime_workflow_resume`/`_continue` either drive the
   family forward or report an accurate, honest status - never a false
   "missing artifacts" block when the work is recoverable.
7. The prose path is unchanged: `FeatureImplementWorkflowDefinition`, its skill,
   CLI, MCP tools, mappers, and tests are untouched.
8. Per-phase observability records each backward edge as ground truth (loop id,
   iteration, verdict, agent id, timestamps) in the existing observability/ledger
   stream; status and finished telemetry reflect loop iterations.
9. Architecture boundaries hold: `RuntimeArchitectureTest` passes; no new layer
   violations; public types live in `model` packages.
10. Maintainer validation passes:
    - `skill-bill validate`
    - `(cd runtime-kotlin && ./gradlew check)`
    - `npx --yes agnix --strict .`
    - `scripts/validate_agent_configs`

## Design Notes

- **A fix phase is not an implement re-run.** M1 adds a new `implement_fix`
  phase whose job is to address specific review findings on the already-mutated
  tree - incremental corrective work, structurally distinct from re-applying the
  plan. This is why M1 does not require relaxing the `implement` exclusion on its
  own; Subtask 3 generalizes idempotency so both phases are safe.
- **Loop, don't fork.** Extend `RunLoop` into a bounded state machine; do not
  author a parallel orchestration loop. The next phase is a function of the
  current phase plus its verdict plus the per-edge iteration counter.
- **Verdict up the strength ladder, like SKILL-65's presence -> schema move.**
  The loop trigger must be a structured, schema-validated verdict so a weak model
  cannot skip remediation by writing reassuring prose. The runtime reads the
  verdict; the agent never decides whether to loop.
- **Idempotency is the real unlock.** Replacing "re-run = re-apply" with
  "re-run = reconcile toward intended state" makes mutating phases safe to
  re-enter and to resume. This single change enables M1, enables M2, removes the
  `implement` exclusion, and incidentally hardens ordinary resume. A
  remediation-checkpoint commit between verifier-passing iterations is the
  complementary safeguard, especially for the wider M2 loop.
- **M2 reuses M1's machinery.** M2 is the same cyclic-executor + idempotency
  pattern with a wider backward edge (re-entering `plan` and `implement`) and a
  lower cap. Building M1 first proves the machinery on the contained loop before
  the wider one.
- **Resume integrity is a prerequisite, not a nicety.** The whole value of the
  runtime over prose is determinism + reliable resume. A bounded loop on top of
  a dead resume path is fragile theatre, so Subtask 1 closes the
  resume/state-model defects (the C1-C5 class from the audit) first.

## Validation Strategy

- Domain + infra-fs tests for the cyclic executor's transition function, the
  per-edge iteration ledger, the structured review/audit verdict schemas, and
  the resume gate's presence resolution for the runtime family.
- Application tests with a fake launcher proving: review->implement_fix->review
  bounded to 3 with convergence and cap-exhaustion block; audit-gap loopback
  bounded to 2; verdict-driven branching (approved vs changes_requested;
  satisfied vs gaps_found); idempotent re-entry of mutating phases (no
  double-apply); crash/resume mid-loop landing on the correct phase+iteration;
  cap enforcement across resume; and that no existing gate is bypassed.
- Tests asserting the prose family is untouched (`IMPLEMENT`/`VERIFY` behavior
  and existing goldens unchanged).
- Observability/ledger assertions that each backward edge is recorded ground
  truth; finished-telemetry reflects loop iteration counts.
- The full maintainer command set for the final gate.

## Open Questions

- Should the M1 verdict reuse the existing review output schema with an added
  structured `verdict` + `findings[]{severity}` block, or a separate review-
  verdict artifact consumed by the executor? Reusing keeps one review contract;
  separate keeps the executor's input minimal.
- Should mutating-phase idempotency be enforced primarily by the
  reconcile-on-resume agent contract (prompt + verification) or by a
  remediation-checkpoint commit between iterations, or both? Both is safest;
  commit-only changes the "commit once at the end" model and may interact with
  `suppress_pr` goal subtasks.
- For M2, should the loopback re-enter `plan` (full re-plan) or a lighter
  `replan` phase scoped to the audit gaps, to avoid re-deciding settled plan
  content? Prose re-enters plan; a scoped replan may be cheaper and lower-risk.
- Should the resume-integrity fix (Subtask 1) write the canonical artifact keys
  with real phase output (full parity, larger rows) or make the generic resume
  gate resolve presence from `feature_task_runtime_phase_records` (no
  duplication, a family-aware seam on the engine)? This is the same A-vs-B
  tradeoff surfaced in the audit.
- Does M2's `gaps_found` verdict need to carry which acceptance criteria failed,
  so the re-plan/re-implement handoff is scoped to the gaps rather than redoing
  the whole feature?
- Should cap exhaustion on either loop block, or downgrade to a
  surfaced-for-human-review terminal that still records the unresolved findings?
  Prose blocks; surfacing may be friendlier for goal-runner batches.

### Resolutions (feature complete, 2026-06-19)

- M1 verdict: reused the review output schema with an additive structured
  `verdict` + `findings[]{severity}` block (one review contract). Both fields are
  optional in `feature-task-runtime-phase-output-schema.yaml`, so no
  `contract_version` bump.
- Mutating-phase idempotency: both — the reconcile-on-resume agent contract
  (prompt directive + the `reconciled_state` reconciliation gate) AND a
  remediation-checkpoint commit established only when a backward edge reopens a
  span containing a mutating phase. The checkpoint is commit-only and never
  pushes, so `suppress_pr` goal subtasks are honored.
- M2 loopback target: re-enter FULL `plan` (matching prose), gap-scoped via the
  handoff rather than a new `replan` phase — the failing criteria are threaded
  into the re-entered plan/implement briefing (`audit_gaps:` block), so settled
  content is not re-decided without adding a phase.
- Resume-integrity (Subtask 1): Option B — the generic resume gate resolves
  per-phase presence from `feature_task_runtime_phase_records` via a domain-owned
  `RequiredArtifactPresenceResolver`, with no duplicated canonical artifact keys
  and the prose families left byte-for-byte unchanged.
- M2 `gaps_found` carries the failing acceptance criteria: yes —
  `FeatureTaskRuntimeAuditVerdict.unmetCriteria` scopes the re-plan/re-implement
  handoff to the gaps.
- Cap exhaustion: blocks loudly (both loops), matching prose — a durable terminal
  blocked record plus an observability/ledger event carrying the loop id,
  iteration count, and unresolved verdict/criteria; it never advances on
  unresolved findings or unmet criteria.

## Cross-Subtask Invariants

- Caps are runtime-owned and durable; no loop is unbounded except `validate`
  (whose existing semantics are untouched).
- No gate is removed or downgraded; loops only add bounded backward edges.
- The prose path is never modified.
- Every backward edge is recorded ground truth; nothing about a loop is
  agent-self-reported.
- Mutating phases never double-apply on re-entry or resume.
