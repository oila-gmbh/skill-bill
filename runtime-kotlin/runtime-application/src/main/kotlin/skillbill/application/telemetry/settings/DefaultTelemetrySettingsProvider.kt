package skillbill.application.telemetry.settings

import me.tatarka.inject.annotations.Inject
import skillbill.application.telemetry.config.loadTelemetrySettingsFromStore
import skillbill.model.EnvironmentContext
import skillbill.ports.telemetry.TelemetryConfigStore
import skillbill.ports.telemetry.TelemetrySettingsProvider
import skillbill.telemetry.model.TelemetrySettings

@Inject
class DefaultTelemetrySettingsProvider(
  private val context: EnvironmentContext,
  private val configStore: TelemetryConfigStore,
) : TelemetrySettingsProvider {
  override fun load(materialize: Boolean): TelemetrySettings = loadTelemetrySettingsFromStore(
    materialize = materialize,
    environment = context.environment,
    configStore = configStore,
  )
}
