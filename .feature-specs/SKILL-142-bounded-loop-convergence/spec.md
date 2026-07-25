# SKILL-142 - Bounded Loop Convergence: Planning Gate Parity, Blocker-Only Reopen, Lane Severity Calibration, Review Depth Tiers, Liveness

## Mode

decomposed

Five dependency-ordered units:

1. Producer-side planning projection gate parity — a plan that fails its projection
   contract never becomes a hydrated checkpoint.
2. Blocker-only reopen — only Blocker reopens `implement_fix` (governed prose plus
   parity test; no Kotlin behavior change).
3. Per-lane severity calibration — governed pack content plus validator coverage.
4. Review depth tiers — redefine `inline` as a bounded light tier, keep
   `delegated` as the full-depth default, make `auto` resolve depth by pass number
   (pass one delegated, later passes inline), and bound the reserved remediation
   pass to the remediation delta with an evidenced per-Blocker disposition.
   Depends on SKILL-141.
5. Review-phase liveness, preflight, and loop accounting — stop the supervisor
   idle-killing a healthy read-only review, fail a missing-native-worker preflight
   before the phase starts rather than after it blocks, state each backward edge's
   cap scope, and instrument mutating-phase attempts well enough to diagnose them.

Units 1–3 and 5 are independent of SKILL-141 and of each other. Unit 4 depends on
SKILL-141 landing. **Unit 5 has the largest measured wall-clock payoff** and should
land first.

## Intended Outcome

Bounded loops in the feature-task runtime terminate on evidence, and no loop
iteration is spent recovering from a defect an upstream gate should have caught.

- A planning projection that fails its contract is repaired producer-side, inside
  that phase's own bounded fix loop, before it is checkpointed or hydrated. A
  consumer never repairs an upstream producer's contract violation.
- Only a Blocker reopens `implement_fix`. Major joins Minor and Nit in the
  unaddressed-findings ledger.
- Specialist lanes emit severities calibrated so Major means "materially worsens
  behavior", not "stylistic observation".
- Review has two honest depth tiers. `delegated` stays the full-depth default;
  `inline` becomes a bounded light tier that covers the routed areas without
  specialist workers and says so; `auto` picks between them by pass number and
  records why. The reserved remediation pass runs the light tier, bounded to the
  remediation delta, and returns an evidenced disposition per prior Blocker. An
  unresolved Blocker pauses the child durably and asks the operator what to do
  next.

The immutable `review_base_sha` baseline is unchanged and remains the authority for
pass one. Only the reserved later pass is rescoped, to the remediation delta.

## Problem

### 1. The producer-side planning gate leaks into the consumer

`AGENTS.md` states the design:

> A feature-task-runtime phase owning a bounded planning projection (`preplan`,
> `plan`, `implement`) is gated producer-side: a completed output failing its
> projection contract re-enters that phase's own bounded fix loop instead of
> blocking a consumer that cannot repair it. The gate and the consumer launch seam
> share one validation function and validator port.

That parity does not hold on the goal hydration path. `GoalChildPlanningHydrator`
validates the prepared payload with `PreparedPlanningPayloadValidator`, which
calls `FeatureTaskRuntimePhaseOutputValidator.validateAndReadPhaseOutput`
(`GoalChildPlanningHydrator.kt:48,54,188`) — the **phase-output** contract. The
consumer launch seam validates the **planning projection** contract via
`FeatureTaskRuntimePlanningProjectionValidator`
(`FeatureTaskRuntimePhaseGates.kt:14`). Two different validators against two
different contracts, so a payload valid as phase output but invalid as a planning
projection passes hydration and is rejected downstream.

Observed on the SKILL-141 run, phase ledger for `wftr-20260724-184042-578i`:

```
seq 0  preplan  complete   attempt=1  execution_origin=goal-planning-hydrated
seq 1  plan     complete   attempt=1  execution_origin=goal-planning-hydrated
seq 2  implement start     attempt=1  execution_origin=agent-executed
seq 3  plan     loop_edge  driving_verdict=record_rejected loop_id=regenerate_plan edge_iteration=1
seq 4  plan     resume     attempt=2  execution_origin=agent-executed
seq 5  plan     fix_loop_iteration attempt=3 fix_loop_iteration=1
```

Quarantine entry:

```
producing_phase_id: plan   consuming_phase_id: implement
rejection_class:    planning_projection_schema
rejection_detail:   $.tasks[0].test_obligations: must have at least 1 items but found 0
```

Preplan and plan were correctly hydrated once from the parent checkpoint
(`goal-planning-hydrated`, attempt 1) — the "plan once, never re-plan in a fix
loop" invariant is intact and must stay intact. The re-planning came entirely
from a contract-invalid plan escaping its producer gate: an empty
`test_obligations` array was checkpointed as settled, hydrated into the child, and
caught only at `implement`'s launch seam, which fired `regenerate_plan` and forced
two agent-executed plan attempts. That is the consumer repairing an upstream
defect, which the design forbids.

