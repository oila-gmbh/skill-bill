package skillbill.application.featuretask

import skillbill.application.model.FeatureTaskRuntimeImplementationContinuation
import skillbill.contracts.JsonSupport
import skillbill.workflow.taskruntime.FeatureTaskRuntimeHandoffProjectionValidator
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeDeliveredProjectionRecord
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeExecutablePlan
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffProjectionValue
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeImplementationAttempt
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePlanTask
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePlanningProjectionContract
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeReceiptCheckpoint
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeReceiptDeviation
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeReceiptReconciliation
import skillbill.workflow.taskruntime.model.canonicalAuditIdentifier
import skillbill.workflow.taskruntime.model.canonicalObligationId
import skillbill.workflow.taskruntime.model.featureTaskRuntimeIsDecompositionPackage

/**
 * SKILL-150 completion gate: an implementation phase reporting `completed` advances only when its
 * receipt actually closes every obligation the authoritative plan declared.
 *
 * The failure this exists to stop is not a schema failure. A receipt can be perfectly schema-valid,
 * list `status: completed`, and still omit half the plan's task ids — the phase-output contract has
 * nothing to say about that, so the run advanced on a false claim and audit and review were handed a
 * completion that was not one. The gate is the producer-side check that closes it.
 *
 * Two rules keep it honest:
 *
 * - The planned obligations are read from the DELIVERED executable-plan projection the runtime itself
 *   persisted, never from the agent-supplied envelope. An agent that could name its own obligations
 *   could close them by naming fewer.
 * - Closure is loop-aware. Under the base implement launch the receipt owes every plan task id; under
 *   [FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID] it owes the carried repair items
 *   instead, because a repair re-entry is not asked to re-close the whole plan and judging it against
 *   plan task ids would deadlock the audit repair loop.
 *
 * [featureTaskRuntimeImplementationCompletionReason] is the ONE validation function; the run-loop gate
 * and the continuation-projection construction below both call it, per the repository rule that a gate
 * and its consumer seam share one validation function.
 */

/**
 * The obligations one implement launch owes, derived entirely from durable runtime-owned records.
 */
internal data class FeatureTaskRuntimeImplementationObligations(
  val plannedTaskIds: List<String>,
  val carriedRepairItemIds: List<String>,
  val loopId: String?,
  /**
   * The backward-edge iteration this launch belongs to, null for a base forward launch. Scopes the
   * continuation projection: a loop id alone is shared by every round of the same loop, so round 2 of
   * the audit-gap loop would otherwise inherit round 1's closures and be told a gap the audit just
   * RE-raised is already closed.
   */
  val edgeIteration: Int? = null,
) {
  val underAuditRepairLoop: Boolean
    get() = loopId == FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID

  /**
   * Under a regenerated plan this still resolves to [plannedTaskIds] — and because those are read from the
   * LATEST delivered executable-plan projection, they are the regenerated plan's ids. A receipt is therefore
   * never charged with a phantom task id carried over from a superseded plan generation.
   */
  val requiredIds: List<String>
    get() = if (underAuditRepairLoop) carriedRepairItemIds else plannedTaskIds

  val obligationNoun: String
    get() = if (underAuditRepairLoop) "repair item" else "plan task"
}

/**
 * The bounded claim an implement receipt makes, parsed from its `produced_outputs`. Nothing here is
 * trusted as authority over what was owed; it is only what the producer says it closed.
 */
internal data class FeatureTaskRuntimeImplementationClaim(
  val completedTaskIds: List<String>,
  val changedPaths: List<String>,
  val unresolvedItems: List<String>,
  val deviations: List<FeatureTaskRuntimeReceiptDeviation>,
  val reconciliationEvidence: FeatureTaskRuntimeReceiptReconciliation?,
  val repositoryCheckpoint: FeatureTaskRuntimeReceiptCheckpoint?,
) {
  /**
   * A deviation is ACTIONABLE when it references an obligation the receipt did not close: it is
   * describing work that both deviated and remains open. A deviation whose ref names a closed
   * obligation is informational — it records how finished work differed, which is exactly what the
   * field is for and must not block. This is mechanical rather than a new agent-declared flag, so an
   * agent cannot downgrade its own deviation to escape the gate.
   */
  fun actionableDeviations(requiredIds: Collection<String>): List<FeatureTaskRuntimeReceiptDeviation> {
    val closed = completedTaskIds.toSet()
    return deviations.filter { it.ref in requiredIds && it.ref !in closed }
  }
}

