package skillbill.application.system

import me.tatarka.inject.annotations.Inject
import skillbill.SkillBillVersion
import skillbill.application.telemetry.telemetrySettingsOrNull
import skillbill.contracts.system.DoctorContract
import skillbill.contracts.system.VersionContract
import skillbill.ports.persistence.DatabaseSessionFactory
import skillbill.ports.telemetry.TelemetrySettingsProvider

@Inject
class SystemService(
  private val database: DatabaseSessionFactory,
  private val settingsProvider: TelemetrySettingsProvider,
  // Defaults to the build-stamped version; injectable so version-dependent logic
  // (e.g. update-check) can be unit-tested against a fixed version. kotlin-inject
  // uses this default because the graph binds no String.
  private val versionValue: String = SkillBillVersion.VALUE,
) {
  fun version(): VersionContract = VersionContract(version = versionValue)

  fun doctor(dbOverride: String?): DoctorContract {
    val dbPath = database.resolveDbPath(dbOverride)
    val settings = telemetrySettingsOrNull(settingsProvider)
    return DoctorContract(
      version = versionValue,
      dbPath = dbPath.toString(),
      dbExists = database.databaseExists(dbOverride),
      telemetryEnabled = settings?.enabled ?: false,
      telemetryLevel = settings?.level ?: "off",
    )
  }
}
