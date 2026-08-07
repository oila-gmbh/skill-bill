package skillbill.application.work

import me.tatarka.inject.annotations.Inject
import skillbill.application.featuretask.FeatureTaskRuntimeStatusService
import skillbill.application.goalrunner.GoalRunnerStatusService
import skillbill.application.model.FeatureTaskRuntimeStatusRequest
import skillbill.application.model.GoalRunnerStatusRequest
import skillbill.application.model.IdeStatusCandidate
import skillbill.application.model.IdeStatusCurrentSubtask
import skillbill.application.model.IdeStatusLifecycleState
import skillbill.application.model.IdeStatusProgress
import skillbill.application.model.IdeStatusSnapshot
import skillbill.application.model.IdeStatusStep
import skillbill.application.model.IdeStatusWorkflowFamily
import skillbill.application.workflow.WorkflowFamily
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.persistence.model.WorkItemKind
import skillbill.workflow.WorkflowEngine
import skillbill.workflow.WorkflowSnapshotValidator
import java.nio.file.Path
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/** Shared projection inputs so family projectors stay under LongParameterList. */
internal data class IdeStatusProjectionContext(
  val unitOfWork: UnitOfWork,
  val repositoryIdentity: String,
  val observedAt: Instant,
  val dbOverride: String?,
  val repoRoot: Path,
)

/**
 * Projects selected work through existing family authorities into the shared IDE model.
 * Does not copy SQLite row DTOs onto the wire.
 */
