package skillbill.application.work

import me.tatarka.inject.annotations.Inject
import skillbill.application.featuretask.FeatureTaskRuntimeStatusService
import skillbill.application.featuretask.model.FeatureTaskRuntimeOperatorDecisionPause
import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseStatus
import skillbill.application.featuretask.model.FeatureTaskRuntimeStatusRequest
import skillbill.application.goalrunner.GoalRunnerStatusService
import skillbill.application.goalrunner.model.GoalRunnerStatusRequest
import skillbill.application.idestatus.model.IdeStatusCandidate
import skillbill.application.idestatus.model.IdeStatusCurrentModel
import skillbill.application.idestatus.model.IdeStatusCurrentPhaseExecution
import skillbill.application.idestatus.model.IdeStatusCurrentSubtask
import skillbill.application.idestatus.model.IdeStatusLifecycleState
import skillbill.application.idestatus.model.IdeStatusPauseReason
import skillbill.application.idestatus.model.IdeStatusPauseReasonCode
import skillbill.application.idestatus.model.IdeStatusPlanning
import skillbill.application.idestatus.model.IdeStatusProgress
import skillbill.application.idestatus.model.IdeStatusSnapshot
import skillbill.application.idestatus.model.IdeStatusStep
import skillbill.application.idestatus.model.IdeStatusWorkflowFamily
import skillbill.application.workflow.WorkflowFamily
import skillbill.error.ShellContentContractException
import skillbill.goalrunner.model.ExecutionLiveness
import skillbill.goalrunner.model.GoalPlanningStatusSnapshot
import skillbill.goalrunner.model.GoalPlanningStatusState
import skillbill.goalrunner.model.GoalRunnerStatusProjection
import skillbill.ports.db.UnitOfWork
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.work.model.WorkItemKind
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.WorkflowSnapshotValidator
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_STATUS_COMPLETED
import java.io.IOException
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** Shared projection inputs so family projectors stay under LongParameterList. */
internal data class IdeStatusProjectionContext(
  val unitOfWork: UnitOfWork,
  val repositoryIdentity: String,
  val observedAt: Instant,
  val dbOverride: String?,
  val repoRoot: Path,
)

/** Optional child-workflow fields loaded from one runtime status projection. */
private data class ChildOptionalContext(
  val currentPhaseId: String? = null,
  val currentModel: IdeStatusCurrentModel?,
  val currentPhaseExecution: IdeStatusCurrentPhaseExecution?,
  val operatorDecisionPause: FeatureTaskRuntimeOperatorDecisionPause? = null,
) {
  companion object {
    val EMPTY = ChildOptionalContext(currentModel = null, currentPhaseExecution = null)
  }
}

/**
 * Projects selected work through existing family authorities into the shared IDE model.
 * Does not copy SQLite row DTOs onto the wire.
 */