### 2. Major reopens a loop it cannot terminate

`skills/bill-feature-goal/content.md:507`: "A Blocker or Major finding reopens
`implement_fix` ... After the bounded remediation, only an unresolved Blocker stops
advancement — a surviving Major moves on and is recorded in the ledger."

Major can *consume* the single remediation iteration but never *stop* the run. The
runtime already agrees Major is non-blocking: `GoalSubtaskReviewState.blocksAdvance`
is Blocker-only (`GoalSubtaskReviewState.kt:84-89`). The reopen rule is governed
prose contradicting the runtime's own advancement semantics.

Local telemetry, 154 review runs / 987 findings (`~/.skill-bill/review-metrics.db`):

| mode | runs | findings | per run | blockers | blockers/run |
|---|---|---|---|---|---|
| delegated | 91 | 695 | 7.6 | 26 | 0.29 |
| inline | 43 | 131 | 3.0 | 8 | 0.19 |

Delegated review emits **408 Majors across 91 runs — 4.5 per run** — each able to
reopen `implement_fix`, against **0.29 Blockers per run** that can actually stop
the run.

### 3. Lane severity is uncalibrated

Major-to-Blocker ratio by category across all 987 findings:

| category | Major | Blocker | ratio |
|---|---|---|---|
| `ux_accessibility` | 151 | 4 | 38:1 |
| `data_persistence` | 205 | 10 | 20:1 |
| `concurrency_lifecycle` | 30 | 2 | 15:1 |
| `testing_quality_gate` | 66 | 5 | 13:1 |
| `behavior_correctness` | 51 | 9 | 5.7:1 |

`behavior_correctness` is the calibration reference. A lane emitting 38 Majors per
Blocker is grading observations as material defects. The SKILL-115 admission gate
governs *whether* a finding is emitted; nothing governs *what severity means per
lane*.

Specialist routing is not the cause and must not change: observed specialist sets
vary per run from 2 areas (`architecture,platform-correctness`) to 8, so routing
already narrows to the diff.

### 4. Inline's contract claims full depth it does not deliver

`skills/bill-code-review/content.md` specifies inline as:

> `inline` always runs the **complete routed review** in the current agent context,
> **regardless of size or risk**, without spawning specialists or fabricating lane
> totals.

and, for the reserved remediation pass:

> …**apply every signal-relevant baseline and specialist rubric**, and treat
> high-risk signals as **required coverage** rather than grounds to refuse or
> delegate. Finding severity, evidence, and approval rules remain unchanged.

Measured behavior does not match that contract. On the SKILL-141 run, pass two ran
inline and reported reviewing "by reading only", naming no lanes and launching no
specialist workers, in **346,677 ms (5.8 min)** against delegated pass one's
**873,529 ms (14.6 min)** across seven lanes. Aggregate telemetry agrees: inline
averages **3.0 findings/run against delegated's 7.6**, and **0.19 Blockers/run
against 0.29**.

Inline is therefore already the light tier in practice — faster, cheaper, and
lower-yield — while its contract claims full-depth parity. The defect is the label,
not the behavior: a caller reading the governed text is told inline is equivalent
coverage, and it is not. Aligning the contract with observed behavior is a
correction, and it carries low behavioral risk precisely because inline already
runs this way.

The fix is to name the tier the behavior already is. Inline is specified as a
topology variant of delegated — same coverage, one worker — when what it should be
is a distinct depth tier: a single compact review covering the routed areas at
reduced depth, whose purpose is verification rather than an audit of every area. A
light tier has real jobs (a fast general-purpose pass, and confirming a bounded
remediation) that delegated is the wrong shape for. The contract just never
described one.

`mode:auto` inherits the same confusion. Today it selects between two paths of
nominally identical coverage, which makes it a near-meaningless choice. Once inline
is honestly lighter, auto becomes a real depth decision and must record which tier
it resolved to and why.

Independently, the reserved second pass reviews the wrong scope. Both passes review
the complete base-to-current delta, pinned to the immutable `review_base_sha` and
only growing, so pass two re-runs an open-ended search over everything pass one
already searched — surfacing a fresh plausible set unrelated to the remediation, at
a cost proportional to delta size rather than finding count. A bounding mechanism
already exists and is unused: `context:feature-remediation` is defined to bound
"the feature workflow's reserved later inline review pass" to the remediation
delta, but `skills/bill-feature-goal/content.md:159` overrides it by requiring
"the complete base-to-current delta: committed, staged, unstaged, and untracked"
for every child review. Two governed surfaces contradict each other and the
heavier one wins.

