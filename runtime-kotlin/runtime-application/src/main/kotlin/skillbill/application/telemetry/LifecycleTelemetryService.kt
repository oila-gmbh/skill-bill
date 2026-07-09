package skillbill.application.telemetry

import me.tatarka.inject.annotations.Inject
import skillbill.application.goalrunner.GoalLifecycleTelemetryEmitter
import skillbill.application.goalrunner.toRecord
import skillbill.application.model.FeatureImplementFinishedRequest
import skillbill.application.model.FeatureImplementStartedRequest
import skillbill.application.model.FeatureTaskRuntimeFinishedRequest
import skillbill.application.model.FeatureTaskRuntimeStartedRequest
import skillbill.application.model.FeatureVerifyFinishedRequest
import skillbill.application.model.FeatureVerifyStartedRequest
import skillbill.application.model.GoalFinishedRequest
import skillbill.application.model.GoalIssueFinishedRequest
import skillbill.application.model.GoalStartedRequest
import skillbill.application.model.GoalSubtaskFinishedRequest
import skillbill.application.model.PrDescriptionGeneratedRequest
import skillbill.application.model.QualityCheckFinishedRequest
import skillbill.application.model.QualityCheckStartedRequest
import skillbill.boundary.OpenBoundaryMap
import skillbill.ports.persistence.DatabaseSessionFactory
import skillbill.ports.telemetry.TelemetrySettingsProvider
import skillbill.telemetry.model.TelemetrySettings

