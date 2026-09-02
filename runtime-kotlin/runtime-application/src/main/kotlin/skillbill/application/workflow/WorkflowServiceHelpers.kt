package skillbill.application.workflow

import skillbill.application.normalizeIssueKey
import skillbill.application.workflow.model.GoalObservabilityProgressInput
import skillbill.application.workflow.model.GoalObservabilityWorktreeActivity
import skillbill.application.workflow.model.PersistOpenedWorkflowArgs
import skillbill.application.workflow.model.WorkflowFamilyKind
import skillbill.application.workflow.model.WorkflowOpenResult
import skillbill.application.workflow.model.WorkflowServiceOpenArgs
import skillbill.application.workflow.model.WorkflowServiceOpenFeatureTaskArgs
import skillbill.application.workflow.model.WorkflowUpdateRequest
import skillbill.application.workflow.model.WorkflowUpdateResult
import skillbill.contracts.JsonSupport
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.workflow.engine.RUNTIME_REPOSITORY_EVIDENCE_ARTIFACT_KEY
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.WorkflowContinueDecision
import skillbill.workflow.engine.model.WorkflowDefinition
import skillbill.workflow.engine.model.WorkflowSnapshotView
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.engine.model.WorkflowUpdateInput
import skillbill.workflow.goal.GoalObservabilityEventValidator
import java.nio.file.Path
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.random.Random

fun incompleteFeatureTaskIdentityError(args: WorkflowServiceOpenArgs): WorkflowOpenResult.Error? {
  val hasIdentityCoordinates = args.repositoryIdentity != null || args.governedSpecPath != null
  val hasIncompleteIdentity = hasIncompleteFeatureTaskIdentity(
    args.kind,
    hasIdentityCoordinates,
    args.issueKey,
    args.repositoryIdentity,
    args.governedSpecPath,
  )
  return if (hasIncompleteIdentity) {
    WorkflowOpenResult.Error(
      workflowId = "unassigned",
      error = INCOMPLETE_FEATURE_TASK_IDENTITY_ERROR,
    )
  } else {
    null
  }
}

fun persistOpenedWorkflow(args: PersistOpenedWorkflowArgs): WorkflowOpenResult =
  args.database.transaction(args.dbOverride) { unitOfWork ->
    val engine = args.engine
    val family = args.family
    val workflowId = args.workflowId
    val stepId = args.stepId
    val record = engine.openRecord(
      family.definition,
      workflowId,
      args.effectiveSessionId,
      stepId,
    )
    family.saveRecord(
      unitOfWork.workflowStates,
      record.toRecord().copy(
        startedAt = null,
        issueKey = normalizeIssueKey(args.issueKey),
      ),
    )
    args.executionIdentity?.let(unitOfWork.workflowStates::saveFeatureTaskExecutionIdentity)
    val saved = family.get(unitOfWork.workflowStates, workflowId) ?: record
    val currentStep = engine.snapshotView(family.definition, saved).steps
      .firstOrNull { it.stepId == stepId }
    val launchProjection = launchProjectionIfReady(
      engine,
      family.definition,
      engine.snapshotView(family.definition, saved),
      stepId,
      currentStep?.attemptCount ?: 0,
    )
    WorkflowOpenResult.Ok(
      workflowId = saved.workflowId,
      dbPath = unitOfWork.dbPath.toString(),
      snapshot = engine.snapshotView(family.definition, saved),
      launchProjection = launchProjection,
    )
  }

val resolveEffectiveSessionId =
  { kind: WorkflowFamilyKind, sessionId: String, definition: WorkflowDefinition, workflowId: String ->
    sessionId.ifBlank {
      if (kind == WorkflowFamilyKind.TASK_RUNTIME) "${definition.defaultSessionPrefix}-$workflowId" else ""
    }
  }

fun WorkflowUpdateRequest.toWorkflowUpdateInput(): WorkflowUpdateInput = WorkflowUpdateInput(
  workflowStatus = workflowStatus,
  currentStepId = currentStepId,
  stepUpdates = stepUpdates,
  artifactsPatch = artifactsPatch,
  sessionId = sessionId,
)

fun WorkflowContinueDecision.toReopenInput(sessionId: String): WorkflowUpdateInput = WorkflowUpdateInput(
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

fun WorkflowUpdateInput.withGoalObservabilityArtifacts(
  existing: WorkflowStateSnapshot,
  workflowId: String,
  validator: GoalObservabilityEventValidator,
  gitOperations: WorkflowGitOperations,
  repoRoot: Path,
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
      input = GoalObservabilityProgressInput(
        artifacts = mergedArtifacts,
        workflowId = workflowId,
        workflowStatus = workflowStatus,
        currentStepId = currentStepId,
        worktreeActivity = gitOperations.worktreeActivity(repoRoot.normalize())
          .takeIf { activity -> activity.ok }
          ?.let { activity ->
            GoalObservabilityWorktreeActivity(
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

fun buildUpdateOk(
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

fun launchProjectionIfReady(
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

fun WorkflowService.openFeatureTask(args: WorkflowServiceOpenFeatureTaskArgs): WorkflowOpenResult {
  require(args.kind in FEATURE_TASK_FAMILY_KINDS) {
    "Only runtime feature-task workflows use execution identity."
  }
  return open(
    WorkflowServiceOpenArgs(
      kind = args.kind,
      sessionId = args.sessionId,
      currentStepId = args.currentStepId,
      dbOverride = args.dbOverride,
      issueKey = args.issueKey,
      repositoryIdentity = args.repositoryIdentity,
      governedSpecPath = args.governedSpecPath,
      routeScope = args.routeScope,
    ),
  )
}

fun generateWorkflowId(prefix: String): String {
  val now = OffsetDateTime.now(ZoneOffset.UTC)
  val suffix = (1..WORKFLOW_ID_SUFFIX_LENGTH).map { SUFFIX_CHARS[Random.nextInt(SUFFIX_CHARS.length)] }
    .joinToString("")
  return "$prefix-${now.year}${now.monthValue.twoDigits()}${now.dayOfMonth.twoDigits()}-" +
    "${now.hour.twoDigits()}${now.minute.twoDigits()}${now.second.twoDigits()}-$suffix"
}

private fun Int.twoDigits(): String = toString().padStart(2, '0')