Termination is budget-shaped, not evidence-shaped: the
`review --changes_requested--> implement_fix` edge carries `perEdgeCap = 1` with
`capExhaustionBehavior = ADVANCE`, and the separate pre-`validate` Blocker gate
stops the run. The operator receives "the budget ran out", not "this finding is
still unresolved, and here is why."

Observed on the SKILL-141 run: `review_fix` exhausted its cap after one iteration
with `changes_requested` unresolved. Review itself ran exactly once and emitted 26
findings together (1 Blocker, 12 Major, 10 Minor, 3 Nit) — review is not the
iteration driver on that run; units 1 and 2 are.

### 5. A healthy review is idle-killed, and a missing native worker blocks late

Two distinct defects produced an 85-minute dead zone on the SKILL-141 subtask
(19:33–20:57 UTC), during which `review` recorded seven consecutive blocked
attempts and `goal status` continued to report `blocked: 0`.

**5a — the supervisor kills a healthy review.** Durable supervision artifact:

```
reason:            timeout
continuation_mode: killed_unresponsive_child
process_state:     killed
step_id:           review
stderr_excerpt:    Agent run stopped after 10m without durable workflow progress.
                   No file activity was observed.
last_file_activity_at: null
last_output_at:        null
```

A delegated review is read-only by construction: it reads the delta, writes no
files, and writes no durable workflow progress until it emits findings. The
measured duration of the review that eventually succeeded was **873,529 ms — 14m
34s** (`review` phase record, `duration_millis`). The idle window that kills it is
**10 minutes**. A correct, working review is therefore structurally guaranteed to
look like a hung process, and to be killed roughly two-thirds of the way through.

This is exactly the case the `idlePolicy` strategy exists for: `HEARTBEAT_EXTENDED`
lets a confirmed-alive process heartbeat extend the idle window, while
`DB_PROGRESS_ONLY` counts only DB token changes. Review runs under a policy that
demands durable progress it cannot produce until it finishes.

**5b — native-worker preflight fails after the phase starts.** The first block:

> Native-worker preflight failed: the kotlin pack declares ten
> `bill-kotlin-code-review-*` agents in `native-agents/agents.yaml`, but the
> install's rendered Claude native-agent cache contains only the eight
> `bill-feature-task-*` agents and no `bill-kotlin-code-review*` worker is
> registered in `~/.claude/agents/`. The shared delegation contract forbids
> substituting general-purpose…

The loud-fail is correct — substituting general-purpose would have been worse. The
defect is *when* it fires: after routing, lane selection, and phase entry, once per
attempt, requiring an operator retry artifact to clear
(`operator_block_retry`, retried 20:57:02 after an install rendered the agents at
20:37). A precondition that is knowable before the phase starts should be checked
before the phase starts.

**5d — a "bounded" cap is unbounded across resumes, and fix duration is
undiagnosable.** `review_fix` carries `perEdgeCap = 1` and
`FeatureTaskRuntimePhaseWorkflowDefinition` describes the span as "bounded at one
review->fix iteration". On the SKILL-141 subtask it fired **twice** — loop entries
at 21:17:03 and 22:30:05 — because the counter resets across a resume. The bound is
per-run, not per-subtask, and nothing on any operator surface reports the cumulative
count. A subtask that resumes repeatedly gets an unbounded number of fix loops while
the contract presents the bound as fixed.

The resulting cost is the run's largest single block and cannot be explained from
durable state. `implement_fix` reached `attempt_count = 4` spanning 21:17:03 to
23:07:00 — roughly **68 minutes** across two loop entries, with the final attempt at
`duration_millis = 771463` (12.9 min). Exactly one quarantine entry exists in the
whole run (`plan -> implement`), so none of that time was gate rejection,
regeneration, or supervisor kill. It was remediation work, and nothing records what
it was working on: no per-attempt finding count, no loop-entry attribution, no
breakdown. The 30-minute entries can only be guessed at.

**5c — none of this is visible.** Seven blocked review attempts, a supervisor kill,
and an 85-minute stall all left `goal status` reporting `blocked: 0`. The evidence
exists in durable artifacts (`supervision_event`, `blocked_reason`,
`goal_attempt_ledger`, per-phase `attempt_count`) but reaches no operator surface.

## Evidence Sites