@Inject
class IdeStatusProjector(
  workflowSnapshotValidator: WorkflowSnapshotValidator,
  private val goalRunnerStatusService: GoalRunnerStatusService,
  private val featureTaskRuntimeStatusService: FeatureTaskRuntimeStatusService,
  private val diagnostics: RuntimeDiagnostics = NoopRuntimeDiagnostics,
) {
  private val workflowEngine = WorkflowEngine(workflowSnapshotValidator)

  internal fun project(candidate: IdeStatusCandidate, context: IdeStatusProjectionContext): IdeStatusSnapshot {
    return when (candidate.workflowFamily) {
      IdeStatusWorkflowFamily.FEATURE_GOAL -> projectGoal(candidate, context)
      IdeStatusWorkflowFamily.FEATURE_TASK_RUNTIME -> projectRuntime(candidate, context)
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
        dbPathOverride = context.dbOverride,
        repoRoot = context.repoRoot,
      ),
    )
    return assembleGoalStatusSnapshot(candidate, context, issueKey, projection)
  }

  private fun assembleGoalStatusSnapshot(
    candidate: IdeStatusCandidate,
    context: IdeStatusProjectionContext,
    issueKey: String,
    projection: GoalRunnerStatusProjection?,
  ): IdeStatusSnapshot {
    val lifecycle = goalLifecycle(candidate, projection)
    val planning = projection?.planning?.toIdeStatusPlanning()
    val planningStep = planning?.takeIf { it.state != GoalPlanningStatusState.PREPARED && !lifecycle.isSettled() }
    val freshness = IdeStatusFreshnessClassifier.classify(candidate.updatedAt, context.observedAt)
    val childContext = childOptionalContext(projection?.currentChildWorkflowId, lifecycle, context)
    val childPhaseStep = childContext.currentPhaseId
      ?.takeIf { it.isNotBlank() && planningStep == null && lifecycle != IdeStatusLifecycleState.TERMINAL }
    val step = goalStep(
      planningStep,
      childPhaseStep
        ?: projection?.currentStep?.takeUnless { lifecycle == IdeStatusLifecycleState.TERMINAL },
      lifecycle,
    )
    val total = (projection?.let { it.completeCount + it.pendingCount + it.blockedCount })
      ?.takeIf { it > 0 }
    val progress = total?.let {
      IdeStatusProgress(completed = projection.completeCount, total = it)
    }
    val currentSubtask = goalCurrentSubtask(projection, context)
    val (activityAt, activityLabel) = agentActivityFields(context.unitOfWork, candidate.workflowId)
    return IdeStatusSnapshot(
      repositoryIdentity = context.repositoryIdentity,
      issueKey = issueKey,
      workflowId = candidate.workflowId,
      workflowFamily = IdeStatusWorkflowFamily.FEATURE_GOAL,
      lifecycleState = lifecycle,
      currentStep = step,
      progress = progress,
      startedAt = candidate.startedAt,
      currentSubtask = currentSubtask,
      currentModel = childContext.currentModel,
      planning = planning,
      currentPhaseExecution = planningStep?.let { null } ?: childContext.currentPhaseExecution,
      pauseRequested = projection?.pauseRequested == true && projection.paused != true,
      pausedAt = parseInstantOrNull(projection?.pausedAt),
      pauseReason = goalPauseReason(lifecycle, projection, childContext),
      activeDurationMs = projection?.recordedActiveDurationMs(),
      activeDurationAsOf = projection?.liveActiveDurationAnchor(),
      lastAgentActivityAt = activityAt,
      lastAgentActivityLabel = activityLabel,
      updatedAt = candidate.updatedAt,
      freshness = freshness,
      summary = planningStep?.takeIf { lifecycle != IdeStatusLifecycleState.PAUSED }
        ?.let { goalPlanningSummary(issueKey, it) }
        ?: goalSummary(issueKey, lifecycle, step.label, projection?.blockedCount ?: 0),
    )
  }

  /**
   * Every subtask settled with no live worker lease means a parent row stuck after
   * finalization died — `running`, `blocked`, or `failed` are all stale against that
   * settled truth. A genuinely blocked goal still has `blockedCount > 0` and is not
   * reinterpreted. Pause controls only override an active candidate; a durable
   * blocked/failed/terminal state with unfinished subtasks must survive a stale pause
   * flag. Only a *consumed* pause downgrades to PAUSED: a requested-but-unconsumed pause
   * is still genuinely running its current subtask, and is reported as the
   * `pause_requested` modifier on an active goal instead.
   *
   * A `running` row whose parent lease is no longer live means no goal runner holds the
   * goal: the process was stopped or died without writing a terminal state. Reporting that
   * as active is the surface's worst lie — it counts an elapsed clock upward for work that
   * is not happening. Operator stop and crash are indistinguishable here (a killed process
   * records no intent), and both leave durable state resumable, so both report `paused`.
   * Only [ExecutionLiveness.IDLE] qualifies; UNKNOWN is a lease-read failure and must not
   * be read as absence.
   */
  private fun goalLifecycle(
    candidate: IdeStatusCandidate,
    projection: GoalRunnerStatusProjection?,
  ): IdeStatusLifecycleState {
    val settledComplete = projection != null &&
      projection.pendingCount == 0 &&
      projection.blockedCount == 0 &&
      projection.completeCount > 0 &&
      projection.executionLiveness != ExecutionLiveness.LIVE
    if (settledComplete) return IdeStatusLifecycleState.TERMINAL
    if (candidate.lifecycleState != IdeStatusLifecycleState.ACTIVE) return candidate.lifecycleState
    return when {
      projection?.paused == true -> IdeStatusLifecycleState.PAUSED
      projection?.executionLiveness == ExecutionLiveness.IDLE -> IdeStatusLifecycleState.PAUSED
      else -> IdeStatusLifecycleState.ACTIVE
    }
  }

  /**
   * Optional child-workflow context for a goal: the launched model and current-phase execution of
   * the currently running child phase. One status read feeds both so they cannot combine values
   * from different snapshots.
   *
   * Optional context must never cost a status reading: this is a nested read of a *different*
   * workflow's rows, so letting its failure escape would downgrade the whole goal snapshot to a
   * single `schema_incompatible` record through [IdeStatusService] — losing lifecycle, progress and
   * pause state over an optional field. The catch is bounded to the two failure modes that mean
   * "this child's rows cannot be read" — a typed durable-record contract failure and an IO failure.
   * A programming defect, an [Error] and a cancellation all still propagate rather than being
   * reported as a healthy snapshot, and each degradation emits a diagnostic naming the child, so a
   * systematically unreadable child is visible instead of silently context-less forever.
   *
   * A terminal goal returns before the read, for the same reason [goalStep] replaces a leftover step
   * string with "Complete": a finished goal can still hold a non-completed child phase record, and
   * reporting its model or execution would show live-looking context for work that is not running.
   * A blocked or failed goal keeps it — "which model was running when it stopped" is the diagnostic
   * there. That early return also skips the child's full status projection where it would buy nothing.
   *
   * The read deliberately sits outside [IdeStatusProjectionContext.unitOfWork]: the goal projection
   * this builds on already reads outside it, so threading only this one read through would not make
   * the snapshot atomic.
   */
  private fun goalPauseReason(
    lifecycle: IdeStatusLifecycleState,
    projection: GoalRunnerStatusProjection?,
    childContext: ChildOptionalContext,
  ): IdeStatusPauseReason? {
    if (lifecycle != IdeStatusLifecycleState.PAUSED) return null
    childContext.operatorDecisionPause?.let { pause ->
      return IdeStatusPauseReason.of(IdeStatusPauseReasonCode.AWAITING_OPERATOR_DECISION, pause.reason)
    }
    return IdeStatusPauseReasonCode.fromWire(projection?.pauseReason)
      ?.let { code -> IdeStatusPauseReason.of(code, null) }
  }

  private fun childOptionalContext(
    childWorkflowId: String?,
    lifecycle: IdeStatusLifecycleState,
    context: IdeStatusProjectionContext,
  ): ChildOptionalContext {
    if (lifecycle == IdeStatusLifecycleState.TERMINAL) return ChildOptionalContext.EMPTY
    val workflowId = childWorkflowId?.takeIf(String::isNotBlank) ?: return ChildOptionalContext.EMPTY
    val degraded = "IDE status omitted optional child context for workflow '$workflowId': " +
      "the child's durable status could not be read."
    val status = try {
      featureTaskRuntimeStatusService.status(
        FeatureTaskRuntimeStatusRequest(workflowId = workflowId, dbPathOverride = context.dbOverride),
      )
    } catch (error: ShellContentContractException) {
      diagnostics.warning(degraded, error)
      null
    } catch (error: IOException) {
      diagnostics.warning(degraded, error)
      null
    } ?: return ChildOptionalContext.EMPTY
    return ChildOptionalContext(
      currentPhaseId = status.currentPhaseId?.takeIf(String::isNotBlank),
      currentModel = status.currentPhaseId?.let { phaseId ->
        status.phases.firstOrNull { it.phaseId == phaseId }?.toIdeStatusCurrentModel()
      },
      currentPhaseExecution = status.currentPhaseExecution,
      operatorDecisionPause = status.operatorDecisionPause,
    )
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
    val (activityAt, activityLabel) = agentActivityFields(context.unitOfWork, candidate.workflowId)
    return IdeStatusSnapshot(
      repositoryIdentity = context.repositoryIdentity,
      issueKey = candidate.issueKey,
      workflowId = candidate.workflowId,
      workflowFamily = IdeStatusWorkflowFamily.FEATURE_TASK_RUNTIME,
      lifecycleState = candidate.lifecycleState,
      currentStep = IdeStatusStep(id = stepId, label = stepLabel),
      progress = progress,
      startedAt = startedAt,
      // Reuses the already-loaded projection: the model and execution reported must belong to the
      // phase reported as current_step, never to a neighbouring one.
      currentModel = status?.phases?.firstOrNull { it.phaseId == stepId }?.toIdeStatusCurrentModel(),
      currentPhaseExecution = status?.currentPhaseExecution?.takeIf { it.phaseId == stepId },
      pauseReason = status?.operatorDecisionPause
        ?.takeIf { candidate.lifecycleState == IdeStatusLifecycleState.PAUSED }
        ?.let { pause ->
          IdeStatusPauseReason.of(IdeStatusPauseReasonCode.AWAITING_OPERATOR_DECISION, pause.reason)
        },
      lastAgentActivityAt = activityAt,
      lastAgentActivityLabel = activityLabel,
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
    val (activityAt, activityLabel) = agentActivityFields(context.unitOfWork, candidate.workflowId)
    val wireFamily = when (family) {
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
      lastAgentActivityAt = activityAt,
      lastAgentActivityLabel = activityLabel,
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

private fun goalCurrentSubtask(
  projection: GoalRunnerStatusProjection?,
  context: IdeStatusProjectionContext,
): IdeStatusCurrentSubtask? = projection?.currentSubtaskId?.takeIf { it > 0 }?.let { subtaskId ->
  IdeStatusCurrentSubtask(
    id = subtaskId.toString(),
    startedAt = projection.currentChildWorkflowId?.takeIf(String::isNotBlank)?.let { workflowId ->
      context.unitOfWork.workList.list(limit = null)
        .firstOrNull { it.workflowId == workflowId }
        ?.startedAt
        ?: parseInstantOrNull(
          context.unitOfWork.workflowStates.getFeatureTaskWorkflow(workflowId)?.startedAt,
        )
    },
    activeDurationMs = projection.recordedSubtaskActiveDurationMs(),
    activeDurationAsOf = projection.liveSubtaskActiveDurationAnchor(),
  )
}

private fun goalStep(
  planningStep: IdeStatusPlanning?,
  projectedStep: String?,
  lifecycle: IdeStatusLifecycleState,
): IdeStatusStep {
  if (planningStep != null) return IdeStatusStep(id = "planning", label = "Planning")
  val projected = projectedStep?.takeIf(String::isNotBlank)
  if (projected != null) return IdeStatusStep(id = projected, label = projected)
  return if (lifecycle == IdeStatusLifecycleState.TERMINAL) {
    IdeStatusStep(id = "done", label = "Complete")
  } else {
    IdeStatusStep(id = "goal", label = "Goal")
  }
}

/**
 * A completed phase's model is history, not the model in force, so it is never projected as current.
 * A paused or blocked phase keeps it: "which model hit the usage limit" and "which model was running
 * when it blocked" are the questions an operator asks at exactly those states.
 */
private fun FeatureTaskRuntimePhaseStatus.toIdeStatusCurrentModel(): IdeStatusCurrentModel? {
  if (status == FEATURE_TASK_RUNTIME_PHASE_STATUS_COMPLETED) return null
  return launchedModel?.takeIf(String::isNotBlank)?.let { model ->
    IdeStatusCurrentModel(
      model = model,
      effort = launchedEffort?.takeIf(String::isNotBlank),
      phaseId = phaseId.takeIf(String::isNotBlank),
    )
  }
}

private fun GoalPlanningStatusSnapshot.toIdeStatusPlanning(): IdeStatusPlanning = IdeStatusPlanning(
  state = state,
  sharedPreplanPrepared = sharedPreplanPrepared,
  plannedSubtaskCount = plannedSubtaskCount,
  totalSubtaskCount = totalSubtaskCount,
  currentPlanningSubtaskId = currentPlanningSubtaskId?.toString(),
  reason = reason,
)

private fun IdeStatusLifecycleState.isSettled(): Boolean = this == IdeStatusLifecycleState.BLOCKED ||
  this == IdeStatusLifecycleState.FAILED ||
  this == IdeStatusLifecycleState.TERMINAL

private fun goalPlanningSummary(issueKey: String, planning: IdeStatusPlanning): String =
  "Goal $issueKey is planning subtasks " +
    "(${planning.plannedSubtaskCount}/${planning.totalSubtaskCount} planned)."

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
  IdeStatusLifecycleState.TERMINAL -> "Goal $issueKey is complete."
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
      // Workflow timestamp columns are written with SQLite CURRENT_TIMESTAMP, which is
      // offset-less UTC. Without this the value reads as unparseable and the caller falls
      // back to a coarser anchor.
      ?: runCatching { LocalDateTime.parse(value.trim(), SQLITE_TIMESTAMP_FORMATTER).toInstant(ZoneOffset.UTC) }
        .getOrNull()
  }
}

private val SQLITE_TIMESTAMP_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

// SKILL-175: FEATURE_TASK_PROSE is retained on WorkItemKind as a legacy read-only wire value so
// work-list history keeps listing prose rows, but the prose engine and its IDE status family are
// deleted. Returning null here excludes prose work items from live IDE status projection instead
// of dispatching them to a deleted family.
internal fun WorkItemKind.toIdeFamily(): IdeStatusWorkflowFamily? = when (this) {
  WorkItemKind.FEATURE_TASK_PROSE -> null
  WorkItemKind.FEATURE_TASK_RUNTIME -> IdeStatusWorkflowFamily.FEATURE_TASK_RUNTIME
  WorkItemKind.FEATURE_VERIFY -> IdeStatusWorkflowFamily.FEATURE_VERIFY
  WorkItemKind.FEATURE_GOAL -> IdeStatusWorkflowFamily.FEATURE_GOAL
}
