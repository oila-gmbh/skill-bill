package skillbill.application.featuretask

import skillbill.contracts.JsonSupport
import skillbill.review.ReviewFindingActionability
import skillbill.review.model.ReviewClaimVerdict
import skillbill.review.model.ReviewScopeDisposition
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditCriterionGap
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditSeverity
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditVerdict
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFindingVerificationDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFindingVerificationVerdict
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
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS ->
        findingVerificationVerdict(outputObject, wireVerdict)
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT -> auditVerdict(outputObject, wireVerdict)
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

  fun unmetAuditCriteria(outputObject: Map<String, Any?>?): List<String> =
    auditVerdictFrom(outputObject)?.blockingCriteria?.map { it.message }.orEmpty()

  fun canonicalAuditCriterionRefs(outputObject: Map<String, Any?>?): List<String> = auditVerdictFrom(outputObject)
    ?.blockingCriteria
    ?.mapNotNull { AUDIT_CRITERION_REF.find(it.message)?.value?.uppercase() }
    ?.distinct()
    .orEmpty()

  private val AUDIT_CRITERION_REF: Regex = Regex("""(AC-\d+)""", RegexOption.IGNORE_CASE)

  fun auditGapPayloadError(outputObject: Map<String, Any?>): String? {
    val wireVerdict = outputObject["verdict"] as? String
    val producedOutputs = JsonSupport.anyToStringAnyMap(outputObject["produced_outputs"])
    return rejectedCriteriaAliasError(producedOutputs)
      ?: rejectedLegacyCriteriaKeyError(producedOutputs)
      ?: auditGapsArrayPayloadError(wireVerdict, producedOutputs)
      ?: if (producedOutputs?.containsKey(FeatureTaskRuntimeVerificationSignalKeys.AUDIT_GAPS) == true) {
        null
      } else {
        auditLegacyCriteriaPayloadError(wireVerdict, producedOutputs)
      }
  }

  private fun findingVerificationVerdict(
    outputObject: Map<String, Any?>?,
    wireVerdict: FeatureTaskRuntimeVerdict?,
  ): FeatureTaskRuntimeVerdict {
    val derived = findingVerificationVerdictFrom(outputObject)?.verdict
    return derived ?: wireVerdict ?: FeatureTaskRuntimeVerdict.ADVANCE
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
    val gapsRaw = producedOutputs?.get(FeatureTaskRuntimeVerificationSignalKeys.AUDIT_GAPS) as? List<*>
      ?: producedOutputs?.get(FeatureTaskRuntimeVerificationSignalKeys.AUDIT_UNMET_CRITERIA) as? List<*>
      ?: return null
    val gaps = gapsRaw.mapNotNull(::auditCriterionGapFromEntry)
    return FeatureTaskRuntimeAuditVerdict(gaps)
  }
}

private fun auditCriterionGapFromEntry(entry: Any?): FeatureTaskRuntimeAuditCriterionGap? {
  val parsed = if (entry is String) {
    entry.takeIf(String::isNotBlank)?.let { it to FeatureTaskRuntimeAuditSeverity.MAJOR }
  } else {
    val map = JsonSupport.anyToStringAnyMap(entry)
    val criterionNote = map?.let {
      val criterion = (it["criterion"] as? String)?.trim()?.takeIf(String::isNotBlank)
      val note = (it["note"] as? String)?.trim()?.takeIf(String::isNotBlank)
      if (criterion != null && note != null) "$criterion: $note" else null
    }
    val message = criterionNote ?: map?.let {
      sequenceOf(it["issue"], it["message"], it["criterion"])
        .filterIsInstance<String>()
        .map(String::trim)
        .firstOrNull(String::isNotBlank)
    }
    val severity = map?.let {
      runCatching { FeatureTaskRuntimeAuditSeverity.fromWire(it["severity"] as? String) }.getOrNull()
        ?: if (criterionNote != null) FeatureTaskRuntimeAuditSeverity.MAJOR else null
    }
    if (message != null && (severity == null || severity.blocksAuditGap)) {
      message to (severity ?: FeatureTaskRuntimeAuditSeverity.MAJOR)
    } else {
      null
    }
  }
  return parsed?.let { (message, severity) -> FeatureTaskRuntimeAuditCriterionGap(message, severity) }
}

private fun rejectedCriteriaAliasError(producedOutputs: Map<String, Any?>?): String? {
  val alias = FeatureTaskRuntimeVerificationSignalKeys.AUDIT_FAILING_CRITERIA_REJECTED_ALIAS
  if (producedOutputs?.containsKey(alias) != true) return null
  return "Audit produced_outputs carries '$alias'; the canonical unmet-criteria key is " +
    "'${FeatureTaskRuntimeVerificationSignalKeys.AUDIT_UNMET_CRITERIA}'. Rename the array. The audit " +
    "criteria signal has exactly one representation, so no alias reaches the audit_gap edge."
}