@Inject
class IdeStatusProjector(
  workflowSnapshotValidator: WorkflowSnapshotValidator,
  private val goalRunnerStatusService: GoalRunnerStatusService,
  private val featureTaskRuntimeStatusService: FeatureTaskRuntimeStatusService,
) {
  private val workflowEngine = WorkflowEngine(workflowSnapshotValidator)

  internal fun project(candidate: IdeStatusCandidate, context: IdeStatusProjectionContext): IdeStatusSnapshot {
    return when (candidate.workflowFamily) {
      IdeStatusWorkflowFamily.FEATURE_GOAL -> projectGoal(candidate, context)
      IdeStatusWorkflowFamily.FEATURE_TASK_RUNTIME -> projectRuntime(candidate, context)
      IdeStatusWorkflowFamily.FEATURE_TASK_PROSE ->
        projectWorkflowFamily(candidate, context, WorkflowFamily.IMPLEMENT)
      IdeStatusWorkflowFamily.FEATURE_VERIFY ->
        projectWorkflowFamily(candidate, context, WorkflowFamily.VERIFY)
    }
  }

  private fun projectGoal(candidate: IdeStatusCandidate, context: IdeStatusProjectionContext): IdeStatusSnapshot {
    val issueKey = candidate.issueKey
      ?: return incompatible(candidate, context, "Goal work is missing an issue key.")
    val projection = goalRunnerStatusService.status(
      GoalRunnerStatusRequest(
        issueKey = issueKey,
        invokedAgentId = "ide-status",
        dbPathOverride = context.dbOverride,
        repoRoot = context.repoRoot,
      ),
    )
    val lifecycle = when {
      projection?.paused == true || projection?.pauseRequested == true -> IdeStatusLifecycleState.PAUSED
      else -> candidate.lifecycleState
    }
    val stepId = projection?.currentStep?.takeIf(String::isNotBlank)
      ?: if (lifecycle == IdeStatusLifecycleState.TERMINAL) "done" else "goal"
    val stepLabel = projection?.currentStep?.takeIf(String::isNotBlank)
      ?: if (lifecycle == IdeStatusLifecycleState.TERMINAL) "Complete" else "Goal"
    val total = (projection?.let { it.completeCount + it.pendingCount + it.blockedCount })
      ?.takeIf { it > 0 }
    val progress = total?.let {
      IdeStatusProgress(completed = projection.completeCount, total = it)
    }
    val currentSubtask = projection?.currentSubtaskId?.takeIf { it > 0 }?.let { subtaskId ->
      IdeStatusCurrentSubtask(
        id = subtaskId.toString(),
        // Durable child WorkItem/workflow started_at only; never synthesize from updated_at.
        startedAt = resolveLaunchedChildStartedAt(
          context.unitOfWork,
          projection.currentChildWorkflowId,
        ),
      )
    }
    val freshness = IdeStatusFreshnessClassifier.classify(candidate.updatedAt, context.observedAt)
    return IdeStatusSnapshot(
      repositoryIdentity = context.repositoryIdentity,
      issueKey = issueKey,
      workflowId = candidate.workflowId,
      workflowFamily = IdeStatusWorkflowFamily.FEATURE_GOAL,
      lifecycleState = lifecycle,
      currentStep = IdeStatusStep(id = stepId, label = stepLabel),
      progress = progress,
      startedAt = candidate.startedAt,
      currentSubtask = currentSubtask,
      updatedAt = candidate.updatedAt,
      freshness = freshness,
      summary = goalSummary(issueKey, lifecycle, stepLabel, projection?.blockedCount ?: 0),
    )
  }

  /**
   * Resolve current-subtask started_at from the launched child's durable WorkItem or workflow
   * snapshot. Omit when no child scope exists or legacy state lacks started_at.
   */
  private fun resolveLaunchedChildStartedAt(unitOfWork: UnitOfWork, childWorkflowId: String?): Instant? {
    val workflowId = childWorkflowId?.takeIf(String::isNotBlank) ?: return null
    val workStarted = unitOfWork.workList.list(limit = null)
      .firstOrNull { it.workflowId == workflowId }
      ?.startedAt
    if (workStarted != null) return workStarted
    val snapshot = unitOfWork.workflowStates.getFeatureTaskWorkflow(workflowId)
    return parseInstantOrNull(snapshot?.startedAt)
  }

  private fun projectRuntime(candidate: IdeStatusCandidate, context: IdeStatusProjectionContext): IdeStatusSnapshot {
    val snapshot = WorkflowFamily.TASK_RUNTIME.get(context.unitOfWork.workflowStates, candidate.workflowId)
      ?: return incompatible(candidate, context, "Runtime workflow snapshot is missing.")
    val status = featureTaskRuntimeStatusService.status(
      FeatureTaskRuntimeStatusRequest(
        workflowId = candidate.workflowId,
        dbPathOverride = context.dbOverride,
      ),
    )
    val stepId = status?.currentPhaseId?.takeIf(String::isNotBlank)
      ?: snapshot.currentStepId.takeIf(String::isNotBlank)
      ?: "unknown"
    val stepLabel = WorkflowFamily.TASK_RUNTIME.definition.stepLabels[stepId]
      ?: stepId.replace('_', ' ').replaceFirstChar { it.titlecase() }
    val phaseTotal = status?.phases?.size?.takeIf { it > 0 }
    val progress = phaseTotal?.let {
      IdeStatusProgress(completed = status.completeCount, total = it)
    }
    val startedAt = parseInstantOrNull(snapshot.startedAt) ?: candidate.startedAt
    // Retention, freshness, and the emitted updated_at must share one anchor; the candidate
    // already carries the snapshot-preferred value, so never re-derive it here.
    val updatedAt = candidate.updatedAt
    return IdeStatusSnapshot(
      repositoryIdentity = context.repositoryIdentity,
      issueKey = candidate.issueKey,
      workflowId = candidate.workflowId,
      workflowFamily = IdeStatusWorkflowFamily.FEATURE_TASK_RUNTIME,
      lifecycleState = candidate.lifecycleState,
      currentStep = IdeStatusStep(id = stepId, label = stepLabel),
      progress = progress,
      startedAt = startedAt,
      updatedAt = updatedAt,
      freshness = IdeStatusFreshnessClassifier.classify(updatedAt, context.observedAt),
      summary = familySummary(
        IdeStatusWorkflowFamily.FEATURE_TASK_RUNTIME,
        candidate.issueKey,
        candidate.lifecycleState,
        stepLabel,
      ),
    )
  }

  private fun projectWorkflowFamily(
    candidate: IdeStatusCandidate,
    context: IdeStatusProjectionContext,
    family: WorkflowFamily,
  ): IdeStatusSnapshot {
    val snapshot = family.get(context.unitOfWork.workflowStates, candidate.workflowId)
      ?: return incompatible(
        candidate,
        context,
        "${family.humanName} workflow snapshot is missing.",
      )
    val view = workflowEngine.snapshotView(family.definition, snapshot)
    val stepId = view.currentStepId.takeIf(String::isNotBlank) ?: "unknown"
    val stepLabel = family.definition.stepLabels[stepId]
      ?: stepId.replace('_', ' ').replaceFirstChar { it.titlecase() }
    val completed = view.steps.count { it.status == "completed" || it.status == "skipped" }
    val total = family.definition.stepIds.size
    val progress = IdeStatusProgress(completed = completed, total = total).takeIf { total > 0 }
    val startedAt = parseInstantOrNull(snapshot.startedAt) ?: candidate.startedAt
    val updatedAt = candidate.updatedAt
    val wireFamily = when (family) {
      WorkflowFamily.IMPLEMENT -> IdeStatusWorkflowFamily.FEATURE_TASK_PROSE
      WorkflowFamily.VERIFY -> IdeStatusWorkflowFamily.FEATURE_VERIFY
      WorkflowFamily.TASK_RUNTIME -> IdeStatusWorkflowFamily.FEATURE_TASK_RUNTIME
    }
    return IdeStatusSnapshot(
      repositoryIdentity = context.repositoryIdentity,
      issueKey = candidate.issueKey,
      workflowId = candidate.workflowId,
      workflowFamily = wireFamily,
      lifecycleState = candidate.lifecycleState,
      currentStep = IdeStatusStep(id = stepId, label = stepLabel),
      progress = progress,
      startedAt = startedAt,
      updatedAt = updatedAt,
      freshness = IdeStatusFreshnessClassifier.classify(updatedAt, context.observedAt),
      summary = familySummary(wireFamily, candidate.issueKey, candidate.lifecycleState, stepLabel),
    )
  }

  private fun incompatible(
    candidate: IdeStatusCandidate,
    context: IdeStatusProjectionContext,
    message: String,
  ): IdeStatusSnapshot = IdeStatusProblemSnapshots.incompatibleRecord(
    repositoryIdentity = context.repositoryIdentity,
    observedAt = context.observedAt,
    message = message,
    workflowId = candidate.workflowId,
  )
}

