package skillbill.application

import skillbill.application.model.TelemetryMutationResult
import skillbill.ports.telemetry.TelemetrySettingsProvider
import skillbill.review.model.FeedbackTelemetryOptions
import skillbill.telemetry.model.TelemetrySettings
import skillbill.telemetry.telemetrySyncTarget

internal fun loadTelemetrySettings(settingsProvider: TelemetrySettingsProvider): TelemetrySettings =
  settingsProvider.load()

internal fun telemetrySettingsOrNull(settingsProvider: TelemetrySettingsProvider): TelemetrySettings? =
  settingsProvider.loadOrNull()

internal fun feedbackTelemetryOptions(settingsProvider: TelemetrySettingsProvider): FeedbackTelemetryOptions {
  val settings = telemetrySettingsOrNull(settingsProvider)
  return FeedbackTelemetryOptions(
    enabled = settings?.enabled ?: false,
    level = settings?.level ?: "off",
  )
}

internal fun telemetryMutationResult(settings: TelemetrySettings, clearedEvents: Int): TelemetryMutationResult =
  TelemetryMutationResult(
    configPath = settings.configPath.toString(),
    telemetryEnabled = settings.enabled,
    telemetryLevel = settings.level,
    syncTarget = telemetrySyncTarget(settings),
    remoteConfigured = settings.proxyUrl.isNotBlank(),
    proxyConfigured = settings.customProxyUrl != null,
    proxyUrl = settings.proxyUrl,
    customProxyUrl = settings.customProxyUrl,
    installId = settings.installId,
    clearedEvents = clearedEvents,
  )

internal fun mapWorkflow(workflow: String): String = when (workflow) {
  "verify" -> "bill-feature-verify"
  "implement" -> "bill-feature-task"
  "feature-task-runtime" -> "feature-task-runtime"
  else -> throw IllegalArgumentException("workflow must be one of: verify, implement, feature-task-runtime.")
}
