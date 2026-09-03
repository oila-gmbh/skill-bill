package skillbill.application.work

import skillbill.application.featuretask.model.FeatureTaskRuntimeOperatorDecisionPause
import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseStatus
import skillbill.application.idestatus.model.IdeStatusCurrentModel
import skillbill.application.idestatus.model.IdeStatusCurrentSubtask
import skillbill.application.idestatus.model.IdeStatusLifecycleState
import skillbill.application.idestatus.model.IdeStatusPlanning
import skillbill.application.idestatus.model.IdeStatusStep
import skillbill.application.idestatus.model.IdeStatusWorkflowFamily
import skillbill.goalrunner.model.GoalPlanningStatusSnapshot
import skillbill.goalrunner.model.GoalRunnerStatusProjection
import skillbill.ports.work.model.WorkItemKind
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_STATUS_COMPLETED
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

internal fun goalCurrentSubtask(
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

internal fun goalStep(
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

internal fun FeatureTaskRuntimePhaseStatus.toIdeStatusCurrentModel(): IdeStatusCurrentModel? {
  if (status == FEATURE_TASK_RUNTIME_PHASE_STATUS_COMPLETED) return null
  return launchedModel?.takeIf(String::isNotBlank)?.let { model ->
    IdeStatusCurrentModel(
      model = model,
      effort = launchedEffort?.takeIf(String::isNotBlank),
      phaseId = phaseId.takeIf(String::isNotBlank),
    )
  }
}

internal fun GoalPlanningStatusSnapshot.toIdeStatusPlanning(): IdeStatusPlanning = IdeStatusPlanning(
  state = state,
  sharedPreplanPrepared = sharedPreplanPrepared,
  plannedSubtaskCount = plannedSubtaskCount,
  totalSubtaskCount = totalSubtaskCount,
  currentPlanningSubtaskId = currentPlanningSubtaskId?.toString(),
  planningWaveSubtaskIds = planningWaveSubtaskIds.map(Int::toString),
  reason = reason,
)

internal fun IdeStatusLifecycleState.isSettled(): Boolean = this == IdeStatusLifecycleState.BLOCKED ||
  this == IdeStatusLifecycleState.FAILED ||
  this == IdeStatusLifecycleState.TERMINAL

internal fun goalPlanningSummary(issueKey: String, planning: IdeStatusPlanning): String {
  val concurrent = planning.planningWaveSubtaskIds.size
  val wave = when (concurrent) {
    0 -> ""
    1 -> " 1 subtask is being planned now."
    else -> " $concurrent subtasks are being planned now."
  }
  return "Goal $issueKey is planning subtasks " +
    "(${planning.plannedSubtaskCount}/${planning.totalSubtaskCount} planned).$wave"
}

internal fun goalSummary(
  issueKey: String,
  lifecycle: IdeStatusLifecycleState,
  stepLabel: String,
  blockedCount: Int,
  operatorDecisionPause: FeatureTaskRuntimeOperatorDecisionPause? = null,
): String = when (lifecycle) {
  IdeStatusLifecycleState.BLOCKED -> {
    operatorDecisionPause?.reason?.takeIf(String::isNotBlank)?.let { reason ->
      "Goal $issueKey is blocked: $reason"
    } ?: run {
      val subtasks = if (blockedCount == 1) "subtask" else "subtasks"
      "Goal $issueKey is blocked" + if (blockedCount > 0) " ($blockedCount $subtasks)." else "."
    }
  }
  IdeStatusLifecycleState.PAUSED -> "Goal $issueKey is paused."
  IdeStatusLifecycleState.FAILED -> "Goal $issueKey failed."
  IdeStatusLifecycleState.TERMINAL -> "Goal $issueKey is complete."
  IdeStatusLifecycleState.ACTIVE -> "Goal $issueKey is active on $stepLabel."
  IdeStatusLifecycleState.IDLE -> "No matching Skill Bill work for this repository."
}

internal fun familySummary(
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
      ?: runCatching { LocalDateTime.parse(value.trim(), SQLITE_TIMESTAMP_FORMATTER).toInstant(ZoneOffset.UTC) }
        .getOrNull()
  }
}

internal val SQLITE_TIMESTAMP_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

internal fun WorkItemKind.toIdeFamily(): IdeStatusWorkflowFamily? = when (this) {
  WorkItemKind.FEATURE_TASK_PROSE -> null
  WorkItemKind.FEATURE_TASK_RUNTIME -> IdeStatusWorkflowFamily.FEATURE_TASK_RUNTIME
  WorkItemKind.FEATURE_VERIFY -> IdeStatusWorkflowFamily.FEATURE_VERIFY
  WorkItemKind.FEATURE_GOAL -> IdeStatusWorkflowFamily.FEATURE_GOAL
}