- `runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/goalrunner/GoalChildPlanningHydrator.kt:48,54,188` — hydration validates via `FeatureTaskRuntimePhaseOutputValidator`, the phase-output contract.
- `runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/featuretask/FeatureTaskRuntimePhaseGates.kt:14` — the consumer seam validates via `FeatureTaskRuntimePlanningProjectionValidator`, the planning-projection contract. The two validator ports are the parity gap.
- `AGENTS.md` — "gated producer-side ... The gate and the consumer launch seam share one validation function and validator port."
- `skills/bill-feature-goal/content.md:507` — the "Blocker or Major finding reopens `implement_fix`" rule.
- `runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/workflow/taskruntime/model/GoalSubtaskReviewState.kt:84-89` — `blocksAdvance` is already Blocker-only; unit 2 needs no runtime change.
- `runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/workflow/taskruntime/model/GoalSubtaskReviewState.kt:69` — `passNumber in 1..GOAL_SUBTASK_REVIEW_MAX_PASSES`, the two-pass bound.
- `runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/workflow/taskruntime/FeatureTaskRuntimePhaseWorkflowDefinition.kt:369-377` — the `review_fix` edge: `perEdgeCap = 1`, `capExhaustionBehavior = ADVANCE`.
- `runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/workflow/taskruntime/FeatureTaskRuntimePhaseWorkflowDefinition.kt:388-408` — the record-regeneration edges at `MAX_RECORD_REGENERATION_ATTEMPTS`.
- `orchestration/contracts/goal-subtask-review-state-schema.yaml:34,38-47` — pass-two-is-inline; `reserved_pass_number` (1..2), `completed_pass_count` (0..2), `disposition`.
- `runtime-kotlin/runtime-contracts/src/main/kotlin/skillbill/contracts/workflow/GoalSubtaskReviewStateSchemaPaths.kt:3` — `GOAL_SUBTASK_REVIEW_STATE_CONTRACT_VERSION = "0.1"`, bumped by unit 4.
- `orchestration/review-orchestrator/review-skill-structure-standard.md:20`, `orchestration/shell-content-contract/SCAFFOLD_PAYLOAD.md:255` — the shared consequence rubric every pack inherits; unit 3 anchors here.
- SKILL-141 acceptance criterion 1 — the non-terminal resumable status unit 4's pause consumes.

## Acceptance Criteria

Criteria 1–5 are unit 1 (subtask 2); 6–8 unit 2 (subtask 3); 9–12 unit 3
(subtask 4); 13–21 unit 4 (subtask 5); 22–28 unit 5 (subtask 1); 29–30 apply to
every unit.

1. Unit 1 — Producer-side planning projection gate parity. Every phase owning a bounded planning projection validates its completed output
   against the **planning projection contract** producer-side, before that output
   is marked settled, checkpointed as a goal planning preparation, or hydrated into
   a child. The producer gate and the consumer launch seam call one shared
   validation function through one validator port; a second, weaker validator on
   any producer path is a defect.
2. A planning output failing its projection contract re-enters its own phase's
   bounded fix loop with the validation detail in the remediation briefing, and is
   never checkpointed or hydrated in the failing state.
3. Goal planning preparation rejects a shared preplan or subtask plan that fails
   its projection contract at write time. A child hydration that would import a
   projection-invalid payload loud-fails through the typed error rather than
   deferring rejection to the consumer.
4. The specific escape observed on SKILL-141 — `plan` completing with
   `tasks[].test_obligations` empty — is rejected producer-side by an explicit
   acceptance test, and a rejection test proves the same payload no longer reaches
   `implement`'s launch seam.
5. The `regenerate_plan`, `regenerate_preplan`, and `regenerate_implement` edges
   remain as the recovery path for genuine drift, unchanged in cap and behavior.
   Unit 1 reduces how often they fire; it does not remove them.

6. Unit 2 — Blocker-only reopen. Only an unresolved Blocker reopens `implement_fix`. Major, Minor, and Nit
   advance and are recorded in the goal-wide unaddressed-findings ledger.
   `skills/bill-feature-goal/content.md` states this without contradiction, and no
   governed surface still claims Major reopens the loop.
7. The runtime's Blocker-only `blocksAdvance` semantics are unchanged, and a test
   asserts governed prose and runtime advancement semantics agree so the
   contradiction cannot silently reappear.
8. A review pass emitting only Major findings advances to `validate` with the
   Majors in the ledger and zero `implement_fix` iterations consumed. A pass
   emitting one Blocker plus several Majors consumes exactly one iteration.

9. Unit 3 — Lane severity calibration. The shared review contract defines severity in terms of observable consequence,
   with `behavior_correctness` as the calibration reference: Blocker means the
   change breaks correctness or safety; Major means the change materially worsens
   behavior for a demonstrated scenario; stylistic, speculative, or pre-existing
   observations are Minor, Nit, or suppressed by the SKILL-115 admission gate.
10. Every maintained pack's specialist content inherits the calibrated definition
    from the shared rubric rather than restating it, and a validator rejects a
    specialist that defines its own severity vocabulary or omits the consequence
    requirement.
11. The lanes with the widest observed Major-to-Blocker spread — `ux_accessibility`
    (38:1) and `data_persistence` (20:1) — carry lane-specific consequence examples
    distinguishing a material defect from an observation. Calibration changes
    content and validation only; it does not remove a lane, narrow routing, or
    weaken the admission gate.
12. Severity distribution per lane stays measurable: the findings telemetry
    supports a per-category Major/Blocker ratio query so calibration can be
    re-evaluated against future runs.

