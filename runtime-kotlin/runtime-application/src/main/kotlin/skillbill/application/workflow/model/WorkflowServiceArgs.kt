package skillbill.application.workflow.model

import skillbill.application.decomposition.DecompositionManifestWriter
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import skillbill.workflow.decomposition.DecompositionManifestValidator
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.engine.model.WorkflowUpdateInput
import java.nio.file.Path

data class ContinueExistingWorkflowArgs(
  val validator: DecompositionManifestValidator? = null,
  val fileStore: DecompositionManifestStore,
  val repoRoot: Path? = null,
  val manifestWriter: DecompositionManifestWriter? = null,
)

data class DecompositionRuntimeWriteArgs(
  val existing: WorkflowStateSnapshot,
  val input: WorkflowUpdateInput,
  val workflowId: String,
  val validator: DecompositionManifestValidator,
  val fileStore: DecompositionManifestStore,
  val repoRoot: Path,
  val manifestWriter: DecompositionManifestWriter,
)
