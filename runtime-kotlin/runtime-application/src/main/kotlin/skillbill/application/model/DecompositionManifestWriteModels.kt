package skillbill.application.model

import skillbill.workflow.model.DecompositionExecutionModel
import skillbill.workflow.model.DecompositionManifest
import skillbill.workflow.model.DecompositionStackBranch
import java.nio.file.Path

data class DecompositionManifestWriteRequest(
  val repoRoot: Path,
  val parentSpecPath: Path,
  val planningResult: Map<String, Any?>,
  val baseBranch: String,
  val featureBranch: String?,
  val executionModel: DecompositionExecutionModel = DecompositionExecutionModel.SAME_BRANCH_COMMIT_PER_SUBTASK,
  val stackBranches: List<DecompositionStackBranch> = emptyList(),
  val currentSubtaskId: Int? = null,
)

data class DecompositionManifestRuntimeUpdate(
  val workflowId: String = "",
  val workflowStatus: String = "",
  val currentStepId: String = "",
  val stepUpdates: List<Map<String, Any?>>? = null,
  val artifactsPatch: Map<String, Any?>? = null,
  val existingArtifacts: Map<String, Any?> = emptyMap(),
)

data class DecompositionManifestWriteResult(
  val manifestPath: Path,
  val manifest: DecompositionManifest,
)
