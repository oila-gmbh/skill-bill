# SKILL-142 Subtask 1 — Review-phase liveness, preflight, and loop accounting

Parent: `.feature-specs/SKILL-142-bounded-loop-convergence/spec.md` (unit 5)

## Scope

Stop the supervisor idle-killing a healthy read-only review, move native-worker
preflight ahead of phase entry, make each backward edge's cap scope explicit and
its cumulative count durable, and record enough per-attempt attribution to
diagnose a long mutating phase from durable state alone.

This unit lands first: it carries the largest measured wall-clock payoff (an
85-minute dead zone on the SKILL-141 subtask, 19:33–20:57 UTC) and depends on no
other unit.

In scope:

- Select the `review` phase's idle policy through the existing named-strategy
  mechanism on `AgentRunProcessRequest` (`idlePolicy`, `HEARTBEAT_EXTENDED`).
- Record a supervisor kill of a confirmed-alive process as a distinct diagnostic
  class rather than a generic block.
- Run native-worker preflight for a routed review before phase entry, with a
  typed error naming the missing agents and the install command that renders
  them.
- Surface blocked attempts, supervisor kills, and per-phase `attempt_count` on
  `goal status`.
- Declare each backward edge's cap scope (per-run or per-subtask) in both the
  workflow definition and the governed prose, and make a subtask's cumulative
  backward-edge iteration count durable and reported.
- Record per-attempt duration, causing loop entry, and in-scope finding/criteria
  count for mutating-phase attempts.
- Distinguish a backward-edge regeneration from a crash/resume re-attempt on the
  operator surface (folded in from parent Open Question 4).

## Evidence Sites

- Supervision artifact for the killed review: `reason: timeout`,
  `continuation_mode: killed_unresponsive_child`, `process_state: killed`,
  `step_id: review`, `last_file_activity_at: null`, `last_output_at: null`.
- The review that eventually succeeded ran `873529` ms (14m 34s) against a
  10-minute idle window.
- `runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/workflow/taskruntime/FeatureTaskRuntimePhaseWorkflowDefinition.kt:369-377`
  — the `review_fix` edge: `perEdgeCap = 1`, `capExhaustionBehavior = ADVANCE`.
- `review_fix` fired twice on one SKILL-141 subtask (21:17:03 and 22:30:05)
  because the counter resets across a resume.
- `implement_fix` reached `attempt_count = 4` spanning 21:17:03 to 23:07:00
  (~68 minutes), final attempt `duration_millis = 771463`, with exactly one
  quarantine entry in the whole run (`plan -> implement`).

## Acceptance Criteria

1. A read-only phase that produces no file activity and no durable workflow
   progress by construction — `review` in either depth tier — runs under an idle
   policy whose window cannot be shorter than that phase's expected duration. A
   confirmed-alive child heartbeat extends the window (`HEARTBEAT_EXTENDED`)
   rather than being ignored.
2. The idle policy is selected through the existing named-strategy mechanism on
   `AgentRunProcessRequest`, never through agent-identity or phase-identity
   branching inside `ProcessWaitLoop` or the process runner.
3. A supervisor kill of a phase whose process was confirmed alive is recorded as
   a distinct, surfaced diagnostic class, not silently folded into a generic
   block.
4. A test asserts a review that emits no durable progress for longer than the
   current 10-minute window, while alive, is not killed; a genuinely dead child
   still is.
5. Native-worker preflight for a routed review runs before phase entry. A pack
   declaring specialists absent from the rendered native-agent cache fails with a
   typed, actionable error naming the missing agents and the install command that
   renders them — before routing, lane selection, and phase start, and without
   requiring an operator retry artifact to clear. Substituting general-purpose
   remains forbidden.
6. Blocked attempts, supervisor kills, and per-phase `attempt_count` are visible
   on the operator surface. `goal status` must not report `blocked: 0` while the
   current phase has accumulated blocked attempts.
7. Every backward edge declares its cap scope explicitly — per-run or
   per-subtask — and the governed prose describing it matches the declaration.
   `review_fix` is either declared per-run in both the definition and the prose,
   or made per-subtask. A test asserts the declared scope matches observed reset
   behavior.
8. A subtask's cumulative backward-edge iteration count across all runs is
   durable and surfaced. `goal status` reports total fix iterations consumed per
   subtask so repeated resumes cannot silently multiply a bound the contract
   presents as fixed.
9. Mutating-phase attempts record enough attribution to diagnose their duration
   from durable state without re-running: per-attempt duration, the loop entry
   that caused the attempt, and the count of findings or criteria in scope for
   it. The SKILL-141 run offers no way to explain why an `implement_fix` entry
   took ~30 minutes; closing that gap is the acceptance bar.
10. The operator surface distinguishes a backward-edge regeneration re-attempt
    from a crash/resume re-attempt, so a regeneration loop is not misread as
    review churn.
11. All operator output stays within the bounded-output contract: counts, phase
    id, attempt number, and diagnostic class only — no paths, no line numbers, no
    raw child output.
12. Repository validation gates pass:

    ```bash
    skill-bill validate
    (cd runtime-kotlin && ./gradlew check)
    npx --yes agnix --strict .
    scripts/validate_agent_configs
    ```

## Non-Goals

- Changing review depth, routing, lane selection, or specialist coverage.
- Removing or re-capping the record-regeneration edges.
- The missing `goal_event:` line on the `review -> implement_fix` backward-edge
  transition. Real observability gap, separate issue key.
- A general operator pause/resume UX; the pause surface belongs to subtask 5.
- Running installer or uninstall flows inside this change.

## Dependency Notes

No dependencies. Independent of SKILL-141 and of every other SKILL-142 subtask.
Lands first.

Subtask 5 consumes this unit's cap-scope declaration when reconciling
`capExhaustionBehavior` with the Blocker disposition, but does not block on it.

## Validation Strategy

- Liveness test: a review child alive but emitting no durable progress past the
  current 10-minute window is not killed; a genuinely dead child still is.
- Strategy-injection test asserting the idle policy arrives through a named
  strategy on `AgentRunProcessRequest` with no agent- or phase-identity branching
  in the process runner.
- Preflight acceptance and rejection tests: a pack with missing rendered
  specialists fails before phase entry with the missing agents named; a complete
  pack proceeds.
- Operator-surface test: `goal status` reflects accumulated blocked attempts and
  supervisor kills instead of reporting `blocked: 0`.
- Cap-scope test: the declared scope of each backward edge matches its observed
  reset behavior; a resumed subtask's cumulative fix-iteration count is durable
  and reported.
- Attribution test: an `implement_fix` attempt records duration, causing loop
  entry, and in-scope finding count, and the recorded fields are sufficient to
  attribute a long attempt without re-running it.
- Output test asserting no path or line number reaches goal-facing surfaces.
- Focused Gradle tests for changed modules, then the full repository gates.

## Next Path

On completion, proceed to subtask 2 (producer-side planning projection gate
parity).
