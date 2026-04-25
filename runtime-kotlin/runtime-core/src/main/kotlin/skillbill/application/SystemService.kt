package skillbill.application

import me.tatarka.inject.annotations.Inject
import skillbill.SkillBillVersion
import skillbill.contracts.system.DoctorContract
import skillbill.contracts.system.VersionContract
import skillbill.ports.persistence.DatabaseSessionFactory
import skillbill.ports.telemetry.TelemetrySettingsProvider

@Inject
class SystemService(
  private val database: DatabaseSessionFactory,
  private val settingsProvider: TelemetrySettingsProvider,
) {
  fun version(): Map<String, Any?> = VersionContract(version = SkillBillVersion.VALUE).toPayload()

  fun doctor(dbOverride: String?): Map<String, Any?> {
    val dbPath = database.resolveDbPath(dbOverride)
    val settings = telemetrySettingsOrNull(settingsProvider)
    return DoctorContract(
      version = SkillBillVersion.VALUE,
      dbPath = dbPath.toString(),
      dbExists = database.databaseExists(dbOverride),
      telemetryEnabled = settings?.enabled ?: false,
      telemetryLevel = settings?.level ?: "off",
    ).toPayload()
  }
}
