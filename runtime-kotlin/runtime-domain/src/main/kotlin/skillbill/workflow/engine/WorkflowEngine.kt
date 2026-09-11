package skillbill.workflow.engine

import skillbill.workflow.engine.model.WorkflowContinueDecision
import skillbill.workflow.engine.model.WorkflowDefinition
import skillbill.workflow.engine.model.WorkflowInputProjection
import skillbill.workflow.engine.model.WorkflowResumeView
import skillbill.workflow.engine.model.WorkflowSnapshotView
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.engine.model.WorkflowSummaryView
import skillbill.workflow.engine.model.WorkflowUpdateAcknowledgementView
import skillbill.workflow.engine.model.WorkflowUpdateInput
import skillbill.workflow.model.WorkflowContinueStatus
import skillbill.workflow.model.WorkflowResumeMode
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.workflowStatus
import skillbill.workflow.model.workflowStepStatus

private typealias CheckpointResolver = () -> String

private val unresolvedCheckpoint: CheckpointResolver = { "" }

class WorkflowEngine(
  private val schemaValidator: WorkflowSnapshotValidator,
  private val checkpoint: CheckpointResolver = unresolvedCheckpoint,
) {
  fun openRecord(
    definition: WorkflowDefinition,
    workflowId: String,
    sessionId: String,
    currentStepId: String,
  ): WorkflowStateSnapshot {
    val snapshot = WorkflowStateSnapshot(
      workflowId = workflowId,
      sessionId = sessionId.trim(),
      workflowName = definition.workflowName,
      contractVersion = definition.contractVersion,
      workflowStatus = "running",
      currentStepId = currentStepId,
      stepsJson = jsonString(defaultSteps(definition, currentStepId)),
      artifactsJson = jsonString(emptyMap<String, Any?>()),
      startedAt = null,
      updatedAt = null,
      finishedAt = null,
      mode = definition.workflowMode,
    )
    schemaValidator.validate(snapshot, definition.workflowName)
    return snapshot
  }

  fun updateRecord(
    definition: WorkflowDefinition,
    existing: WorkflowStateSnapshot,
    input: WorkflowUpdateInput,
  ): WorkflowStateSnapshot {
    val existingArtifacts = decodeObject(existing.artifactsJson)
    val mergedArtifacts = if (input.replaceArtifacts) {
      LinkedHashMap()
    } else {
      LinkedHashMap(existingArtifacts)
    }
    input.artifactsPatch?.let { patch -> mergedArtifacts.putAll(patch) }
    val terminal = input.workflowStatus in definition.terminalStatuses
    val updated = existing.copy(
      sessionId = input.sessionId.trim().ifBlank { existing.sessionId.orEmpty() },
      workflowStatus = input.workflowStatus,
      currentStepId = input.currentStepId.trim().ifBlank { existing.currentStepId.orEmpty() },
      stepsJson = jsonString(mergeStepUpdates(definition, decodeSteps(existing.stepsJson), input.stepUpdates)),
      artifactsJson = jsonString(mergedArtifacts),
      finishedAt = if (terminal) existing.finishedAt ?: "" else null,
    )
    schemaValidator.validate(updated, definition.workflowName)
    return updated
  }

  fun snapshotView(definition: WorkflowDefinition, record: WorkflowStateSnapshot): WorkflowSnapshotView {
    schemaValidator.validate(record, definition.workflowName)
    return snapshotViewFrom(record)
  }

  fun summaryView(definition: WorkflowDefinition, record: WorkflowStateSnapshot): WorkflowSummaryView {
    schemaValidator.validate(record, definition.workflowName)
    return WorkflowSummaryView(
      workflowId = record.workflowId,
      sessionId = record.sessionId.orEmpty(),
      workflowName = record.workflowName,
      mode = record.mode,
      contractVersion = record.contractVersion,
      workflowStatus = record.workflowStatus,
      currentStepId = record.currentStepId.orEmpty(),
      startedAt = record.startedAt.orEmpty(),
      updatedAt = record.updatedAt.orEmpty(),
      finishedAt = record.finishedAt.orEmpty(),
    )
  }

  fun updateAcknowledgementView(
    snapshot: WorkflowSnapshotView,
    input: WorkflowUpdateInput,
  ): WorkflowUpdateAcknowledgementView = WorkflowUpdateAcknowledgementView(
    status = "ok",
    workflowId = snapshot.workflowId,
    workflowName = snapshot.workflowName,
    workflowStatus = snapshot.workflowStatus,
    currentStepId = snapshot.currentStepId,
    updatedStepIds = input.stepUpdates.orEmpty().mapNotNull { it["step_id"] as? String },
    updatedArtifactKeys = input.artifactsPatch.orEmpty().keys.sorted(),
    readOnlyFullStateGuidance =
    "Update returns a compact acknowledgement. Use explicit read-only workflow get/show for full state, " +
      "including steps and the complete durable artifacts map.",
  )

  fun resumeView(definition: WorkflowDefinition, record: WorkflowStateSnapshot): WorkflowResumeView {
    val snapshot = snapshotView(definition, record)
    val stepsById = snapshot.steps.associateBy { it.stepId }
    val lastCompletedStepId =
      definition.stepIds
        .lastOrNull { stepId -> stepsById[stepId]?.status?.workflowStepStatus() == WorkflowStepStatus.COMPLETED }
        .orEmpty()

    var resumeStepId = snapshot.currentStepId
    val resumeMode =
      when {
        snapshot.workflowStatus.workflowStatus() == WorkflowStatus.COMPLETED -> WorkflowResumeMode.DONE
        snapshot.workflowStatus in definition.terminalStatuses -> WorkflowResumeMode.RECOVER
        else -> WorkflowResumeMode.RESUME
      }
    val currentStepCompleted =
      stepsById[snapshot.currentStepId]?.status?.workflowStepStatus() == WorkflowStepStatus.COMPLETED
    if (resumeMode == WorkflowResumeMode.RESUME && currentStepCompleted) {
      resumeStepId =
        definition.stepIds.firstOrNull { stepId ->
          stepsById[stepId]?.status?.workflowStepStatus() in workflowResumableStepStatuses
        }
          ?: snapshot.currentStepId
    }
    val availableArtifacts = snapshot.artifacts.keys.sorted()
    val requiredArtifacts = definition.requiredArtifactsByStep[resumeStepId].orEmpty()
    val missingArtifacts =
      definition.requiredArtifactPresenceResolver.missingRequiredArtifacts(snapshot, resumeStepId, requiredArtifacts)
        .filterNot { it == RUNTIME_REPOSITORY_EVIDENCE_ARTIFACT_KEY }
    val canResume = resumeMode != WorkflowResumeMode.DONE && missingArtifacts.isEmpty()
    val nextAction =
      if (resumeMode == WorkflowResumeMode.DONE) {
        "Workflow already completed. Inspect ${definition.completedTerminalSummaryArtifact} or telemetry for a summary."
      } else {
        definition.resumeActions[resumeStepId]
          ?: "Inspect workflow state, refresh missing artifacts, and continue from the current step."
      }
    return WorkflowResumeView(
      snapshot = snapshot,
      resumeMode = resumeMode,
      resumeStepId = resumeStepId,
      lastCompletedStepId = lastCompletedStepId,
      availableArtifacts = availableArtifacts,
      requiredArtifacts = requiredArtifacts,
      missingArtifacts = missingArtifacts,
      canResume = canResume,
      nextAction = nextAction,
    )
  }

  fun continueDecision(
    definition: WorkflowDefinition,
    record: WorkflowStateSnapshot,
    sessionSummary: Map<String, Any?> = emptyMap(),
    continueStatusOverride: WorkflowContinueStatus? = null,
    workflowStatusBeforeContinueOverride: String? = null,
  ): WorkflowContinueDecision {
    val resume = resumeView(definition, record)
    val snapshot = resume.snapshot
    val currentStep = snapshot.steps.firstOrNull { it.stepId == resume.resumeStepId }
    val attemptCount = currentStep?.attemptCount ?: 0
    val nextAttemptCount = maxOf(attemptCount + 1, 1)
    val actualContinueStatus = continueStatusFor(snapshot, resume, currentStep)
    val continueStatus = continueStatusOverride ?: actualContinueStatus
    val workflowStatusBeforeContinue = workflowStatusBeforeContinueOverride ?: snapshot.workflowStatus
    return buildContinueDecision(
      BuildContinueDecisionRequest(
        context = ContinueAssemblyContext(
          definition = definition,
          record = record,
          resume = resume,
          snapshot = snapshot,
          declaredProjection = launchProjection(definition, snapshot, resume.resumeStepId, attemptCount),
        ),
        continueStatus = continueStatus,
        workflowStatusBeforeContinue = workflowStatusBeforeContinue,
        actualContinueStatus = actualContinueStatus,
        nextAttemptCount = nextAttemptCount,
        sessionSummary = sessionSummary,
      ),
    )
  }

  fun launchProjection(
    definition: WorkflowDefinition,
    snapshot: WorkflowSnapshotView,
    stepId: String,
    producerIteration: Int,
    resolvedRepositoryCheckpointIdentity: String = checkpoint(),
  ): WorkflowInputProjection? = definition.inputProjectionsByStep[stepId]?.let {
    WorkflowInputProjectionSelector.select(
      definition,
      snapshot,
      stepId,
      producerIteration,
      resolvedRepositoryCheckpointIdentity,
    )
  }

  fun freshLaunchProjection(
    definition: WorkflowDefinition,
    record: WorkflowStateSnapshot,
    stepId: String,
    producerIteration: Int,
  ): WorkflowInputProjection? =
    launchProjection(definition, snapshotView(definition, record), stepId, producerIteration)

  companion object {
    fun validateOpen(definition: WorkflowDefinition, currentStepId: String): String? =
      validateWorkflowOpen(definition, currentStepId)

    fun validateUpdate(definition: WorkflowDefinition, input: WorkflowUpdateInput): String? =
      validateWorkflowUpdate(definition, input)
  }
}
