package skillbill.application.featuretask

import skillbill.contracts.JsonSupport
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditCriterionGap
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditVerdict
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeReviewFinding
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeReviewSeverity
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeReviewVerdict
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict

internal object FeatureTaskRuntimeOutputVerification {
  fun verdictFor(phaseId: String, output: FeatureTaskRuntimePhaseOutput?): FeatureTaskRuntimeVerdict {
    val outputObject = output?.let(::outputObject)
    val wireVerdict = (outputObject?.get("verdict") as? String)
      ?.takeIf(String::isNotBlank)
      ?.let(FeatureTaskRuntimeVerdict::fromWire)
    return when (phaseId) {
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW -> reviewVerdict(outputObject, wireVerdict)
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT -> auditVerdict(outputObject, wireVerdict)
      else -> wireVerdict ?: FeatureTaskRuntimeVerdict.ADVANCE
    }
  }

  fun unresolvedReviewFindings(output: FeatureTaskRuntimePhaseOutput?): List<FeatureTaskRuntimeReviewFinding> =
    output?.let(::outputObject)?.let(::reviewVerdictFrom)?.unresolvedFindings.orEmpty()

  fun unmetAuditCriteria(output: FeatureTaskRuntimePhaseOutput?): List<String> =
    output?.let(::outputObject)?.let(::auditVerdictFrom)?.unmetCriteria?.map { it.message }.orEmpty()

  fun auditGapPayloadError(outputObject: Map<String, Any?>): String? {
    val wireVerdict = outputObject["verdict"] as? String
    if (wireVerdict != FeatureTaskRuntimeVerdict.GAPS_FOUND.wireValue) return null
    val producedOutputs = JsonSupport.anyToStringAnyMap(outputObject["produced_outputs"])
    val raw = producedOutputs?.get(FeatureTaskRuntimeVerificationSignalKeys.AUDIT_UNMET_CRITERIA)
      ?: producedOutputs?.get(FeatureTaskRuntimeVerificationSignalKeys.AUDIT_FAILING_CRITERIA_ALIAS)
    val entries = raw as? List<*>
      ?: return "Audit verdict 'gaps_found' requires a non-empty produced_outputs.unmet_criteria array."
    if (entries.isEmpty() || entries.any { auditGapMessage(it) == null }) {
      return "Audit verdict 'gaps_found' requires every produced_outputs.unmet_criteria entry " +
        "to carry a non-blank message."
    }
    return null
  }

  private fun reviewVerdict(
    outputObject: Map<String, Any?>?,
    wireVerdict: FeatureTaskRuntimeVerdict?,
  ): FeatureTaskRuntimeVerdict {
    val reviewVerdict = reviewVerdictFrom(outputObject)
    return if (reviewVerdict?.unresolvedFindings?.isNotEmpty() == true) {
      FeatureTaskRuntimeVerdict.CHANGES_REQUESTED
    } else {
      wireVerdict ?: reviewVerdict?.verdict ?: FeatureTaskRuntimeVerdict.ADVANCE
    }
  }

  private fun auditVerdict(
    outputObject: Map<String, Any?>?,
    wireVerdict: FeatureTaskRuntimeVerdict?,
  ): FeatureTaskRuntimeVerdict {
    val auditVerdict = auditVerdictFrom(outputObject)
    return if (auditVerdict?.unmetCriteria?.isNotEmpty() == true) {
      FeatureTaskRuntimeVerdict.GAPS_FOUND
    } else {
      wireVerdict ?: auditVerdict?.verdict ?: FeatureTaskRuntimeVerdict.ADVANCE
    }
  }

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
    val gapsRaw = (
      producedOutputs?.get(FeatureTaskRuntimeVerificationSignalKeys.AUDIT_UNMET_CRITERIA)
        ?: producedOutputs?.get(FeatureTaskRuntimeVerificationSignalKeys.AUDIT_FAILING_CRITERIA_ALIAS)
      ) as? List<*>
      ?: return null
    val gaps = gapsRaw.mapNotNull { entry -> auditGapMessage(entry)?.let(::FeatureTaskRuntimeAuditCriterionGap) }
    return FeatureTaskRuntimeAuditVerdict(gaps)
  }

  private fun auditGapMessage(entry: Any?): String? =
    (entry as? String)?.takeIf(String::isNotBlank)
      ?: JsonSupport.anyToStringAnyMap(entry)?.let { map ->
        ((map["message"] ?: map["criterion"]) as? String)?.takeIf(String::isNotBlank)
      }

  private fun outputObject(output: FeatureTaskRuntimePhaseOutput): Map<String, Any?>? =
    JsonSupport.parseObjectOrNull(output.payload)
      ?.let(JsonSupport::jsonElementToValue)
      ?.let(JsonSupport::anyToStringAnyMap)
}
