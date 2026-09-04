package skillbill.application.workflow.model

import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.workflow.decomposition.DecompositionManifestValidator
import skillbill.workflow.decomposition.model.DecompositionContinuationSelection
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import java.nio.file.Path

data class AdvanceCompletedSubtasksRequest(
  val parentRecord: WorkflowStateSnapshot,
  val manifest: DecompositionManifest,
  val unitOfWork: UnitOfWork,
  val validator: DecompositionManifestValidator,
  val gitOperations: WorkflowGitOperations,
  val repoRootProvider: () -> Path,
)

data class CheckoutAndValidateBranchRequest(
  val parentRecord: WorkflowStateSnapshot,
  val manifest: DecompositionManifest,
  val selection: DecompositionContinuationSelection.Start,
  val unitOfWork: UnitOfWork,
  val validator: DecompositionManifestValidator,
  val gitOperations: WorkflowGitOperations,
  val repoRootProvider: () -> Path,
)
