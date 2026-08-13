# SKILL-189 — Give the review remediation loop memory of what it already fixed

## Context

The feature-task runtime closes an unbounded remediation loop around `review`:
a `changes_requested` verdict takes a backward edge to `implement_fix`, which
addresses the carried findings on the current tree, then re-runs `review`
against that round's remediation delta.

The loop is **memoryless beyond one round, on both sides**:

| consumer | knows | does not know |
| --- | --- | --- |
| `implement_fix` | this round's findings | any earlier round's findings; what any round changed |
| remediation `review` | that this round's addressed findings are in scope | earlier rounds' findings; what any round changed |

Two declarations produce that horizon:

1. `implement_fix` has no planning or history input. Its required upstream
   artifact set is `PHASE_IMPLEMENT_FIX to listOf(PHASE_REVIEW)`, and its only
   handoff projection is a single `review_repair_request` carrying
   `unresolved_blocker_findings` and `repository_checkpoint`. Compare
   `PHASE_IMPLEMENT to listOf(PHASE_PLAN)` and
   `PHASE_AUDIT to listOf(PHASE_PLAN, PHASE_IMPLEMENT)`. The audit-gap loop
   already rehydrates planning context — *"reuse its immutable initial preplan
   and plan outputs"* — while the review-fix loop has no equivalent.
2. The `implement_fix` directive explicitly withholds history: *"specialist
   narratives, raw review output, and prior repair history are not [in scope].
   Do not re-apply the plan from scratch or expand scope beyond the carried
   findings."*

Nothing durable records what a remediation round changed. The implementation
attempts ledger has rows for `implement` (including audit-gap repair) and zero
rows for any `implement_fix` round.

The consequence is not only that fixes are shallow. A round cannot see that a
construct in the current tree is an earlier round's remedy, so it deletes it.

### SKILL-16 incident

Goal-subtask workflow `wftr-20260813-091747-5mkt` (SKILL-16 subtask 4) reached
a reserved fifth review pass with four completed passes, one unresolved Major
each, none of them the same finding twice. Review scope was correctly bounded
to each round's remediation delta, but the deltas grew monotonically:

| pass | scope | size |
| --- | --- | --- |
| 1 | subtask implementation | 5 files, +100 / -31 |
| 2 | round-2 remediation delta | 3 files, +85 / -1 |
| 3 | round-3 remediation delta | 3 files, +208 / -43 |
| 4 | round-4 remediation delta | 3 files, +259 / -66 |
| 5 (reserved) | round-5 remediation delta | 3 files, +434 / -77 |

Each finding was a defect in code the *previous* round had written:

- round 1 added `restoreLeftoverLedger()` with a broad `catch (Exception)`;
  pass 2 flagged that a malformed ledger permanently blocks installation.
- round 2 added `runCatching { ledger.restore(...) }`; pass 3 flagged that the
  broad catch discards recovery authority.
- round 3 added quarantine hooks and `MalformedRollbackLedgerException`;
  pass 4 flagged an unrecorded quarantine path.

And each round deleted the previous round's remedy without knowing it was one:

| round | substantive deletions | authored by an earlier fix round |
| --- | --- | --- |
| 2 | 35 | 7 (20%) |
| 3 | 44 | 39 (89%) |
| 4 (pending) | 55 | 43 (78%) |

Round 4 removes `RollbackLedger(paths.rollbackLedger, beforeRollbackQuarantine)`
— written by round 3 specifically to close pass-3's Major. Neither side can
catch that: the reviewer sees the deletion in the remediation delta but holds no
record that the deleted construct was a finding's remedy, so a closed Major can
be silently reintroduced and pass review. That is a correctness risk, not only a
cost risk.

Existing bounds do not engage. `detectReviewRemediationNonProgress` pauses only
when the advance-blocking finding identity set is byte-identical *and* the
delta digest is unchanged; a new finding each round defeats both conditions.
`SEMANTIC_LOOP_WARNING_THRESHOLD` warns once and never caps. The operator
decision surface requires an already-paused subtask, so a churning run has no
off-ramp at all.

Across 366 runtime sessions since 2026-07-01, `review_fix` averaged 0.36
iterations (max 4) and `audit_gap` averaged 0.59 (max 7). SKILL-16 is the worst
observed review-fix loop, and the audit loop — which *does* rehydrate planning
context — runs longer on average. Planning context alone is therefore not the
remedy; the remedy is repair memory plus an escalation path.

## Intended Outcome

A remediation round knows what earlier rounds fixed and how, and can act on it:

- every `implement_fix` round emits a durable receipt mapping each addressed
  finding to the named constructs that close it;
