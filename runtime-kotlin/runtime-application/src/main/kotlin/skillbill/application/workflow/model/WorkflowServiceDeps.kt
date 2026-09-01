package skillbill.application.workflow.model

import me.tatarka.inject.annotations.Inject
import skillbill.application.decomposition.DecompositionManifestWriter
import skillbill.model.RepositoryRoot
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.workflow.decomposition.DecompositionManifestFileStore
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.workflow.decomposition.DecompositionManifestValidator
import skillbill.workflow.engine.WorkflowSnapshotValidator
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.engine.model.WorkflowUpdateInput
import skillbill.workflow.goal.GoalObservabilityEventValidator
import java.nio.file.Path

@Inject
data class WorkflowServiceDeps(
  val database: DatabaseSessionFactory,
  val gitOperations: WorkflowGitOperations,
  val decompositionManifestFileStore: DecompositionManifestFileStore,
  val workflowSnapshotValidator: WorkflowSnapshotValidator,
  val decompositionManifestValidator: DecompositionManifestValidator,
  val decompositionManifestWriter: DecompositionManifestWriter,
  val repositoryRoot: RepositoryRoot,
  val goalObservabilityEventValidator: GoalObservabilityEventValidator,
)

data class ContinueExistingWorkflowArgs(
  val validator: DecompositionManifestValidator? = null,
  val fileStore: DecompositionManifestFileStore,
  val repoRoot: Path? = null,
  val manifestWriter: DecompositionManifestWriter? = null,
)

data class DecompositionRuntimeWriteArgs(
  val existing: WorkflowStateSnapshot,
  val input: WorkflowUpdateInput,
  val workflowId: String,
  val validator: DecompositionManifestValidator,
  val fileStore: DecompositionManifestFileStore,
  val repoRoot: Path,
  val manifestWriter: DecompositionManifestWriter,
)
