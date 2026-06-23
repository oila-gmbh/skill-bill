package skillbill.application.decomposition

import skillbill.application.model.DecompositionManifestRuntimeUpdate
import skillbill.application.workflow.repoRoot
import skillbill.workflow.model.CurrentSubtaskIntent
import skillbill.workflow.model.DecompositionExecutionModel
import skillbill.workflow.model.DecompositionManifest
import skillbill.workflow.model.DecompositionSubtask
import java.nio.file.Path

private val statusTrackedSteps = setOf("implement", "review", "audit", "validate", "pr_description", "finish")
private val completionSteps = setOf("pr_description", "finish")
private val terminalSkippedSteps = setOf("pr_description", "finish")

internal fun DecompositionSubtask.withRuntimeFields(
  manifest: DecompositionManifest,
  update: DecompositionManifestRuntimeUpdate,
  status: String?,
): DecompositionSubtask {
  val artifacts = mergedArtifacts(update)
  val nextStatus = status ?: this.status
  // On a terminal transition, copy the agent-attribution rollup off the merged goal_continuation_outcome
  // artifact map (loose-map style, mirroring commitShaFrom); non-terminal updates leave it untouched.
  val terminalOutcome = (artifacts["goal_continuation_outcome"] as? Map<*, *>)
    ?.takeIf { nextStatus == "complete" || nextStatus == "blocked" }
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
    blockedReason = blockedReasonFrom(update, nextStatus) ?: blockedReason.takeUnless { nextStatus != "blocked" },
    lastResumableStep = update.currentStepId.takeIf(String::isNotBlank) ?: lastResumableStep,
    finalizingAgentId = terminalOutcome?.get("finalizing_agent_id")?.toString()?.takeIf(String::isNotBlank)
      ?: finalizingAgentId,
    participatingAgentIds = rolledParticipants.ifEmpty { participatingAgentIds },
  )
}

internal fun DecompositionManifest.currentSubtaskIdForUpdate(
  repoRoot: Path,
  update: DecompositionManifestRuntimeUpdate,
): Int? {
  val assessment = mergedArtifacts(update)["assessment"] as? Map<*, *>
  val specPath = assessment?.get("spec_path")?.toString()?.takeIf(String::isNotBlank)
  val matchedId = specPath?.let { matchingSubtaskId(repoRoot, it) }
  return if (specPath != null) {
    matchedId
  } else {
    currentSubtaskIntent.subtaskId.takeIf { it != 0 }
  }
}

internal fun statusFromUpdate(update: DecompositionManifestRuntimeUpdate): String? {
  val stepUpdates = update.stepUpdates.orEmpty()
  return when {
    update.workflowStatus == "blocked" || stepUpdates.any { it["status"] == "blocked" } -> "blocked"
    prSuppressedCommitStatus(update) == "complete" -> "complete"
    prSuppressedCommitStatus(update) == "blocked" -> "blocked"
    stepUpdates.any { it["status"] == "skipped" && it["step_id"] in terminalSkippedSteps } -> "skipped"
    update.workflowStatus == "completed" ||
      stepUpdates.any { it["status"] == "completed" && it["step_id"] in completionSteps } -> "complete"
    update.currentStepId in statusTrackedSteps || stepUpdates.any { it["step_id"] in statusTrackedSteps } ->
      "in_progress"
    else -> null
  }
}

internal fun intentFor(subtaskId: Int, status: String?): CurrentSubtaskIntent = when (status) {
  "blocked" -> CurrentSubtaskIntent(subtaskId = subtaskId, action = "blocked")
  "complete", "skipped" -> CurrentSubtaskIntent(subtaskId = 0, action = "complete")
  "in_progress" -> CurrentSubtaskIntent(subtaskId = subtaskId, action = "resume")
  else -> CurrentSubtaskIntent(subtaskId = subtaskId, action = "start")
}

internal fun DecompositionManifest.withParentStatus(): DecompositionManifest {
  val parentStatus = when {
    subtasks.all { it.status in setOf("complete", "skipped") } -> "complete"
    subtasks.any { it.status == "blocked" } -> "blocked"
    subtasks.any { it.status in setOf("in_progress", "complete", "skipped") || it.hasStarted() } -> "in_progress"
    else -> "pending"
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
  if (status == "blocked") {
    val artifacts = mergedArtifacts(update)
    artifacts["blocked_reason"]?.toString()?.takeIf(String::isNotBlank)
      ?: "Goal-continuation commit_push completed without commit_push_result.commit_sha."
        .takeIf { prSuppressedCommitStatus(update) == "blocked" }
      ?: "Workflow step '${update.currentStepId.ifBlank { "unknown" }}' is blocked."
  } else {
    null
  }

private fun prSuppressedCommitStatus(update: DecompositionManifestRuntimeUpdate): String? {
  val artifacts = mergedArtifacts(update)
  val goalContinuation = artifacts["goal_continuation"] as? Map<*, *> ?: return null
  val suppressPr = goalContinuation["suppress_pr"] == true
  val commitPushResult = artifacts["commit_push_result"] as? Map<*, *>
  val commitPushActive = update.currentStepId == "commit_push" ||
    update.stepUpdates.orEmpty().any { it["step_id"] == "commit_push" }
  val preCommitProjection = commitPushActive &&
    commitPushResult?.get("pre_commit_projection") == true &&
    commitShaFrom(artifacts) == null
  val commitPushCompleted =
    update.stepUpdates.orEmpty().any { it["step_id"] == "commit_push" && it["status"] == "completed" }
  return when {
    !suppressPr -> null
    preCommitProjection -> "complete"
    !commitPushCompleted -> null
    commitShaFrom(artifacts) != null -> "complete"
    else -> "blocked"
  }
}

private fun commitShaFrom(artifacts: Map<String, Any?>): String? {
  val fromCommitPush = (artifacts["commit_push_result"] as? Map<*, *>)
    ?.get("commit_sha")?.toString()?.trim()?.takeIf(String::isNotBlank)
  val fromOutcome = (artifacts["goal_continuation_outcome"] as? Map<*, *>)
    ?.get("commit_sha")?.toString()?.trim()?.takeIf(String::isNotBlank)
  if (fromCommitPush != null && fromOutcome != null && fromCommitPush != fromOutcome) {
    val subtaskId = (artifacts["goal_continuation_outcome"] as? Map<*, *>)?.get("subtask_id")
      ?: (artifacts["goal_continuation"] as? Map<*, *>)?.get("subtask_id")
    error(
      "Conflicting completing commit SHAs for subtask $subtaskId: " +
        "commit_push_result.commit_sha=$fromCommitPush vs goal_continuation_outcome.commit_sha=$fromOutcome.",
    )
  }
  return fromCommitPush ?: fromOutcome
}
