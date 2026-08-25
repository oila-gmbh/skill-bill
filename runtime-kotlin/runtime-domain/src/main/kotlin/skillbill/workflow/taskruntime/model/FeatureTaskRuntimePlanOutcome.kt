package skillbill.workflow.taskruntime.model

import skillbill.boundary.OpenBoundaryMap
import skillbill.workflow.model.SpecSource

private const val DECOMPOSE_MODE: String = "decompose"

data class FeatureTaskRuntimeDecomposePlanOutcome(
  val reason: String,
  val featureName: String,
  val parentSpecOverview: String,
  val validationStrategy: String,
  val baseBranch: String,
  val featureBranch: String,
  val subtasks: List<FeatureTaskRuntimeDecomposeSubtask>,
  val specSource: SpecSource = SpecSource.LOCAL,
) {
  init {
    require(reason.isNotBlank()) { "FeatureTaskRuntimeDecomposePlanOutcome.reason must be non-blank." }
    require(featureName.isNotBlank()) { "FeatureTaskRuntimeDecomposePlanOutcome.featureName must be non-blank." }
    require(subtasks.size >= 2) {
      "FeatureTaskRuntimeDecomposePlanOutcome.subtasks must contain at least two subtasks."
    }
    if (specSource == SpecSource.LINEAR) {
      require(subtasks.all { !it.linearIssueId.isNullOrBlank() }) {
        "Linear decompose plans require a nonblank linear_issue_id for every subtask."
      }
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
  val linearIssueId: String? = null,
)

/**
 * True when a plan phase output is a decomposition package rather than a bounded planning projection.
 *
 * A decompose stop terminates the run at planning, so `implement` never runs and no consumer ever
 * parses an `executable_plan` from it. The package carries its own separately-contracted shape, which
 * [featureTaskRuntimeDecomposePlanOutcomeOrNull] decodes and the planning stopper loud-fails on, so
 * demanding a projection of it would block a correct decompose plan against a contract nothing reads.
 *
 * The absent `projection_kind` is the distinguishing signal: an `executable_plan` projection that
 * merely declares `mode: decompose` is still a projection and stays under the producer gate.
 */
@OpenBoundaryMap("Feature-task-runtime decomposition-package detection on the schema-validated phase-output wire map")
fun featureTaskRuntimeIsDecompositionPackage(phaseOutput: Map<String, Any?>): Boolean {
  sidecarDecompositionPackage(phaseOutput)?.let { packageMap ->
    if (packageMap["mode"]?.toString() == DECOMPOSE_MODE) return true
  }
  val producedOutputs = phaseOutput.stringAnyMap("produced_outputs") ?: return false
  if (producedOutputs["projection_kind"] != null) return false
  val packageMap = producedOutputs.stringAnyMap("decomposition_package") ?: producedOutputs
  return packageMap["mode"]?.toString() == DECOMPOSE_MODE
}

fun featureTaskRuntimeIsDecompositionPackageFromProse(prose: String): Boolean =
  decompositionPackageMapFromProse(prose)?.get("mode")?.toString() == DECOMPOSE_MODE

fun featureTaskRuntimeIsDecompositionPackageFromSidecar(sidecar: FeatureTaskRuntimePhaseRecordSidecar?): Boolean =
  sidecar?.decompositionPackage?.get("mode")?.toString() == DECOMPOSE_MODE

private fun sidecarDecompositionPackage(phaseOutput: Map<String, Any?>): Map<String, Any?>? {
  val sidecar = phaseOutput.stringAnyMap("runtime_owned_sidecar") ?: return null
  return sidecar.stringAnyMap("decomposition_package")
}

fun featureTaskRuntimeDecomposePlanOutcomeOrNull(
  phaseOutput: Map<String, Any?>,
  specSource: SpecSource,
): FeatureTaskRuntimeDecomposePlanOutcome? {
  val packageMap = sidecarDecompositionPackage(phaseOutput)
    ?: phaseOutput.stringAnyMap("produced_outputs")?.stringAnyMap("decomposition_package")
    ?: phaseOutput.stringAnyMap("produced_outputs")?.takeIf { it["mode"]?.toString() == DECOMPOSE_MODE }
  return packageMap?.takeIf { it["mode"]?.toString() == DECOMPOSE_MODE }?.let { decomposePackage ->
    decomposePlanOutcomeFromPackage(decomposePackage, phaseOutput["summary"]?.toString().orEmpty(), specSource)
  }
}

fun featureTaskRuntimeDecomposePlanOutcomeFromProse(
  prose: String,
  specSource: SpecSource,
): FeatureTaskRuntimeDecomposePlanOutcome? {
  val packageMap = decompositionPackageMapFromProse(prose) ?: return null
  if (packageMap["mode"]?.toString() != DECOMPOSE_MODE) return null
  return decomposePlanOutcomeFromPackage(packageMap, prose.lines().firstOrNull().orEmpty(), specSource)
}

private fun decomposePlanOutcomeFromPackage(
  decomposePackage: Map<String, Any?>,
  summary: String,
  specSource: SpecSource,
): FeatureTaskRuntimeDecomposePlanOutcome = FeatureTaskRuntimeDecomposePlanOutcome(
  reason = decomposePackage.firstString("reason", "decomposition_reason").ifBlank { summary },
  featureName = decomposePackage.firstString("feature_name", "name").ifBlank { "feature" },
  parentSpecOverview = decomposePackage.firstString("parent_spec_overview", "overview").ifBlank { summary },
  validationStrategy = decomposePackage.firstString("validation_strategy").ifBlank { "bill-code-check" },
  baseBranch = decomposePackage.firstString("base_branch").ifBlank { "main" },
  featureBranch = decomposePackage.firstString("feature_branch"),
  specSource = specSource,
  subtasks = decomposePackage.requireSubtasks(),
)
