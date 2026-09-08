package skillbill.application.telemetry

import skillbill.application.telemetry.model.FeatureTaskRuntimeFinishedRequest
import skillbill.application.telemetry.model.GoalFinishedRequest
import skillbill.application.telemetry.model.GoalIssueFinishedRequest
import skillbill.application.telemetry.model.GoalStartedRequest
import skillbill.application.telemetry.model.GoalSubtaskFinishedRequest
import skillbill.application.telemetry.model.QualityCheckFinishedRequest
import skillbill.application.telemetry.model.QualityCheckStartedRequest
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.telemetry.TelemetrySettingsProvider
import skillbill.review.normalizeRoutedSkill
import skillbill.review.normalizeStackLabel
import skillbill.telemetry.model.TelemetrySettings

class LifecycleTelemetryGoalEmission(
  private val database: DatabaseSessionFactory,
  private val settingsProvider: TelemetrySettingsProvider,
) : GoalLifecycleTelemetryEmitter {
  override fun goalStarted(request: GoalStartedRequest, dbOverride: String?) {
    enabledStandaloneResult(settingsProvider, request.workflowId) { settings ->
      database.transaction(dbOverride) { unitOfWork ->
        unitOfWork.lifecycleTelemetry.goalStarted(request.toRecord(), settings.level)
      }
    }
  }

  override fun goalSubtaskFinished(request: GoalSubtaskFinishedRequest, dbOverride: String?) {
    enabledStandaloneResult(settingsProvider, request.workflowId) { settings ->
      val reconciledRequest = request.reconcileBlockedReason()
      database.transaction(dbOverride) { unitOfWork ->
        unitOfWork.lifecycleTelemetry.goalSubtaskFinished(reconciledRequest.toRecord(), settings.level)
      }
    }
  }

  override fun goalFinished(request: GoalFinishedRequest, dbOverride: String?) {
    enabledStandaloneResult(settingsProvider, request.workflowId) { settings ->
      database.transaction(dbOverride) { unitOfWork ->
        unitOfWork.lifecycleTelemetry.goalFinished(request.toRecord(), settings.level)
      }
    }
  }

  override fun goalIssueFinished(request: GoalIssueFinishedRequest, dbOverride: String?) {
    enabledStandaloneResult(settingsProvider, request.parentWorkflowId) { settings ->
      database.transaction(dbOverride) { unitOfWork ->
        unitOfWork.lifecycleTelemetry.goalIssueFinished(request.toRecord(), settings.level)
      }
    }
  }
}

internal fun enabledStandaloneResult(
  settingsProvider: TelemetrySettingsProvider,
  sessionId: String,
  action: (TelemetrySettings) -> Unit,
): Map<String, Any?> {
  val settings = telemetrySettingsOrNull(settingsProvider)
  return if (settings?.enabled == true) {
    action(settings)
    lifecycleOkPayload(sessionId)
  } else {
    lifecycleSkippedPayload(sessionId)
  }
}

internal fun FeatureTaskRuntimeFinishedRequest.reconcileBlockedRuntimeFields(): FeatureTaskRuntimeFinishedRequest {
  if (completionStatus != "blocked") {
    return this
  }
  return copy(
    lastIncompletePhase = lastIncompletePhase.takeIf(String::isNotBlank) ?: phaseOutcomes.firstIncompletePhase(),
    blockedReason = normalizedBlockedReason(
      reason = blockedReason,
      category = "runtime",
      fallback = "Feature-task-runtime blocked without a specific reason.",
    ),
  )
}

internal fun Map<String, String>.firstIncompletePhase(): String =
  entries.firstOrNull { it.value != "completed" }?.key?.takeIf(String::isNotBlank) ?: "unknown"

internal fun QualityCheckStartedRequest.normalizedLabels(): QualityCheckStartedRequest {
  val stack = normalizeStackLabel(detectedStack)
  return copy(
    routedSkill = normalizeRoutedSkill(routedSkill),
    detectedStack = stack.stack,
    fallback = fallback || stack.fallback,
    fallbackReason = fallbackReason ?: stack.fallbackReason,
  )
}

internal fun QualityCheckFinishedRequest.normalizedLabels(): QualityCheckFinishedRequest {
  val stack = normalizeStackLabel(detectedStack)
  return copy(
    routedSkill = normalizeRoutedSkill(routedSkill),
    detectedStack = stack.stack,
    fallback = fallback || stack.fallback,
    fallbackReason = fallbackReason ?: stack.fallbackReason,
  )
}

internal fun GoalSubtaskFinishedRequest.reconcileBlockedReason(): GoalSubtaskFinishedRequest {
  if (status != "blocked") {
    return this
  }
  return copy(
    blockedReason = normalizedBlockedReason(
      reason = blockedReason,
      category = "runtime",
      fallback = "Goal subtask $subtaskId is blocked.",
    ),
  )
}
