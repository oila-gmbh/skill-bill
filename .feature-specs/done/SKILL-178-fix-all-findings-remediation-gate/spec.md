# SKILL-178 — Fix-all-findings remediation loop with a Blocker+Major advancement gate

Status: Prepared

## Intended Outcome

A review remediation round fixes **every** finding it was handed, and a subtask
advances past review only when no Blocker and no Major survives. When a Blocker or
Major genuinely cannot be fixed, the subtask blocks for a human instead of
advancing with the defect recorded in a ledger nobody reads.

Today the runtime has one severity gate expressed twice, and both are Blocker-only:

- `FeatureTaskRuntimeReviewSeverity.requiresRemediation` (Blocker) decides which
  findings reopen `implement_fix`.
- `FeatureTaskRuntimeReviewSeverity.blocksAdvance` (Blocker) decides which findings
  hard-stop the run.

So a Major is reported, recorded in the goal-wide unaddressed-findings ledger, and
the run moves on. In practice that ships real defects: Major is the severity for
"this is wrong but not catastrophic", and the ledger is a write-mostly surface. The
`implement_fix` pass is handed only Blockers, so an agent already sitting in the
file with full context declines to fix the Major two lines away, and a later subtask
inherits it.

After this feature there is one remediation scope (all findings) and one
advancement gate (Blocker and Major both block). Minor and Nit keep today's
behaviour exactly: fixed opportunistically when handed to the fix pass, never
blocking, always durably recorded in the ledger.

## Background

Three mechanisms already exist and must be extended rather than rebuilt.

**The remediation delta is already bounded.** `reviewExecutionDirective` composes a
`## Reserved remediation pass` section for pass ≥ 2 whose scope is *"the immediately
preceding pass's Blocker findings union diff(this round's pre-fix tree -> post-fix
tree)"* (`FeatureTaskRuntimeReviewExecutionDirective.kt:74`), and the immutable-base
framing is deliberately suppressed on those passes. The verification pass therefore
already reviews only the fix-touched surface. Only the *finding* half of that union
is Blocker-scoped and needs widening.

**Remediation is already unbounded.** SKILL-157 removed the finite cap:
`reserveNextPass()` reserves another pass for as long as an unresolved Blocker
survives, and no count mints a pause.

**The human escape hatch already exists.** `GoalSubtaskReviewState` carries a
`PAUSED` disposition, `blockerDispositions` with an `UNRESOLVED` verdict,
`operatorDecision` (`RETRY_FIX` / `ACCEPT_AND_ADVANCE`), `operatorRetryRounds`, and a
`retryReviewPending` path that re-opens the consumed pass so a granted fix is
genuinely re-reviewed instead of replaying the overridden verdict. `--operator-decision`
is already wired on the feature-task runtime CLI commands. What is missing is that a
Major never reaches this path at all, because a Major never blocks.

The change is therefore concentrated: widen two severity predicates, widen the
remediation-delta finding union, route Major into the existing pause/decision path,
and bring governed skill content and the parity-lock tests along in lockstep.

## Acceptance Criteria

1. A remediation round is handed every finding from the preceding review pass regardless of severity — Blocker, Major, Minor, and Nit — and fixes them in a single `implement_fix` loop, rather than being handed Blockers only.
2. Advancement past review requires zero unresolved Blockers and zero unresolved Majors. A surviving Minor or Nit does not block advancement and continues to be persisted in the goal-wide unaddressed-findings ledger.
3. The verification review pass stays strictly delta-bounded and never re-reviews the subtask's full base-to-current delta. Its scope union is "all findings addressed in that round" unioned with `diff(pre-fix tree -> post-fix tree)`, widened from today's "the immediately preceding pass's Blocker findings".
4. The worked example holds: a subtask touches 10 files; pass one returns 1 Blocker, 2 Major, and 4 Minor; the fix addresses all 7 and touches 4 of the 10 files; the verification pass reviews only those 4 files' delta plus the addressed findings.
5. Non-convergence — a re-review returning the same unresolved Blocker or Major set with no repository change — blocks the subtask for human input. It neither advances nor loops forever.
6. The block is durable, resumable, and retryable by a human with no attempt limit; no finite count of attempts converts the block into either advancement or terminal failure.
7. A human may hand-fix the finding on the feature branch and resume; resume reuses the existing durable subtask state rather than restarting the subtask, and the hand-applied fix is genuinely re-reviewed rather than having the overridden verdict replayed at it.
8. Governed skill content and runtime behaviour state the same rule, and the existing parity/content-lock tests pass against the new rule rather than being deleted.
9. Goal-facing output remains path-free and sanitized; location-bearing evidence stays retrievable only through `skill-bill goal findings --issue-key <KEY>`.

## Constraints

- Governed skill content and runtime behaviour must stay in lockstep; the parity and
  content-lock tests are authoritative and must be updated, not deleted.
- Do not weaken finding severity, evidence, admission, or approval rules. This
  feature widens what must be fixed; it never lowers the bar for what counts as a
  finding.
- Goal-facing surfaces (`goal`, `status`, `watch`, telemetry, PR body) stay
  sanitized: subtask id, pass, verdict/disposition, count, severity, and a
  class/symbol-or-sanitized-stem label only.
- Prose mode is being removed by in-flight SKILL-175 on
  `feat/SKILL-175-remove-prose-opencode-runtime-support`. Add no prose-mode surface;
  if a prose surface is still present when a subtask runs, leave it alone rather
  than extending it.
- Terminology: `blockerDispositions` and adjacent "Blocker" naming become
  semantically wrong once Major blocks. Renaming is in scope where it prevents a
  false invariant message, but a durable artifact key may only be renamed with a
  migration; prefer keeping the persisted key and correcting the doc/message.

## Non-Goals

- Reintroducing a finite remediation attempt cap.
- Changing pass-one scope. The immutable `review_base_sha` plus baseline untracked
  inventory remains pass one's sole authority.
- Changing audit-first ordering (`implement -> audit -> review -> validate`).
- Promoting Minor or Nit to blocking severity.
- Redesigning the unaddressed-findings ledger or its retrieval command.
- Changing how severity is assigned to a finding in the first place.

## Subtasks

1. Widen the domain severity gates so Blocker and Major both require remediation and both block advancement.
2. Widen the remediation-delta finding union to all addressed findings and update the review prompt surface.
3. Route a non-converging Major into the existing durable human-resumable pause, with unbounded retry and hand-fix resume.
4. Bring governed skill content and the parity-lock test suite into lockstep with the new rule.

## Next Path

```bash
skill-bill goal SKILL-178
```
