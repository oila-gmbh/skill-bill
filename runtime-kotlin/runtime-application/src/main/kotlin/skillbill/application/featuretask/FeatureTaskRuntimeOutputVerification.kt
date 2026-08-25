package skillbill.application.featuretask

import skillbill.contracts.JsonSupport
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditCriterionGap
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditSeverity
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFindingVerificationDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeReviewFinding
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict

@Suppress("TooManyFunctions")
internal object FeatureTaskRuntimeOutputVerification {
  fun verdictFor(phaseId: String, outputObject: Map<String, Any?>?): FeatureTaskRuntimeVerdict? =
    FeatureTaskRuntimePhaseOutputDerivation.verdictFor(derivationContext(phaseId, outputObject))

  fun dispositionsFrom(outputObject: Map<String, Any?>?): List<FeatureTaskRuntimeFindingVerificationDisposition> =
    FeatureTaskRuntimePhaseOutputDerivation.dispositionsFrom(derivationContext("", outputObject))

  fun verifiedFindingDispositions(
    outputObject: Map<String, Any?>?,
  ): List<FeatureTaskRuntimeFindingVerificationDisposition> =
    FeatureTaskRuntimePhaseOutputDerivation.verifiedFindingDispositions(derivationContext("", outputObject))

  fun rejectedFindingDispositions(
    outputObject: Map<String, Any?>?,
  ): List<FeatureTaskRuntimeFindingVerificationDisposition> =
    FeatureTaskRuntimePhaseOutputDerivation.rejectedFindingDispositions(derivationContext("", outputObject))

  fun unresolvedReviewFindings(outputObject: Map<String, Any?>?): List<FeatureTaskRuntimeReviewFinding> =
    FeatureTaskRuntimePhaseOutputDerivation.unresolvedReviewFindings(derivationContext("", outputObject))

  fun unmetAuditCriteria(outputObject: Map<String, Any?>?): List<String> =
    FeatureTaskRuntimePhaseOutputDerivation.unmetAuditCriteria(derivationContext("", outputObject))

  fun canonicalAuditCriterionRefs(outputObject: Map<String, Any?>?): List<String> =
    FeatureTaskRuntimePhaseOutputDerivation.canonicalAuditCriterionRefs(derivationContext("", outputObject))

  private val AUDIT_CRITERION_REF: Regex = Regex("""(AC-\d+)""", RegexOption.IGNORE_CASE)

  fun auditGapPayloadError(outputObject: Map<String, Any?>): String? {
    val wireVerdict = outputObject["verdict"] as? String
    val producedOutputs = JsonSupport.anyToStringAnyMap(outputObject["produced_outputs"])
    return auditGapsArrayPayloadError(wireVerdict, producedOutputs)
      ?: if (producedOutputs?.containsKey(FeatureTaskRuntimeVerificationSignalKeys.AUDIT_GAPS) == true) {
        null
      } else {
        auditLegacyCriteriaPayloadError(wireVerdict, producedOutputs)
      }
  }

  private fun derivationContext(phaseId: String, outputObject: Map<String, Any?>?): FeatureTaskRuntimeDerivationContext {
    val map = outputObject.orEmpty()
    val resolvedPhaseId = (map["phase_id"] as? String)?.takeIf(String::isNotBlank) ?: phaseId
    return FeatureTaskRuntimeDerivationContext(
      phaseId = resolvedPhaseId,
      outputText = JsonSupport.mapToJsonString(map),
      outputMap = map,
    )
  }
}

private fun auditCriterionGapFromEntry(entry: Any?): FeatureTaskRuntimeAuditCriterionGap? {
  if (entry is String) {
    return entry.takeIf(String::isNotBlank)?.let {
      FeatureTaskRuntimeAuditCriterionGap(it, FeatureTaskRuntimeAuditSeverity.MAJOR)
    }
  }
  val map = JsonSupport.anyToStringAnyMap(entry) ?: return null
  val criterion = (map["criterion"] as? String)?.trim()?.takeIf(String::isNotBlank)
  val note = (map["note"] as? String)?.trim()?.takeIf(String::isNotBlank)
  val issue = sequenceOf(map["issue"], map["message"])
    .filterIsInstance<String>()
    .map(String::trim)
    .firstOrNull(String::isNotBlank)
  val message = auditCriterionGapMessage(criterion, note, issue)
  val wired = runCatching { FeatureTaskRuntimeAuditSeverity.fromWire(map["severity"] as? String) }.getOrNull()
  val severity = wired
    ?: if (criterion != null && (note != null || issue != null)) {
      FeatureTaskRuntimeAuditSeverity.MAJOR
    } else {
      null
    }
  return when {
    message == null -> null
    severity != null && !severity.blocksAuditGap -> null
    else -> FeatureTaskRuntimeAuditCriterionGap(message, severity ?: FeatureTaskRuntimeAuditSeverity.MAJOR)
  }
}

private fun auditCriterionGapMessage(criterion: String?, note: String?, issue: String?): String? = when {
  criterion != null && note != null -> "$criterion: $note"
  criterion != null && issue != null -> "$criterion: $issue"
  issue != null -> issue
  criterion != null -> criterion
  else -> null
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
      "Audit verdict 'gaps_found' requires a non-empty produced_outputs.gaps array " +
        "(empty produced_outputs.unmet_criteria is not the gap signal)."
    wireVerdict != FeatureTaskRuntimeVerdict.GAPS_FOUND.wireValue && !criteriaDriveGapsFound -> null
    legacyRaw !is List<*> ->
      "Audit verdict 'gaps_found' requires a non-empty produced_outputs.gaps array."
    legacyRaw.isEmpty() || parsedCriteria.size != legacyRaw.size ->
      "Audit verdict 'gaps_found' requires every produced_outputs.gaps entry " +
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
