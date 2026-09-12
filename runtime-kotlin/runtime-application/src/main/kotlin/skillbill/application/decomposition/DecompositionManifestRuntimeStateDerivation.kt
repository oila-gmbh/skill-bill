package skillbill.application.decomposition

import skillbill.application.decomposition.model.DecompositionManifestRuntimeUpdate
import skillbill.application.telemetry.normalizedBlockedReason
import skillbill.contracts.SharedPayloadKeys
import skillbill.workflow.decomposition.model.CurrentSubtaskIntent
import skillbill.workflow.decomposition.model.DecompositionExecutionModel
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.model.DecompositionStatus
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.decompositionStatus
import skillbill.workflow.model.workflowStatus
import skillbill.workflow.model.workflowStepStatus
import java.nio.file.Path

// Runtime terminal step is `pr` (FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PR). Keep
// `pr_description` only so legacy GoalRunner lastResumableStep stamps still decode as terminal.
private val statusTrackedSteps = setOf("implement", "review", "audit", "validate", "pr", "pr_description", "finish")
private val completionSteps = setOf("pr", "pr_description", "finish")
private val terminalSkippedSteps = setOf("pr", "pr_description", "finish")

fun DecompositionSubtask.withRuntimeFields(
  manifest: DecompositionManifest,
  update: DecompositionManifestRuntimeUpdate,
  status: String?,
): DecompositionSubtask {
  val artifacts = mergedArtifacts(update)
  val nextStatus = status ?: this.status
  // On a terminal transition, copy the agent-attribution rollup off the merged goal_continuation_outcome
  // artifact map (loose-map style, mirroring commitShaFrom); non-terminal updates leave it untouched.
  val terminalOutcome = (artifacts["goal_continuation_outcome"] as? Map<*, *>)
    ?.takeIf { nextStatus.decompositionStatus() in setOf(DecompositionStatus.COMPLETE, DecompositionStatus.BLOCKED) }
  val rolledParticipants = (terminalOutcome?.get("participating_agent_ids") as? List<*>)
    ?.mapNotNull { it?.toString()?.takeIf(String::isNotBlank) }
    .orEmpty()
  return copy(
    status = nextStatus,
    branch = branchName(artifacts["branch"]).ifBlank {
      when (manifest.executionModel) {
        DecompositionExecutionModel.SAME_BRANCH_COMMIT_PER_SUBTASK -> manifest.featureBranch
        DecompositionExecutionModel.STACKED_BRANCHES ->
          manifest.stackBranches.firstOrNull { it.subtaskId == id }?.branch
      } ?: branch
    },
    workflowId = update.workflowId.ifBlank { workflowId },
    commitSha = commitShaFrom(artifacts) ?: commitSha,
    blockedReason = blockedReasonFrom(update, nextStatus) ?: blockedReason.takeUnless {
      nextStatus.decompositionStatus() != DecompositionStatus.BLOCKED
    },
    lastResumableStep = update.currentStepId.takeIf(String::isNotBlank) ?: lastResumableStep,
    finalizingAgentId = terminalOutcome?.get("finalizing_agent_id")?.toString()?.takeIf(String::isNotBlank)
      ?: finalizingAgentId,
    participatingAgentIds = rolledParticipants.ifEmpty { participatingAgentIds },
  )
}

fun DecompositionManifest.currentSubtaskIdForUpdate(repoRoot: Path, update: DecompositionManifestRuntimeUpdate): Int? {
  val assessment = mergedArtifacts(update)["assessment"] as? Map<*, *>
  val specPath = assessment?.get("spec_path")?.toString()?.takeIf(String::isNotBlank)
  val matchedId = specPath?.let { matchingSubtaskId(repoRoot, it) }
  return if (specPath != null) {
    matchedId
  } else {
    currentSubtaskIntent.subtaskId.takeIf { it != 0 }
  }
}

fun statusFromUpdate(update: DecompositionManifestRuntimeUpdate): String? {
  val stepUpdates = update.stepUpdates.orEmpty()
  val workflowStatus = update.workflowStatus.workflowStatus()
  return when {
    workflowStatus == WorkflowStatus.BLOCKED ||
      stepUpdates.any { it[SharedPayloadKeys.STATUS].workflowStepStatus() == WorkflowStepStatus.BLOCKED } ->
      DecompositionStatus.BLOCKED.wireValue
    prSuppressedCommitStatus(update) == DecompositionStatus.COMPLETE -> DecompositionStatus.COMPLETE.wireValue
    prSuppressedCommitStatus(update) == DecompositionStatus.BLOCKED -> DecompositionStatus.BLOCKED.wireValue
    stepUpdates.any {
      it[SharedPayloadKeys.STATUS].workflowStepStatus() == WorkflowStepStatus.SKIPPED &&
        it[SharedPayloadKeys.STEP_ID] in terminalSkippedSteps
    } -> DecompositionStatus.SKIPPED.wireValue
    workflowStatus == WorkflowStatus.COMPLETED ||
      stepUpdates.any {
        it[SharedPayloadKeys.STATUS].workflowStepStatus() == WorkflowStepStatus.COMPLETED &&
          it[SharedPayloadKeys.STEP_ID] in completionSteps
      } -> DecompositionStatus.COMPLETE.wireValue
    update.currentStepId in statusTrackedSteps ||
      stepUpdates.any { it[SharedPayloadKeys.STEP_ID] in statusTrackedSteps } ->
      DecompositionStatus.IN_PROGRESS.wireValue
    else -> null
  }
}