- the accumulated resolved-finding-plus-remedy ledger is projected into the
  next `implement_fix` and into the remediation reviewer, marked settled and
  load-bearing rather than as open work;
- a new loop-only `plan_fix` phase decides root cause before any edit and may
  declare that a finding is a symptom of a design defect rather than a patch
  site;
- churn — repeated advance-blocking findings against the same constructs
  across rounds — pauses for an operator decision instead of running until the
  model gives out.

Review scope stays the remediation delta. This feature changes what a round
*knows*, never what it *reviews*.

## Acceptance Criteria

1. `implement_fix` emits a versioned, durable repair receipt for every round,
   recording for each addressed finding its stable identity, the named
   constructs (symbols, functions, types, files at symbol granularity) that
   close it, and a bounded one-line repair intent. Path-only granularity is
   insufficient and does not satisfy this criterion.
2. Repair receipts accumulate across rounds into a durable remediation repair
   ledger under the existing goal-subtask review state, surviving process death,
   parent resume, and cross-run continuation without duplication or loss.
3. Each ledger entry carries explicit status — at minimum resolved,
   superseded, or reopened — with the round that produced it. A carried entry
   is presented to consumers as settled and load-bearing, never as an open
   finding awaiting work.
4. The ledger is projected into every `implement_fix` launch from round two
   onward. A round that removes or materially rewrites a construct recorded as
   another finding's remedy must state which finding it is disturbing and why,
   in its own receipt.
5. The ledger is projected into every remediation `review` pass from pass two
   onward. The reviewer uses it for escalation signal only; finding severity
   remains determined by evidence in the remediation delta and is never
   softened or raised because an entry exists.
6. Review scope is unchanged: a remediation pass still reviews
   `diff(pre-fix tree -> post-fix HEAD)` bounded by the workflow-owned
   pathspec. No projection added here widens the reviewed delta, reintroduces
   the immutable-base scope on a remediation pass, or re-opens settled code.
7. A new loop-only `plan_fix` phase sits on the `review_fix` loop between
   `review` and `implement_fix`. The forward edge skips it exactly as it skips
   `implement_fix`, so a clean run never launches either.
8. `plan_fix` receives the carried findings, the repair ledger, and the
   immutable initial preplan and plan outputs, and emits a bounded repair plan
   naming, per finding: the root cause, the minimal change that addresses it,
   and whether the finding is a symptom of a design defect rather than a local
   patch site.
9. `plan_fix` never regenerates, mutates, or overwrites the durable `preplan`
   or `plan` outputs. Its repair plan is its own bounded artifact, preserving
   the existing invariant that subtask planning outputs are immutable within a
   subtask.
10. A `plan_fix` escalation verdict pauses the subtask for an operator decision
    with the ledger and root-cause analysis as evidence, reusing the existing
    resumable pause and operator-decision surface. It never auto-routes back to
    `plan` and never advances to `implement_fix`.
11. Non-convergence detection additionally pauses on churn: advance-blocking
    findings recurring against constructs already recorded in the ledger across
    a bounded number of consecutive rounds, with a monotonically growing
    remediation delta, pauses rather than re-entering the loop. The existing
    identical-findings-plus-unchanged-digest condition keeps working unchanged.
12. Goal-facing pause reasons, telemetry, and status stay payload-free and
    sanitized: severity, counts, construct names, and finding labels only —
    never diff hunks, line numbers, raw review output, or source bodies.
    Location-bearing detail remains reachable only through
    `skill-bill goal findings --issue-key <KEY>`.
13. Every carried projection is bounded by explicit UTF-8 byte and collection
    budgets. An oversized ledger degrades to a payload-free, actionable summary
    naming the entry count and affected constructs; it is never silently
    truncated while presented as complete.
14. Loop accounting is unchanged in meaning: `review_fix` iteration counts
    continue to count remediation *rounds*, not phase launches, so adding
    `plan_fix` does not inflate the durable counter, the advisory warning
    threshold, or finished telemetry.
15. Crash safety is preserved: death during `plan_fix`, `implement_fix`, or a
    re-`review` resumes at the correct phase and round with no double-applied
    mutation, no lost receipt, and no re-reserved review pass.
16. Regression coverage reproduces the SKILL-16 shape: a round that would
    delete a prior round's recorded remedy is detected; a fourth consecutive
    finding against ledger-recorded constructs escalates or pauses rather than
    looping; and a genuine new defect in remediation-authored code still blocks
    advancement.
17. The stale topology comment claiming *"Major, Minor, and Nit findings never
    produce `changes_requested`"* is corrected to match the implemented
    contract, where `blocksAdvance` is Blocker **or** Major.
