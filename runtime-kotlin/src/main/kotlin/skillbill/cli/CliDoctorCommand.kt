package skillbill.cli

import skillbill.SkillBillVersion
import skillbill.db.DatabaseRuntime
import java.nio.file.Files

internal val doctorCliCommand: CliCommandNode =
  leafCommand(
    name = "doctor",
    parse = { cursor -> parseFormatOnlyArgs(cursor, "doctor") },
    execute = ::doctorCommand,
  )

private fun doctorCommand(args: FormatOnlyArgs, context: CliRuntimeContext, dbOverride: String?): CliExecutionResult {
  val dbPath = DatabaseRuntime.resolveDbPath(dbOverride, context.environment, context.userHome)
  val settings = telemetrySettingsOrNull(context)
  return payloadResult(
    linkedMapOf(
      "version" to SkillBillVersion.VALUE,
      "db_path" to dbPath.toString(),
      "db_exists" to Files.exists(dbPath),
      "telemetry_enabled" to (settings?.enabled ?: false),
      "telemetry_level" to (settings?.level ?: "off"),
    ),
    args.format,
  )
}
