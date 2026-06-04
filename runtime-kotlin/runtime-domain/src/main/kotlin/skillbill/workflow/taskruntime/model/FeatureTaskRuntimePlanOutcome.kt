package skillbill.workflow.taskruntime.model

import skillbill.boundary.OpenBoundaryMap

private const val DECOMPOSE_MODE: String = "decompose"

data class FeatureTaskRuntimeDecomposePlanOutcome(
  val reason: String,
  val featureName: String,
  val parentSpecOverview: String,
  val validationStrategy: String,
  val baseBranch: String,
  val featureBranch: String,
  val subtasks: List<FeatureTaskRuntimeDecomposeSubtask>,
) {
  init {
    require(reason.isNotBlank()) { "FeatureTaskRuntimeDecomposePlanOutcome.reason must be non-blank." }
    require(featureName.isNotBlank()) { "FeatureTaskRuntimeDecomposePlanOutcome.featureName must be non-blank." }
    require(subtasks.size >= 2) {
      "FeatureTaskRuntimeDecomposePlanOutcome.subtasks must contain at least two subtasks."
    }
  }
}

data class FeatureTaskRuntimeDecomposeSubtask(
  val id: Int,
  val name: String,
  val scope: String,
  val acceptanceCriteria: List<String>,
  val nonGoals: List<String>,
  val dependencyNotes: String,
  val validationStrategy: String,
  val nextPath: String,
  val dependsOn: List<Int>,
)

@OpenBoundaryMap("Feature-task-runtime plan outcome projection reads the schema-validated phase-output wire map")
fun featureTaskRuntimeDecomposePlanOutcomeOrNull(
  phaseOutput: Map<String, Any?>,
): FeatureTaskRuntimeDecomposePlanOutcome? {
  val producedOutputs = phaseOutput.stringAnyMap("produced_outputs")
  val packageMap = producedOutputs?.stringAnyMap("decomposition_package") ?: producedOutputs
  return packageMap?.takeIf { it["mode"]?.toString() == DECOMPOSE_MODE }?.let { decomposePackage ->
    val summary = phaseOutput["summary"]?.toString().orEmpty()
    FeatureTaskRuntimeDecomposePlanOutcome(
      reason = decomposePackage.firstString("reason", "decomposition_reason").ifBlank { summary },
      featureName = decomposePackage.firstString("feature_name", "name").ifBlank { "feature" },
      parentSpecOverview = decomposePackage.firstString("parent_spec_overview", "overview").ifBlank { summary },
      validationStrategy = decomposePackage.firstString("validation_strategy").ifBlank { "bill-code-check" },
      baseBranch = decomposePackage.firstString("base_branch").ifBlank { "main" },
      featureBranch = decomposePackage.firstString("feature_branch"),
      subtasks = decomposePackage.requireSubtasks(),
    )
  }
}
