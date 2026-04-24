package skillbill.app

import skillbill.RuntimeContext
import skillbill.review.FeedbackTelemetryOptions
import skillbill.telemetry.TelemetryConfigRuntime
import skillbill.telemetry.TelemetrySettings

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
