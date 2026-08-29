package skillbill.application.workflow

import skillbill.application.goalrunner.GoalObservabilityArtifacts
import skillbill.application.workflow.model.WorkflowFamilyKind
import skillbill.application.workflow.model.WorkflowUpdateRequest
import skillbill.application.workflow.model.WorkflowUpdateResult
import skillbill.contracts.JsonSupport
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.workflow.engine.RUNTIME_REPOSITORY_EVIDENCE_ARTIFACT_KEY
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.WorkflowDefinition
import skillbill.workflow.engine.model.WorkflowSnapshotView
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.engine.model.WorkflowUpdateInput
import skillbill.workflow.goal.GoalObservabilityEventValidator
import java.nio.file.Path
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.random.Random
import skillbill.workflow.engine.model.WorkflowContinueDecision

internal val resolveEffectiveSessionId =
  { kind: WorkflowFamilyKind, sessionId: String, definition: WorkflowDefinition, workflowId: String ->
    sessionId.ifBlank {
      if (kind == WorkflowFamilyKind.TASK_RUNTIME) "${definition.defaultSessionPrefix}-$workflowId" else ""
    }
  }

internal fun WorkflowUpdateRequest.toWorkflowUpdateInput(): WorkflowUpdateInput = WorkflowUpdateInput(
  workflowStatus = workflowStatus,
  currentStepId = currentStepId,
  stepUpdates = stepUpdates,
  artifactsPatch = artifactsPatch,
  sessionId = sessionId,
)

internal fun WorkflowContinueDecision.toReopenInput(sessionId: String): WorkflowUpdateInput =
  WorkflowUpdateInput(
    workflowStatus = "running",
    currentStepId = resumeStepId,
    stepUpdates =
    listOf(
      mapOf(
        "step_id" to resumeStepId,
        "status" to "running",
        "attempt_count" to nextAttemptCount,
      ),
    ),
    artifactsPatch = null,
    sessionId = sessionId,
  )

internal fun WorkflowUpdateInput.withGoalObservabilityArtifacts(
  existing: WorkflowStateSnapshot,
  workflowId: String,
  validator: GoalObservabilityEventValidator,
  gitOperations: WorkflowGitOperations,
): WorkflowUpdateInput {
  val patch = artifactsPatch
  return if (patch?.containsKey("progress_event") != true) {
    this
  } else {
    val existingArtifacts = JsonSupport.parseObjectOrNull(existing.artifactsJson)
      ?.let(JsonSupport::jsonElementToValue)
      ?.let(JsonSupport::anyToStringAnyMap)
      .orEmpty()
    val mergedArtifacts = LinkedHashMap(existingArtifacts).apply { putAll(patch) }
    val observabilityPatch = GoalObservabilityArtifacts.patchForProgressEvent(
      input = GoalObservabilityArtifacts.ProgressInput(
        artifacts = mergedArtifacts,
        workflowId = workflowId,
        workflowStatus = workflowStatus,
        currentStepId = currentStepId,
        worktreeActivity = gitOperations.worktreeActivity(Path.of("").toAbsolutePath().normalize())
          .takeIf { activity -> activity.ok }
          ?.let { activity ->
            GoalObservabilityArtifacts.GoalObservabilityWorktreeActivity(
              changedFileSummary = activity.changedFileSummary,
              diffStat = activity.diffStat,
            )
          },
      ),
      validator = validator,
    )
    observabilityPatch?.let { copy(artifactsPatch = LinkedHashMap(patch).apply { putAll(it) }) } ?: this
  }
}

internal fun buildUpdateOk(
  engine: WorkflowEngine,
  definition: WorkflowDefinition,
  updated: WorkflowStateSnapshot,
  effectiveInput: WorkflowUpdateInput,
  dbPath: String,
): WorkflowUpdateResult.Ok {
  val snapshot = engine.snapshotView(definition, updated)
  val currentStep = snapshot.steps.firstOrNull { it.stepId == snapshot.currentStepId }
  return WorkflowUpdateResult.Ok(
    workflowId = updated.workflowId,
    dbPath = dbPath,
    acknowledgement = engine.updateAcknowledgementView(
      snapshot = snapshot,
      input = effectiveInput,
    ),
    launchProjection = launchProjectionIfReady(
      engine,
      definition,
      snapshot,
      snapshot.currentStepId,
      currentStep?.attemptCount ?: 0,
    ),
  )
}

internal fun launchProjectionIfReady(
  engine: WorkflowEngine,
  definition: WorkflowDefinition,
  snapshot: WorkflowSnapshotView,
  stepId: String,
  producerIteration: Int,
) = definition.inputProjectionsByStep[stepId]
  ?.takeIf { declaration ->
    declaration.requiredArtifactKeys.all { artifactKey ->
      artifactKey == RUNTIME_REPOSITORY_EVIDENCE_ARTIFACT_KEY || snapshot.artifacts.containsKey(artifactKey)
    }
  }
  ?.let { engine.launchProjection(definition, snapshot, stepId, producerIteration) }

internal fun generateWorkflowId(prefix: String): String {
  val now = OffsetDateTime.now(ZoneOffset.UTC)
  val suffix = (1..WORKFLOW_ID_SUFFIX_LENGTH).map { SUFFIX_CHARS[Random.nextInt(SUFFIX_CHARS.length)] }
    .joinToString("")
  return "$prefix-${now.year}${now.monthValue.twoDigits()}${now.dayOfMonth.twoDigits()}-" +
    "${now.hour.twoDigits()}${now.minute.twoDigits()}${now.second.twoDigits()}-$suffix"
}

private fun Int.twoDigits(): String = toString().padStart(2, '0')
