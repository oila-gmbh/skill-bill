package skillbill.application

import me.tatarka.inject.annotations.Inject
import skillbill.RuntimeContext
import skillbill.SkillBillVersion
import skillbill.db.DatabaseRuntime
import java.nio.file.Files

@Inject
class SystemService(private val context: RuntimeContext) {
  fun version(): Map<String, Any?> = linkedMapOf("version" to SkillBillVersion.VALUE)

  fun doctor(dbOverride: String?): Map<String, Any?> {
    val dbPath = DatabaseRuntime.resolveDbPath(dbOverride, context.environment, context.userHome)
    val settings = telemetrySettingsOrNull(context)
    return linkedMapOf(
      "version" to SkillBillVersion.VALUE,
      "db_path" to dbPath.toString(),
      "db_exists" to Files.exists(dbPath),
      "telemetry_enabled" to (settings?.enabled ?: false),
      "telemetry_level" to (settings?.level ?: "off"),
    )
  }
}