private fun goalSummary(
  issueKey: String,
  lifecycle: IdeStatusLifecycleState,
  stepLabel: String,
  blockedCount: Int,
): String = when (lifecycle) {
  IdeStatusLifecycleState.BLOCKED -> {
    val subtasks = if (blockedCount == 1) "subtask" else "subtasks"
    "Goal $issueKey is blocked" + if (blockedCount > 0) " ($blockedCount $subtasks)." else "."
  }
  IdeStatusLifecycleState.PAUSED -> "Goal $issueKey is paused."
  IdeStatusLifecycleState.FAILED -> "Goal $issueKey failed."
  IdeStatusLifecycleState.TERMINAL -> "Goal $issueKey is terminal."
  IdeStatusLifecycleState.ACTIVE -> "Goal $issueKey is active on $stepLabel."
  IdeStatusLifecycleState.IDLE -> "No matching Skill Bill work for this repository."
}

private fun familySummary(
  family: IdeStatusWorkflowFamily,
  issueKey: String?,
  lifecycle: IdeStatusLifecycleState,
  stepLabel: String,
): String {
  val subject = issueKey?.let { "${family.wireValue} $it" } ?: family.wireValue
  return when (lifecycle) {
    IdeStatusLifecycleState.ACTIVE -> "$subject is active on $stepLabel."
    IdeStatusLifecycleState.PAUSED -> "$subject is paused at $stepLabel."
    IdeStatusLifecycleState.BLOCKED -> "$subject is blocked at $stepLabel."
    IdeStatusLifecycleState.FAILED -> "$subject failed at $stepLabel."
    IdeStatusLifecycleState.TERMINAL -> "$subject is terminal."
    IdeStatusLifecycleState.IDLE -> "No matching Skill Bill work for this repository."
  }
}

internal fun parseInstantOrNull(value: String?): Instant? {
  if (value.isNullOrBlank()) return null
  return try {
    Instant.parse(value)
  } catch (_: DateTimeParseException) {
    runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()
  }
}

internal fun WorkItemKind.toIdeFamily(): IdeStatusWorkflowFamily = when (this) {
  WorkItemKind.FEATURE_TASK_PROSE -> IdeStatusWorkflowFamily.FEATURE_TASK_PROSE
  WorkItemKind.FEATURE_TASK_RUNTIME -> IdeStatusWorkflowFamily.FEATURE_TASK_RUNTIME
  WorkItemKind.FEATURE_VERIFY -> IdeStatusWorkflowFamily.FEATURE_VERIFY
  WorkItemKind.FEATURE_GOAL -> IdeStatusWorkflowFamily.FEATURE_GOAL
}
