# SKILL-178 · Subtask 3 — Human-resumable block on a non-converging Blocker or Major

## Scope

Route a non-converging Major into the pause path a non-converging Blocker already
takes, and confirm that path satisfies the unbounded-retry and hand-fix-resume
requirements end to end.

The mechanism already exists in
`runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/workflow/taskruntime/model/GoalSubtaskReviewState.kt`
and must be extended, not rebuilt:

- `GoalSubtaskReviewDisposition.PAUSED` with `pausedForOperatorDecision`.
- `blockerDispositions` carrying a `GoalSubtaskBlockerDispositionVerdict.UNRESOLVED`.
- `acceptsOperatorDecision` (~line 511), which today opens on an unresolved Blocker
  disposition or a last pass whose `blocksAdvance` is true. Subtask 1 already widens
  `blocksAdvance`, so verify a Major reaches this predicate rather than assuming it.
- `operatorDecision` (`RETRY_FIX` / `ACCEPT_AND_ADVANCE`), `pauseRelease`, and
  `operatorRetryRounds`.
- `reserveNextPass()`'s `retryReviewPending` branch (~line 445), which re-opens the
  already-consumed pass and drops the stale result and its pause, so a granted fix is
  genuinely re-reviewed instead of replaying the overridden verdict. This is the
  mechanism behind parent AC 7 — verify it, and fix it if it does not hold for a
  hand-applied fix.

CLI surface: `--operator-decision` already exists in
`runtime-kotlin/runtime-cli/src/main/kotlin/skillbill/cli/featuretask/FeatureTaskRuntimeCliCommands.kt`
(~lines 167, 237, 364). Confirm a human can reach it for a paused *goal* subtask, not
only a standalone feature-task run. If `skill-bill goal` has no path to record the
decision, add one; the parent requires that a human can weigh in and resume, and a
decision surface reachable only from a different command does not satisfy that.

Non-convergence detection — a re-review returning the same unresolved set with no
repository change — already exists as a stop path (see the SKILL-157 artifacts under
`.feature-specs/done/`). Widen its finding set to Blocker and Major. Do not add a
count-based cap: no finite number of attempts may convert the pause into either
advancement or terminal failure.

Sanitization is a hard constraint here. The pause reason reaching goal-facing output
carries severity, count, and a sanitized label only — never a path, line number, or
diff hunk.

## Acceptance Criteria

1. A re-review that returns the same unresolved Major set with no repository change pauses the subtask for an operator decision, exactly as the same situation with a Blocker does today.
2. A paused subtask is non-terminal and durable: the goal reports it as blocked-awaiting-human rather than failed, and the durable state survives process exit and resume.
3. `acceptsOperatorDecision` opens for a subtask whose last completed pass carries an unresolved Major, so a human can act on it.
4. No finite count of remediation passes, retry rounds, or resumes converts the pause into automatic advancement or into terminal failure; `operatorRetryRounds` is unbounded.
5. A human can record an operator decision for a paused goal subtask through a documented `skill-bill` command, without hand-editing durable state or `decomposition-manifest.yaml`.
6. Granting a retry re-opens the consumed review pass and drops the stale result and its pause, so the hand-applied fix is genuinely re-reviewed rather than having the overridden verdict replayed at it.
7. A human may hand-fix the finding on the feature branch and resume; resume reuses the existing durable subtask state — `review_base_sha`, baseline untracked inventory, completed pass accounting — and does not restart the subtask from planning.
8. The pause reason surfaced to goal-facing output contains no path, line number, diff hunk, or raw child-review output; location-bearing evidence remains reachable only through `skill-bill goal findings --issue-key <KEY>`.

## Non-Goals

- Reintroducing a finite remediation or retry cap.
- Changing severity predicates (subtask 1) or remediation-delta scope (subtask 2).
- Redesigning the operator-decision vocabulary; `RETRY_FIX` and `ACCEPT_AND_ADVANCE`
  are sufficient.
- Adding an interactive prompt inside the runtime; the decision is recorded out of
  band by a human and consumed on resume.
- Auto-detecting that a human hand-fixed something. Resume plus re-review is the
  contract.

## Dependencies

Subtask 1 — `blocksAdvance` must already include Major before a Major can reach the
pause path.

## Validation Strategy

- State-machine tests: a pass whose findings are Major-only and unchanged across two
  passes with no repository change reaches `PAUSED`; the same findings with a
  repository change reserve another pass instead.
- Assert the `PAUSED` invariant accepts a Major-only unresolved disposition (the
  invariant message widened in subtask 1 must match real behaviour here).
- Assert unboundedness explicitly: drive many retry rounds and assert no transition
  to advancement or failure — the regression guard against a cap creeping back.
- Round-trip a paused state through durable persistence and resume, asserting
  `review_base_sha`, baseline untracked inventory, and completed pass accounting are
  byte-identical and that the subtask does not re-enter planning.
- Simulate the hand-fix path: pause, mutate the tree out of band, record `RETRY_FIX`,
  resume, and assert the re-opened pass re-reviews rather than replaying the prior
  verdict.
- Assert the goal-facing pause reason is path-free with a snapshot or a
  no-path-pattern assertion.
- Build and test the affected modules.

## Next Path

Subtask 4 brings governed content and the parity locks into lockstep.
