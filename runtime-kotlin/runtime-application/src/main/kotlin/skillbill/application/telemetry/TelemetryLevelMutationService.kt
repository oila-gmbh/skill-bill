package skillbill.application.telemetry

import me.tatarka.inject.annotations.Inject
import skillbill.application.telemetry.config.TelemetryConfigMutations
import skillbill.application.telemetry.config.clearsPendingOutbox
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.telemetry.TelemetryConfigStore
import skillbill.ports.telemetry.TelemetryLevelMutator
import skillbill.ports.telemetry.TelemetrySettingsProvider
import skillbill.ports.telemetry.model.TelemetryLevelMutationResult

@Inject
class TelemetryLevelMutationService(
  private val database: DatabaseSessionFactory,
  private val settingsProvider: TelemetrySettingsProvider,
  private val configStore: TelemetryConfigStore,
) : TelemetryLevelMutator {
  override fun setLevel(level: String): TelemetryLevelMutationResult {
    val currentLevel = settingsProvider.load(materialize = false).level
    val (settings, clearedEvents) =
      if (clearsPendingOutbox(currentLevel, level) && database.databaseExists()) {
        database.transaction { unitOfWork ->
          TelemetryConfigMutations.setTelemetryLevel(
            level = level,
            configStore = configStore,
            settingsProvider = settingsProvider,
            outbox = unitOfWork.telemetryOutbox,
          )
        }
      } else {
        TelemetryConfigMutations.setTelemetryLevel(
          level = level,
          configStore = configStore,
          settingsProvider = settingsProvider,
        )
      }
    return TelemetryLevelMutationResult(settings, clearedEvents)
  }
}
