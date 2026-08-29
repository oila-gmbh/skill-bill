package skillbill.application.featuretask

import skillbill.application.decomposition.decodeArtifacts
import skillbill.application.workflow.WorkflowFamily
import skillbill.error.InvalidFeatureTaskRuntimeCheckpointIdentityVersionError
import skillbill.error.InvalidGoalSubtaskReviewStateSchemaError
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.resolveCheckpointRef
import skillbill.workflow.goal.model.GOAL_REVIEW_BASE_RECOVERIES_ARTIFACT_KEY
import skillbill.workflow.goal.model.GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY
import skillbill.workflow.goal.model.GoalSubtaskReviewState
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_CHECKPOINT_IDENTITIES_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_CHECKPOINT_REF_NAMESPACE
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCheckpointIdentity
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalContinuationArtifact
import skillbill.workflow.taskruntime.model.featureTaskRuntimeCheckpointIdentitiesFromArtifact
import java.nio.file.Path
import java.time.Instant

private const val CHECKPOINT_IDENTITY_QUARANTINE_ARTIFACT_KEY: String =
  "feature_task_runtime_checkpoint_identities_quarantine"

internal class FeatureTaskRuntimeRemediationBaseReconciler(
  private val database: DatabaseSessionFactory,
  private val patcher: FeatureTaskRuntimeGoalContinuationArtifactPatcher,
) {
  @Suppress("LongMethod", "CyclomaticComplexMethod", "ReturnCount")
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
    return reconcileFromSnapshot(snapshot, workflowId, gitOperations, repoRoot, dbOverride)
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
              "quarantined_at" to Instant.now().toString(),
              "rejected_record" to rejected,
            ),
          ),
        ),
      )
    }
  }

  private fun readRemediationSnapshot(
    workflowId: String,
    dbOverride: String?,
  ): Triple<
    GoalSubtaskReviewState,
    FeatureTaskRuntimeGoalContinuationArtifact,
    List<FeatureTaskRuntimeCheckpointIdentity>,
    >? =
    database.read(dbOverride) { unitOfWork ->
      val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId) ?: return@read null
      val artifacts = decodeArtifacts(record.artifactsJson)
      runCatching {
        val state = reviewStateFromArtifacts(artifacts) ?: return@read null
        val continuation = continuationFromArtifacts(artifacts) ?: return@read null
        val checkpoints = featureTaskRuntimeCheckpointIdentitiesFromArtifact(
          artifacts[FEATURE_TASK_RUNTIME_CHECKPOINT_IDENTITIES_ARTIFACT_KEY],
        )
        Triple(state, continuation, checkpoints)
      }.getOrElse { error ->
        if (error is InvalidGoalSubtaskReviewStateSchemaError) return@read null else throw error
      }
    }

  @Suppress("LongMethod", "CyclomaticComplexMethod", "ReturnCount")
  private fun reconcileFromSnapshot(
    snapshot: Triple<
      GoalSubtaskReviewState,
      FeatureTaskRuntimeGoalContinuationArtifact,
      List<FeatureTaskRuntimeCheckpointIdentity>,
      >,
    workflowId: String,
    gitOperations: WorkflowGitOperations,
    repoRoot: Path,
    dbOverride: String?,
  ): RemediationBaseCoherenceResult {
    val (state, continuation, checkpoints) = snapshot
    if (state.remediationBaseSha == null &&
      checkpoints.none { it.loopId == FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID }
    ) {
      return RemediationBaseCoherent(state)
    }
    val latestRemediationResolved = latestResolvedReviewFixCheckpointCommit(
      checkpoints = checkpoints,
      gitOperations = gitOperations,
      repoRoot = repoRoot,
    )
    val stored = state.remediationBaseSha
    val storedResolves = stored?.let { resolvesCommit(gitOperations, repoRoot, it) } == true
    fun isStrictAncestor(ancestor: String, descendant: String): Boolean {
      if (ancestor == descendant) return false
      val ancestry = gitOperations.isCommitAncestor(repoRoot, ancestor, descendant)
      return ancestry.ok && ancestry.value == "true"
    }
    val reconciliation = when {
      latestRemediationResolved != null &&
        (stored == null || isStrictAncestor(stored, latestRemediationResolved.sha)) ->
        ReconciliationHeal(latestRemediationResolved.sha)
      stored != null && storedResolves -> {
        val head = gitOperations.headCommitSha(repoRoot)
        if (!head.ok || head.value.isBlank()) {
          ReconciliationCoherent
        } else {
          val headSha = head.value.trim()
          val onBranch = gitOperations.isCommitAncestor(repoRoot, stored, headSha)
          when {
            !onBranch.ok -> ReconciliationCoherent
            onBranch.value == "true" -> ReconciliationCoherent
            latestRemediationResolved != null ->
              ReconciliationHeal(latestRemediationResolved.sha)
            else -> ReconciliationBlocked
          }
        }
      }
      latestRemediationResolved != null -> ReconciliationHeal(latestRemediationResolved.sha)
      stored != null && !storedResolves -> ReconciliationBlocked
      else -> ReconciliationBlocked
    }
    when (reconciliation) {
      ReconciliationCoherent -> return RemediationBaseCoherent(state)
      ReconciliationBlocked -> {
        val failedRef = latestReviewFixCheckpointRef(checkpoints)
        val guidance = remediationBaseReconciliationBlockedGuidance(
          workflowId = workflowId,
          continuation = continuation,
          failedRef = failedRef,
          storedSha = stored,
        )
        appendRemediationBaseReconciliationEvidence(
          workflowId = workflowId,
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
          dbOverride = dbOverride,
        )
        return RemediationBaseBlocked(guidance)
      }
      is ReconciliationHeal -> {
        val target = reconciliation.sha
        if (target == stored) return RemediationBaseCoherent(state)
        val reason = when {
          stored == null -> "committed_but_unrecorded"
          latestRemediationResolved != null && latestRemediationResolved.sha == target ->
            "committed_but_unrecorded"
          else -> "recorded_but_superseded"
        }
        if (!storedResolves && stored != null) {
          appendRemediationBaseReconciliationEvidence(
            workflowId = workflowId,
            recovery = RemediationBaseRecovery(
              originalSha = stored,
              replacementSha = target,
              reason = reason,
              goalBranch = continuation.goalBranch,
              failureMessageOverride =
              "Resume reconciled remediation_base_sha ($reason) through checkpoint ref after stored base missed.",
            ),
            signal = RemediationDegradationSignal(
              seam = "FeatureTaskRuntimeGoalContinuationRecorder.reconcileRemediationBaseCoherence",
              valueUsed = stored,
              valueExpected = "resolvable remediation_base_sha commit",
              cause = "stored remediation base did not resolve; reconciled through checkpoint ref",
            ),
            dbOverride = dbOverride,
          )
        }
        val headSha = gitOperations.headCommitSha(repoRoot).value.orEmpty().trim()
        val healed = database.transaction(dbOverride) { unitOfWork ->
          val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId)
            ?: return@transaction null
          val artifacts = decodeArtifacts(record.artifactsJson)
          val latest = reviewStateFromArtifacts(artifacts) ?: return@transaction null
          if (latest.remediationBaseSha == target) return@transaction latest
          val updated = latest.copy(remediationBaseSha = target)
          val evidenceEntry = remediationBaseRecoveryEvidenceEntry(
            RemediationBaseRecovery(
              originalSha = stored,
              replacementSha = target,
              reason = reason,
              goalBranch = continuation.goalBranch,
              headSha = headSha,
            ),
          )
          val priorEvidence = (artifacts[GOAL_REVIEW_BASE_RECOVERIES_ARTIFACT_KEY] as? List<*>).orEmpty()
          patcher.save(
            record,
            unitOfWork.workflowStates,
            mapOf(
              GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY to updated.toArtifactMap(),
              GOAL_REVIEW_BASE_RECOVERIES_ARTIFACT_KEY to priorEvidence + evidenceEntry,
            ),
          )
          updated
        }
        return RemediationBaseCoherent(healed ?: state)
      }
    }
  }

  private sealed interface ReconciliationDecision

  private data object ReconciliationCoherent : ReconciliationDecision

  private data object ReconciliationBlocked : ReconciliationDecision

  private data class ReconciliationHeal(val sha: String) : ReconciliationDecision

  private data class ResolvedReviewFixCheckpoint(val identity: FeatureTaskRuntimeCheckpointIdentity, val sha: String)

  private fun latestResolvedReviewFixCheckpointCommit(
    checkpoints: List<FeatureTaskRuntimeCheckpointIdentity>,
    gitOperations: WorkflowGitOperations,
    repoRoot: Path,
  ): ResolvedReviewFixCheckpoint? = checkpoints
    .asReversed()
    .firstNotNullOfOrNull { identity ->
      if (identity.loopId != FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID) {
        return@firstNotNullOfOrNull null
      }
      resolveCheckpointRefCommit(gitOperations, repoRoot, identity.checkpointRef)
        ?.let { ResolvedReviewFixCheckpoint(identity, it) }
    }

  private fun resolveCheckpointRefCommit(
    gitOperations: WorkflowGitOperations,
    repoRoot: Path,
    checkpointRef: String,
  ): String? {
    val resolved = gitOperations.resolveCheckpointRef(
      repoRoot,
      FEATURE_TASK_RUNTIME_CHECKPOINT_REF_NAMESPACE,
      checkpointRef,
    )
    if (!resolved.ok) return null
    return resolved.value.orEmpty().trim().takeIf(String::isNotBlank)
  }

  private fun resolvesCommit(
    gitOperations: WorkflowGitOperations,
    repoRoot: Path,
    sha: String,
  ): Boolean {
    val resolved = gitOperations.resolveCommit(repoRoot, sha.trim())
    return resolved.ok && resolved.value.orEmpty().trim().isNotBlank()
  }

  private fun remediationBaseReconciliationBlockedGuidance(
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

  private fun appendRemediationBaseReconciliationEvidence(
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
}

private val remediationBlockedCause: (String?, Boolean, String?) -> String = { stored, storedResolves, failedRef ->
  when {
    stored != null && !storedResolves ->
      "stored remediation_base_sha '$stored' did not resolve to a commit"
    failedRef != null ->
      "checkpoint ref '$failedRef' did not resolve to a commit"
    else -> "no review_fix checkpoint ref resolved to a commit"
  }
}

private val latestReviewFixCheckpointRef: (List<FeatureTaskRuntimeCheckpointIdentity>) -> String? = { checkpoints ->
  checkpoints
    .asReversed()
    .firstOrNull { it.loopId == FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID }
    ?.checkpointRef
}

private fun remediationBaseRecoveryEvidenceEntry(
  recovery: RemediationBaseRecovery,
  signal: RemediationDegradationSignal = RemediationDegradationSignal(),
): LinkedHashMap<String, Any?> {
  val failureMessage = recovery.failureMessageOverride ?: run {
    val headDetail = recovery.headSha?.takeIf(String::isNotBlank)?.let { " at HEAD '$it'" }.orEmpty()
    "Resume reconciled remediation_base_sha (${recovery.reason}) so the recorded base stays reachable " +
      "from branch '${recovery.goalBranch}'$headDetail."
  }
  return linkedMapOf<String, Any?>(
    "original_sha" to recovery.originalSha,
    "replacement_sha" to recovery.replacementSha,
    "repointed_field" to GoalReviewBaseField.REMEDIATION_BASE.wireValue,
    "failure_reason" to recovery.reason,
    "failure_message" to failureMessage,
    "goal_branch" to recovery.goalBranch,
  ).also { entry ->
    signal.seam?.let { entry["seam"] = it }
    signal.valueUsed?.let { entry["value_used"] = it }
    signal.valueExpected?.let { entry["value_expected"] = it }
    signal.cause?.let { entry["cause"] = it }
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