13. Unit 4 — Inline as a bounded light tier. `inline` is redefined as a distinct depth tier, not a topology variant of
    delegated. It is one agent, no specialist workers, covering the routed areas as
    an explicit checklist at reduced depth, under a bounded budget. The governed
    text drops "the complete routed review … regardless of size or risk" and drops
    "apply every signal-relevant baseline and specialist rubric … required
    coverage", and states plainly that inline is not equivalent coverage to
    delegated.
14. What inline keeps, unchanged and inherited rather than restated: the severity
    vocabulary, the SKILL-115 admission gate, evidence and consequence
    requirements, the F-XXX risk register format, and telemetry. A lighter tier
    lowers depth and budget, never the bar a finding must clear to be emitted.
15. `delegated` is unchanged and remains the default when no mode is supplied. It
    keeps its proportional routing (2 to 8 lanes observed), its full depth, and its
    loud-fail on unlaunchable native workers with no degradation to inline.
16. `auto` resolves depth from review pass number and nothing else in this change:
    **pass one resolves to `delegated`, every later pass resolves to `inline`.**
    The rule is deliberately minimal — it encodes the existing goal-child flow that
    `goal-subtask-review-state-schema.yaml:34` already documents, rather than
    inventing thresholds nobody has calibrated. It must be expressed as one named,
    declared rule so a later change can add signals (diff size, routed area count,
    risk markers) without reworking the seam. Auto reports its resolved tier and
    the rule that decided it in review metadata and never resolves silently.
    Explicit `inline` or `delegated` always overrides auto.
17. When `parallel:<agent>` is active, both lanes share the resolved tier. Lane 2
    receives the same tier lane 1 resolved to; a light lane paired with a
    full-depth lane is rejected before either lane starts. Neither lane may
    recursively launch parallel review, unchanged from today.
18. The reserved later review pass runs inline, bounded to the remediation delta
    rather than re-reviewing the full base-to-current delta. This is the job the
    light tier exists for. `context:feature-remediation` stays the bounding
    mechanism, and `skills/bill-feature-goal/content.md:159` is corrected so the
    goal contract stops forcing "the complete base-to-current delta" on every child
    review. The immutable `review_base_sha` and baseline untracked inventory remain
    unchanged and remain the authority for pass one.
19. The bounded remediation pass emits an explicit disposition — `resolved`,
    `unresolved`, or `superseded` — for every Blocker the prior pass emitted, with
    evidence citing the specific changed lines that resolve or fail to resolve it.
    An unevidenced disposition is rejected at the parse seam. Its scope is
    `prior Blocker findings` union `diff(pre-fix tree -> post-fix tree)`, so defects
    introduced by the remediation itself are still caught.
20. When every Blocker resolves or is superseded, the child advances to `validate`.
    When any Blocker remains unresolved, the child enters the non-terminal resumable
    status from SKILL-141 — it pauses rather than blocking — persisting the
    unresolved findings, their evidence, the reserved pass state, and the resumable
    step, and surfaces a bounded operator decision. Resume reuses the persisted
    review state, `review_base_sha`, and baseline untracked inventory exactly, and
    never re-reserves a consumed pass.
21. `implement_fix` retains `perEdgeCap = 1`, but cap exhaustion is no longer the
    terminating signal: the Blocker disposition is. `capExhaustionBehavior` and the
    pre-`validate` Blocker gate are reconciled so a child cannot both advance on cap
    exhaustion and pause on an unresolved Blocker.

22. Unit 5 — Review-phase liveness. A read-only phase that produces no file activity and no durable workflow
    progress by construction — `review` in either depth tier — runs
    under an idle policy whose window cannot be shorter than that phase's expected
    duration. A confirmed-alive child heartbeat extends the window
    (`HEARTBEAT_EXTENDED`) rather than being ignored. The policy is selected through
    the existing named-strategy mechanism on `AgentRunProcessRequest`, never through
    agent-identity or phase-identity branching inside the process runner.
23. A supervisor kill of a phase whose process was confirmed alive is recorded as a
    distinct, surfaced diagnostic class, not silently folded into a generic block. A
    test asserts a review that emits no durable progress for longer than the current
    10-minute window, while alive, is not killed.
24. Native-worker preflight for a routed review runs before phase entry. A pack
    declaring specialists absent from the rendered native-agent cache fails with a
    typed, actionable error naming the missing agents and the install command that
    renders them — before routing, lane selection, and phase start, and without
    requiring an operator retry artifact to clear. Substituting general-purpose
    remains forbidden.
25. Blocked attempts, supervisor kills, and per-phase `attempt_count` are visible on
    the operator surface. `goal status` must not report `blocked: 0` while the
    current phase has accumulated blocked attempts. Output stays within the
    bounded-output contract: counts, phase id, attempt number, and diagnostic class
    only — no paths, no raw child output.

