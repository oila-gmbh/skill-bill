# SKILL-176 · Subtask 1 — Validation-depth absent-vs-default

## Scope

Make the goal-continuation conflict gate distinguish a durable field that was never recorded from one explicitly set to the default value, and audit every other field the gate compares for the same conflation.

Primary sites:

- `runtime-domain/src/main/kotlin/skillbill/workflow/taskruntime/model/FeatureTaskRuntimeGoalContinuationArtifact.kt` — `validationDepth` is non-nullable with `= ValidationDepth.DEFAULT` (line 17); `optionalGoalContinuationValidationDepth()` returns `ValidationDepth.DEFAULT` on absence (line 137); `toArtifactMap` always writes the key (line 37); `rejectUnknownGoalContinuationKeys` gates the accepted key set (line 119).
- `runtime-application/src/main/kotlin/skillbill/application/featuretask/FeatureTaskRuntimeGoalContinuationPolicy.kt` — `suppliedGoalContinuationConflict` (line 42) and its five comparisons.
- `runtime-domain/src/main/kotlin/skillbill/workflow/model/ValidationDepth.kt` — `DEFAULT = FULL`.

The launcher stamps `build_only` on non-final subtasks, so a child row lacking the key decodes as `FULL` and hard-blocks with "The supplied goal-continuation validation depth conflicts with its durable child policy." Observed on child `wftr-20260808-175505-c5po`.

Within the gate, `codeReviewMode` and `parallelReviewAgent` already guard the supplied side with `?.takeIf`, tolerating absence there but not on the durable side. `validationDepth` is the only field non-nullable on both sides. `reviewBaseline` is force-unwrapped with `requireNotNull`. Each needs a stated position, not an assumption.

## Acceptance Criteria

1. A durable goal-continuation artifact with no `validation_depth` key is distinguishable at the model level from one recording an explicit depth, and the distinction survives a `toArtifactMap` / `fromArtifactMap` round trip.
2. Resuming a goal child whose durable artifact has no recorded validation depth adopts the launcher-supplied depth and proceeds, emitting durable evidence that the depth was adopted rather than matched.
3. Resuming a goal child whose durable artifact records a depth differing from the supplied one still blocks with the existing message text unchanged.
4. Resuming with a supplied depth equal to the recorded one proceeds, with no adoption evidence emitted.
5. Every field compared in `suppliedGoalContinuationConflict` — identity, `codeReviewMode`, `validationDepth`, `parallelReviewAgent`, `reviewBaseline` — is audited, and each either distinguishes absent from set or carries a comment stating why absence cannot occur for it.
6. Any sibling field found to share the conflation is fixed in this subtask under the same absent-adopts-supplied rule.
7. A regression test resumes from a durable artifact map literally missing the `validation_depth` key, asserts the resume proceeds, and fails against the pre-fix runtime.
8. Artifacts written by the current runtime, which always include `validation_depth`, keep resuming with no behavior change.

## Non-Goals

- Changing which depth the launcher selects for a subtask.
- Changing `ValidationDepth.DEFAULT`, or the meaning of `build_only` versus `full` during validation.
- Touching the review-baseline reachability question, which is subtask 2.

## Dependencies

None. This subtask is independently landable.

## Validation Strategy

- Unit coverage on `fromArtifactMap` for the three states: key absent, key present with `build_only`, key present with `full`.
- Unit coverage on `suppliedGoalContinuationConflict` for adopt, match, and genuine-conflict paths.
- A durable-row test that seeds a workflow artifacts JSON without the key and drives the resume path, so the fix is proven at the persistence seam and not only in memory.
- Confirm `rejectUnknownGoalContinuationKeys` still rejects genuinely unknown keys after any key-set change.

## Next Path

Subtask 2 — review-base reachability and recovery.