private fun rejectedLegacyCriteriaKeyError(producedOutputs: Map<String, Any?>?): String? {
  val legacyKey = FeatureTaskRuntimeVerificationSignalKeys.AUDIT_UNMET_CRITERIA
  if (
    producedOutputs?.containsKey(legacyKey) != true ||
    producedOutputs.containsKey(FeatureTaskRuntimeVerificationSignalKeys.AUDIT_GAPS)
  ) {
    return null
  }
  val legacy = producedOutputs[legacyKey]
  if (legacy is List<*> && legacy.isEmpty()) return null
  return "Audit produced_outputs carries '$legacyKey'; " +
    "the canonical gap signal is '${FeatureTaskRuntimeVerificationSignalKeys.AUDIT_GAPS}'. " +
    "Rename the array to gaps and pair it with the compact audit gap vocabulary."
}

private fun auditGapsArrayPayloadError(wireVerdict: String?, producedOutputs: Map<String, Any?>?): String? {
  val gapsKey = FeatureTaskRuntimeVerificationSignalKeys.AUDIT_GAPS
  val gapsRaw = producedOutputs?.get(gapsKey)
  if (producedOutputs?.containsKey(gapsKey) != true) return null
  if (wireVerdict == FeatureTaskRuntimeVerdict.SATISFIED.wireValue) {
    return auditSatisfiedGapsPayloadError(gapsRaw)
  }
  val parsedGaps = (gapsRaw as? List<*>)?.mapNotNull(::auditCriterionGapFromEntry).orEmpty()
  val gapsDriveGapsFound = parsedGaps.any { it.severity.blocksAuditGap }
  return when {
    wireVerdict == FeatureTaskRuntimeVerdict.GAPS_FOUND.wireValue && gapsRaw is List<*> && gapsRaw.isEmpty() ->
      "Audit verdict 'gaps_found' contradicts empty produced_outputs.gaps."
    wireVerdict != FeatureTaskRuntimeVerdict.GAPS_FOUND.wireValue && !gapsDriveGapsFound -> null
    gapsRaw !is List<*> ->
      "Audit verdict 'gaps_found' requires a non-empty produced_outputs.gaps array."
    gapsRaw.isEmpty() || parsedGaps.size != gapsRaw.size ->
      "Audit verdict 'gaps_found' requires every produced_outputs.gaps entry " +
        "to carry blocker or major severity with a non-blank issue; move minor and nit findings " +
        "to produced_outputs.${FeatureTaskRuntimeVerificationSignalKeys.AUDIT_NON_BLOCKING_FINDINGS}."
    else -> null
  }
}

private fun auditLegacyCriteriaPayloadError(wireVerdict: String?, producedOutputs: Map<String, Any?>?): String? {
  val legacyRaw = producedOutputs?.get(FeatureTaskRuntimeVerificationSignalKeys.AUDIT_UNMET_CRITERIA)
  if (wireVerdict == FeatureTaskRuntimeVerdict.SATISFIED.wireValue) {
    return auditSatisfiedLegacyPayloadError(legacyRaw)
  }
  val parsedCriteria = (legacyRaw as? List<*>)?.mapNotNull(::auditCriterionGapFromEntry).orEmpty()
  val criteriaDriveGapsFound = parsedCriteria.any { it.severity.blocksAuditGap }
  return when {
    wireVerdict == FeatureTaskRuntimeVerdict.GAPS_FOUND.wireValue && legacyRaw is List<*> && legacyRaw.isEmpty() ->
      "Audit verdict 'gaps_found' contradicts empty produced_outputs.unmet_criteria."
    wireVerdict != FeatureTaskRuntimeVerdict.GAPS_FOUND.wireValue && !criteriaDriveGapsFound -> null
    legacyRaw !is List<*> -> "Audit verdict 'gaps_found' requires a non-empty produced_outputs.unmet_criteria array."
    legacyRaw.isEmpty() || parsedCriteria.size != legacyRaw.size ->
      "Audit verdict 'gaps_found' requires every produced_outputs.unmet_criteria entry " +
        "to carry a non-blank message and severity blocker or major; move minor and nit findings " +
        "to produced_outputs.${FeatureTaskRuntimeVerificationSignalKeys.AUDIT_NON_BLOCKING_FINDINGS}."
    else -> null
  }
}

private fun auditSatisfiedGapsPayloadError(raw: Any?): String? = when {
  raw !is List<*> -> "Audit verdict 'satisfied' requires an explicit empty produced_outputs.gaps array."
  raw.isNotEmpty() -> "Audit verdict 'satisfied' contradicts non-empty produced_outputs.gaps."
  else -> null
}

private fun auditSatisfiedLegacyPayloadError(raw: Any?): String? = when {
  raw !is List<*> -> "Audit verdict 'satisfied' requires an explicit empty produced_outputs.unmet_criteria array."
  raw.isNotEmpty() -> "Audit verdict 'satisfied' contradicts non-empty produced_outputs.unmet_criteria."
  else -> null
}