26. Every backward edge declares its cap scope explicitly — per-run or per-subtask
    — and the governed prose describing it matches the declaration. `review_fix`
    carries `perEdgeCap = 1` and is described as "bounded at one review->fix
    iteration", but its counter resets across a resume, so the bound is per-run.
    Either declare it per-run in both the definition and the prose, or make it
    per-subtask. A test asserts the declared scope matches observed reset behavior.
27. A subtask's cumulative backward-edge iteration count across all runs is durable
    and surfaced. `goal status` reports total fix iterations consumed per subtask so
    repeated resumes cannot silently multiply a bound the contract presents as
    fixed.
28. Mutating-phase attempts record enough attribution to diagnose their duration
    from durable state without re-running: per-attempt duration, the loop entry that
    caused the attempt, and the count of findings or criteria in scope for it. The
    SKILL-141 run offers no way to explain why an `implement_fix` entry took ~30
    minutes; that gap is the acceptance bar. Output stays within the bounded-output
    contract — counts and phase identity only, no paths or raw child output.

29. All units — bounded output. Goal-facing output obeys the bounded-output contract: subtask id, pass,
    per-finding verdict, counts, severity, and class/symbol-or-sanitized-stem label
    only. No path, line number, diff hunk, or raw child output reaches
    `goal_event:`, status, watch, telemetry, or PR surfaces. Location-bearing
    evidence remains retrievable only through
    `skill-bill goal findings --issue-key <KEY>`.
30. Acceptance and rejection tests prove: a projection-invalid plan is rejected
    producer-side and never hydrated; the `test_obligations: []` payload no longer
    reaches `implement`; a Major-only pass advances with zero fix iterations; prose
    and runtime advancement semantics agree; a specialist defining its own severity
    vocabulary is rejected; the light tier omits specialist workers and declares
    reduced coverage; auto records its resolved tier and deciding signals; the
    remediation pass
    emits one evidenced verdict per Blocker; a fix-introduced defect in the post-fix
    diff is caught; an unevidenced verdict is rejected; a context-inheriting
    remediation pass is bounded to the remediation delta; an unresolved Blocker
    pauses resumably and resume
    reuses the same review state and baseline; contract drift loud-fails through the
    typed error; and the repository validation gates pass:

    ```bash
    skill-bill validate
    (cd runtime-kotlin && ./gradlew check)
    npx --yes agnix --strict .
    scripts/validate_agent_configs
    ```

## Constraints

- Unit 4 depends on SKILL-141: the pause consumes the non-terminal resumable status
  SKILL-141 introduces. Do not fork a second pause mechanism.
- Units 1–3 are independent of SKILL-141 and can land first.
- Preserve the "preplan and plan execute once at the parent and are hydrated into
  the child" invariant. Unit 1 must reduce re-planning, never introduce a new path
  that re-executes settled planning.
- Do not change `review_base_sha` capture, reuse, the baseline untracked
  subtraction, or the "never substitute HEAD, origin/main, merge base, full feature
  branch, or an earlier subtask's commits" rule.
- Do not change specialist routing, remove a lane, or reduce lane coverage.
  Telemetry shows routing already narrows per diff (2 to 8 areas observed).
- Reuse the existing runtime-contract recipe for the schema bump. No silent
  migration for legacy records.
- Agent-specific behavior goes in named injectable strategies, never conditional
  branching inside the process runner.
- Preserve the F-XXX risk register, the SKILL-115 admission gate, SKILL-129 bounded
  review context, telemetry, and the learnings model.
- Preserve unrelated working-tree changes. Do not run installer or uninstall flows
  inside this change.
- `GoalChildPlanningHydrator.kt` is under active modification by the in-flight
  SKILL-141 run; unit 1 must be rebased onto SKILL-141's landed state, not onto the
  pre-run file.

## Non-Goals

- Replacing delegated with a compact prompt, or making the light tier the default.
  Delegated keeps its fan-out and stays the default: it routes proportionally (2 to
  8 lanes observed) and yields ~50% more Blockers per run than inline as measured
  today (0.29 vs 0.19).
- Removing `mode:inline` or `mode:auto`. Both survive with clarified jobs.
- Lowering the bar a finding must clear. The light tier reduces depth and budget,
  never the admission gate, evidence requirements, or severity vocabulary.
- Reducing specialist coverage, removing a review area, or narrowing routing.
- Changing the immutable per-child review baseline, or advancing it per commit.
- Removing or re-capping the record-regeneration edges.
- Weakening the admission gate or the evidence requirements for findings.
- Pausing before the single bounded fix attempt runs.
- A general operator pause/resume UX beyond the durable decision surface required.
- Reworking the `audit_gap` loop or audit-first ordering.
- The missing `goal_event:` line on the `review -> implement_fix` backward-edge
  transition observed during the SKILL-141 run. Real observability gap, separate
  issue key.
