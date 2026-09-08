package skillbill.application.workflow

import skillbill.application.workflow.model.FeatureTaskIdentityRepairArgs
import skillbill.application.workflow.model.WorkflowUpdateResult
import skillbill.contracts.issuekey.normalizeIssueKey
import skillbill.ports.continuation.FeatureTaskExecutionIdentityPolicy
import skillbill.ports.workflow.model.FeatureTaskExecutionIdentity
import skillbill.ports.workflow.model.FeatureTaskRouteScope
import skillbill.ports.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.WorkflowUpdateInput
import java.time.OffsetDateTime
import java.time.ZoneOffset

class WorkflowServiceFeatureTaskIdentityRepair(
  private val engine: WorkflowEngine,
) {
  fun repair(args: FeatureTaskIdentityRepairArgs): WorkflowUpdateResult {
    val unitOfWork = args.unitOfWork
    val workflowId = args.workflowId
    val normalizedIssueKey = args.normalizedIssueKey
    val repositoryIdentity = args.repositoryIdentity
    val governedSpecPath = args.governedSpecPath
    val normalizedReason = args.normalizedReason
    val family = WorkflowFamily.TASK_RUNTIME
    val workflowRow = unitOfWork.workflowStates.getFeatureTaskRuntimeWorkflow(workflowId)
      ?: return WorkflowUpdateResult.Error(
        workflowId,
        "Unknown runtime workflow_id '$workflowId'.",
        unitOfWork.dbPath.toString(),
      )
    val existing = requireNotNull(family.get(unitOfWork.workflowStates, workflowId))
    if (existing.workflowStatus in family.definition.terminalStatuses) {
      return WorkflowUpdateResult.Error(
        workflowId,
        "Runtime workflow '$workflowId' is already terminal with status '${existing.workflowStatus}'; " +
          "identity repair is only supported for nonterminal workflows.",
        unitOfWork.dbPath.toString(),
      )
    }
    val persistedIssueKey = workflowRow.issueKey?.let(::normalizeIssueKey)?.uppercase()
    if (persistedIssueKey != null && persistedIssueKey != normalizedIssueKey) {
      return WorkflowUpdateResult.Error(
        workflowId,
        "Runtime workflow '$workflowId' belongs to issue '$persistedIssueKey', not '$normalizedIssueKey'.",
        unitOfWork.dbPath.toString(),
      )
    }
    val identity = FeatureTaskExecutionIdentity(
      workflowId = workflowId,
      normalizedIssueKey = normalizedIssueKey,
      repositoryIdentity = repositoryIdentity,
      governedSpecPath = governedSpecPath,
      mode = FeatureTaskWorkflowMode.RUNTIME,
      routeScope = FeatureTaskRouteScope.STANDALONE,
    )
    FeatureTaskExecutionIdentityPolicy.validate(identity)
    unitOfWork.workflowStates.saveFeatureTaskExecutionIdentity(identity)
    val input = WorkflowUpdateInput(
      workflowStatus = existing.workflowStatus,
      currentStepId = existing.currentStepId.orEmpty(),
      stepUpdates = null,
      artifactsPatch = mapOf(
        FEATURE_TASK_RUNTIME_IDENTITY_REPAIR_ARTIFACT_KEY to mapOf(
          "reason" to normalizedReason,
          "repaired_at" to OffsetDateTime.now(ZoneOffset.UTC).toString(),
          "repository_identity" to repositoryIdentity,
          "governed_spec_path" to governedSpecPath,
        ),
      ),
      sessionId = "",
    )
    val updated = engine.updateRecord(family.definition, existing, input)
    family.save(unitOfWork.workflowStates, updated)
    return buildUpdateOk(engine, family.definition, updated, input, unitOfWork.dbPath.toString())
  }
}
