package skillbill.application.workflow

import skillbill.application.workflow.model.WorkflowUpdateResult
import skillbill.contracts.JsonSupport
import skillbill.ports.db.UnitOfWork
import skillbill.ports.workflow.model.FeatureTaskWorkflowMode
import skillbill.ports.workflow.model.WorkflowStateRecord
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.engine.model.WorkflowUpdateAcknowledgementView
import skillbill.workflow.engine.model.WorkflowUpdateInput
import java.time.OffsetDateTime
import java.time.ZoneOffset

internal class WorkflowServiceFeatureTaskAbandon(
  private val engine: WorkflowEngine,
) {
  fun abandonRuntimeFeatureTask(
    unitOfWork: UnitOfWork,
    existing: WorkflowStateSnapshot,
    normalizedReason: String,
  ): WorkflowUpdateResult {
    val family = WorkflowFamily.TASK_RUNTIME
    if (existing.workflowStatus in family.definition.terminalStatuses) {
      return WorkflowUpdateResult.Error(
        existing.workflowId,
        "Runtime workflow '${existing.workflowId}' is already terminal with status '${existing.workflowStatus}'.",
        unitOfWork.dbPath.toString(),
      )
    }
    val input = WorkflowUpdateInput(
      workflowStatus = "abandoned",
      currentStepId = existing.currentStepId.orEmpty(),
      stepUpdates = null,
      artifactsPatch = mapOf(
        FEATURE_TASK_RUNTIME_OPERATOR_ABANDONMENT_ARTIFACT_KEY to mapOf(
          "reason" to normalizedReason,
          "abandoned_at" to OffsetDateTime.now(ZoneOffset.UTC).toString(),
        ),
      ),
      sessionId = "",
    )
    val updated = engine.updateRecord(family.definition, existing, input)
    family.save(unitOfWork.workflowStates, updated)
    return buildUpdateOk(engine, family.definition, updated, input, unitOfWork.dbPath.toString())
  }

  fun abandonLegacyProseFeatureTask(
    unitOfWork: UnitOfWork,
    existing: WorkflowStateRecord,
    normalizedReason: String,
  ): WorkflowUpdateResult {
    if (existing.workflowStatus in FEATURE_TASK_TERMINAL_STATUSES) {
      return WorkflowUpdateResult.Error(
        existing.workflowId,
        "Feature-task workflow '${existing.workflowId}' is already terminal with status '${existing.workflowStatus}'.",
        unitOfWork.dbPath.toString(),
      )
    }
    val abandonedAt = OffsetDateTime.now(ZoneOffset.UTC).toString()
    val artifacts = LinkedHashMap(decodeWorkflowArtifacts(existing.artifactsJson))
    artifacts[FEATURE_TASK_RUNTIME_OPERATOR_ABANDONMENT_ARTIFACT_KEY] = mapOf(
      "reason" to normalizedReason,
      "abandoned_at" to abandonedAt,
    )
    val updated = existing.copy(
      workflowStatus = "abandoned",
      artifactsJson = JsonSupport.mapToJsonString(artifacts),
      finishedAt = abandonedAt,
    )
    unitOfWork.workflowStates.terminalizeLegacyProseFeatureTaskWorkflow(updated)
    return WorkflowUpdateResult.Ok(
      workflowId = updated.workflowId,
      dbPath = unitOfWork.dbPath.toString(),
      acknowledgement = WorkflowUpdateAcknowledgementView(
        status = "ok",
        workflowId = updated.workflowId,
        workflowName = updated.workflowName,
        workflowStatus = "abandoned",
        currentStepId = updated.currentStepId,
        updatedStepIds = emptyList(),
        updatedArtifactKeys = listOf(FEATURE_TASK_RUNTIME_OPERATOR_ABANDONMENT_ARTIFACT_KEY),
        readOnlyFullStateGuidance =
        "Update returns a compact acknowledgement. Use explicit read-only workflow get/show for full state, " +
          "including steps and the complete durable artifacts map.",
      ),
      launchProjection = null,
    )
  }
}