- Retroactively repairing goals already blocked under the two-pass model; a hard
  reset remains the documented recovery.

## Validation Strategy

- Validator-port parity test asserting producer gate and consumer launch seam
  resolve to one shared validation function for every planning-projection phase.
- Acceptance test: `tasks[].test_obligations` empty is rejected producer-side and
  re-enters plan's own fix loop with the detail in the briefing.
- Rejection test: a projection-invalid payload cannot be written as a goal planning
  preparation or hydrated into a child.
- Regression test asserting hydrated preplan/plan still record
  `execution_origin=goal-planning-hydrated` at attempt 1 — the plan-once invariant.
- Prose/runtime parity test asserting governed reopen semantics match
  `blocksAdvance`.
- Loop tests: Major-only pass consumes zero iterations; Blocker plus Majors consumes
  exactly one.
- Validator acceptance and rejection tests for the calibrated severity rubric and
  pack inheritance.
- Schema and parity tests for the bumped review-state contract version and 1-pass
  bounds.
- Round-trip test proving verification verdicts and evidence persist across reload.
- Scope test asserting verification input is `Blocker findings union post-fix diff`,
  rejecting a launch carrying the full base-to-current delta.
- Tier tests: inline launches no specialist workers and reports reduced coverage;
  delegated is unchanged and remains the no-argument default; auto records its
  resolved tier and the signals behind it; explicit modes override auto.
- Pause/resume test: unresolved Blocker pauses resumably; resume reuses review
  state, baseline, and consumed pass count.
- Rejection tests: unevidenced disposition; a light-tier pass claiming delegated
  equivalence;
  legacy `0.1` review-state record.
- Output test asserting no path or line number reaches goal-facing surfaces.
- Liveness test: a review child alive but emitting no durable progress past the
  current 10-minute window is not killed; a genuinely dead child still is.
- Preflight acceptance and rejection tests: a pack with missing rendered
  specialists fails before phase entry with the missing agents named; a complete
  pack proceeds.
- Operator-surface test: `goal status` reflects accumulated blocked attempts and
  supervisor kills instead of reporting `blocked: 0`.
- Cap-scope test: the declared scope of each backward edge matches its observed
  reset behavior; a resumed subtask's cumulative fix-iteration count is durable and
  reported.
- Attribution test: an `implement_fix` attempt records duration, causing loop entry,
  and in-scope finding count, and the recorded fields are sufficient to attribute a
  long attempt without re-running it.
- Focused Gradle tests for changed modules, then the full repository gates.

## Resolved Decisions

1. **Operator decision vocabulary at the pause** — `retry_fix`,
   `accept_and_advance`, `abandon_subtask`. `retry_fix` grants one fresh
   `implement_fix` iteration each time the operator chooses it, and is
   operator-granted and unbudgeted: the human is the bound, so no cap can silently
   multiply across resumes. Lands in subtask 5.
2. **`superseded` granularity** — not split in this change. `superseded` stays one
   disposition value. Distinguishing "the code was removed" from "the finding was
   wrong" is a quality signal worth feeding back into the admission gate, but it is
   a separate concern from bounding the loop.
3. **Remediation pass disposition scope** — strictly Blockers. This is cheapest and
   matches the Blocker-only advancement semantics unit 2 codifies. Majors remain in
   the unaddressed-findings ledger, unverified. Lands in subtask 5.
4. **`attempt_count` distinguishing regeneration from crash/resume re-attempt** —
   folded into unit 5 (subtask 1). It is the same durable field and the same
   operator surface as the per-attempt attribution that unit already owns.

## Subtask Mapping

Units are ordered for execution by payoff and dependency, so subtask numbering does
not follow unit numbering:

| subtask | unit | title | depends on | status |
|---|---|---|---|---|
| 1 | 5 | Review-phase liveness, preflight, and loop accounting | — | **complete** |
| 2 | 1 | Producer-side planning projection gate parity | — | pending |
| 3 | 2 | Blocker-only reopen | — | pending |
| 4 | 3 | Per-lane severity calibration | — | pending |
| 5 | 4 | Inline as a bounded light tier; remediation pass bounded to its delta | 3 (required), 1 (soft) | in progress |

Unit 5 lands first: it carries the largest measured wall-clock payoff and blocks
nothing. Unit 4 lands last — SKILL-141 has landed (merge `e586cb43`), and it also
builds on unit 2's Blocker-only semantics.

## Completed Work

### Subtask 1 — Review-phase liveness, preflight, and loop accounting (complete)

Key deliverables:

- `readOnlyPhase: Boolean` on `SkillRunRequest`; `FeatureTaskRuntimeRunLoop` sets it
  true when `run.phaseId == PHASE_REVIEW`, selecting `HEARTBEAT_EXTENDED` idle policy
  via the existing named-strategy mechanism.