fun intentFor(subtaskId: Int, status: String?): CurrentSubtaskIntent = when (status.decompositionStatus()) {
  DecompositionStatus.BLOCKED -> CurrentSubtaskIntent(subtaskId = subtaskId, action = "blocked")
  DecompositionStatus.COMPLETE, DecompositionStatus.SKIPPED ->
    CurrentSubtaskIntent(subtaskId = 0, action = "complete")
  DecompositionStatus.IN_PROGRESS -> CurrentSubtaskIntent(subtaskId = subtaskId, action = "resume")
  else -> CurrentSubtaskIntent(subtaskId = subtaskId, action = "start")
}

fun DecompositionManifest.withParentStatus(): DecompositionManifest {
  val parentStatus = when {
    subtasks.all {
      it.status.decompositionStatus() in setOf(DecompositionStatus.COMPLETE, DecompositionStatus.SKIPPED)
    } -> DecompositionStatus.COMPLETE.wireValue
    subtasks.any { it.status.decompositionStatus() == DecompositionStatus.BLOCKED } ->
      DecompositionStatus.BLOCKED.wireValue
    subtasks.any {
      it.status.decompositionStatus() in setOf(
        DecompositionStatus.IN_PROGRESS,
        DecompositionStatus.COMPLETE,
        DecompositionStatus.SKIPPED,
      ) || it.hasStarted()
    } -> DecompositionStatus.IN_PROGRESS.wireValue
    else -> DecompositionStatus.PENDING.wireValue
  }
  return copy(status = parentStatus)
}

private fun DecompositionManifest.matchingSubtaskId(repoRoot: Path, specPath: String): Int? {
  val absoluteSpecPath = resolvedParentSpecPath(repoRoot, Path.of(specPath)).normalize()
  return subtasks.firstOrNull { subtask ->
    resolvedParentSpecPath(repoRoot, Path.of(subtask.specPath)).normalize() == absoluteSpecPath
  }?.id
}

private fun mergedArtifacts(update: DecompositionManifestRuntimeUpdate): Map<String, Any?> =
  LinkedHashMap(update.existingArtifacts).apply { update.artifactsPatch?.let(::putAll) }

private fun blockedReasonFrom(update: DecompositionManifestRuntimeUpdate, status: String): String? =
  if (status.decompositionStatus() == DecompositionStatus.BLOCKED) {
    val artifacts = mergedArtifacts(update)
    val rawReason = artifacts["blocked_reason"]?.toString()?.takeIf(String::isNotBlank)
    when {
      rawReason != null -> normalizedBlockedReason(
        reason = rawReason,
        category = "runtime",
        fallback = "Workflow step '${update.currentStepId.ifBlank { "unknown" }}' is blocked.",
      )
      prSuppressedCommitStatus(update) == DecompositionStatus.BLOCKED -> normalizedBlockedReason(
        reason = null,
        category = "git",
        fallback = "Goal-continuation commit_push completed without commit_push_result.commit_sha.",
      )
      else -> normalizedBlockedReason(
        reason = null,
        category = "runtime",
        fallback = "Workflow step '${update.currentStepId.ifBlank { "unknown" }}' is blocked.",
      )
    }
  } else {
    null
  }

private fun prSuppressedCommitStatus(update: DecompositionManifestRuntimeUpdate): DecompositionStatus? {
  val artifacts = mergedArtifacts(update)
  val goalContinuation = artifacts["goal_continuation"] as? Map<*, *> ?: return null
  val suppressPr = goalContinuation["suppress_pr"] == true
  val commitPushResult = artifacts["commit_push_result"] as? Map<*, *>
  val commitPushActive = update.currentStepId == "commit_push" ||
    update.stepUpdates.orEmpty().any { it[SharedPayloadKeys.STEP_ID] == "commit_push" }
  val preCommitProjection = commitPushActive &&
    commitPushResult?.get("pre_commit_projection") == true &&
    commitShaFrom(artifacts) == null
  val commitPushCompleted =
    update.stepUpdates.orEmpty().any {
      it[SharedPayloadKeys.STEP_ID] == "commit_push" &&
        it[SharedPayloadKeys.STATUS].workflowStepStatus() == WorkflowStepStatus.COMPLETED
    }
  return when {
    !suppressPr -> null
    preCommitProjection -> DecompositionStatus.COMPLETE
    !commitPushCompleted -> null
    commitShaFrom(artifacts) != null -> DecompositionStatus.COMPLETE
    else -> DecompositionStatus.BLOCKED
  }
}

private fun commitShaFrom(artifacts: Map<String, Any?>): String? {
  val fromCommitPush = (artifacts["commit_push_result"] as? Map<*, *>)
    ?.get("commit_sha")?.toString()?.trim()?.takeIf(String::isNotBlank)
  val fromOutcome = (artifacts["goal_continuation_outcome"] as? Map<*, *>)
    ?.get("commit_sha")?.toString()?.trim()?.takeIf(String::isNotBlank)
  if (fromCommitPush != null && fromOutcome != null && fromCommitPush != fromOutcome) {
    val subtaskId = (artifacts["goal_continuation_outcome"] as? Map<*, *>)?.get(SharedPayloadKeys.SUBTASK_ID)
      ?: (artifacts["goal_continuation"] as? Map<*, *>)?.get(SharedPayloadKeys.SUBTASK_ID)
    error(
      "Conflicting completing commit SHAs for subtask $subtaskId: " +
        "commit_push_result.commit_sha=$fromCommitPush vs goal_continuation_outcome.commit_sha=$fromOutcome.",
    )
  }
  return fromCommitPush ?: fromOutcome
}
