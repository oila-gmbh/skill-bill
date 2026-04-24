package skillbill.cli

private val versionCliCommand =
  leafCommand(
    name = "version",
    parse = { cursor -> parseFormatOnlyArgs(cursor, "version") },
  ) { args, _, _ ->
    payloadResult(linkedMapOf("version" to skillbill.SkillBillVersion.VALUE), args.format)
  }

private val rootCliCommand =
  commandGroup(
    name = "phase-4",
    children =
    buildList {
      addAll(reviewCliCommands)
      add(learningsCliCommand)
      add(telemetryCliCommand)
      add(versionCliCommand)
      add(doctorCliCommand)
    },
    unknownCommandMessage = { token -> "Unsupported Phase 4 CLI command '$token'." },
  )

object CliRuntime {
  fun run(arguments: List<String>, context: CliRuntimeContext = CliRuntimeContext()): CliExecutionResult {
    val cursor = ArgumentCursor(arguments)
    val dbOverride = parseGlobalDbOverride(cursor, context.dbPathOverride)
    val command = cursor.take()
    return rootCliCommand.dispatch(command, CliInvocation(cursor, context, dbOverride))
  }
}

internal fun parseGlobalDbOverride(cursor: ArgumentCursor, fallback: String?): String? {
  var dbOverride = fallback
  while (cursor.hasNext() && cursor.peek() == "--db") {
    cursor.take()
    dbOverride = cursor.requireValue("--db")
  }
  return dbOverride
}
