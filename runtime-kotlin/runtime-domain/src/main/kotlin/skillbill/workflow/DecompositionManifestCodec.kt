package skillbill.workflow

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import skillbill.error.InvalidDecompositionManifestSchemaError
import skillbill.workflow.model.CurrentSubtaskIntent
import skillbill.workflow.model.DecompositionDependency
import skillbill.workflow.model.DecompositionExecutionModel
import skillbill.workflow.model.DecompositionManifest
import skillbill.workflow.model.DecompositionStackBranch
import skillbill.workflow.model.DecompositionSubtask
import java.nio.file.Files
import java.nio.file.Path

object DecompositionManifestCodec {
  private val yamlMapper: YAMLMapper by lazy { YAMLMapper() }

  fun load(path: Path): DecompositionManifest = decodeYaml(Files.readString(path), path.toString())

  fun decodeMap(wireMap: Map<String, Any?>, sourceLabel: String = "<in-memory>"): DecompositionManifest {
    DecompositionManifestSchemaValidator.validate(wireMap, sourceLabel)
    val manifest = wireMap.toDecompositionManifest(sourceLabel)
    validateCoherence(manifest, sourceLabel)
    return manifest
  }

  fun validate(manifest: DecompositionManifest, sourceLabel: String = "<in-memory>") {
    DecompositionManifestSchemaValidator.validate(manifest.toWireMap(), sourceLabel)
    validateCoherence(manifest, sourceLabel)
  }

  fun encodeYaml(manifest: DecompositionManifest): String {
    validate(manifest)
    return yamlMapper.writeValueAsString(manifest.toWireMap())
  }

  fun decodeYaml(yamlText: String, sourceLabel: String = "<in-memory>"): DecompositionManifest {
    val parsed = DecompositionManifestSchemaValidator.validateYamlText(yamlText, sourceLabel)
    val manifest = parsed.toDecompositionManifest(sourceLabel)
    validateCoherence(manifest, sourceLabel)
    return manifest
  }

  private fun validateCoherence(manifest: DecompositionManifest, sourceLabel: String) {
    if (manifest.contractVersion != DECOMPOSITION_MANIFEST_CONTRACT_VERSION) {
      invalidDecompositionManifest(
        sourceLabel,
        "contract_version '${manifest.contractVersion}' must equal '$DECOMPOSITION_MANIFEST_CONTRACT_VERSION'.",
      )
    }
    val seenIds = mutableSetOf<Int>()
    val seenSpecPaths = mutableSetOf<String>()
    manifest.subtasks.forEachIndexed { index, subtask ->
      if (!seenIds.add(subtask.id)) {
        invalidDecompositionManifest(sourceLabel, "subtasks[$index].id '${subtask.id}' is duplicated.")
      }
      if (!seenSpecPaths.add(subtask.specPath)) {
        invalidDecompositionManifest(sourceLabel, "subtasks[$index].spec_path '${subtask.specPath}' is duplicated.")
      }
      subtask.dependencies.forEach { dependency ->
        if (dependency.subtaskId !in seenIds) {
          invalidDecompositionManifest(
            sourceLabel,
            "subtasks[$index].dependencies references '${dependency.subtaskId}', which is not a prior subtask id.",
          )
        }
      }
    }
    validateExecutionModel(manifest, sourceLabel)
    validateCurrentIntent(manifest, sourceLabel)
  }

  private fun validateExecutionModel(manifest: DecompositionManifest, sourceLabel: String) {
    when (manifest.executionModel) {
      DecompositionExecutionModel.SAME_BRANCH_COMMIT_PER_SUBTASK -> {
        if (manifest.featureBranch.isNullOrBlank()) {
          invalidDecompositionManifest(sourceLabel, "same_branch_commit_per_subtask requires feature_branch.")
        }
        if (manifest.stackBranches.isNotEmpty()) {
          invalidDecompositionManifest(
            sourceLabel,
            "same_branch_commit_per_subtask requires stack_branches to be empty.",
          )
        }
      }
      DecompositionExecutionModel.STACKED_BRANCHES -> {
        if (manifest.featureBranch != null) {
          invalidDecompositionManifest(sourceLabel, "stacked_branches requires feature_branch to be null.")
        }
        val expectedIds = manifest.subtasks.map { it.id }
        val branchIds = manifest.stackBranches.map { it.subtaskId }
        if (branchIds != expectedIds) {
          invalidDecompositionManifest(
            sourceLabel,
            "stacked_branches requires stack_branches to declare one branch per subtask in subtask order.",
          )
        }
      }
    }
  }

