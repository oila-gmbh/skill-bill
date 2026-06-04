package skillbill.workflow.taskruntime.model

import skillbill.error.InvalidWorkflowStateSchemaError

internal fun Map<String, Any?>.requireSubtasks(): List<FeatureTaskRuntimeDecomposeSubtask> {
  val rawSubtasks = this["subtasks"] as? List<*>
    ?: planOutcomeSchemaError("Decompose plan produced_outputs.subtasks must be a list.")
  return rawSubtasks.mapIndexed { index, raw ->
    val map = raw as? Map<*, *>
      ?: planOutcomeSchemaError("Decompose plan produced_outputs.subtasks[$index] must be an object.")
    val subtask = map.entries
      .filter { it.key is String }
      .associate { it.key as String to it.value }
    FeatureTaskRuntimeDecomposeSubtask(
      id = subtask.requireInt("id", index),
      name = subtask.requireString("name", index),
      scope = subtask.requireString("scope", index),
      acceptanceCriteria = subtask.requireStringList("acceptance_criteria", index),
      nonGoals = subtask.optionalStringList("non_goals", index),
      dependencyNotes = subtask.firstString("dependency_notes").ifBlank { "See decomposition manifest dependencies." },
      validationStrategy = subtask.requireString("validation_strategy", index),
      nextPath = subtask.requireString("next_path", index),
      dependsOn = subtask.optionalIntList("depends_on", index),
    )
  }
}

internal fun Map<String, Any?>.stringAnyMap(key: String): Map<String, Any?>? = (this[key] as? Map<*, *>)?.entries
  ?.filter { it.key is String }
  ?.associate { it.key as String to it.value }

internal fun Map<String, Any?>.firstString(vararg keys: String): String =
  keys.firstNotNullOfOrNull { key -> this[key]?.toString()?.trim()?.takeIf(String::isNotBlank) }.orEmpty()

private fun Map<String, Any?>.requireString(key: String, index: Int): String = firstString(key).ifBlank {
  planOutcomeSchemaError("Decompose plan subtask[$index].$key must be a non-blank string.")
}

private fun Map<String, Any?>.requireInt(key: String, index: Int): Int = this[key].asIntOrNull()
  ?: planOutcomeSchemaError("Decompose plan subtask[$index].$key must be an integer.")

private fun Map<String, Any?>.requireStringList(key: String, index: Int): List<String> =
  optionalStringList(key, index).takeIf(List<String>::isNotEmpty)
    ?: planOutcomeSchemaError("Decompose plan subtask[$index].$key must contain at least one string.")

private fun Map<String, Any?>.optionalStringList(key: String, index: Int): List<String> {
  val raw = this[key] ?: return emptyList()
  val list = raw as? List<*>
    ?: planOutcomeSchemaError("Decompose plan subtask[$index].$key must be a list.")
  return list.mapIndexed { itemIndex, value ->
    value?.toString()?.trim()?.takeIf(String::isNotBlank)
      ?: planOutcomeSchemaError("Decompose plan subtask[$index].$key[$itemIndex] must be a non-blank string.")
  }
}

private fun Map<String, Any?>.optionalIntList(key: String, index: Int): List<Int> {
  val raw = this[key] ?: return emptyList()
  val list = raw as? List<*>
    ?: planOutcomeSchemaError("Decompose plan subtask[$index].$key must be a list.")
  return list.mapIndexed { itemIndex, value ->
    value.asIntOrNull()
      ?: planOutcomeSchemaError("Decompose plan subtask[$index].$key[$itemIndex] must be an integer.")
  }
}

private fun Any?.asIntOrNull(): Int? = when (this) {
  is Int -> this
  is Long -> takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
  is Number -> toDouble().takeIf { it % 1.0 == 0.0 }?.toInt()
  is String -> toIntOrNull()
  else -> null
}

private fun planOutcomeSchemaError(detail: String): Nothing = throw InvalidWorkflowStateSchemaError(detail)
