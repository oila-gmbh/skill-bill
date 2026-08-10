# SKILL-178 · Subtask 2 — Widen the remediation-delta finding union

## Scope

The verification pass is already delta-bounded and must stay that way. Only the
*finding* half of its scope union changes: from "the immediately preceding pass's
Blocker findings" to "all findings addressed in that round".

Primary surface —
`runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/featuretask/FeatureTaskRuntimeReviewExecutionDirective.kt`:

- `remediationContext()` (~line 65) composes the `## Reserved remediation pass`
  section. Its scope sentence (~line 74) reads *"Scope is strictly the immediately
  preceding pass's Blocker findings union diff(this round's pre-fix tree -> post-fix
  tree)"*. Widen the finding half. Keep verbatim: the prohibition on re-reviewing the
  full base-to-current delta, the statement that `review_base_sha` and the baseline
  untracked inventory are pass one's authority only, and "A defect introduced by the
  remediation itself must still be caught."
- The `## Review execution mode` sentence (~line 24) says remediation passes "run for
  as long as an unresolved Blocker survives". Widen to Blocker or Major. Leave the
  unbounded-ness itself intact.
- `materializedScope()` and `baselineUntrackedPolicy()` already suppress pass-one
  framing on a remediation pass. Do not disturb that suppression — it is what keeps
  the two scope statements from contradicting each other in one prompt.

Second surface —
`runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/featuretask/FeatureTaskRuntimePhasePromptComposer.kt`
(~line 280) composes the `bill-code-review mode:inline context:feature-remediation`
instruction. Keep it consistent with the directive.

Third surface — whatever assembles the findings handed into the `implement_fix`
briefing. Subtask 1 widened `remediationFindings` at the model, but confirm the fix
briefing actually carries the widened list (and that Minor/Nit findings are offered
to the fix pass as fixable, per parent AC 1) rather than re-filtering by severity on
the way through. Check `FeatureTaskRuntimeRunLoop` (~lines 1412 and 1666, where
`unresolvedFindings` is formatted into a suffix) and
`FeatureTaskRuntimeOutputVerification.kt:33`.

`context:feature-remediation` stays inline-only and non-recursive. Do not widen the
delegated tier onto remediation passes.

## Acceptance Criteria

1. The reserved-remediation-pass prompt states its scope as all findings addressed in that round unioned with `diff(pre-fix tree -> post-fix tree)`, not the preceding pass's Blocker findings.
2. The prompt still forbids re-reviewing the subtask's full base-to-current delta, still states that `review_base_sha` and the baseline untracked inventory are pass one's authority only, and still requires that a defect introduced by the remediation itself be caught.
3. The review-execution-mode text states that remediation passes run for as long as an unresolved Blocker or Major survives.
4. The immutable-base scope section and the baseline-untracked policy section remain suppressed on a remediation pass, so no remediation prompt contains two contradictory scope statements.
5. The `implement_fix` briefing carries every finding from the preceding pass — Blocker, Major, Minor, and Nit — with no severity re-filter applied between the verdict model and the briefing.
6. The worked example holds end to end: for a subtask touching 10 files where pass one returns 1 Blocker, 2 Major, and 4 Minor, and the fix addresses all 7 while touching 4 files, the composed verification prompt scopes review to those 4 files' delta plus the addressed findings and names none of the 6 untouched files.
7. A remediation pass still executes inline under `context:feature-remediation` and never launches a delegated or recursive parallel lane.

## Non-Goals

- Changing pass-one scope or the immutable-base framing.
- Changing severity predicates (subtask 1).
- Changing pause or operator-decision behaviour (subtask 3).
- Updating governed skill content (subtask 4).
- Making remediation passes bounded.

## Dependencies

Subtask 1 — the widened `remediationFindings` is what this subtask carries into the
prompt and the fix briefing.

## Validation Strategy

- Extend `FeatureTaskRuntimeRemediationPassPromptTest` to assert the widened scope
  sentence and the preserved prohibitions, and to assert `mode:delegated` paired with
  `context:feature-remediation` is still rejected.
- Add a prompt test for the parent worked example that asserts the 4 touched files
  appear and the 6 untouched files do not.
- Assert a pass-one prompt still carries the immutable-base section, and a pass-two
  prompt still carries neither the immutable-base nor the baseline-untracked section.
- Assert the fix briefing includes a Minor finding from the preceding pass, which is
  the regression guard for the "one loop fixes everything" behaviour.
- Build and test the affected modules.

## Next Path

Subtask 3 routes a non-converging Major into the human-resumable pause.
