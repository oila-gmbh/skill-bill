package skillbill.application

import me.tatarka.inject.annotations.Inject
import skillbill.application.model.FeatureImplementFinishedRequest
import skillbill.application.model.FeatureImplementStartedRequest
import skillbill.application.model.FeatureVerifyFinishedRequest
import skillbill.application.model.FeatureVerifyStartedRequest
import skillbill.application.model.PrDescriptionGeneratedRequest
import skillbill.application.model.QualityCheckFinishedRequest
import skillbill.application.model.QualityCheckStartedRequest
import skillbill.boundary.OpenBoundaryMap
import skillbill.ports.persistence.DatabaseSessionFactory
import skillbill.ports.telemetry.TelemetrySettingsProvider
import skillbill.telemetry.model.TelemetrySettings

@Inject
class LifecycleTelemetryService(
  private val database: DatabaseSessionFactory,
  private val settingsProvider: TelemetrySettingsProvider,
) {
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
        request.orchestrated -> request.orchestratedPayload(telemetryLevelOrAnonymous())
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
        request.orchestrated -> request.orchestratedPayload(telemetryLevelOrAnonymous())
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
      request.orchestrated -> request.orchestratedPayload(telemetryLevelOrAnonymous())
      else ->
        enabledStandaloneResult(sessionId) { settings ->
          database.transaction(null) { unitOfWork ->
            unitOfWork.lifecycleTelemetry.prDescriptionGenerated(request.toRecord(sessionId), settings.level)
          }
        }
    }
  }

  private fun enabledStandaloneResult(sessionId: String, action: (TelemetrySettings) -> Unit): Map<String, Any?> {
    val settings = telemetrySettingsOrNull(settingsProvider)
    return if (settings?.enabled == true) {
      action(settings)
      lifecycleOkPayload(sessionId)
    } else {
      lifecycleSkippedPayload(sessionId)
    }
  }

  private fun telemetryLevelOrAnonymous(): String = telemetrySettingsOrNull(settingsProvider)?.level ?: "anonymous"
}