/**
 * Returns a payload-free reason when the receipt does not honestly close its obligations, else null.
 *
 * This is the single validation function shared by the producer-side gate and the continuation
 * projection: the set of still-open obligations the retry prompt names is by construction the same set
 * this function refused to advance on.
 */
internal fun featureTaskRuntimeImplementationCompletionReason(
  phaseId: String,
  obligations: FeatureTaskRuntimeImplementationObligations,
  claim: FeatureTaskRuntimeImplementationClaim,
  returnedText: String = "",
): String? {
  val missing = featureTaskRuntimeOpenObligations(obligations, claim, returnedText)
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

/** The obligations the claim leaves open, in the plan's declared order. */
internal fun featureTaskRuntimeOpenObligations(
  obligations: FeatureTaskRuntimeImplementationObligations,
  claim: FeatureTaskRuntimeImplementationClaim,
  returnedText: String = "",
): List<String> {
  val closedFromReceipt = claim.completedTaskIds.map(::canonicalObligationId).toSet()
  val closedFromProse = FeatureTaskRuntimePhaseOutputDerivation
    .closedObligationIds(returnedText, obligations.requiredIds)
    .toSet()
  val closed = closedFromReceipt + closedFromProse
  return obligations.requiredIds.filterNot { canonicalObligationId(it) in closed }
}

/**
 * Builds the continuation projection from the durable attempt history and the durable obligations.
 *
 * Obligations closed in an earlier segment STAY closed: closure is accumulated across every prior
 * attempt for this phase rather than taken from the latest receipt alone, so a segment that closes
 * task-1 and a later segment that closes task-2 leave only task-3 open. Returns null when there is no
 * prior attempt for this phase — the first launch has no prior receipt by construction, which is
 * exactly why this is composed here rather than declared as a required upstream projection on an
 * implement self-edge.
 *
 * Accumulation is scoped to ONE visit: same phase, same loop, same edge iteration. Segments of the
 * current visit are what "already closed" can honestly mean. An earlier round of the same loop closed
 * obligations the verifier has since re-raised, so folding its closures in would report a recurring
 * gap as closed and instruct the repair agent to skip the very item it was sent back for.
 */
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

/**
 * Reads the authoritative planned task ids out of the delivered executable-plan projection for the
 * given consumer phase. The latest delivered iteration wins, so a regenerated plan supersedes the
 * generation it replaced and the gate never charges a receipt with a phantom task id.
 *
 * Returns an empty list when no executable-plan projection has been delivered yet; the caller treats
 * that as "no plan-derived obligation to enforce" rather than inventing one.
 */
internal fun featureTaskRuntimePlannedTaskIdsFrom(
  delivered: Collection<FeatureTaskRuntimeDeliveredProjectionRecord>,
  consumerPhaseId: String,
): List<String> {
  val projection = delivered
    .filter { it.consumerPhaseId == consumerPhaseId }
    .sortedBy { it.iteration }
    .lastOrNull { record ->
      record.envelope.projections.any {
        it.projectionContractId == FeatureTaskRuntimePlanningProjectionContract.EXECUTABLE_PLAN_ID
      }
    }
    ?.envelope
    ?.projections
    ?.firstOrNull { it.projectionContractId == FeatureTaskRuntimePlanningProjectionContract.EXECUTABLE_PLAN_ID }
    ?: return emptyList()
  val fromStructured = projection.fields
    .firstOrNull { it.name == FeatureTaskRuntimeExecutablePlan.FIELD_TASKS }
    ?.value
    ?.let { it as? FeatureTaskRuntimeHandoffProjectionValue.TextList }
    ?.items
    ?.mapNotNull(FeatureTaskRuntimePlanTask.Companion::taskIdFromBriefingLine)
    .orEmpty()
  if (fromStructured.isNotEmpty()) return fromStructured
  val receipt = projection.fields
    .firstOrNull {
      it.name == FeatureTaskRuntimeHandoffProjectionValidator.PHASE_OUTPUT_RECEIPT_FIELD
    }
    ?.value
    ?.let { it as? FeatureTaskRuntimeHandoffProjectionValue.Text }
    ?.text
    ?: return emptyList()
  return plannedTaskIdsFromPhaseOutputReceipt(receipt)
}

private fun plannedTaskIdsFromPhaseOutputReceipt(receipt: String): List<String> {
  val json = firstJsonObjectInPlanningPayload(receipt) ?: return emptyList()
  val envelope = JsonSupport.parseObjectOrNull(json)
    ?.let(JsonSupport::jsonElementToValue)
    ?.let(JsonSupport::anyToStringAnyMap)
    ?: return emptyList()
  val produced = JsonSupport.anyToStringAnyMap(envelope["produced_outputs"]).orEmpty()
  val tasks = produced["tasks"] as? List<*> ?: return emptyList()
  return tasks.mapNotNull { entry ->
    (JsonSupport.anyToStringAnyMap(entry)?.get("task_id") as? String)
      ?.trim()
      ?.takeIf(String::isNotBlank)
  }
}

/**
 * The repair items an audit-gap re-entry carries. Read from the receipt's own result/deferral lists is
 * NOT acceptable as authority, so this takes the ids the runtime delivered in the launch briefing.
 */
internal fun featureTaskRuntimeCarriedRepairItemIds(briefingRepairItemIds: List<String>): List<String> =
  briefingRepairItemIds.distinct()

/**
 * Repair items a receipt closes: an explicitly deferred item is closed for the purposes of this
 * segment (the deferral is itself the governed outcome), so it does not hold the repair loop open.
 */
internal fun featureTaskRuntimeClosedRepairItemIds(outputMap: Map<String, Any?>): List<String> {
  val produced = JsonSupport.anyToStringAnyMap(outputMap["produced_outputs"]).orEmpty()
  val results = (produced["repair_item_results"] as? List<*>).orEmpty().mapNotNull { entry ->
    (JsonSupport.anyToStringAnyMap(entry)?.get("repair_item_id") as? String)?.takeIf(String::isNotBlank)
  }
  // Canonicalized for the same reason the audit-repair result gate canonicalizes: the repair-plan
  // schema permits an uppercase AC- prefix and plan ingest lowercases it, so a remediation echoing the
  // identifier as the criterion spells it must still count as closing that item. Without this the gate
  // read an uppercase echo as an unclosed obligation and deadlocked the audit repair loop.
  return (results + produced.stringList("deferred_repair_item_ids"))
    .map(::canonicalAuditIdentifier)
    .distinct()
}

/**
 * The producer-side gate call site, extracted from the run loop's already-long settle function.
 *
 * Returns a reason only for a mutating phase that CLAIMS completion. A blocked or failed envelope
 * settles through the terminal path before reaching here, and a decomposition package is exempt
 * because it stops the run at planning and owes no implementation receipt at all.
 */
internal fun featureTaskRuntimeIncompleteWorkGateReason(
  phaseId: String,
  outputMap: Map<String, Any?>,
  obligations: FeatureTaskRuntimeImplementationObligations,
  returnedText: String = "",
): String? {
  val gateApplies = FeatureTaskRuntimePhaseWorkflowDefinition.isMutatingPhase(phaseId) &&
    outputMap["status"] == PHASE_OUTPUT_STATUS_COMPLETED &&
    !featureTaskRuntimeIsDecompositionPackage(outputMap) &&
    obligations.requiredIds.isNotEmpty()
  if (!gateApplies) return null
  return featureTaskRuntimeImplementationCompletionReason(
    phaseId = phaseId,
    obligations = obligations,
    claim = featureTaskRuntimeImplementationClaimFrom(outputMap, obligations, returnedText),
    returnedText = returnedText,
  )
}
