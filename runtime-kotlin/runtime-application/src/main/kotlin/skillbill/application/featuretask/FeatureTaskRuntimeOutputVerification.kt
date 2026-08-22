package skillbill.application.featuretask

import skillbill.contracts.JsonSupport
import skillbill.review.ReviewFindingActionability
import skillbill.review.model.ReviewClaimVerdict
import skillbill.review.model.ReviewScopeDisposition
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditCriterionGap
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditSeverity
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditVerdict
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairPlan
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeReviewFinding
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeReviewSeverity
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeReviewVerdict
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict

/**
 * Reads the verification signals a settled phase's structured output carries. Every entry point
 * takes the ALREADY-PARSED output object rather than the raw payload: the phase-output contract
 * accepts YAML and fenced or prose-trailed JSON, so a raw-text JSON parse here would silently see
 * nothing for those shapes and report [FeatureTaskRuntimeVerdict.ADVANCE] for an audit that in fact
 * reported gaps. The caller owns parsing through the same validator that admitted the output.
 */
@Suppress("TooManyFunctions")
internal object FeatureTaskRuntimeOutputVerification {
  fun verdictFor(phaseId: String, outputObject: Map<String, Any?>?): FeatureTaskRuntimeVerdict {
    val wireVerdict = (outputObject?.get("verdict") as? String)
      ?.takeIf(String::isNotBlank)
      ?.let(FeatureTaskRuntimeVerdict::fromWire)
    return when (phaseId) {
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW -> reviewVerdict(outputObject, wireVerdict)
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT -> auditVerdict(outputObject, wireVerdict)
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN_FIX -> planFixVerdict(outputObject, wireVerdict)
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
        "Audit verdict 'gaps_found' requires every produced_outputs.unmet_criteria entry to carry a " +
          "criterion ref and a one-line note on what is missing; move minor and nit findings to " +
          "produced_outputs.${FeatureTaskRuntimeVerificationSignalKeys.AUDIT_NON_BLOCKING_FINDINGS}."
      else -> null
    }
  }

  fun repairPlanFrom(outputObject: Map<String, Any?>?): FeatureTaskRuntimeRepairPlan? = outputObject
    ?.get("produced_outputs")
    ?.let(JsonSupport::anyToStringAnyMap)
    ?.get("repair_plan")
    ?.let(JsonSupport::anyToStringAnyMap)
    ?.let { raw ->
      runCatching { FeatureTaskRuntimeRepairPlan.fromArtifactMap(raw, "produced_outputs.repair_plan") }.getOrNull()
    }

  private fun planFixVerdict(
    outputObject: Map<String, Any?>?,
    wireVerdict: FeatureTaskRuntimeVerdict?,
  ): FeatureTaskRuntimeVerdict {
    val plan = repairPlanFrom(outputObject) ?: return wireVerdict ?: FeatureTaskRuntimeVerdict.ADVANCE
    return if (plan.escalates) {
      FeatureTaskRuntimeVerdict.ESCALATED
    } else {
      FeatureTaskRuntimeVerdict.REPAIR_PLANNED
    }
  }

  private fun reviewVerdict(
    outputObject: Map<String, Any?>?,
    wireVerdict: FeatureTaskRuntimeVerdict?,
  ): FeatureTaskRuntimeVerdict {
    val reviewVerdict = reviewVerdictFrom(outputObject)
    return reviewVerdict?.verdict ?: wireVerdict ?: FeatureTaskRuntimeVerdict.ADVANCE
  }

  // The audit verdict gates entry into review, so it must be canonical rather than whatever string
  // the phase happened to emit: FeatureTaskRuntimeVerdict.fromWire accepts any non-blank value, and
  // an audit reporting no unmet criteria under a synonym ("pass", "Satisfied") would otherwise
  // settle with an off-vocabulary verdict and block a run that has no gap at all. The derived
  // verdict wins wherever a criteria array makes it decidable; a bare wire verdict is honoured only
  // when it is a known audit verdict, so an undecidable audit blocks loudly instead of advancing.
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

  private fun auditVerdictFrom(outputObject: Map<String, Any?>?): FeatureTaskRuntimeAuditVerdict? {
    val producedOutputs = outputObject?.get("produced_outputs")?.let(JsonSupport::anyToStringAnyMap)
    val gapsRaw = producedOutputs?.get(FeatureTaskRuntimeVerificationSignalKeys.AUDIT_UNMET_CRITERIA) as? List<*>
      ?: return null
    val gaps = gapsRaw.mapNotNull(::auditCriterionGap)
    return FeatureTaskRuntimeAuditVerdict(gaps)
  }

  /**
   * One unmet-criterion entry: the criterion ref and one dense note that diagnoses the gap and
   * carries the implement-ready fix plan. Every entry an audit reports blocks the run, so there is
   * no severity to read — an audit that considers a finding non-blocking puts it in
   * non_blocking_findings instead of grading it here.
   */
  private fun auditCriterionGap(entry: Any?): FeatureTaskRuntimeAuditCriterionGap? {
    val map = JsonSupport.anyToStringAnyMap(entry) ?: return null
    val criterion = (map["criterion"] as? String)?.takeIf(String::isNotBlank) ?: return null
    val note = (map["note"] as? String)?.takeIf(String::isNotBlank) ?: return null
    return FeatureTaskRuntimeAuditCriterionGap("$criterion: $note", FeatureTaskRuntimeAuditSeverity.MAJOR)
  }
}

private fun rejectedCriteriaAliasError(producedOutputs: Map<String, Any?>?): String? {
  val alias = FeatureTaskRuntimeVerificationSignalKeys.AUDIT_FAILING_CRITERIA_REJECTED_ALIAS
  if (producedOutputs?.containsKey(alias) != true) return null
  return "Audit produced_outputs carries '$alias'; the canonical unmet-criteria key is " +
    "'${FeatureTaskRuntimeVerificationSignalKeys.AUDIT_UNMET_CRITERIA}'. Rename the array. The audit " +
    "criteria signal has exactly one representation, so no alias reaches the audit_gap edge."
}

private fun auditSatisfiedPayloadError(raw: Any?): String? = when {
  raw !is List<*> -> "Audit verdict 'satisfied' requires an explicit empty produced_outputs.unmet_criteria array."
  raw.isNotEmpty() -> "Audit verdict 'satisfied' contradicts non-empty produced_outputs.unmet_criteria."
  else -> null
}
