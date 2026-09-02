package skillbill.application.featuretask

import skillbill.application.decomposition.decodeArtifacts
import skillbill.application.featuretask.model.PersistHealedRemediationBaseRequest
import skillbill.application.featuretask.model.RemediationBaseBlocked
import skillbill.application.featuretask.model.RemediationBaseCoherenceResult
import skillbill.application.featuretask.model.RemediationBaseCoherent
import skillbill.application.featuretask.model.RemediationBaseHealRequest
import skillbill.application.featuretask.model.RemediationReconcileSnapshot
import skillbill.application.featuretask.model.RemediationReconciliationApplyRequest
import skillbill.application.featuretask.model.RemediationReconciliationBlocked
import skillbill.application.featuretask.model.RemediationReconciliationCoherent
import skillbill.application.featuretask.model.RemediationReconciliationHeal
import skillbill.application.workflow.model.WorkflowFamily
import skillbill.error.InvalidFeatureTaskRuntimeCheckpointIdentityVersionError
import skillbill.error.InvalidGoalSubtaskReviewStateSchemaError
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.workflow.goal.model.GOAL_REVIEW_BASE_RECOVERIES_ARTIFACT_KEY
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_CHECKPOINT_IDENTITIES_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalContinuationArtifact
import skillbill.workflow.taskruntime.model.featureTaskRuntimeCheckpointIdentitiesFromArtifact
import java.nio.file.Path
import java.time.Clock

private const val CHECKPOINT_IDENTITY_QUARANTINE_ARTIFACT_KEY: String =
  "feature_task_runtime_checkpoint_identities_quarantine"

