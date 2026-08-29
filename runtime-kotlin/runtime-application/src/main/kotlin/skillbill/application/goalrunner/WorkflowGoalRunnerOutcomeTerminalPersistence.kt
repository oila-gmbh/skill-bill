package skillbill.application.goalrunner

import skillbill.application.decomposition.decodeArtifacts
import skillbill.application.featuretask.FeatureTaskRuntimeCrashLiveness
import skillbill.application.workflow.WorkflowFamily
import skillbill.contracts.JsonSupport
import skillbill.goalrunner.model.GoalRunnerStoredOutcome
import skillbill.goalrunner.model.GoalRunnerTerminalStatus
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.engine.model.WorkflowUpdateInput
import java.nio.file.Path
import java.time.Instant

internal class WorkflowGoalRunnerOutcomeTerminalPersistence(
  private val engine: WorkflowEngine,
  private val gitOperations: WorkflowGitOperations,
  private val workerSupervisor: FeatureTaskRuntimeWorkerSupervisor,
) {
  fun resolveTerminalOutcome(
    workflowStates: WorkflowStateRepository,
    workflowId: String,
    issueKey: String,
    subtaskId: Int,
    measuredCommitSha: () -> String?,
  ): GoalRunnerStoredOutcome? {
    val candidate = workflowFamilyFor(workflowStates, workflowId)
      ?.let { family -> family.get(workflowStates, workflowId)?.let { snapshot -> family to snapshot } }
    return candidate?.let { (family, snapshot) ->
      engine.snapshotView(family.definition, snapshot)
      val artifacts = decodeArtifacts(snapshot.artifactsJson)
      goalContinuation(artifacts)
        ?.takeIf { it.issueKey == issueKey && it.subtaskId == subtaskId }
        ?.let { continuation -> terminalOutcomeFor(snapshot, artifacts, continuation, measuredCommitSha) }
    }
  }

  fun recoverResolvedCommitPushBlock(
    workflowStates: WorkflowStateRepository,
    identity: GoalSubtaskIdentity,
    repoRoot: Path,
    outcome: GoalRunnerStoredOutcome,
  ): GoalRunnerStoredOutcome? = outcome
    .takeIf { it.status == GoalRunnerTerminalStatus.BLOCKED && it.lastResumableStep == "commit_push" }
    ?.let {
      workflowFamilyFor(workflowStates, identity.workflowId)?.get(workflowStates, identity.workflowId)
    }
    ?.let { record -> goalContinuation(decodeArtifacts(record.artifactsJson)) }
    ?.takeIf { continuation ->
      continuation.issueKey == identity.issueKey && continuation.subtaskId == identity.subtaskId
    }
    ?.goalBranch
    ?.takeIf(String::isNotBlank)
    ?.takeIf { branch -> gitOperations.validateBranchBase(repoRoot, "origin/$branch", "HEAD").ok }
    ?.let { gitOperations.headCommitSha(repoRoot).measuredCommitSha() }
    ?.let { commitSha ->
      GoalRunnerStoredOutcome(
        status = GoalRunnerTerminalStatus.COMPLETE,
        workflowId = identity.workflowId,
        commitSha = commitSha,
        blockedReason = null,
        lastResumableStep = "commit_push",
        suppressPr = outcome.suppressPr,
      )
    }

  @Suppress("ReturnCount")
  fun crashReconcileToResumable(
    workflowStates: WorkflowStateRepository,
    workflowId: String,
    issueKey: String,
    subtaskId: Int,
  ): GoalRunnerStoredOutcome? {
    val ownership = workflowStates.getFeatureTaskRuntimeWorkerOwnership(workflowId) ?: return null
    val row = workflowStates.getFeatureTaskRuntimeWorkflow(workflowId) ?: return null
    if (row.workflowStatus != "running") return null
    val continuation = goalContinuation(decodeArtifacts(row.artifactsJson))
      ?.takeIf { it.issueKey == issueKey && it.subtaskId == subtaskId }
      ?: return null
    val now = Instant.now()
    if (!runCatching { Instant.parse(ownership.expiresAt).isBefore(now) }.getOrDefault(false)) return null
    if (!FeatureTaskRuntimeCrashLiveness.isConfirmedDead(workerSupervisor.inspect(ownership))) return null
    val reconciled = workflowStates.reconcileFeatureTaskRuntimeCrashedWorker(
      workflowId = workflowId,
      ownerToken = ownership.ownerToken,
      generation = ownership.generation,
      interruptionReason = "lease_expired: worker lease expired and process confirmed dead",
      nowInstant = now.toString(),
    )
    if (!reconciled) return null
    return GoalRunnerStoredOutcome(
      status = GoalRunnerTerminalStatus.RECONCILABLE,
      workflowId = workflowId,
      commitSha = null,
      blockedReason = null,
      lastResumableStep = row.currentStepId.ifBlank { "preplan" },
      suppressPr = continuation.suppressPr,
    )
  }

  fun persistMeasuredCompletion(
    workflowStates: WorkflowStateRepository,
    workflowId: String,
    issueKey: String,
    subtaskId: Int,
    outcome: GoalRunnerStoredOutcome,
  ) {
    val recordContext = workflowFamilyFor(workflowStates, workflowId)
      ?.let { family -> family.get(workflowStates, workflowId)?.let { record -> family to record } }
      ?.takeIf { outcome.status == GoalRunnerTerminalStatus.COMPLETE && !outcome.commitSha.isNullOrBlank() }
    recordContext?.let { (family, record) ->
      val artifacts = decodeArtifacts(record.artifactsJson)
      val existingOutcome = goalContinuationOutcome(artifacts, issueKey, subtaskId, outcome.suppressPr)
      val needsBackfill = (existingOutcome == null || existingOutcome.commitSha.isNullOrBlank()) &&
        commitShaFrom(artifacts).isNullOrBlank()
      if (needsBackfill) {
        val updated = engine.updateRecord(
          family.definition,
          record,
          WorkflowUpdateInput(
            workflowStatus = record.workflowStatus,
            currentStepId = record.currentStepId,
            stepUpdates = null,
            artifactsPatch = mapOf(
              "goal_continuation_outcome" to mapOf(
                "issue_key" to issueKey,
                "subtask_id" to subtaskId,
                "status" to "complete",
                "workflow_id" to workflowId,
                "commit_sha" to outcome.commitSha,
                "last_resumable_step" to (outcome.lastResumableStep ?: "commit_push"),
              ),
            ),
            sessionId = record.sessionId.orEmpty(),
          ),
        )
        family.save(workflowStates, updated)
      }
    }
  }

  @Suppress("ReturnCount")
  fun displaceStaleBlockedContinuationOutcomeIfPresent(
    workflowStates: WorkflowStateRepository,
    workflowId: String,
    issueKey: String,
    subtaskId: Int,
  ) {
    val family = workflowFamilyFor(workflowStates, workflowId) ?: return
    val record = family.get(workflowStates, workflowId) ?: return
    val artifacts = decodeArtifacts(record.artifactsJson)
    val continuation = goalContinuation(artifacts)
      ?.takeIf { it.issueKey == issueKey && it.subtaskId == subtaskId }
      ?: return
    val stored = goalContinuationOutcome(artifacts, issueKey, subtaskId, continuation.suppressPr)
      ?.takeIf { it.status == GoalRunnerTerminalStatus.BLOCKED }
      ?: return
    val derived = derivedTerminalOutcomeFor(record, artifacts, continuation) { null }
    if (nonCompleteStoredOutcomeIsCorroborated(stored.copy(workflowId = workflowId), derived, record)) {
      return
    }
    val evidenceAlreadyPresent = artifacts[GOAL_CONTINUATION_OUTCOME_DISPLACEMENT_ARTIFACT_KEY] != null
    val updated = engine.updateRecord(
      family.definition,
      record,
      WorkflowUpdateInput(
        workflowStatus = record.workflowStatus,
        currentStepId = record.currentStepId,
        stepUpdates = null,
        artifactsPatch = buildMap {
          if (!evidenceAlreadyPresent) {
            put(
              GOAL_CONTINUATION_OUTCOME_DISPLACEMENT_ARTIFACT_KEY,
              linkedMapOf(
                "workflow_id" to workflowId,
                "issue_key" to issueKey,
                "subtask_id" to subtaskId,
                "displaced_status" to "blocked",
                "original_blocked_reason" to stored.blockedReason,
                "failed_corroboration" to linkedMapOf(
                  "derived_status" to derived?.status?.toGoalContinuationWireStatus(),
                  "derived_blocked_reason" to derived?.blockedReason,
                  "stored_blocked_reason" to stored.blockedReason,
                ),
                "displaced_at" to Instant.now().toString(),
              ),
            )
          }
          put("goal_continuation_outcome", null)
        },
        sessionId = record.sessionId.orEmpty(),
      ),
    )
    family.save(workflowStates, updated)
  }

  fun recoverMissingResultPrefixTerminalOutcome(
    workflowStates: WorkflowStateRepository,
    family: WorkflowFamily,
    record: WorkflowStateSnapshot,
    output: Map<String, Any?>,
    issueKey: String,
    subtaskId: Int,
    workflowId: String,
  ): GoalRunnerStoredOutcome? {
    val terminalArtifact = missingResultPrefixTerminalOutcomeArtifact(output, issueKey, subtaskId, workflowId)
    val existingArtifacts = decodeArtifacts(record.artifactsJson)
    val artifactsPatch = linkedMapOf<String, Any?>(
      "goal_runner_missing_result_prefix_recovery" to linkedMapOf(
        "issue_key" to issueKey,
        "subtask_id" to subtaskId,
        "workflow_id" to workflowId,
        "output" to output,
      ),
    )
    if (terminalArtifact != null &&
      goalContinuationOutcome(existingArtifacts, issueKey, subtaskId, suppressPr = true) == null
    ) {
      artifactsPatch["goal_continuation_outcome"] = terminalArtifact
    }
    val updated = engine.updateRecord(
      family.definition,
      record,
      WorkflowUpdateInput(
        workflowStatus = record.workflowStatus,
        currentStepId = record.currentStepId,
        stepUpdates = null,
        artifactsPatch = artifactsPatch,
        sessionId = record.sessionId.orEmpty(),
      ),
    )
    family.save(workflowStates, updated)
    val recoveredArtifacts = existingArtifacts + artifactsPatch
    val recoveredContinuation = goalContinuation(recoveredArtifacts)
      ?.takeIf { it.issueKey == issueKey && it.subtaskId == subtaskId }
    val recovered = recoveredContinuation?.let {
      goalContinuationOutcome(recoveredArtifacts, issueKey, subtaskId, it.suppressPr)
    }?.copy(workflowId = workflowId)
    return recovered ?: resolveTerminalOutcome(workflowStates, workflowId, issueKey, subtaskId) { null }
  }
}

internal fun WorkflowGitOperationResult.measuredCommitSha(): String? = value.trim().takeIf { ok && it.isNotBlank() }
