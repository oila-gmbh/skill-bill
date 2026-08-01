package skillbill.application.decomposition

import skillbill.workflow.model.DecompositionDependency
import skillbill.workflow.model.DecompositionExecutionModel
import skillbill.workflow.model.DecompositionStackBranch
import skillbill.workflow.model.DecompositionSubtask
import skillbill.workflow.model.SpecSource
import java.nio.file.Path

internal fun parseSubtasks(
  planningResult: Map<String, Any?>,
  sourceLabel: String,
  specSource: SpecSource = specSource(planningResult, sourceLabel),
): List<DecompositionSubtask> {
  val rawSubtasks = planningResult["subtasks"] as? List<*>
    ?: invalidManifest(sourceLabel, "decomposition planning result must contain subtasks.")
  if (rawSubtasks.isEmpty()) {
    invalidManifest(sourceLabel, "decomposition planning result must contain at least one subtask.")
  }
  return rawSubtasks.mapIndexed { index, raw ->
    val item = raw.asStringAnyMap(sourceLabel, "subtasks[$index]")
    val name = when {
      item.containsKey("title") -> item.stringValue("title", sourceLabel, "subtasks[$index].title")
      item.containsKey("name") -> item.stringValue("name", sourceLabel, "subtasks[$index].name")
      else -> invalidManifest(sourceLabel, "subtasks[$index].name must be present.")
    }
    DecompositionSubtask(
      id = item.intValue("id", sourceLabel),
      name = name,
      specPath = item.stringValue("spec_path", sourceLabel, "subtasks[$index].spec_path"),
      status = "pending",
      linearIssueId = linearIssueId(item, index, sourceLabel, specSource),
      dependencies = parseDependencies(item["dependencies"] ?: item["depends_on"], sourceLabel, index),
    )
  }
}

internal fun specSource(plan: Map<String, Any?>, sourceLabel: String = "<planning-result>"): SpecSource {
  val raw = plan["spec_source"] ?: return SpecSource.LOCAL
  val value = raw as? String
    ?: invalidManifest(sourceLabel, "spec_source must be a string when present.")
  if (value.isBlank()) {
    invalidManifest(sourceLabel, "spec_source must be nonblank when present.")
  }
  return SpecSource.fromWireValue(value)
    ?: invalidManifest(sourceLabel, "spec_source '$value' is not supported.")
}

private fun linearIssueId(item: Map<String, Any?>, index: Int, sourceLabel: String, specSource: SpecSource): String? {
  val raw = item["linear_issue_id"]
  val value = when (raw) {
    null -> null
    is String -> raw.takeIf(String::isNotBlank)
      ?: invalidManifest(sourceLabel, "subtasks[$index].linear_issue_id must be nonblank when present.")
    else -> invalidManifest(sourceLabel, "subtasks[$index].linear_issue_id must be a string when present.")
  }
  if (specSource == SpecSource.LINEAR && value == null) {
    invalidManifest(sourceLabel, "subtasks[$index].linear_issue_id is required for linear spec_source.")
  }
  return value
}

internal fun parseDependencies(raw: Any?, sourceLabel: String, subtaskIndex: Int): List<DecompositionDependency> {
  if (raw == null) {
    return emptyList()
  }
  val dependencies = raw as? List<*> ?: invalidManifest(
    sourceLabel,
    "subtasks[$subtaskIndex].dependencies must be a list.",
  )
  return dependencies.mapIndexed { depIndex, value ->
    when (value) {
      is Map<*, *> -> {
        val dependency = value.asStringAnyMap(sourceLabel, "subtasks[$subtaskIndex].dependencies[$depIndex]")
        DecompositionDependency(
          subtaskId = dependency.intValue("subtask_id", sourceLabel),
          optional = dependency.booleanValueOrDefault("optional", false, sourceLabel),
          skipped = dependency.booleanValueOrDefault("skipped", false, sourceLabel),
        )
      }
      else -> DecompositionDependency(
        subtaskId = value.asInt(sourceLabel, "subtasks[$subtaskIndex].dependencies[$depIndex]"),
      )
    }
  }
}

internal fun parentSpecPath(plan: Map<String, Any?>): String {
  when (val rawParentPath = plan["parent_spec_path"]) {
    is String -> if (rawParentPath.isNotBlank()) return rawParentPath
    null -> Unit
    else -> invalidManifest("<planning-result>", "parent_spec_path must be a string when present.")
  }
  val firstSubtask = (plan["subtasks"] as? List<*>).orEmpty().firstOrNull().asStringAnyMapOrNull()
    ?: invalidManifest("<planning-result>", "decomposition planning result must contain subtasks.")
  val firstSpecPath = firstSubtask["spec_path"] as? String
    ?: invalidManifest("<planning-result>", "subtasks[0].spec_path must be a string.")
  if (firstSpecPath.isBlank()) invalidManifest("<planning-result>", "subtasks[0].spec_path must be nonblank.")
  return Path.of(firstSpecPath).parent.resolve("spec.md").toString()
}

internal fun executionModel(plan: Map<String, Any?>): DecompositionExecutionModel {
  val raw = when (val value = plan["execution_model"]) {
    null -> DecompositionExecutionModel.SAME_BRANCH_COMMIT_PER_SUBTASK.wireValue
    is String -> value.takeIf(String::isNotBlank)
      ?: invalidManifest("<planning-result>", "execution_model must be nonblank when present.")
    else -> invalidManifest("<planning-result>", "execution_model must be a string when present.")
  }
  return DecompositionExecutionModel.fromWireValue(raw)
    ?: invalidManifest("<planning-result>", "execution_model '$raw' is not supported.")
}

internal fun baseBranch(plan: Map<String, Any?>, sourceLabel: String): String {
  val raw = plan["base_branch"] ?: return "main"
  return (raw as? String)?.takeIf(String::isNotBlank)
    ?: invalidManifest(sourceLabel, "base_branch must be a nonblank string when present.")
}

internal fun parseStackBranches(plan: Map<String, Any?>): List<DecompositionStackBranch> =
  (plan["stack_branches"] as? List<*>).orEmpty().mapIndexed { index, raw ->
    val item = raw.asStringAnyMap("<planning-result>", "stack_branches[$index]")
    DecompositionStackBranch(
      subtaskId = item.intValue("subtask_id", "<planning-result>"),
      branch = item.stringValue("branch", "<planning-result>", "stack_branches[$index].branch"),
      baseBranch = item.stringValue("base_branch", "<planning-result>", "stack_branches[$index].base_branch"),
    )
  }

private fun Map<String, Any?>.stringValue(key: String, sourceLabel: String, fieldPath: String): String =
  (this[key] as? String)?.takeIf(String::isNotBlank)
    ?: invalidManifest(sourceLabel, "$fieldPath must be a nonblank string.")

internal fun defaultFeatureBranch(parentSpecPath: Path): String {
  val (issueKey, featureName) = issueAndFeature(parentSpecPath.parent.fileName.toString())
  return "feat/$issueKey-$featureName"
}

internal fun branchName(branchArtifact: Any?): String = when (branchArtifact) {
  is Map<*, *> -> branchArtifact["branch_name"]?.toString().orEmpty()
    .ifBlank { branchArtifact["branch"]?.toString().orEmpty() }
  is String -> branchArtifact
  else -> ""
}
