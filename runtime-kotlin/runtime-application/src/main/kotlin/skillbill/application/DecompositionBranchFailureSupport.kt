package skillbill.application

import skillbill.application.model.WorkflowContinueResult
import skillbill.ports.persistence.UnitOfWork
import skillbill.workflow.model.DecompositionManifest
import skillbill.workflow.model.WorkflowStateSnapshot

internal fun blockedBranchStartResult(
  parentRecord: WorkflowStateSnapshot,
  manifest: DecompositionManifest,
  subtaskId: Int,
  reason: String,
  unitOfWork: UnitOfWork,
): WorkflowContinueResult {
  val blockedManifest = manifest.withBlockedSubtask(subtaskId, reason, "create_branch")
  persistParentDecompositionRuntime(parentRecord, blockedManifest, unitOfWork)
  return WorkflowContinueResult.DecompositionBlockedBranchStart(
    dbPath = unitOfWork.dbPath.toString(),
    workflowId = parentRecord.workflowId,
    issueKey = manifest.issueKey,
    blockedReason = reason.ifBlank { "Git operation failed." },
  )
}
