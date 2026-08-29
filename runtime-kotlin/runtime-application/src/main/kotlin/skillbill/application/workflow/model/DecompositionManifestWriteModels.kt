package skillbill.application.workflow.model

import skillbill.boundary.OpenBoundaryMap
import skillbill.workflow.decomposition.model.DecompositionExecutionModel
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionManifestRepairEvidence
import skillbill.workflow.decomposition.model.DecompositionStackBranch
import skillbill.workflow.decomposition.model.SpecSource
import java.nio.file.Path

data class DecompositionManifestWriteRequest(
  val repoRoot: Path,
  val parentSpecPath: Path,
  @OpenBoundaryMap("Caller-supplied JSON plan payload")
  val planningResult: Map<String, Any?>,
  val baseBranch: String,
  val featureBranch: String?,
  val executionModel: DecompositionExecutionModel = DecompositionExecutionModel.SAME_BRANCH_COMMIT_PER_SUBTASK,
  val stackBranches: List<DecompositionStackBranch> = emptyList(),
  val currentSubtaskId: Int? = null,
  val specSource: SpecSource = SpecSource.LOCAL,
)

data class DecompositionManifestRuntimeUpdate(
  val workflowId: String = "",
  val workflowStatus: String = "",
  val currentStepId: String = "",
  @OpenBoundaryMap("Caller-supplied JSON patch for workflow step updates")
  val stepUpdates: List<Map<String, Any?>>? = null,
  @OpenBoundaryMap("Caller-supplied JSON patch for durable workflow artifacts")
  val artifactsPatch: Map<String, Any?>? = null,
  @OpenBoundaryMap("Workflow artifacts snapshot (caller-supplied JSON passthrough)")
  val existingArtifacts: Map<String, Any?> = emptyMap(),
)

data class DecompositionManifestWriteResult(
  val manifestPath: Path,
  val manifest: DecompositionManifest,
  val repairEvidence: List<DecompositionManifestRepairEvidence> = emptyList(),
)
