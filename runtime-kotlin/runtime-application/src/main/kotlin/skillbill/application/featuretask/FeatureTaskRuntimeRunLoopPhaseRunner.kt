package skillbill.application.featuretask

import skillbill.workflow.taskruntime.model.acceptanceCriterionRefsFor

internal fun FeatureTaskRuntimeRunLoop.declaredCriterionRefs(): List<String> =
  acceptanceCriterionRefsFor(request.runInvariants.acceptanceCriteria.size)

// Empty by construction: every audit re-decides every declared criterion against the tree, so no
// criterion is ever durably closed against a later audit. Kept as a seam because the audit briefing
// and the open-criteria projection both read it.
internal fun FeatureTaskRuntimeRunLoop.durablyClosedCriterionRefs(): List<String> = emptyList()

internal fun FeatureTaskRuntimeRunLoop.openAuditCriterionRefs(
  closedCriterionRefs: List<String> = durablyClosedCriterionRefs(),
): List<String> = declaredCriterionRefs() - closedCriterionRefs.toSet()

internal fun FeatureTaskRuntimeRunLoop.runDeclaredReviewDriverCycle(
  run: PhaseRun,
  state: FeatureTaskRuntimeRunState,
  observability: FeatureTaskRuntimeRunObservability,
): PhaseOutcome = when (val prepared = prepareRuntimeOwnedReview(run, state)) {
  is RuntimeOwnedReviewBlocked -> prepared.outcome
  is RuntimeOwnedReviewReady -> {
    prepareLaunchForCapture(prepared.run, state, null)
    executePreparedReviewDriver(prepared, observability)
  }
}
