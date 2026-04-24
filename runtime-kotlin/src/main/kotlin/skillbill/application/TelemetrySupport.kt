package skillbill.application

import skillbill.ports.telemetry.TelemetrySettingsProvider
import skillbill.review.FeedbackTelemetryOptions
import skillbill.telemetry.TelemetrySettings
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

internal fun telemetryMutationPayload(settings: TelemetrySettings, clearedEvents: Int): Map<String, Any?> = linkedMapOf(
  "config_path" to settings.configPath.toString(),
  "telemetry_enabled" to settings.enabled,
  "telemetry_level" to settings.level,
  "sync_target" to telemetrySyncTarget(settings),
  "remote_configured" to settings.proxyUrl.isNotBlank(),
  "proxy_configured" to (settings.customProxyUrl != null),
  "proxy_url" to settings.proxyUrl,
  "custom_proxy_url" to settings.customProxyUrl,
  "install_id" to settings.installId,
  "cleared_events" to clearedEvents,
)

internal fun mapWorkflow(workflow: String): String = when (workflow) {
  "verify" -> "bill-feature-verify"
  "implement" -> "bill-feature-implement"
  else -> throw IllegalArgumentException("workflow must be one of: verify, implement.")
}
