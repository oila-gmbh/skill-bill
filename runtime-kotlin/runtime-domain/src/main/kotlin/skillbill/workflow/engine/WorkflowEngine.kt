package skillbill.workflow.engine

import skillbill.workflow.engine.model.SessionId
import skillbill.workflow.engine.model.WorkflowContinueDecision
import skillbill.workflow.engine.model.WorkflowDefinition
import skillbill.workflow.engine.model.WorkflowId
import skillbill.workflow.engine.model.WorkflowInputProjection
import skillbill.workflow.engine.model.WorkflowResumeView
import skillbill.workflow.engine.model.WorkflowSnapshotView
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.engine.model.WorkflowSummaryView
import skillbill.workflow.engine.model.WorkflowUpdateAcknowledgementView
import skillbill.workflow.engine.model.WorkflowUpdateInput

private typealias CheckpointResolver = () -> String

private val unresolvedCheckpoint: CheckpointResolver = { "" }

class WorkflowEngine(
  private val schemaValidator: WorkflowSnapshotValidator,
  private val checkpoint: CheckpointResolver = unresolvedCheckpoint,
) {
  fun openRecord(
    definition: WorkflowDefinition,
    workflowId: WorkflowId,
    sessionId: SessionId,
    currentStepId: String,
  ): WorkflowStateSnapshot {
    val snapshot = WorkflowStateSnapshot(
      workflowId = workflowId,
      sessionId = sessionId,
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
      sessionId = input.sessionId.value.ifBlank { existing.sessionId.value }
        .let(::SessionId),
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
      sessionId = record.sessionId,
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
      definition.stepIds.lastOrNull { stepId -> stepsById[stepId]?.status == "completed" }.orEmpty()

    var resumeStepId = snapshot.currentStepId
    val resumeMode =
      when {
        snapshot.workflowStatus == "completed" -> "done"
        snapshot.workflowStatus in definition.terminalStatuses -> "recover"
        else -> "resume"
      }
    if (resumeMode == "resume" && stepsById[snapshot.currentStepId]?.status == "completed") {
      resumeStepId =
        definition.stepIds.firstOrNull { stepId -> stepsById[stepId]?.status in workflowResumableStepStatuses }
          ?: snapshot.currentStepId
    }
    val availableArtifacts = snapshot.artifacts.keys.sorted()
    val requiredArtifacts = definition.requiredArtifactsByStep[resumeStepId].orEmpty()
    val missingArtifacts =
      definition.requiredArtifactPresenceResolver.missingRequiredArtifacts(snapshot, resumeStepId, requiredArtifacts)
        .filterNot { it == RUNTIME_REPOSITORY_EVIDENCE_ARTIFACT_KEY }
    val canResume = resumeMode != "done" && missingArtifacts.isEmpty()
    val nextAction =
      if (resumeMode == "done") {
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
    continueStatusOverride: String? = null,
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
