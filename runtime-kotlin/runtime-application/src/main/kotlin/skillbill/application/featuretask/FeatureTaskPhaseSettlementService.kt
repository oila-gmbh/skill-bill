package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.boundary.OpenBoundaryMap
import skillbill.contracts.JsonSupport
import skillbill.ports.featuretask.FeatureTaskPhaseSettlementRepository
import skillbill.ports.featuretask.model.FeatureTaskPhaseSettlement
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.ProsePhaseOutputSynthesizer
import skillbill.workflow.taskruntime.model.SettlementEnvelopeRequest
import java.time.Instant

@Inject
class FeatureTaskPhaseSettlementService(
  private val repository: FeatureTaskPhaseSettlementRepository,
) {
  @Suppress("LongParameterList")
  @OpenBoundaryMap("MCP feature_task_phase_complete acknowledgement wire map")
  fun complete(
    workflowId: String,
    phaseId: String,
    attempt: Int,
    value: String,
    prompt: String? = null,
    summary: String? = null,
    dbPathOverride: String? = null,
  ): Map<String, Any?> {
    require(ProsePhaseOutputSynthesizer.isProsePhase(phaseId)) {
      "phase_id must be a prose phase (preplan|plan|implement|audit)."
    }
    require(phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT) {
      "Use feature_task_audit_settle for audit completions."
    }
    val envelope = ProsePhaseOutputSynthesizer.envelopeFromSettlement(
      SettlementEnvelopeRequest(
        phaseId = phaseId,
        status = "completed",
        value = value,
        summary = summary?.takeIf { it.any { ch -> !ch.isWhitespace() } } ?: truncateSummary(value),
        prompt = prompt,
      ),
    )
    return persist(
      PersistRequest(
        workflowId = workflowId,
        phaseId = phaseId,
        attempt = attempt,
        kind = KIND_COMPLETE,
        envelope = envelope,
        dbPathOverride = dbPathOverride,
      ),
    )
  }

  @Suppress("LongParameterList")
  @OpenBoundaryMap("MCP feature_task_phase_block acknowledgement wire map")
  fun block(
    workflowId: String,
    phaseId: String,
    attempt: Int,
    reason: String,
    failureDisposition: String = "needs_user_action",
    dbPathOverride: String? = null,
  ): Map<String, Any?> {
    require(ProsePhaseOutputSynthesizer.isProsePhase(phaseId)) {
      "phase_id must be a prose phase (preplan|plan|implement|audit)."
    }
    val envelope = ProsePhaseOutputSynthesizer.envelopeFromSettlement(
      SettlementEnvelopeRequest(
        phaseId = phaseId,
        status = "blocked",
        value = reason,
        summary = truncateSummary(reason),
        failureDisposition = failureDisposition,
        verdict = if (phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT) "gaps_found" else null,
      ),
    )
    return persist(
      PersistRequest(
        workflowId = workflowId,
        phaseId = phaseId,
        attempt = attempt,
        kind = KIND_BLOCK,
        envelope = envelope,
        dbPathOverride = dbPathOverride,
      ),
    )
  }

  @Suppress("LongParameterList")
  @OpenBoundaryMap("MCP feature_task_audit_settle acknowledgement wire map")
  fun auditSettle(
    workflowId: String,
    phaseId: String,
    attempt: Int,
    verdict: String,
    value: String,
    summary: String? = null,
    dbPathOverride: String? = null,
  ): Map<String, Any?> {
    require(phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT) {
      "feature_task_audit_settle requires phase_id=audit."
    }
    val envelope = ProsePhaseOutputSynthesizer.envelopeFromSettlement(
      SettlementEnvelopeRequest(
        phaseId = phaseId,
        status = "completed",
        value = value,
        summary = summary?.takeIf { it.any { ch -> !ch.isWhitespace() } } ?: truncateSummary(value),
        verdict = verdict,
      ),
    )
    return persist(
      PersistRequest(
        workflowId = workflowId,
        phaseId = phaseId,
        attempt = attempt,
        kind = KIND_AUDIT_SETTLE,
        envelope = envelope,
        dbPathOverride = dbPathOverride,
      ),
    )
  }

  @OpenBoundaryMap("Durable MCP phase-settlement envelope wire map for gate consumption")
  fun findEnvelope(
    workflowId: String,
    phaseId: String,
    attempt: Int,
    dbPathOverride: String? = null,
  ): Map<String, Any?>? {
    val settlement = repository.find(workflowId, phaseId, attempt, dbPathOverride) ?: return null
    @Suppress("UNCHECKED_CAST")
    return JsonSupport.parseObjectOrNull(settlement.envelopeJson)
      ?.let { JsonSupport.jsonElementToValue(it) as? Map<String, Any?> }
  }

  fun clear(workflowId: String, phaseId: String, attempt: Int, dbPathOverride: String? = null): Boolean =
    repository.delete(workflowId, phaseId, attempt, dbPathOverride)

  private fun persist(request: PersistRequest): Map<String, Any?> {
    val envelopeJson = JsonSupport.mapToJsonString(request.envelope)
    repository.upsert(
      FeatureTaskPhaseSettlement(
        workflowId = request.workflowId,
        phaseId = request.phaseId,
        attempt = request.attempt,
        kind = request.kind,
        envelopeJson = envelopeJson,
        recordedAt = Instant.now().toString(),
      ),
      dbPathOverride = request.dbPathOverride,
    )
    return linkedMapOf(
      "status" to "ok",
      "workflow_id" to request.workflowId,
      "phase_id" to request.phaseId,
      "attempt" to request.attempt,
      "kind" to request.kind,
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
    val kind: String,
    val envelope: Map<String, Any?>,
    val dbPathOverride: String?,
  )

  companion object {
    const val KIND_COMPLETE: String = "complete"
    const val KIND_BLOCK: String = "block"
    const val KIND_AUDIT_SETTLE: String = "audit_settle"
    private const val SUMMARY_MAX_CHARS: Int = 240
    private const val SUMMARY_ELLIPSIS_PREFIX: Int = 237
  }
}
