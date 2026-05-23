package skillbill.workflow

import skillbill.error.InvalidDecompositionManifestSchemaError

internal object DecompositionManifestCoherenceValidator {
  fun validate(manifest: Map<String, Any?>, sourceLabel: String) {
    val stackBranches = (manifest["stack_branches"] as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }
    val subtasks = (manifest["subtasks"] as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }
    val subtaskIds = validateSubtasks(subtasks, sourceLabel)
    validateExecutionModel(manifest, subtasks, subtaskIds, stackBranches, sourceLabel)
    validateCurrentIntent(manifest, subtaskIds, sourceLabel)
  }

  private fun validateSubtasks(subtasks: List<Map<*, *>>, sourceLabel: String): Set<Int> {
    val subtaskIds = mutableSetOf<Int>()
    subtasks.forEachIndexed { index, subtask ->
      val id = (subtask["id"] as? Number)?.toInt()
        ?: throw coherenceError(sourceLabel, "subtasks[$index].id", "Subtask id must be an integer.")
      if (!subtaskIds.add(id)) {
        throw coherenceError(sourceLabel, "subtasks[$index].id", "Duplicate subtask id '$id'.")
      }
      validateDependencies(subtask, subtaskIds, id, index, sourceLabel)
    }
    return subtaskIds
  }

  private fun validateDependencies(
    subtask: Map<*, *>,
    subtaskIds: Set<Int>,
    id: Int,
    index: Int,
    sourceLabel: String,
  ) {
    val dependencies = (subtask["dependencies"] as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }
    dependencies.forEachIndexed { depIndex, dependency ->
      val path = "subtasks[$index].dependencies[$depIndex].subtask_id"
      val dependencyId = (dependency["subtask_id"] as? Number)?.toInt()
        ?: throw coherenceError(sourceLabel, path, "Dependency subtask_id must be an integer.")
      if (dependencyId !in subtaskIds || dependencyId == id) {
        throw coherenceError(
          sourceLabel,
          path,
          "Dependency '$dependencyId' must reference an earlier declared subtask.",
        )
      }
    }
  }

  private fun validateExecutionModel(
    manifest: Map<String, Any?>,
    subtasks: List<Map<*, *>>,
    subtaskIds: Set<Int>,
    stackBranches: List<Map<*, *>>,
    sourceLabel: String,
  ) {
    when (manifest["execution_model"]?.toString().orEmpty()) {
      "same_branch_commit_per_subtask" -> validateSameBranch(manifest, stackBranches, sourceLabel)
      "stacked_branches" -> validateStackedBranches(manifest, subtasks, subtaskIds, stackBranches, sourceLabel)
    }
  }

  private fun validateSameBranch(manifest: Map<String, Any?>, stackBranches: List<Map<*, *>>, sourceLabel: String) {
    if (manifest["feature_branch"]?.toString().orEmpty().isBlank()) {
      throw coherenceError(
        sourceLabel,
        "feature_branch",
        "same_branch_commit_per_subtask manifests must declare feature_branch.",
      )
    }
    if (stackBranches.isNotEmpty()) {
      throw coherenceError(
        sourceLabel,
        "stack_branches",
        "same_branch_commit_per_subtask manifests must not declare stack branches.",
      )
    }
  }

  private fun validateStackedBranches(
    manifest: Map<String, Any?>,
    subtasks: List<Map<*, *>>,
    subtaskIds: Set<Int>,
    stackBranches: List<Map<*, *>>,
    sourceLabel: String,
  ) {
    if (manifest["feature_branch"] != null) {
      throw coherenceError(sourceLabel, "feature_branch", "stacked_branches manifests must set feature_branch to null.")
    }
    val expectedStackIds = subtasks.mapNotNull { (it["id"] as? Number)?.toInt() }
    val actualStackIds = stackBranches.mapNotNull { (it["subtask_id"] as? Number)?.toInt() }
    if (actualStackIds != expectedStackIds || actualStackIds.toSet() != subtaskIds) {
      throw coherenceError(
        sourceLabel,
        "stack_branches",
        "stacked_branches manifests must declare exactly one branch per subtask in subtask order.",
      )
    }
  }

  private fun validateCurrentIntent(manifest: Map<String, Any?>, subtaskIds: Set<Int>, sourceLabel: String) {
    val intent = manifest["current_subtask_intent"] as? Map<*, *> ?: return
    val intentId = (intent["subtask_id"] as? Number)?.toInt() ?: 0
    val intentAction = intent["action"]?.toString().orEmpty()
    if (intentAction == "none" && intentId != 0) {
      throw coherenceError(
        sourceLabel,
        "current_subtask_intent.subtask_id",
        "Intent action none must use subtask_id 0.",
      )
    }
    if (intentAction != "none" && intentId !in subtaskIds) {
      throw coherenceError(
        sourceLabel,
        "current_subtask_intent.subtask_id",
        "Current subtask intent must reference a declared subtask.",
      )
    }
  }

  private fun coherenceError(
    sourceLabel: String,
    fieldPath: String,
    reason: String,
  ): InvalidDecompositionManifestSchemaError =
    InvalidDecompositionManifestSchemaError(sourceLabel = sourceLabel, reason = "$fieldPath: $reason")
}
