package skillbill.workflow

import skillbill.error.InvalidDecompositionManifestSchemaError
import skillbill.workflow.model.CurrentSubtaskIntent
import skillbill.workflow.model.DecompositionDependency
import skillbill.workflow.model.DecompositionExecutionModel
import skillbill.workflow.model.DecompositionManifest
import skillbill.workflow.model.DecompositionStackBranch
import skillbill.workflow.model.DecompositionSubtask
import skillbill.workflow.model.SpecSource
import java.math.BigDecimal
import java.math.BigInteger

object DecompositionManifestCodec {
  fun decodeMap(wireMap: Map<String, Any?>, sourceLabel: String = "<in-memory>"): DecompositionManifest {
    return wireMap.toDecompositionManifest(sourceLabel)
  }
}

private fun Map<String, Any?>.toDecompositionManifest(sourceLabel: String): DecompositionManifest {
  val executionModelValue = stringValue("execution_model", sourceLabel)
  val executionModel =
    DecompositionExecutionModel.fromWireValue(executionModelValue)
      ?: invalidDecompositionManifest(sourceLabel, "execution_model '$executionModelValue' is not supported.")
  val specSource = when (val rawSpecSource = nullableStringValue("spec_source", sourceLabel)) {
    null -> SpecSource.LOCAL
    else -> SpecSource.fromWireValue(rawSpecSource)
      ?: invalidDecompositionManifest(sourceLabel, "spec_source '$rawSpecSource' is not supported.")
  }
  val subtasks = listValue("subtasks").mapIndexed { index, raw ->
    raw.asMap(sourceLabel, "subtasks[$index]").toDecompositionSubtask(sourceLabel, index)
  }
  val current = this["current_subtask_intent"].asMap(sourceLabel, "current_subtask_intent")
  return DecompositionManifest(
    contractVersion = stringValue("contract_version", sourceLabel),
    issueKey = stringValue("issue_key", sourceLabel),
    featureName = stringValue("feature_name", sourceLabel),
    parentSpecPath = stringValue("parent_spec_path", sourceLabel),
    specSource = specSource,
    status = nullableStringValue("status", sourceLabel) ?: "pending",
    executionModel = executionModel,
    baseBranch = stringValue("base_branch", sourceLabel),
    featureBranch = nullableStringValue("feature_branch", sourceLabel),
    stackBranches = listValue("stack_branches").mapIndexed { index, raw ->
      val item = raw.asMap(sourceLabel, "stack_branches[$index]")
      DecompositionStackBranch(
        subtaskId = item.intValue("subtask_id", sourceLabel),
        branch = item.stringValue("branch", sourceLabel),
        baseBranch = item.stringValue("base_branch", sourceLabel),
      )
    },
    currentSubtaskIntent = CurrentSubtaskIntent(
      subtaskId = current.intValue("subtask_id", sourceLabel),
      action = current.stringValue("action", sourceLabel),
    ),
    subtasks = subtasks,
  )
}

private fun Map<String, Any?>.toDecompositionSubtask(sourceLabel: String, index: Int): DecompositionSubtask {
  val participatingAgentIds = when (val value = this["participating_agent_ids"]) {
    null -> emptyList()
    is List<*> -> value.map { element ->
      val str = element as? String
        ?: invalidDecompositionManifest(sourceLabel, "participating_agent_ids must be a list of strings.")
      str.takeIf(String::isNotBlank)
        ?: invalidDecompositionManifest(sourceLabel, "participating_agent_ids must be a list of non-blank strings.")
    }
    else -> invalidDecompositionManifest(sourceLabel, "participating_agent_ids must be a list of strings or null.")
  }
  return DecompositionSubtask(
    id = intValue("id", sourceLabel),
    name = stringValue("name", sourceLabel),
    specPath = stringValue("spec_path", sourceLabel),
    status = stringValue("status", sourceLabel),
    branch = nullableStringValue("branch", sourceLabel),
    commitSha = nullableStringValue("commit_sha", sourceLabel),
    workflowId = nullableStringValue("workflow_id", sourceLabel),
    blockedReason = nullableStringValue("blocked_reason", sourceLabel),
    lastResumableStep = nullableStringValue("last_resumable_step", sourceLabel),
    linearIssueId = nullableStringValue("linear_issue_id", sourceLabel),
    finalizingAgentId = when (val raw = this["finalizing_agent_id"]) {
      null -> null
      is String -> raw.takeIf(String::isNotBlank)
        ?: invalidDecompositionManifest(sourceLabel, "finalizing_agent_id must be a non-blank string or null.")
      else -> invalidDecompositionManifest(sourceLabel, "finalizing_agent_id must be a string or null.")
    },
    participatingAgentIds = participatingAgentIds,
    dependencies = listValue("dependencies").mapIndexed { depIndex, dep ->
      val dependency = dep.asMap(sourceLabel, "subtasks[$index].dependencies[$depIndex]")
      DecompositionDependency(
        subtaskId = dependency.intValue("subtask_id", sourceLabel),
        optional = dependency.booleanValue("optional", sourceLabel),
        skipped = dependency.booleanValue("skipped", sourceLabel),
      )
    },
  )
}

private fun Map<String, Any?>.stringValue(key: String, sourceLabel: String): String = when (val value = this[key]) {
  is String -> value
  else -> invalidDecompositionManifest(sourceLabel, "$key must be a string.")
}

private fun Map<String, Any?>.nullableStringValue(key: String, sourceLabel: String): String? =
  when (val value = this[key]) {
    null -> null
    is String -> value
    else -> invalidDecompositionManifest(sourceLabel, "$key must be a string or null.")
  }

private fun Map<String, Any?>.intValue(key: String, sourceLabel: String): Int = this[key].asInt(sourceLabel, key)

private fun Map<String, Any?>.booleanValue(key: String, sourceLabel: String): Boolean = when (val value = this[key]) {
  is Boolean -> value
  else -> invalidDecompositionManifest(sourceLabel, "$key must be a boolean.")
}

private fun Map<String, Any?>.listValue(key: String): List<Any?> = (this[key] as? List<*>).orEmpty()

private fun Any?.asMap(sourceLabel: String, fieldPath: String): Map<String, Any?> =
  (this as? Map<*, *>)?.entries?.associateTo(LinkedHashMap<String, Any?>()) { (key, value) ->
    val stringKey = key as? String ?: invalidDecompositionManifest(sourceLabel, "$fieldPath contains a non-string key.")
    stringKey to value
  } ?: invalidDecompositionManifest(sourceLabel, "$fieldPath must be an object.")

private fun Any?.asInt(sourceLabel: String, fieldPath: String): Int = when (this) {
  is Byte -> toInt()
  is Short -> toInt()
  is Int -> this
  is Long -> try {
    Math.toIntExact(this)
  } catch (_: ArithmeticException) {
    null
  }
  is BigInteger -> try {
    intValueExact()
  } catch (_: ArithmeticException) {
    null
  }
  is BigDecimal -> try {
    toBigIntegerExact().intValueExact()
  } catch (_: ArithmeticException) {
    null
  }
  else -> null
} ?: invalidDecompositionManifest(sourceLabel, "$fieldPath must be an exact Kotlin Int.")

private fun invalidDecompositionManifest(sourceLabel: String, reason: String): Nothing =
  throw InvalidDecompositionManifestSchemaError(sourceLabel = sourceLabel, reason = reason)
