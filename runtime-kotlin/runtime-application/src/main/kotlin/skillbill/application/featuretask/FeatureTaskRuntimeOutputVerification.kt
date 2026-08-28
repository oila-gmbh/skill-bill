package skillbill.application.featuretask

import skillbill.contracts.JsonSupport
import skillbill.review.ReviewFindingActionability
import skillbill.review.model.ReviewClaimVerdict
import skillbill.review.model.ReviewScopeDisposition
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFindingVerificationDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFindingVerificationVerdict
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeReviewFinding
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeReviewSeverity
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeReviewVerdict
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict

internal object FeatureTaskRuntimeOutputVerification {
  fun verdictFor(phaseId: String, outputObject: Map<String, Any?>?): FeatureTaskRuntimeVerdict {
    val wireVerdict = (outputObject?.get("verdict") as? String)
      ?.takeIf(String::isNotBlank)
      ?.let { value -> FeatureTaskRuntimeVerdict.rejectRemovedVerdict(value, "phase output verdict") }
    return when (phaseId) {
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW -> reviewVerdict(outputObject, wireVerdict)
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS ->
        findingVerificationVerdict(wireVerdict)
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT -> auditVerdict(wireVerdict)
      else -> wireVerdict ?: FeatureTaskRuntimeVerdict.ADVANCE
    }
  }

  fun dispositionsFrom(outputObject: Map<String, Any?>?): List<FeatureTaskRuntimeFindingVerificationDisposition> =
    findingVerificationVerdictFrom(outputObject)?.dispositions.orEmpty()

  fun verifiedFindingDispositions(
    outputObject: Map<String, Any?>?,
  ): List<FeatureTaskRuntimeFindingVerificationDisposition> =
    findingVerificationVerdictFrom(outputObject)?.verifiedDispositions.orEmpty()

  fun rejectedFindingDispositions(
    outputObject: Map<String, Any?>?,
  ): List<FeatureTaskRuntimeFindingVerificationDisposition> =
    findingVerificationVerdictFrom(outputObject)?.rejectedDispositions.orEmpty()

  fun unresolvedReviewFindings(outputObject: Map<String, Any?>?): List<FeatureTaskRuntimeReviewFinding> =
    reviewVerdictFrom(outputObject)?.unresolvedFindings.orEmpty()

  fun auditProseValue(outputObject: Map<String, Any?>?): String? = outputObject?.get("produced_outputs")
    ?.let(JsonSupport::anyToStringAnyMap)
    ?.get("value")
    ?.toString()
    ?.takeIf(String::isNotBlank)
}

private fun findingVerificationVerdict(wireVerdict: FeatureTaskRuntimeVerdict?): FeatureTaskRuntimeVerdict =
  requireNotNull(wireVerdict) {
    "verify_findings phase output is missing verdict."
  }

private fun findingVerificationVerdictFrom(
  outputObject: Map<String, Any?>?,
): FeatureTaskRuntimeFindingVerificationVerdict? {
  val dispositionsRaw = outputObject?.get("produced_outputs")
    ?.let(JsonSupport::anyToStringAnyMap)
    ?.get(FeatureTaskRuntimeVerificationSignalKeys.FINDINGS_VERIFICATION_DISPOSITIONS) as? List<*>
    ?: return null
  val dispositions = FeatureTaskRuntimeFindingVerificationDisposition.parseList(
    dispositionsRaw,
    "produced_outputs.${FeatureTaskRuntimeVerificationSignalKeys.FINDINGS_VERIFICATION_DISPOSITIONS}",
  )
  return FeatureTaskRuntimeFindingVerificationVerdict(dispositions)
}

private fun reviewVerdict(
  outputObject: Map<String, Any?>?,
  wireVerdict: FeatureTaskRuntimeVerdict?,
): FeatureTaskRuntimeVerdict {
  val reviewVerdict = reviewVerdictFrom(outputObject)
  return reviewVerdict?.verdict ?: wireVerdict ?: FeatureTaskRuntimeVerdict.ADVANCE
}

private fun auditVerdict(wireVerdict: FeatureTaskRuntimeVerdict?): FeatureTaskRuntimeVerdict = when {
  wireVerdict in FeatureTaskRuntimeVerdict.AUDIT_VERDICTS -> wireVerdict!!
  else -> FeatureTaskRuntimeVerdict.GAPS_FOUND
}

private fun reviewVerdictFrom(outputObject: Map<String, Any?>?): FeatureTaskRuntimeReviewVerdict? {
  val findingsRaw = outputObject?.get("produced_outputs")
    ?.let(JsonSupport::anyToStringAnyMap)
    ?.get(FeatureTaskRuntimeVerificationSignalKeys.REVIEW_FINDINGS) as? List<*>
    ?: return null
  val findings = findingsRaw.mapNotNull(::actionableReviewFinding)
  return FeatureTaskRuntimeReviewVerdict(findings)
}

private fun actionableReviewFinding(entry: Any?): FeatureTaskRuntimeReviewFinding? {
  val map = JsonSupport.anyToStringAnyMap(entry) ?: return null
  val severity = (map["severity"] as? String)?.takeIf(String::isNotBlank)
  val message = (map["message"] as? String)?.takeIf(String::isNotBlank)
  if (severity == null || message == null) return null
  val claimVerdict = optionalClaimVerdict(map["claim_verdict"])
  val scopeDisposition = optionalScopeDisposition(map["scope_disposition"])
  if (!ReviewFindingActionability.isActionable(claimVerdict, scopeDisposition)) {
    return null
  }
  return FeatureTaskRuntimeReviewFinding(FeatureTaskRuntimeReviewSeverity.fromWire(severity), message)
}

private fun optionalClaimVerdict(raw: Any?): ReviewClaimVerdict? {
  val value = (raw as? String)?.trim()?.takeIf(String::isNotBlank) ?: return null
  return ReviewClaimVerdict.entries.firstOrNull { it.wireValue == value }
}

private fun optionalScopeDisposition(raw: Any?): ReviewScopeDisposition? {
  val value = (raw as? String)?.trim()?.takeIf(String::isNotBlank) ?: return null
  return ReviewScopeDisposition.entries.firstOrNull { it.wireValue == value }
}
