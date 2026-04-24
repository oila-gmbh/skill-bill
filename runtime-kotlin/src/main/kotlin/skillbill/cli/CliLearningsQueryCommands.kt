package skillbill.cli

import skillbill.cli.models.LearningsListArgs
import skillbill.cli.models.LearningsResolveArgs
import skillbill.cli.models.LearningsShowArgs
import skillbill.db.DatabaseRuntime
import skillbill.learnings.LearningScope
import skillbill.learnings.LearningStore
import skillbill.learnings.LearningsRuntime
import skillbill.learnings.learningPayload

internal val learningsQueryCliCommands: List<CliCommandNode> =
  listOf(
    leafCommand(name = "list", parse = ::parseLearningsListArgs, execute = ::learningsListCommand),
    leafCommand(name = "show", parse = ::parseLearningsShowArgs, execute = ::learningsShowCommand),
    leafCommand(name = "resolve", parse = ::parseLearningsResolveArgs, execute = ::learningsResolveCommand),
  )

private fun parseLearningsListArgs(cursor: ArgumentCursor): LearningsListArgs {
  var status = "all"
  var format = CliFormat.TEXT
  while (cursor.hasNext()) {
    when (val token = cursor.take()) {
      "--status" -> status = cursor.requireValue(token)
      "--format" -> format = requireFormat(cursor.requireValue(token))
      else -> throw IllegalArgumentException("Unknown option '$token' for learnings list.")
    }
  }
  return LearningsListArgs(status, format)
}

private fun learningsListCommand(
  args: LearningsListArgs,
  context: CliRuntimeContext,
  dbOverride: String?,
): CliExecutionResult {
  DatabaseRuntime.openDb(dbOverride, context.environment, context.userHome).use { openDb ->
    val entries = LearningStore.listLearnings(openDb.connection, args.status).map(::learningPayload)
    return if (args.format == CliFormat.JSON) {
      payloadResult(
        linkedMapOf("db_path" to openDb.dbPath.toString(), "learnings" to entries),
        args.format,
      )
    } else {
      CliExecutionResult(exitCode = 0, stdout = CliOutput.learnings(entries))
    }
  }
}

private fun parseLearningsShowArgs(cursor: ArgumentCursor): LearningsShowArgs {
  var id: Int? = null
  var format = CliFormat.TEXT
  while (cursor.hasNext()) {
    when (val token = cursor.take()) {
      "--id" -> id = cursor.requireValue(token).toInt()
      "--format" -> format = requireFormat(cursor.requireValue(token))
      else -> throw IllegalArgumentException("Unknown option '$token' for learnings show.")
    }
  }
  require(id != null) { "--id is required." }
  return LearningsShowArgs(id, format)
}

private fun learningsShowCommand(
  args: LearningsShowArgs,
  context: CliRuntimeContext,
  dbOverride: String?,
): CliExecutionResult {
  DatabaseRuntime.openDb(dbOverride, context.environment, context.userHome).use { openDb ->
    val record = LearningStore.getLearning(openDb.connection, args.id)
    return learningRecordResult(openDb.dbPath.toString(), record, args.format)
  }
}

private fun parseLearningsResolveArgs(cursor: ArgumentCursor): LearningsResolveArgs {
  var repo: String? = null
  var skill: String? = null
  var reviewSessionId: String? = null
  var format = CliFormat.TEXT
  while (cursor.hasNext()) {
    when (val token = cursor.take()) {
      "--repo" -> repo = cursor.requireValue(token)
      "--skill" -> skill = cursor.requireValue(token)
      "--review-session-id" -> reviewSessionId = cursor.requireValue(token)
      "--format" -> format = requireFormat(cursor.requireValue(token))
      else -> throw IllegalArgumentException("Unknown option '$token' for learnings resolve.")
    }
  }
  return LearningsResolveArgs(repo, skill, reviewSessionId, format)
}

private fun learningsResolveCommand(
  args: LearningsResolveArgs,
  context: CliRuntimeContext,
  dbOverride: String?,
): CliExecutionResult {
  DatabaseRuntime.openDb(dbOverride, context.environment, context.userHome).use { openDb ->
    val (repoScopeKey, skillName, rows) =
      LearningsRuntime.resolveLearnings(openDb.connection, args.repo, args.skill)
    val payloadEntries = rows.map(::learningPayload)
    args.reviewSessionId?.takeIf(String::isNotBlank)?.let {
      LearningsRuntime.saveSessionLearnings(
        openDb.connection,
        it,
        learningsSessionJson(skillName, payloadEntries),
      )
    }
    val payload =
      learningsResolvePayload(
        openDb.dbPath.toString(),
        repoScopeKey,
        skillName,
        args.reviewSessionId,
        payloadEntries,
      )
    return if (args.format == CliFormat.JSON) {
      payloadResult(payload, args.format)
    } else {
      CliExecutionResult(
        exitCode = 0,
        stdout = CliOutput.resolvedLearnings(repoScopeKey, skillName, LearningScope.precedence, payloadEntries),
        payload = payload,
      )
    }
  }
}
