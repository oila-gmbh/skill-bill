package skillbill.workflow.engine

import skillbill.workflow.engine.model.WorkflowCompactContinueView
import skillbill.workflow.engine.model.WorkflowContinueDecision
import skillbill.workflow.engine.model.WorkflowContinueView
import skillbill.workflow.engine.model.WorkflowDefinition
import skillbill.workflow.engine.model.WorkflowInputProjection
import skillbill.workflow.engine.model.WorkflowResumeView
import skillbill.workflow.engine.model.WorkflowSnapshotView
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.engine.model.WorkflowStepState
import skillbill.workflow.engine.model.WorkflowSummaryView
import skillbill.workflow.engine.model.WorkflowUpdateAcknowledgementView
import skillbill.workflow.engine.model.WorkflowUpdateInput
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition

private typealias CheckpointResolver = () -> String

private val unresolvedCheckpoint: CheckpointResolver = { "" }

class WorkflowEngine(
  private val schemaValidator: WorkflowSnapshotValidator,
  private val checkpoint: CheckpointResolver = unresolvedCheckpoint,
) {
  private fun validatedSnapshotMap(definition: WorkflowDefinition, record: WorkflowStateSnapshot): Map<String, Any?> {
    val snapshot = linkedMapOf<String, Any?>(
      "workflow_id" to record.workflowId,
      "session_id" to record.sessionId.orEmpty(),
      "workflow_name" to record.workflowName,
      "contract_version" to record.contractVersion,
      "workflow_status" to record.workflowStatus,
      "current_step_id" to record.currentStepId.orEmpty(),
      "steps" to decodeSteps(record.stepsJson),
      "artifacts" to decodeObject(record.artifactsJson),
      "started_at" to record.startedAt.orEmpty(),
      "updated_at" to record.updatedAt.orEmpty(),
      "finished_at" to record.finishedAt.orEmpty(),
    ).apply {
      record.mode?.let { mode -> put("mode", mode) }
    }
    schemaValidator.validate(snapshot, definition.workflowName)
    return snapshot
  }

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
    validatedSnapshotMap(definition, snapshot)
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
    validatedSnapshotMap(definition, updated)
    return updated
  }

  fun snapshotView(definition: WorkflowDefinition, record: WorkflowStateSnapshot): WorkflowSnapshotView {
    val map = validatedSnapshotMap(definition, record)
    return snapshotViewFromMap(map)
  }

  fun summaryView(definition: WorkflowDefinition, record: WorkflowStateSnapshot): WorkflowSummaryView {
    val map = validatedSnapshotMap(definition, record)
    return WorkflowSummaryView(
      workflowId = map["workflow_id"] as String,
      sessionId = map["session_id"] as String,
      workflowName = map["workflow_name"] as String,
      mode = map["mode"] as? String,
      contractVersion = map["contract_version"] as String,
      workflowStatus = map["workflow_status"] as String,
      currentStepId = map["current_step_id"] as String,
      startedAt = map["started_at"] as String,
      updatedAt = map["updated_at"] as String,
      finishedAt = map["finished_at"] as String,
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
    val declaredProjection = launchProjection(
      definition,
      snapshot,
      resume.resumeStepId,
      attemptCount,
    )
    val stepArtifactKeys = declaredProjection?.artifacts?.keys?.toList()
      ?: continueArtifactKeys(definition, resume.resumeStepId, snapshot)
    val stepArtifacts = declaredProjection?.artifacts ?: stepArtifactKeys.associateWith { key ->
      resolvedArtifactValue(definition, snapshot, key).value
    }
    val currentStepArtifactKeys = resume.requiredArtifacts
    val omittedArtifactKeys = resume.availableArtifacts.filterNot(currentStepArtifactKeys::contains)
    val extraFields =
      if (definition.workflowName == FeatureTaskRuntimePhaseWorkflowDefinition.definition.workflowName) {
        implementExtraFields(snapshot.artifacts)
      } else {
        emptyMap()
      }
    val continuationBriefText = continuationBrief(
      definition = definition,
      workflowId = record.workflowId,
      resumeStepId = resume.resumeStepId,
      continueStatus = continueStatus,
      nextAction = resume.nextAction,
      currentStepArtifactKeys = currentStepArtifactKeys,
      omittedArtifactKeys = omittedArtifactKeys,
    )
    val continuationEntryPromptText = continuationEntryPrompt(
      definition = definition,
      workflowId = record.workflowId,
      sessionId = record.sessionId.orEmpty(),
      resumeStepId = resume.resumeStepId,
      continueStatus = continueStatus,
      currentStepArtifactKeys = currentStepArtifactKeys,
      omittedArtifactKeys = omittedArtifactKeys,
      nextAction = resume.nextAction,
      sessionSummary = sessionSummary,
      extraFields = extraFields,
      nextAttemptCount = nextAttemptCount,
    )
    val compact = compactContinueView(
      definition = definition,
      snapshot = snapshot,
      resume = resume,
      continueStatus = continueStatus,
      workflowStatusBeforeContinue = workflowStatusBeforeContinue,
      continueStepLabel = definition.stepLabels[resume.resumeStepId] ?: resume.resumeStepId,
      continueStepDirective = definition.continuationDirectives[resume.resumeStepId]
        ?: "Resume the workflow from the current step using the recovered artifacts as authoritative context.",
      continuationBrief = continuationBriefText,
      continuationEntryPrompt = continuationEntryPromptText,
      declaredProjection = declaredProjection,
    )
    val view = WorkflowContinueView(
      resume = resume,
      skillName = definition.skillName,
      workflowStatusBeforeContinue = workflowStatusBeforeContinue,
      continueStatus = continueStatus,
      continueStepId = resume.resumeStepId,
      continueStepLabel = definition.stepLabels[resume.resumeStepId] ?: resume.resumeStepId,
      continueStepDirective = definition.continuationDirectives[resume.resumeStepId]
        ?: "Resume the workflow from the current step using the recovered artifacts as authoritative context.",
      referenceSections = definition.continuationReferenceSections[resume.resumeStepId].orEmpty(),
      stepArtifactKeys = stepArtifactKeys,
      stepArtifacts = stepArtifacts,
      extraFields = extraFields,
      sessionSummary = sessionSummary,
      continuationBrief = continuationBriefText,
      continuationEntryPrompt = continuationEntryPromptText,
      compact = compact,
    )
    return WorkflowContinueDecision(
      view = view,
      shouldReopen = actualContinueStatus == "reopened",
      resumeStepId = resume.resumeStepId,
      nextAttemptCount = nextAttemptCount,
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

    fun snapshotMap(view: WorkflowSnapshotView) = WorkflowEngineWireMaps.snapshotMap(view)

    fun summaryMap(view: WorkflowSummaryView) = WorkflowEngineWireMaps.summaryMap(view)

    fun resumeMap(view: WorkflowResumeView) = WorkflowEngineWireMaps.resumeMap(view)

    fun continueMap(view: WorkflowContinueView) = WorkflowEngineWireMaps.continueMap(view)

    fun compactContinueMap(view: WorkflowCompactContinueView) =
      WorkflowEngineWireMaps.compactContinueMap(view)

    fun updateAcknowledgementMap(view: WorkflowUpdateAcknowledgementView) =
      WorkflowEngineWireMaps.updateAcknowledgementMap(view)

    fun inputProjectionMap(projection: WorkflowInputProjection) =
      WorkflowEngineWireMaps.inputProjectionMap(projection)
  }
}
