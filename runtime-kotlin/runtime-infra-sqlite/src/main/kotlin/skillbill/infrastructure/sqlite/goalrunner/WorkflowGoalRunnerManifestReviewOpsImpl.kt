package skillbill.infrastructure.sqlite.goalrunner
import skillbill.ports.goalrunner.persistence.featureTaskRecordForLegacyControls
import skillbill.ports.goalrunner.persistence.migrateLegacyGoalRunnerControls
import skillbill.ports.goalrunner.persistence.outOfBandAcceptancesFromLegacyArtifacts
import skillbill.ports.goalrunner.persistence.reviewPolicyFromLegacyArtifacts
import skillbill.ports.goalrunner.runner.model.GoalRunnerOutOfBandAcceptance
import skillbill.ports.goalrunner.runner.model.GoalRunnerReviewPolicy
import skillbill.ports.workflow.decomposition.runtime.decodeArtifacts
import skillbill.ports.workflow.persistence.model.WorkflowFamily
import skillbill.review.context.model.CodeReviewExecutionMode

internal class WorkflowGoalRunnerManifestReviewOpsImpl(
  private val ctx: WorkflowGoalRunnerManifestStoreContext,
) : GoalRunnerManifestReviewOps {
  override fun reviewMode(parentWorkflowId: String, dbPathOverride: String?): CodeReviewExecutionMode? =
    ctx.database.read(dbPathOverride) { unitOfWork ->
      unitOfWork.goalRunnerControls.reviewPolicy(parentWorkflowId)?.codeReviewMode
        ?: featureTaskRecordForLegacyControls(unitOfWork.workflowStates, parentWorkflowId)
          ?.let { record -> reviewPolicyFromLegacyArtifacts(decodeArtifacts(record.artifactsJson))?.codeReviewMode }
    }
  override fun persistReviewMode(
    parentWorkflowId: String,
    mode: CodeReviewExecutionMode,
    dbPathOverride: String?,
  ): CodeReviewExecutionMode = ctx.database.transaction(dbPathOverride) { unitOfWork ->
    val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, parentWorkflowId)
      ?: error("Goal parent workflow '$parentWorkflowId' no longer exists.")
    migrateLegacyGoalRunnerControls(unitOfWork, record)
    val existing = unitOfWork.goalRunnerControls.reviewPolicy(parentWorkflowId)?.codeReviewMode
    if (existing != null) {
      ctx.parentProjection.rewrite(unitOfWork, record)
      existing
    } else {
      unitOfWork.goalRunnerControls.persistReviewPolicy(
        parentWorkflowId,
        GoalRunnerReviewPolicy(codeReviewMode = mode),
      )
      ctx.parentProjection.rewrite(unitOfWork, record)
      mode
    }
  }
  override fun reviewPolicy(parentWorkflowId: String, dbPathOverride: String?): GoalRunnerReviewPolicy? =
    ctx.database.read(dbPathOverride) { unitOfWork ->
      unitOfWork.goalRunnerControls.reviewPolicy(parentWorkflowId)
        ?: featureTaskRecordForLegacyControls(unitOfWork.workflowStates, parentWorkflowId)
          ?.let { record -> reviewPolicyFromLegacyArtifacts(decodeArtifacts(record.artifactsJson)) }
    }
  override fun persistReviewPolicy(
    parentWorkflowId: String,
    policy: GoalRunnerReviewPolicy,
    dbPathOverride: String?,
  ): GoalRunnerReviewPolicy = ctx.database.transaction(dbPathOverride) { unitOfWork ->
    val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, parentWorkflowId)
      ?: error("Goal parent workflow '$parentWorkflowId' no longer exists.")
    migrateLegacyGoalRunnerControls(unitOfWork, record)
    val existing = unitOfWork.goalRunnerControls.reviewPolicy(parentWorkflowId)
    if (existing == policy) {
      ctx.parentProjection.rewrite(unitOfWork, record)
      existing
    } else {
      unitOfWork.goalRunnerControls.persistReviewPolicy(parentWorkflowId, policy)
      ctx.parentProjection.rewrite(unitOfWork, record)
      policy
    }
  }
  override fun outOfBandAcceptances(
    parentWorkflowId: String,
    dbPathOverride: String?,
  ): Map<Int, GoalRunnerOutOfBandAcceptance> = ctx.database.read(dbPathOverride) { unitOfWork ->
    unitOfWork.goalRunnerControls.outOfBandAcceptances(parentWorkflowId).ifEmpty {
      featureTaskRecordForLegacyControls(unitOfWork.workflowStates, parentWorkflowId)
        ?.let { record -> outOfBandAcceptancesFromLegacyArtifacts(decodeArtifacts(record.artifactsJson)) }
        .orEmpty()
    }
  }
  override fun persistOutOfBandAcceptance(
    parentWorkflowId: String,
    acceptance: GoalRunnerOutOfBandAcceptance,
    dbPathOverride: String?,
  ): GoalRunnerOutOfBandAcceptance = ctx.database.transaction(dbPathOverride) { unitOfWork ->
    val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, parentWorkflowId)
      ?: error("Goal parent workflow '$parentWorkflowId' no longer exists.")
    migrateLegacyGoalRunnerControls(unitOfWork, record)
    unitOfWork.goalRunnerControls.persistOutOfBandAcceptance(parentWorkflowId, acceptance)
    ctx.parentProjection.rewrite(unitOfWork, record)
    acceptance
  }
}
