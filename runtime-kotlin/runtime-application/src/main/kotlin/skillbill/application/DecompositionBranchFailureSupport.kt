package skillbill.application

import skillbill.ports.persistence.UnitOfWork
import skillbill.workflow.model.DecompositionManifest
import skillbill.workflow.model.WorkflowStateSnapshot

internal fun blockedBranchStartPayload(
  parentRecord: WorkflowStateSnapshot,
  manifest: DecompositionManifest,
  subtaskId: Int,
  reason: String,
  unitOfWork: UnitOfWork,
): Map<String, Any?> {
  val blockedManifest = manifest.withBlockedSubtask(subtaskId, reason, "create_branch")
  persistParentDecompositionRuntime(parentRecord, blockedManifest, unitOfWork)
  return gitErrorPayload(parentRecord.workflowId, manifest.issueKey, reason, unitOfWork)
}