@Inject
@Suppress("TooManyFunctions")
class LifecycleTelemetryService(
  private val database: DatabaseSessionFactory,
  private val settingsProvider: TelemetrySettingsProvider,
) : GoalLifecycleTelemetryEmitter {
  @OpenBoundaryMap("Lifecycle telemetry event bag emitted to the MCP/CLI telemetry boundary")
  fun featureImplementStarted(request: FeatureImplementStartedRequest): Map<String, Any?> {
    val sessionId = generateLifecycleSessionId("fis")
    return validateFeatureImplementStarted(request)
      ?.let { lifecycleErrorPayload(sessionId, it) }
      ?: enabledStandaloneResult(sessionId) { settings ->
        database.transaction(null) { unitOfWork ->
          unitOfWork.lifecycleTelemetry.featureImplementStarted(request.toRecord(sessionId), settings.level)
        }
      }
  }

  @OpenBoundaryMap("Lifecycle telemetry event bag emitted to the MCP/CLI telemetry boundary")
  fun featureImplementFinished(request: FeatureImplementFinishedRequest): Map<String, Any?> =
    validateFeatureImplementFinished(request)
      ?.let { lifecycleErrorPayload(request.sessionId, it) }
      ?: enabledStandaloneResult(request.sessionId) { settings ->
        database.transaction(null) { unitOfWork ->
          unitOfWork.lifecycleTelemetry.featureImplementFinished(request.toRecord(), settings.level)
        }
      }

  @OpenBoundaryMap("Lifecycle telemetry event bag emitted to the MCP/CLI telemetry boundary")
  fun featureTaskRuntimeStarted(
    request: FeatureTaskRuntimeStartedRequest,
    dbOverride: String? = null,
  ): Map<String, Any?> {
    val sessionId = request.sessionId.ifBlank { generateLifecycleSessionId("ftr") }
    return enabledStandaloneResult(sessionId) { settings ->
      database.transaction(dbOverride) { unitOfWork ->
        unitOfWork.lifecycleTelemetry.featureTaskRuntimeStarted(request.toRecord(sessionId), settings.level)
      }
    }
  }

  @OpenBoundaryMap("Lifecycle telemetry event bag emitted to the MCP/CLI telemetry boundary")
  fun featureTaskRuntimeFinished(
    request: FeatureTaskRuntimeFinishedRequest,
    dbOverride: String? = null,
  ): Map<String, Any?> = enabledStandaloneResult(request.sessionId) { settings ->
    val reconciledRequest = request.reconcileBlockedRuntimeFields()
    database.transaction(dbOverride) { unitOfWork ->
      unitOfWork.lifecycleTelemetry.featureTaskRuntimeFinished(reconciledRequest.toRecord(), settings.level)
    }
  }

  @OpenBoundaryMap("Lifecycle telemetry event bag emitted to the MCP/CLI telemetry boundary")
  fun qualityCheckStarted(request: QualityCheckStartedRequest): Map<String, Any?> {
    val sessionId = generateLifecycleSessionId("qck")
    return when {
      request.orchestrated -> orchestratedStartedSkippedPayload()
      else ->
        validateQualityCheckStarted(request)
          ?.let { lifecycleErrorPayload(sessionId, it) }
          ?: enabledStandaloneResult(sessionId) { settings ->
            database.transaction(null) { unitOfWork ->
              unitOfWork.lifecycleTelemetry.qualityCheckStarted(request.toRecord(sessionId), settings.level)
            }
          }
    }
  }

  @OpenBoundaryMap("Lifecycle telemetry event bag emitted to the MCP/CLI telemetry boundary")
  fun qualityCheckFinished(request: QualityCheckFinishedRequest): Map<String, Any?> =
    validateQualityCheckFinished(request)
      ?.let { lifecycleErrorPayload(request.sessionId, it) }
      ?: when {
        request.orchestrated -> request.orchestratedPayload(telemetryLevelOrAnonymous(settingsProvider))
        else ->
          enabledStandaloneResult(request.sessionId) { settings ->
            database.transaction(null) { unitOfWork ->
              unitOfWork.lifecycleTelemetry.qualityCheckFinished(request.toRecord(), settings.level)
            }
          }
      }

  @OpenBoundaryMap("Lifecycle telemetry event bag emitted to the MCP/CLI telemetry boundary")
  fun featureVerifyStarted(request: FeatureVerifyStartedRequest): Map<String, Any?> {
    val sessionId = generateLifecycleSessionId("fvr")
    return when {
      request.orchestrated -> orchestratedStartedSkippedPayload()
      else ->
        enabledStandaloneResult(sessionId) { settings ->
          database.transaction(null) { unitOfWork ->
            unitOfWork.lifecycleTelemetry.featureVerifyStarted(request.toRecord(sessionId), settings.level)
          }
        }
    }
  }

  @OpenBoundaryMap("Lifecycle telemetry event bag emitted to the MCP/CLI telemetry boundary")
  fun featureVerifyFinished(request: FeatureVerifyFinishedRequest): Map<String, Any?> =
    validateFeatureVerifyFinished(request)
      ?.let { lifecycleErrorPayload(request.sessionId, it) }
      ?: when {
        request.orchestrated -> request.orchestratedPayload(telemetryLevelOrAnonymous(settingsProvider))
        else ->
          enabledStandaloneResult(request.sessionId) { settings ->
            database.transaction(null) { unitOfWork ->
              unitOfWork.lifecycleTelemetry.featureVerifyFinished(request.toRecord(), settings.level)
            }
          }
      }

  @OpenBoundaryMap("Lifecycle telemetry event bag emitted to the MCP/CLI telemetry boundary")
  fun prDescriptionGenerated(request: PrDescriptionGeneratedRequest): Map<String, Any?> {
    val sessionId = if (request.orchestrated) "" else generateLifecycleSessionId("prd")
    return when {
      request.orchestrated -> request.orchestratedPayload(telemetryLevelOrAnonymous(settingsProvider))
      else ->
        enabledStandaloneResult(sessionId) { settings ->
          database.transaction(null) { unitOfWork ->
            unitOfWork.lifecycleTelemetry.prDescriptionGenerated(request.toRecord(sessionId), settings.level)
          }
        }
    }
  }

  override fun goalStarted(request: GoalStartedRequest, dbOverride: String?) {
    enabledStandaloneResult(request.workflowId) { settings ->
      database.transaction(dbOverride) { unitOfWork ->
        unitOfWork.lifecycleTelemetry.goalStarted(request.toRecord(), settings.level)
      }
    }
  }

  override fun goalSubtaskFinished(request: GoalSubtaskFinishedRequest, dbOverride: String?) {
    enabledStandaloneResult(request.workflowId) { settings ->
      database.transaction(dbOverride) { unitOfWork ->
        unitOfWork.lifecycleTelemetry.goalSubtaskFinished(request.toRecord(), settings.level)
      }
    }
  }

  override fun goalFinished(request: GoalFinishedRequest, dbOverride: String?) {
    enabledStandaloneResult(request.workflowId) { settings ->
      database.transaction(dbOverride) { unitOfWork ->
        unitOfWork.lifecycleTelemetry.goalFinished(request.toRecord(), settings.level)
      }
    }
  }

  override fun goalIssueFinished(request: GoalIssueFinishedRequest, dbOverride: String?) {
    enabledStandaloneResult(request.parentWorkflowId) { settings ->
      database.transaction(dbOverride) { unitOfWork ->
        unitOfWork.lifecycleTelemetry.goalIssueFinished(request.toRecord(), settings.level)
      }
    }
  }

  private fun enabledStandaloneResult(sessionId: String, action: (TelemetrySettings) -> Unit): Map<String, Any?> =
    enabledStandaloneResult(settingsProvider, sessionId, action)
}

internal fun telemetryLevelOrAnonymous(settingsProvider: TelemetrySettingsProvider): String =
  telemetrySettingsOrNull(settingsProvider)?.level ?: "anonymous"

private fun enabledStandaloneResult(
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

private fun FeatureTaskRuntimeFinishedRequest.reconcileBlockedRuntimeFields(): FeatureTaskRuntimeFinishedRequest {
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

private fun Map<String, String>.firstIncompletePhase(): String =
  entries.firstOrNull { it.value != "completed" }?.key?.takeIf(String::isNotBlank) ?: "unknown"
