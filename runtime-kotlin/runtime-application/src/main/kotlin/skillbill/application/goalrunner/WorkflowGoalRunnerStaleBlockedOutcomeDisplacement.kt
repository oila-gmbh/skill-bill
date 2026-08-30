package skillbill.application.goalrunner

import skillbill.application.decomposition.decodeArtifacts
import skillbill.application.workflow.WorkflowFamily
import skillbill.goalrunner.model.GoalRunnerStoredOutcome
import skillbill.goalrunner.model.GoalRunnerTerminalStatus
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.engine.model.WorkflowUpdateInput
import java.time.Instant

internal class WorkflowGoalRunnerStaleBlockedOutcomeDisplacement(
  private val engine: WorkflowEngine,
) {
  fun displaceIfPresent(
    workflowStates: WorkflowStateRepository,
    workflowId: String,
    issueKey: String,
    subtaskId: Int,
  ) {
    val context = loadDisplacementContext(workflowStates, workflowId, issueKey, subtaskId) ?: return
    if (shouldRetainBlockedOutcome(context)) return
    persistDisplacement(workflowStates, context)
  }

  private fun loadDisplacementContext(
    workflowStates: WorkflowStateRepository,
    workflowId: String,
    issueKey: String,
    subtaskId: Int,
  ): DisplacementContext? {
    val family = workflowFamilyFor(workflowStates, workflowId) ?: return null
    val record = family.get(workflowStates, workflowId) ?: return null
    val artifacts = decodeArtifacts(record.artifactsJson)
    val continuation = goalContinuation(artifacts)
      ?.takeIf { it.issueKey == issueKey && it.subtaskId == subtaskId }
    val stored = continuation
      ?.let { goalContinuationOutcome(artifacts, issueKey, subtaskId, it.suppressPr) }
      ?.takeIf { it.status == GoalRunnerTerminalStatus.BLOCKED }
    if (continuation == null || stored == null) return null
    return DisplacementContext(family, record, artifacts, continuation, stored, workflowId, issueKey, subtaskId)
  }

  private fun shouldRetainBlockedOutcome(context: DisplacementContext): Boolean {
    val derived = derivedTerminalOutcomeFor(context.record, context.artifacts, context.continuation) { null }
    return nonCompleteStoredOutcomeIsCorroborated(
      context.stored.copy(workflowId = context.workflowId),
      derived,
      context.record,
    )
  }

  private fun persistDisplacement(workflowStates: WorkflowStateRepository, context: DisplacementContext) {
    val evidenceAlreadyPresent = context.artifacts[GOAL_CONTINUATION_OUTCOME_DISPLACEMENT_ARTIFACT_KEY] != null
    val derived = derivedTerminalOutcomeFor(context.record, context.artifacts, context.continuation) { null }
    val updated = engine.updateRecord(
      context.family.definition,
      context.record,
      WorkflowUpdateInput(
        workflowStatus = context.record.workflowStatus,
        currentStepId = context.record.currentStepId,
        stepUpdates = null,
        artifactsPatch = buildMap {
          if (!evidenceAlreadyPresent) {
            put(
              GOAL_CONTINUATION_OUTCOME_DISPLACEMENT_ARTIFACT_KEY,
              linkedMapOf(
                "workflow_id" to context.workflowId,
                "issue_key" to context.issueKey,
                "subtask_id" to context.subtaskId,
                "displaced_status" to "blocked",
                "original_blocked_reason" to context.stored.blockedReason,
                "failed_corroboration" to linkedMapOf(
                  "derived_status" to derived?.status?.toGoalContinuationWireStatus(),
                  "derived_blocked_reason" to derived?.blockedReason,
                  "stored_blocked_reason" to context.stored.blockedReason,
                ),
                "displaced_at" to Instant.now().toString(),
              ),
            )
          }
          put("goal_continuation_outcome", null)
        },
        sessionId = context.record.sessionId.orEmpty(),
      ),
    )
    context.family.save(workflowStates, updated)
  }

  private data class DisplacementContext(
    val family: WorkflowFamily,
    val record: WorkflowStateSnapshot,
    val artifacts: Map<String, Any?>,
    val continuation: GoalContinuation,
    val stored: GoalRunnerStoredOutcome,
    val workflowId: String,
    val issueKey: String,
    val subtaskId: Int,
  )
}
