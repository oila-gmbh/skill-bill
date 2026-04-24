package skillbill.application

import me.tatarka.inject.annotations.Inject
import skillbill.RuntimeContext
import skillbill.SkillBillVersion
import skillbill.ports.persistence.DatabaseSessionFactory

@Inject
class SystemService(
  private val context: RuntimeContext,
  private val database: DatabaseSessionFactory,
) {
  fun version(): Map<String, Any?> = linkedMapOf("version" to SkillBillVersion.VALUE)

  fun doctor(dbOverride: String?): Map<String, Any?> {
    val dbPath = database.resolveDbPath(dbOverride)
    val settings = telemetrySettingsOrNull(context)
    return linkedMapOf(
      "version" to SkillBillVersion.VALUE,
      "db_path" to dbPath.toString(),
      "db_exists" to database.databaseExists(dbOverride),
      "telemetry_enabled" to (settings?.enabled ?: false),
      "telemetry_level" to (settings?.level ?: "off"),
    )
  }
}