  private fun validateCurrentIntent(manifest: DecompositionManifest, sourceLabel: String) {
    val current = manifest.currentSubtaskIntent
    if (current.action == "none") {
      if (current.subtaskId != 0) {
        invalidDecompositionManifest(sourceLabel, "current_subtask_intent.subtask_id must be 0 when action is none.")
      }
      return
    }
    if (manifest.subtasks.none { it.id == current.subtaskId }) {
      invalidDecompositionManifest(
        sourceLabel,
        "current_subtask_intent.subtask_id '${current.subtaskId}' does not reference an existing subtask.",
      )
    }
  }
}

private fun Map<String, Any?>.toDecompositionManifest(sourceLabel: String): DecompositionManifest {
  val executionModelValue = stringValue("execution_model", sourceLabel)
  val executionModel =
    DecompositionExecutionModel.fromWireValue(executionModelValue)
      ?: invalidDecompositionManifest(sourceLabel, "execution_model '$executionModelValue' is not supported.")
  val subtasks = listValue("subtasks").mapIndexed { index, raw ->
    val item = raw.asMap(sourceLabel, "subtasks[$index]")
    DecompositionSubtask(
      id = item.intValue("id", sourceLabel),
      name = item.stringValue("name", sourceLabel),
      specPath = item.stringValue("spec_path", sourceLabel),
      status = item.stringValue("status", sourceLabel),
      branch = item.nullableStringValue("branch", sourceLabel),
      commitSha = item.nullableStringValue("commit_sha", sourceLabel),
      workflowId = item.nullableStringValue("workflow_id", sourceLabel),
      reviewResult = item.nullableMapValue("review_result", sourceLabel),
      auditResult = item.nullableMapValue("audit_result", sourceLabel),
      validationResult = item.nullableMapValue("validation_result", sourceLabel),
      blockedReason = item.nullableStringValue("blocked_reason", sourceLabel),
      lastResumableStep = item.nullableStringValue("last_resumable_step", sourceLabel),
      dependencies = item.listValue("dependencies").mapIndexed { depIndex, dep ->
        val dependency = dep.asMap(sourceLabel, "subtasks[$index].dependencies[$depIndex]")
        DecompositionDependency(
          subtaskId = dependency.intValue("subtask_id", sourceLabel),
          optional = dependency.booleanValue("optional", sourceLabel),
          skipped = dependency.booleanValue("skipped", sourceLabel),
        )
      },
    )
  }
  val current = this["current_subtask_intent"].asMap(sourceLabel, "current_subtask_intent")
  return DecompositionManifest(
    contractVersion = stringValue("contract_version", sourceLabel),
    issueKey = stringValue("issue_key", sourceLabel),
    featureName = stringValue("feature_name", sourceLabel),
    parentSpecPath = stringValue("parent_spec_path", sourceLabel),
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

private fun Map<String, Any?>.nullableMapValue(key: String, sourceLabel: String): Map<String, Any?>? =
  when (val value = this[key]) {
    null -> null
    is Map<*, *> -> value.entries.associateTo(LinkedHashMap<String, Any?>()) { (mapKey, mapValue) ->
      val stringKey = mapKey as? String ?: invalidDecompositionManifest(sourceLabel, "$key contains a non-string key.")
      stringKey to mapValue
    }
    else -> invalidDecompositionManifest(sourceLabel, "$key must be an object or null.")
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
  is Int -> this
  is Long -> this.toInt()
  is Number -> this.toInt()
  else -> invalidDecompositionManifest(sourceLabel, "$fieldPath must be an integer.")
}

private fun invalidDecompositionManifest(sourceLabel: String, reason: String): Nothing =
  throw InvalidDecompositionManifestSchemaError(sourceLabel = sourceLabel, reason = reason)
