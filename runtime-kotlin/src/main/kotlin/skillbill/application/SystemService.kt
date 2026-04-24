package skillbill.application

import me.tatarka.inject.annotations.Inject
import skillbill.SkillBillVersion
import skillbill.ports.persistence.DatabaseSessionFactory
import skillbill.ports.telemetry.TelemetrySettingsProvider

@Inject
class SystemService(
  private val database: DatabaseSessionFactory,
  private val settingsProvider: TelemetrySettingsProvider,
) {
  fun version(): Map<String, Any?> = linkedMapOf("version" to SkillBillVersion.VALUE)

  fun doctor(dbOverride: String?): Map<String, Any?> {
    val dbPath = database.resolveDbPath(dbOverride)
    val settings = telemetrySettingsOrNull(settingsProvider)
    return linkedMapOf(
      "version" to SkillBillVersion.VALUE,
      "db_path" to dbPath.toString(),
      "db_exists" to database.databaseExists(dbOverride),
      "telemetry_enabled" to (settings?.enabled ?: false),
      "telemetry_level" to (settings?.level ?: "off"),
    )
  }
}