- `READ_ONLY_PHASE_PROGRESS_IDLE_TIMEOUT_MINUTES` replaces the fixed 10-minute window
  for review-phase launches.
- `lastLiveHeartbeatNanos` updated on every `pollStatusHeartbeat` poll (not only every
  90s) so short idle timeouts in tests see the heartbeat.
- `GoalRunnerAttemptLedgerStore` port + `GoalRunnerAttemptLedgerSummary` data class
  exposing `blockedAttemptCount`, `supervisorKillCount`, `phaseAttemptCounts`, and
  `cumulativeFixIterations`; `WorkflowGoalRunnerOutcomeStore` implements both ports.
- `GoalRunner` reads `GoalRunnerAttemptLedgerStore` and surfaces counts on the
  operator-facing status output.
- `AgentRunCommandBuilders` reads `readOnlyPhase` and wires `HEARTBEAT_EXTENDED`.
- `bill-code-review/content.md` updated so `delegated` and `inline` descriptions
  match the strings asserted by `FeatureSpecSkillWiringContractTest`.
- `DeclaredReviewSpecialistsPort` (new port) + `FileSystemDeclaredReviewSpecialists`
  (infra-fs implementation) reads platform pack manifests statically and computes
  specialist skill names (`bill-<slug>-code-review-<area>`) before routing.
- `FeatureTaskRuntimePhaseGates.reviewNativeAgentPreflight()` calls native-worker
  preflight before phase entry using declared specialists; verifies both the invoked
  agent and `parallelReviewAgent` (when present); no-ops only when no specialists are
  declared.
- `FeatureTaskRuntimeRunLoop.runPhase()` calls `reviewNativeAgentPreflight` for
  `PHASE_REVIEW` after the budget-settle early-exit checks and before `preLaunchBlock`.
- `RuntimeComponent` wires `FileSystemDeclaredReviewSpecialists` as
  `DeclaredReviewSpecialistsPort` via kotlin-inject `@Provides`.
- `GoalRunnerLivenessSnapshot` carries `aliveAtKill: Boolean` set at snapshot
  construction in the adapter layer; `confirmedAliveKillDiagnosticClass` reads it
  directly instead of checking liveness states that no kill path can carry.
- `GoalRunnerWorkflowOutcomeStore` gains `childWorkflowLoopIterations` (reads child
  durable phase records to return `loopId → max edgeIteration`); used by
  `recordStoppedLedgerEntries` to account for edges completed within a single child run
  beyond what stop-position inference alone could see.
- `GoalRunnerLedgerContext.causingLoopEntry` and `GoalRunnerLedgerContext.reAttemptCause`
  are now populated: threaded through `LaunchRecordingContext` and set from
  `pendingCausingLoopEntry` / `pendingReAttemptCause` maps on `GoalRunner`.
- `reAttemptCauseCounts: Map<String, Int>` added to `GoalRunnerAttemptLedgerSummary`,
  `GoalRunnerStatusProjectionExtras`, and `GoalRunnerStatusProjection`; accumulated by
  `AttemptLedgerAccumulator` from the `re_attempt_cause` ledger field; regeneration
  edges labeled `"regeneration"` instead of `"crash_resume"` when child phase records
  carry a regeneration loop id.
- `goalContinuationCommand` passes `idlePolicy = unstreamedLivenessPolicy(request)` so
  goal-continuation child review runs also benefit from the heartbeat-extended window;
  `readOnlyPhase` on the `SkillRunRequest` is set by inspecting `goalContinuation` before
  constructing it.
- All 5 backward edges in `FeatureTaskRuntimePhaseWorkflowDefinition` declare
  `capScope = FeatureTaskRuntimeBackwardEdgeCapScope.PER_RUN` explicitly; a parity
  test in `FeatureTaskRuntimePhaseWorkflowDefinitionTest` asserts all edges carry it.
- Governed prose for `review_fix` and the `audit` → `implement` → `audit` loop both
  state PER_RUN scope explicitly with the counter-reset and cumulative-reporting
  semantics.
- All four repository validation gates pass.

### Prior Work On This Branch

Commit `c6f4b1c7 feat(SKILL-142): define inline review as a bounded light tier`
is already on `feat/SKILL-142-inline-review-depth-tier` and touches
`skills/bill-code-review/content.md`. Unit 4 (subtask 5) is therefore **in
progress, not greenfield**: audit that commit against criteria 13–21, keep what
already satisfies them, and implement only the remainder.

## Telemetry Caveat

All distributions above come from 154 review runs on this repository — one stack,
one author, one codebase. Enough to act on a 38:1 intra-lane ratio and on
4.5-Majors-per-run loop pressure, both structural. Not enough to conclude how the
review architecture performs on other codebases; unit 3's calibration should be
re-evaluated once multi-repo telemetry exists.
