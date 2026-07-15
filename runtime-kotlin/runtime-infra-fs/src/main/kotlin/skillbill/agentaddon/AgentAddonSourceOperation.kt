package skillbill.agentaddon

import skillbill.error.InvalidAgentAddonSchemaError

internal inline fun <T> sourceOperation(sourceLabel: String, fallbackReason: String, operation: () -> T): T =
  runCatching(operation).getOrElse { error ->
    throw error.asSourceSchemaError(sourceLabel, fallbackReason)
  }

private fun Throwable.asSourceSchemaError(sourceLabel: String, fallbackReason: String): Throwable = when (this) {
  is InvalidAgentAddonSchemaError -> this
  is Exception -> InvalidAgentAddonSchemaError(sourceLabel, message ?: fallbackReason, this)
  else -> this
}

internal fun invalid(sourceLabel: String, reason: String): Nothing =
  throw InvalidAgentAddonSchemaError(sourceLabel, reason)

internal fun Map<String, Any?>.string(key: String, sourceLabel: String): String =
  this[key] as? String ?: invalid(sourceLabel, "$key must be a string")

internal fun Map<String, Any?>.stringList(key: String, sourceLabel: String): List<String> =
  (this[key] as? List<*>)?.map { it as? String ?: invalid(sourceLabel, "$key entries must be strings") }
    ?: invalid(sourceLabel, "$key must be an array")
