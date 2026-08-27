package skillbill.application.featuretask

import skillbill.application.model.FeatureTaskRuntimeImplementationContinuation
import skillbill.contracts.JsonSupport
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeImplementationAttempt
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeReceiptCheckpoint
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeReceiptDeviation
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeReceiptReconciliation
import skillbill.workflow.taskruntime.model.canonicalAuditIdentifier
import skillbill.workflow.taskruntime.model.featureTaskRuntimeIsDecompositionPackage

internal data class FeatureTaskRuntimeImplementationObligations(
  val plannedTaskIds: List<String>,
  val carriedRepairItemIds: List<String>,
  val loopId: String?,
  val edgeIteration: Int? = null,
) {
  val underAuditRepairLoop: Boolean
    get() = loopId == FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID

  val requiredIds: List<String>
    get() = if (underAuditRepairLoop) carriedRepairItemIds else plannedTaskIds

  val obligationNoun: String
    get() = if (underAuditRepairLoop) "repair item" else "plan task"
}

internal data class FeatureTaskRuntimeImplementationClaim(
  val completedTaskIds: List<String>,
  val changedPaths: List<String>,
  val unresolvedItems: List<String>,
  val deviations: List<FeatureTaskRuntimeReceiptDeviation>,
  val reconciliationEvidence: FeatureTaskRuntimeReceiptReconciliation?,
  val repositoryCheckpoint: FeatureTaskRuntimeReceiptCheckpoint?,
) {
  fun actionableDeviations(requiredIds: Collection<String>): List<FeatureTaskRuntimeReceiptDeviation> {
    val closed = completedTaskIds.toSet()
    return deviations.filter { it.ref in requiredIds && it.ref !in closed }
  }
}

internal fun featureTaskRuntimeImplementationCompletionReason(
  phaseId: String,
  obligations: FeatureTaskRuntimeImplementationObligations,
  claim: FeatureTaskRuntimeImplementationClaim,
): String? {
  val missing = featureTaskRuntimeOpenObligations(obligations, claim)
  if (missing.isNotEmpty()) {
    return "Phase '$phaseId' reported 'completed' but its implementation receipt does not close every " +
      "${obligations.obligationNoun} the authoritative plan declared. Still open: ${missing.joinToString()}. " +
      "Continue the implementation and close ${if (missing.size == 1) "it" else "them"}, or report 'blocked' " +
      "or 'failed' with a disposition; a top-level 'completed' status cannot stand in for closing the work."
  }
  if (claim.unresolvedItems.isNotEmpty()) {
    return "Phase '$phaseId' reported 'completed' but its implementation receipt carries a non-empty " +
      "'unresolved_items' (${claim.unresolvedItems.size} entr" +
      "${if (claim.unresolvedItems.size == 1) "y" else "ies"}). Resolve them and re-emit the receipt, or " +
      "report 'blocked' or 'failed'; completion and an open unresolved item cannot both be true."
  }
  val actionable = claim.actionableDeviations(obligations.requiredIds)
  if (actionable.isNotEmpty()) {
    return "Phase '$phaseId' reported 'completed' but its implementation receipt carries an actionable " +
      "deviation against an unclosed ${obligations.obligationNoun}: ${actionable.joinToString { it.ref }}. " +
      "Close that work or move it to 'unresolved_items' under a 'blocked' or 'failed' envelope."
  }
  return null
}

internal fun featureTaskRuntimeOpenObligations(
  obligations: FeatureTaskRuntimeImplementationObligations,
  claim: FeatureTaskRuntimeImplementationClaim,
): List<String> {
  val closed = claim.completedTaskIds.toSet()
  return obligations.requiredIds.filterNot { it in closed }
}

internal fun featureTaskRuntimeImplementationContinuationFrom(
  phaseId: String,
  attempts: List<FeatureTaskRuntimeImplementationAttempt>,
  obligations: FeatureTaskRuntimeImplementationObligations,
): FeatureTaskRuntimeImplementationContinuation? {
  val phaseAttempts = attempts.filter {
    it.phaseId == phaseId && it.loopId == obligations.loopId && it.edgeIteration == obligations.edgeIteration
  }
  val latest = phaseAttempts.maxByOrNull { it.sequenceNumber } ?: return null
  val closedAcrossSegments = phaseAttempts.flatMap { it.completedTaskIds }.distinct()
  val accumulated = FeatureTaskRuntimeImplementationClaim(
    completedTaskIds = closedAcrossSegments,
    changedPaths = phaseAttempts.flatMap { it.changedPaths }.distinct(),
    unresolvedItems = latest.unresolvedItems,
    deviations = latest.deviations,
    reconciliationEvidence = latest.reconciliationEvidence,
    repositoryCheckpoint = latest.repositoryCheckpoint,
  )
  return FeatureTaskRuntimeImplementationContinuation(
    phaseId = phaseId,
    segmentNumber = phaseAttempts.size + 1,
    completedTaskIds = obligations.requiredIds.filter { it in closedAcrossSegments.toSet() },
    openObligationIds = featureTaskRuntimeOpenObligations(obligations, accumulated),
    obligationNoun = obligations.obligationNoun,
    changedPaths = accumulated.changedPaths,
    deviations = latest.deviations,
    unresolvedItems = latest.unresolvedItems,
    reconciliationEvidence = latest.reconciliationEvidence,
    repositoryCheckpoint = latest.repositoryCheckpoint,
    failureDisposition = latest.failureDisposition?.wireValue,
  )
}

internal fun featureTaskRuntimePlannedTaskIdsFrom(): List<String> = emptyList()

internal fun featureTaskRuntimeCarriedRepairItemIds(briefingRepairItemIds: List<String>): List<String> =
  briefingRepairItemIds.distinct()

internal fun featureTaskRuntimeClosedRepairItemIds(outputMap: Map<String, Any?>): List<String> {
  val produced = JsonSupport.anyToStringAnyMap(outputMap["produced_outputs"]).orEmpty()
  val results = (produced["repair_item_results"] as? List<*>).orEmpty().mapNotNull { entry ->
    (JsonSupport.anyToStringAnyMap(entry)?.get("repair_item_id") as? String)?.takeIf(String::isNotBlank)
  }
  return (results + produced.stringList("deferred_repair_item_ids"))
    .map(::canonicalAuditIdentifier)
    .distinct()
}

internal fun featureTaskRuntimeIncompleteWorkGateReason(
  phaseId: String,
  outputMap: Map<String, Any?>,
  obligations: FeatureTaskRuntimeImplementationObligations,
): String? {
  if (!FeatureTaskRuntimePhaseWorkflowDefinition.isMutatingPhase(phaseId) ||
    outputMap["status"] != PHASE_OUTPUT_STATUS_COMPLETED ||
    featureTaskRuntimeIsDecompositionPackage(outputMap)
  ) {
    return null
  }
  val claim = featureTaskRuntimeImplementationClaimFrom(outputMap, obligations)
  if (claim.unresolvedItems.isNotEmpty()) {
    return "Phase '$phaseId' reported 'completed' but its implementation receipt carries a non-empty " +
      "'unresolved_items' (${claim.unresolvedItems.size} entr" +
      "${if (claim.unresolvedItems.size == 1) "y" else "ies"}). Resolve them and re-emit the receipt, or " +
      "report 'blocked' or 'failed'; completion and an open unresolved item cannot both be true."
  }
  return if (obligations.underAuditRepairLoop) {
    featureTaskRuntimeImplementationCompletionReason(
      phaseId = phaseId,
      obligations = obligations,
      claim = claim,
    )
  } else {
    null
  }
}
