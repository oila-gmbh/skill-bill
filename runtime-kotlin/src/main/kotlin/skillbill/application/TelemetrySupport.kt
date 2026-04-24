package skillbill.application

import skillbill.RuntimeContext
import skillbill.review.FeedbackTelemetryOptions
import skillbill.telemetry.TelemetryConfigRuntime
import skillbill.telemetry.TelemetrySettings
import skillbill.telemetry.telemetrySyncTarget

internal fun loadTelemetrySettings(context: RuntimeContext): TelemetrySettings =
  TelemetryConfigRuntime.loadTelemetrySettings(
    environment = context.environment,
    userHome = context.userHome,
  )

internal fun telemetrySettingsOrNull(context: RuntimeContext): TelemetrySettings? =
  runCatching { loadTelemetrySettings(context) }.getOrNull()

internal fun feedbackTelemetryOptions(context: RuntimeContext): FeedbackTelemetryOptions {
  val settings = telemetrySettingsOrNull(context)
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
