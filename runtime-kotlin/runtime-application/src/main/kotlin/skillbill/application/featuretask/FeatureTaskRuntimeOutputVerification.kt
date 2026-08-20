package skillbill.application.featuretask

import skillbill.contracts.JsonSupport
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditCriterionGap
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditSeverity
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditVerdict
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeReviewFinding
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeReviewSeverity
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeReviewVerdict
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict

@Suppress("TooManyFunctions")
internal object FeatureTaskRuntimeOutputVerification {
  fun verdictFor(phaseId: String, outputObject: Map<String, Any?>?): FeatureTaskRuntimeVerdict {
    val wireVerdict = (outputObject?.get("verdict") as? String)
      ?.takeIf(String::isNotBlank)
      ?.let { value -> FeatureTaskRuntimeVerdict.rejectRemovedVerdict(value, "phase output verdict") }
    return when (phaseId) {
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW -> reviewVerdict(outputObject, wireVerdict)
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT -> auditVerdict(outputObject, wireVerdict)
      else -> wireVerdict ?: FeatureTaskRuntimeVerdict.ADVANCE
    }
  }

  fun unresolvedReviewFindings(outputObject: Map<String, Any?>?): List<FeatureTaskRuntimeReviewFinding> =
    reviewVerdictFrom(outputObject)?.unresolvedFindings.orEmpty()

  fun unmetAuditCriteria(outputObject: Map<String, Any?>?): List<String> =
    auditVerdictFrom(outputObject)?.blockingCriteria?.map { it.message }.orEmpty()

  fun auditGapPayloadError(outputObject: Map<String, Any?>): String? {
    val wireVerdict = outputObject["verdict"] as? String
    val producedOutputs = JsonSupport.anyToStringAnyMap(outputObject["produced_outputs"])
    rejectedCriteriaAliasError(producedOutputs)?.let { return it }
    val raw = producedOutputs?.get(FeatureTaskRuntimeVerificationSignalKeys.AUDIT_UNMET_CRITERIA)
    if (wireVerdict == FeatureTaskRuntimeVerdict.SATISFIED.wireValue) return auditSatisfiedPayloadError(raw)
    val parsedCriteria = (raw as? List<*>)?.mapNotNull(::auditCriterionGap).orEmpty()
    val criteriaDriveGapsFound = parsedCriteria.any { it.severity.blocksAuditGap }
    return when {
      wireVerdict == FeatureTaskRuntimeVerdict.GAPS_FOUND.wireValue && raw is List<*> && raw.isEmpty() ->
        "Audit verdict 'gaps_found' contradicts empty produced_outputs.unmet_criteria."
      wireVerdict != FeatureTaskRuntimeVerdict.GAPS_FOUND.wireValue && !criteriaDriveGapsFound -> null
      raw !is List<*> -> "Audit verdict 'gaps_found' requires a non-empty produced_outputs.unmet_criteria array."
      raw.isEmpty() || parsedCriteria.size != raw.size ->
        "Audit verdict 'gaps_found' requires every produced_outputs.unmet_criteria entry " +
          "to carry a non-blank message and severity blocker or major; move minor and nit findings " +
          "to produced_outputs.${FeatureTaskRuntimeVerificationSignalKeys.AUDIT_NON_BLOCKING_FINDINGS}."
      else -> null
    }
  }

  private fun reviewVerdict(
    outputObject: Map<String, Any?>?,
    wireVerdict: FeatureTaskRuntimeVerdict?,
  ): FeatureTaskRuntimeVerdict {
    val reviewVerdict = reviewVerdictFrom(outputObject)
    return reviewVerdict?.verdict ?: wireVerdict ?: FeatureTaskRuntimeVerdict.ADVANCE
  }

  private fun auditVerdict(
    outputObject: Map<String, Any?>?,
    wireVerdict: FeatureTaskRuntimeVerdict?,
  ): FeatureTaskRuntimeVerdict = auditVerdictFrom(outputObject)?.verdict
    ?: wireVerdict?.takeIf(FeatureTaskRuntimeVerdict.AUDIT_VERDICTS::contains)
    ?: FeatureTaskRuntimeVerdict.ADVANCE

  private fun reviewVerdictFrom(outputObject: Map<String, Any?>?): FeatureTaskRuntimeReviewVerdict? {
    val findingsRaw = outputObject?.get("produced_outputs")
      ?.let(JsonSupport::anyToStringAnyMap)
      ?.get(FeatureTaskRuntimeVerificationSignalKeys.REVIEW_FINDINGS) as? List<*>
      ?: return null
    val findings = findingsRaw.mapNotNull { entry ->
      val map = JsonSupport.anyToStringAnyMap(entry) ?: return@mapNotNull null
      val severity = (map["severity"] as? String)?.takeIf(String::isNotBlank) ?: return@mapNotNull null
      val message = (map["message"] as? String)?.takeIf(String::isNotBlank) ?: return@mapNotNull null
      FeatureTaskRuntimeReviewFinding(FeatureTaskRuntimeReviewSeverity.fromWire(severity), message)
    }
    return FeatureTaskRuntimeReviewVerdict(findings)
  }

  private fun auditVerdictFrom(outputObject: Map<String, Any?>?): FeatureTaskRuntimeAuditVerdict? {
    val producedOutputs = outputObject?.get("produced_outputs")?.let(JsonSupport::anyToStringAnyMap)
    val gapsRaw = producedOutputs?.get(FeatureTaskRuntimeVerificationSignalKeys.AUDIT_UNMET_CRITERIA) as? List<*>
      ?: return null
    val gaps = gapsRaw.mapNotNull(::auditCriterionGap)
    return FeatureTaskRuntimeAuditVerdict(gaps)
  }

  private fun auditCriterionGap(entry: Any?): FeatureTaskRuntimeAuditCriterionGap? {
    val parsed = if (entry is String) {
      entry.takeIf(String::isNotBlank)?.let { it to FeatureTaskRuntimeAuditSeverity.MAJOR }
    } else {
      val map = JsonSupport.anyToStringAnyMap(entry)
      val message = map?.let { ((it["message"] ?: it["criterion"]) as? String)?.takeIf(String::isNotBlank) }
      val severity = map?.let {
        runCatching { FeatureTaskRuntimeAuditSeverity.fromWire(it["severity"] as? String) }.getOrNull()
      }
      if (message != null && severity?.blocksAuditGap == true) message to severity else null
    }
    return parsed?.let { (message, severity) -> FeatureTaskRuntimeAuditCriterionGap(message, severity) }
  }
}

private fun rejectedCriteriaAliasError(producedOutputs: Map<String, Any?>?): String? {
  val alias = FeatureTaskRuntimeVerificationSignalKeys.AUDIT_FAILING_CRITERIA_REJECTED_ALIAS
  if (producedOutputs?.containsKey(alias) != true) return null
  return "Audit produced_outputs carries '$alias'; the canonical unmet-criteria key is " +
    "'${FeatureTaskRuntimeVerificationSignalKeys.AUDIT_UNMET_CRITERIA}'. Rename the array and pair it with a " +
    "complete produced_outputs.audit_repair_plan. The audit criteria signal has exactly one representation " +
    "so no alias can reach the audit_gap edge without passing the repair-plan gate."
}

private fun auditSatisfiedPayloadError(raw: Any?): String? = when {
  raw !is List<*> -> "Audit verdict 'satisfied' requires an explicit empty produced_outputs.unmet_criteria array."
  raw.isNotEmpty() -> "Audit verdict 'satisfied' contradicts non-empty produced_outputs.unmet_criteria."
  else -> null
}