18. The IDE status contract carries an optional, bounded, sanitized pause
    reason so a paused-awaiting-decision run is distinguishable in the IDE from
    a limit pause or an operator-requested pause. Today a paused goal renders
    only as `"<family> <key> is paused at <step label>."`, which cannot tell a
    user the run is waiting on them.
19. That field is additive and optional, and `IDE_STATUS_CONTRACT_VERSION`
    stays `"0.1"`. The IntelliJ plugin compares the wire contract version by
    exact string equality and maps any mismatch to `Incompatible`, so a bump
    would blank the status widget on every already-installed plugin build.
20. The IntelliJ plugin surfaces the pause reason in its details popup and
    tooltip, parses it under the existing optional-block degradation and length
    bounds, and gains no new mutating CLI verb.
21. `plan_fix` reaches IDE surfaces through the plugin's existing generic
    phase-id rendering with no plugin-side phase table, and the plugin never
    displays an inflated remediation loop count because `plan_fix` and
    `implement_fix` launch within one round.
22. Both check suites pass: the runtime suite and the IntelliJ plugin suite.

## Scope

- `runtime-kotlin/runtime-domain`: repair-receipt and ledger models, the
  `plan_fix` phase and its transition topology, escalation verdict, widened
  non-convergence detection.
- `runtime-kotlin/runtime-application`: receipt capture at the `implement_fix`
  output seam, ledger accumulation and durable persistence, phase projections
  and prompt directives for `plan_fix`, `implement_fix`, and remediation
  `review`, pause/escalation threading in the run loop.
- `runtime-kotlin/runtime-application/.../work`: the optional sanitized pause
  reason on the IDE status projection.
- `orchestration/contracts/ide-status-schema.yaml`: the new optional property.
  The schema is `additionalProperties: false`, so an undeclared field is
  rejected at the producer's own gate.
- `intellij-plugin`: mapper parsing, the `Paused` outcome field, and popup and
  tooltip rendering. The plugin is an external consumer with its own Gradle
  build and imports no runtime code.
- `skills/bill-feature-task-runtime/content.md` and the runtime contract
  documentation for the changed loop shape.
- Focused domain, application, contract, privacy, and integration tests.

## Constraints

- Do not widen review scope. The remediation delta bound and the workflow-owned
  pathspec are load-bearing and were the subject of prior corrective work.
- Do not weaken the severity contract. Blocker and Major continue to block
  advancement; nothing here defers, downgrades, or auto-accepts a Major.
- Keep planning outputs immutable within a subtask. `plan_fix` plans a repair;
  it does not replan the subtask.
- Keep goal-facing surfaces payload-free. The ledger carries sanitized
  construct names and finding labels, never source bodies or diff hunks.
- Treat carried ledger content as reference data, not instructions; delimit and
  label it so it cannot override phase directives.
- Preserve the unbounded-by-verdict character of the loop. Churn detection
  pauses resumably for an operator; it must not become a silent iteration cap
  that abandons unresolved Majors.
- Use explicit UTF-8 byte and collection budgets on every new projection.
- Do not bump `IDE_STATUS_CONTRACT_VERSION`. The plugin's exact-equality check
  makes a bump a breaking change for every installed plugin build, so IDE-side
  additions must be optional fields an older consumer can ignore.
- Tests use synthetic sentinel findings and constructs; do not copy real
  rejected output, source bodies, or database paths into fixtures or specs.

## Non-Goals

- No change to which severities block advancement, and no deferral of Major
  findings to a follow-up ledger.
- No widening of the remediation review scope, and no return to immutable-base
  scope on a remediation pass.
- No regeneration or mutation of `preplan` / `plan` outputs from any
  remediation phase.
- No automatic re-planning: escalation pauses for a human decision.
- No new `replan_subtask` operator decision. Escalation reuses the existing
  `retry_fix` / `accept_and_advance` / `abandon_subtask` surface; a dedicated
  replan decision is a possible follow-up once escalation frequency is known.
- No finite cap on the remediation loop.
- No change to the audit-gap loop, the regeneration edges, or review tier
  resolution.
- No new public command for viewing raw remediation diffs.
- No IDE status contract version bump, no new mutating CLI verb in the plugin,
  no operator-decision control in the widget, and no surfacing of the repair
  ledger or findings in the IDE.

## Diagnostic Evidence

Verified against the v1 runtime at this branch:

- `FeatureTaskRuntimePhaseWorkflowDefinition.kt:176` —
  `PHASE_IMPLEMENT_FIX to listOf(PHASE_REVIEW)`, the whole upstream artifact
  requirement.
