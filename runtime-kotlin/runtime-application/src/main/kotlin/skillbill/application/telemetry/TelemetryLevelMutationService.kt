package skillbill.application.telemetry

import me.tatarka.inject.annotations.Inject
import skillbill.ports.persistence.DatabaseSessionFactory
import skillbill.ports.telemetry.TelemetryConfigStore
import skillbill.ports.telemetry.TelemetryLevelMutator
import skillbill.ports.telemetry.TelemetrySettingsProvider
import skillbill.ports.telemetry.model.TelemetryLevelMutationResult
import skillbill.telemetry.config.TelemetryConfigMutations
import skillbill.telemetry.config.clearsPendingOutbox

@Inject
class TelemetryLevelMutationService(
  private val database: DatabaseSessionFactory,
  private val settingsProvider: TelemetrySettingsProvider,
  private val configStore: TelemetryConfigStore,
) : TelemetryLevelMutator {
  override fun setLevel(level: String, dbOverride: String?): TelemetryLevelMutationResult {
    val currentLevel = settingsProvider.load(materialize = false).level
    val (settings, clearedEvents) =
      if (clearsPendingOutbox(currentLevel, level) && database.databaseExists(dbOverride)) {
        database.transaction(dbOverride) { unitOfWork ->
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
