package skillbill.workflow.taskruntime

import skillbill.boundary.OpenBoundaryMap
import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN
import skillbill.workflow.taskruntime.model.SettlementEnvelopeRequest

object ProsePhaseOutputSynthesizer {
  private val PROSE_PHASE_IDS: Set<String> = setOf(PHASE_PREPLAN, PHASE_PLAN, PHASE_IMPLEMENT, PHASE_AUDIT)
  private val STATUS_TOKENS: Set<String> = setOf("completed", "blocked", "failed")
  private val AUDIT_VERDICTS: Set<String> = setOf("satisfied", "gaps_found")

  fun isProsePhase(phaseId: String): Boolean = phaseId in PROSE_PHASE_IDS

  @OpenBoundaryMap("Synthesized prose phase-output wire map recovered for schema re-entry")
  fun trySynthesize(phaseOutputText: String, phaseId: String): Map<String, Any?>? {
    if (!isProsePhase(phaseId)) return null
    val request = synthesisRequest(phaseOutputText, phaseId) ?: return null
    return stampEnvelope(request)
  }

  @OpenBoundaryMap("MCP settlement prose phase-output wire map stamped for gate consumption")
  fun envelopeFromSettlement(request: SettlementEnvelopeRequest): Map<String, Any?> {
    require(isProsePhase(request.phaseId)) { "phaseId must be a prose phase, was '${request.phaseId}'." }
    require(request.value.any { !it.isWhitespace() }) { "value must be non-blank." }
    require(request.summary.any { !it.isWhitespace() }) { "summary must be non-blank." }
    require(request.status in STATUS_TOKENS) { "status must be one of $STATUS_TOKENS." }
    return stampEnvelope(request)
  }

  private fun synthesisRequest(phaseOutputText: String, phaseId: String): SettlementEnvelopeRequest? {
    val parsed = ProsePhaseOutputParse.bestEffortParse(phaseOutputText)
    if (parsed == null || !ProsePhaseOutputParse.identityCompatible(parsed, phaseId)) return null
    val status = ProsePhaseOutputParse.recoverStatus(parsed) ?: return null
    val valueAndVerdict = recoverableValueAndVerdict(parsed, phaseOutputText, phaseId) ?: return null
    return SettlementEnvelopeRequest(
      phaseId = phaseId,
      status = status,
      value = valueAndVerdict.first,
      summary = ProsePhaseOutputParse.recoverSummary(parsed, valueAndVerdict.first),
      prompt = ProsePhaseOutputParse.recoverPrompt(parsed),
      verdict = valueAndVerdict.second,
      failureDisposition = if (status == "blocked" || status == "failed") {
        ProsePhaseOutputParse.recoverFailureDisposition(parsed)
      } else {
        null
      },
    )
  }

  private fun recoverableValueAndVerdict(
    parsed: Map<String, Any?>,
    phaseOutputText: String,
    phaseId: String,
  ): Pair<String, String?>? {
    val existingValue = ProsePhaseOutputParse.directValue(parsed)
    val value = existingValue ?: ProsePhaseOutputParse.recoverLegacyValue(parsed) ?: return null
    if (existingValue != null && phaseId != PHASE_AUDIT) return null
    val verdict = if (phaseId == PHASE_AUDIT) {
      ProsePhaseOutputParse.recoverAuditVerdict(parsed, phaseOutputText) ?: return null
    } else {
      null
    }
    return value to verdict
  }

  private fun stampEnvelope(request: SettlementEnvelopeRequest): Map<String, Any?> {
    val produced = linkedMapOf<String, Any?>("value" to request.value)
    if (!request.prompt.isNullOrBlank()) {
      produced["prompt"] = request.prompt
    }
    val envelope = linkedMapOf<String, Any?>(
      "contract_version" to FEATURE_TASK_RUNTIME_CONTRACT_VERSION,
      "phase_id" to request.phaseId,
      "status" to request.status,
      "summary" to request.summary,
      "produced_outputs" to produced,
    )
    if (request.phaseId == PHASE_AUDIT) {
      val resolved = requireNotNull(request.verdict?.takeIf { it in AUDIT_VERDICTS }) {
        "audit settlement requires verdict in $AUDIT_VERDICTS."
      }
      envelope["verdict"] = resolved
    }
    if ((request.status == "blocked" || request.status == "failed") && !request.failureDisposition.isNullOrBlank()) {
      envelope["failure_disposition"] = request.failureDisposition
    }
    return envelope
  }
}