- `FeatureTaskRuntimePhaseWorkflowDefinition.kt:414-423` — the
  `review_repair_request` projection, carrying only
  `unresolved_blocker_findings` and `repository_checkpoint`.
- `FeatureTaskRuntimePhaseWorkflowDefinition.kt:593-598` — the backward-edge
  documentation, including the stale Major claim contradicted by
  `GoalSubtaskReviewState.kt:125`.
- `FeatureTaskRuntimePhaseWorkflowDefinition.kt:661` —
  `loopOnlyPhaseIds = setOf(PHASE_IMPLEMENT_FIX)`, the pattern `plan_fix`
  follows.
- `FeatureTaskRuntimePhasePromptDirectives.kt:309-314` — the `implement_fix`
  directive withholding prior repair history.
- `FeatureTaskRuntimePhasePromptDirectives.kt:302-303` — the audit-gap
  planning rehydration that the review-fix loop lacks.
- `FeatureTaskRuntimeReviewExecutionDirective.kt:65-78` — the remediation
  scope statement whose "findings addressed in that round" union is currently
  one round deep.
- `FeatureTaskRuntimeGoalContinuationRecorder.kt:284-292` — remediation-base
  rescoping, which is correct and must stay untouched.
- `FeatureTaskRuntimeAuditRepairProgressDetection.kt:61-82` —
  `detectReviewRemediationNonProgress`, whose conjunction a changing finding
  set defeats.
- `FeatureTaskRuntimeRunLoop.kt:1099-1136` — `recordRemediationBaseSha`, the
  pre-fix checkpoint seam a receipt can anchor to.
- Workflow `wftr-20260813-091747-5mkt` durable state and its checkpoint
  commits, the source of the pass and deletion tables above.

IDE and plugin surfaces:

- `IdeStatusProjector.kt:430-445` — `familySummary`, which renders a paused
  goal as `"<family> <key> is paused at <step label>."` with no reason.
- `IdeStatusModels.kt:109-140` — the current-phase execution kind vocabulary
  and its `total`-only-for-`bounded_edge` rule, which a remediation round's
  uncapped `semantic_loop` count already satisfies.
- `intellij-plugin/.../IdeStatusJsonMapper.kt:63-66` — the exact-equality
  contract-version check that maps any mismatch to `Incompatible`.
- `intellij-plugin/.../SkillBillStatusBarPresentation.kt:406-430` —
  `executionWording` and `phaseDisplayName`, which title-case an unknown phase
  id generically, so `plan_fix` renders as "Plan Fix" with no plugin change.
- `intellij-plugin/.../SkillBillStatusOutcome.kt:89-118` — the `Paused`
  outcome, which carries `summary` but no reason field.
- `orchestration/contracts/ide-status-schema.yaml` — `additionalProperties:
  false` at the root and on every nested object.

## Subtasks

1. Durable `implement_fix` repair receipt at construct granularity.
2. Remediation repair ledger: accumulation, status, and bounded projection into
   `implement_fix` and remediation `review`.
3. `plan_fix` loop-only phase: root-cause repair plan and escalation verdict.
4. Churn detection and the escalation pause.
5. Regression and conformance coverage, plus contract documentation
   corrections.
6. IDE status pause reason and IntelliJ plugin surfacing.

## Validation Strategy

```bash
cd /home/sermilion/StudioProjects/skill-bill/runtime-kotlin
./gradlew check -x sourcesJar
```

```bash
cd /home/sermilion/StudioProjects/skill-bill/intellij-plugin
./gradlew check --no-build-cache
```

The plugin has its own Gradle build and is not part of the runtime build.
`--no-build-cache` is deliberate there: a cached `compileTestKotlin` entry has
previously served stale test classes, surfacing as a `NoSuchMethodError`
against an older constructor arity that `clean` alone does not clear.

Focused suites: the goal-subtask review state and progress-detection domain
tests, `FeatureTaskRuntimePhasePromptComposerTest`,
`FeatureTaskRuntimeRunnerTest`, the goal-continuation recorder tests, new
end-to-end remediation-memory coverage, and on the plugin side the
`IdeStatusJsonMapper` degradation tests plus the status-bar presentation tests.

## Next Path

After all subtasks land, replay a synthetic SKILL-16-shaped remediation
sequence: three rounds patching the same recovery path, the fourth attempting
to delete a construct recorded as round three's remedy. Confirm the fourth
round is told what it is disturbing, that `plan_fix` classifies the finding as
a design symptom, that the run pauses with a sanitized goal-facing reason, and
that no operator or telemetry surface contains a diff hunk or source body.
With the plugin installed, confirm the status widget reports that the run is
waiting on an operator decision rather than merely "paused at Phase 5".