class FeatureTaskRuntimeRemediationBaseReconciler(
  val database: DatabaseSessionFactory,
  val patcher: FeatureTaskRuntimeGoalContinuationArtifactPatcher,
  val clock: Clock,
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

  internal fun appendRemediationRollbackDegradationEvidence(
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

internal fun FeatureTaskRuntimeRemediationBaseReconciler.reconcileFromSnapshot(
  snapshot: RemediationReconcileSnapshot,
  workflowId: String,
  gitOperations: WorkflowGitOperations,
  repoRoot: Path,
  dbOverride: String?,
): RemediationBaseCoherenceResult {
  if (snapshot.state.remediationBaseSha == null &&
    snapshot.checkpoints.none { it.loopId == FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID }
  ) {
    return RemediationBaseCoherent(snapshot.state)
  }
  val latestRemediationResolved = latestResolvedReviewFixCheckpointCommit(
    checkpoints = snapshot.checkpoints,
    gitOperations = gitOperations,
    repoRoot = repoRoot,
  )
  val reconciliation = decideRemediationReconciliation(
    snapshot = snapshot,
    latestRemediationResolved = latestRemediationResolved,
    gitOperations = gitOperations,
    repoRoot = repoRoot,
  )
  return applyRemediationReconciliation(
    RemediationReconciliationApplyRequest(
      reconciliation = reconciliation,
      snapshot = snapshot,
      workflowId = workflowId,
      gitOperations = gitOperations,
      repoRoot = repoRoot,
      dbOverride = dbOverride,
      latestRemediationResolved = latestRemediationResolved,
    ),
  )
}

internal fun FeatureTaskRuntimeRemediationBaseReconciler.applyRemediationReconciliation(
  request: RemediationReconciliationApplyRequest,
): RemediationBaseCoherenceResult {
  val state = request.snapshot.state
  val continuation = request.snapshot.continuation
  val checkpoints = request.snapshot.checkpoints
  val stored = state.remediationBaseSha
  val storedResolves = stored?.let { resolvesCommit(request.gitOperations, request.repoRoot, it) } == true
  return when (request.reconciliation) {
    RemediationReconciliationCoherent -> RemediationBaseCoherent(state)
    RemediationReconciliationBlocked -> {
      val failedRef = latestReviewFixCheckpointRef(checkpoints)
      val guidance = remediationBaseReconciliationBlockedGuidance(
        workflowId = request.workflowId,
        continuation = continuation,
        failedRef = failedRef,
        storedSha = stored,
      )
      appendRemediationBaseReconciliationEvidence(
        workflowId = request.workflowId,
        recovery = RemediationBaseRecovery(
          originalSha = stored,
          replacementSha = null,
          reason = "reconciliation_blocked",
          goalBranch = continuation.goalBranch,
          failureMessageOverride = guidance,
        ),
        signal = RemediationDegradationSignal(
          seam = "FeatureTaskRuntimeGoalContinuationRecorder.reconcileRemediationBaseCoherence",
          valueUsed = failedRef ?: stored.orEmpty(),
          valueExpected = "resolvable review_fix checkpoint ref commit",
          cause = remediationBlockedCause(stored, storedResolves, failedRef),
        ),
        dbOverride = request.dbOverride,
      )
      RemediationBaseBlocked(guidance)
    }
    is RemediationReconciliationHeal -> healRemediationBase(
      RemediationBaseHealRequest(
        target = request.reconciliation.sha,
        stored = stored,
        storedResolves = storedResolves,
        state = state,
        continuation = continuation,
        workflowId = request.workflowId,
        gitOperations = request.gitOperations,
        repoRoot = request.repoRoot,
        dbOverride = request.dbOverride,
        latestRemediationResolved = request.latestRemediationResolved,
      ),
    )
  }
}

private fun FeatureTaskRuntimeRemediationBaseReconciler.healRemediationBase(
  request: RemediationBaseHealRequest,
): RemediationBaseCoherenceResult {
  if (request.target == request.stored) return RemediationBaseCoherent(request.state)
  val reason = remediationBaseHealReason(request.stored, request.target, request.latestRemediationResolved)
  if (!request.storedResolves && request.stored != null) {
    appendRemediationBaseReconciliationEvidence(
      workflowId = request.workflowId,
      recovery = RemediationBaseRecovery(
        originalSha = request.stored,
        replacementSha = request.target,
        reason = reason,
        goalBranch = request.continuation.goalBranch,
        failureMessageOverride =
        "Resume reconciled remediation_base_sha ($reason) through checkpoint ref after stored base missed.",
      ),
      signal = RemediationDegradationSignal(
        seam = "FeatureTaskRuntimeGoalContinuationRecorder.reconcileRemediationBaseCoherence",
        valueUsed = request.stored,
        valueExpected = "resolvable remediation_base_sha commit",
        cause = "stored remediation base did not resolve; reconciled through checkpoint ref",
      ),
      dbOverride = request.dbOverride,
    )
  }
  val healed = persistHealedRemediationBaseState(
    PersistHealedRemediationBaseRequest(
      workflowId = request.workflowId,
      target = request.target,
      stored = request.stored,
      reason = reason,
      continuation = request.continuation,
      gitOperations = request.gitOperations,
      repoRoot = request.repoRoot,
      dbOverride = request.dbOverride,
    ),
  )
  return RemediationBaseCoherent(healed ?: request.state)
}

internal fun FeatureTaskRuntimeRemediationBaseReconciler.remediationBaseReconciliationBlockedGuidance(
  workflowId: String,
  continuation: FeatureTaskRuntimeGoalContinuationArtifact,
  failedRef: String?,
  storedSha: String?,
): String {
  val refDetail = failedRef?.let { "checkpoint ref '$it'" } ?: "stored remediation base"
  val storedDetail = storedSha?.let { " (stored sha '$it' also failed to resolve)" }.orEmpty()
  return "Remediation base reconciliation blocked for workflow '$workflowId' on branch " +
    "'${continuation.goalBranch}': $refDetail could not be resolved to a commit$storedDetail. " +
    "Run `skill-bill goal repair --issue-key ${continuation.issueKey} --subtask ${continuation.subtaskId} " +
    "--apply` to repoint or clear the unreachable remediation base, then resume the goal child."
}

internal fun FeatureTaskRuntimeRemediationBaseReconciler.appendRemediationBaseReconciliationEvidence(
  workflowId: String,
  recovery: RemediationBaseRecovery,
  signal: RemediationDegradationSignal,
  dbOverride: String?,
) {
  database.transaction(dbOverride) { unitOfWork ->
    val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId) ?: return@transaction
    val artifacts = decodeArtifacts(record.artifactsJson)
    val evidenceEntry = remediationBaseRecoveryEvidenceEntry(recovery, signal)
    val priorEvidence = (artifacts[GOAL_REVIEW_BASE_RECOVERIES_ARTIFACT_KEY] as? List<*>).orEmpty()
    patcher.save(
      record,
      unitOfWork.workflowStates,
      mapOf(GOAL_REVIEW_BASE_RECOVERIES_ARTIFACT_KEY to priorEvidence + evidenceEntry),
    )
  }
}
