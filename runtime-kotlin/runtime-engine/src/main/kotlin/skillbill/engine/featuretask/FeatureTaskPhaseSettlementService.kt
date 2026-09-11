package skillbill.engine.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.boundary.OpenBoundaryMap
import skillbill.contracts.JsonSupportimport skillbill.ports.featuretask.FeatureTaskPhaseSettlementRepository
import skillbill.ports.featuretask.model.FeatureTaskPhaseSettlement
import skillbill.ports.featuretask.model.FeatureTaskPhaseSettlementKind
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.ProsePhaseOutputSynthesizer
import skillbill.workflow.taskruntime.model.SettlementEnvelopeRequest
import java.time.Clock

@Inject
class FeatureTaskPhaseSettlementService(
  private val repository: FeatureTaskPhaseSettlementRepository,
  private val clock: Clock,
) {
  @OpenBoundaryMap("MCP feature_task_phase_complete acknowledgement wire map")
  fun complete(request: FeatureTaskPhaseSettlementCompleteRequest): Map<String, Any?> {
    require(ProsePhaseOutputSynthesizer.isProsePhase(request.phaseId)) {
      "phase_id must be a prose phase (preplan|plan|implement|audit)."
    }
    require(request.phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT) {
      "Use feature_task_audit_settle for audit completions."
    }
    val envelope = ProsePhaseOutputSynthesizer.envelopeFromSettlement(
      SettlementEnvelopeRequest(
        phaseId = request.phaseId,
        status = "completed",
        value = request.value,
        summary = request.summary?.takeIf { it.any { ch -> !ch.isWhitespace() } } ?: truncateSummary(request.value),
        prompt = request.prompt,
      ),
    )
    return persist(
      PersistRequest(
        workflowId = request.workflowId,
        phaseId = request.phaseId,
        attempt = request.attempt,
        kind = KIND_COMPLETE,
        envelope = envelope,
      ),
    )
  }

  @OpenBoundaryMap("MCP feature_task_phase_block acknowledgement wire map")
  fun block(request: FeatureTaskPhaseSettlementBlockRequest): Map<String, Any?> {
    require(ProsePhaseOutputSynthesizer.isProsePhase(request.phaseId)) {
      "phase_id must be a prose phase (preplan|plan|implement|audit)."
    }
    val envelope = ProsePhaseOutputSynthesizer.envelopeFromSettlement(
      SettlementEnvelopeRequest(
        phaseId = request.phaseId,
        status = "blocked",
        value = request.reason,
        summary = truncateSummary(request.reason),
        failureDisposition = request.failureDisposition,
        verdict = if (request.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT) "gaps_found" else null,
      ),
    )
    return persist(
      PersistRequest(
        workflowId = request.workflowId,
        phaseId = request.phaseId,
        attempt = request.attempt,
        kind = KIND_BLOCK,
        envelope = envelope,
      ),
    )
  }

  @OpenBoundaryMap("MCP feature_task_audit_settle acknowledgement wire map")
  fun auditSettle(request: FeatureTaskPhaseSettlementAuditRequest): Map<String, Any?> {
    require(request.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT) {
      "feature_task_audit_settle requires phase_id=audit."
    }
    val envelope = ProsePhaseOutputSynthesizer.envelopeFromSettlement(
      SettlementEnvelopeRequest(
        phaseId = request.phaseId,
        status = "completed",
        value = request.value,
        summary = request.summary?.takeIf { it.any { ch -> !ch.isWhitespace() } } ?: truncateSummary(request.value),
        verdict = request.verdict,
      ),
    )
    return persist(
      PersistRequest(
        workflowId = request.workflowId,
        phaseId = request.phaseId,
        attempt = request.attempt,
        kind = KIND_AUDIT_SETTLE,
        envelope = envelope,
      ),
    )
  }

  @OpenBoundaryMap("Durable MCP phase-settlement envelope wire map for gate consumption")
  fun findEnvelope(workflowId: String, phaseId: String, attempt: Int): Map<String, Any?>? {
    val settlement = repository.find(workflowId, phaseId, attempt) ?: return null
    return JsonSupport.parseObjectOrNull(settlement.envelopeJson)
      ?.let { JsonSupport.anyToStringAnyMap(JsonSupport.jsonElementToValue(it)) }  }

  fun clear(workflowId: String, phaseId: String, attempt: Int): Boolean =
    repository.delete(workflowId, phaseId, attempt)

  private fun persist(request: PersistRequest): Map<String, Any?> {
    val envelopeJson = JsonSupport.mapToJsonString(request.envelope)
    repository.upsert(
      FeatureTaskPhaseSettlement(
        workflowId = request.workflowId,
        phaseId = request.phaseId,
        attempt = request.attempt,
        kind = request.kind,
        envelopeJson = envelopeJson,
        recordedAt = clock.instant().toString(),
      ),
    )
    return linkedMapOf(
      "status" to "ok",
      "workflow_id" to request.workflowId,
      "phase_id" to request.phaseId,
      "attempt" to request.attempt,
      "kind" to request.kind.wireValue,
      "envelope" to request.envelope,
    )
  }

  private fun truncateSummary(value: String): String {
    val compact = value.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
    return when {
      compact.isBlank() -> "Phase settlement recorded."
      compact.length <= SUMMARY_MAX_CHARS -> compact
      else -> compact.take(SUMMARY_ELLIPSIS_PREFIX) + "..."
    }
  }

  private data class PersistRequest(
    val workflowId: String,
    val phaseId: String,
    val attempt: Int,
    val kind: FeatureTaskPhaseSettlementKind,
    val envelope: Map<String, Any?>,
  )

  companion object {
    val KIND_COMPLETE: FeatureTaskPhaseSettlementKind = FeatureTaskPhaseSettlementKind.Complete
    val KIND_BLOCK: FeatureTaskPhaseSettlementKind = FeatureTaskPhaseSettlementKind.Block
    val KIND_AUDIT_SETTLE: FeatureTaskPhaseSettlementKind = FeatureTaskPhaseSettlementKind.AuditSettle
    private const val SUMMARY_MAX_CHARS: Int = 240
    private const val SUMMARY_ELLIPSIS_PREFIX: Int = 237
  }
}
