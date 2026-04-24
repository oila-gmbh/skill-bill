package skillbill.cli

import skillbill.cli.models.LearningDeleteArgs
import skillbill.cli.models.LearningStatusArgs
import skillbill.cli.models.LearningsAddArgs
import skillbill.cli.models.LearningsEditArgs
import skillbill.db.DatabaseRuntime
import skillbill.learnings.CreateLearningRequest
import skillbill.learnings.LearningScope
import skillbill.learnings.LearningStore
import skillbill.learnings.UpdateLearningRequest

internal val learningsMutationCliCommands: List<CliCommandNode> =
  listOf(
    leafCommand(name = "add", parse = ::parseLearningsAddArgs, execute = ::learningsAddCommand),
    leafCommand(name = "edit", parse = ::parseLearningsEditArgs, execute = ::learningsEditCommand),
    leafCommand(name = "disable", parse = ::parseLearningStatusArgs) { args, context, dbOverride ->
      learningsStatusCommand(args, context, dbOverride, "disabled")
    },
    leafCommand(name = "enable", parse = ::parseLearningStatusArgs) { args, context, dbOverride ->
      learningsStatusCommand(args, context, dbOverride, "active")
    },
    leafCommand(name = "delete", parse = ::parseLearningDeleteArgs, execute = ::learningsDeleteCommand),
  )

private fun parseLearningsAddArgs(cursor: ArgumentCursor): LearningsAddArgs {
  var scope = LearningScope.GLOBAL
  var scopeKey = ""
  var title: String? = null
  var rule = ""
  var reason = ""
  var fromRun: String? = null
  var fromFinding: String? = null
  var format = CliFormat.TEXT
  while (cursor.hasNext()) {
    when (val token = cursor.take()) {
      "--scope" -> scope = LearningScope.fromWireName(cursor.requireValue(token))
      "--scope-key" -> scopeKey = cursor.requireValue(token)
      "--title" -> title = cursor.requireValue(token)
      "--rule" -> rule = cursor.requireValue(token)
      "--reason" -> reason = cursor.requireValue(token)
      "--from-run" -> fromRun = cursor.requireValue(token)
      "--from-finding" -> fromFinding = cursor.requireValue(token)
      "--format" -> format = requireFormat(cursor.requireValue(token))
      else -> throw IllegalArgumentException("Unknown option '$token' for learnings add.")
    }
  }
  require(!title.isNullOrBlank()) { "--title is required." }
  require(rule.isNotBlank()) { "--rule is required." }
  require(!fromRun.isNullOrBlank()) { "--from-run is required." }
  require(!fromFinding.isNullOrBlank()) { "--from-finding is required." }
  return LearningsAddArgs(scope, scopeKey, title, rule, reason, fromRun, fromFinding, format)
}

private fun learningsAddCommand(
  args: LearningsAddArgs,
  context: CliRuntimeContext,
  dbOverride: String?,
): CliExecutionResult {
  DatabaseRuntime.openDb(dbOverride, context.environment, context.userHome).use { openDb ->
    val learningId =
      LearningStore.addLearning(
        openDb.connection,
        CreateLearningRequest(
          args.scope,
          args.scopeKey,
          args.title,
          args.rule,
          args.reason,
          args.fromRun,
          args.fromFinding,
        ),
      )
    val record = LearningStore.getLearning(openDb.connection, learningId)
    return learningRecordResult(openDb.dbPath.toString(), record, args.format)
  }
}

private fun parseLearningsEditArgs(cursor: ArgumentCursor): LearningsEditArgs {
  var id: Int? = null
  var scope: LearningScope? = null
  var scopeKey: String? = null
  var title: String? = null
  var rule: String? = null
  var reason: String? = null
  var format = CliFormat.TEXT
  while (cursor.hasNext()) {
    when (val token = cursor.take()) {
      "--id" -> id = cursor.requireValue(token).toInt()
      "--scope" -> scope = LearningScope.fromWireName(cursor.requireValue(token))
      "--scope-key" -> scopeKey = cursor.requireValue(token)
      "--title" -> title = cursor.requireValue(token)
      "--rule" -> rule = cursor.requireValue(token)
      "--reason" -> reason = cursor.requireValue(token)
      "--format" -> format = requireFormat(cursor.requireValue(token))
      else -> throw IllegalArgumentException("Unknown option '$token' for learnings edit.")
    }
  }
  require(id != null) { "--id is required." }
  require(listOf(scope, scopeKey, title, rule, reason).any { it != null }) {
    "Learning edit requires at least one field to update."
  }
  return LearningsEditArgs(id, scope, scopeKey, title, rule, reason, format)
}

private fun learningsEditCommand(
  args: LearningsEditArgs,
  context: CliRuntimeContext,
  dbOverride: String?,
): CliExecutionResult {
  DatabaseRuntime.openDb(dbOverride, context.environment, context.userHome).use { openDb ->
    val record =
      LearningStore.editLearning(
        openDb.connection,
        UpdateLearningRequest(args.id, args.scope, args.scopeKey, args.title, args.rule, args.reason),
      )
    return learningRecordResult(openDb.dbPath.toString(), record, args.format)
  }
}

private fun parseLearningStatusArgs(cursor: ArgumentCursor): LearningStatusArgs {
  var id: Int? = null
  var format = CliFormat.TEXT
  while (cursor.hasNext()) {
    when (val token = cursor.take()) {
      "--id" -> id = cursor.requireValue(token).toInt()
      "--format" -> format = requireFormat(cursor.requireValue(token))
      else -> throw IllegalArgumentException("Unknown option '$token' for learnings status.")
    }
  }
  require(id != null) { "--id is required." }
  return LearningStatusArgs(id, format)
}

private fun learningsStatusCommand(
  args: LearningStatusArgs,
  context: CliRuntimeContext,
  dbOverride: String?,
  status: String,
): CliExecutionResult {
  DatabaseRuntime.openDb(dbOverride, context.environment, context.userHome).use { openDb ->
    val record = LearningStore.setLearningStatus(openDb.connection, args.id, status)
    return learningRecordResult(openDb.dbPath.toString(), record, args.format)
  }
}

private fun parseLearningDeleteArgs(cursor: ArgumentCursor): LearningDeleteArgs {
  var id: Int? = null
  var format = CliFormat.TEXT
  while (cursor.hasNext()) {
    when (val token = cursor.take()) {
      "--id" -> id = cursor.requireValue(token).toInt()
      "--format" -> format = requireFormat(cursor.requireValue(token))
      else -> throw IllegalArgumentException("Unknown option '$token' for learnings delete.")
    }
  }
  require(id != null) { "--id is required." }
  return LearningDeleteArgs(id, format)
}

private fun learningsDeleteCommand(
  args: LearningDeleteArgs,
  context: CliRuntimeContext,
  dbOverride: String?,
): CliExecutionResult {
  DatabaseRuntime.openDb(dbOverride, context.environment, context.userHome).use { openDb ->
    LearningStore.deleteLearning(openDb.connection, args.id)
    return payloadResult(
      linkedMapOf("db_path" to openDb.dbPath.toString(), "deleted_learning_id" to args.id),
      args.format,
    )
  }
}
