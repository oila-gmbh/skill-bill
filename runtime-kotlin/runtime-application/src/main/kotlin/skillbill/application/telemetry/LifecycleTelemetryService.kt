package skillbill.application.telemetry

import me.tatarka.inject.annotations.Inject
import skillbill.application.telemetry.model.FeatureTaskRuntimeFinishedRequest
import skillbill.application.telemetry.model.FeatureTaskRuntimeStartedRequest
import skillbill.application.telemetry.model.FeatureVerifyFinishedRequest
import skillbill.application.telemetry.model.FeatureVerifyStartedRequest
import skillbill.application.telemetry.model.PrDescriptionGeneratedRequest
import skillbill.application.telemetry.model.QualityCheckFinishedRequest
import skillbill.application.telemetry.model.QualityCheckStartedRequest
import skillbill.boundary.OpenBoundaryMap
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.telemetry.TelemetrySettingsProvider

@Inject
class LifecycleTelemetryService(
  private val database: DatabaseSessionFactory,
  private val settingsProvider: TelemetrySettingsProvider,
) : GoalLifecycleTelemetryEmitter by LifecycleTelemetryGoalEmission(database, settingsProvider) {
  @OpenBoundaryMap("Lifecycle telemetry event bag emitted to the MCP/CLI telemetry boundary")
  fun featureTaskRuntimeStarted(
    request: FeatureTaskRuntimeStartedRequest,
    dbOverride: String? = null,
  ): Map<String, Any?> {
    val sessionId = request.sessionId.ifBlank { generateLifecycleSessionId("ftr") }
    return enabledStandaloneResult(settingsProvider, sessionId) { settings ->
      database.transaction(dbOverride) { unitOfWork ->
        unitOfWork.lifecycleTelemetry.featureTaskRuntimeStarted(request.toRecord(sessionId), settings.level)
      }
    }
  }

  @OpenBoundaryMap("Lifecycle telemetry event bag emitted to the MCP/CLI telemetry boundary")
  fun featureTaskRuntimeFinished(
    request: FeatureTaskRuntimeFinishedRequest,
    dbOverride: String? = null,
  ): Map<String, Any?> = enabledStandaloneResult(settingsProvider, request.sessionId) { settings ->
    val reconciledRequest = request.reconcileBlockedRuntimeFields()
    database.transaction(dbOverride) { unitOfWork ->
      unitOfWork.lifecycleTelemetry.featureTaskRuntimeFinished(reconciledRequest.toRecord(), settings.level)
    }
  }

  @OpenBoundaryMap("Lifecycle telemetry event bag emitted to the MCP/CLI telemetry boundary")
  fun qualityCheckStarted(request: QualityCheckStartedRequest): Map<String, Any?> {
    val sessionId = generateLifecycleSessionId("qck")
    val normalizedRequest = request.normalizedLabels()
    return when {
      normalizedRequest.orchestrated -> orchestratedStartedSkippedPayload()
      else ->
        validateQualityCheckStarted(normalizedRequest)
          ?.let { lifecycleErrorPayload(sessionId, it) }
          ?: enabledStandaloneResult(settingsProvider, sessionId) { settings ->
            database.transaction(null) { unitOfWork ->
              unitOfWork.lifecycleTelemetry.qualityCheckStarted(
                normalizedRequest.toRecord(sessionId),
                settings.level,
              )
            }
          }
    }
  }

  @OpenBoundaryMap("Lifecycle telemetry event bag emitted to the MCP/CLI telemetry boundary")
  fun qualityCheckFinished(request: QualityCheckFinishedRequest): Map<String, Any?> {
    val normalizedRequest = request.normalizedLabels()
    return validateQualityCheckFinished(normalizedRequest)
      ?.let { lifecycleErrorPayload(normalizedRequest.sessionId, it) }
      ?: when {
        normalizedRequest.orchestrated ->
          normalizedRequest.orchestratedPayload(telemetryLevelOrAnonymous(settingsProvider))
        else ->
          enabledStandaloneResult(settingsProvider, normalizedRequest.sessionId) { settings ->
            database.transaction(null) { unitOfWork ->
              unitOfWork.lifecycleTelemetry.qualityCheckFinished(
                normalizedRequest.toRecord(),
                settings.level,
              )
            }
          }
      }
  }

  @OpenBoundaryMap("Lifecycle telemetry event bag emitted to the MCP/CLI telemetry boundary")
  fun featureVerifyStarted(request: FeatureVerifyStartedRequest): Map<String, Any?> {
    val sessionId = generateLifecycleSessionId("fvr")
    return when {
      request.orchestrated -> orchestratedStartedSkippedPayload()
      else ->
        enabledStandaloneResult(settingsProvider, sessionId) { settings ->
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
          enabledStandaloneResult(settingsProvider, request.sessionId) { settings ->
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
        enabledStandaloneResult(settingsProvider, sessionId) { settings ->
          database.transaction(null) { unitOfWork ->
            unitOfWork.lifecycleTelemetry.prDescriptionGenerated(request.toRecord(sessionId), settings.level)
          }
        }
    }
  }
}

internal fun telemetryLevelOrAnonymous(settingsProvider: TelemetrySettingsProvider): String =
  telemetrySettingsOrNull(settingsProvider)?.level ?: "anonymous"
