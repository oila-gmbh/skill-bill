package skillbill.application.featuretask

import skillbill.application.decomposition.decodeArtifacts
import skillbill.application.workflow.WorkflowFamily
import skillbill.error.InvalidFeatureTaskRuntimeCheckpointIdentityVersionError
import skillbill.error.InvalidGoalSubtaskReviewStateSchemaError
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.workflow.goal.model.GOAL_REVIEW_BASE_RECOVERIES_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_CHECKPOINT_IDENTITIES_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.featureTaskRuntimeCheckpointIdentitiesFromArtifact
import java.nio.file.Path
import java.time.Clock

private const val CHECKPOINT_IDENTITY_QUARANTINE_ARTIFACT_KEY: String =
  "feature_task_runtime_checkpoint_identities_quarantine"

internal class FeatureTaskRuntimeRemediationBaseReconciler(
  internal val database: DatabaseSessionFactory,
  internal val patcher: FeatureTaskRuntimeGoalContinuationArtifactPatcher,
  internal val clock: Clock,
) {
  fun reconcileRemediationBaseCoherence(
    workflowId: String,
    gitOperations: WorkflowGitOperations,
    repoRoot: Path,
    dbOverride: String? = null,
  ): RemediationBaseCoherenceResult {
    val snapshot = try {
      readRemediationSnapshot(workflowId, dbOverride)
    } catch (error: InvalidFeatureTaskRuntimeCheckpointIdentityVersionError) {
      quarantineLegacyCheckpointIdentities(workflowId, error, dbOverride)
      return RemediationBaseCoherent(null)
    } ?: return RemediationBaseCoherent(null)
    return reconcileFromSnapshot(
      snapshot = snapshot,
      workflowId = workflowId,
      gitOperations = gitOperations,
      repoRoot = repoRoot,
      dbOverride = dbOverride,
    )
  }

  private fun quarantineLegacyCheckpointIdentities(
    workflowId: String,
    error: InvalidFeatureTaskRuntimeCheckpointIdentityVersionError,
    dbOverride: String?,
  ) {
    database.transaction(dbOverride) { unitOfWork ->
      val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId)
        ?: return@transaction
      val artifacts = decodeArtifacts(record.artifactsJson)
      val rejected = artifacts[FEATURE_TASK_RUNTIME_CHECKPOINT_IDENTITIES_ARTIFACT_KEY]
        ?: return@transaction
      val existing = (artifacts[CHECKPOINT_IDENTITY_QUARANTINE_ARTIFACT_KEY] as? List<*>).orEmpty()
      patcher.save(
        record,
        unitOfWork.workflowStates,
        mapOf(
          FEATURE_TASK_RUNTIME_CHECKPOINT_IDENTITIES_ARTIFACT_KEY to null,
          CHECKPOINT_IDENTITY_QUARANTINE_ARTIFACT_KEY to existing + listOf(
            linkedMapOf(
              "workflow_id" to workflowId,
              "rejection_detail" to error.message.orEmpty(),
              "quarantined_at" to clock.instant().toString(),
              "rejected_record" to rejected,
            ),
          ),
        ),
      )
    }
  }

  private fun readRemediationSnapshot(workflowId: String, dbOverride: String?): RemediationReconcileSnapshot? =
    database.read(dbOverride) { unitOfWork ->
      val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId) ?: return@read null
      val artifacts = decodeArtifacts(record.artifactsJson)
      runCatching {
        val state = reviewStateFromArtifacts(artifacts) ?: return@read null
        val continuation = continuationFromArtifacts(artifacts) ?: return@read null
        val checkpoints = featureTaskRuntimeCheckpointIdentitiesFromArtifact(
          artifacts[FEATURE_TASK_RUNTIME_CHECKPOINT_IDENTITIES_ARTIFACT_KEY],
        )
        RemediationReconcileSnapshot(state, continuation, checkpoints)
      }.getOrElse { error ->
        if (error is InvalidGoalSubtaskReviewStateSchemaError) return@read null else throw error
      }
    }

  fun appendRemediationRollbackDegradationEvidence(
    workflowId: String,
    signal: RemediationDegradationSignal,
    dbOverride: String?,
  ) {
    database.transaction(dbOverride) { unitOfWork ->
      val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId) ?: return@transaction
      val artifacts = decodeArtifacts(record.artifactsJson)
      val goalBranch = continuationFromArtifacts(artifacts)?.goalBranch.orEmpty()
      val evidenceEntry = remediationBaseRecoveryEvidenceEntry(
        RemediationBaseRecovery(
          originalSha = null,
          replacementSha = null,
          reason = "rollback_degradation",
          goalBranch = goalBranch,
          failureMessageOverride = "Remediation rollback degradation at ${signal.seam}.",
        ),
        signal,
      )
      val priorEvidence = (artifacts[GOAL_REVIEW_BASE_RECOVERIES_ARTIFACT_KEY] as? List<*>).orEmpty()
      patcher.save(
        record,
        unitOfWork.workflowStates,
        mapOf(GOAL_REVIEW_BASE_RECOVERIES_ARTIFACT_KEY to priorEvidence + evidenceEntry),
      )
    }
  }
}

internal data class RemediationBaseRecovery(
  val originalSha: String?,
  val replacementSha: String?,
  val reason: String,
  val goalBranch: String,
  val headSha: String? = null,
  val failureMessageOverride: String? = null,
)

internal data class RemediationDegradationSignal(
  val seam: String? = null,
  val valueUsed: String? = null,
  val valueExpected: String? = null,
  val cause: String? = null,
)
