package skillbill.application

import skillbill.workflow.model.DecompositionDependency
import skillbill.workflow.model.DecompositionExecutionModel
import skillbill.workflow.model.DecompositionStackBranch
import skillbill.workflow.model.DecompositionSubtask
import java.nio.file.Path

internal fun parseSubtasks(planningResult: Map<String, Any?>, sourceLabel: String): List<DecompositionSubtask> {
  val rawSubtasks = planningResult["subtasks"] as? List<*>
    ?: invalidManifest(sourceLabel, "decomposition planning result must contain subtasks.")
  return rawSubtasks.mapIndexed { index, raw ->
    val item = raw.asStringAnyMap(sourceLabel, "subtasks[$index]")
    val name = item["title"]?.toString()?.ifBlank { null }
      ?: item["name"]?.toString()?.ifBlank { null }
      ?: "Subtask ${item.intValue("id", sourceLabel)}"
    DecompositionSubtask(
      id = item.intValue("id", sourceLabel),
      name = name,
      specPath = item["spec_path"]?.toString()?.ifBlank { null }
        ?: invalidManifest(sourceLabel, "subtasks[$index].spec_path must be present."),
      status = "pending",
      dependencies = parseDependencies(item["dependencies"] ?: item["depends_on"], sourceLabel, index),
    )
  }
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
  plan["parent_spec_path"]?.toString()?.takeIf(String::isNotBlank)?.let { return it }
  val firstSubtask = (plan["subtasks"] as? List<*>).orEmpty().firstOrNull().asStringAnyMapOrNull()
    ?: invalidManifest("<planning-result>", "decomposition planning result must contain subtasks.")
  val firstSpecPath = firstSubtask["spec_path"]?.toString()?.takeIf(String::isNotBlank)
    ?: invalidManifest("<planning-result>", "subtasks[0].spec_path must be present.")
  return Path.of(firstSpecPath).parent.resolve("spec.md").toString()
}

internal fun executionModel(plan: Map<String, Any?>): DecompositionExecutionModel {
  val raw = plan["execution_model"]?.toString()?.takeIf(String::isNotBlank)
    ?: DecompositionExecutionModel.SAME_BRANCH_COMMIT_PER_SUBTASK.wireValue
  return DecompositionExecutionModel.fromWireValue(raw)
    ?: invalidManifest("<planning-result>", "execution_model '$raw' is not supported.")
}

internal fun parseStackBranches(plan: Map<String, Any?>): List<DecompositionStackBranch> =
  (plan["stack_branches"] as? List<*>).orEmpty().mapIndexed { index, raw ->
    val item = raw.asStringAnyMap("<planning-result>", "stack_branches[$index]")
    DecompositionStackBranch(
      subtaskId = item.intValue("subtask_id", "<planning-result>"),
      branch = item["branch"]?.toString()?.takeIf(String::isNotBlank)
        ?: invalidManifest("<planning-result>", "stack_branches[$index].branch must be present."),
      baseBranch = item["base_branch"]?.toString()?.takeIf(String::isNotBlank) ?: "main",
    )
  }

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
